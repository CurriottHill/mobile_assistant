package com.example.mobile_assistant

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object FirebaseAuthSupport {
    fun isConfigured(): Boolean {
        return BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()
    }

    fun isGoogleSignInConfigured(): Boolean {
        return isConfigured() && BuildConfig.FIREBASE_WEB_CLIENT_ID.isNotBlank()
    }

    fun ensureInitialized(context: Context): Boolean {
        if (!isConfigured()) return false
        if (FirebaseApp.getApps(context).isNotEmpty()) return true

        val options = FirebaseOptions.Builder()
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .build()
        FirebaseApp.initializeApp(context, options)
        return true
    }

    fun auth(context: Context): FirebaseAuth {
        ensureInitialized(context)
        return FirebaseAuth.getInstance()
    }

    fun googleSignInClient(context: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(BuildConfig.FIREBASE_WEB_CLIENT_ID)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    suspend fun signInWithGoogleIdToken(context: Context, idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth(context).signInWithCredential(credential).awaitTask()
    }

    fun describeGoogleSignInError(error: Throwable): String {
        val statusCode = (error as? ApiException)?.statusCode
        return when (statusCode) {
            CommonStatusCodes.DEVELOPER_ERROR -> {
                "Google sign-in is misconfigured. In Firebase, add Android app package " +
                    "com.assistant with this debug SHA-1, then use the Web client ID " +
                    "in FIREBASE_WEB_CLIENT_ID and rebuild."
            }
            CommonStatusCodes.CANCELED -> "Google sign-in was cancelled."
            CommonStatusCodes.NETWORK_ERROR -> "Google sign-in failed because the network is unavailable."
            null -> error.message ?: "Could not sign in with Google."
            else -> error.message ?: "Google sign-in failed with status $statusCode."
        }
    }
}

suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
