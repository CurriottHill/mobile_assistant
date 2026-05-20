package com.example.mobile_assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Looks up contacts by name/number and reports phone numbers, emails, and which messaging
 * apps each contact has (detected via well-known ContactsContract.Data mimetypes). A precursor
 * the model can call before send_whatsapp_message / call_contact.
 */
internal class ContactsToolService(private val context: Context) {

    private data class ContactRow(
        var displayName: String,
        val phones: MutableSet<String> = linkedSetOf(),
        val emails: MutableSet<String> = linkedSetOf(),
        val messagingApps: MutableSet<String> = linkedSetOf()
    )

    fun execute(arguments: JSONObject): SharedToolExecutionResult {
        val query = arguments.optString("query").trim()
        if (query.isBlank()) {
            return errorResult("Missing query.", "I need a name or number to search contacts.")
        }
        val limit = arguments.optInt("limit", 10).coerceIn(1, 50)
        val appFilter = arguments.optString("app").trim().lowercase().ifBlank { null }

        val canRead = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!canRead) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_SEARCH_CONTACTS,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_SEARCH_CONTACTS)
                    .put("needs_permission", "READ_CONTACTS")
                    .put("error", "Contacts permission is not granted."),
                chatResponse = "I need contacts permission first.",
                uiAction = PermissionUiActions.appPermission(context, Manifest.permission.READ_CONTACTS)
            )
        }

        val byContactId = linkedMapOf<String, ContactRow>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.Data.DISPLAY_NAME,
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1
                ),
                null, null, null
            )?.use { cursor ->
                val idIx = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                val nameIx = cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
                val mimeIx = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
                val data1Ix = cursor.getColumnIndex(ContactsContract.Data.DATA1)
                if (idIx < 0 || nameIx < 0 || mimeIx < 0) return@use
                while (cursor.moveToNext()) {
                    val contactId = cursor.getString(idIx)?.trim().orEmpty()
                    if (contactId.isBlank()) continue
                    val name = cursor.getString(nameIx)?.trim().orEmpty()
                    val mime = cursor.getString(mimeIx)?.trim().orEmpty()
                    val data1 = if (data1Ix >= 0) cursor.getString(data1Ix)?.trim().orEmpty() else ""
                    val row = byContactId.getOrPut(contactId) { ContactRow(displayName = name) }
                    if (row.displayName.isBlank() && name.isNotBlank()) row.displayName = name
                    when (mime) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                            if (data1.isNotBlank()) row.phones.add(data1)
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE ->
                            if (data1.isNotBlank()) row.emails.add(data1)
                        WHATSAPP_MIME -> row.messagingApps.add("whatsapp")
                        TELEGRAM_MIME -> row.messagingApps.add("telegram")
                        SIGNAL_MIME -> row.messagingApps.add("signal")
                    }
                }
            }
        }

        val normalizedQuery = query.lowercase(Locale.US)
        val queryDigits = query.filter { it.isDigit() }
        val matches = byContactId.values
            .filter { row ->
                row.displayName.lowercase(Locale.US).contains(normalizedQuery) ||
                    (queryDigits.length >= 3 && row.phones.any { it.filter(Char::isDigit).contains(queryDigits) })
            }
            .filter { row -> appFilter == null || row.messagingApps.contains(appFilter) }
            .sortedBy { it.displayName.lowercase(Locale.US) }
            .take(limit)

        val content = JSONObject()
            .put("ok", true)
            .put("tool", SharedToolSchemas.TOOL_SEARCH_CONTACTS)
            .put("query", query)
            .put("count", matches.size)
            .also { c -> appFilter?.let { c.put("app", it) } }
            .put("contacts", JSONArray().also { arr ->
                matches.forEach { row ->
                    arr.put(
                        JSONObject()
                            .put("display_name", row.displayName)
                            .put("phone_numbers", JSONArray().also { a -> row.phones.forEach(a::put) })
                            .put("emails", JSONArray().also { a -> row.emails.forEach(a::put) })
                            .put("messaging_apps", JSONArray().also { a -> row.messagingApps.forEach(a::put) })
                    )
                }
            })

        val chatResponse = when {
            matches.isEmpty() -> "I did not find any contacts matching $query."
            matches.size == 1 -> {
                val m = matches[0]
                "Found ${m.displayName}" +
                    (m.phones.firstOrNull()?.let { " at $it" } ?: "") + "."
            }
            else -> "Found ${matches.size} contacts: ${matches.take(5).joinToString(", ") { it.displayName }}."
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_SEARCH_CONTACTS,
            content = content,
            chatResponse = chatResponse
        )
    }

    private fun errorResult(error: String, chatResponse: String) = SharedToolExecutionResult(
        toolName = SharedToolSchemas.TOOL_SEARCH_CONTACTS,
        content = JSONObject()
            .put("ok", false)
            .put("tool", SharedToolSchemas.TOOL_SEARCH_CONTACTS)
            .put("error", error),
        chatResponse = chatResponse
    )

    private companion object {
        private const val WHATSAPP_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
        private const val TELEGRAM_MIME =
            "vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile"
        private const val SIGNAL_MIME =
            "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact"
    }
}
