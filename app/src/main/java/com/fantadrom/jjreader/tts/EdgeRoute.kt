package com.fantadrom.jjreader.tts

/** Two public primary-client routes. Alternative is diagnostic-only until device comparison. */
enum class EdgeRoute(val id: String, val endpoint: String, val tokenParameter: String) {
    CURRENT("current", "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1", "TrustedClientToken"),
    READEST("readest", "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1", "Ocp-Apim-Subscription-Key")
}
