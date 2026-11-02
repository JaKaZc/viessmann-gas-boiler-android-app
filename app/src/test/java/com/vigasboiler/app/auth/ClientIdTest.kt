package com.vigasboiler.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientIdTest {

    @Test
    fun `whitespace and line breaks from copying are removed`() {
        assertEquals("0123456789abcdef0123456789abcdef", ClientId.normalize("  0123456789abcdef\n0123456789abcdef \t"))
    }

    @Test
    fun `portal client ids are valid`() {
        assertTrue(ClientId.isValid("0123456789abcdef0123456789abcdef"))
        assertTrue(ClientId.isValid("my-client_id.0123"))
    }

    @Test
    fun `short input, urls and text are not client ids`() {
        assertFalse(ClientId.isValid(""))
        assertFalse(ClientId.isValid("abc123"))
        assertFalse(ClientId.isValid("https://developer.viessmann-climatesolutions.com"))
        assertFalse(ClientId.isValid("com.vigasboiler.app://oauth2redirect"))
        assertFalse(ClientId.isValid("x".repeat(129)))
    }
}
