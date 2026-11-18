package com.msp1974.vacompanion.ui.layouts.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.audio.MicGate
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.VAViewModel

private const val VOICE = "Voice assistant"

// Features that are still being tried out, so that each can be switched off if it causes trouble.
fun experimentalConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    return ConfigSection(
        "Experimental", Icons.Default.Science, listOf(
            ConfigItem.Toggle(VOICE, "Suppress wake sound",
                "Turn the microphone right down while the wake sound plays. Use this when the sound is a spoken one, such as 'how can i help?', so it isn't heard as part of your command",
                Icons.Default.MicOff, key = "suppress_wake_sound",
                get = { c.suppressWakeSound }, set = { c.suppressWakeSound = it }),
            ConfigItem.Toggle(VOICE, "Assistant indicator",
                "A bar along the bottom of the screen: cyan while listening (it grows with your voice), pulsing blue while thinking, green while speaking, red on error. Nothing while idle",
                Icons.Default.LinearScale, key = "assist_indicator",
                get = { c.assistIndicatorEnabled }, set = { c.assistIndicatorEnabled = it }),
            ConfigItem.Toggle(VOICE, "Detect end of speech on the device",
                "When the assistant keeps listening for your reply, stop as soon as you stop talking, even when Home Assistant doesn't notice a short reply such as 'yes'",
                Icons.Default.KeyboardVoice, key = "local_end_of_speech",
                get = { c.localEndOfSpeech }, set = { c.localEndOfSpeech = it }),
            ConfigItem.FloatValue(VOICE, "Detection threshold with music",
                "Threshold used while this device plays music, which lowers the score of a real wake word. 0 uses the normal threshold. Never goes below 3. Best set by the Home Assistant calibration",
                Icons.Default.Tune, key = "wake_word_threshold_music", min = 0f, max = 10f, step = 0.5f,
                get = { c.wakeWordThresholdMusic * 10 }, set = { c.wakeWordThresholdMusic = it / 10f }),
            ConfigItem.IntValue(VOICE, "Voice gain",
                "Make the assistant's voice louder than the TTS produces, with compression so it doesn't clip. Useful when music is much louder than the voice",
                Icons.Default.Campaign, key = "voice_gain", min = 0, max = 12, unit = " dB",
                get = { c.voiceGainDb }, set = { c.voiceGainDb = it }),
            ConfigItem.Toggle(VOICE, "Music echo cancellation",
                "Take the music this device plays (Sendspin) out of what the microphone hears, so the wake word and your request are heard over it. Uses more CPU. Applies when the microphone next starts",
                Icons.Default.Hearing, key = "echo_cancellation",
                get = { c.echoCancellationEnabled }, set = { c.echoCancellationEnabled = it }),
            ConfigItem.Toggle(VOICE, "Send wake word clips",
                "Send Home Assistant the audio around each wake word detection (3 s before, 1 s after) and what came of it, to retrain the wake word model. Kept in memory only until sent",
                Icons.Default.GraphicEq, key = "wake_word_clips",
                get = { c.wakeWordClipsEnabled }, set = { c.wakeWordClipsEnabled = it }),
            ConfigItem.IntValue(VOICE, "Minimum microphone level",
                "Keep the microphone silent until it hears something louder than this, so whispers and quiet background sound are ignored. Measured on the raw microphone signal in dB below full scale, so closer to 0 is louder. The lowest value turns it off",
                Icons.Default.VoiceOverOff, key = "mic_min_level",
                min = MicGate.OFF_DBFS, max = MicGate.MAX_DBFS, unit = " dB",
                get = { c.micMinLevel }, set = { c.micMinLevel = it }),
            ConfigItem.Action(VOICE, "Microphone calibration",
                "Say the wake word in your normal voice so this device knows how loud you are. Several devices can then be compared when they all hear you. Do it on each device, from the same distance and in the same voice",
                Icons.Default.Tune,
                value = { if (c.wakeLevelCalibrated) "%+.1f dB".format(c.wakeLevelOffsetDb) else "Not calibrated" },
                onClick = { vm.openCalibrationWizard() }),
            ConfigItem.Toggle(VOICE, "Wake word arbitration",
                "When several devices hear the same wake word, they agree which one answers. Off, standard HA first detection wins applies",
                Icons.Default.HowToVote, key = "wake_word_arbitration",
                get = { c.wakeWordArbitrationEnabled }, set = { c.wakeWordArbitrationEnabled = it }),
            ConfigItem.Options(VOICE, "Ducking scope",
                "Which devices lower their music when this one answers, and whose answers lower this one's: those in the same Home Assistant area, those set to the same custom zone, or none. Disabled, this device neither ducks the others nor is ducked by them",
                Icons.Default.Hub, key = "duck_scope",
                options = listOf(
                    ConfigOption(APPConfig.DUCK_SCOPE_AREA, "Area"),
                    ConfigOption(APPConfig.DUCK_SCOPE_ZONE, "Custom zone"),
                    ConfigOption(APPConfig.DUCK_SCOPE_DISABLED, "Disabled"),
                ),
                get = { c.duckScope }, set = { c.duckScope = it }),
            ConfigItem.Options(VOICE, "Custom ducking zone", "Devices set to the same zone duck together",
                Icons.Default.Hub, key = "duck_zone",
                options = APPConfig.DUCK_ZONES.map { ConfigOption(it, it) },
                get = { c.duckZone }, set = { c.duckZone = it })
                .enabledWhen { c.duckScope == APPConfig.DUCK_SCOPE_ZONE },
        )
    )
}
