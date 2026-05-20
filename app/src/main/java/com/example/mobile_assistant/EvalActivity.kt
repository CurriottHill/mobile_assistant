package com.example.mobile_assistant

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only eval harness. Pick a model, tap a task to inject it into the live agent
 * exactly as a typed message, let [EvalRecorder] capture the run, have Haiku judge it,
 * then confirm/override the verdict. Results persist forever (see plan §9).
 *
 * Device state is NOT auto-reset between tasks — arrange it per each task's setup hint.
 */
class EvalActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var verdictPanel: LinearLayout

    private var running = false
    private var selectedModel = AgentModelConfig.EVAL_HAIKU_MODEL
    private lateinit var modelStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eval)
        title = "Agent Evals"
        root = findViewById(R.id.evalRoot)

        addHeader("Agent Eval Harness")
        addBody(
            "Tap a task to run it on the phone as if typed. The accessibility service " +
                "must be enabled. Arrange device state per each task's setup hint first."
        )

        buildModelSelector()
        statusView = addBody("Idle.")
        addButton("View aggregates (per model)") { showAggregates() }

        verdictPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 24, 0, 24)
        }
        root.addView(verdictPanel)

        addHeader("Tasks")
        val tasks = EvalTaskCatalog.load(this)
        if (tasks.isEmpty()) {
            addBody("No tasks found in assets/eval_tasks.json")
        } else {
            tasks.forEach { addTaskRow(it) }
        }

        addBody(
            "Export: adb exec-out run-as $packageName tar c files/evals > evals.tar\n" +
                "Data dir: filesDir/evals/ (eval_runs.json, index.json, run_*.json, shots/, eval_database.txt — never deleted)"
        )
    }

    override fun onDestroy() {
        AgentModelConfig.agentModelOverride = null
        EvalRecorder.active = null
        scope.cancel()
        super.onDestroy()
    }

    // ─── UI builders ─────────────────────────────────────────────────────────

    private fun buildModelSelector() {
        addHeader("Agent model")
        val rowfun = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val haiku = Button(this).apply { text = "Haiku" }
        val sonnet = Button(this).apply { text = "Sonnet" }
        haiku.setOnClickListener {
            selectedModel = AgentModelConfig.EVAL_HAIKU_MODEL
            refreshModelStatus()
        }
        sonnet.setOnClickListener {
            selectedModel = AgentModelConfig.SONNET_MODEL
            refreshModelStatus()
        }
        rowfun.addView(haiku)
        rowfun.addView(sonnet)
        root.addView(rowfun)
        modelStatus = addBody("")
        refreshModelStatus()
    }

    private fun refreshModelStatus() {
        modelStatus.text = "Agent model for next run: $selectedModel  (judge: ${AgentModelConfig.EVAL_HAIKU_MODEL})"
    }

    private fun addTaskRow(task: EvalTask) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }
        box.addView(TextView(this).apply {
            text = "▶ ${task.id}"
            textSize = 16f
        })
        box.addView(TextView(this).apply { text = "Prompt: ${task.prompt}" })
        box.addView(TextView(this).apply { text = "Success: ${task.successCriteria}" })
        box.addView(TextView(this).apply { text = "Setup: ${task.setupHint}" })
        box.addView(Button(this).apply {
            text = "Run"
            setOnClickListener { runTask(task) }
        })
        root.addView(box)
    }

    private fun addHeader(text: String): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, 28, 0, 8)
        }
        root.addView(tv)
        return tv
    }

    private fun addBody(text: String): TextView {
        val tv = TextView(this).apply {
            this.text = text
            setPadding(0, 4, 0, 4)
        }
        root.addView(tv)
        return tv
    }

    private fun addButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }
        root.addView(b)
        return b
    }

    private fun status(text: String) {
        statusView.text = text
    }

    // ─── Run flow ────────────────────────────────────────────────────────────

    private fun runTask(task: EvalTask) {
        if (running) {
            toast("A run is already in progress")
            return
        }
        val svc = AssistantAccessibilityService.instance
        if (svc == null) {
            toast("Enable the accessibility service first")
            return
        }
        running = true
        verdictPanel.visibility = View.GONE
        AgentModelConfig.agentModelOverride = selectedModel

        val rec = EvalRecorder(applicationContext)
        rec.start(
            taskId = task.id,
            goalText = task.prompt,
            successCriteria = task.successCriteria,
            agentModel = AgentModelConfig.AGENT_MODEL,
            judgeModel = AgentModelConfig.EVAL_HAIKU_MODEL
        )
        EvalRecorder.active = rec

        status("Running ${task.id} on $selectedModel — injecting prompt…")
        if (!svc.injectAgentPrompt(task.prompt)) {
            toast("Overlay unavailable")
            cleanup()
            return
        }

        scope.launch {
            val record = awaitFinished(rec)
            if (record == null) {
                status("Timed out waiting for the run to finish.")
                cleanup()
                return@launch
            }
            status("Agent finished: ${record.stopReason}, ${record.stepCount} steps, ${record.toolCalls.size} tools. Judging with Haiku...")
            val savedRecord = withContext(Dispatchers.IO) {
                val previousRuns = EvalRecorder.loadAllRecords(this@EvalActivity)
                    .asSequence()
                    .filter { it.taskId == record.taskId && it.runId != record.runId && it.verdict != null }
                    .sortedBy { it.timestampMillis }
                    .toList()
                    .takeLast(3)
                EvalJudge(BuildConfig.ANTHROPIC_API_KEY.trim())
                    .judge(record, EvalRecorder.evalsDir(this@EvalActivity), previousRuns)
                    .let { verdict -> EvalRecorder.saveVerdict(this@EvalActivity, record, verdict) }
            }
            running = false
            AgentModelConfig.agentModelOverride = null
            status("Saved ${savedRecord.runId} (${savedRecord.verdict?.source ?: "judge"}).")
            showVerdict(savedRecord, savedRecord.verdict ?: return@launch)
        }
    }

    private suspend fun awaitFinished(rec: EvalRecorder): EvalRunRecord? {
        val deadlineMs = System.currentTimeMillis() + 6 * 60 * 1000L
        while (System.currentTimeMillis() < deadlineMs) {
            rec.finishedRecord?.let { return it }
            delay(500)
        }
        return rec.finishedRecord
    }

    private fun cleanup() {
        running = false
        AgentModelConfig.agentModelOverride = null
        EvalRecorder.active = null
    }

    // ─── Verdict confirm / override ──────────────────────────────────────────

    private fun showVerdict(record: EvalRunRecord, verdict: EvalVerdict) {
        verdictPanel.removeAllViews()
        verdictPanel.visibility = View.VISIBLE

        verdictPanel.addView(TextView(this).apply {
            text = "Judge verdict for ${record.runId} (${record.agentModel})"
            textSize = 18f
        })
        val episodes = verdict.recoveryEpisodes.joinToString("\n") {
            "  • step ${it.step} [${it.source}] ${it.whatWentWrong} → detected=${it.detected}, stepsToRecover=${it.stepsToRecover} (${it.outcome})"
        }
        verdictPanel.addView(TextView(this).apply {
            text = buildString {
                appendLine("completed=${verdict.completed}  confidence=${"%.2f".format(verdict.confidence)}")
                appendLine("quality_score=${"%.1f".format(verdict.qualityScore)}/10")
                appendLine("recovery_rate=${"%.2f".format(verdict.recoveryRate)}")
                appendLine("rationale: ${verdict.rationale}")
                appendLine("episodes:")
                append(episodes.ifBlank { "  (none)" })
            }
        })

        val completedCheck = CheckBox(this).apply {
            text = "Completed (override)"
            isChecked = verdict.completed
        }
        verdictPanel.addView(completedCheck)

        verdictPanel.addView(TextView(this).apply { text = "Recovery rate 0.0–1.0 (override):" })
        val recoveryEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.2f".format(verdict.recoveryRate))
        }
        verdictPanel.addView(recoveryEdit)

        verdictPanel.addView(Button(this).apply {
            text = "Save override"
            setOnClickListener {
                val editedCompleted = completedCheck.isChecked
                val editedRecovery = recoveryEdit.text.toString().toDoubleOrNull()
                    ?.coerceIn(0.0, 1.0) ?: verdict.recoveryRate

                val completedOverride =
                    if (editedCompleted != verdict.completed) editedCompleted else null
                val recoveryOverride =
                    if (editedRecovery != verdict.recoveryRate) editedRecovery else null
                val overridden = completedOverride != null || recoveryOverride != null
                if (!overridden) {
                    status("Judge verdict already saved for ${record.runId}.")
                    verdictPanel.visibility = View.GONE
                    return@setOnClickListener
                }

                val finalVerdict = verdict.copy(
                    source = "manual_override",
                    manualCompletedOverride = completedOverride,
                    manualRecoveryOverride = recoveryOverride
                )
                scope.launch {
                    withContext(Dispatchers.IO) {
                        EvalRecorder.saveVerdict(this@EvalActivity, record, finalVerdict)
                    }
                    EvalRecorder.active = null
                    verdictPanel.visibility = View.GONE
                    status("Saved ${record.runId} (${finalVerdict.source}).")
                }
            }
        })
        verdictPanel.post {
            (root.parent as? ScrollView)?.smoothScrollTo(0, verdictPanel.top)
        }
    }

    // ─── Aggregates ──────────────────────────────────────────────────────────

    private fun showAggregates() {
        scope.launch {
            val aggregates = withContext(Dispatchers.IO) {
                EvalScoring.aggregateByModel(EvalRecorder.loadAllRecords(this@EvalActivity))
            }
            val text = if (aggregates.isEmpty()) {
                "No saved runs yet."
            } else {
                aggregates.joinToString("\n\n") { a ->
                    buildString {
                        appendLine("model=${a.agentModel}  (runs=${a.runCount})")
                        appendLine("  completion rate: ${"%.0f%%".format(a.completionRate * 100)}")
                        appendLine("  avg steps: ${"%.1f".format(a.avgSteps)}")
                        appendLine("  avg tools: ${"%.1f".format(a.avgToolCalls)}")
                        appendLine("  avg recovery: ${"%.2f".format(a.avgRecoveryRate)}")
                        append("  user stopped/intervened: ${a.userStoppedCount}/${a.userIntervenedCount}")
                    }
                }
            }
            status(text)
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
