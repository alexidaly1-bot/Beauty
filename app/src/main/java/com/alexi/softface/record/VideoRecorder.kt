package com.alexi.softface.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor

/**
 * Records what you see (with the look) to MP4: H.264 video from a GL surface + AAC audio.
 * Video and audio use the same clock (System.nanoTime).
 */
class VideoRecorder(width: Int, height: Int, fd: FileDescriptor, private val withAudio: Boolean) {
    // Encoders like sizes that are multiples of 16.
    val width = width / 16 * 16
    val height = height / 16 * 16

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val video: MediaCodec
    val inputSurface: Surface
    private var audio: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    @Volatile private var stopping = false

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, this.width, this.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (this@VideoRecorder.width * this@VideoRecorder.height * 30 * 0.17f).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        video.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = video.createInputSurface()
        if (withAudio) setupAudio()
    }

    @SuppressLint("MissingPermission")
    private fun setupAudio() {
        try {
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audio = enc
            audioRecord = rec
        } catch (t: Throwable) {
            Log.w(TAG, "no audio", t)
            audio = null; audioRecord = null
        }
    }

    fun start() {
        video.start()
        videoThread = Thread({ drainVideo() }, "SoftFaceVideo").apply { start() }
        val a = audio
        val r = audioRecord
        if (a != null && r != null) {
            a.start()
            r.startRecording()
            audioThread = Thread({ audioLoop(a, r) }, "SoftFaceAudio").apply { start() }
        }
    }

    /** Blocks until the file is finished. Call off the main thread. */
    fun stop() {
        stopping = true
        runCatching { video.signalEndOfInputStream() }
        videoThread?.join(3000)
        audioThread?.join(3000)
        runCatching { video.stop() }; video.release()
        audio?.let { runCatching { it.stop() }; it.release() }
        audioRecord?.let { runCatching { it.stop() }; it.release() }
        inputSurface.release()
        synchronized(lock) {
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun tryStartMuxer() {
        val needAudio = audio != null
        if (!muxerStarted && videoTrack >= 0 && (!needAudio || audioTrack >= 0)) {
            muxer.start()
            muxerStarted = true
        }
    }

    private fun write(track: Int, buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (muxerStarted && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                buf.position(info.offset); buf.limit(info.offset + info.size)
                muxer.writeSampleData(track, buf, info)
            }
        }
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = try { video.dequeueOutputBuffer(info, 10_000) } catch (t: Throwable) { break }
            when {
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    videoTrack = muxer.addTrack(video.outputFormat); tryStartMuxer()
                }
                i >= 0 -> {
                    video.getOutputBuffer(i)?.let { write(videoTrack, it, info) }
                    video.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun audioLoop(enc: MediaCodec, rec: AudioRecord) {
        val info = MediaCodec.BufferInfo()
        var eosSent = false
        while (true) {
            if (!eosSent) {
                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = enc.getInputBuffer(inIdx)!!
                    buf.clear()
                    val n = rec.read(buf, buf.capacity())
                    val pts = System.nanoTime() / 1000
                    if (stopping) {
                        enc.queueInputBuffer(inIdx, 0, maxOf(n, 0), pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                    } else {
                        enc.queueInputBuffer(inIdx, 0, maxOf(n, 0), pts, 0)
                    }
                }
            }
            val o = enc.dequeueOutputBuffer(info, 10_000)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    audioTrack = muxer.addTrack(enc.outputFormat); tryStartMuxer()
                }
                o >= 0 -> {
                    enc.getOutputBuffer(o)?.let { write(audioTrack, it, info) }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    companion object {
        private const val TAG = "VideoRecorder"
        private const val SAMPLE_RATE = 44100
    }
}
