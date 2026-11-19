package ru.dimar.nikonaicontrol.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

class NikonPtpController(private val usbManager: NikonUsbManager) {

    private var transactionId = 1
    private val ioBuffer = ByteArray(16384)
    var lastErrorCode: String = "OK"

    private fun nextTransactionId(): Int = transactionId++

    private fun buildCommandPacket(opCode: Int, params: IntArray = intArrayOf()): ByteArray {
        val length = 12 + params.size * 4
        val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(length)
        buf.putShort(PTP_CONTAINER_COMMAND)
        buf.putShort(opCode.toShort())
        buf.putInt(nextTransactionId())
        for (p in params) buf.putInt(p)
        return buf.array()
    }

    private fun buildDataPacket(opCode: Int, txId: Int, payload: ByteArray): ByteArray {
        val length = 12 + payload.size
        val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(length)
        buf.putShort(PTP_CONTAINER_DATA)
        buf.putShort(opCode.toShort())
        buf.putInt(txId)
        buf.put(payload)
        return buf.array()
    }

    private data class PtpResult(val responseCode: Int, val data: ByteArray? = null)

    @Synchronized
    private fun executeTransaction(opCode: Int, params: IntArray = intArrayOf(), outData: ByteArray? = null): PtpResult {
        val cmdPacket = buildCommandPacket(opCode, params)
        val currentTxId = ByteBuffer.wrap(cmdPacket, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int

        if (usbManager.sendBytes(cmdPacket, cmdPacket.size, 800) <= 0) {
            lastErrorCode = "USB_SEND_FAIL"
            return PtpResult(-1)
        }

        if (outData != null) {
            val dataPacket = buildDataPacket(opCode, currentTxId, outData)
            if (usbManager.sendBytes(dataPacket, dataPacket.size, 800) <= 0) {
                lastErrorCode = "USB_DATA_FAIL"
                return PtpResult(-1)
            }
        }

        var receivedData: ByteArray? = null

        while (true) {
            val bytesRead = usbManager.readBytes(ioBuffer, ioBuffer.size, 800)
            if (bytesRead < 12) {
                lastErrorCode = "USB_READ_SHORT($bytesRead)"
                return PtpResult(-1)
            }

            val header = ByteBuffer.wrap(ioBuffer, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
            val totalLength = header.int
            val containerType = header.short
            val code = header.short.toInt() and 0xFFFF

            val fullPacket = if (totalLength <= bytesRead) {
                ioBuffer.copyOfRange(0, totalLength)
            } else {
                val combined = ByteArray(totalLength)
                System.arraycopy(ioBuffer, 0, combined, 0, bytesRead)
                var offset = bytesRead
                while (offset < totalLength) {
                    val chunk = usbManager.readBytes(ioBuffer, minOf(ioBuffer.size, totalLength - offset), 800)
                    if (chunk <= 0) break
                    System.arraycopy(ioBuffer, 0, combined, offset, chunk)
                    offset += chunk
                }
                combined
            }

            if (containerType == PTP_CONTAINER_DATA) {
                if (fullPacket.size > 12) receivedData = fullPacket.copyOfRange(12, fullPacket.size)
            } else if (containerType == PTP_CONTAINER_RESPONSE) {
                lastErrorCode = "0x${code.toString(16).uppercase()}"
                return PtpResult(code, receivedData)
            }
        }
    }

    fun openSession(): Boolean {
        transactionId = 1
        val res = executeTransaction(0x1002, intArrayOf(1))
        val ok = res.responseCode == 0x2001 || res.responseCode == 0x201E
        if (ok) {
            executeTransaction(0x1001)
            executeTransaction(0x90C2, intArrayOf(1))
            pollEvents()
        }
        return ok
    }

    fun pollEvents() { executeTransaction(0x90C7) }

    fun waitDeviceReady(maxTries: Int = 8): Boolean {
        for (i in 0 until maxTries) {
            val res = executeTransaction(0x90C8)
            if (res.responseCode == 0x2001) return true
            if (res.responseCode == -1) return false
            Thread.sleep(60)
        }
        return false
    }

    fun closeSession(): Boolean {
        executeTransaction(0x90C2, intArrayOf(0))
        return executeTransaction(0x1003).responseCode == 0x2001
    }

    fun startLiveView(): Boolean {
        pollEvents()
        waitDeviceReady(8)
        for (attempt in 0 until 5) {
            val res = executeTransaction(0x9201)
            if (res.responseCode == 0x2001) {
                waitDeviceReady(8)
                return true
            }
            if (res.responseCode != 0xF004 && res.responseCode != 0x2019) {
                return false
            }
            Thread.sleep(250)
            pollEvents()
        }
        return false
    }

    fun endLiveView(): Boolean {
        val res = executeTransaction(0x9202)
        Thread.sleep(250)
        return res.responseCode == 0x2001
    }

    fun getLiveViewImage(): ByteArray? {
        val res = executeTransaction(0x9203)
        val raw = res.data ?: return null
        if (res.responseCode != 0x2001) return null

        var start = -1
        for (i in 0 until raw.size - 2) {
            if (raw[i] == 0xFF.toByte() && raw[i + 1] == 0xD8.toByte()) {
                start = i
                break
            }
        }
        if (start == -1) return null

        var end = raw.size
        for (i in raw.size - 2 downTo start + 2) {
            if (raw[i] == 0xFF.toByte() && raw[i + 1] == 0xD9.toByte()) {
                end = i + 2
                break
            }
        }
        return raw.copyOfRange(start, end)
    }

    fun capturePhoto(): Boolean {
        Thread.sleep(150)
        waitDeviceReady(10)
        // 0x9207 (InitiateCaptureRecInMedia) с параметром 1 = запись напрямую на SD-карту камеры
        var res = executeTransaction(0x9207, intArrayOf(1))
        if (res.responseCode != 0x2001) {
            // Запасной вариант: временно отпускаем Host Mode (0x90C2 = 0) и делаем стандартный спуск на карту
            executeTransaction(0x90C2, intArrayOf(0))
            Thread.sleep(100)
            res = executeTransaction(0x100E, intArrayOf(0, 0))
            Thread.sleep(200)
            executeTransaction(0x90C2, intArrayOf(1))
        }
        if (res.responseCode == 0x2001) {
            waitDeviceReady(15)
            pollEvents()
            return true
        }
        return false
    }

    fun startMovieRec(): Boolean = executeTransaction(0x920A).responseCode == 0x2001
    fun stopMovieRec(): Boolean = executeTransaction(0x920B).responseCode == 0x2001

    fun getSupportedApertures(): List<Int> {
        val res = executeTransaction(0x1014, intArrayOf(0x5007))
        val data = res.data ?: return emptyList()
        if (res.responseCode != 0x2001 || data.size < 14) return emptyList()
        return try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buf.short
            buf.short
            buf.get()
            buf.short
            buf.short
            val formFlag = buf.get().toInt() and 0xFF
            if (formFlag == 2 && buf.remaining() >= 2) {
                val count = buf.short.toInt() and 0xFFFF
                val list = mutableListOf<Int>()
                for (i in 0 until count) {
                    if (buf.remaining() >= 2) {
                        list.add(buf.short.toInt() and 0xFFFF)
                    }
                }
                list
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun afDrive(): Boolean = executeTransaction(0x90C1).responseCode == 0x2001
    fun changeAfArea(x: Int, y: Int): Boolean = executeTransaction(0x9205, intArrayOf(x, y)).responseCode == 0x2001

    private fun setPropWithRetry(propertyCode: Int, bytes: ByteArray): Boolean {
        for (attempt in 0 until 3) {
            val res = executeTransaction(0x1016, intArrayOf(propertyCode), bytes)
            if (res.responseCode == 0x2001) return true
            if (res.responseCode != 0x2019) return false
            Thread.sleep(60)
        }
        return false
    }

    private fun setByteProp(propertyCode: Int, value: Int): Boolean =
        setPropWithRetry(propertyCode, byteArrayOf(value.toByte()))

    private fun setShortProp(propertyCode: Int, value: Int): Boolean {
        val bytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
        return setPropWithRetry(propertyCode, bytes)
    }

    private fun setIntProp(propertyCode: Int, value: Int): Boolean {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        return setPropWithRetry(propertyCode, bytes)
    }

    fun setISO(value: Int): Boolean = setShortProp(0x500F, value)
    fun setAperture(value: Int): Boolean = setShortProp(0x5007, value)
    fun setShutterSpeed(value: Int): Boolean = setIntProp(0x500D, value)
    fun setWhiteBalance(value: Int): Boolean = setShortProp(0x5005, value)
    fun setExposureCompensation(evMilli: Int): Boolean = setShortProp(0x5010, evMilli)
    fun setImageFormat(nikonCompressionCode: Int): Boolean = setByteProp(0x5004, nikonCompressionCode)
    fun setPictureControl(picCtrlId: Int): Boolean = setByteProp(0xD200, picCtrlId)

    companion object {
        private const val PTP_CONTAINER_COMMAND: Short = 1
        private const val PTP_CONTAINER_DATA: Short = 2
        private const val PTP_CONTAINER_RESPONSE: Short = 3
    }
}