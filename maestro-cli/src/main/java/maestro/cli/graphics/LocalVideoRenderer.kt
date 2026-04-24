package maestro.cli.graphics

import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.view.ProgressBar
import maestro.cli.view.render
import okio.ByteString.Companion.decodeBase64
import org.jcodec.api.PictureWithMetadata
import org.jcodec.api.awt.AWTSequenceEncoder
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Rational
import org.jcodec.scale.AWTUtil
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.Closeable
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

interface FrameRenderer {
    fun render(
        outputWidthPx: Int,
        outputHeightPx: Int,
        screen: BufferedImage,
        text: String,
    ): BufferedImage
}

class LocalVideoRenderer(
    private val frameRenderer: FrameRenderer,
    private val outputFile: File,
    private val outputFPS: Int,
    private val outputWidthPx: Int,
    private val outputHeightPx: Int,
) : VideoRenderer {

    private sealed class WorkItem {
        data class Frame(val future: CompletableFuture<ByteArray>) : WorkItem()
        object Repeat : WorkItem()
        object End : WorkItem()
    }

    private data class VideoInfo(val width: Int, val height: Int, val durationSeconds: Double)

    private fun probeVideo(file: File): VideoInfo {
        val proc = ProcessBuilder(
            "ffprobe", "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=width,height,duration",
            "-of", "csv=p=0",
            file.absolutePath
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val parts = proc.inputStream.bufferedReader().readText().trim().split(",")
        val width = parts[0].toInt()
        val height = parts[1].toInt()

        // Stream-level duration is N/A for some container formats (e.g. MOV/MP4 recorded
        // by iOS). Fall back to container-level duration in that case.
        val duration = parts.getOrNull(2)?.toDoubleOrNull() ?: run {
            val fmt = ProcessBuilder(
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                file.absolutePath
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            fmt.inputStream.bufferedReader().readText().trim().toDouble()
        }

        return VideoInfo(width, height, duration)
    }

    private fun startFfmpegDecode(file: File): Process {
        return ProcessBuilder(
            "ffmpeg", "-loglevel", "error",
            "-i", file.absolutePath,
            "-f", "rawvideo", "-pix_fmt", "bgr24",
            "-r", "$outputFPS",
            "pipe:1"
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    }

    override fun render(
        screenRecording: File,
        textFrames: List<AnsiResultView.Frame>,
    ) {
        System.err.println()
        System.err.println("@|bold Rendering video - This may take some time...|@".render())
        System.err.println()
        System.err.println(outputFile.absolutePath)

        val uploadProgress = ProgressBar(50)
        val decodedTextFrames = textFrames.map { frame ->
            frame to frame.content.decodeBase64()!!.string(Charsets.UTF_8).stripAnsiCodes()
        }

        val ffmpeg = startFfmpeg()
        if (ffmpeg != null) {
            renderWithFfmpeg(screenRecording, decodedTextFrames, uploadProgress, ffmpeg)
        } else {
            renderWithJCodec(screenRecording, decodedTextFrames, uploadProgress)
        }

        System.err.println()
        System.err.println()
        System.err.println("Rendering complete! If you're sharing on Twitter be sure to tag us 😄 @|bold @mobile__dev|@".render())
    }

    /**
     * Fast path: FFmpeg subprocess (hardware encode/decode) + pipeline parallelism.
     *
     * Main thread: FFmpeg decode (VideoToolbox on macOS) → CRC dedup → BlockingQueue
     * Render thread: Skia render → BGRbytes → FFmpeg stdin (skipped for Repeat frames)
     * FFmpeg process: H.264 encode (hardware on macOS, software ultrafast elsewhere)
     */
    private fun renderWithFfmpeg(
        screenRecording: File,
        decodedTextFrames: List<Pair<AnsiResultView.Frame, String>>,
        uploadProgress: ProgressBar,
        ffmpeg: Process,
    ) {
        val parallelRenderer = ParallelSkiaRenderer(outputWidthPx = outputWidthPx, outputHeightPx = outputHeightPx)
        val queue = LinkedBlockingQueue<WorkItem>(QUEUE_CAPACITY)
        val renderError = AtomicReference<Throwable>()

        val renderThread = Thread {
            try {
                ffmpeg.outputStream.buffered().use { stdin ->
                    val bgrBytes = ByteArray(outputWidthPx * outputHeightPx * 3)
                    while (true) {
                        when (val item = queue.take()) {
                            is WorkItem.End -> break
                            is WorkItem.Repeat -> stdin.write(bgrBytes)
                            is WorkItem.Frame -> {
                                val bytes = item.future.get()
                                bytes.copyInto(bgrBytes)
                                stdin.write(bgrBytes)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                renderError.set(e)
            }
        }
        renderThread.isDaemon = true
        renderThread.start()

        val info = probeVideo(screenRecording)
        val frameByteCount = info.width * info.height * 3
        val estimatedFrameCount = (info.durationSeconds * outputFPS).toInt().coerceAtLeast(1)

        val decodeProcess = startFfmpegDecode(screenRecording)
        val decodeStream = decodeProcess.inputStream.buffered()
        val rawBuffer = ByteArray(frameByteCount)
        var prevCrc = 0L
        var prevText: String? = null
        var frameIndex = 0

        try {
            while (true) {
                val bytesRead = decodeStream.readNBytes(rawBuffer, 0, frameByteCount)
                if (bytesRead < frameByteCount) break

                val currentTimestampSeconds = frameIndex.toDouble() / outputFPS
                val curText = decodedTextFrames.lastOrNull { (frame, _) ->
                    frame.timestamp.div(1000.0) <= currentTimestampSeconds
                }?.second ?: decodedTextFrames.first().second

                val crc = CRC32().apply { update(rawBuffer, 0, frameByteCount) }.value
                if (crc == prevCrc && curText == prevText) {
                    queue.put(WorkItem.Repeat)
                } else {
                    val screenImage = BufferedImage(info.width, info.height, BufferedImage.TYPE_3BYTE_BGR)
                    System.arraycopy(rawBuffer, 0, (screenImage.raster.dataBuffer as DataBufferByte).data, 0, frameByteCount)
                    val future = parallelRenderer.submit(screenImage, curText)
                    queue.put(WorkItem.Frame(future))
                    prevCrc = crc
                    prevText = curText
                }

                uploadProgress.set(frameIndex / estimatedFrameCount.toFloat())
                frameIndex++
            }
        } finally {
            queue.put(WorkItem.End)
            decodeProcess.destroyForcibly()
            decodeProcess.waitFor()
        }

        renderThread.join()
        parallelRenderer.close()
        (frameRenderer as? Closeable)?.close()
        renderError.get()?.let { throw RuntimeException("Render thread failed", it) }

        val exitCode = ffmpeg.waitFor()
        if (exitCode != 0) {
            System.err.println("Warning: ffmpeg exited with code $exitCode")
        }
    }

    /** Fallback path when ffmpeg is not installed. */
    private fun renderWithJCodec(
        screenRecording: File,
        decodedTextFrames: List<Pair<AnsiResultView.Frame, String>>,
        uploadProgress: ProgressBar,
    ) {
        (frameRenderer as? Closeable).use {
            NIOUtils.writableFileChannel(outputFile.absolutePath).use { out ->
                AWTSequenceEncoder(out, Rational.R(outputFPS, 1)).use { encoder ->
                    useFrameGrab(screenRecording) { grab ->
                        val outputDurationSeconds = grab.videoTrack.meta.totalDuration
                        val outputFrameCount = (outputDurationSeconds * outputFPS).toInt()
                        var curFrame: PictureWithMetadata = grab.nativeFrameWithMetadata!!
                        var nextFrame: PictureWithMetadata? = grab.nativeFrameWithMetadata
                        (0..outputFrameCount).forEach { frameIndex ->
                            val currentTimestampSeconds = frameIndex.toDouble() / outputFPS

                            // !! Due to smart cast limitation: https://youtrack.jetbrains.com/issue/KT-7186
                            @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                            while (nextFrame != null && nextFrame!!.timestamp <= currentTimestampSeconds) {
                                curFrame = nextFrame!!
                                nextFrame = grab.nativeFrameWithMetadata
                            }

                            val curImage = AWTUtil.toBufferedImage(curFrame.picture)
                            val curText = decodedTextFrames.lastOrNull { (frame, _) ->
                                frame.timestamp.div(1000.0) <= currentTimestampSeconds
                            }?.second ?: decodedTextFrames.first().second
                            val outputImage = frameRenderer.render(outputWidthPx, outputHeightPx, curImage, curText)
                            encoder.encodeImage(outputImage)

                            uploadProgress.set(frameIndex / outputFrameCount.toFloat())
                        }
                    }
                }
            }
        }
    }

    private fun startFfmpeg(): Process? {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        val codec = if (isMac) "h264_videotoolbox" else "libx264"
        val extraArgs = if (!isMac) listOf("-preset", "ultrafast") else emptyList()

        return try {
            ProcessBuilder(buildList {
                addAll(listOf(
                    "ffmpeg", "-y",
                    "-loglevel", "error",
                    "-f", "rawvideo",
                    "-pix_fmt", "bgr24",
                    "-s", "${outputWidthPx}x${outputHeightPx}",
                    "-r", "$outputFPS",
                    "-i", "pipe:0",
                    "-c:v", codec,
                    "-pix_fmt", "yuv420p",
                    "-movflags", "+faststart",
                ))
                addAll(extraArgs)
                add(outputFile.absolutePath)
            })
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        } catch (e: Exception) {
            System.err.println("ffmpeg not found, falling back to built-in encoder (slower)")
            null
        }
    }

    private fun String.stripAnsiCodes(): String {
        return replace("\\u001B\\[[;\\d]*[mH]".toRegex(), "")
    }

    companion object {
        private const val QUEUE_CAPACITY = 8
    }
}
