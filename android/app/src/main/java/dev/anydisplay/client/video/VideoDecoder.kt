package dev.anydisplay.client.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import dev.anydisplay.client.Elog
import dev.anydisplay.client.device.DeviceCaps
import dev.anydisplay.client.protocol.StreamConfig
import dev.anydisplay.client.protocol.VideoFrame
import dev.anydisplay.client.session.VideoSink
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Asynchronous MediaCodec decoder tuned for latency: every input is queued as soon as a buffer is free, every output
 * is released for display immediately, and each frame is acknowledged after render or drop.
 * All codec calls run on one handler thread.
 */
class VideoDecoder(private val surface: Surface, private val listener: Listener) : VideoSink {
    interface Listener {
        fun onAck(streamId: Long, frameId: Long, decodeUs: Long, rendered: Boolean, hostPtsUs: Long, decoderId: Long)
        fun onKeyframeNeeded()
        fun onDecoderStarted(name: String, config: StreamConfig)
        fun onDecoderStarting(source: VideoDecoder, streamId: Long, decoderId: Long): Boolean
        fun onDecodeInput(streamId: Long, decoderId: Long)
        fun onDecodeFailure(streamId: Long, decoderId: Long, reason: String, temporary: Boolean)
    }

    private class Pending(val frame: VideoFrame, val receivedNanos: Long)
    private class InFlight(val streamId: Long, val frameId: Long, val hostPtsUs: Long, val queuedNanos: Long)

