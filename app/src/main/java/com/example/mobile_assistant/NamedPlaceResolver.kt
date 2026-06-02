package com.example.mobile_assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Resolves friendly place tokens used in navigation / travel-time requests:
 *  - "my location" / "current location" / "here" -> [skipAsCurrentLocation] (origin omitted)
 *  - "home" / "work" -> the user's "Me" profile postal address (READ_CONTACTS)
 *  - anything else -> passed through literally ([resolved] = true, it is a real place string)
 *
 * When a named place ("home"/"work") cannot be resolved the literal token is returned with
 * [resolved] = false so callers can surface that to the model.
 */
internal object NamedPlaceResolver {

    data class Resolution(
        val value: String,
        val resolved: Boolean,
        val skipAsCurrentLocation: Boolean = false
    )

    private val CURRENT_LOCATION_TOKENS = setOf(
        "my location", "current location", "here", "my current location", "current position"
    )
    private val NAMED_PLACE_TYPES = mapOf(
        "home" to ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME,
        "work" to ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK
    )

    fun resolve(context: Context, token: String): Resolution {
        val trimmed = token.trim()
        if (trimmed.isBlank()) {
            return Resolution(value = "", resolved = true, skipAsCurrentLocation = true)
        }
        val lower = trimmed.lowercase(Locale.US)
        if (lower in CURRENT_LOCATION_TOKENS) {
            return Resolution(value = "", resolved = true, skipAsCurrentLocation = true)
        }
        val namedType = NAMED_PLACE_TYPES[lower]
            ?: return Resolution(value = trimmed, resolved = true)

        lookupMemoryNamedPlace(context, lower)?.let { memoryPlace ->
            return Resolution(value = memoryPlace, resolved = true)
        }

        val address = lookupProfilePostalAddress(context, namedType)
        return if (address.isNullOrBlank()) {
            Resolution(value = trimmed, resolved = false)
        } else {
            Resolution(value = address, resolved = true)
        }
    }

    private fun lookupMemoryNamedPlace(context: Context, lower: String): String? {
        val key = when (lower) {
            "home" -> "Home"
            "work" -> "Work"
            else -> return null
        }
        val main = runCatching {
            MemoryRepository(context).promptSnapshot().mainMarkdown
        }.getOrNull().orEmpty()
        val regex = Regex("""(?im)^${Regex.escape(key)}:\s*(.+?)\s*$""")
        return regex.find(main)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun lookupProfilePostalAddress(context: Context, preferredType: Int): String? {
        val canRead = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!canRead) return null

        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.Profile.CONTENT_URI,
            ContactsContract.Contacts.Data.CONTENT_DIRECTORY
        )
        var preferred: String? = null
        var fallback: String? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                    ContactsContract.CommonDataKinds.StructuredPostal.TYPE
                ),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                val addrIx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS
                )
                val typeIx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.StructuredPostal.TYPE
                )
                if (addrIx < 0) return@use
                while (cursor.moveToNext()) {
                    val addr = cursor.getString(addrIx)?.trim().orEmpty()
                    if (addr.isBlank()) continue
                    if (fallback == null) fallback = addr
                    if (typeIx >= 0 && cursor.getInt(typeIx) == preferredType) {
                        preferred = addr
                        break
                    }
                }
            }
        }
        return preferred ?: fallback
    }
}
