package com.example.mobile_assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MemoryRepositoryTest {

    @Test
    fun seedFiles_areCreatedWhenMissing() {
        val (dir, repository) = tempRepository()

        val snapshot = repository.promptSnapshot()

        assertTrue(File(dir, "soul.md").exists())
        assertTrue(File(dir, "main.md").exists())
        assertTrue(File(dir, "routines.md").exists())
        assertEquals("# Soul", snapshot.soulMarkdown.trim())
        assertTrue(snapshot.mainMarkdown.contains("Default messaging app:"))
        assertFalse(snapshot.mainMarkdown.contains("Default messaging app: whatsapp"))
        assertTrue(snapshot.mainMarkdown.contains("## Onboarding"))
        assertTrue(snapshot.mainMarkdown.contains("Status: not_started"))
        assertTrue(snapshot.mainMarkdown.contains("Skipped steps:"))
        assertTrue(snapshot.mainMarkdown.contains("[[routines.md#commute-to-work]]"))
    }

    @Test
    fun read_supportsExactFileAndHeadingSlug() {
        val (_, repository) = tempRepository()

        val full = repository.read("main.md")
        val commute = repository.read("routines.md", "commute-to-work")

        assertTrue(full.content.contains("Default messaging app"))
        assertTrue(commute.content.contains("## Commute to Work"))
        assertTrue(commute.content.contains("Call check_calendar with range today."))
        val memoryOnboarding = repository.read("routines.md", "memory-onboarding").content
        assertTrue(memoryOnboarding.contains("## Memory Onboarding"))
        assertTrue(memoryOnboarding.contains("app setup onboarding form"))
        assertTrue(memoryOnboarding.contains("Collect these optional fields"))
        assertFalse(full.content.contains("Call check_calendar with range today."))
    }

    @Test
    fun memoryOnboarding_completeFromFormWritesFieldsAndCompletes() {
        val (_, repository) = tempRepository()
        val onboarding = MemoryOnboardingManager(repository)

        onboarding.completeFromForm(
            name = "Will",
            personality = "Warm, direct, and practical",
            home = "London",
            work = "",
            defaultMessagingApp = "WhatsApp"
        )

        assertFalse(onboarding.needsOnboarding())
        val main = repository.read("main.md").content
        val soul = repository.read("soul.md").content
        assertTrue(main.contains("Name: Will"))
        assertTrue(main.contains("Home: London"))
        assertTrue(main.contains("Work:"))
        assertTrue(main.contains("Default messaging app: whatsapp"))
        assertTrue(main.contains("Skipped steps: work"))
        assertTrue(main.contains("Status: complete"))
        assertTrue(main.contains("Completed at:"))
        assertTrue(soul.contains("Warm, direct, and practical"))
    }

    @Test
    fun memoryOnboarding_detectsBlankStructuralMemoryAndCompletesAfterCoreQuestions() {
        val (_, repository) = tempRepository()
        val onboarding = MemoryOnboardingManager(repository)
        val questions = mutableListOf<String>()

        assertTrue(onboarding.needsOnboarding())
        questions += onboarding.startOrResumeQuestion().orEmpty()
        questions += onboarding.submitAnswer("my name is Will").assistantMessage
        questions += onboarding.submitAnswer("warm, dry, and practical").assistantMessage
        questions += onboarding.submitAnswer("I live in London").assistantMessage
        questions += onboarding.submitAnswer("I work at OpenAI London").assistantMessage
        val done = onboarding.submitAnswer("WhatsApp")

        assertTrue(done.complete)
        assertFalse(onboarding.needsOnboarding())

        val main = repository.read("main.md").content
        val soul = repository.read("soul.md").content
        assertTrue(main.contains("Name: Will"))
        assertTrue(main.contains("Home: London"))
        assertTrue(main.contains("Work: OpenAI London"))
        assertTrue(main.contains("Default messaging app: whatsapp"))
        assertTrue(main.contains("Status: complete"))
        assertTrue(soul.contains("warm, dry, and practical"))
        assertFalse(questions.joinToString(" ").contains("commute", ignoreCase = true))
        assertFalse(questions.joinToString(" ").contains("playlist", ignoreCase = true))
    }

    @Test
    fun memoryOnboarding_skippedAnswersRemainBlankButAllowCompletion() {
        val (_, repository) = tempRepository()
        val onboarding = MemoryOnboardingManager(repository)

        assertEquals("What is your name?", onboarding.startOrResumeQuestion())
        onboarding.submitAnswer("skip")
        onboarding.submitAnswer("skip")
        onboarding.submitAnswer("skip")
        onboarding.submitAnswer("skip")
        val done = onboarding.submitAnswer("skip")

        assertTrue(done.complete)
        assertFalse(onboarding.needsOnboarding())
        val main = repository.read("main.md").content
        val soul = repository.read("soul.md").content
        assertTrue(main.contains("Name:"))
        assertTrue(main.contains("Home:"))
        assertTrue(main.contains("Work:"))
        assertTrue(main.contains("Default messaging app:"))
        assertTrue(main.contains("Status: complete"))
        assertEquals("# Soul", soul.trim())
    }

    @Test
    fun edit_supportsAppendReplaceOverwriteAndAllowedCreate() {
        val (_, repository) = tempRepository()

        repository.edit(
            path = "main.md",
            mode = "append",
            heading = "contact-messaging-availability",
            content = "Alice: whatsapp, telegram"
        )
        assertTrue(repository.read("main.md").content.contains("Alice: whatsapp, telegram"))

        repository.edit(
            path = "main.md",
            mode = "replace",
            oldText = "Alice: whatsapp, telegram",
            content = "Alice: signal"
        )
        assertTrue(repository.read("main.md").content.contains("Alice: signal"))

        repository.edit(
            path = "people/alice.md",
            mode = "overwrite",
            content = "# Alice\n\nPreferred app: signal",
            createIfMissing = true
        )
        assertTrue(repository.read("people/alice.md").content.contains("Preferred app: signal"))
    }

    @Test
    fun link_addsWikiStyleLink() {
        val (_, repository) = tempRepository()
        repository.edit(
            path = "people/alice.md",
            mode = "overwrite",
            content = "# Alice",
            createIfMissing = true
        )

        repository.link("main.md", "people/alice.md", "Alice")

        assertTrue(repository.read("main.md").content.contains("- Alice: [[people/alice.md]]"))
    }

    @Test
    fun pathValidation_rejectsDisallowedPaths() {
        val (_, repository) = tempRepository()

        assertIllegal { repository.read("/main.md") }
        assertIllegal { repository.read("../main.md") }
        assertIllegal { repository.read(".hidden.md") }
        assertIllegal { repository.read("people/alice.txt") }
        assertIllegal {
            repository.edit(
                path = "random.md",
                mode = "overwrite",
                content = "# Random",
                createIfMissing = true
            )
        }
    }

    @Test
    fun upsertPerson_createsFileWithChannelAndAppendsAdditionalChannel() {
        val (_, repository) = tempRepository()

        repository.upsertPerson(name = "Alice", channel = "whatsapp")
        val firstRead = repository.read("people/alice.md").content
        assertTrue(firstRead.contains("# Alice"))
        assertTrue(firstRead.contains("## Preferred channels"))
        assertTrue(firstRead.contains("- whatsapp"))
        assertTrue(firstRead.contains("## Last contacted"))
        assertTrue(firstRead.contains("whatsapp @"))

        repository.upsertPerson(name = "Alice", channel = "sms")
        val secondRead = repository.read("people/alice.md").content
        assertTrue(secondRead.contains("- whatsapp"))
        assertTrue(secondRead.contains("- sms"))
        assertTrue(secondRead.contains("sms @"))
    }

    @Test
    fun upsertPerson_withBlankNameDoesNothing() {
        val (dir, repository) = tempRepository()

        val result = repository.upsertPerson(name = "   ", channel = "sms")

        assertEquals(null, result)
        assertFalse(File(dir, "people").exists() && File(dir, "people").listFiles()?.isNotEmpty() == true)
    }

    @Test
    fun upsertPlace_createsFileAndUpdatesOnSecondCall() {
        val (_, repository) = tempRepository()

        repository.upsertPlace(
            name = "Feed Store",
            coords = "37.7749,-122.4194",
            address = "123 Main St"
        )
        val first = repository.read("places/feed-store.md").content
        assertTrue(first.contains("# Feed Store"))
        assertTrue(first.contains("37.7749,-122.4194"))
        assertTrue(first.contains("123 Main St"))
        assertTrue(first.contains("## Last used"))

        repository.upsertPlace(name = "Feed Store")
        val second = repository.read("places/feed-store.md").content
        // Coords and address persist across a no-data follow-up call.
        assertTrue(second.contains("37.7749,-122.4194"))
        assertTrue(second.contains("123 Main St"))
    }

    @Test
    fun upsertPreference_appendsTimestampedNoteUnderNotesHeading() {
        val (_, repository) = tempRepository()

        repository.upsertPreference(
            topic = "Farmers Market",
            statement = "Prefer Monday mornings"
        )
        val first = repository.read("preferences/farmers-market.md").content
        assertTrue(first.contains("# Farmers Market"))
        assertTrue(first.contains("## Notes"))
        assertTrue(first.contains("Prefer Monday mornings"))

        repository.upsertPreference(
            topic = "Farmers Market",
            statement = "Bring cash"
        )
        val second = repository.read("preferences/farmers-market.md").content
        assertTrue(second.contains("Prefer Monday mornings"))
        assertTrue(second.contains("Bring cash"))
    }

    @Test
    fun upsertRoutine_writesStepsList() {
        val (_, repository) = tempRepository()

        repository.upsertRoutine(
            name = "Morning Briefing",
            steps = listOf(
                "Check calendar",
                "Check notifications",
                "Read weather"
            )
        )

        val content = repository.read("routines.md", "morning-briefing").content
        assertTrue(content.contains("## Morning Briefing"))
        assertTrue(content.contains("Steps:"))
        assertTrue(content.contains("1. Check calendar"))
        assertTrue(content.contains("2. Check notifications"))
        assertTrue(content.contains("3. Read weather"))
        assertTrue(repository.read("main.md").content.contains("- Morning Briefing: read [[routines.md#morning-briefing]]"))
    }

    @Test
    fun memoryToolService_returnsStructuredResultsAndErrors() {
        val (_, repository) = tempRepository()
        val service = MemoryToolService(repository)

        val read = service.executeRead(
            JSONObject()
                .put("path", "routines.md")
                .put("heading", "commute-to-work")
        )
        val bad = service.executeRead(JSONObject().put("path", "../main.md"))

        assertTrue(read.content.getBoolean("ok"))
        assertEquals(SharedToolSchemas.TOOL_MEMORY_READ, read.toolName)
        assertTrue(read.content.getString("content").contains("Commute to Work"))
        assertFalse(bad.content.getBoolean("ok"))
    }

    @Test
    fun memorySaveFact_savesPreferenceWhenReviewerApproves() {
        val (_, repository) = tempRepository()
        val service = MemoryToolService(
            repository,
            saveFactReviewer = StaticMemorySaveFactReviewer(
                MemorySaveFactDecision(
                    save = true,
                    statement = "Prefers jazz for focus work",
                    reason = "New preference"
                )
            )
        )

        val result = service.executeSaveFact(
            JSONObject()
                .put("kind", "preference")
                .put("subject", "music")
                .put("fact", "I prefer jazz when I'm focusing")
        )

        assertTrue(result.content.getBoolean("ok"))
        assertTrue(result.content.getBoolean("saved"))
        val saved = repository.read("preferences/music.md").content
        assertTrue(saved.contains("Prefers jazz for focus work"))
    }

    @Test
    fun memorySaveFact_skipsWhenReviewerFindsNoNewMemory() {
        val (dir, repository) = tempRepository()
        val service = MemoryToolService(
            repository,
            saveFactReviewer = StaticMemorySaveFactReviewer(
                MemorySaveFactDecision(
                    save = false,
                    reason = "Duplicate"
                )
            )
        )

        val result = service.executeSaveFact(
            JSONObject()
                .put("kind", "preference")
                .put("subject", "music")
                .put("fact", "I prefer jazz when I'm focusing")
        )

        assertTrue(result.content.getBoolean("ok"))
        assertFalse(result.content.getBoolean("saved"))
        assertFalse(File(dir, "preferences/music.md").exists())
    }

    @Test
    fun memorySaveFact_canUpdateMainMemoryField() {
        val (_, repository) = tempRepository()
        val service = MemoryToolService(
            repository,
            saveFactReviewer = StaticMemorySaveFactReviewer(
                MemorySaveFactDecision(
                    save = true,
                    statement = "Default messaging app: whatsapp",
                    reason = "New default",
                    mainField = "Default messaging app",
                    mainValue = "whatsapp"
                )
            )
        )

        val result = service.executeSaveFact(
            JSONObject()
                .put("kind", "main")
                .put("subject", "Default messaging app")
                .put("fact", "Use WhatsApp by default")
        )

        assertTrue(result.content.getBoolean("ok"))
        assertTrue(result.content.getBoolean("saved"))
        assertTrue(repository.read("main.md").content.contains("Default messaging app: whatsapp"))
    }

    @Test
    fun memorySaveFact_savesPersonChannelAndMainContactAvailability() {
        val (_, repository) = tempRepository()
        val service = MemoryToolService(
            repository,
            saveFactReviewer = StaticMemorySaveFactReviewer(
                MemorySaveFactDecision(
                    save = true,
                    statement = "Alice is available on WhatsApp",
                    reason = "New contact channel",
                    channel = "whatsapp"
                )
            )
        )

        val result = service.executeSaveFact(
            JSONObject()
                .put("kind", "person")
                .put("subject", "Alice")
                .put("fact", "Alice has WhatsApp")
        )

        assertTrue(result.content.getBoolean("ok"))
        assertTrue(result.content.getBoolean("saved"))
        val person = repository.read("people/alice.md").content
        val main = repository.read("main.md").content
        assertTrue(person.contains("- whatsapp"))
        assertTrue(main.contains("## Contact Messaging Availability"))
        assertTrue(main.contains("Alice: whatsapp"))
    }

    @Test
    fun memorySaveFact_savesRoutineIntoRootRoutineFileAndIndexesMain() {
        val (_, repository) = tempRepository()
        val service = MemoryToolService(
            repository,
            saveFactReviewer = StaticMemorySaveFactReviewer(
                MemorySaveFactDecision(
                    save = true,
                    statement = "Morning routine",
                    reason = "New routine",
                    steps = listOf("Check calendar", "Read weather")
                )
            )
        )

        val result = service.executeSaveFact(
            JSONObject()
                .put("kind", "routine")
                .put("subject", "Morning Briefing")
                .put("fact", "Check calendar then read weather")
        )

        assertTrue(result.content.getBoolean("ok"))
        assertTrue(result.content.getBoolean("saved"))
        val routine = repository.read("routines.md", "morning-briefing").content
        val main = repository.read("main.md").content
        assertTrue(routine.contains("## Morning Briefing"))
        assertTrue(routine.contains("1. Check calendar"))
        assertTrue(routine.contains("2. Read weather"))
        assertTrue(main.contains("- Morning Briefing: read [[routines.md#morning-briefing]]"))
    }

    private fun tempRepository(): Pair<File, MemoryRepository> {
        val dir = Files.createTempDirectory("assistant-memory-test").toFile()
        return dir to MemoryRepository(dir)
    }

    private fun assertIllegal(block: () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("Expected IllegalArgumentException", thrown)
    }

    private class StaticMemorySaveFactReviewer(
        private val decision: MemorySaveFactDecision
    ) : MemorySaveFactReviewer {
        override fun review(request: MemorySaveFactReviewRequest): MemorySaveFactDecision {
            return decision
        }
    }
}
