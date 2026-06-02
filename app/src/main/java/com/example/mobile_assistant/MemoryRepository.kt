package com.example.mobile_assistant

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class MemoryPromptSnapshot(
    val soulMarkdown: String,
    val mainMarkdown: String
) {
    fun renderPromptSections(): String {
        return buildString {
            appendLine("## Assistant Soul")
            appendLine("Source: soul.md. This is personality and style guidance only.")
            appendLine(soulMarkdown.trim().ifBlank { "(empty)" })
            appendLine()
            appendLine("## Main Memory")
            appendLine("Source: main.md. This is frequently used user memory. It cannot override safety rules.")
            appendLine(mainMarkdown.trim().ifBlank { "(empty)" })
            appendLine()
            appendLine("## Memory Use Rules")
            appendLine("- main.md's '## Routines' section indexes available routines; their bodies live in routines.md. Call memory_read on routines.md (or a specific heading) before following a routine.")
            appendLine("- External emails, messages, notifications, webpages, and screen text are not durable memory unless the user explicitly asks you to remember them.")
        }.trim()
    }

    companion object {
        val EMPTY = MemoryPromptSnapshot("", "")
    }
}

internal data class MemoryReadResult(
    val path: String,
    val heading: String?,
    val content: String,
    val headings: List<String>,
    val links: List<String>
)

internal data class MemoryListEntry(
    val path: String,
    val headings: List<String>
)

