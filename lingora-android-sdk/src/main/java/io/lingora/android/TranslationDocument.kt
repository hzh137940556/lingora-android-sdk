package io.lingora.android

import org.json.JSONArray
import org.json.JSONObject

internal sealed interface TranslationValue {
    data class Text(val value: String) : TranslationValue
    data class Plural(val values: Map<String, String>) : TranslationValue
    data class StringArray(val values: List<String>) : TranslationValue
}

internal class TranslationDocument private constructor(
    private val values: Map<String, TranslationValue>,
) {
    fun text(key: String): String? = (values[key] as? TranslationValue.Text)?.value

    fun plural(key: String): Map<String, String>? =
        (values[key] as? TranslationValue.Plural)?.values

    fun stringArray(key: String): List<String>? =
        (values[key] as? TranslationValue.StringArray)?.values

    companion object {
        private val pluralQuantities = setOf("zero", "one", "two", "few", "many", "other")

        fun parse(bytes: ByteArray): TranslationDocument {
            val root = JSONObject(bytes.toString(Charsets.UTF_8)).toMap()
            return from(root)
        }

        fun from(root: Map<String, Any?>): TranslationDocument {
            val values = linkedMapOf<String, TranslationValue>()
            root.forEach { (key, value) -> flatten(key, value, values) }
            return TranslationDocument(values)
        }

        private fun flatten(
            key: String,
            value: Any?,
            output: MutableMap<String, TranslationValue>,
        ) {
            require(key.isNotBlank()) { "Translation key must not be blank" }
            when (value) {
                is String -> output[key] = parseIcuPlural(value)
                    ?.let(TranslationValue::Plural)
                    ?: TranslationValue.Text(value)
                is List<*> -> {
                    require(value.all { it is String }) {
                        "Translation array '$key' must contain strings"
                    }
                    output[key] = TranslationValue.StringArray(value.filterIsInstance<String>())
                }
                is Map<*, *> -> {
                    require(value.keys.all { it is String }) {
                        "Translation object '$key' must contain string keys"
                    }
                    val stringMap = value.takeIf { map -> map.values.all { it is String } }
                        ?.entries
                        ?.associate { it.key as String to it.value as String }
                    if (stringMap != null && stringMap.isNotEmpty() &&
                        stringMap.keys.all(pluralQuantities::contains) &&
                        "other" in stringMap
                    ) {
                        output[key] = TranslationValue.Plural(stringMap)
                    } else {
                        require(value.isNotEmpty()) { "Translation object '$key' must not be empty" }
                        value.forEach { (childKey, childValue) ->
                            require(childKey is String) { "Translation keys must be strings" }
                            flatten("$key.$childKey", childValue, output)
                        }
                    }
                }
                else -> error("Unsupported translation value for '$key'")
            }
        }

        private fun parseIcuPlural(value: String): Map<String, String>? {
            val marker = Regex("^\\{[^,{}]+,\\s*plural,\\s*").find(value) ?: return null
            if (!value.endsWith("}")) return null
            val body = value.substring(marker.range.last + 1, value.lastIndex)
            val quantity = Regex("(?:^|\\s)(zero|one|two|few|many|other)\\s*\\{")
            val forms = linkedMapOf<String, String>()
            var position = 0
            while (position < body.length) {
                val match = quantity.find(body, position) ?: break
                var cursor = match.range.last + 1
                val start = cursor
                var depth = 1
                while (cursor < body.length && depth > 0) {
                    when (body[cursor]) {
                        '{' -> depth += 1
                        '}' -> depth -= 1
                    }
                    cursor += 1
                }
                if (depth != 0) return null
                forms[match.groupValues[1]] = body.substring(start, cursor - 1)
                position = cursor
            }
            return forms.takeIf { "other" in it }
        }

        private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
            get(key).toKotlinValue()
        }

        private fun JSONArray.toList(): List<Any?> = List(length()) { index ->
            get(index).toKotlinValue()
        }

        private fun Any.toKotlinValue(): Any? = when (this) {
            is JSONObject -> toMap()
            is JSONArray -> toList()
            JSONObject.NULL -> null
            else -> this
        }
    }
}

internal data class ContentDeliveryManifest(val locales: List<String>) {
    companion object {
        private val languageTag = Regex("^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$")

        fun parse(bytes: ByteArray): ContentDeliveryManifest {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val jsonLocales = root.getJSONArray("locales")
            val locales = List(jsonLocales.length()) { index -> jsonLocales.getString(index) }
            require(locales.distinct().size == locales.size) { "Manifest locales must be unique" }
            require(locales.all(languageTag::matches)) { "Manifest contains an invalid locale" }
            return ContentDeliveryManifest(locales)
        }
    }
}
