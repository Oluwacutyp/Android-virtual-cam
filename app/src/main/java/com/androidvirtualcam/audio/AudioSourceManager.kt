package com.androidvirtualcam.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AudioSourceManager – handles USB-C audio interface, Bluetooth mic, lavalier via headphone jack
 * 
 * Enumerates available input devices and allows selecting:
 * - Built-in mic (default)
 * - USB audio interface (USB-C)
 * - Bluetooth SCO (Bluetooth mic)
 * - Wired headset / lavalier via headphone jack
 * - Unprocessed, Voice Communication, Camcorder sources
 */
class AudioSourceManager(private val context: Context) {

    private val tag = "AudioSourceManager"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    enum class AudioSourceType(val displayName: String, val mediaRecorderSource: Int) {
        BUILTIN_MIC("Built-in Mic", MediaRecorder.AudioSource.MIC),
        VOICE_COMMUNICATION("Voice Communication", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
        CAMCORDER("Camcorder", MediaRecorder.AudioSource.CAMCORDER),
        UNPROCESSED("Unprocessed (USB interface)", MediaRecorder.AudioSource.UNPROCESSED),
        VOICE_RECOGNITION("Voice Recognition", MediaRecorder.AudioSource.VOICE_RECOGNITION),
        DEFAULT("Default", MediaRecorder.AudioSource.DEFAULT)
    }

    enum class InputDeviceType(val displayName: String) {
        BUILTIN("Phone Mic"),
        USB("USB Audio Interface"),
        BLUETOOTH("Bluetooth Mic"),
        WIRED_HEADSET("Wired Headset / Lavalier"),
        UNKNOWN("Unknown")
    }

    data class AudioDevice(
        val id: Int,
        val name: String,
        val type: InputDeviceType,
        val deviceInfo: AudioDeviceInfo?,
        val isAvailable: Boolean
    )

    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices

    private val _selectedSourceType = MutableStateFlow(AudioSourceType.BUILTIN_MIC)
    val selectedSourceType: StateFlow<AudioSourceType> = _selectedSourceType

    private val _selectedDevice = MutableStateFlow<AudioDevice?>(null)
    val selectedDevice: StateFlow<AudioDevice?> = _selectedDevice

    private val _isBluetoothScoOn = MutableStateFlow(false)
    val isBluetoothScoOn: StateFlow<Boolean> = _isBluetoothScoOn

    init {
        refreshDevices()
    }

    fun refreshDevices() {
        try {
            val devices = mutableListOf<AudioDevice>()

            // Built-in always available
            devices.add(AudioDevice(0, "Built-in Microphone", InputDeviceType.BUILTIN, null, true))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                for (dev in inputDevices) {
                    val type = when (dev.type) {
                        AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputDeviceType.BUILTIN
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> InputDeviceType.WIRED_HEADSET
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> InputDeviceType.BLUETOOTH
                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_HEADSET -> InputDeviceType.USB
                        else -> InputDeviceType.UNKNOWN
                    }

                    // Avoid duplicate builtin
                    if (type == InputDeviceType.BUILTIN && devices.any { it.type == InputDeviceType.BUILTIN }) continue

                    val name = when (type) {
                        InputDeviceType.USB -> "USB Audio: ${dev.productName ?: "Interface"}"
                        InputDeviceType.BLUETOOTH -> "Bluetooth: ${dev.productName ?: "Mic"}"
                        InputDeviceType.WIRED_HEADSET -> "Wired Lavalier: ${dev.productName ?: "Headset"}"
                        else -> "${type.displayName}: ${dev.productName ?: "Device"}"
                    }

                    devices.add(AudioDevice(dev.id, name, type, dev, true))
                    Log.d(tag, "Found input device: $name type=${dev.type} id=${dev.id}")
                }
            }

            // Check wired headset plugged
            if (audioManager.isWiredHeadsetOn) {
                if (devices.none { it.type == InputDeviceType.WIRED_HEADSET }) {
                    devices.add(AudioDevice(100, "Wired Headset (Lavalier) – Detected", InputDeviceType.WIRED_HEADSET, null, true))
                }
            }

            // Check Bluetooth
            if (audioManager.isBluetoothScoAvailableOffCall) {
                if (devices.none { it.type == InputDeviceType.BLUETOOTH }) {
                    devices.add(AudioDevice(101, "Bluetooth Mic – Available (SCO)", InputDeviceType.BLUETOOTH, null, audioManager.isBluetoothScoOn))
                }
            }

            _availableDevices.value = devices
            Log.i(tag, "Refreshed devices: ${devices.size} found")

        } catch (e: Exception) {
            Log.e(tag, "refreshDevices failed", e)
        }
    }

