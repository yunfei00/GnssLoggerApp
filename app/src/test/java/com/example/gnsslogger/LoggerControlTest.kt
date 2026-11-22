package com.example.gnsslogger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class LoggerControlTest {
    @Test
    fun acceptsOnlyCanonicalRunIds() {
        assertTrue(validId(UUID.randomUUID().toString()))
        assertFalse(validId("1-1-1-1-1"))
        assertFalse(validId("../../gnss"))
        assertFalse(validId("00000000-0000-0000-0000-000000000000/satellites.csv"))
    }
}
