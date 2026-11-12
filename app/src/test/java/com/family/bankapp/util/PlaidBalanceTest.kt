package com.family.bankapp.util

import com.family.bankapp.plaid.availableBalanceCents
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaidBalanceTest {

    @Test
    fun available_prefersAvailableOverCurrent() {
        // 2026-11-11, 360 Checking ···7098: current $3.41, pending $21.51,
        // available −$18.10. Free to spend must use available, not current.
        assertEquals(-1_810L, availableBalanceCents(current = 3.41, available = -18.10))
    }

    @Test
    fun available_fallsBackToCurrentWhenAvailableIsMissing() {
        assertEquals(47_202L, availableBalanceCents(current = 472.02, available = null))
    }

    @Test
    fun available_isZeroWhenNeitherIsReported() {
        assertEquals(0L, availableBalanceCents(current = null, available = null))
    }
}
