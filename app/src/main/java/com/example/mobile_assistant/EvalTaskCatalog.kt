package com.example.mobile_assistant

import android.content.Context
import org.json.JSONObject

internal data class EvalTask(
    val id: String,
    val prompt: String,
    val successCriteria: String,
    val setupHint: String
)

internal object EvalTaskCatalog {

    private const val ASSET_NAME = "eval_tasks.json"

    fun load(context: Context): List<EvalTask> {
        return runCatching {
            val raw = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            parse(raw)
        }.getOrDefault(emptyList())
    }

    /** Pure parse (org.json only) so it can be unit-tested. Malformed input -> empty list. */
    fun parse(raw: String): List<EvalTask> {
        return runCatching {
            val arr = JSONObject(raw).optJSONArray("tasks") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
                val id = o.optString("id").trim()
                val prompt = o.optString("prompt").trim()
                if (id.isBlank() || prompt.isBlank()) return@mapNotNull null
                EvalTask(
                    id = id,
                    prompt = prompt,
                    successCriteria = o.optString("success_criteria").trim(),
                    setupHint = o.optString("setup_hint").trim()
                )
            }
        }.getOrDefault(emptyList())
    }
}
