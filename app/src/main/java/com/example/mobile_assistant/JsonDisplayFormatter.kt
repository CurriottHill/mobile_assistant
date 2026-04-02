package com.example.mobile_assistant

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object JsonDisplayFormatter {
    fun formatMarkdownCodeBlock(value: Any?, language: String): String {
        val body = formatPromptForDisplay(value).trimEnd()
        return buildString {
            append("```")
            append(language)
            append('\n')
            append(body)
            append("\n```")
        }
    }

    fun formatPromptForDisplay(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> formatJsonObjectForDisplay(value, 0)
            is JSONArray -> formatJsonArrayForDisplay(value, 0)
            else -> value.toString()
        }
    }

    private fun formatJsonObjectForDisplay(obj: JSONObject, indent: Int): String {
        val keys = obj.keys().asSequence().toList()
        if (keys.isEmpty()) return "{}"

        val childIndent = indent + 2
        return buildString {
            append("{\n")
            keys.forEachIndexed { index, key ->
                append(" ".repeat(childIndent))
                append(JSONObject.quote(key))
                append(": ")
                append(formatJsonValueForDisplay(obj.opt(key), childIndent))
                if (index < keys.lastIndex) append(',')
                append('\n')
            }
            append(" ".repeat(indent))
            append('}')
        }
    }

    private fun formatJsonArrayForDisplay(array: JSONArray, indent: Int): String {
        if (array.length() == 0) return "[]"

        val childIndent = indent + 2
        return buildString {
            append("[\n")
            for (i in 0 until array.length()) {
                append(" ".repeat(childIndent))
                append(formatJsonValueForDisplay(array.opt(i), childIndent))
                if (i < array.length() - 1) append(',')
                append('\n')
            }
            append(" ".repeat(indent))
            append(']')
        }
    }

    private fun formatJsonValueForDisplay(value: Any?, indent: Int): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> formatJsonObjectForDisplay(value, indent)
            is JSONArray -> formatJsonArrayForDisplay(value, indent)
            is String -> formatJsonStringForDisplay(value)
            is Number, is Boolean -> value.toString()
            else -> formatJsonStringForDisplay(value.toString())
        }
    }

    private fun formatJsonStringForDisplay(value: String): String {
        if (!value.contains('\n')) {
            return JSONObject.quote(value)
        }

        val quoted = JSONObject.quote(value)
        val unescaped = JSONTokener(quoted).nextValue() as? String ?: value
        return buildString {
            append('"')
            append(unescaped)
            append('"')
        }
    }
}
