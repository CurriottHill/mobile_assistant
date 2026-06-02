package com.example.mobile_assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class MemoryOnboardingTurn(
    val assistantMessage: String,
    val complete: Boolean
)

internal class MemoryOnboardingManager(
    private val repository: MemoryRepository
) {
    private enum class Step(
        val key: String,
        val question: String,
        val mainField: String? = null
    ) {
        NAME("name", "What is your name?", "Name"),
        PERSONALITY("personality", "What personality should I have?"),
        HOME("home", "What is your home location?", "Home"),
        WORK("work", "What is your work location?", "Work"),
        DEFAULT_MESSAGING("default_messaging_app", "What should your default messaging app be?", "Default messaging app");

        companion object {
            fun fromKey(key: String?): Step {
                return entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: NAME
            }
        }
    }

    fun needsOnboarding(): Boolean {
        val main = repository.read(MemoryRepository.MAIN_FILE).content
        if (onboardingValue(main, "Status").equals("complete", ignoreCase = true)) return false
        return true
    }

    fun completeFromForm(
        name: String,
        personality: String,
        home: String,
        work: String,
        defaultMessagingApp: String
    ) {
        val answers = mapOf(
            Step.NAME to name.trim(),
            Step.PERSONALITY to personality.trim(),
            Step.HOME to home.trim(),
            Step.WORK to work.trim(),
            Step.DEFAULT_MESSAGING to defaultMessagingApp.trim()
        )
        answers.forEach { (step, answer) ->
            if (answer.isBlank()) recordSkippedStep(step) else saveAnswer(step, answer)
        }
        persistOnboardingState(status = "complete", currentStep = null, completedAt = timestampUtc())
    }

    fun startOrResumeQuestion(): String? {
        if (!needsOnboarding()) return null
        val main = repository.read(MemoryRepository.MAIN_FILE).content
        val step = Step.fromKey(onboardingValue(main, "Current step"))
        persistOnboardingState(status = "in_progress", currentStep = step)
        return step.question
    }

    fun submitAnswer(rawAnswer: String): MemoryOnboardingTurn {
        val answer = rawAnswer.trim()
        if (isFinishLater(answer)) {
            persistOnboardingState(status = "complete", currentStep = null, completedAt = timestampUtc())
            return MemoryOnboardingTurn(
                assistantMessage = "No problem. I will learn the rest as we go.",
                complete = true
            )
        }

        val main = repository.read(MemoryRepository.MAIN_FILE).content
        val currentStep = Step.fromKey(onboardingValue(main, "Current step"))
        val skipped = isSkip(answer)
        if (!skipped) {
            saveAnswer(currentStep, answer)
        }

        val nextStep = nextStepAfter(currentStep)
        return if (nextStep == null) {
            persistOnboardingState(status = "complete", currentStep = null, completedAt = timestampUtc())
            MemoryOnboardingTurn(
                assistantMessage = "All set. I will remember that.",
                complete = true
            )
        } else {
            persistOnboardingState(status = "in_progress", currentStep = nextStep)
            MemoryOnboardingTurn(
                assistantMessage = if (skipped) {
                    "No problem. ${nextStep.question}"
                } else {
                    "Got it. ${nextStep.question}"
                },
                complete = false
            )
        }
    }

    private fun saveAnswer(step: Step, answer: String) {
        when (step) {
            Step.PERSONALITY -> {
                repository.edit(
                    path = MemoryRepository.SOUL_FILE,
                    mode = "overwrite",
                    content = "# Soul\n\n${answer.trim()}\n"
                )
            }
            Step.DEFAULT_MESSAGING -> updateMainField(step.mainField ?: return, normalizeMessagingApp(answer))
            else -> updateMainField(step.mainField ?: return, cleanFactAnswer(answer))
        }
    }

    private fun updateMainField(field: String, value: String) {
        val main = repository.read(MemoryRepository.MAIN_FILE).content
        val updated = setLineValue(main, field, value)
        repository.edit(
            path = MemoryRepository.MAIN_FILE,
            mode = "overwrite",
            content = updated
        )
    }

    private fun persistOnboardingState(
        status: String,
        currentStep: Step?,
        completedAt: String? = null
    ) {
        val main = repository.read(MemoryRepository.MAIN_FILE).content
        var updated = ensureOnboardingSection(main)
        updated = setLineValue(updated, "Status", status)
        updated = if (currentStep == null) {
            setLineValue(updated, "Current step", "")
        } else {
            setLineValue(updated, "Current step", currentStep.key)
        }
        if (completedAt != null) {
            updated = setLineValue(updated, "Completed at", completedAt)
        }
        repository.edit(
            path = MemoryRepository.MAIN_FILE,
            mode = "overwrite",
            content = updated
        )
    }

    private fun ensureOnboardingSection(main: String): String {
        if (Regex("""(?m)^##\s+Onboarding\s*$""").containsMatchIn(main)) return main
        return buildString {
            append(main.trimEnd())
            if (isNotEmpty()) appendLine()
            appendLine()
            appendLine("## Onboarding")
            appendLine("Status: not_started")
            appendLine("Current step: name")
            appendLine("Skipped steps:")
        }
    }

    private fun recordSkippedStep(step: Step) {
        val main = repository.read(MemoryRepository.MAIN_FILE).content
        val existing = skippedSteps(main)
        if (step.key in existing) return
        repository.edit(
            path = MemoryRepository.MAIN_FILE,
            mode = "overwrite",
            content = setLineValue(
                ensureOnboardingSection(main),
                "Skipped steps",
                (existing + step.key).joinToString(", ")
            )
        )
    }

    private fun setLineValue(text: String, label: String, value: String): String {
        val replacement = "$label: ${value.trim()}"
        val regex = Regex("""(?m)^${Regex.escape(label)}:.*$""")
        return if (regex.containsMatchIn(text)) {
            regex.replaceFirst(text, replacement)
        } else {
            buildString {
                append(text.trimEnd())
                if (isNotEmpty()) appendLine()
                appendLine(replacement)
            }
        }
    }

    private fun onboardingValue(main: String, label: String): String? {
        val onboarding = repositorySection(main, "Onboarding") ?: main
        return Regex("""(?m)^${Regex.escape(label)}:[ \t]*(.*)$""")
            .find(onboarding)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.ifBlank { null }
    }

    private fun skippedSteps(main: String): List<String> {
        return onboardingValue(main, "Skipped steps")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    private fun repositorySection(text: String, heading: String): String? {
        val start = Regex("""(?m)^##\s+${Regex.escape(heading)}\s*$""").find(text) ?: return null
        val next = Regex("""(?m)^##\s+.+$""").find(text, start.range.last + 1)
        return text.substring(start.range.first, next?.range?.first ?: text.length)
    }

    private fun nextStepAfter(step: Step): Step? {
        val steps = Step.entries
        val nextIndex = steps.indexOf(step) + 1
        return steps.getOrNull(nextIndex)
    }

    private fun isSkip(answer: String): Boolean {
        val normalized = answer.lowercase(Locale.US).trim()
        return normalized.isBlank() ||
            normalized in setOf("skip", "skip this", "not now", "later", "do this later", "none", "no")
    }

    private fun isFinishLater(answer: String): Boolean {
        val normalized = answer.lowercase(Locale.US).trim()
        return normalized in setOf("skip onboarding", "stop onboarding", "finish later", "do onboarding later")
    }

    private fun cleanFactAnswer(answer: String): String {
        return answer.trim()
            .replace(Regex("""(?i)^my name is\s+"""), "")
            .replace(Regex("""(?i)^i am\s+"""), "")
            .replace(Regex("""(?i)^i'm\s+"""), "")
            .replace(Regex("""(?i)^my home is\s+"""), "")
            .replace(Regex("""(?i)^i live at\s+"""), "")
            .replace(Regex("""(?i)^i live in\s+"""), "")
            .replace(Regex("""(?i)^my work is\s+"""), "")
            .replace(Regex("""(?i)^i work at\s+"""), "")
            .trim()
    }

    private fun normalizeMessagingApp(answer: String): String {
        val cleaned = cleanFactAnswer(answer).lowercase(Locale.US)
        return when {
            cleaned.replace(" ", "") == "whatsapp" || cleaned == "what's app" -> "whatsapp"
            cleaned.contains("telegram") -> "telegram"
            cleaned.contains("signal") -> "signal"
            cleaned.contains("sms") -> "sms"
            cleaned.contains("text message") -> "sms"
            else -> cleaned
        }
    }

    private fun timestampUtc(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
}