    private val thread = HandlerThread("anydisplay-decode", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    private var codec: MediaCodec? = null
    private var config: StreamConfig? = null
    private val freeInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<Pending>()
    private val inFlight = LinkedHashMap<Long, InFlight>()
    private var nextPts = 1L
    private var needKeyframe = true
    private var lastKeyframeRequestMs = 0L
    private var released = false
    private var recoveryAttempts = 0
    private var decoderId = 0L


    @Volatile var decoderName = ""
        private set

    override fun onConfig(config: StreamConfig) {
        handler.post {
            if (!released) {
                recoveryAttempts = 0
                start(config)
            }
        }
    }

    override fun onFrame(frame: VideoFrame, receivedNanos: Long) {
        handler.post {
            if (released) {
                listener.onAck(frame.streamId, frame.frameId, 0, false, frame.ptsUs, decoderId)
                return@post
            }
            enqueue(Pending(frame, receivedNanos))
        }
    }

    private fun enqueue(p: Pending) {
        val cfg = config
        if (cfg == null || codec == null || p.frame.streamId != cfg.streamId) {
            drop(p.frame)
            return
        }
        if (needKeyframe) {
            if (!p.frame.keyframe) {
                drop(p.frame)
                requestKeyframe()
                return
            }
            needKeyframe = false
        }
        pending.addLast(p)
        feed()
    }

    private fun feed() {
        val c = codec ?: return
        while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
            val p = pending.removeFirst()
            val index = freeInputs.removeFirst()
            val f = p.frame
            try {
                val buf = c.getInputBuffer(index) ?: throw IllegalStateException("no input buffer")
                if (f.dataLength > buf.capacity()) throw IllegalStateException("frame ${f.dataLength} B > buffer ${buf.capacity()} B")
                buf.clear()
                buf.put(f.buffer, f.dataOffset, f.dataLength)
                val pts = nextPts++
                inFlight[pts] = InFlight(f.streamId, f.frameId, f.ptsUs, System.nanoTime())
                c.queueInputBuffer(index, 0, f.dataLength, pts, 0)
                listener.onDecodeInput(f.streamId, decoderId)
            } catch (e: Exception) {
                Elog.w("queueInputBuffer failed: ${e.message}")
                drop(f)
                recoverException(e, "input error")
                return
            }
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (c !== codec) return
            freeInputs.addLast(index)
            feed()
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec) return
            val now = System.nanoTime()
            // Older entries never came out: the decoder skipped them.
            val it = inFlight.entries.iterator()
            var match: InFlight? = null
            while (it.hasNext()) {
                val e = it.next()
                if (e.key < info.presentationTimeUs) {
                    listener.onAck(e.value.streamId, e.value.frameId, 0, false, e.value.hostPtsUs, decoderId)
                    it.remove()
                } else {
                    if (e.key == info.presentationTimeUs) { match = e.value; it.remove() }
                    break
                }
            }
            val render = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
            try {
                // An explicit "now" timestamp lets SurfaceFlinger drop a stale frame when a newer one is queued for the
                // same vsync. With releaseOutputBuffer(index, true) the decoder blocks on the display's buffer queue,
                // so any burst leaves frames queued for good (measured: 44 ms instead of 7 ms per frame).
                if (render) c.releaseOutputBuffer(index, System.nanoTime()) else c.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Elog.w("releaseOutputBuffer failed: ${e.message}")
                match?.let { listener.onAck(it.streamId, it.frameId, 0, false, it.hostPtsUs, decoderId) }
                recoverException(e, "output release error")
                return
            }
            match?.let { listener.onAck(it.streamId, it.frameId, (now - it.queuedNanos) / 1000, render, it.hostPtsUs, decoderId) }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (c !== codec) return
            Elog.w("decoder error: ${e.diagnosticInfo} recoverable=${e.isRecoverable} transient=${e.isTransient}")
            recoverException(e, "codec error")
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            if (c === codec) Elog.i("decoder output format: $format")
        }
    }

    private fun start(cfg: StreamConfig) {
        stopCodec()
        config = cfg
        decoderId = nextDecoderId.incrementAndGet()
        if (!listener.onDecoderStarting(this, cfg.streamId, decoderId)) return
        val choice = DeviceCaps.decoders()[cfg.codec]
        val mime = DeviceCaps.mimeOf(cfg.codec)
        val attempts = listOf(true to true, false to true, false to false)
        var lastError: Exception? = null
        for ((vendorKeys, tuning) in attempts) {
            var c: MediaCodec? = null
            try {
                c = if (choice != null) MediaCodec.createByCodecName(choice.name) else MediaCodec.createDecoderByType(mime)
                c.setCallback(callback, handler)
                c.configure(format(cfg, mime, c.name, vendorKeys, tuning), surface, null, 0)
                c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                codec = c
                c.start()
                decoderName = c.name
                needKeyframe = true
                Elog.i("decoder ${c.name} started ${cfg.width}x${cfg.height} ${mime} vendorKeys=$vendorKeys tuning=$tuning")
                listener.onDecoderStarted(c.name, cfg)
                return
            } catch (e: Exception) {
                lastError = e
                Elog.w("decoder configure failed (vendorKeys=$vendorKeys tuning=$tuning): ${e.message}")
                codec = null
                try { c?.release() } catch (_: Exception) {}
                if (temporary(e)) {
                    reportFailure("decoder resource failure: ${e.message}", true)
                    return
                }
            }
        }
        Elog.e("no decoder could be started for $mime ${cfg.width}x${cfg.height}")
        reportFailure("cannot initialize $mime ${cfg.width}x${cfg.height}: ${lastError?.message}", false)
    }

    private fun temporary(e: Exception) = e is MediaCodec.CodecException &&
        (e.isTransient || e.errorCode == MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE ||
            e.errorCode == MediaCodec.CodecException.ERROR_RECLAIMED)

    private fun recoverException(e: Exception, where: String) {
        if (temporary(e)) reportFailure("$where: decoder resource failure: ${e.message}", true)
        else reset("$where: ${e.message}")
    }

    private fun reportFailure(reason: String, temporary: Boolean) {
        val cfg = config ?: return
        stopCodec()
        listener.onDecodeFailure(cfg.streamId, decoderId, reason, temporary)
    }

    private fun format(cfg: StreamConfig, mime: String, name: String, vendorKeys: Boolean, tuning: Boolean): MediaFormat {
        val f = MediaFormat.createVideoFormat(mime, cfg.width, cfg.height)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(cfg.width * cfg.height * 3 / 2, 2 * 1024 * 1024))
        if (tuning) {
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            f.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            f.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        }
        if (vendorKeys) {
            val n = name.lowercase()
            when {
                n.contains("qcom") || n.contains("qti") -> {
                    f.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                    f.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
                }
                n.contains("hisi") || n.contains("hisilicon") -> {
                    f.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
                    f.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1)
                }
                n.contains("exynos") -> f.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                n.contains("amlogic") -> f.setInteger("vendor.low-latency.enable", 1)
                n.contains("mtk") || n.contains("mediatek") -> f.setInteger("vdec-lowlatency", 1)
            }
        }
        return f
    }

    /** Drops queued work, restarts the codec, and asks the host for a keyframe. */
    private fun reset(why: String) {
        val cfg = config ?: return
        if (recoveryAttempts++ >= 1) {
            reportFailure("decoder failed after recovery: $why", false)
            return
        }
        Elog.i("decoder reset: $why")
        start(cfg)
        if (codec != null) requestKeyframe(force = true)
    }

    private fun requestKeyframe(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastKeyframeRequestMs > 500) {
            lastKeyframeRequestMs = now
            listener.onKeyframeNeeded()
        }
    }

    private fun drop(f: VideoFrame) = listener.onAck(f.streamId, f.frameId, 0, false, f.ptsUs, decoderId)

    private fun stopCodec() {
        val c = codec
        codec = null
        if (c != null) {
            try { c.stop() } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
        }
        freeInputs.clear()
        for (p in pending) drop(p.frame)
        pending.clear()
        for (e in inFlight.values) listener.onAck(e.streamId, e.frameId, 0, false, e.hostPtsUs, decoderId)
        inFlight.clear()
    }

    /** Releases the codec and waits until the decode thread has stopped. */
    fun release() {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            released = true
            stopCodec()
            done.countDown()
        }
        done.await(1, java.util.concurrent.TimeUnit.SECONDS)
        thread.quitSafely()
    }

    companion object {
        private val nextDecoderId = AtomicLong()
    }
}
