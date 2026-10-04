# FlowVoice (Superflow AI Native Android Clone)

> **Speak in Hindi, Tamil, Telugu, Malayalam, Kannada, or Hinglish — Instantly type polished, fluent English directly in WhatsApp, Gmail, Slack, and any app.**

Built in **Native Kotlin** using Android **`AccessibilityService`** and **`WindowManager.addView`** for zero-latency, seamless floating overlay dictation.

---

## 🌟 Architecture & How It Works

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Android AccessibilityService (FlowAccessibilityService)  │
│    - Detects focused EditText/TextInput across ANY app       │
│    - Coordinates on-screen Flow Bubble                      │
│    - Injects text via AccessibilityNodeInfo.ACTION_SET_TEXT │
│      (with automatic Clipboard + ACTION_PASTE fallback)     │
└──────────────────────────┬──────────────────────────────────┘
                           │ User taps Floating Bubble & speaks
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. FloatingBubbleManager (WindowManager Overlay)            │
│    - TYPE_APPLICATION_OVERLAY floating draggable pill       │
│    - Smooth touch drag & edge snapping                      │
│    - States: Idle -> Recording -> Polishing -> Inserted     │
└──────────────────────────┬──────────────────────────────────┘
                           │ Compressed AAC audio (.m4a)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. Indic Speech Recognition (ASR) Engine                    │
│    - Groq Whisper-large-v3 (<300ms roundtrip)               │
│    - Or Sarvam AI (Saarika:v2 for pure regional dialects)   │
└──────────────────────────┬──────────────────────────────────┘
                           │ Raw Transcript
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. Fast LLM Tone & English Polisher                         │
│    - Llama-3.3-70B-Versatile on Groq                        │
│    - Transforms Hinglish & colloquial phrasing into         │
│      natural, fluent, grammatically polished English         │
│    - Adapts tone: Natural, Professional, Casual, or Concise │
└─────────────────────────────────────────────────────────────┘
```

---

## 🚀 Features

- **Floating Flow Bubble**: Persistent, lightweight floating pill that stays on your screen or appears whenever an input field is focused.
- **Accents & Code-Switching**: Recognizes mixed-language speech (Hinglish, Tanglish, etc.), handles conversational pauses and colloquialisms.
- **Grammar & Tone Refinement**: Turns thoughts like *"bhai client ko update bhej do kal tak report de denge"* into *"Hey, please update the client that we will deliver the report by tomorrow."*
- **Universal Injection**: Types directly into WhatsApp, Gmail, LinkedIn, Slack, Telegram, Twitter/X, and SMS without awkward copy-pasting.
- **Ultra-Fast (< 1s)**: Powered by Groq's LPUs for near-instant speech recognition and rewriting.

---

## 📁 Project Structure

```
flow-voice-android/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml                  # Permissions, Service declarations
│   │   ├── java/com/flowvoice/app/
│   │   │   ├── FlowApplication.kt              # Notification channels & app context
│   │   │   ├── audio/
│   │   │   │   └── AudioRecorderManager.kt     # High quality AAC recording & amplitudes
│   │   │   ├── data/
│   │   │   │   └── PreferencesManager.kt       # API keys, tone, provider config
│   │   │   ├── network/
│   │   │   │   ├── GroqClient.kt               # Groq Whisper + Llama 3.3 pipeline
│   │   │   │   ├── SarvamClient.kt             # Sarvam AI Saarika & translation
│   │   │   │   └── SpeechPolisherEngine.kt     # High-level pipeline coordinator
│   │   │   ├── service/
│   │   │   │   └── FlowAccessibilityService.kt # Universal text field detection & injection
│   │   │   ├── ui/
│   │   │   │   ├── FloatingBubbleManager.kt    # WindowManager floating overlay
│   │   │   │   └── MainActivity.kt             # Onboarding, permissions & settings
│   │   │   └── utils/
│   │   │       └── PermissionUtils.kt          # Overlay, Accessibility & Mic checks
│   │   └── res/
│   │       ├── layout/
│   │       │   ├── activity_main.xml           # Dashboard UI
│   │       │   └── layout_floating_bubble.xml  # Floating pill UI states
│   │       ├── values/
│   │       │   ├── colors.xml, strings.xml, themes.xml
│   │       └── xml/
│   │           └── accessibility_service_config.xml
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 🛠️ How to Open and Run in Android Studio

1. **Open Android Studio**.
2. Select **File > Open...** and navigate to:
   ```
   d:\From Github\speech-text\flow-voice-android
   ```
3. Android Studio will automatically sync the Gradle files.
4. Connect an Android phone (or start an emulator with Android 8.0+ / API 26+).
5. Click **Run (`Shift + F10`)**.

---

## ⚙️ Initial Setup Inside the App

1. **Grant Permissions**:
   - **Display over other apps**: Enables the floating bubble on screen.
   - **Accessibility Service**: Enables FlowVoice to detect text inputs and paste text.
   - **Microphone**: Records your voice.
2. **Configure AI Engine**:
   - **Groq (Recommended)**: Get a free API key at [console.groq.com](https://console.groq.com). Groq gives sub-second transcription with `whisper-large-v3` and `llama-3.3-70b-versatile`.
   - **Sarvam AI**: For specialized pure regional Indic dialects at [sarvam.ai](https://www.sarvam.ai/).
3. **Pick Your Tone**:
   - *Natural & Fluent*
   - *Professional / Work*
   - *Casual / Chat*
   - *Concise & Direct*
4. Tap **"Show Floating Bubble Now"** or tap any input field to test!
