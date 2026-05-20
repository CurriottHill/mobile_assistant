package com.example.mobile_assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

internal data class ContactPhoneMatch(
    val displayName: String,
    val phoneNumber: String,
    val matchKind: String,
    val score: Int? = null
)

internal data class NameMatchScore(val value: Int, val matchKind: String)

/**
 * Shared contact-resolution logic. Extracted verbatim from [WhatsAppToolService] so WhatsApp and
 * other phone-number based features resolve a name/number identically. Behavior must stay
 * byte-for-byte the same as the original WhatsApp implementation.
 */
internal object ContactResolver {

    fun resolveBestPhoneNumber(context: Context, input: String): ContactPhoneMatch? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        sanitizePhoneNumber(trimmed)?.let { number ->
            return ContactPhoneMatch(displayName = trimmed, phoneNumber = number, matchKind = "direct_number")
        }

        val normalizedQuery = normalizeName(trimmed)
        if (normalizedQuery.isBlank()) return null

        val canReadContacts = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!canReadContacts) return null

        val matches = mutableListOf<ContactPhoneMatch>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null, null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIndex < 0 || numberIndex < 0) return@use

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIndex)?.trim().orEmpty()
                    val numberRaw = cursor.getString(numberIndex)?.trim().orEmpty()
                    if (displayName.isBlank() || numberRaw.isBlank()) continue
                    val sanitizedNumber = sanitizePhoneNumber(numberRaw) ?: continue
                    val normalizedName = normalizeName(displayName)
                    if (normalizedName.isBlank()) continue
                    val score = matchScore(normalizedQuery, normalizedName) ?: continue
                    matches += ContactPhoneMatch(
                        displayName = displayName,
                        phoneNumber = sanitizedNumber,
                        matchKind = score.matchKind,
                        score = score.value
                    )
                }
            }
        }

        return matches.minWithOrNull(
            compareBy<ContactPhoneMatch> { it.score ?: Int.MAX_VALUE }.thenBy { it.displayName.length }
        )
    }

    fun sanitizePhoneNumber(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val hasPlus = trimmed.startsWith("+")
        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.length < 3) return null
        return if (hasPlus) "+$digitsOnly" else digitsOnly
    }

    fun normalizeName(value: String): String =
        value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    fun matchScore(query: String, name: String): NameMatchScore? {
        if (name == query) return NameMatchScore(0, "exact")
        val nameTokens = name.split(" ").filter { it.isNotBlank() }
        if (nameTokens.any { it == query }) return NameMatchScore(1, "token_exact")
        if (name.startsWith("$query ")) return NameMatchScore(2, "prefix")
        if (name.contains(" $query ")) return NameMatchScore(3, "contains")
        if (nameTokens.any { token -> token.startsWith(query) && query.length >= 2 }) return NameMatchScore(4, "token_prefix")
        return null
    }
}
