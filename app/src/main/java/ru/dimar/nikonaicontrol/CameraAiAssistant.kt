package ru.dimar.nikonaicontrol

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector

data class AiCameraPreset(
    val title: String,
    val explanation: String,
    val shutterValue: Int,
    val shutterLabel: String,
    val targetAperture: Int,
    val isoValue: Int,
    val wbValue: Int,
    val picCtrlId: Int,
    val cineMode: Int,
    val focusPointNorm: Pair<Int, Int>? = null
)

object CameraAiAssistant {

    fun analyzeLiveFrame(srcBmp: Bitmap, availableApertures: List<Int>): AiCameraPreset {
        val minAp = availableApertures.minOrNull() ?: 350
        val midAp = availableApertures.firstOrNull { it >= 800 } ?: (availableApertures.lastOrNull() ?: 800)

        // 1. Замеряем освещённость сцены (Luma)
        var lumaSum = 0L
        var pixelCount = 0
        val step = 16
        for (y in 0 until srcBmp.height step step) {
            for (x in 0 until srcBmp.width step step) {
                val c = srcBmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                lumaSum += (r * 77 + g * 150 + b * 29) shr 8
                pixelCount++
            }
        }
        val avgLuma = if (pixelCount > 0) (lumaSum / pixelCount).toInt() else 120

        // 2. Поиск лица и линии глаз (база для трекинга AF)
        var detectedFaceNorm: Pair<Int, Int>? = null
        try {
            val rgb565 = srcBmp.copy(Bitmap.Config.RGB_565, false)
            if (rgb565 != null) {
                val detector = FaceDetector(rgb565.width, rgb565.height, 1)
                val faces = arrayOfNulls<FaceDetector.Face>(1)
                if (detector.findFaces(rgb565, faces) > 0 && faces[0] != null) {
                    val midPoint = PointF()
                    faces[0]!!.getMidPoint(midPoint)
                    val nx = ((midPoint.x / rgb565.width) * 640).toInt().coerceIn(0, 640)
                    val ny = ((midPoint.y / rgb565.height) * 480).toInt().coerceIn(0, 480)
                    detectedFaceNorm = Pair(nx, ny)
                }
                rgb565.recycle()
            }
        } catch (_: Throwable) {}

        // 3. Принятие решения на основе анализа кадра
        if (detectedFaceNorm != null) {
            val iso = if (avgLuma < 70) 1600 else if (avgLuma < 120) 400 else 100
            val shutter = if (avgLuma > 170) ((1 shl 16) or 500) else ((1 shl 16) or 160)
            val sLabel = if (avgLuma > 170) "1/500" else "1/160"
            return AiCameraPreset(
                title = "Vision AI: Лицо в кадре",
                explanation = "Найдено лицо (яркость $avgLuma/255). Фокус наведён на глаза, диафрагма открыта для боке.",
                shutterValue = shutter,
                shutterLabel = sLabel,
                targetAperture = minAp,
                isoValue = iso,
                wbValue = 2,
                picCtrlId = 5,
                cineMode = 0,
                focusPointNorm = detectedFaceNorm
            )
        }

        return when {
            avgLuma < 55 -> AiCameraPreset(
                title = "Vision AI: Темная сцена ($avgLuma)",
                explanation = "Кадр тёмный — открыта диафрагма, выдержка 1/40 и ISO 3200.",
                shutterValue = (1 shl 16) or 40,
                shutterLabel = "1/40",
                targetAperture = minAp,
                isoValue = 3200,
                wbValue = 2,
                picCtrlId = 2,
                cineMode = 1
            )
            avgLuma > 185 -> AiCameraPreset(
                title = "Vision AI: Яркий свет ($avgLuma)",
                explanation = "Обнаружен сильный свет — диафрагма прикрыта, выдержка 1/1000 и ISO 100.",
                shutterValue = (1 shl 16) or 1000,
                shutterLabel = "1/1000",
                targetAperture = midAp,
                isoValue = 100,
                wbValue = 4,
                picCtrlId = 1,
                cineMode = 0
            )
            else -> AiCameraPreset(
                title = "Vision AI: Нормальный свет ($avgLuma)",
                explanation = "Оптимальный баланс 1/160 сек, открытая диафрагма и ISO 200.",
                shutterValue = (1 shl 16) or 160,
                shutterLabel = "1/160",
                targetAperture = minAp,
                isoValue = 200,
                wbValue = 2,
                picCtrlId = 1,
                cineMode = 0
            )
        }
    }

