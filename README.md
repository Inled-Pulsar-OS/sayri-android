# Sayri Android

Android client and system assistant for Sayri, the native artificial intelligence assistant of Pulsar OS.

## Features

- Default Assistant Integration: Configurable as the default Android assistant (ACTION_ASSIST), allowing invocation via long press on the power button, home button, or bottom swipe gesture.
- Floating Assist Card: Non-intrusive floating assistant interface with an animated particle orb (SayriOrbView), streaming responses, and quick voice/text input.
- Circle to Search: Visual search overlay that allows circling or tapping any part of the active screen to analyze context and query the AI model.
- System Voice Interaction and Dictation: Implements Android VoiceInteractionService and RecognitionService for system-wide speech recognition and dictation across any application.
- Full Chat Interface: Standalone messaging window with multi-session history, real-time Markdown rendering, and code syntax styling.
- Local and Cloud Model Support: Supports OpenAI-compatible endpoints, Ollama, custom remote models, and automatic local network discovery of the Sayri daemon running on Pulsar OS.
- Device Tool Integration: Autonomous device control tools, including toggling flashlight, Wi-Fi, Bluetooth, volume, display brightness, and launching installed applications.
- Accessibility Service: Optional accessibility service for screen context inspection and assistive automation.

## Requirements

- Android 8.0 (API level 26) or higher.
- Java Development Kit (JDK) 17 or higher.
- Android SDK Platform 34 and Build Tools.

## Building from Source

Clone the repository:

```bash
git clone https://github.com/Inled-Pulsar-OS/sayri-android.git
cd sayri-android
```

Compile the debug APK using the Gradle wrapper:

```bash
./gradlew assembleDebug
```

The output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

To generate a release build:

```bash
./gradlew assembleRelease
```

## Testing and Installation

1. Connect your Android device via USB with USB Debugging enabled, or pair wirelessly with ADB:

```bash
adb connect <device-ip>:5555
```

2. Install the application on your device:

```bash
./gradlew installDebug
# or using adb directly:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

3. Launch and configure the application:
- Open Sayri from the application launcher.
- Open Settings to configure your API endpoint (local Pulsar OS host, Ollama URL, or OpenAI-compatible provider) and model name.
- To set Sayri as your default assistant, go to Android Settings -> Apps -> Default Apps -> Digital Assistant App, and select Sayri.
- Test the assistant by long-pressing the home button or using your device's assistant gesture.

## Architecture

- Language and Toolkit: 100% Kotlin with Android Jetpack, ViewBinding, and Kotlin Coroutines.
- Networking: OkHttp with Server-Sent Events (SSE) for streaming completions (Streaming API).
- Voice and Speech: Android SpeechRecognizer with custom VoiceInteractionService pipeline.
- Tools: DeviceToolManager using Android system services (CameraManager, WifiManager, AudioManager).

## License

Part of the Pulsar OS ecosystem by Inled Group.
