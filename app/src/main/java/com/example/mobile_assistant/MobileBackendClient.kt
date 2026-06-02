package com.example.mobile_assistant

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

object AccountCreditPolicy {
    const val FREE_MONTHLY_CREDITS = 50
    const val PLUS_MONTHLY_CREDITS = 1000
    const val PRO_MONTHLY_CREDITS = 2200
    const val USD_PER_CREDIT = 0.01

    fun monthlyCreditsForPlan(plan: String): Int {
        return when (plan.trim().lowercase()) {
            "plus" -> PLUS_MONTHLY_CREDITS
            "pro" -> PRO_MONTHLY_CREDITS
            else -> FREE_MONTHLY_CREDITS
        }
    }

    fun creditsForCost(estimatedCostUsd: Double): Int {
        if (estimatedCostUsd <= 0.0) return 0
        return ceil(estimatedCostUsd / USD_PER_CREDIT).toInt()
    }
}

data class MarvinAccountSummary(
    val email: String?,
    val plan: String,
    val subscriptionStatus: String,
    val monthlyCreditLimit: Int,
    val usedCreditsThisMonth: Int,
    val remainingCreditsThisMonth: Int,
    val billingPeriodEnd: String?,
    val estimatedModelCostUsd: Double
)

sealed class AssistantTaskStartResult {
    data class Allowed(val taskId: String, val remainingCreditsThisMonth: Int) : AssistantTaskStartResult()
    data class OverLimit(val message: String, val plan: String, val monthlyCreditLimit: Int, val usedCreditsThisMonth: Int) : AssistantTaskStartResult()
    data class Failed(val message: String) : AssistantTaskStartResult()
}

data class ModelUsageRecordResult(
    val modelCallId: String?,
    val sequence: Int,
    val estimatedCostUsd: Double,
    val creditsUsed: Int,
    val remainingCreditsThisMonth: Int?
)

class MobileBackendClient(private val context: Context) {
    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun accountSummary(): MarvinAccountSummary = withContext(Dispatchers.IO) {
        val json = authenticatedRequest("/api/me")
        val plan = json.optString("plan", "free")
        val estimatedModelCostUsd = json.optDouble("estimatedModelCostUsd", 0.0)
        val monthlyCreditLimit = json.optNullableInt("monthlyCreditLimit")
            ?: json.optNullableInt("monthlyCredits")
            ?: AccountCreditPolicy.monthlyCreditsForPlan(plan)
        val usedCredits = json.optNullableInt("usedCreditsThisMonth")
            ?: json.optNullableInt("creditsUsedThisMonth")
            ?: AccountCreditPolicy.creditsForCost(estimatedModelCostUsd)
        MarvinAccountSummary(
            email = json.optString("email").ifBlank { null },
            plan = plan,
            subscriptionStatus = json.optString("subscriptionStatus", "free"),
            monthlyCreditLimit = monthlyCreditLimit,
            usedCreditsThisMonth = usedCredits,
            remainingCreditsThisMonth = json.optNullableInt("remainingCreditsThisMonth")
                ?: (monthlyCreditLimit - usedCredits).coerceAtLeast(0),
            billingPeriodEnd = json.optString("billingPeriodEnd").ifBlank { null },
            estimatedModelCostUsd = estimatedModelCostUsd
        )
    }

    suspend fun startAssistantTask(
        userPrompt: String,
        taskType: String,
        mainModel: String,
        metadata: JSONObject? = null
    ): AssistantTaskStartResult = withContext(Dispatchers.IO) {
        runCatching {
            val response = authenticatedRequest(
                path = "/api/assistant/tasks/start",
                method = "POST",
                body = JSONObject()
                    .put("userPrompt", userPrompt)
                    .put("taskType", taskType)
                    .put("mainModel", mainModel)
                    .apply { metadata?.let { put("metadata", it) } }
            )
            AssistantTaskStartResult.Allowed(
                taskId = response.optString("taskId"),
                remainingCreditsThisMonth = response.optNullableInt("remainingCreditsThisMonth")
                    ?: response.optNullableInt("remainingTasksThisMonth")
                    ?: 0
            )
        }.getOrElse { error ->
            val message = error.message ?: "Could not start assistant task."
            if (message.contains("limit", ignoreCase = true) || message.contains("credit", ignoreCase = true)) {
                AssistantTaskStartResult.OverLimit(
                    message = message,
                    plan = "free",
                    monthlyCreditLimit = AccountCreditPolicy.FREE_MONTHLY_CREDITS,
                    usedCreditsThisMonth = AccountCreditPolicy.FREE_MONTHLY_CREDITS
                )
            } else {
                AssistantTaskStartResult.Failed(message)
            }
        }
    }

