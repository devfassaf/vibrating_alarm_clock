package com.faybish.vibealarm.domain.update

/**
 * Turns a GitHub release body into the few lines a person actually wants to read
 * before agreeing to an update.
 *
 * GitHub's auto-generated body is English PR titles, @handles and URLs — noise. So the
 * section under a "what's new" heading wins when the release script wrote one, and
 * otherwise the whole body is used with the markdown and plumbing stripped out.
 */
object ReleaseNotes {

    private val HEADING = Regex(
        """^\s*#{1,6}\s*(?:מה\s*חדש|what'?s\s*new)\s*\??\s*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val NEXT_SECTION = Regex("""^\s*#{1,6}\s+\S|^\s*---\s*$""", RegexOption.MULTILINE)

    private const val MAX_LINE = 160
    private const val MAX_LINES = 12
    private const val MAX_VERSIONS = 8

    /** Short plain-text lines. Never null, possibly empty. */
    fun extract(body: String?): List<String> {
        var text = body.orEmpty()
        HEADING.find(text)?.let { heading ->
            val after = text.substring(heading.range.last + 1)
            val next = NEXT_SECTION.find(after)
            text = if (next != null) after.substring(0, next.range.first) else after
        }

        return text.lineSequence()
            .map { line ->
                // A markdown heading is structure, not content: in the fallback path it
                // would otherwise show up as a bogus first bullet ("What's Changed").
                if (Regex("""^\s*#{1,6}\s+""").containsMatchIn(line)) "" else line
            }
            .map { it.denoise() }
            .filter { it.isNotBlank() && !Regex("""^[-=–—.:]+$""").matches(it) }
            .map { if (it.length > MAX_LINE) it.take(MAX_LINE - 1).trimEnd() + "…" else it }
            .take(MAX_LINES)
            .toList()
    }

    private fun String.denoise(): String = this
        .replace(Regex("""^\s*>+\s*"""), "")
        .replace(Regex("""^\s*[-*+]\s+"""), "")
        .replace(Regex("""\*\*(.*?)\*\*"""), "$1")
        .replace(Regex("""[*_`]"""), "")
        .replace(Regex("""\[([^]]+)]\([^)]*\)"""), "$1")
        .replace(Regex("""\bby\s+@[\w-]+\s+in\s+\S+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""@[\w-]+"""), "")
        .replace(Regex("""https?://\S+"""), "")
        .replace(Regex("""^\s*Full Changelog.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\(#\d+\)|#\d+"""), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()

    /**
     * Notes per version, newest first, for every version the device would gain by
     * updating — someone who skipped several releases sees everything they missed.
     */
    fun whatsNew(
        releases: List<RawRelease>,
        installedVersion: String?,
        max: Int = MAX_VERSIONS,
    ): List<VersionNotes> = releases
        .asSequence()
        .filter { !it.draft && !it.preRelease && it.tag.isNotBlank() }
        .mapNotNull { raw ->
            val version = Versions.clean(raw.tag).removePrefix("v")
            if (Versions.parse(version) == null) return@mapNotNull null
            if (!Versions.isNewer(version, installedVersion)) return@mapNotNull null
            VersionNotes(version = version, lines = extract(raw.body))
        }
        .sortedWith { a, b -> Versions.compare(b.version, a.version) }
        .take(max)
        .toList()
}

/** A release as GitHub reports it, before the updater reduces it. */
data class RawRelease(
    val tag: String,
    val body: String?,
    val draft: Boolean,
    val preRelease: Boolean,
    val assets: List<ReleaseAsset>,
)
