package com.pixelforge.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `parses and compares semantic versions accurately`() {
        val v110 = AppVersion.parse("v1.1.0")!!
        val v120 = AppVersion.parse("1.2.0")!!
        val v1100 = AppVersion.parse("V1.10.0")!!
        val v2 = AppVersion.parse("2.0")!!

        assertTrue(v120 > v110)
        assertTrue(v1100 > v120)
        assertTrue(v2 > v1100)
        assertEquals(v110, AppVersion.parse("1.1.0+build5"))
    }

    @Test
    fun `prereleases sort lower than stable releases and invalid inputs return null`() {
        val rc = AppVersion.parse("v1.2.0-rc1")!!
        val stable = AppVersion.parse("v1.2.0")!!

        assertTrue(stable > rc)
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("not-a-version"))
        assertNull(AppVersion.parse("1.-2.0"))
    }
}
