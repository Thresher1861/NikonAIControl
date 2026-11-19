package ru.dimar.nikonaicontrol.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat

class NikonUsbManager(private val context: Context) {

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var bulkOutEndpoint: UsbEndpoint? = null
    private var isReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { openDeviceConnection(it) }
                        } else {
                            onError?.invoke("Нет разрешения на USB")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    findAndConnect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device?.deviceId == usbDevice?.deviceId) {
                        close()
                        onDisconnected?.invoke()
                    }
                }
            }
        }
    }

    fun findAndConnect() {
        if (usbConnection != null) { onConnected?.invoke(); return }
        registerReceiverIfNeeded()
        val camera = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x04B0 }
        if (camera == null) {
            onError?.invoke("Nikon D3400 не найдена по USB")
            return
        }
        usbDevice = camera
        if (usbManager.hasPermission(camera)) {
            openDeviceConnection(camera)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(camera, permissionIntent)
        }
    }

    private fun openDeviceConnection(device: UsbDevice) {
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            onError?.invoke("Не удалось открыть USB-порт")
            return
        }

        var targetInterface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
                targetInterface = intf
                break
            }
        }
        if (targetInterface == null && device.interfaceCount > 0) {
            targetInterface = device.getInterface(0)
        }
        if (targetInterface == null) {
            connection.close()
            onError?.invoke("Интерфейс камеры не найден")
            return
        }

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until targetInterface.endpointCount) {
            val ep = targetInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
        }

        if (epIn == null || epOut == null) {
            connection.close()
            onError?.invoke("Bulk-каналы USB не найдены")
            return
        }

        if (!connection.claimInterface(targetInterface, true)) {
            connection.close()
            onError?.invoke("Не удалось захватить интерфейс")
            return
        }

        usbConnection = connection
        usbInterface = targetInterface
        bulkInEndpoint = epIn
        bulkOutEndpoint = epOut
        onConnected?.invoke()
    }

    fun sendBytes(data: ByteArray, length: Int = data.size, timeout: Int = 5000): Int {
        val conn = usbConnection ?: return -1
        val ep = bulkOutEndpoint ?: return -1
        return conn.bulkTransfer(ep, data, length, timeout)
    }

    fun readBytes(buffer: ByteArray, length: Int = buffer.size, timeout: Int = 5000): Int {
        val conn = usbConnection ?: return -1
        val ep = bulkInEndpoint ?: return -1
        val safeLength = minOf(length, ep.maxPacketSize * 32, 16384)
        return conn.bulkTransfer(ep, buffer, safeLength, timeout)
    }

    fun close() {
        try {
            usbInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (_: Exception) {
        } finally {
            usbConnection = null
            usbInterface = null
            bulkInEndpoint = null
            bulkOutEndpoint = null
        }
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (_: Exception) {
            }
            isReceiverRegistered = false
        }
    }

    private fun registerReceiverIfNeeded() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        isReceiverRegistered = true
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "ru.dimar.nikonaicontrol.USB_PERMISSION"
    }
}
