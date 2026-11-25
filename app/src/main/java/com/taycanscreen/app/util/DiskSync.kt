package com.taycanscreen.app.util

import com.taycanscreen.app.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Spotify keeps losing its sign-in on the box (12, 14, 17, 24 Nov). On 24 Nov it happened twice, each
 * time after a park where the box lost power with Spotify playing, and the next start was signed out
 * before the box was even online: Spotify's saved sign-in was lost on the box itself. The box loses
 * power without warning at every switch-off, and Android holds recent writes in memory for a while
 * before they reach storage, so whatever Spotify saved last can vanish.
 *
 * This asks the box every few seconds to write everything waiting to storage (the standard `sync`
 * command; it needs no special rights and changes nothing). It can't make a write that's cut off
 * halfway survive, but it shrinks the window in which a finished save is lost from up to a minute
 * to a few seconds.
 */
object DiskSync {
    private const val TAG = "sync"
    private const val EVERY_MS = 5_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            var reported = false
            var failures = 0
            while (true) {
                val t0 = Mono.ms()
                val result = runCatching {
                    val p = ProcessBuilder("sync").redirectErrorStream(true).start()
                    val done = p.waitFor(10, TimeUnit.SECONDS)
                    if (!done) { p.destroy(); -1 } else p.exitValue()
                }
                val ms = Mono.ms() - t0
                val ok = result.getOrNull() == 0
                if (!reported) {
                    reported = true
                    AppLog.info(TAG, if (ok) "Writing the box's storage every ${EVERY_MS / 1000} s (first sync took $ms ms)"
                        else "The box refused sync (${result.exceptionOrNull()?.message ?: "exit ${result.getOrNull()}"}); not retrying often")
                }
                if (!ok && ++failures >= 3) return@launch // not available on this box: stop quietly
                if (ok && ms > 2_000) AppLog.warn(TAG, "Sync took $ms ms")
                delay(EVERY_MS)
            }
        }
    }
}
