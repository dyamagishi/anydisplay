package dev.anydisplay.client.video

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anydisplay.client.data.Settings
import dev.anydisplay.client.session.Session
import dev.anydisplay.client.session.SessionHub
import dev.anydisplay.client.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in end-to-end test: a real Mac/Windows host must already be discovering this USB/Wi-Fi client. */
@RunWith(AndroidJUnit4::class)
class DecodeNegotiationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val main = Handler(Looper.getMainLooper())

    private fun await(message: String, condition: () -> Boolean) {
        val latch = CountDownLatch(1)
        val deadline = SystemClock.elapsedRealtime() + 20_000
        val check = object : Runnable {
            override fun run() {
                if (condition()) latch.countDown()
                else if (SystemClock.elapsedRealtime() < deadline) main.postDelayed(this, 25)
            }
        }
        main.post(check)
        try { assertTrue(message, latch.await(21, TimeUnit.SECONDS)) }
        finally { main.removeCallbacks(check) }
        Log.i("DecodeDeviceTest", message)
    }

    private fun playing(): Session? = SessionHub.session?.takeIf { it.connected && it.decodeConfirmed }

    @Test
    fun liveHostRecoversRotatesAndStopsAfterFiniteFailures() {
        assumeTrue("Requires a live host; enable with -e liveHost true",
            InstrumentationRegistry.getArguments().getString("liveHost") == "true")
        val context = instrumentation.targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val oldOrientation = prefs.getString(Settings.K_ORIENTATION, null)
        prefs.edit().putString(Settings.K_ORIENTATION, "portrait").commit()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await("initial portrait produced actual decoded frames") { playing()?.config?.let { it.height > it.width } == true }
            val s = playing()!!
            val initial = s.config!!
            Log.i("DecodeDeviceTest", "initial=${initial.width}x${initial.height} request=${s.lastRequest}")

            val beforeFailure = s.totalRendered.get()
            val initialDecoder = s.decoderId!!
            s.onDecodeFailure(initial.streamId, initialDecoder, "instrumentation simulated decoder failure", false)
            await("failure renegotiated a smaller stream and rendered frames") {
                val c = s.config
                c != null && c.streamId != initial.streamId && c.width < initial.width &&
                    c.height < initial.height && s.decodeConfirmed && s.totalRendered.get() > beforeFailure
            }
            val request = s.lastRequest
            s.onDecodeFailure(initial.streamId, initialDecoder, "late error from old stream", false)
            assertEquals("old stream must not change the request", request, s.lastRequest)

            val beforeRotation = s.totalRendered.get()
            prefs.edit().putString(Settings.K_ORIENTATION, "landscape").commit()
            s.refreshConfigRequest()
            await("landscape settings reset negotiation and rendered frames") {
                s.config?.let { it.width > it.height } == true && s.decodeConfirmed && s.totalRendered.get() > beforeRotation
            }
            val beforePortrait = s.totalRendered.get()
            prefs.edit().putString(Settings.K_ORIENTATION, "portrait").commit()
            s.refreshConfigRequest()
            await("portrait settings restored the original size and rendered frames") {
                s.config?.let { it.width == initial.width && it.height == initial.height } == true &&
                    s.decodeConfirmed && s.totalRendered.get() > beforePortrait
            }

            // The reset starts a fresh four-attempt series. Exercise the real Session/host path.
            for (attempt in 1..4) {
                val current = s.config!!
                val frames = s.totalRendered.get()
                s.onDecodeFailure(current.streamId, s.decoderId!!, "instrumentation exhaustion $attempt", false)
                if (attempt < 4) await("bounded recovery attempt $attempt rendered a new stream") {
                    s.config?.streamId != current.streamId && s.decodeConfirmed && s.totalRendered.get() > frames
                }
            }
            await("four failed sizes closed the session") { !s.connected }
            assertTrue(s.endMessage.orEmpty().contains("4 sizes"))

            await("host reconnected and rendering resumed") { playing()?.let { it !== s } == true }
            val next = playing()!!
            val beforeResourceFailure = next.lastRequest
            next.onDecodeFailure(next.config!!.streamId, next.decoderId!!, "instrumentation temporary resource shortage", true)
            await("resource shortage closed without a resolution fallback") { !next.connected }
            assertEquals(beforeResourceFailure, next.lastRequest)
            assertTrue(next.endMessage.orEmpty().contains("resource shortage"))
            await("final reconnect produced decoded frames") { playing()?.let { it !== next } == true }
        } finally {
            prefs.edit().apply {
                if (oldOrientation == null) remove(Settings.K_ORIENTATION)
                else putString(Settings.K_ORIENTATION, oldOrientation)
            }.commit()
            SessionHub.session?.refreshConfigRequest()
        }
    }
}
