package com.faybish.vibealarm.domain

/**
 * Naming a copy.
 *
 * Two rows showing the same time with the same label are two rows nobody can tell apart, and
 * the one you switch off in a hurry at 6am should be the one you meant. The suffix comes from
 * resources so it reads in the app's language, and it is added once: duplicating a copy of a
 * copy gives a longer chain of nothing useful.
 */
fun duplicateLabel(label: String, copySuffix: String): String = when {
    label.isBlank() -> ""
    label.trimEnd().endsWith(copySuffix) -> label
    else -> "${label.trimEnd()} $copySuffix"
}
