package io.github.jkanie.eventken.android

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.jkanie.eventken.android.reminders.ReminderScheduler
import io.github.jkanie.eventken.android.reminders.ReminderWorker
import io.github.jkanie.eventken.shared.model.EventSummary
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class RemindersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val concert =
        EventSummary(
            id = 1,
            title = "Harbour Lights Summer Concert",
            startsAt = Instant.parse("2027-07-11T02:30:00Z"),
            timeZone = "America/Vancouver",
        )
    private lateinit var scheduler: ReminderScheduler

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler =
            ReminderScheduler(context, FixedClock(Instant.parse("2027-07-01T00:00:00Z")), zone = { TimeZone.of("America/Vancouver") })
    }

    private fun work() = WorkManager.getInstance(context).getWorkInfosByTag(ReminderScheduler.tag(1)).get()

    @Test
    fun schedulesTheSharedRulesAsWork() {
        val plan = scheduler.schedule(concert)
        assertEquals(listOf(Instant.parse("2027-07-10T01:00:00Z"), Instant.parse("2027-07-11T01:30:00Z")), plan.map { it.at })
        val infos = work()
        assertEquals(2, infos.size)
        assertTrue(infos.all { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun schedulingAgainReplacesAndCancelStops() {
        scheduler.schedule(concert)
        scheduler.schedule(concert)
        assertEquals(2, work().count { it.state == WorkInfo.State.ENQUEUED })
        scheduler.cancel(1)
        assertTrue(work().none { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun theWorkerPostsANotification() =
        runBlocking {
            shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val worker =
                TestListenableWorkerBuilder<ReminderWorker>(context)
                    .setInputData(
                        workDataOf(
                            ReminderWorker.KEY_EVENT_ID to 1L,
                            ReminderWorker.KEY_TITLE to "Harbour Lights Summer Concert",
                            ReminderWorker.KEY_TEXT to "Sat 10 Jul 2027, 19:30",
                        ),
                    ).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
            assertEquals(1, posted.size)
            assertEquals("Harbour Lights Summer Concert", shadowOf(posted.single()).contentTitle)
        }

    @Test
    fun theWorkerFailsWithoutAnEvent() =
        runBlocking {
            val worker = TestListenableWorkerBuilder<ReminderWorker>(context).build()
            assertEquals(ListenableWorker.Result.failure(), worker.doWork())
        }
}
