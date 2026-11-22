package com.msp1974.vacompanion.wyoming

import kotlinx.serialization.json.JsonPrimitive
import com.msp1974.vacompanion.device.DeviceManager
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject

class WyomingCapabilitiesBuilder @Inject constructor(private val deviceManager: DeviceManager) {

    val config = deviceManager.config
    val deviceInfo = deviceManager.deviceInfo

    @OptIn(ExperimentalSerializationApi::class)
    fun buildInfo(): JsonObject {
        val baseCapabilities = deviceInfo.getJson()
        val availableWakeSounds = config.availableWakeSounds
        val availableAlarms = config.availableAlarms

        return buildJsonObject {
           baseCapabilities.forEach { (key, value) -> put(key, value) }
            putJsonArray("wake_sounds") {
                availableWakeSounds.forEach { sound ->
                    add(buildJsonObject {
                        put("id", sound.id)
                        put("name", sound.name)
                    })
                }
            }
            // casa extension (docs/PROTOCOL.md in felipearmat/vaca-linux): version and features,
            // so the integration creates only what the device supports.
            put("has_screen", true)
            put("casa_protocol", CASA_PROTOCOL)
            putJsonArray("casa_features") { CASA_FEATURES.forEach { add(JsonPrimitive(it)) } }
            // A dedicated satellite whose app is device owner: HA can update it silently
            // (install_update) and release it (release_device_owner).
            put("device_owner", com.msp1974.vacompanion.device.ManagedDevice.isOwner(deviceManager.context))
            putJsonArray("alarms") {
                availableAlarms.forEach { alarm ->
                    add(buildJsonObject {
                        put("id", alarm.id)
                        put("name", alarm.name)
                    })
                }
            }
        }
    }
}

const val CASA_PROTOCOL = 1
val CASA_FEATURES = listOf(
    "thinking_sound", "thinking_intro_sound", "thinking_delay", "voice_gain", "echo_cancellation",
    "wake_word_clips", "wake_word_threshold_music", "local_end_of_speech", "assist_indicator",
    "wake_word_detection", "wake_word_near_miss", "ambient_capture", "level_offset_db",
    "mic_source", "mic_auto_switch", "output_auto_switch", "conversation_ducking", "indicator_prompt", "chat_drawer", "ambient_noise",
    "bt_keep_alive", "install_update", "remove_accounts",
)
