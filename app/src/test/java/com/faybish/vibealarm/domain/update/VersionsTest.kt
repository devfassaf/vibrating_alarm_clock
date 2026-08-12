package com.faybish.vibealarm.domain.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VersionsTest {

    @Test
    fun `parses plain and v-prefixed versions`() {
        assertThat(Versions.parse("1.2.3")).isEqualTo(Triple(1, 2, 3))
        assertThat(Versions.parse("v1.2.3")).isEqualTo(Triple(1, 2, 3))
        assertThat(Versions.parse("v10.0.27")).isEqualTo(Triple(10, 0, 27))
    }

    @Test
    fun `rejects things that are not versions`() {
        listOf(null, "", "   ", "latest", "v", "1.2", "abc.def.ghi").forEach {
            assertThat(Versions.parse(it)).isNull()
        }
    }

    /**
     * A tag typed or pasted in a Hebrew-language field picks up an invisible
     * right-to-left mark. `trim()` does not remove it, and its presence makes the
     * version unparseable — which the updater reads as "no update", not as an error.
     */
    @Test
    fun `invisible bidi and zero-width marks are stripped before parsing`() {
        val marks = listOf("‏", "‎", "‫", "⁦", "​", "﻿")
        marks.forEach { mark ->
            assertThat(Versions.parse("$mark v1.2.3 ")).isEqualTo(Triple(1, 2, 3))
            assertThat(Versions.parse("v1.2.3$mark")).isEqualTo(Triple(1, 2, 3))
            assertThat(Versions.isDeliverable("v1.2.3$mark")).isTrue()
        }
    }

    @Test
    fun `compares by major then minor then patch`() {
        assertThat(Versions.isNewer("1.0.1", "1.0.0")).isTrue()
        assertThat(Versions.isNewer("1.1.0", "1.0.9")).isTrue()
        assertThat(Versions.isNewer("2.0.0", "1.9.9")).isTrue()
        assertThat(Versions.isNewer("1.0.0", "1.0.0")).isFalse()
        assertThat(Versions.isNewer("1.0.0", "1.0.1")).isFalse()
        // Numeric, not lexicographic: "10" is larger than "9".
        assertThat(Versions.isNewer("1.0.10", "1.0.9")).isTrue()
    }

    @Test
    fun `unparseable input is never newer, so a bad tag cannot trigger a downgrade`() {
        assertThat(Versions.isNewer("garbage", "1.0.0")).isFalse()
        assertThat(Versions.isNewer("1.0.1", null)).isFalse()
        assertThat(Versions.compare("garbage", "1.0.0")).isEqualTo(0)
    }

    /**
     * The four-component trap: `1.0.0.1` parses to `1.0.0`, so it compares EQUAL to an
     * installed `1.0.0` and every device answers "up to date". Publishing one wastes a
     * whole release, so the release script refuses it — using exactly this predicate.
     */
    @Test
    fun `a four-component version is not deliverable`() {
        assertThat(Versions.isDeliverable("1.0.0.1")).isFalse()
        assertThat(Versions.isDeliverable("v1.0.26.1")).isFalse()
        // …and here is why it must be refused rather than merely compared:
        assertThat(Versions.isNewer("1.0.0.1", "1.0.0")).isFalse()
    }

    @Test
    fun `deliverable means exactly three components`() {
        assertThat(Versions.isDeliverable("1.0.0")).isTrue()
        assertThat(Versions.isDeliverable("v1.0.0")).isTrue()
        listOf("1.0", "1", "1.0.0-beta", "1.0.0 ", null, "").forEach {
            if (it == "1.0.0 ") return@forEach // trailing space is trimmed, still valid
            assertThat(Versions.isDeliverable(it)).isFalse()
        }
        assertThat(Versions.isDeliverable("1.0.0 ")).isTrue()
    }

    @Test
    fun `versionCode increases with the version and matches the release script formula`() {
        assertThat(Versions.versionCode("1.0.0")).isEqualTo(10_000)
        assertThat(Versions.versionCode("1.0.1")).isEqualTo(10_001)
        assertThat(Versions.versionCode("1.2.3")).isEqualTo(10_203)
        assertThat(Versions.versionCode("2.0.0")).isEqualTo(20_000)

        // Monotonic across every ordered pair — a versionCode that fails to increase
        // blocks the install with no useful message.
        val ordered = listOf("1.0.0", "1.0.1", "1.0.99", "1.1.0", "1.99.99", "2.0.0", "10.0.0")
        val codes = ordered.map { Versions.versionCode(it)!! }
        assertThat(codes).isInStrictOrder()
    }

    @Test
    fun `versionCode refuses components that would break monotonicity`() {
        assertThat(Versions.versionCode("1.100.0")).isNull()
        assertThat(Versions.versionCode("1.0.100")).isNull()
        assertThat(Versions.versionCode("nonsense")).isNull()
    }
}
