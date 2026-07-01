package com.hermes.voice

import android.media.*
import android.media.AudioRecord
import android.os.Build
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class AudioBridge(private val activity: MainActivity, private val webView: WebView) {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var audioData = ByteArrayOutputStream()
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val audioManager: android.media.AudioManager by lazy {
        activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    }

    private var mediaPlayer: MediaPlayer? = null
    private var speakerEnabled = false
    private var bluetoothEnabled = false

    @JavascriptInterface
    fun startRecording(): String {
        if (isRecording) return "already_recording"

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            return "error_bad_value"
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return "error_init"
            }

            audioData.reset()
            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        audioData.write(buffer, 0, read)
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
            recordingThread?.join(2000)
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            val pcmData = audioData.toByteArray()
            audioData.reset()

            val oggData = pcmToOgg(pcmData)
            return Base64.encodeToString(oggData, Base64.NO_WRAP)
        } catch (e: Exception) {
            return ""
        }
    }

    private fun pcmToOgg(pcmData: ByteArray): ByteArray {
        // For simplicity, wrap PCM in a WAV-like container
        // The bridge server accepts audio/ogg but also handles raw audio
        val sampleCount = pcmData.size / 2
        val wav = ByteArrayOutputStream()
        wav.write("RIFF".toByteArray())
        wav.write(intToBytes(36 + pcmData.size))
        wav.write("WAVE".toByteArray())
        wav.write("fmt ".toByteArray())
        wav.write(intToBytes(16))
        wav.write(shortToBytes(1)) // PCM
        wav.write(shortToBytes(1)) // mono
        wav.write(intToBytes(sampleRate))
        wav.write(intToBytes(sampleRate * 2))
        wav.write(shortToBytes(2))
        wav.write(shortToBytes(16))
        wav.write("data".toByteArray())
        wav.write(intToBytes(pcmData.size))
        wav.write(pcmData)
        return wav.toByteArray()
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        )
    }

    private fun shortToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte()
        )
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
        activity.runOnUiThread {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
        }
    }

    @JavascriptInterface
    fun setSpeakerEnabled(enabled: Boolean) {
        speakerEnabled = enabled
        audioManager.isSpeakerphoneOn = enabled

        if (enabled) {
            bluetoothEnabled = false
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
        } else {
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        }
    }

    @JavascriptInterface
    fun setBluetoothEnabled(enabled: Boolean) {
        bluetoothEnabled = enabled
        if (enabled) {
            speakerEnabled = false
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        } else {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
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
        return prefs.getString("bridge_url", "http://100.67.204.21:8700") ?: "http://100.67.204.21:8700"
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
}
