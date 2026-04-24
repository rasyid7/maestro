package maestro.cli.graphics

import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue

class ParallelSkiaRenderer(
    private val workerCount: Int = Runtime.getRuntime().availableProcessors(),
    private val outputWidthPx: Int,
    private val outputHeightPx: Int,
) : Closeable {

    private sealed class Job {
        data class Render(
            val future: CompletableFuture<ByteArray>,
            val screen: BufferedImage,
            val text: String,
        ) : Job()
        object Poison : Job()
    }

    private val jobQueue = LinkedBlockingQueue<Job>(workerCount * 2)

    private val workers: List<Thread> = List(workerCount) {
        Thread {
            val renderer = SkiaFrameRenderer()
            val bgrBuffer = BufferedImage(outputWidthPx, outputHeightPx, BufferedImage.TYPE_3BYTE_BGR)
            val bgrGraphics = bgrBuffer.createGraphics()
            val bgrBytes = (bgrBuffer.raster.dataBuffer as DataBufferByte).data
            try {
                while (true) {
                    when (val job = jobQueue.take()) {
                        is Job.Poison -> break
                        is Job.Render -> try {
                            val rendered = renderer.render(outputWidthPx, outputHeightPx, job.screen, job.text)
                            bgrGraphics.drawImage(rendered, 0, 0, null)
                            job.future.complete(bgrBytes.copyOf())
                        } catch (e: Throwable) {
                            job.future.completeExceptionally(e)
                        }
                    }
                }
            } finally {
                bgrGraphics.dispose()
                renderer.close()
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun submit(screen: BufferedImage, text: String): CompletableFuture<ByteArray> {
        val future = CompletableFuture<ByteArray>()
        jobQueue.put(Job.Render(future, screen, text))
        return future
    }

    override fun close() {
        repeat(workerCount) { jobQueue.put(Job.Poison) }
        workers.forEach { it.join() }
    }
}
