# NikonAIControl 📸🤖

> ⚠️ **EARLY ALPHA / WORK IN PROGRESS (СЫРАЯ АЛЬФА-ВЕРСИЯ)**  
> **EN:** This project is in an early experimental stage. Expect occasional UI freezes, USB/Live View stream dropouts on long sessions, and incomplete SD card saving in Host Mode. Contributions, bug reports, and Pull Requests are highly appreciated!  
> **RU:** Проект находится на ранней экспериментальной стадии (сырой вид). Возможны подлагивания, обрывы потока Live View / USB на длинных сессиях и неполная работа сохранения снимков на SD-карту в режиме Host Mode. Буду рад любой помощи, баг-репортам и Pull Request'ам!

[🇬🇧 English](#english) | [🇷🇺 Русский](#русский)

---

<a name="english"></a>
## 🇬🇧 English

**Open-Source Android USB OTG Camera Controller, Cinema Monitor & Vision AI Assistant for Nikon DSLR / Z Cameras.**

A native Kotlin Android application for direct tethered control of **Nikon** cameras (tested on **Nikon D3400 / EXPEED 4**) over a USB OTG cable without proprietary third-party SDKs — built directly on top of the **PTP/MTP protocol (Android USB Host API)**.

### ✨ Key Features

* **Real-Time Live View:** Streaming viewfinder powered by a single-threaded priority USB command queue (`UsbCommandQueue`) with instant frame buffer recycling.
* **Tactile Control Wheels (`WheelSelectorView`):** Smooth horizontal dials for **Shutter Speed** (`1/4000` — `30"` + `Bulb`), **Aperture** (with automatic lens range detection while zooming), and **ISO** (`100` — `25600`).
* **Vision AI & Scene Autopilot (`CameraAiAssistant`):**
  * **Live Frame Auto-Analysis:** Evaluates scene luminance (`Luma`), detects human faces/eyes via Android `FaceDetector`, automatically moves the AF point onto the face, and sets optimal exposure parameters.
  * **Natural Language Presets:** Configures camera settings from scene descriptions (*Portrait*, *Night City*, *Cinema 1/50*, *Sport*, *Landscape*, *Astro*).
* **Cinema Monitoring & LUTs (`CINE` + `Picture Control`):**
  * Real-time GPU `ColorMatrix` LUTs: **Blackmagic Deep** (crushed blacks + switches camera Picture Control to `Flat`), **Teal & Orange**, and **Kodak 2383 Film**.
  * Saves graded JPEG frames with the active Cinema LUT directly to the smartphone gallery (`Pictures/NikonAIControl`).
* **Pro Operator Tools (`MENU`):**
  * **Highlight Zebra (`ZebraOverlayView`):** Real-time diagonal striping for overexposed areas (`>95%`).
  * **Double Exposure (`2x`):** Overlays the first frame as a translucent ghost over Live View and blends (`Screen` mode) with the second shot into the phone gallery.
  * **180° Screen Flip (`FLIP`):** Instantly rotates the entire UI and viewfinder for upside-down rig mounting.
  * **Touch Autofocus (`FocusView`):** Tap anywhere on the screen to position the AF box and trigger focus drive.

### 📷 Camera Compatibility

* **Hardware Tested:** Nikon D3400 (EXPEED 4).
* **Expected Out-of-the-Box Compatibility (Nikon Vendor PTP `0x04B0`):**
  * D3xxx Series: `D3200`, `D3300`, `D3400`, `D3500`
  * D5xxx Series: `D5100`, `D5200`, `D5300`, `D5500`, `D5600`
  * D7xxx Series: `D7000`, `D7100`, `D7200`, `D7500`
  * Full-Frame & Z Series: `D610`, `D750`, `D810`, `Z30`, `Z50`, `Z fc`, `Z5`, `Z6`, `Z7`

### 🛠 Known Issues & Help Wanted (PRs Welcome!)

1. **SD Card Image Saving in Host Mode (`0x90C2 = 1`):** On Nikon D3400 during active USB Host Mode, shutter release commands (`0x90C0` / `0x100E` / `0x9207`) trigger the shutter curtain, but saving directly to the camera's SD card requires either proper SDRAM object retrieval (`0x90C3` / `0x1009`) or exact `RecordingMedia` (`0xD108`) state toggling.
2. **EXPEED 4 Event Buffer Stability:** During extended Live View sessions or rapid `EV` changes, the 64-slot PTP event ring buffer (`0x90C7`) needs tighter synchronization with `DeviceReady` (`0x90C8`) to prevent occasional stream stalls.
3. **Future Roadmap:** Continuous real-time Face/Eye/Object AF tracking and adding a `CanonPtpController` (`0x04A9`) module.

### 🚀 Build & Run

1. Open the project in **Android Studio** (Kotlin, minSdk 26+).
2. Build the APK: `Build -> Generate App Bundles or APKs -> Generate APKs`.
3. Connect your Nikon camera via a quality **USB OTG** cable, set the mode dial to **`M`** (or `A`/`S`/`P`), and grant USB Host permission on your phone.

---

<a name="русский"></a>
## 🇷🇺 Русский

**Открытый Android-контроллер, киномонитор и Vision AI ассистент для камер Nikon DSLR / Z по кабелю USB OTG.**

Приложение на Kotlin для прямого управления камерами **Nikon** (проверено на **Nikon D3400 / EXPEED 4**) без сторонних закрытых библиотек — поверх чистого протокола **PTP/MTP (Android USB Host API)**.

### ✨ Что уже реализовано

* **Live View в реальном времени:** Потоковый видоискатель через единую приоритетную очередь USB-команд (`UsbCommandQueue`) с мгновенным освобождением памяти кадров.
* **Тактильные барабаны управления (`WheelSelectorView`):** Плавные горизонтальные шкалы для **Выдержки** (`1/4000` — `30"` + `Bulb`), **Диафрагмы** (с автоопределением доступного диапазона объектива при зумировании) и **ISO** (`100` — `25600`).
* **Vision AI и Автопилот (`CameraAiAssistant`):**
  * **Авто-анализ текущего кадра:** Оценка освещённости сцены (`Luma`), поиск лица/глаз в кадре через `FaceDetector`, автоматическое наведение точки фокусировки и подбор экспозиционной тройки.
  * **Текстовые сценарии:** Подбор настроек по описанию сцены (*Портрет*, *Ночной город*, *Кинорежим 1/50*, *Спорт*, *Пейзаж*, *Астро*).
* **Киношный мониторинг и LUT (`CINE` + `Picture Control`):**
  * Профили **Blackmagic Deep** (глубокий чёрный + перевод камеры в `Flat`), **Teal & Orange**, **Kodak 2383 Film**.
  * Сохранение кадров с применённым киношным LUT напрямую в галерею смартфона (`Pictures/NikonAIControl`).
* **Профессиональные инструменты оператора (`МЕНЮ`):**
  * **Зебра пересветов (`ZebraOverlayView`):** Диагональная штриховка засвеченных участков (`>95%`) в реальном времени.
  * **Двойная экспозиция (`2x`):** Наложение первого кадра полупрозрачным «призраком» поверх Live View и склейка (`Screen Blend`) со вторым кадром.
  * **Переворот экрана на 180° (`FLIP`)** для работы на риге / клетке вверх ногами.
  * **Сенсорный автофокус (`FocusView`):** Тап по экрану перемещает рамку фокуса и запускает привод АФ.

### 🛠 Известные задачи для сообщества (Pull Requests Welcome!)

1. **Запись снимков на SD-карту в режиме Host Mode (`0x90C2 = 1`):** На Nikon D3400 при активном внешнем управлении команды `0x90C0` / `0x100E` / `0x9207` срабатывают затвором, но требуют корректной вычитки из SDRAM (`0x90C3` / `0x1009`) или точной последовательности переключения `RecordingMedia` (`0xD108`) для сохранения файла на флешку камеры.
2. **Стабильность USB-буфера EXPEED 4 на сверхдлинных сессиях:** При длительной работе Live View и быстрой смене `EV` кольцевой буфер событий (`0x90C7`) требует более точной синхронизации с `DeviceReady` (`0x90C8`).
3. **Развитие проекта:** Добавление непрерывного трекинга лица/глаз/объекта (`AF Tracking`) и поддержка камер Canon EOS (`0x04A9`).