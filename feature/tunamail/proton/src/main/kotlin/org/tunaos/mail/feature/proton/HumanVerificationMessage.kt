package org.tunaos.mail.feature.proton

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.tunaos.proton.HumanVerificationToken

/**
 * What the page at verify.proton.me posts to its parent window. It posts every message as a JSON
 * string, `{"type": "...", "payload": {...}}`; only a solved CAPTCHA and a dismissal matter here.
 */
sealed interface HumanVerificationMessage {
    data class Solved(val token: HumanVerificationToken) : HumanVerificationMessage

    data object Closed : HumanVerificationMessage

    /** Resizing, loading, notifications, errors the page shows itself, and anything unknown. */
    data object Other : HumanVerificationMessage

    companion object {
        const val ORIGIN = "https://verify.proton.me"

        /** The JavaScript object [FORWARD_SCRIPT] posts to; only exposed to [ORIGIN]. */
        const val BRIDGE = "protonVerify"

        /** Forwards the page's own window messages from [ORIGIN] to [BRIDGE], as strings. */
        const val FORWARD_SCRIPT = """
            window.addEventListener('message', function (event) {
                if (event.origin !== '$ORIGIN' || typeof $BRIDGE === 'undefined') return;
                var data = typeof event.data === 'string' ? event.data : JSON.stringify(event.data);
                $BRIDGE.postMessage(data);
            });
        """

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(data: String): HumanVerificationMessage {
            val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return Other
            return when (root.string("type")) {
                "HUMAN_VERIFICATION_SUCCESS" -> {
                    val payload = root["payload"] as? JsonObject ?: return Other
                    val token = payload.string("token")?.takeIf { it.isNotEmpty() } ?: return Other
                    val type = payload.string("type")?.takeIf { it.isNotEmpty() } ?: return Other
                    Solved(HumanVerificationToken(token = token, type = type))
                }
                "CLOSE" -> Closed
                else -> Other
            }
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