    fun analyzePrompt(rawPrompt: String, availableApertures: List<Int>): AiCameraPreset {
        val p = rawPrompt.lowercase().trim()
        val minAp = availableApertures.minOrNull() ?: 350
        val midAp = availableApertures.firstOrNull { it >= 800 } ?: (availableApertures.lastOrNull() ?: 800)

        return when {
            p.contains("портрет") || p.contains("разм") || p.contains("боке") -> AiCameraPreset(
                title = "Портрет / Боке",
                explanation = "Максимально открытая диафрагма объектива для размытия фона и профиль Portrait.",
                shutterValue = (1 shl 16) or 250,
                shutterLabel = "1/250",
                targetAperture = minAp,
                isoValue = 200,
                wbValue = 2,
                picCtrlId = 5,
                cineMode = 0
            )
            p.contains("ноч") || p.contains("темн") || p.contains("вечер") -> AiCameraPreset(
                title = "Ночной город (с рук)",
                explanation = "Светосила на максимум, выдержка 1/40 и ISO 3200 для чистых ночных кадров.",
                shutterValue = (1 shl 16) or 40,
                shutterLabel = "1/40",
                targetAperture = minAp,
                isoValue = 3200,
                wbValue = 2,
                picCtrlId = 2,
                cineMode = 1
            )
            p.contains("звезд") || p.contains("звёзд") || p.contains("небо") -> AiCameraPreset(
                title = "Астро / Звёздное небо",
                explanation = "Выдержка 15 сек (штатив!), открытая диафрагма и ISO 3200.",
                shutterValue = (15 shl 16) or 1,
                shutterLabel = "15\"",
                targetAperture = minAp,
                isoValue = 3200,
                wbValue = 4,
                picCtrlId = 2,
                cineMode = 0
            )
            p.contains("спорт") || p.contains("бег") || p.contains("быстр") -> AiCameraPreset(
                title = "Спорт / Заморозка",
                explanation = "Короткая выдержка 1/1000 сек заморозит движение, ISO 800.",
                shutterValue = (1 shl 16) or 1000,
                shutterLabel = "1/1000",
                targetAperture = minAp,
                isoValue = 800,
                wbValue = 2,
                picCtrlId = 3,
                cineMode = 0
            )
            p.contains("пейзаж") || p.contains("природ") || p.contains("резк") -> AiCameraPreset(
                title = "Пейзаж / Резкость",
                explanation = "Прикрытая диафрагма f/8+ для глубины резкости и чистое ISO 100.",
                shutterValue = (1 shl 16) or 125,
                shutterLabel = "1/125",
                targetAperture = midAp,
                isoValue = 100,
                wbValue = 4,
                picCtrlId = 6,
                cineMode = 0
            )
            p.contains("кино") || p.contains("блек") || p.contains("blackmagic") -> AiCameraPreset(
                title = "Кинорежим (1/50 + Blackmagic)",
                explanation = "Киношная выдержка 1/50 сек (180°), профиль Flat и LUT Blackmagic Deep.",
                shutterValue = (1 shl 16) or 50,
                shutterLabel = "1/50",
                targetAperture = minAp,
                isoValue = 400,
                wbValue = 4,
                picCtrlId = 7,
                cineMode = 1
            )
            else -> AiCameraPreset(
                title = "Универсальный баланс",
                explanation = "Сбалансированная экспозиция 1/160 сек, открытая диафрагма и ISO 200.",
                shutterValue = (1 shl 16) or 160,
                shutterLabel = "1/160",
                targetAperture = minAp,
                isoValue = 200,
                wbValue = 2,
                picCtrlId = 1,
                cineMode = 0
            )
        }
    }
}