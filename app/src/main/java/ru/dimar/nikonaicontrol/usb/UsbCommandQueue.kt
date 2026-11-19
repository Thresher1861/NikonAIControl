package ru.dimar.nikonaicontrol.usb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.LinkedBlockingDeque

class UsbCommandQueue(
    private val ptp: NikonPtpController,
    private val onFrame: (Bitmap, Int) -> Unit,
    private val onAperturesUpdated: (List<Int>) -> Unit,
    private val onConnectionLost: () -> Unit
) {
    private data class CommandTask(
        val name: String,
        val stopLvDuringExec: Boolean = false,
        val action: (NikonPtpController) -> Boolean,
        val onComplete: ((Boolean, String) -> Unit)? = null
    )

    private val queue = LinkedBlockingDeque<CommandTask>()
    @Volatile private var isRunning = false
    @Volatile var isLiveViewEnabled = false
    private var workerThread: Thread? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        workerThread = Thread({
            var frameCount = 0

            while (isRunning) {
                try {
                    val task = queue.pollFirst()
                    if (task != null) {
                        val wasLv = isLiveViewEnabled
                        if (task.stopLvDuringExec && wasLv) {
                            ptp.endLiveView()
                        }
                        val ok = task.action(ptp)
                        val code = ptp.lastErrorCode
                        if (task.stopLvDuringExec && wasLv) {
                            isLiveViewEnabled = ptp.startLiveView()
                        }
                        task.onComplete?.invoke(ok, code)
                        continue
                    }

                    if (isLiveViewEnabled) {
                        if (frameCount % 60 == 0) {
                            val list = ptp.getSupportedApertures()
                            if (list.isNotEmpty()) onAperturesUpdated(list)
                        }

                        // Сбрасываем 64-слотовый журнал системных событий камеры каждые 100 кадров без пауз
                        if (frameCount > 0 && frameCount % 100 == 0) {
                            ptp.pollEvents()
                        }

                        // Чистый конвейер: забираем кадр из кольцевого буфера EXPEED 4 -> показываем -> старый сразу уничтожается в UI
                        val jpeg = ptp.getLiveViewImage()
                        if (jpeg != null && jpeg.size > 1024) {
                            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                            if (bmp != null) {
                                frameCount++
                                onFrame(bmp, frameCount)
                            }
                            Thread.sleep(30)
                        } else {
                            Thread.sleep(45)
                        }
                    } else {
                        Thread.sleep(30)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                    Thread.sleep(50)
                }
            }
        }, "Expeed4InfiniteStream").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun enqueue(
        name: String,
        stopLvDuringExec: Boolean = false,
        action: (NikonPtpController) -> Boolean,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        queue.removeAll { it.name == name }
        queue.offerLast(CommandTask(name, stopLvDuringExec, action, onComplete))
    }

    fun stop() {
        isRunning = false
        isLiveViewEnabled = false
        queue.clear()
        workerThread?.interrupt()
    }
}