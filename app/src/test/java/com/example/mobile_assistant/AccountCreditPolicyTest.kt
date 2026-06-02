package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountCreditPolicyTest {
    @Test
    fun monthlyCreditsForPlan_usesConfiguredPlanAllowances() {
        assertEquals(50, AccountCreditPolicy.monthlyCreditsForPlan("free"))
        assertEquals(1000, AccountCreditPolicy.monthlyCreditsForPlan("plus"))
        assertEquals(2200, AccountCreditPolicy.monthlyCreditsForPlan("pro"))
        assertEquals(2200, AccountCreditPolicy.monthlyCreditsForPlan(" Pro "))
    }

    @Test
    fun monthlyCreditsForPlan_defaultsUnknownPlansToFreeAllowance() {
        assertEquals(50, AccountCreditPolicy.monthlyCreditsForPlan(""))
        assertEquals(50, AccountCreditPolicy.monthlyCreditsForPlan("unknown"))
    }

    @Test
    fun creditsForCost_roundsUpToWholeCredits() {
        assertEquals(0, AccountCreditPolicy.creditsForCost(0.0))
        assertEquals(1, AccountCreditPolicy.creditsForCost(0.001))
        assertEquals(1, AccountCreditPolicy.creditsForCost(0.01))
        assertEquals(2, AccountCreditPolicy.creditsForCost(0.011))
    }
}
