package ru.dimar.nikonaicontrol

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ru.dimar.nikonaicontrol.usb.NikonPtpController
import ru.dimar.nikonaicontrol.usb.NikonUsbManager
import ru.dimar.nikonaicontrol.usb.UsbCommandQueue
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var ivLiveView: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnShutter: Button
    private lateinit var usbManager: NikonUsbManager
    private var ptpController: NikonPtpController? = null
    private var usbQueue: UsbCommandQueue? = null
    private var availableApertures: List<Int> = listOf(180, 280, 350, 400, 450, 500, 560, 710, 800, 1100, 1600, 2200)
    private var isFlipped = false
    private var currentCineMode = 0
    private var doubleExpFirstFrame: android.graphics.Bitmap? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_main)

        ivLiveView = findViewById(R.id.ivLiveView)
        tvStatus = findViewById(R.id.tvStatus)
        btnShutter = findViewById(R.id.btnShutter)

        usbManager = NikonUsbManager(this)
        usbManager.onConnected = {
            runOnUiThread { tvStatus.text = "[v29] USB открыт..." }
            startCameraSession()
        }
        usbManager.onDisconnected = {
            usbQueue?.stop()
            runOnUiThread { tvStatus.text = "[v29] Камера отключена" }
        }
        usbManager.onError = { err ->
            runOnUiThread { tvStatus.text = "[v29] USB: $err" }
        }

        tvStatus.setOnClickListener {
            tvStatus.text = "[v29] Переподключение..."
            usbQueue?.stop()
            usbManager.close()
            usbManager.findAndConnect()
        }

        btnShutter.setOnClickListener {
            val firstBmp = doubleExpFirstFrame
            if (firstBmp != null && !firstBmp.isRecycled) {
                val secondBmp = (ivLiveView.drawable as? BitmapDrawable)?.bitmap
                if (secondBmp != null && !secondBmp.isRecycled) {
                    val result = android.graphics.Bitmap.createBitmap(firstBmp.width, firstBmp.height, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(result)
                    canvas.drawBitmap(firstBmp, 0f, 0f, null)
                    val blendPaint = android.graphics.Paint().apply {
                        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
                    }
                    canvas.drawBitmap(secondBmp, 0f, 0f, blendPaint)
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Nikon2X_${System.currentTimeMillis()}.jpg")
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NikonAIControl")
                    }
                    contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                        contentResolver.openOutputStream(uri)?.use { out -> result.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out) }
                    }
                    findViewById<ImageView>(R.id.ivGhost)?.apply { setImageBitmap(null); visibility = View.GONE }
                    doubleExpFirstFrame = null
                    tvStatus.text = "[v29] 2x кадр сохранён в Галерею!"
                    Toast.makeText(this, "Двойная экспозиция сохранена в Pictures/NikonAIControl!", Toast.LENGTH_LONG).show()
                }
                return@setOnClickListener
            }
            val q = usbQueue ?: return@setOnClickListener
            if (currentCineMode != 0) {
                (ivLiveView.drawable as? BitmapDrawable)?.bitmap?.let { src ->
                    if (!src.isRecycled) saveBitmapWithCineLut(src, currentCineMode)
                }
            }
            tvStatus.text = "[v29] Спуск..."
            q.enqueue(
                name = "CAPTURE",
                stopLvDuringExec = true,
                action = { ptp -> ptp.capturePhoto() },
                onComplete = { ok, code ->
                    runOnUiThread {
                        tvStatus.text = if (ok) "[v29] Снимок готов!" else "[v29] Спуск ($code)"
                    }
                }
            )
        }

        var isRecording = false
        findViewById<Button>(R.id.btnVideo).setOnClickListener { btn ->
            val q = usbQueue ?: return@setOnClickListener
            if (currentCineMode != 0) {
                (ivLiveView.drawable as? BitmapDrawable)?.bitmap?.let { src ->
                    if (!src.isRecycled) saveBitmapWithCineLut(src, currentCineMode)
                }
            }
            val startRec = !isRecording
            q.enqueue(
                name = "VIDEO",
                action = { ptp -> if (startRec) ptp.startMovieRec() else ptp.stopMovieRec() },
                onComplete = { ok, code ->
                    if (ok) isRecording = startRec
                    runOnUiThread {
                        (btn as Button).text = if (isRecording) "STOP" else "VID"
                        btn.setBackgroundColor(if (isRecording) Color.RED else Color.DKGRAY)
                        tvStatus.text = if (ok) (if (isRecording) "[v29] Запись видео..." else "[v29] Видео стоп") else "[v29] Видео ($code)"
                    }
                }
            )
        }

        findViewById<Button>(R.id.btnAi).setOnClickListener {
            val presets = arrayOf("Авто-анализ кадра (Vision AI)", "Портрет с размытием", "Ночной город (с рук)", "Кинорежим (1/50 + Blackmagic)", "Спорт / Заморозка", "Пейзаж (резкость f/8)", "Звёздное небо (15 сек)")
            val input = EditText(this).apply {
                hint = "Или напишите задачу (например: портрет)..."
                setPadding(32, 24, 32, 24)
            }
            AlertDialog.Builder(this)
                .setTitle("AI Автопилот")
                .setView(input)
                .setItems(presets) { _, which ->
                    if (which == 0) {
                        val curBmp = (ivLiveView.drawable as? BitmapDrawable)?.bitmap
                        if (curBmp != null && !curBmp.isRecycled) {
                            applyAiPreset(CameraAiAssistant.analyzeLiveFrame(curBmp, availableApertures))
                        } else {
                            tvStatus.text = "[v29] Нет кадра для анализа"
                        }
                    } else {
                        applyAiPreset(CameraAiAssistant.analyzePrompt(presets[which], availableApertures))
                    }
                }
                .setPositiveButton("Применить") { _, _ ->
                    val query = input.text.toString().ifBlank { "универсальный" }
                    applyAiPreset(CameraAiAssistant.analyzePrompt(query, availableApertures))
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        setupMenuAndWheels()

        val focusView = findViewById<FocusView>(R.id.focusView)
        findViewById<Button>(R.id.btnAf).setOnClickListener {
            val (x, y) = focusView.getNormalizedCoords()
            focusView.setLocked(false)
            usbQueue?.enqueue("AF", action = { ptp -> ptp.changeAfArea(x, y); ptp.afDrive() }, onComplete = { ok, code ->
                runOnUiThread {
                    focusView.setLocked(ok)
                    tvStatus.text = if (ok) "[v29] Фокус ОК" else "[v29] Фокус ($code)"
                }
            })
        }

        ivLiveView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                focusView.setFocusPoint(event.x, event.y, false)
                val (x, y) = focusView.getNormalizedCoords()
                usbQueue?.enqueue("AF", action = { ptp -> ptp.changeAfArea(x, y); ptp.afDrive() }, onComplete = { ok, code ->
                    runOnUiThread {
                        focusView.setLocked(ok)
                        tvStatus.text = if (ok) "[v29] Фокус ОК" else "[v29] Фокус ($code)"
                    }
                })
            }
            true
        }

        usbManager.findAndConnect()
    }
    private fun setupMenuAndWheels() {
        val wheelShutter = findViewById<WheelSelectorView>(R.id.wheelShutter)
        val wheelAperture = findViewById<WheelSelectorView>(R.id.wheelAperture)
        val wheelIso = findViewById<WheelSelectorView>(R.id.wheelIso)
        val zebraView = findViewById<ZebraOverlayView>(R.id.zebraView)
        val rootLayout = findViewById<View>(R.id.rootLayout)

        val shutterLabels = listOf(
            "1/4000", "1/3200", "1/2500", "1/2000", "1/1600", "1/1250", "1/1000",
            "1/800", "1/640", "1/500", "1/400", "1/320", "1/250", "1/200",
            "1/160", "1/125", "1/100", "1/80", "1/60", "1/50", "1/40", "1/30",
            "1/25", "1/20", "1/15", "1/13", "1/10", "1/8", "1/6", "1/5", "1/4",
            "0.3\"", "0.4\"", "0.5\"", "0.6\"", "0.8\"", "1\"", "1.3\"", "1.6\"",
            "2\"", "2.5\"", "3\"", "4\"", "5\"", "6\"", "8\"", "10\"", "13\"", "15\"", "20\"", "25\"", "30\"", "Bulb"
        )
        val shutterValues = intArrayOf(
            (1 shl 16) or 4000, (1 shl 16) or 3200, (1 shl 16) or 2500, (1 shl 16) or 2000,
            (1 shl 16) or 1600, (1 shl 16) or 1250, (1 shl 16) or 1000, (1 shl 16) or 800,
            (1 shl 16) or 640, (1 shl 16) or 500, (1 shl 16) or 400, (1 shl 16) or 320,
            (1 shl 16) or 250, (1 shl 16) or 200, (1 shl 16) or 160, (1 shl 16) or 125,
            (1 shl 16) or 100, (1 shl 16) or 80, (1 shl 16) or 60, (1 shl 16) or 50,
            (1 shl 16) or 40, (1 shl 16) or 30, (1 shl 16) or 25, (1 shl 16) or 20,
            (1 shl 16) or 15, (1 shl 16) or 13, (1 shl 16) or 10, (1 shl 16) or 8,
            (1 shl 16) or 6, (1 shl 16) or 5, (1 shl 16) or 4,
            (3 shl 16) or 10, (4 shl 16) or 10, (5 shl 16) or 10, (6 shl 16) or 10, (8 shl 16) or 10,
            (1 shl 16) or 1, (13 shl 16) or 10, (16 shl 16) or 10, (2 shl 16) or 1, (25 shl 16) or 10,
            (3 shl 16) or 1, (4 shl 16) or 1, (5 shl 16) or 1, (6 shl 16) or 1, (8 shl 16) or 1,
            (10 shl 16) or 1, (13 shl 16) or 1, (15 shl 16) or 1, (20 shl 16) or 1, (25 shl 16) or 1, (30 shl 16) or 1,
            -1
        )
        wheelShutter.items = shutterLabels
        wheelShutter.selectedIndex = 15
        wheelShutter.onValueChanged = { i -> sendProp("SHUTTER", "Выд ${shutterLabels[i]}") { it.setShutterSpeed(shutterValues[i]) } }

        val isoLabels = listOf("100", "200", "400", "800", "1600", "3200", "6400", "12800", "25600")
        val isoValues = intArrayOf(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600)
        wheelIso.items = isoLabels
        wheelIso.selectedIndex = 2
        wheelIso.onValueChanged = { i -> sendProp("ISO", "ISO ${isoLabels[i]}") { it.setISO(isoValues[i]) } }

        wheelAperture.items = availableApertures.map { ap -> val f = ap / 100.0; if (f % 1.0 == 0.0) "${f.toInt()}" else "$f" }
        wheelAperture.onValueChanged = { i ->
            if (i in availableApertures.indices) {
                val target = availableApertures[i]
                sendProp("APERTURE", "f/${target / 100.0}") { it.setAperture(target) }
            }
        }

        findViewById<Button>(R.id.btnMenu).setOnClickListener {
            val menuItems = arrayOf(
                "Формат (RAW / JPEG)",
                "Зебра пересветов (${if (zebraView.isZebraEnabled) "ВКЛ" else "ВЫКЛ"})",
                "Picture Control (PC)",
                "Киношный LUT (CINE)",
                "Баланс белого (ББ)",
                "Экспокоррекция (EV)",
                "Переворот экрана 180° (FLIP)", "Двойная экспозиция (2x)"
            )
            AlertDialog.Builder(this).setTitle("Инструменты камеры").setItems(menuItems) { _, which ->
                when (which) {
                    0 -> {
                        val l = arrayOf("RAW (NEF)", "RAW + JPEG Fine", "JPEG Fine", "JPEG Normal", "JPEG Basic")
                        val c = intArrayOf(4, 7, 2, 1, 0)
                        AlertDialog.Builder(this).setTitle("Формат файла").setItems(l) { _, i -> sendProp("FORMAT", l[i]) { it.setImageFormat(c[i]) } }.show()
                    }
                    1 -> {
                        zebraView.isZebraEnabled = !zebraView.isZebraEnabled
                        tvStatus.text = if (zebraView.isZebraEnabled) "[v29] Зебра: ВКЛ" else "[v29] Зебра: ВЫКЛ"
                    }
                    2 -> {
                        val l = arrayOf("Стандартный", "Нейтральный", "Насыщенный", "Монохром (ЧБ)", "Портрет", "Пейзаж", "Flat (Киношный)")
                        val c = intArrayOf(1, 2, 3, 4, 5, 6, 7)
                        AlertDialog.Builder(this).setTitle("Picture Control").setItems(l) { _, i -> sendProp("PIC_CTRL", "PC: ${l[i]}") { it.setPictureControl(c[i]) } }.show()
                    }
                    3 -> {
                        val l = arrayOf("Выкл (Оригинал)", "Blackmagic Deep", "Teal & Orange", "Kodak 2383 Film")
                        AlertDialog.Builder(this).setTitle("Киношный LUT").setItems(l) { _, i -> applyCineLut(i); tvStatus.text = "[v29] LUT: ${l[i]}" }.show()
                    }
                    4 -> {
                        val l = arrayOf("Авто", "Солнце", "Облачно", "Тень", "Лампа", "Флуоресцент", "Вспышка")
                        val v = intArrayOf(2, 4, 6, 7, 5, 3, 6)
                        AlertDialog.Builder(this).setTitle("Баланс белого").setItems(l) { _, i -> sendProp("WB", "ББ: ${l[i]}") { it.setWhiteBalance(v[i]) } }.show()
                    }
                    5 -> {
                        val l = arrayOf("-3.0", "-2.0", "-1.0", "-0.7", "-0.3", "0.0", "+0.3", "+0.7", "+1.0", "+2.0", "+3.0")
                        val v = intArrayOf(-3000, -2000, -1000, -700, -300, 0, 300, 700, 1000, 2000, 3000)
                        AlertDialog.Builder(this).setTitle("Экспокоррекция EV").setItems(l) { _, i -> sendProp("EV", "EV ${l[i]}") { it.setExposureCompensation(v[i]) } }.show()
                    }
                    6 -> {
                        isFlipped = !isFlipped
                        rootLayout.rotation = if (isFlipped) 180f else 0f
                        tvStatus.text = if (isFlipped) "[v29] Переворот 180°" else "[v29] Обычный вид 0°"
                    }
                    7 -> {
                        val ghostView = findViewById<ImageView>(R.id.ivGhost)
                        if (doubleExpFirstFrame == null) {
                            val cur = (ivLiveView.drawable as? BitmapDrawable)?.bitmap
                            if (cur != null && !cur.isRecycled) {
                                doubleExpFirstFrame = cur.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                ghostView?.setImageBitmap(doubleExpFirstFrame)
                                ghostView?.visibility = View.VISIBLE
                                tvStatus.text = "[v29] 2x: Сделайте 2-й кадр кнопкой REC"
                            }
                        } else {
                            ghostView?.setImageBitmap(null)
                            ghostView?.visibility = View.GONE
                            doubleExpFirstFrame = null
                            tvStatus.text = "[v29] 2x: Отменено"
                        }
                    }
                }
            }.show()
        }
    }
    private fun applyCineLut(mode: Int) {
        currentCineMode = mode
        when (mode) {
            0 -> ivLiveView.clearColorFilter()
            1 -> ivLiveView.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(1.24f,0f,0f,0f,-28f, 0f,1.22f,0f,0f,-26f, 0f,0f,1.26f,0f,-24f, 0f,0f,0f,1f,0f)))
            2 -> ivLiveView.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(1.25f,0.08f,-0.10f,0f,-18f, -0.04f,1.15f,0.06f,0f,-15f, -0.12f,0.14f,1.28f,0f,-10f, 0f,0f,0f,1f,0f)))
            3 -> ivLiveView.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(1.22f,0.05f,0f,0f,-16f, 0.02f,1.18f,0f,0f,-18f, 0f,-0.04f,1.12f,0f,-22f, 0f,0f,0f,1f,0f)))
        }
    }

    private fun sendProp(key: String, label: String, action: (NikonPtpController) -> Boolean) {
        usbQueue?.enqueue(key, action = action, onComplete = { ok, code ->
            runOnUiThread { tvStatus.text = if (ok) "[v29] $label: ОК" else "[v29] $label ($code)" }
        })
    }

    private fun applyAiPreset(preset: AiCameraPreset) {
        tvStatus.text = "[v29] AI: ${preset.title}..."
        applyCineLut(preset.cineMode)
        usbQueue?.enqueue(
            name = "AI_BATCH",
            action = { ptp ->
                ptp.setShutterSpeed(preset.shutterValue)
                Thread.sleep(60)
                ptp.setAperture(preset.targetAperture)
                Thread.sleep(60)
                ptp.setISO(preset.isoValue)
                Thread.sleep(60)
                ptp.setPictureControl(preset.picCtrlId); preset.focusPointNorm?.let { (fx, fy) -> Thread.sleep(60); ptp.changeAfArea(fx, fy); ptp.afDrive() }
                true
            },
            onComplete = { _, _ ->
                runOnUiThread {
                    val fVal = preset.targetAperture / 100.0
                    val fStr = if (fVal % 1.0 == 0.0) "f/${fVal.toInt()}" else "f/$fVal"
                    syncWheelsToPreset(preset)
                    preset.focusPointNorm?.let { (fx, fy) -> findViewById<FocusView>(R.id.focusView)?.setFocusPoint((fx / 640f) * ivLiveView.width, (fy / 480f) * ivLiveView.height, true) }
                    tvStatus.text = "[v29] AI: ${preset.shutterLabel}, $fStr, ISO ${preset.isoValue}"
                    Toast.makeText(this, preset.explanation, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun startCameraSession() {
        thread {
            val ptp = NikonPtpController(usbManager)
            ptpController = ptp
            if (!ptp.openSession()) {
                runOnUiThread { tvStatus.text = "[v29] Сессия: ${ptp.lastErrorCode}" }
                return@thread
            }
            val q = UsbCommandQueue(
                ptp = ptp,
                onFrame = { bmp, count ->
                    zebraViewAnalyze(bmp)
                    runOnUiThread {
                        val oldBmp = (ivLiveView.drawable as? BitmapDrawable)?.bitmap
                        ivLiveView.setImageBitmap(bmp)
                        if (oldBmp != null && !oldBmp.isRecycled && oldBmp != bmp) oldBmp.recycle()
                        if (count % 15 == 1) tvStatus.text = "[v29] LV #$count"
                    }
                },
                onAperturesUpdated = { newList ->
                    availableApertures = newList
                    runOnUiThread {
                        findViewById<WheelSelectorView>(R.id.wheelAperture)?.items = newList.map { ap ->
                            val f = ap / 100.0; if (f % 1.0 == 0.0) "${f.toInt()}" else "$f"
                        }
                    }
                },
                onConnectionLost = { runOnUiThread { tvStatus.text = "[v29] Связь потеряна" } }
            )
            usbQueue = q
            q.start()
            q.enqueue("START_LV", action = { it.startLiveView() }, onComplete = { ok, code ->
                if (ok) q.isLiveViewEnabled = true else runOnUiThread { tvStatus.text = "[v29] LV ($code)" }
            })
        }
    }

    private fun zebraViewAnalyze(bmp: android.graphics.Bitmap) {
        findViewById<ZebraOverlayView>(R.id.zebraView)?.analyzeFrame(bmp)
    }

    private fun saveBitmapWithCineLut(src: android.graphics.Bitmap, mode: Int) {
        try {
            val outBmp = android.graphics.Bitmap.createBitmap(src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(outBmp)
            val paint = android.graphics.Paint()
            val cm = when (mode) {
                1 -> ColorMatrix(floatArrayOf(1.24f,0f,0f,0f,-28f, 0f,1.22f,0f,0f,-26f, 0f,0f,1.26f,0f,-24f, 0f,0f,0f,1f,0f))
                2 -> ColorMatrix(floatArrayOf(1.25f,0.08f,-0.10f,0f,-18f, -0.04f,1.15f,0.06f,0f,-15f, -0.12f,0.14f,1.28f,0f,-10f, 0f,0f,0f,1f,0f))
                3 -> ColorMatrix(floatArrayOf(1.22f,0.05f,0f,0f,-16f, 0.02f,1.18f,0f,0f,-18f, 0f,-0.04f,1.12f,0f,-22f, 0f,0f,0f,1f,0f))
                else -> null
            }
            if (cm != null) paint.colorFilter = ColorMatrixColorFilter(cm)
            canvas.drawBitmap(src, 0f, 0f, paint)
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "NikonCine_${System.currentTimeMillis()}.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NikonAIControl")
            }
            contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                contentResolver.openOutputStream(uri)?.use { out -> outBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out) }
            }
            outBmp.recycle()
        } catch (_: Throwable) {}
    }

    private fun syncWheelsToPreset(preset: AiCameraPreset) {
        val wShutter = findViewById<WheelSelectorView>(R.id.wheelShutter)
        val wAp = findViewById<WheelSelectorView>(R.id.wheelAperture)
        val wIso = findViewById<WheelSelectorView>(R.id.wheelIso)

        val sIdx = wShutter.items.indexOf(preset.shutterLabel)
        if (sIdx >= 0) wShutter.selectedIndex = sIdx

        val aIdx = availableApertures.indexOf(preset.targetAperture)
        if (aIdx >= 0) wAp.selectedIndex = aIdx

        val iIdx = wIso.items.indexOf(preset.isoValue.toString())
        if (iIdx >= 0) wIso.selectedIndex = iIdx
    }

    override fun onDestroy() {
        usbQueue?.stop()
        thread {
            ptpController?.endLiveView()
            ptpController?.closeSession()
            usbManager.close()
        }
        super.onDestroy()
    }
}