    suspend fun completeAssistantTask(taskId: String) = withContext(Dispatchers.IO) {
        finishAssistantTask(taskId = taskId, status = "completed")
    }

    suspend fun finishAssistantTask(
        taskId: String,
        status: String,
        finalResponse: String? = null,
        cutoffReason: String? = null,
        metadata: JSONObject? = null
    ) = withContext(Dispatchers.IO) {
        authenticatedRequest(
            path = "/api/assistant/tasks/finish",
            method = "POST",
            body = JSONObject()
                .put("taskId", taskId)
                .put("status", status)
                .apply {
                    finalResponse?.let { put("finalResponse", it) }
                    cutoffReason?.let { put("cutoffReason", it) }
                    metadata?.let { put("metadata", it) }
                }
        )
        Unit
    }

    suspend fun recordModelUsage(
        taskId: String?,
        provider: String,
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        callType: String = "chat",
        cachedInputTokens: Int = 0,
        cacheCreationInputTokens: Int = 0,
        webSearchRequests: Int = 0,
        durationMs: Long? = null,
        toolsCalled: JSONArray? = null
    ): ModelUsageRecordResult = withContext(Dispatchers.IO) {
        val response = authenticatedRequest(
            path = "/api/assistant/model-usage",
            method = "POST",
            body = JSONObject()
                .put("taskId", taskId)
                .put("provider", provider)
                .put("model", model)
                .put("callType", callType)
                .put("inputTokens", inputTokens)
                .put("cachedInputTokens", cachedInputTokens)
                .put("cacheCreationInputTokens", cacheCreationInputTokens)
                .put("outputTokens", outputTokens)
                .put("webSearchRequests", webSearchRequests)
                .apply {
                    durationMs?.let { put("durationMs", it) }
                    toolsCalled?.let { put("toolsCalled", it) }
                }
        )
        ModelUsageRecordResult(
            modelCallId = response.optString("modelCallId").ifBlank { null },
            sequence = response.optInt("sequence"),
            estimatedCostUsd = response.optDouble("estimatedCostUsd", 0.0),
            creditsUsed = response.optNullableInt("creditsUsed")
                ?: AccountCreditPolicy.creditsForCost(response.optDouble("estimatedCostUsd", 0.0)),
            remainingCreditsThisMonth = response.optNullableInt("remainingCreditsThisMonth")
        )
    }

    suspend fun updateModelCallTools(
        taskId: String,
        modelCallId: String,
        toolsCalled: JSONArray
    ) = withContext(Dispatchers.IO) {
        authenticatedRequest(
            path = "/api/assistant/model-call-tools",
            method = "POST",
            body = JSONObject()
                .put("taskId", taskId)
                .put("modelCallId", modelCallId)
                .put("toolsCalled", toolsCalled)
        )
        Unit
    }

    private suspend fun authenticatedRequest(
        path: String,
        method: String = "GET",
        body: JSONObject? = null
    ): JSONObject {
        val baseUrl = BuildConfig.MARVIN_API_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            throw IllegalStateException(context.getString(R.string.account_backend_not_configured))
        }
        FirebaseAuthSupport.ensureInitialized(context)
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException(context.getString(R.string.account_sign_in_required))
        val token = user.getIdToken(false).awaitTask().token
            ?: throw IllegalStateException(context.getString(R.string.account_sign_in_required))

        val requestBuilder = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $token")

        if (method == "POST") {
            requestBuilder.post((body ?: JSONObject()).toString().toRequestBody(jsonMediaType))
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(responseBody) }.getOrDefault(JSONObject())
            if (!response.isSuccessful) {
                throw IllegalStateException(json.optString("error").ifBlank { "Request failed." })
            }
            return json
        }
    }
}

private fun JSONObject.optNullableInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}
