package com.noLate.eta.simulator

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrivateFixtureJwtSecretTest {
    @Test
    fun `each new private H2 server has an independent signing key`() {
        val first = freshPrivateFixtureJwtSecret()
        val second = freshPrivateFixtureJwtSecret()
        assertTrue(first.startsWith("isolated-fixture-only-") && first.length >= 64)
        assertNotEquals(first, second)
    }
}
