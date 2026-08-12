package com.faybish.vibealarm.domain.update

/**
 * Version handling for the in-app updater. Pure, so every rule below is unit-tested.
 *
 * The whole updater rests on comparing two strings, and the failure mode when that
 * comparison is subtly wrong is the worst kind: the release exists, the download page
 * serves it, and every installed app cheerfully answers "you are up to date".
 */
object Versions {

    /**
     * Invisible characters that a tag picks up when it is typed or pasted in a
     * Hebrew-language context: bidi marks, zero-width joiners, BOM. `trim()` does not
     * remove any of them, and a single one makes the regex below fail — which reads as
     * "no update available" rather than as an error.
     */
    private val INVISIBLE = Regex("[‎‏‪-‮⁦-⁩​-‍﻿]")

    private val THREE_PART = Regex("""^v?(\d+)\.(\d+)\.(\d+)""")
    private val EXACTLY_THREE_PART = Regex("""^v?\d+\.\d+\.\d+$""")

    fun clean(raw: String?): String = INVISIBLE.replace(raw.orEmpty(), "").trim()

    /** @return major/minor/patch, or null when [raw] is not a version at all. */
    fun parse(raw: String?): Triple<Int, Int, Int>? {
        val m = THREE_PART.find(clean(raw)) ?: return null
        val (a, b, c) = m.destructured
        return Triple(a.toIntOrNull() ?: return null, b.toIntOrNull() ?: return null, c.toIntOrNull() ?: return null)
    }

    /**
     * Can a device ever be *offered* this version?
     *
     * [parse] reads exactly three components, so a fourth is not "smaller" — it is
     * invisible: `1.0.0.1` parses to `1.0.0` and compares EQUAL to an installed
     * `1.0.0`, so every app answers "up to date" and the release reaches nobody.
     * Widening the parser would not help, because the apps already in the field
     * compare by three components. The only repair is refusing to publish such a
     * version, which is why the release script checks this before it builds.
     */
    fun isDeliverable(raw: String?): Boolean = EXACTLY_THREE_PART.matches(clean(raw))

    /** Standard comparison, except that unparseable input compares equal — never newer. */
    fun compare(a: String?, b: String?): Int {
        val left = parse(a) ?: return 0
        val right = parse(b) ?: return 0
        return compareValuesBy(left, right, { it.first }, { it.second }, { it.third })
    }

    fun isNewer(remote: String?, local: String?): Boolean = compare(remote, local) > 0

    /**
     * Android's own version counter, derived from the name so the two can never drift.
     * Monotonic as long as minor and patch stay below 100 — enforced where it is
     * generated, since a versionCode that fails to increase blocks the install.
     */
    fun versionCode(raw: String?): Int? {
        val (major, minor, patch) = parse(raw) ?: return null
        if (minor >= 100 || patch >= 100) return null
        return major * 10_000 + minor * 100 + patch
    }
}
