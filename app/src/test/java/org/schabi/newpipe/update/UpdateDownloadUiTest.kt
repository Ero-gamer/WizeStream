package org.schabi.newpipe.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.schabi.newpipe.R
import org.schabi.newpipe.settings.SettingsActivity
import org.schabi.newpipe.settings.UpdateSettingsFragment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateDownloadUiTest {
    @Test
    fun cancelButtonAndDialogCancellationBothCancelTheWork() {
        listOf(false, true).forEach { cancelButton ->
            withDownload { manager, workId, _ ->
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                if (cancelButton) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() else dialog.cancel()
                shadowOf(Looper.getMainLooper()).idle()
                verify(manager).cancelWorkById(workId)
                assertFalse(dialog.isShowing)
            }
        }
    }

    @Test
    fun destroyingAndRestoringTheViewKeepsTheDownloadAttached() {
        withDownload { manager, workId, activity ->
            val fragment = activity.supportFragmentManager.fragments.single()
            val previous = ShadowDialog.getLatestDialog() as AlertDialog
            activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
            assertFalse(previous.isShowing)
            verify(manager, never()).cancelWorkById(workId)
            activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            val restored = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(restored.isShowing)
            restored.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            verify(manager).cancelWorkById(workId)
        }
    }

    @Test
    fun completionNotificationReopensTheMatchingDownload() {
        val context = RuntimeEnvironment.getApplication()
        val workId = UUID.randomUUID()
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        UpdateDownloadNotifications.completed(context, workId, "1.32.0")
        val notification = notifications.allNotifications.single()
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(SettingsActivity::class.java.name, intent.component!!.className)
        assertTrue(intent.getBooleanExtra(SettingsActivity.EXTRA_OPEN_UPDATE_SETTINGS, false))
        assertEquals(workId.toString(), intent.getStringExtra(UpdateDownloadNotifications.EXTRA_WORK_ID))
        UpdateDownloadNotifications.dismissCompleted(context, workId)
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test
    @Config(sdk = [33])
    fun completionWithoutNotificationPermissionDoesNotFail() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        UpdateDownloadNotifications.completed(context, UUID.randomUUID(), "1.32.0")
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @SuppressLint("RestrictedApi") // WorkManager's delegate keeps these UI tests off real workers.
    private fun withDownload(assertions: (WorkManager, UUID, FragmentActivity) -> Unit) {
        val workId = UUID.randomUUID()
        val manager = mock(WorkManagerImpl::class.java)
        `when`(manager.getWorkInfoByIdLiveData(workId)).thenReturn(MutableLiveData<WorkInfo?>())
        val previous = WorkManagerImpl.getInstance()
        WorkManagerImpl.setDelegate(manager)
        try {
            Robolectric.buildActivity(FragmentActivity::class.java).use { controller ->
                val activity = controller.get()
                activity.setTheme(R.style.LightTheme)
                activity.intent = Intent().putExtra(UpdateDownloadNotifications.EXTRA_WORK_ID, workId.toString())
                controller.setup().visible()
                activity.supportFragmentManager.beginTransaction()
                    .add(android.R.id.content, UpdateSettingsFragment()).commitNow()
                shadowOf(Looper.getMainLooper()).idle()
                assertions(manager, workId, activity)
            }
        } finally {
            WorkManagerImpl.setDelegate(previous)
        }
    }
}
