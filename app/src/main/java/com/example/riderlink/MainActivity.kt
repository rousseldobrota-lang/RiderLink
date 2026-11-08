package com.example.riderlink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val serviceId: UUID = UUID.fromString("c71f4d8e-6a2b-4a4c-9c10-1b2c3d4e5f60")
    private val serviceName = "RiderLink Intercom"
    private val sampleRate = 16000
    private val frameBytes = 640 // 20 ms of 16 kHz, 16-bit mono PCM
    private val running = AtomicBoolean(false)
    private var socket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var status: TextView? = null
    private var deviceSpinner: Spinner? = null
    private var connectButton: Button? = null
    private var listenButton: Button? = null
    private var disconnectButton: Button? = null
    private var adapter: BluetoothAdapter? = null
    private var pairedDevices: List<BluetoothDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        adapter = BluetoothAdapter.getDefaultAdapter()
        buildUi()
        requestNeededPermissions()
        refreshDevices()
    }

    private fun buildUi() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 34, 28, 28)
            setBackgroundColor(0xFF10231D.toInt())
        }
        val title = TextView(this).apply {
            text = "RIDERLINK"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Two-rider Bluetooth intercom"
            textSize = 16f
            setTextColor(0xFFB8D4C8.toInt())
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Pair both Android phones in Bluetooth Settings first."
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 24)
        }
        deviceSpinner = Spinner(this)
        val refresh = Button(this).apply {
            text = "Refresh paired phones"
            setOnClickListener { refreshDevices() }
        }
        listenButton = Button(this).apply {
            text = "CREATE INTERCOM (this phone listens)"
            setOnClickListener { startListening() }
        }
        connectButton = Button(this).apply {
            text = "CONNECT TO SELECTED PHONE"
            setOnClickListener { connectSelected() }
        }
        disconnectButton = Button(this).apply {
            text = "DISCONNECT"
            isEnabled = false
            setOnClickListener { disconnect() }
        }
        val note = TextView(this).apply {
            text = "Use one phone as the listener and the other as the connector. Keep RiderLink open during the ride. A compatible Bluetooth helmet headset can be used as the phone's audio input/output."
            textSize = 13f
            setTextColor(0xFFB8D4C8.toInt())
            setPadding(0, 24, 0, 0)
        }
        listOf(title, subtitle, status, deviceSpinner, refresh, listenButton,
            connectButton, disconnectButton, note).forEach { v ->
            layout.addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (v == title) 64 else LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (v is Button) topMargin = 8 })
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) wanted.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 10)
    }

    @SuppressLint("MissingPermission")
    private fun refreshDevices() {
        val a = adapter
        if (a == null) { setStatus("This phone does not support Bluetooth."); return }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Allow Nearby devices permission, then refresh.")
            return
        }
        if (!a.isEnabled) {
            setStatus("Turn on Bluetooth in Android Settings.")
            return
        }
        pairedDevices = a.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()
        val labels = pairedDevices.map { "${it.name ?: "Unknown device"}  (${it.address})" }
        deviceSpinner?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        setStatus(if (labels.isEmpty()) "No paired phones found. Pair the two phones in Android Bluetooth Settings."
                  else "Found ${labels.size} paired device(s).")
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (!permissionsOk()) return
        if (running.get()) return
        thread(name = "RiderLink-listen") {
            try {
                serverSocket = adapter?.listenUsingRfcommWithServiceRecord(serviceName, serviceId)
                runOnUiThread { setStatus("Waiting for the other rider to connect…") }
                val accepted = serverSocket?.accept() ?: throw IOException("Bluetooth listener unavailable")
                serverSocket?.close()
                serverSocket = null
                beginAudio(accepted)
            } catch (e: Exception) {
                runOnUiThread { setStatus("Could not listen: ${e.message}") }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectSelected() {
        if (!permissionsOk()) return
        if (running.get()) return
        val index = deviceSpinner?.selectedItemPosition ?: -1
        if (index !in pairedDevices.indices) {
            setStatus("Select a paired phone first.")
            return
        }
        val device = pairedDevices[index]
        thread(name = "RiderLink-connect") {
            try {
                adapter?.cancelDiscovery()
                val s = device.createRfcommSocketToServiceRecord(serviceId)
                runOnUiThread { setStatus("Connecting to ${device.name ?: "phone"}…") }
                s.connect()
                beginAudio(s)
            } catch (e: Exception) {
                runOnUiThread { setStatus("Connection failed: ${e.message}") }
            }
        }
    }

    private fun permissionsOk(): Boolean {
        val ok = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        if (!ok) setStatus("Grant Microphone and Nearby devices permissions, then try again.")
        return ok
    }

    private fun beginAudio(s: BluetoothSocket) {
        socket = s
        running.set(true)
        runOnUiThread {
            setStatus("CONNECTED — speak normally. Keep both phones connected.")
            listenButton?.isEnabled = false
            connectButton?.isEnabled = false
            disconnectButton?.isEnabled = true
        }
        thread(name = "RiderLink-audio") { audioLoop(s) }
    }

    private fun audioLoop(s: BluetoothSocket) {
        var recorder: AudioRecord? = null
        var player: AudioTrack? = null
        try {
            val minIn = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            val minOut = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            if (minIn <= 0 || minOut <= 0) throw IOException("Audio format not supported")
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minIn, frameBytes * 4)).build()
            player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minOut, frameBytes * 4))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            if (AcousticEchoCanceler.isAvailable()) {
                try { AcousticEchoCanceler.create(recorder.audioSessionId)?.enabled = true } catch (_: Exception) {}
            }
            recorder.startRecording()
            player.play()
            val input = s.inputStream
            val output = s.outputStream
            val sendThread = thread(name = "RiderLink-send") {
                val buffer = ByteArray(frameBytes)
                try {
                    while (running.get() && s.isConnected) {
                        var offset = 0
                        while (offset < buffer.size && running.get()) {
                            val n = recorder.read(buffer, offset, buffer.size - offset)
                            if (n <= 0) throw IOException("Microphone read failed")
                            offset += n
                        }
                        if (offset == buffer.size) {
                            synchronized(output) { output.write(buffer); output.flush() }
                        }
                    }
                } catch (_: Exception) { running.set(false) }
            }
            val buffer = ByteArray(frameBytes)
            while (running.get() && s.isConnected) {
                var offset = 0
                while (offset < buffer.size && running.get()) {
                    val n = input.read(buffer, offset, buffer.size - offset)
                    if (n < 0) throw IOException("Other rider disconnected")
                    offset += n
                }
                if (offset == buffer.size) player.write(buffer, 0, buffer.size)
            }
            sendThread.join(500)
        } catch (e: Exception) {
            runOnUiThread { setStatus("Audio link ended: ${e.message}") }
        } finally {
            running.set(false)
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            try { player?.stop() } catch (_: Exception) {}
            try { player?.release() } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
            socket = null
            runOnUiThread {
                listenButton?.isEnabled = true
                connectButton?.isEnabled = true
                disconnectButton?.isEnabled = false
                if (status?.text?.startsWith("CONNECTED") == true) setStatus("Disconnected.")
            }
        }
    }

    private fun disconnect() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        setStatus("Disconnecting…")
    }

    private fun setStatus(message: String) {
        runOnUiThread { status?.text = message }
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
