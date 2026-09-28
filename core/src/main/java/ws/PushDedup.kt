package ws

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Drops a TMS push seen again inside [windowMs]. Keyed on `Command` + `RequestRef` when the message
 * carries one, else on the whole message (item 93). Thread-safe; the clock is injected for tests.
 */
internal class PushDedup(
	private val windowMs: Long,
	private val now: () -> Long,
) {
	private val seenAt = LinkedHashMap<String, Long>()

	/** True if [message] is a repeat inside the window; otherwise records it and returns false. */
	@Synchronized
	fun isDuplicate(message: String): Boolean {
		val t = now()
		seenAt.values.removeAll { t - it >= windowMs }
		val key = keyOf(message)
		if (seenAt.containsKey(key)) return true
		seenAt[key] = t
		return false
	}

	companion object {
		internal fun keyOf(message: String): String {
			val obj = parse(message) ?: return message
			val ref = obj.stringOrNull("RequestRef")?.takeIf { it.isNotBlank() } ?: return message
			return "${obj.stringOrNull("Command").orEmpty()}|$ref"
		}

		// Same unwrapping as onMessage: a plain object, or one double-encoded as a JSON string.
		private fun parse(message: String): JsonObject? = try {
			val el = JsonParser.parseString(message)
			when {
				el.isJsonObject -> el.asJsonObject
				el.isJsonPrimitive && el.asJsonPrimitive.isString ->
					JsonParser.parseString(el.asString).takeIf { it.isJsonObject }?.asJsonObject
				else -> null
			}
		} catch (e: Exception) {
			null
		}

		private fun JsonObject.stringOrNull(name: String): String? = try {
			get(name)?.takeIf { it.isJsonPrimitive }?.asString
		} catch (e: Exception) {
			null
		}
	}
}
