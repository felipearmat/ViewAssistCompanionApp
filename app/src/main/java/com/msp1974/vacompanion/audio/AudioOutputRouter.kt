package com.msp1974.vacompanion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber

/**
 * Resolves [APPConfig.audioOutputId] (-1 = automatic, otherwise an [AudioDeviceInfo.getId])
 * into the actual device to route playback to. Used by every player - MusicPlayerService,
 * AlarmService, SoundEffectsPlayer, VoicePlayerService - via
 * `ExoPlayer.setPreferredAudioDevice()`/`AudioTrack.setPreferredDevice()`, so output routing
 * stays centralized and consistent rather than each player resolving the id separately.
 */
object AudioOutputRouter {

    /** Null means "automatic" - either the setting is -1, or the configured device isn't
     *  currently connected, in which case this logs and falls back to automatic rather than
     *  silently routing nowhere. */
    /**
     * The id of the output playback is actually going to, or -1 if it can't be told. That is the
     * selected device when one is set and connected; on automatic it is whatever Android routes
     * media to (asked directly on Android 13+, otherwise worked out in Android's usual priority).
     */
    /**
     * The external output to use when output_auto_switch is on: Bluetooth, then USB, wired, line
     * and HDMI. Null when only the device's own speaker is there.
     */
    fun externalOutput(outputs: Array<AudioDeviceInfo>): AudioDeviceInfo? {
        val priority = listOf(
            listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_HEADSET),
            listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY),
            listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG),
            listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC),
        )
        for (types in priority) {
            outputs.firstOrNull { it.type in types && it.isSink }?.let { return it }
        }
        return null
    }

    /** True when playback is going to a Bluetooth speaker or headset (which plays late). */
    fun isBluetoothOutput(context: Context, config: APPConfig): Boolean {
        val id = activeOutputId(context, config)
        if (id < 0) return false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
        return device?.type in setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }

    fun activeOutputId(context: Context, config: APPConfig): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        if (config.outputAutoSwitch) externalOutput(outputs)?.let { return it.id }

        val selected = config.audioOutputId
        val selectedDevice = outputs.firstOrNull { it.id == selected }
        if (selectedDevice != null) {
            if (selectedDevice.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { return it.id }
            }
            return selected
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            audioManager.getAudioDevicesForAttributes(attributes).firstOrNull()?.let { return it.id }
        }

        val priority = listOf(
            listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
            listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE),
            listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC),
            listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE),
        )
        for (types in priority) {
            outputs.firstOrNull { it.type in types }?.let { return it.id }
        }
        return -1
    }

    /**
     * Points [track] at [device] only when it isn't already playing there. Changing a track's
     * preferred device invalidates it, and Android rebuilds it at the next start() or write
     * ("dead IAudioTrack ... creating a new one"), which can cut the start of what it plays.
     * A new track is routed as soon as it is built, so most of the time nothing needs doing.
     */
    fun routeIfNeeded(track: AudioTrack, device: AudioDeviceInfo?) {
        val current = track.routedDevice
        if (device == null) {
            if (track.preferredDevice != null) track.setPreferredDevice(null)
            return
        }
        if (current?.id == device.id && track.preferredDevice == null) return
        if (track.preferredDevice?.id == device.id) return
        Timber.d("AudioOutputRouter: routing track from ${current?.type} to ${device.type}")
        track.setPreferredDevice(device)
    }

    fun resolvePreferredDevice(context: Context, config: APPConfig): AudioDeviceInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (config.outputAutoSwitch) {
            externalOutput(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))?.let { return it }
        }
        val id = config.audioOutputId
        if (id < 0) return null

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val device = outputs.firstOrNull { it.id == id }
        if (device == null) {
            Timber.w("AudioOutputRouter: configured output id=$id not currently connected - falling back to automatic")
        }
        // A satellite is never listened to with the phone at the ear: the earpiece is nearly
        // inaudible across a room, so the built-in speaker is used instead.
        if (device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
            Timber.w("AudioOutputRouter: earpiece selected, using the built-in speaker")
            return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: device
        }
        return device
    }
}
