package com.example.mobile_assistant

/**
 * Pure scoring + aggregation logic. No Android / org.json dependencies so it is fully
 * unit-testable on the JVM.
 *
 * Recovery rate is judge-driven: the judge identifies episodes (from tool error codes
 * AND visually-ineffective actions), and this object turns those qualitative findings
 * into a deterministic, reproducible score.
 */
internal object EvalScoring {

    /**
     * Score for a single recovery episode:
     *  - not detected, or never recovered (stepsToRecover < 0)        -> 0.0
     *  - corrected in <= 1 extra step                                 -> 1.0
     *  - corrected in 2 extra steps                                   -> 0.6
     *  - took >= 3 extra steps (e.g. tapped the same dud button ~3x)  -> 0.0
     */
    fun episodeScore(detected: Boolean, stepsToRecover: Int): Double {
        if (!detected || stepsToRecover < 0) return 0.0
        return when {
            stepsToRecover <= 1 -> 1.0
            stepsToRecover == 2 -> 0.6
            else -> 0.0
        }
    }

    /**
     * Run recovery rate = mean of per-episode scores.
     * No episodes means nothing went wrong, which is perfect recovery -> 1.0.
     */
    fun recoveryRate(episodes: List<RecoveryEpisode>): Double {
        if (episodes.isEmpty()) return 1.0
        val total = episodes.sumOf { episodeScore(it.detected, it.stepsToRecover) }
        return total / episodes.size
    }

    /**
     * Fold completed runs into per-model rollups for the comparison panel.
     * A run counts as "completed" using the final verdict (manual override wins,
     * else the judge's call). Runs without a saved verdict are ignored for the
     * completion/recovery rates but still counted for step/tool averages.
     */
    fun aggregateByModel(records: List<EvalRunRecord>): List<ModelAggregate> {
        return records
            .groupBy { it.agentModel }
            .toSortedMap()
            .map { (model, runs) ->
                val verdicts = runs.mapNotNull { it.verdict }
                val completed = verdicts.count { effectiveCompleted(it) }
                ModelAggregate(
                    agentModel = model,
                    runCount = runs.size,
                    completionRate = ratio(completed, verdicts.size),
                    avgSteps = mean(runs.map { it.stepCount.toDouble() }),
                    avgToolCalls = mean(runs.map { it.toolCalls.size.toDouble() }),
                    avgRecoveryRate = mean(verdicts.map { effectiveRecoveryRate(it) }),
                    userStoppedCount = runs.count { it.userStopped },
                    userIntervenedCount = runs.count { it.userIntervened }
                )
            }
    }

    fun effectiveCompleted(verdict: EvalVerdict): Boolean =
        verdict.manualCompletedOverride ?: verdict.completed

    fun effectiveRecoveryRate(verdict: EvalVerdict): Double =
        verdict.manualRecoveryOverride ?: verdict.recoveryRate

    private fun ratio(num: Int, denom: Int): Double =
        if (denom <= 0) 0.0 else num.toDouble() / denom.toDouble()

    private fun mean(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.sum() / values.size
}
