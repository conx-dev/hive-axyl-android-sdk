package com.hiveaxyl.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePkceTest {

    @Test
    fun createsRfc7636Challenge() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        val challenge = ApplePkce.createChallenge(verifier)

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }

    @Test
    fun createsValidVerifier() {
        val verifier = ApplePkce.createVerifier()

        assertEquals(64, verifier.length)
        assertTrue(verifier.matches(Regex("^[A-Za-z0-9._~-]+$")))
    }

    @Test
    fun clearsOnlyMatchingPendingVerifier() {
        val store = InMemoryAppleLoginPendingStore()
        store.save("verifier")

        store.clear("different")
        assertEquals("verifier", store.load())
        store.clear("verifier")

        assertNull(store.load())
    }
}
