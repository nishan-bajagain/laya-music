package ca.ilianokokoro.umihi.music.core.helpers

import android.util.Log
import ca.ilianokokoro.umihi.music.BuildConfig
import kotlin.time.measureTimedValue

object LogHelper {
    const val TAG = "UmihiPrint"

    /**
     * Measures and logs the execution time of [block].
     * Only active in debug builds — no performance overhead in release.
     */
    inline fun <T> benchmark(
        label: String,
        block: () -> T
    ): T {
        val result = measureTimedValue(block)
        if (BuildConfig.DEBUG) {
            Log.d("UmihiBench", "$label: ${result.duration.inWholeMilliseconds} ms")
        }
        return result.value
    }

    /**
     * Debug log — stripped from release builds automatically by R8/ProGuard,
     * and also gated on [BuildConfig.DEBUG] as a belt-and-suspenders guard.
     */
    fun printd(message: String, tag: String = TAG) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    /**
     * Error log — always active so production crashes remain diagnosable.
     */
    fun printe(message: String, tag: String = TAG, exception: Exception? = null) {
        Log.e(tag, message, exception)
    }
}
