# Hermes Voice

A hands-free voice assistant app for Android that connects to a remote voice bridge server.

## Features

- **Voice Recording**: Record audio via microphone and send to bridge server
- **Audio Playback**: Play responses through speaker, earpiece, or Bluetooth
- **Dark Call-Like UI**: Fullscreen immersive interface with pulsing button and waveform animations
- **Audio Routing**: Switch between earpiece, speakerphone, and Bluetooth SCO
- **Configurable Bridge URL**: Connect to any voice bridge server

## Architecture

- **WebView-based UI**: Single `index.html` with inline CSS/JS for the call interface
- **Native AudioBridge**: Kotlin `@JavascriptInterface` for mic recording and audio playback
- **VoiceService**: Foreground service to keep connection alive

## Bridge Server API

Connects to a voice bridge server with these endpoints:

- `POST /voice` - Send audio, get audio response with transcript headers
- `POST /chat` - Send text, get audio response
- `GET /health` - Server health check

## Build

The app is built via GitHub Actions CI (aapt2 requires x86_64).

Push to `main` branch to trigger a build. The APK will be available as a build artifact.

## Requirements

- Android 9+ (API 28)
- Microphone permission
- Bluetooth permission (optional, for BT audio)
