package com.shinevoice.core.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes common container/audio formats (MP3, M4A/AAC, OGG, WAV fallback via
 * MonoWavReader) into mono 16-bit [PcmAudio] using MediaCodec. Pure WAV files
 * are handled by [MonoWavReader] to avoid codec startup cost on the common path.
 */
object AudioCodecDecoder {

    fun decodeToPcm(input: File, targetRate: Int = TARGET_SAMPLE_RATE): PcmAudio? {
        MonoWavReader.read(input)?.let { wav ->
            return PcmAudio(Resampler.toRate(targetRate, wav.samples, wav.sampleRate), targetRate)
        }
        return try {
            val extractor = MediaExtractor()
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
            } ?: run { extractor.release(); return null }

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            var sourceRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                targetRate
            }
            val inputBuffers = codec.inputBuffers
            val outputBuffers = codec.outputBuffers
            codec.configure(format, null, null, 0)
            codec.start()
            val accumulator = ByteArrayOutputStreamEx()
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            decode@ while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = inputBuffers[inputIndex]
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val buffer = outputBuffers[outputIndex]
                        if (info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            accumulator.write(chunk)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEos = true
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sourceRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                    }
                    outputIndex < 0 -> {
                        // INFO_TRY_AGAIN_LATER; loop again.
                    }
                    else -> {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            extractor.release()
            codec.stop()
            codec.release()
            val pcm = accumulator.toByteArray()
            if (pcm.isEmpty()) return null
            val raw = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            val shorts = ShortArray(pcm.size / 2)
            var stereo = false
            format.let {
                // MediaCodec decodes channels per the encoding; collapse by averaging
                // pairs only when the source track advertises >1 channel.
                stereo = it.containsKey(MediaFormat.KEY_CHANNEL_COUNT) &&
                    it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) > 1
            }
            var index = 0
            while (raw.remaining() >= 2) {
                val left = raw.short
                val right = if (stereo && raw.remaining() >= 2) raw.short else left
                shorts[index++] = ((left + right) / 2).toShort()
            }
            val mono = shorts.copyOf(index)
            PcmAudio(Resampler.toRate(targetRate, mono, sourceRate), targetRate)
        } catch (_: Exception) {
            null
        }
    }

    private class ByteArrayOutputStreamEx {
        private var data = ByteArray(8192)
        private var size = 0

        fun write(bytes: ByteArray) {
            if (size + bytes.size > data.size) {
                data = data.copyOf(maxOf(data.size * 2, size + bytes.size))
            }
            System.arraycopy(bytes, 0, data, size, bytes.size)
            size += bytes.size
        }

        fun toByteArray(): ByteArray = data.copyOf(size)
    }
}