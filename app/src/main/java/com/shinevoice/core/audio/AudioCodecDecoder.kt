package com.shinevoice.core.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.AudioFormat
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes common container/audio formats (MP3, M4A/AAC, OGG, WAV fallback via
 * MonoWavReader) into mono 16-bit [PcmAudio] using MediaCodec. Pure WAV files
 * are handled by [MonoWavReader] to avoid codec startup cost on the common path.
 *
 * Lifecycle follows the documented contract strictly: create -> configure ->
 * start, and only THEN pull input/output buffers per dequeue call. The old
 * implementation grabbed codec.inputBuffers/outputBuffers before configure,
 * which throws IllegalStateException on many devices, and it trusted the input
 * track's channel count instead of the decoder's actual output format. Output
 * format changes (sample rate / channels / PCM encoding) are re-read when the
 * codec announces them, and PCM_FLOAT output is converted to PCM16.
 */
object AudioCodecDecoder {

    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val MAX_STALL_LOOPS = 2_000 // ~20 s of no-progress before giving up

    fun decodeToPcm(input: File, targetRate: Int = TARGET_SAMPLE_RATE): PcmAudio? {
        MonoWavReader.read(input)?.let { wav ->
            return PcmAudio(Resampler.toRate(targetRate, wav.samples, wav.sampleRate), targetRate)
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            runCatching { extractor.setDataSource(input.absolutePath) }.getOrElse { return null }
            val format = (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
                val track = extractor.getTrackFormat(index)
                if (track.containsKey(MediaFormat.KEY_MIME) &&
                    (track.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")
                ) {
                    extractor.selectTrack(index)
                    track
                } else {
                    null
                }
            } ?: return null

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            var sourceRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                targetRate
            }
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                1
            }
            var pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var stall = 0
            val chunks = ArrayList<ByteArray>()

            while (!sawOutputEos && stall < MAX_STALL_LOOPS) {
                var progressed = false
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        progressed = true
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    in 0..Int.MAX_VALUE -> {
                        progressed = true
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            chunks.add(copyChunk(buffer, pcmEncoding, channels))
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEos = true
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progressed = true
                        // The decoder's real output format, not the input track's.
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sourceRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> Unit
                }
                stall = if (progressed) 0 else stall + 1
            }

            if (!sawOutputEos && chunks.isEmpty()) return null
            val pcm = chunks.fold(ByteArray(0)) { acc, b -> acc + b }
            if (pcm.isEmpty()) return null

            // Decode interleaved frames down to mono using the ACTUAL output
            // channel count and PCM encoding read above.
            val mono = downmixToMono(pcm, pcmEncoding, channels) ?: return null
            PcmAudio(Resampler.toRate(targetRate, mono, sourceRate), targetRate)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Copies one output chunk, normalizing PCM encoding to little-endian bytes. */
    private fun copyChunk(buffer: ByteBuffer, pcmEncoding: Int, channels: Int): ByteArray {
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                // Convert float [-1,1] to PCM16 with saturated rounding.
                val floats = FloatArray(buffer.remaining() / 4)
                buffer.asFloatBuffer().get(floats)
                val out = ByteBuffer.allocate(floats.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (f in floats) {
                    val v = if (f.isNaN()) 0f else f.coerceIn(-1f, 1f)
                    out.putShort((v * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                }
                out.array()
            }
            else -> {
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                bytes
            }
        }
    }

    /** Averages every channel of every complete frame; null on frame misalignment. */
    private fun downmixToMono(pcm: ByteArray, pcmEncoding: Int, channels: Int): ShortArray? {
        // PCM_FLOAT output was already converted to PCM16 in copyChunk, so the
        // interleaved stream here is always 16-bit little-endian.
        if (pcmEncoding != AudioFormat.ENCODING_PCM_FLOAT && pcmEncoding != AudioFormat.ENCODING_PCM_16BIT) {
            return null
        }
        if (channels < 1) return null
        val bytesPerFrame = channels * 2
        val frames = pcm.size / bytesPerFrame
        if (frames <= 0) return null
        val raw = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val mono = ShortArray(frames)
        for (frame in 0 until frames) {
            if (channels == 1) {
                mono[frame] = raw.short
            } else {
                var acc = 0
                repeat(channels) { acc += raw.short.toInt() }
                mono[frame] = (acc / channels).toShort()
            }
        }
        return mono
    }
}
