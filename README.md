# Second Sense — Alert Manager (Pair B)

This project contains the **AlertManager** module for the **Second Sense** assistive device system on Android.

---

## 📌 Pair B Objective
- **Build `AlertManager.kt`** with two core functions:
  1. `speak(text: String)`: Synthesizes spoken voice alerts using Android's `TextToSpeech` engine.
  2. `vibrate(strength: Int)`: Emits haptic tactile warnings using Android's `VibrationEffect`.
- **Test UI Screen**:
  - Two primary buttons: **“Speak”** and **“Vibrate”**
  - Text input for custom alert phrases with quick presets (*Obstacle*, *Turn Left*, *Battery Low*).
  - Strength slider (1 to 255) with quick presets (*Low*, *Medium*, *Max*).
  - Diagnostic console showing live status and pulse feedback.

---

## 🏗️ Project Structure
```
second-sense/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml          # Declares VIBRATE permission & MainActivity
│   │   │   ├── java/com/secondsense/alertmanager/
│   │   │   │   ├── AlertManager.kt          # Core alert manager (TTS + VibrationEffect)
│   │   │   │   └── MainActivity.kt          # Test UI controller with Speak & Vibrate buttons
│   │   │   └── res/
│   │   │       ├── layout/activity_main.xml # Modern Material UI with test controls
│   │   │       ├── values/                  # Strings, colors, themes
│   │   │       └── drawable/                # Indicator & graphics
│   │   └── test/
│   │       └── java/com/secondsense/alertmanager/
│   │           └── AlertManagerTest.kt      # Unit tests
│   └── build.gradle.kts                     # App module configuration
├── gradle/wrapper/                          # Gradle wrapper (v8.9)
├── build.gradle.kts                         # Root build configuration
├── settings.gradle.kts                      # Gradle settings
└── local.properties                         # Android SDK configuration
```

---

## 💻 Core Code Details

### 1. `speak(text: String)`
```kotlin
fun speak(text: String) {
    if (!isTtsReady) {
        pendingSpeechText = text
        return
    }
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "Alert_${System.currentTimeMillis()}")
}
```
- Flushes previous audio when urgent obstacle alerts arrive.
- Buffers any alert requested before the TTS engine finishes initialization.

### 2. `vibrate(strength: Int)`
```kotlin
fun vibrate(strength: Int, durationMillis: Long = 400L) {
    val amplitude = strength.coerceIn(1, 255)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(durationMillis, amplitude)
        } else {
            VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    } else {
        vibrator.vibrate(durationMillis)
    }
}
```
- Uses `VibrationEffect.createOneShot(...)` for hardware-accelerated vibration.
- Supports amplitude scaling from 1 to 255.
- Gracefully handles devices without amplitude control.

---

## 🚀 How to Run & Test
1. **Open in Android Studio**:
   - Launch Android Studio -> **Open** -> Select `second sense` folder.
   - Gradle will sync automatically.
2. **Run on Device / Emulator**:
   - Connect an Android phone via USB (or start an emulator with API 26+).
   - Press **Run ▶ (Shift + F10)**.
3. **Testing**:
   - Tap **Speak** to hear the TTS voice alert.
   - Adjust the slider or pick a preset, then tap **Vibrate** to feel the haptic feedback.
