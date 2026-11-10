package com.booxai.helper.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun `patch version is newer`() {
        assertTrue(VersionComparator.isNewer("1.0.1", "1.0.0"))
    }

    @Test
    fun `minor version is newer`() {
        assertTrue(VersionComparator.isNewer("1.1", "1.0.0"))
        assertTrue(VersionComparator.isNewer("1.1.0", "1.0.9"))
    }

    @Test
    fun `missing segments count as zero`() {
        assertFalse(VersionComparator.isNewer("1.1.0", "1.1"))
        assertFalse(VersionComparator.isNewer("1.1", "1.1.0"))
        assertTrue(VersionComparator.isNewer("1.1.1", "1.1"))
    }

    @Test
    fun `v prefix is ignored`() {
        assertTrue(VersionComparator.isNewer("v1.2.0", "1.1.0"))
        assertTrue(VersionComparator.isNewer("V1.2", "v1.1"))
        assertFalse(VersionComparator.isNewer("v1.1.0", "1.1.0"))
    }

    @Test
    fun `prerelease suffix is ignored`() {
        assertFalse(VersionComparator.isNewer("1.0.0-debug", "1.0.0"))
        assertTrue(VersionComparator.isNewer("1.0.1-debug", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.1.0-beta.2", "1.1.0"))
    }

    @Test
    fun `invalid version is never newer`() {
        assertFalse(VersionComparator.isNewer("abc", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.0", "abc"))
        assertFalse(VersionComparator.isNewer("", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.x", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1..0", "1.0.0"))
    }

    @Test
    fun `same version is not newer`() {
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewer("v2", "2.0.0"))
    }
}
