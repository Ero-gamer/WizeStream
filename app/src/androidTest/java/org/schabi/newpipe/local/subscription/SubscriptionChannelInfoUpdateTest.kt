package org.schabi.newpipe.local.subscription

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

@RunWith(AndroidJUnit4::class)
class SubscriptionChannelInfoUpdateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "subscription-metadata-${UUID.randomUUID()}"
    private val writer = Executors.newSingleThreadExecutor()
    private val databaseField = NewPipeDatabase::class.java
        .getDeclaredField("databaseInstance").apply { isAccessible = true }
    private var previousDatabase: Any? = null
    private lateinit var database: AppDatabase
    private lateinit var manager: SubscriptionManager

    @Before
    fun setUp() {
        // Use the app's disk/WAL configuration and real Room executors, not trampoline schedulers.
        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        previousDatabase = databaseField.get(null)
        databaseField.set(null, database)
        manager = SubscriptionManager(context, SubscriptionEntity.DEFAULT_PROFILE_ID)
    }

    @After
    fun tearDown() {
        writer.shutdownNow()
        try {
            assertTrue(writer.awaitTermination(10, SECONDS))
        } finally {
            databaseField.set(null, previousDatabase)
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun refreshUpdatesOnlyMetadataForTheExactProfileServiceAndUrl() {
        val original = insert()
        val untouched = listOf(
            insert(original.copy(uid = 0, profileId = "other-profile")),
            insert(original.copy(uid = 0, serviceId = 1)),
            // A channel id is case sensitive, and '_' must not act as a LIKE wildcard.
            insert(original.copy(uid = 0, url = URL.replace("_", "x"))),
            insert(original.copy(uid = 0, url = URL.lowercase()))
        )

        manager.updateChannelInfo(channel()).test()
            .awaitDone(10, SECONDS).assertComplete().assertNoErrors()

        assertEquals(
            original.copy(
                name = "Updated channel",
                avatarUrl = AVATAR_URL,
                description = "Updated description",
                subscriberCount = 123
            ),
            stored()
        )
        untouched.forEach {
            assertEquals(it, database.subscriptionDAO().getSubscription(it.uid))
        }
    }

    @Test
    fun refreshIsDeferredAndDoesNotSubscribeToMissingChannels() {
        val refresh = manager.updateChannelInfo(channel())
        refresh.test().awaitDone(10, SECONDS).assertComplete().assertNoErrors()
        assertTrue(database.subscriptionDAO().getAllDirect().isEmpty())

        val original = insert()
        val pending = manager.updateChannelInfo(channel())
        assertEquals(original, stored())
        database.subscriptionDAO().delete(original)
        pending.test().awaitDone(10, SECONDS).assertComplete().assertNoErrors()
        assertTrue(database.subscriptionDAO().getAllDirect().isEmpty())
    }

    @Test
    fun refreshCanBeChainedFromARoomQueryCallback() {
        insert()
        database.subscriptionDAO()
            .getSubscriptionForProfile(SubscriptionEntity.DEFAULT_PROFILE_ID, 0, URL)
            .flatMapCompletable { manager.updateChannelInfo(channel()) }
            .andThen(manager.updateChannelInfo(channel("Second refresh")))
            .test().awaitDone(10, SECONDS).assertComplete().assertNoErrors()

        assertEquals("Second refresh", stored().name)
        assertFalse(database.inTransaction())
    }

    @Test
    fun refreshPreservesSettingsCommittedByAConcurrentWriter() {
        val original = insert()
        refreshWhileWriting {
            database.subscriptionDAO().update(
                original.copy(
                    notificationMode = 0,
                    notificationKeywords = "new keywords",
                    youtubeModeMask = SubscriptionEntity.YOUTUBE_MODE_MUSIC
                )
            )
        }

        assertEquals("Updated channel", stored().name)
        assertEquals(0, stored().notificationMode)
        assertEquals("new keywords", stored().notificationKeywords)
        assertEquals(SubscriptionEntity.YOUTUBE_MODE_MUSIC, stored().youtubeModeMask)
        assertEquals(original.uid, stored().uid)
    }

    @Test
    fun refreshDoesNotRestoreAConcurrentlyDeletedSubscription() {
        val original = insert()
        refreshWhileWriting { database.subscriptionDAO().delete(original) }
        assertTrue(database.subscriptionDAO().getAllDirect().isEmpty())
    }

    @Test
    fun failedWriteReportsTheErrorAndAllowsTheNextRefresh() {
        val original = insert()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_metadata_update BEFORE UPDATE ON subscriptions " +
                "BEGIN SELECT RAISE(ABORT, 'metadata write fixture'); END"
        )
        try {
            manager.updateChannelInfo(channel()).test().awaitDone(10, SECONDS)
                .assertError(SQLiteException::class.java).assertNotComplete()
            assertEquals(original, stored())
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_metadata_update")
        }

        manager.updateChannelInfo(channel()).test()
            .awaitDone(10, SECONDS).assertComplete().assertNoErrors()
        assertEquals("Updated channel", stored().name)
    }

    private fun refreshWhileWriting(write: () -> Unit) {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val task = writer.submit {
            database.runInTransaction {
                write()
                writing.countDown()
                check(release.await(10, SECONDS)) { "Writer was not released" }
            }
        }
        try {
            assertTrue("Writer did not acquire its transaction", writing.await(10, SECONDS))
            val refresh = manager.updateChannelInfo(channel()).test()
            release.countDown()
            task.get(10, SECONDS)
            refresh.awaitDone(10, SECONDS).assertComplete().assertNoErrors()
        } finally {
            release.countDown()
        }
    }

    private fun insert(
        entity: SubscriptionEntity = SubscriptionEntity(
            serviceId = 0,
            url = URL,
            name = "Original channel",
            avatarUrl = "https://example.com/original.jpg",
            description = "Original description",
            subscriberCount = 10,
            notificationMode = 1,
            notificationKeywords = "lessons",
            youtubeModeMask = SubscriptionEntity.YOUTUBE_MODE_ALL
        )
    ): SubscriptionEntity = entity.apply { uid = database.subscriptionDAO().insert(this) }

    private fun stored(): SubscriptionEntity = requireNotNull(
        database.subscriptionDAO().getSubscriptionDirectForProfile(
            SubscriptionEntity.DEFAULT_PROFILE_ID,
            0,
            URL
        )
    )

    private fun channel(name: String = "Updated channel"): ChannelInfo {
        val link = ListLinkHandler(URL, URL, "fixture", emptyList(), emptyList())
        return ChannelInfo(0, "fixture", URL, URL, name, link).apply {
            avatars = listOf(Image(AVATAR_URL, 100, 100, Image.ResolutionLevel.MEDIUM))
            description = "Updated description"
            subscriberCount = 123
        }
    }

    companion object {
        private const val URL = "https://www.youtube.com/channel/UC_metadata"
        private const val AVATAR_URL = "https://example.com/updated.jpg"
    }
}