    fun setAudioSourceType(type: AudioSourceType) {
        _selectedSourceType.value = type
        Log.i(tag, "Audio source type set to ${type.displayName} (${type.mediaRecorderSource})")

        // Auto-select device based on source type
        when (type) {
            AudioSourceType.UNPROCESSED -> {
                // Prefer USB
                val usb = _availableDevices.value.firstOrNull { it.type == InputDeviceType.USB }
                if (usb != null) _selectedDevice.value = usb
            }
            AudioSourceType.VOICE_COMMUNICATION -> {
                // Prefer Bluetooth or wired
                val bt = _availableDevices.value.firstOrNull { it.type == InputDeviceType.BLUETOOTH }
                val wired = _availableDevices.value.firstOrNull { it.type == InputDeviceType.WIRED_HEADSET }
                _selectedDevice.value = bt ?: wired ?: _availableDevices.value.firstOrNull { it.type == InputDeviceType.BUILTIN }
            }
            else -> {}
        }
    }

    fun selectDevice(device: AudioDevice) {
        _selectedDevice.value = device
        Log.i(tag, "Selected device: ${device.name} type=${device.type}")

        when (device.type) {
            InputDeviceType.BLUETOOTH -> {
                enableBluetoothSco(true)
                // For BT, use VOICE_COMMUNICATION source for best compatibility
                _selectedSourceType.value = AudioSourceType.VOICE_COMMUNICATION
            }
            InputDeviceType.USB -> {
                enableBluetoothSco(false)
                _selectedSourceType.value = AudioSourceType.UNPROCESSED
            }
            InputDeviceType.WIRED_HEADSET -> {
                enableBluetoothSco(false)
                _selectedSourceType.value = AudioSourceType.CAMCORDER
            }
            InputDeviceType.BUILTIN -> {
                enableBluetoothSco(false)
                _selectedSourceType.value = AudioSourceType.BUILTIN_MIC
            }
            else -> {}
        }
    }

    fun enableBluetoothSco(enable: Boolean) {
        try {
            if (enable) {
                if (!audioManager.isBluetoothScoOn) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    _isBluetoothScoOn.value = true
                    Log.i(tag, "Bluetooth SCO enabled")
                }
            } else {
                if (audioManager.isBluetoothScoOn) {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                    audioManager.mode = AudioManager.MODE_NORMAL
                    _isBluetoothScoOn.value = false
                    Log.i(tag, "Bluetooth SCO disabled")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "enableBluetoothSco failed", e)
        }
    }

    fun getCurrentAudioSource(): Int {
        return _selectedSourceType.value.mediaRecorderSource
    }

    fun getPreferredDeviceInfo(): AudioDeviceInfo? {
        return _selectedDevice.value?.deviceInfo
    }

    fun getDeviceDescription(): String {
        val device = _selectedDevice.value
        val source = _selectedSourceType.value
        return if (device != null) {
            "${device.name} via ${source.displayName}"
        } else {
            source.displayName
        }
    }

    fun release() {
        try {
            enableBluetoothSco(false)
        } catch (_: Exception) {}
    }
}
