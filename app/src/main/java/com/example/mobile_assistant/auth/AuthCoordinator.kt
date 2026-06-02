package com.example.mobile_assistant.auth

import android.content.Context
import com.example.mobile_assistant.FirebaseAuthSupport
import com.example.mobile_assistant.awaitTask
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser

object AuthCoordinator {

    fun currentUser(context: Context): FirebaseUser? {
        if (!FirebaseAuthSupport.ensureInitialized(context)) return null
        return FirebaseAuthSupport.auth(context).currentUser
    }

    fun signOut(context: Context) {
        if (!FirebaseAuthSupport.ensureInitialized(context)) return
        FirebaseAuthSupport.auth(context).signOut()
    }

    suspend fun signUpWithEmail(
        context: Context,
        email: String,
        password: String
    ): Result<FirebaseUser> = runCatching {
        require(FirebaseAuthSupport.ensureInitialized(context)) {
            "Firebase is not configured in this build."
        }
        val result = FirebaseAuthSupport.auth(context)
            .createUserWithEmailAndPassword(email.trim(), password)
            .awaitTask()
        result.user ?: error("Firebase returned no user after registration.")
    }

    suspend fun signInWithEmail(
        context: Context,
        email: String,
        password: String
    ): Result<FirebaseUser> = runCatching {
        require(FirebaseAuthSupport.ensureInitialized(context)) {
            "Firebase is not configured in this build."
        }
        val result = FirebaseAuthSupport.auth(context)
            .signInWithEmailAndPassword(email.trim(), password)
            .awaitTask()
        result.user ?: error("Firebase returned no user after sign-in.")
    }

    fun describeAuthError(error: Throwable): String = when (error) {
        is FirebaseAuthWeakPasswordException ->
            "That password is too weak. Use at least 6 characters."
        is FirebaseAuthInvalidCredentialsException ->
            "Email or password doesn't look right. Double-check and try again."
        is FirebaseAuthInvalidUserException ->
            "No account found for that email. Switch to Register to create one."
        is FirebaseAuthUserCollisionException ->
            "An account already exists for that email. Switch to Sign in."
        is FirebaseNetworkException ->
            "Network unavailable. Check your connection and try again."
        else -> error.message?.takeIf { it.isNotBlank() }
            ?: "Couldn't complete that. Please try again."
    }

    fun validateEmailPassword(
        email: String,
        password: String,
        confirmPassword: String? = null
    ): String? {
        val trimmed = email.trim()
        if (trimmed.isEmpty()) return "Enter your email address."
        if (!EMAIL_REGEX.matches(trimmed)) return "That email doesn't look valid."
        if (password.length < 6) return "Password must be at least 6 characters."
        if (confirmPassword != null && password != confirmPassword) {
            return "Passwords don't match."
        }
        return null
    }

    private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
}