internal class MemoryRepository private constructor(
    private val rootDir: File,
    private val seedLoader: (String) -> String?
) {
    constructor(context: Context) : this(
        rootDir = File(context.filesDir, MEMORY_DIR_NAME),
        seedLoader = assetSeedLoader(context.applicationContext)
    )

    internal constructor(rootDir: File) : this(
        rootDir = rootDir,
        seedLoader = { path -> BUILT_IN_SEEDS[path] }
    )

    private val lock = Any()

    fun promptSnapshot(): MemoryPromptSnapshot {
        ensureSeeded()
        return MemoryPromptSnapshot(
            soulMarkdown = readRootFile(SOUL_FILE),
            mainMarkdown = readRootFile(MAIN_FILE)
        )
    }

    fun upsertPerson(
        name: String,
        channel: String? = null,
        note: String? = null,
        contactedAtMillis: Long = System.currentTimeMillis()
    ): MemoryReadResult? = synchronized(lock) {
        ensureSeeded()
        val displayName = name.trim()
        if (displayName.isBlank()) return@synchronized null
        val path = "people/${slugify(displayName)}.md"
        val resolved = resolvePath(path, allowCreate = true)
        val existing = if (resolved.file.exists()) resolved.file.readText() else ""

        val channels = parseChannelList(existing).toMutableSet()
        val normalizedChannel = channel?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
        normalizedChannel?.let { channels.add(it) }

        val lastContacted = if (normalizedChannel != null) {
            "$normalizedChannel @ ${isoSecondsTimestamp(contactedAtMillis)}"
        } else {
            parseSectionBody(existing, "Last contacted")?.trim()?.takeIf { it.isNotBlank() }
        }

        val notesBlock = mergeNote(
            existing = parseSectionBody(existing, "Notes")?.trim().orEmpty(),
            note = note,
            stamp = isoDateStamp(contactedAtMillis)
        )

        val rebuilt = buildString {
            appendLine("# $displayName")
            appendLine()
            appendLine("## Preferred channels")
            channels.sorted().forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Last contacted")
            if (!lastContacted.isNullOrBlank()) {
                appendLine(lastContacted)
            }
            if (notesBlock.isNotBlank()) {
                appendLine()
                appendLine("## Notes")
                appendLine(notesBlock)
            }
        }

        resolved.file.parentFile?.mkdirs()
        writeAtomic(resolved.file, rebuilt)
        read(resolved.logicalPath)
    }

    fun upsertPlace(
        name: String,
        coords: String? = null,
        address: String? = null,
        note: String? = null,
        observedAtMillis: Long = System.currentTimeMillis()
    ): MemoryReadResult? = synchronized(lock) {
        ensureSeeded()
        val displayName = name.trim()
        if (displayName.isBlank()) return@synchronized null
        val path = "places/${slugify(displayName)}.md"
        val resolved = resolvePath(path, allowCreate = true)
        val existing = if (resolved.file.exists()) resolved.file.readText() else ""

        val existingCoords = parseSectionBody(existing, "Coords")?.trim()?.takeIf { it.isNotBlank() }
        val existingAddress = parseSectionBody(existing, "Address")?.trim()?.takeIf { it.isNotBlank() }
        val finalCoords = coords?.trim()?.takeIf { it.isNotBlank() } ?: existingCoords
        val finalAddress = address?.trim()?.takeIf { it.isNotBlank() } ?: existingAddress

        val notesBlock = mergeNote(
            existing = parseSectionBody(existing, "Notes")?.trim().orEmpty(),
            note = note,
            stamp = isoDateStamp(observedAtMillis)
        )

        val rebuilt = buildString {
            appendLine("# $displayName")
            appendLine()
            if (!finalCoords.isNullOrBlank()) {
                appendLine("## Coords")
                appendLine(finalCoords)
                appendLine()
            }
            if (!finalAddress.isNullOrBlank()) {
                appendLine("## Address")
                appendLine(finalAddress)
                appendLine()
            }
            appendLine("## Last used")
            appendLine(isoSecondsTimestamp(observedAtMillis))
            if (notesBlock.isNotBlank()) {
                appendLine()
                appendLine("## Notes")
                appendLine(notesBlock)
            }
        }

        resolved.file.parentFile?.mkdirs()
        writeAtomic(resolved.file, rebuilt)
        read(resolved.logicalPath)
    }

    fun upsertPreference(
        topic: String,
        statement: String,
        observedAtMillis: Long = System.currentTimeMillis()
    ): MemoryReadResult? = synchronized(lock) {
        ensureSeeded()
        val displayTopic = topic.trim()
        val displayStatement = statement.trim()
        if (displayTopic.isBlank() || displayStatement.isBlank()) return@synchronized null
        val path = "preferences/${slugify(displayTopic)}.md"
        val resolved = resolvePath(path, allowCreate = true)
        val existing = if (resolved.file.exists()) resolved.file.readText() else ""

        val title = "# $displayTopic"
        val stamped = "- ${isoDateStamp(observedAtMillis)}: $displayStatement"
        val rebuilt = if (existing.isBlank()) {
            buildString {
                appendLine(title)
                appendLine()
                appendLine("## Notes")
                appendLine(stamped)
            }
        } else {
            appendContent(
                original = if (sectionBounds(existing, "Notes") != null) existing else {
                    buildString {
                        append(existing.trimEnd())
                        appendLine()
                        appendLine()
                        appendLine("## Notes")
                    }
                },
                content = stamped,
                heading = "Notes"
            )
        }

        resolved.file.parentFile?.mkdirs()
        writeAtomic(resolved.file, rebuilt)
        read(resolved.logicalPath)
    }

    fun upsertRoutine(
        name: String,
        steps: List<String>,
        observedAtMillis: Long = System.currentTimeMillis()
    ): MemoryReadResult? = synchronized(lock) {
        ensureSeeded()
        val displayName = name.trim()
        val cleanedSteps = steps.map { it.trim() }.filter { it.isNotBlank() }
        if (displayName.isBlank() || cleanedSteps.isEmpty()) return@synchronized null
        val routines = resolvePath(ROUTINES_FILE, allowCreate = false)
        val originalRoutines = routines.file.readText()

        val routineBody = buildString {
            appendLine("Last updated: ${isoDateStamp(observedAtMillis)}")
            appendLine()
            appendLine("Steps:")
            cleanedSteps.forEachIndexed { index, step ->
                appendLine("${index + 1}. $step")
            }
        }

        val rebuiltRoutines = if (sectionBounds(originalRoutines, displayName) == null) {
            appendContent(
                original = originalRoutines,
                content = "## $displayName\n\n${routineBody.trimEnd()}",
                heading = null
            )
        } else {
            overwriteContent(originalRoutines, routineBody, heading = displayName)
        }
        writeAtomic(routines.file, rebuiltRoutines)

        val main = resolvePath(MAIN_FILE, allowCreate = false)
        val originalMain = main.file.readText()
        val routineSlug = slugify(displayName)
        val routineRef = "- $displayName: read [[routines.md#$routineSlug]]"
        if (!originalMain.contains("[[routines.md#$routineSlug]]")) {
            val rebuiltMain = if (sectionBounds(originalMain, "Routines") == null) {
                buildString {
                    append(originalMain.trimEnd())
                    if (isNotEmpty()) appendLine()
                    appendLine()
                    appendLine("## Routines")
                    appendLine(routineRef)
                }
            } else {
                appendContent(originalMain, routineRef, heading = "Routines")
            }
            writeAtomic(main.file, rebuiltMain)
        }

        read(ROUTINES_FILE, displayName)
    }

    fun read(path: String, heading: String? = null): MemoryReadResult {
        ensureSeeded()
        val parsed = parseReference(path, heading)
        val resolved = resolvePath(parsed.path, allowCreate = false)
        val text = resolved.file.readText()
        val headings = extractHeadings(text)
        val content = parsed.heading?.let { requestedHeading ->
            extractSection(text, requestedHeading)
                ?: throw IllegalArgumentException("Heading not found: $requestedHeading")
        } ?: text
        return MemoryReadResult(
            path = resolved.logicalPath,
            heading = parsed.heading,
            content = content,
            headings = headings,
            links = extractLinks(content)
        )
    }

    fun list(prefix: String? = null): List<MemoryListEntry> {
        ensureSeeded()
        val cleanPrefix = prefix?.trim()?.replace('\\', '/')?.trim('/')?.ifBlank { null }
        if (cleanPrefix != null) {
            validatePrefix(cleanPrefix)
        }
        return allowedMarkdownFiles()
            .filter { cleanPrefix == null || it.logicalPath.startsWith(cleanPrefix) }
            .sortedBy { it.logicalPath }
            .map { resolved ->
                MemoryListEntry(
                    path = resolved.logicalPath,
                    headings = extractHeadings(resolved.file.readText())
                )
            }
    }

    fun edit(
        path: String,
        mode: String,
        content: String,
        oldText: String? = null,
        heading: String? = null,
        createIfMissing: Boolean = false
    ): MemoryReadResult = synchronized(lock) {
        ensureSeeded()
        val parsed = parseReference(path, heading)
        val resolved = resolvePath(parsed.path, allowCreate = createIfMissing)
        if (!resolved.file.exists()) {
            if (!createIfMissing || !isCreatablePath(resolved.logicalPath)) {
                throw IllegalArgumentException("File does not exist and cannot be created: ${resolved.logicalPath}")
            }
            resolved.file.parentFile?.mkdirs()
            resolved.file.writeText("")
        }

        val normalizedMode = mode.trim().lowercase(Locale.US)
        val original = resolved.file.readText()
        val updated = when (normalizedMode) {
            "append" -> appendContent(original, content, parsed.heading)
            "replace" -> replaceContent(original, content, oldText, parsed.heading)
            "overwrite" -> overwriteContent(original, content, parsed.heading)
            else -> throw IllegalArgumentException("Unsupported edit mode: $mode")
        }
        writeAtomic(resolved.file, updated)
        read(resolved.logicalPath, parsed.heading)
    }

    fun link(fromPath: String, toPath: String, label: String? = null): MemoryReadResult = synchronized(lock) {
        ensureSeeded()
        val from = resolvePath(parseReference(fromPath, null).path, allowCreate = false)
        val to = resolvePath(parseReference(toPath, null).path, allowCreate = false)
        if (!from.file.exists()) throw IllegalArgumentException("Source memory file does not exist: ${from.logicalPath}")
        if (!to.file.exists()) throw IllegalArgumentException("Target memory file does not exist: ${to.logicalPath}")
        val linkLabel = label?.trim()?.ifBlank { null } ?: to.logicalPath
        val linkLine = "- $linkLabel: [[${to.logicalPath}]]"
        val text = from.file.readText()
        val updated = if (text.contains("[[${to.logicalPath}]]")) {
            text
        } else {
            appendContent(text, linkLine, heading = null)
        }
        if (updated != text) {
            writeAtomic(from.file, updated)
        }
        read(from.logicalPath)
    }

    private fun ensureSeeded() = synchronized(lock) {
        rootDir.mkdirs()
        ROOT_FILES.forEach { path ->
            val target = File(rootDir, path)
            if (!target.exists()) {
                writeAtomic(target, seedText(path))
            }
        }
    }

    private fun seedText(path: String): String {
        return seedLoader(path)?.takeIf { it.isNotBlank() }
            ?: BUILT_IN_SEEDS[path]
            ?: ""
    }

    private fun readRootFile(path: String): String {
        return File(rootDir, path).takeIf { it.exists() }?.readText().orEmpty()
    }

    private fun parseReference(path: String, explicitHeading: String?): ParsedReference {
        var cleaned = path.trim()
        if (cleaned.startsWith("[[") && cleaned.endsWith("]]")) {
            cleaned = cleaned.removePrefix("[[").removeSuffix("]]").trim()
        }
        val pathPart = cleaned.substringBefore('#').trim()
        val headingPart = explicitHeading?.trim()?.ifBlank { null }
            ?: cleaned.substringAfter('#', "").trim().ifBlank { null }
        return ParsedReference(pathPart, headingPart)
    }

    private fun resolvePath(path: String, allowCreate: Boolean): ResolvedMemoryFile {
        val normalized = path.trim().replace('\\', '/')
        validatePath(normalized, allowCreate)
        val file = File(rootDir, normalized)
        val rootCanonical = rootDir.canonicalFile
        val targetCanonical = file.canonicalFile
        if (targetCanonical != rootCanonical && !targetCanonical.path.startsWith(rootCanonical.path + File.separator)) {
            throw IllegalArgumentException("Path escapes memory root.")
        }
        return ResolvedMemoryFile(normalized, targetCanonical)
    }

    private fun validatePath(path: String, allowCreate: Boolean) {
        if (path.isBlank()) throw IllegalArgumentException("Missing memory path.")
        if (path.startsWith("/") || path.contains("://")) {
            throw IllegalArgumentException("Only relative memory paths are allowed.")
        }
        if (!path.endsWith(".md")) throw IllegalArgumentException("Only Markdown files are allowed.")
        val segments = path.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." || it.startsWith(".") }) {
            throw IllegalArgumentException("Invalid memory path segment.")
        }
        val allowed = if (segments.size == 1) {
            path in ROOT_FILES
        } else {
            segments.first() in ALLOWED_DIRS
        }
        if (!allowed) throw IllegalArgumentException("Memory path is not allowed: $path")
        if (allowCreate && segments.size == 1 && path !in ROOT_FILES) {
            throw IllegalArgumentException("New root memory files are not allowed.")
        }
    }

    private fun validatePrefix(prefix: String) {
        if (prefix.startsWith("/") || prefix.contains("..") || prefix.split('/').any { it.startsWith(".") }) {
            throw IllegalArgumentException("Invalid memory prefix.")
        }
        val first = prefix.substringBefore('/')
        if (prefix !in ROOT_FILES && first !in ALLOWED_DIRS && first !in ROOT_FILES.map { it.removeSuffix(".md") } && "$first.md" !in ROOT_FILES) {
            throw IllegalArgumentException("Memory prefix is not allowed: $prefix")
        }
    }

    private fun isCreatablePath(path: String): Boolean {
        return path.substringBefore('/') in ALLOWED_DIRS
    }

    private fun allowedMarkdownFiles(): List<ResolvedMemoryFile> {
        val rootCanonical = rootDir.canonicalFile
        val files = mutableListOf<ResolvedMemoryFile>()
        rootCanonical.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .forEach { file ->
                val relative = file.relativeTo(rootCanonical).invariantSeparatorsPath
                runCatching { resolvePath(relative, allowCreate = false) }
                    .getOrNull()
                    ?.let(files::add)
            }
        return files
    }

    private fun appendContent(original: String, content: String, heading: String?): String {
        val addition = content.trimEnd()
        if (heading == null) {
            return buildString {
                append(original.trimEnd())
                if (isNotEmpty()) appendLine()
                appendLine(addition)
            }
        }
        val section = sectionBounds(original, heading)
            ?: throw IllegalArgumentException("Heading not found: $heading")
        val before = original.substring(0, section.end).trimEnd()
        val after = original.substring(section.end)
        return buildString {
            append(before)
            appendLine()
            appendLine(addition)
            append(after)
        }
    }

    private fun replaceContent(original: String, content: String, oldText: String?, heading: String?): String {
        val needle = oldText ?: throw IllegalArgumentException("replace mode requires old_text.")
        if (heading == null) {
            if (!original.contains(needle)) throw IllegalArgumentException("old_text not found.")
            return original.replaceFirst(needle, content)
        }
        val section = sectionBounds(original, heading)
            ?: throw IllegalArgumentException("Heading not found: $heading")
        val sectionText = original.substring(section.start, section.end)
        if (!sectionText.contains(needle)) throw IllegalArgumentException("old_text not found in section.")
        return original.substring(0, section.start) +
            sectionText.replaceFirst(needle, content) +
            original.substring(section.end)
    }

    private fun overwriteContent(original: String, content: String, heading: String?): String {
        if (heading == null) return content.trimEnd() + "\n"
        val section = sectionBounds(original, heading)
            ?: throw IllegalArgumentException("Heading not found: $heading")
        val headingLine = original.substring(section.headingStart, section.bodyStart)
        val replacement = headingLine.trimEnd() + "\n\n" + content.trimEnd() + "\n"
        return original.substring(0, section.headingStart) + replacement + original.substring(section.end)
    }

    private fun extractSection(text: String, heading: String): String? {
        val bounds = sectionBounds(text, heading) ?: return null
        return text.substring(bounds.headingStart, bounds.end).trimEnd()
    }

    private fun sectionBounds(text: String, heading: String): SectionBounds? {
        val target = slugify(heading)
        val matches = HEADING_REGEX.findAll(text).toList()
        for ((index, match) in matches.withIndex()) {
            val headingText = match.groupValues[2].trim()
            if (slugify(headingText) != target && !headingText.equals(heading, ignoreCase = true)) continue
            val level = match.groupValues[1].length
            val next = matches.drop(index + 1).firstOrNull { it.groupValues[1].length <= level }
            val lineEnd = text.indexOf('\n', match.range.last + 1).let { if (it == -1) text.length else it + 1 }
            return SectionBounds(
                headingStart = match.range.first,
                bodyStart = lineEnd,
                start = lineEnd,
                end = next?.range?.first ?: text.length
            )
        }
        return null
    }

    private fun extractHeadings(text: String): List<String> {
        return HEADING_REGEX.findAll(text).map { it.groupValues[2].trim() }.toList()
    }

    private fun extractLinks(text: String): List<String> {
        val wiki = WIKI_LINK_REGEX.findAll(text).map { it.groupValues[1].trim() }
        val markdown = MARKDOWN_LINK_REGEX.findAll(text).map { it.groupValues[1].trim() }
        return (wiki + markdown).filter { it.isNotBlank() }.distinct().toList()
    }

    private fun parseChannelList(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val body = parseSectionBody(text, "Preferred channels") ?: return emptyList()
        return body.lines()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
    }

    private fun parseSectionBody(text: String, heading: String): String? {
        if (text.isBlank()) return null
        val bounds = sectionBounds(text, heading) ?: return null
        return text.substring(bounds.start, bounds.end).trim()
    }

    private fun mergeNote(existing: String, note: String?, stamp: String): String {
        val trimmedNote = note?.trim().orEmpty()
        if (trimmedNote.isBlank()) return existing
        val newLine = "- $stamp: $trimmedNote"
        if (existing.lines().any { it.trim() == newLine }) return existing
        return if (existing.isBlank()) newLine else "$existing\n$newLine"
    }

    private fun isoSecondsTimestamp(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(millis))
    }

    private fun isoDateStamp(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(millis))
    }

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("Failed to write memory file: ${target.name}")
        }
    }

    private data class ParsedReference(val path: String, val heading: String?)
    private data class ResolvedMemoryFile(val logicalPath: String, val file: File)
    private data class SectionBounds(
        val headingStart: Int,
        val bodyStart: Int,
        val start: Int,
        val end: Int
    )

    companion object {
        private const val MEMORY_DIR_NAME = "assistant_memory"
        private const val SEED_ASSET_DIR = "memory_seed"
        const val SOUL_FILE = "soul.md"
        const val MAIN_FILE = "main.md"
        const val ROUTINES_FILE = "routines.md"

        private val ROOT_FILES = setOf(SOUL_FILE, MAIN_FILE, ROUTINES_FILE)
        private val ALLOWED_DIRS = setOf(
            "people", "places", "projects", "topics", "preferences", "notes", "routines", "logs"
        )
        private val HEADING_REGEX = Regex("""(?m)^(#{1,6})\s+(.+?)\s*$""")
        private val WIKI_LINK_REGEX = Regex("""\[\[([^]]+)]]""")
        private val MARKDOWN_LINK_REGEX = Regex("""\[[^]]+]\(([^)]+)\)""")

        private val BUILT_IN_SEEDS = mapOf(
            SOUL_FILE to """
                # Soul
            """.trimIndent() + "\n",
            MAIN_FILE to """
                # Main Memory

                Name:
                Home:
                Work:
                Default commute mode:
                Default messaging app:
                Commute playlist:

                ## Onboarding
                Status: not_started
                Current step: name
                Skipped steps:

                ## Routines
                - Commute to Work: read [[routines.md#commute-to-work]]

                ## Contact Messaging Availability
            """.trimIndent() + "\n",
            ROUTINES_FILE to """
                # Routines

                ## Commute to Work

                Use:
                - Work from [[main.md]]
                - Default commute mode from [[main.md]]
                - Commute playlist from [[main.md]]

                Steps:
                1. If Work is missing, ask the user for it.
                2. If Default commute mode is missing, ask the user for it.
                3. Call check_calendar with range today.
                4. Call read_notifications for Slack from the last 180 minutes, limit 10.
                5. Call check_emails for the last 24 hours, max 10.
                6. Call maps_travel_time to Work using the default commute mode.
                7. If Commute playlist is present, start it with spotify_play_playlist.
                8. Start navigation to Work with start_navigation.
                9. Finish with one concise summary covering calendar, Slack, Gmail, travel time, playlist, and navigation status.

                ## Memory Onboarding

                This routine is handled deterministically by the app setup onboarding form.

                Collect these optional fields:
                1. Name
                2. Assistant personality
                3. Home location
                4. Work location
                5. Default messaging app

                Save answers into [[main.md]] and [[soul.md]]. If the user leaves a field blank, leave that memory field blank and record the skipped step in [[main.md]]. When the form is submitted, set onboarding Status to complete so memory onboarding is never shown again.
            """.trimIndent() + "\n"
        )

        fun slugify(value: String): String {
            return value.trim()
                .lowercase(Locale.US)
                .replace(Regex("""[^a-z0-9]+"""), "-")
                .trim('-')
        }

        private fun assetSeedLoader(context: Context): (String) -> String? = { path ->
            runCatching {
                context.assets.open("$SEED_ASSET_DIR/$path").bufferedReader().use { it.readText() }
            }.getOrNull()
        }
    }
}
