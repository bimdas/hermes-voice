package com.hermes.voice

import android.media.*
import android.media.AudioRecord
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

class AudioBridge(private val activity: MainActivity, private val webView: WebView) {

    private var mediaRecorder: MediaRecorder? = null
    @Volatile private var isRecording = false
    private var recordingThread: Thread? = null
    private var tempAudioFile: java.io.File? = null
    private var currentMimeType = "audio/wav"

    private val audioManager: android.media.AudioManager by lazy {
        activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    }

    private var mediaPlayer: MediaPlayer? = null
    private var speakerEnabled = false
    private var bluetoothEnabled = false

    // Android TTS engine
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsEngine: String? = null // default to system engine

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(activity, { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val engine = tts?.defaultEngine ?: ""
                val result = tts?.setLanguage(Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED
                activity.runOnUiThread {
                    webView.evaluateJavascript(
                        "if (window.onTtsReady) window.onTtsReady('$engine', ${result == TextToSpeech.LANG_AVAILABLE});",
                        null
                    )
                }
            }
        }, ttsEngine)
    }

    @JavascriptInterface
    fun startRecording(): String {
        if (isRecording) return "already_recording"

        try {
            val useOgg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val fileSuffix = if (useOgg) ".ogg" else ".m4a"
            currentMimeType = if (useOgg) "audio/ogg" else "audio/mp4"

            tempAudioFile = java.io.File.createTempFile("hermes_rec_", fileSuffix, activity.cacheDir)

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(activity)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (useOgg) {
                    setOutputFormat(11) // MediaRecorder.OutputFormat.OGG is 11
                    setAudioEncoder(7)  // MediaRecorder.AudioEncoder.OPUS is 7
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                setAudioEncodingBitRate(24000)
                setAudioSamplingRate(16000)
                setOutputFile(tempAudioFile?.absolutePath)
                prepare()
                start()
            }

            isRecording = true

            val vadEnabled = isVadEnabled()
            val silenceDurationMs = getSilenceDuration()
            val speechThreshold = getSpeechThreshold().toDouble()
            var hasSpoken = false
            var lastActiveTime = System.currentTimeMillis()

            recordingThread = Thread {
                var lastAmplitudeUpdate = 0L
                while (isRecording) {
                    try {
                        val maxAmp = mediaRecorder?.maxAmplitude ?: 0
                        val rms = maxAmp.toDouble()

                        val now = System.currentTimeMillis()
                        if (now - lastAmplitudeUpdate > 50) {
                            lastAmplitudeUpdate = now
                            activity.runOnUiThread {
                                webView.evaluateJavascript("if (window.onMicVolume) window.onMicVolume($rms);", null)
                            }
                        }

                        if (vadEnabled) {
                            if (rms > speechThreshold) {
                                if (!hasSpoken) {
                                    hasSpoken = true
                                    activity.runOnUiThread {
                                        webView.evaluateJavascript("if (window.onSpeechStarted) window.onSpeechStarted();", null)
                                    }
                                }
                                lastActiveTime = now
                            } else if (hasSpoken) {
                                if (now - lastActiveTime > silenceDurationMs) {
                                    activity.runOnUiThread {
                                        webView.evaluateJavascript("if (window.onVadSilenceTriggered) window.onVadSilenceTriggered();", null)
                                    }
                                    stopAndSendNativeInternal()
                                    hasSpoken = false
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // MediaRecorder might have been stopped/released by another thread
                    }

                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }.apply { start() }

            return "ok"
        } catch (e: SecurityException) {
            return "permission_denied"
        } catch (e: Exception) {
            return "error:${e.message}"
        }
    }

    @JavascriptInterface
    fun stopRecording(): String {
        if (!isRecording) return ""
        isRecording = false

        try {
            recordingThread?.interrupt()
            recordingThread?.join(2000)
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null

            val file = tempAudioFile
            if (file != null && file.exists()) {
                val data = file.readBytes()
                file.delete()
                tempAudioFile = null
                return Base64.encodeToString(data, Base64.NO_WRAP)
            }
            return ""
        } catch (e: Exception) {
            return ""
        }
    }

    @JavascriptInterface
    fun stopAndSendNative() {
        stopAndSendNativeInternal()
    }

    private fun stopAndSendNativeInternal() {
        if (!isRecording) return
        isRecording = false

        Thread {
            var fileData: ByteArray? = null
            var mime: String? = null
            try {
                recordingThread?.interrupt()
                recordingThread?.join(2000)
                mediaRecorder?.stop()
            } catch (e: Exception) {
                // Log or handle stop exception safely
            } finally {
                try {
                    mediaRecorder?.release()
                } catch (e: Exception) {}
                mediaRecorder = null

                val file = tempAudioFile
                if (file != null && file.exists()) {
                    if (file.length() > 0) {
                        try {
                            fileData = file.readBytes()
                            mime = currentMimeType
                        } catch (e: Exception) {}
                    }
                    file.delete()
                }
                tempAudioFile = null
            }

            if (fileData != null && mime != null) {
                sendAudioToBridge(fileData, mime)
            } else {
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('No audio recorded file found');", null)
                }
            }
        }.start()
    }

    private fun sendAudioToBridge(audioData: ByteArray, mimeType: String) {
        Thread {
            try {
                val bridgeUrl = getBridgeUrl()
                // Request text-only mode (no server TTS) - we speak locally
                val useLocalTts = isLocalTtsEnabled()
                val endpoint = if (useLocalTts) "$bridgeUrl/voice?tts=false" else "$bridgeUrl/voice"
                val url = java.net.URL(endpoint)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = getRequestTimeout().toInt()
                conn.doOutput = true
                conn.doInput = true
                conn.setRequestProperty("Content-Type", mimeType)

                conn.outputStream.use { os ->
                    os.write(audioData)
                    os.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    if (useLocalTts) {
                        // Text-only JSON response - speak locally
                        val responseBody = conn.inputStream.use { it.readBytes() }
                        val json = JSONObject(String(responseBody))
                        val userTranscript = json.optString("text_in", "")
                        val assistantResponse = json.optString("text_out", "")
                        val timings = json.optString("timings", "{}")

                        activity.runOnUiThread {
                            val escUser = JSONObject.quote(userTranscript)
                            val escAssistant = JSONObject.quote(assistantResponse)
                            webView.evaluateJavascript("if (window.onBridgeSuccess) window.onBridgeSuccess($escUser, $escAssistant);", null)

                            // Speak locally via Android TTS
                            speakText(assistantResponse)
                        }
                    } else {
                        // Legacy mode: server returned audio
                        val userTranscript = conn.getHeaderField("X-Transcript") ?: ""
                        val assistantResponse = conn.getHeaderField("X-Response") ?: ""
                        val audioBytes = conn.inputStream.use { it.readBytes() }

                        activity.runOnUiThread {
                            val escUser = JSONObject.quote(userTranscript)
                            val escAssistant = JSONObject.quote(assistantResponse)
                            webView.evaluateJavascript("if (window.onBridgeSuccess) window.onBridgeSuccess($escUser, $escAssistant);", null)

                            playAudioBytes(audioBytes)
                        }
                    }
                } else {
                    val errorMsg = "Server returned code $responseCode"
                    activity.runOnUiThread {
                        webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('$errorMsg');", null)
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('$errorMsg');", null)
                }
            }
        }.start()
    }

    // -----------------------------------------------------------------------
    // Android native TTS
    // -----------------------------------------------------------------------

    private fun speakText(text: String) {
        if (!ttsReady || tts == null) {
            webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('TTS not ready');", null)
            return
        }

        val speed = getTtsSpeed()
        tts?.setSpeechRate(speed)

        webView.evaluateJavascript("if (window.onAudioPlayStarted) window.onAudioPlayStarted();", null)

        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onAudioPlayCompleted) window.onAudioPlayCompleted();", null)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onAudioError) window.onAudioError('TTS error');", null)
                }
            }
        })

        val params = android.os.Bundle()
        if (speakerEnabled) {
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        } else {
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "hermes_tts")
    }

    @JavascriptInterface
    fun stopSpeaking() {
        tts?.stop()
    }

    private fun playAudioBytes(audioBytes: ByteArray) {
        try {
            activity.runOnUiThread {
                try {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer()

                    val tempFile = java.io.File.createTempFile("audio_resp_", ".mp3", activity.cacheDir)
                    tempFile.writeBytes(audioBytes)
                    tempFile.deleteOnExit()

                    mediaPlayer?.apply {
                        setDataSource(tempFile.absolutePath)

                        if (speakerEnabled) {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                            )
                        } else {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                        }

                        setOnPreparedListener {
                            start()
                            webView.evaluateJavascript("if (window.onAudioPlayStarted) window.onAudioPlayStarted();", null)
                        }
                        setOnCompletionListener {
                            webView.evaluateJavascript("if (window.onAudioPlayCompleted) window.onAudioPlayCompleted();", null)
                            tempFile.delete()
                        }
                        setOnErrorListener { _, what, extra ->
                            webView.evaluateJavascript("if (window.onAudioError) window.onAudioError('$what:$extra');", null)
                            tempFile.delete()
                            true
                        }
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    webView.evaluateJavascript("if (window.onAudioError) window.onAudioError('${e.message}');", null)
                }
            }
        } catch (e: Exception) {
            webView.evaluateJavascript("if (window.onAudioError) window.onAudioError('${e.message}');", null)
        }
    }

    @JavascriptInterface
    fun playAudio(base64Audio: String) {
        try {
            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)

            activity.runOnUiThread {
                try {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer()

                    val tempFile = java.io.File.createTempFile("audio_", ".mp3", activity.cacheDir)
                    tempFile.writeBytes(audioBytes)
                    tempFile.deleteOnExit()

                    mediaPlayer?.apply {
                        setDataSource(tempFile.absolutePath)

                        if (speakerEnabled) {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                            )
                        } else {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                        }

                        setOnPreparedListener {
                            start()
                            webView.evaluateJavascript("onAudioPlayStarted()", null)
                        }
                        setOnCompletionListener {
                            webView.evaluateJavascript("onAudioPlayCompleted()", null)
                            tempFile.delete()
                        }
                        setOnErrorListener { _, what, extra ->
                            webView.evaluateJavascript("onAudioError('$what:$extra')", null)
                            tempFile.delete()
                            true
                        }
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    webView.evaluateJavascript("onAudioError('${e.message}')", null)
                }
            }
        } catch (e: Exception) {
            webView.evaluateJavascript("onAudioError('${e.message}')", null)
        }
    }

    @JavascriptInterface
    fun stopAudio() {
        tts?.stop()
        activity.runOnUiThread {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
        }
    }

    private fun routeToSpeaker(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val speakerDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                }
            } else {
                val currentDevice = audioManager.communicationDevice
                if (currentDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    audioManager.clearCommunicationDevice()
                }
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
        }
    }

    private fun routeToBluetooth(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val bluetoothDevice = devices.find {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (bluetoothDevice != null) {
                    audioManager.setCommunicationDevice(bluetoothDevice)
                }
            } else {
                val currentDevice = audioManager.communicationDevice
                if (currentDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    currentDevice?.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    audioManager.clearCommunicationDevice()
                }
            }
        } else {
            @Suppress("DEPRECATION")
            if (enabled) {
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
            } else {
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
            }
        }
    }

    @JavascriptInterface
    fun setSpeakerEnabled(enabled: Boolean) {
        speakerEnabled = enabled

        if (enabled) {
            bluetoothEnabled = false
            routeToBluetooth(false)
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
            routeToSpeaker(true)
        } else {
            routeToSpeaker(false)
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        }
    }

    @JavascriptInterface
    fun setBluetoothEnabled(enabled: Boolean) {
        bluetoothEnabled = enabled
        if (enabled) {
            speakerEnabled = false
            routeToSpeaker(false)
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            routeToBluetooth(true)
        } else {
            routeToBluetooth(false)
            if (!speakerEnabled) {
                audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            }
        }
    }

    @JavascriptInterface
    fun getAudioDevices(): String {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
        val result = JSONArray()
        for (device in devices) {
            val obj = JSONObject()
            obj.put("id", device.id)
            obj.put("name", device.productName?.toString() ?: "Unknown")
            obj.put("type", device.type)
            obj.put("isSource", device.isSource)
            result.put(obj)
        }
        return result.toString()
    }

    @JavascriptInterface
    fun getBridgeUrl(): String {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getString("bridge_url", "http://192.168.1.100:8700") ?: "http://192.168.1.100:8700"
    }

    @JavascriptInterface
    fun setBridgeUrl(url: String) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("bridge_url", url).apply()
    }

    @JavascriptInterface
    fun hasPermission(permission: String): Boolean {
        return activity.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    @JavascriptInterface
    fun isVadEnabled(): Boolean {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean("vad_enabled", false)
    }

    @JavascriptInterface
    fun setVadEnabled(enabled: Boolean) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("vad_enabled", enabled).apply()
    }

    @JavascriptInterface
    fun getSilenceDuration(): Long {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getLong("silence_duration", 1500L)
    }

    @JavascriptInterface
    fun setSilenceDuration(durationMs: Long) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putLong("silence_duration", durationMs).apply()
    }

    @JavascriptInterface
    fun getSpeechThreshold(): Float {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getFloat("speech_threshold", 1000f)
    }

    @JavascriptInterface
    fun setSpeechThreshold(threshold: Float) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putFloat("speech_threshold", threshold).apply()
    }

    @JavascriptInterface
    fun getRequestTimeout(): Long {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getLong("request_timeout", 120000L)
    }

    @JavascriptInterface
    fun setRequestTimeout(timeoutMs: Long) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putLong("request_timeout", timeoutMs).apply()
    }

    // TTS settings
    @JavascriptInterface
    fun isLocalTtsEnabled(): Boolean {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean("local_tts_enabled", true) // default ON
    }

    @JavascriptInterface
    fun setLocalTtsEnabled(enabled: Boolean) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("local_tts_enabled", enabled).apply()
    }

    @JavascriptInterface
    fun getTtsSpeed(): Float {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getFloat("tts_speed", 1.1f) // slightly faster than normal
    }

    @JavascriptInterface
    fun setTtsSpeed(speed: Float) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putFloat("tts_speed", speed).apply()
    }

    @JavascriptInterface
    fun startForegroundService() {
        activity.runOnUiThread {
            try {
                val intent = android.content.Intent(activity, VoiceService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(intent)
                } else {
                    activity.startService(intent)
                }
            } catch (e: Exception) {
                // Ignore or log error
            }
        }
    }

    @JavascriptInterface
    fun stopForegroundService() {
        activity.runOnUiThread {
            try {
                val intent = android.content.Intent(activity, VoiceService::class.java)
                activity.stopService(intent)
            } catch (e: Exception) {
                // Ignore or log error
            }
        }
    }
}
