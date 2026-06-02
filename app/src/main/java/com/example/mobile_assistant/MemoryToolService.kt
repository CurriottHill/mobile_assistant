package com.example.mobile_assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal class MemoryToolService(
    private val repository: MemoryRepository,
    private val saveFactReviewer: MemorySaveFactReviewer = OpenAiMemorySaveFactReviewer()
) {
    fun executeRead(arguments: JSONObject): SharedToolExecutionResult {
        val path = arguments.optString("path").trim()
        val heading = arguments.optString("heading").trim().ifBlank { null }
        return runCatching {
            val result = repository.read(path, heading)
            SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_MEMORY_READ,
                content = readResultContent(SharedToolSchemas.TOOL_MEMORY_READ, result),
                chatResponse = "Read ${result.path}${result.heading?.let { " section $it" } ?: ""}."
            )
        }.getOrElse { errorResult(SharedToolSchemas.TOOL_MEMORY_READ, it.message ?: "Could not read memory.") }
    }

    fun executeEdit(arguments: JSONObject): SharedToolExecutionResult {
        val path = arguments.optString("path").trim()
        val mode = arguments.optString("mode").trim()
        val content = arguments.optString("content")
        val oldText = arguments.optString("old_text").takeIf { arguments.has("old_text") }
        val heading = arguments.optString("heading").trim().ifBlank { null }
        val createIfMissing = arguments.optBoolean("create_if_missing", false)
        return runCatching {
            val result = repository.edit(
                path = path,
                mode = mode,
                content = content,
                oldText = oldText,
                heading = heading,
                createIfMissing = createIfMissing
            )
            SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_MEMORY_EDIT,
                content = readResultContent(SharedToolSchemas.TOOL_MEMORY_EDIT, result)
                    .put("mode", mode)
                    .put("created_if_missing", createIfMissing),
                chatResponse = "Updated ${result.path}."
            )
        }.getOrElse { errorResult(SharedToolSchemas.TOOL_MEMORY_EDIT, it.message ?: "Could not edit memory.") }
    }

    fun executeList(arguments: JSONObject): SharedToolExecutionResult {
        val prefix = arguments.optString("prefix").trim().ifBlank { null }
        return runCatching {
            val entries = repository.list(prefix)
            SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_MEMORY_LIST,
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", SharedToolSchemas.TOOL_MEMORY_LIST)
                    .also { content -> prefix?.let { content.put("prefix", it) } }
                    .put("files", JSONArray().also { files ->
                        entries.forEach { entry ->
                            files.put(
                                JSONObject()
                                    .put("path", entry.path)
                                    .put("headings", JSONArray().also { headings ->
                                        entry.headings.forEach(headings::put)
                                    })
                            )
                        }
                    }),
                chatResponse = "Found ${entries.size} memory file${if (entries.size == 1) "" else "s"}."
            )
        }.getOrElse { errorResult(SharedToolSchemas.TOOL_MEMORY_LIST, it.message ?: "Could not list memory.") }
    }

    fun executeLink(arguments: JSONObject): SharedToolExecutionResult {
        val fromPath = arguments.optString("from_path").trim()
        val toPath = arguments.optString("to_path").trim()
        val label = arguments.optString("label").trim().ifBlank { null }
        return runCatching {
            val result = repository.link(fromPath, toPath, label)
            SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_MEMORY_LINK,
                content = readResultContent(SharedToolSchemas.TOOL_MEMORY_LINK, result)
                    .put("to_path", repository.read(toPath).path),
                chatResponse = "Linked ${result.path}."
            )
        }.getOrElse { errorResult(SharedToolSchemas.TOOL_MEMORY_LINK, it.message ?: "Could not link memory.") }
    }

    fun executeSaveFact(arguments: JSONObject): SharedToolExecutionResult {
        val kind = arguments.optString("kind").trim().lowercase(Locale.US)
        val subject = arguments.optString("subject").trim()
        val fact = arguments.optString("fact").trim()
        val context = arguments.optString("context").trim().ifBlank { null }
        return runCatching {
            require(kind in SUPPORTED_SAVE_FACT_KINDS) { "Unsupported memory fact kind: $kind" }
            require(subject.isNotBlank()) { "Missing memory fact subject." }
            require(fact.isNotBlank()) { "Missing memory fact." }

            val targetPath = targetPath(kind, subject)
            val existing = readExisting(targetPath)
            val request = MemorySaveFactReviewRequest(
                kind = kind,
                subject = subject,
                fact = fact,
                context = context,
                targetPath = targetPath,
                existingMemory = existing
            )
            val decision = saveFactReviewer.review(request)
            if (!decision.save) {
                return@runCatching SharedToolExecutionResult(
                    toolName = SharedToolSchemas.TOOL_MEMORY_SAVE_FACT,
                    content = JSONObject()
                        .put("ok", true)
                        .put("tool", SharedToolSchemas.TOOL_MEMORY_SAVE_FACT)
                        .put("saved", false)
                        .put("path", targetPath)
                        .put("reason", decision.reason.ifBlank { "No new durable memory." }),
                    chatResponse = "No new memory saved."
                )
            }

            val statement = decision.statement.ifBlank { fact }
            val saved = when (kind) {
                "preference" -> repository.upsertPreference(subject, statement)
                "person" -> savePerson(subject, decision, statement, fact, context)
                "place" -> repository.upsertPlace(subject, note = statement)
                "routine" -> saveRoutine(subject, decision, statement)
                "main" -> saveMainFact(decision, statement)
                else -> null
            } ?: throw IllegalStateException("Could not save memory fact.")

            SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_MEMORY_SAVE_FACT,
                content = readResultContent(SharedToolSchemas.TOOL_MEMORY_SAVE_FACT, saved)
                    .put("saved", true)
                    .put("kind", kind)
                    .put("subject", subject)
                    .put("statement", statement)
                    .also { content -> decision.channel?.let { content.put("channel", it) } }
                    .put("reason", decision.reason),
                chatResponse = "Saved memory."
            )
        }.getOrElse {
            errorResult(SharedToolSchemas.TOOL_MEMORY_SAVE_FACT, it.message ?: "Could not save memory fact.")
        }
    }

    private fun readResultContent(toolName: String, result: MemoryReadResult): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("tool", toolName)
            .put("path", result.path)
            .also { content -> result.heading?.let { content.put("heading", it) } }
            .put("content", result.content)
            .put("headings", JSONArray().also { headings -> result.headings.forEach(headings::put) })
            .put("links", JSONArray().also { links -> result.links.forEach(links::put) })
    }

    private fun errorResult(toolName: String, error: String): SharedToolExecutionResult {
        return SharedToolExecutionResult(
            toolName = toolName,
            content = JSONObject()
                .put("ok", false)
                .put("tool", toolName)
                .put("error", error),
            chatResponse = error
        )
    }

    private fun readExisting(path: String): String {
        return runCatching { repository.read(path).content }.getOrDefault("")
    }

    private fun targetPath(kind: String, subject: String): String {
        return when (kind) {
            "preference" -> "preferences/${slugify(subject)}.md"
            "person" -> "people/${slugify(subject)}.md"
            "place" -> "places/${slugify(subject)}.md"
            "routine" -> MemoryRepository.ROUTINES_FILE
            "main" -> MemoryRepository.MAIN_FILE
            else -> throw IllegalArgumentException("Unsupported memory fact kind: $kind")
        }
    }

    private fun saveRoutine(
        subject: String,
        decision: MemorySaveFactDecision,
        statement: String
    ): MemoryReadResult? {
        val steps = decision.steps.ifEmpty {
            statement.lines()
                .map { it.trim().trimStart('-', '*').trim() }
                .filter { it.isNotBlank() }
        }
        return repository.upsertRoutine(subject, steps)
    }

    private fun savePerson(
        subject: String,
        decision: MemorySaveFactDecision,
        statement: String,
        originalFact: String,
        context: String?
    ): MemoryReadResult? {
        val channel = decision.channel?.normalizeChannel()
            ?: inferChannel("$statement $originalFact ${context.orEmpty()}")
        val saved = repository.upsertPerson(subject, channel = channel, note = statement)
        if (channel != null && channel in CONTACT_AVAILABILITY_CHANNELS) {
            updateContactMessagingAvailability(subject, channel)
        }
        return saved
    }

    private fun saveMainFact(decision: MemorySaveFactDecision, statement: String): MemoryReadResult? {
        val field = normalizeMainField(decision.mainField ?: statement.substringBefore(':', ""))
            ?: return null
        val value = decision.mainValue
            ?: statement.substringAfter(':', "").trim().takeIf { it.isNotBlank() }
            ?: return null
        val main = repository.read(MemoryRepository.MAIN_FILE).content
        val updated = setLineValue(main, field, value)
        repository.edit(
            path = MemoryRepository.MAIN_FILE,
            mode = "overwrite",
            content = updated
        )
        return repository.read(MemoryRepository.MAIN_FILE)
    }

    private fun updateContactMessagingAvailability(contactName: String, channel: String) {
        val main = repository.read(MemoryRepository.MAIN_FILE).content
        val updated = upsertSectionKeyValue(
            text = ensureSection(main, "Contact Messaging Availability"),
            heading = "Contact Messaging Availability",
            key = contactName.trim(),
            value = channel
        )
        if (updated != main) {
            repository.edit(
                path = MemoryRepository.MAIN_FILE,
                mode = "overwrite",
                content = updated
            )
        }
    }

    private fun ensureSection(text: String, heading: String): String {
        if (Regex("""(?mi)^##\s+${Regex.escape(heading)}\s*$""").containsMatchIn(text)) return text
        return buildString {
            append(text.trimEnd())
            if (isNotEmpty()) appendLine()
            appendLine()
            appendLine("## $heading")
        }
    }

    private fun upsertSectionKeyValue(text: String, heading: String, key: String, value: String): String {
        if (key.isBlank() || value.isBlank()) return text
        val lines = text.lines().toMutableList()
        val headingIndex = lines.indexOfFirst { line ->
            line.trim().equals("## $heading", ignoreCase = true)
        }
        if (headingIndex == -1) return text
        val sectionEnd = lines.drop(headingIndex + 1)
            .indexOfFirst { it.startsWith("## ") }
            .let { if (it == -1) lines.size else headingIndex + 1 + it }
        val keyRegex = Regex("""^\s*-?\s*${Regex.escape(key)}\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
        val existingIndex = (headingIndex + 1 until sectionEnd).firstOrNull { index ->
            keyRegex.matches(lines[index])
        }
        if (existingIndex != null) {
            val existingValues = keyRegex.find(lines[existingIndex])
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
            val merged = mergeCsvValues(existingValues, value)
            lines[existingIndex] = "$key: $merged"
        } else {
            lines.add(sectionEnd, "$key: $value")
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun mergeCsvValues(existing: String, value: String): String {
        return (existing.split(',', '/', ';') + value)
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString(", ")
    }

    private fun normalizeMainField(field: String): String? {
        val normalized = field.trim().lowercase(Locale.US)
        return MAIN_FIELDS.firstOrNull { it.lowercase(Locale.US) == normalized }
    }

    private fun setLineValue(text: String, label: String, value: String): String {
        val regex = Regex("""(?m)^${Regex.escape(label)}:\s*.*$""")
        val replacement = "$label: ${value.trim()}"
        return if (regex.containsMatchIn(text)) {
            text.replace(regex, replacement)
        } else {
            text.trimEnd() + "\n$replacement\n"
        }
    }

    private fun slugify(value: String): String {
        return value.trim()
            .lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
            .ifBlank { "memory" }
    }

    private fun inferChannel(text: String): String? {
        val normalized = text.lowercase(Locale.US)
        return CONTACT_CHANNELS.firstOrNull { channel ->
            Regex("""\b${Regex.escape(channel)}\b""").containsMatchIn(normalized)
        }
    }

    private fun String.normalizeChannel(): String? {
        val normalized = trim().lowercase(Locale.US)
        return CONTACT_CHANNELS.firstOrNull { it == normalized }
    }

    private companion object {
        val SUPPORTED_SAVE_FACT_KINDS = setOf("preference", "person", "place", "routine", "main")
        val CONTACT_CHANNELS = listOf("whatsapp", "telegram", "signal", "sms", "call")
        val CONTACT_AVAILABILITY_CHANNELS = setOf("whatsapp", "telegram", "signal", "sms")
        val MAIN_FIELDS = listOf(
            "Name",
            "Home",
            "Work",
            "Default commute mode",
            "Default messaging app",
            "Commute playlist"
        )
    }
}

internal data class MemorySaveFactReviewRequest(
    val kind: String,
    val subject: String,
    val fact: String,
    val context: String?,
    val targetPath: String,
    val existingMemory: String
)

internal data class MemorySaveFactDecision(
    val save: Boolean,
    val statement: String = "",
    val reason: String = "",
    val steps: List<String> = emptyList(),
    val mainField: String? = null,
    val mainValue: String? = null,
    val channel: String? = null
)

internal interface MemorySaveFactReviewer {
    fun review(request: MemorySaveFactReviewRequest): MemorySaveFactDecision
}

internal class OpenAiMemorySaveFactReviewer(
    private val client: OkHttpClient = OkHttpClient(),
    private val onUsageRecorded: ((JSONObject) -> Unit)? = null
) : MemorySaveFactReviewer {
    override fun review(request: MemorySaveFactReviewRequest): MemorySaveFactDecision {
        val apiKey = BuildConfig.OPENAI_API_KEY.trim()
        if (apiKey.isBlank()) {
            throw IllegalStateException("OpenAI API key not configured for memory saving.")
        }

        val payload = JSONObject()
            .put("model", AgentModelConfig.MEMORY_SAVE_MODEL)
            .put("max_completion_tokens", 500)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", userPrompt(request)))
            )

        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url(OPENAI_CHAT_COMPLETIONS_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                throw IllegalStateException("Memory save model error HTTP ${response.code}: ${responseBody.take(500)}")
            }
            val responseJson = JSONObject(response.body?.string().orEmpty())
            onUsageRecorded?.invoke(responseJson)
            val content = AgentApiSupport.parseOpenAiChatCompletionText(responseJson)
                .optString("content")
                .trim()
            val decisionJson = JSONObject(content)
            return MemorySaveFactDecision(
                save = decisionJson.optBoolean("save", false),
                statement = decisionJson.optString("statement").trim(),
                reason = decisionJson.optString("reason").trim(),
                steps = decisionJson.optJSONArray("steps")?.let { steps ->
                    (0 until steps.length()).mapNotNull { index ->
                        steps.optString(index).trim().takeIf { it.isNotBlank() }
                    }
                }.orEmpty(),
                mainField = decisionJson.optString("main_field").trim().ifBlank { null },
                mainValue = decisionJson.optString("main_value").trim().ifBlank { null },
                channel = decisionJson.optString("channel").trim().ifBlank { null }
            )
        }
    }

    private fun userPrompt(request: MemorySaveFactReviewRequest): String {
        return """
Kind: ${request.kind}
Subject: ${request.subject}
Target path: ${request.targetPath}
Candidate fact: ${request.fact}
Context: ${request.context ?: "(none)"}

Existing memory at target:
${request.existingMemory.ifBlank { "(missing or empty)" }}
        """.trimIndent()
    }

    private companion object {
        private const val OPENAI_CHAT_COMPLETIONS_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        private const val SYSTEM_PROMPT = """
You decide whether a candidate fact should be saved to long term assistant memory.
Return only one JSON object with these fields:
save: boolean
statement: concise durable statement to save, or empty string
reason: short reason
steps: array of routine steps, only for routine memories
main_field: one of Name, Home, Work, Default commute mode, Default messaging app, Commute playlist, only for main memories
main_value: field value, only for main memories
channel: whatsapp, telegram, signal, sms, or call, only for person contact channel memories

Save only if the fact is durable, reusable, and not already present in the existing memory.
Skip duplicates, vague facts, one time wishes, raw external content, and sensitive facts unless the context says the user explicitly asked to remember them.
For preferences, preserve the user's meaning and write a concise statement like "Prefers jazz for focus work" or "Never use SMS unless requested".
For corrections, write the corrected durable fact.
"""
    }
}
