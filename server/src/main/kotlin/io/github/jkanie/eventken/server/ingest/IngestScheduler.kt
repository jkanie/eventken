package io.github.jkanie.eventken.server.ingest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [IngestService.ingestAll] every [interval]. The next run waits for the
 * previous 1 to finish, so 2 runs never overlap, however slow the feeds are.
 */
class IngestScheduler(
    private val ingest: suspend () -> Unit,
    private val interval: Duration,
    private val firstDelay: Duration = 10.seconds,
) {
    private val log = LoggerFactory.getLogger(IngestScheduler::class.java)

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            delay(firstDelay)
            while (isActive) {
                try {
                    ingest()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Scheduled ingest failed", e)
                }
                delay(interval)
            }
        }
}
