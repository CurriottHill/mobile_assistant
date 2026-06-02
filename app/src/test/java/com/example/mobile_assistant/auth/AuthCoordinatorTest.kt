package com.example.mobile_assistant.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM coverage of the parts of [AuthCoordinator] that don't require Android or Firebase
 * runtime classes. The Firebase exception subclasses can't be instantiated under the unit-test
 * classpath (their constructors call android.text.TextUtils, which throws "not mocked"), so the
 * is-checks in [AuthCoordinator.describeAuthError] are validated end-to-end during onboarding QA.
 */
class AuthCoordinatorTest {

    @Test
    fun describeAuthError_fallsBackToMessage() {
        val message = AuthCoordinator.describeAuthError(IllegalStateException("boom"))
        assertEquals("boom", message)
    }

    @Test
    fun describeAuthError_blankMessage_fallsBackToGeneric() {
        val message = AuthCoordinator.describeAuthError(IllegalStateException(""))
        assertEquals("Couldn't complete that. Please try again.", message)
    }

    @Test
    fun describeAuthError_nullMessage_fallsBackToGeneric() {
        val message = AuthCoordinator.describeAuthError(RuntimeException())
        assertNotNull(message)
        assert(message.isNotBlank())
    }

    @Test
    fun validateEmailPassword_rejectsBlankEmail() {
        val error = AuthCoordinator.validateEmailPassword("", "abcdef")
        assertEquals("Enter your email address.", error)
    }

    @Test
    fun validateEmailPassword_rejectsInvalidEmail() {
        val error = AuthCoordinator.validateEmailPassword("not-an-email", "abcdef")
        assertEquals("That email doesn't look valid.", error)
    }

    @Test
    fun validateEmailPassword_rejectsShortPassword() {
        val error = AuthCoordinator.validateEmailPassword("a@b.co", "abc")
        assertEquals("Password must be at least 6 characters.", error)
    }

    @Test
    fun validateEmailPassword_rejectsMismatchedConfirm() {
        val error = AuthCoordinator.validateEmailPassword("a@b.co", "abcdef", "abcdeg")
        assertEquals("Passwords don't match.", error)
    }

    @Test
    fun validateEmailPassword_acceptsValid() {
        val error = AuthCoordinator.validateEmailPassword("a@b.co", "abcdef", "abcdef")
        assertNull(error)
    }

    @Test
    fun validateEmailPassword_acceptsValidWithoutConfirm() {
        val error = AuthCoordinator.validateEmailPassword("a@b.co", "abcdef")
        assertNull(error)
    }

    @Test
    fun validateEmailPassword_trimsEmailWhitespace() {
        val error = AuthCoordinator.validateEmailPassword("  a@b.co  ", "abcdef")
        assertNull(error)
    }
}
