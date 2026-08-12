package com.faybish.vibealarm.domain.update

/**
 * The message someone sends a friend to pass the app on. Pure, so its wording and — more
 * importantly — its links are pinned by tests.
 *
 * Two deliberate choices, both learned in the sibling project:
 *
 * The explanation page leads and the APK follows. A bare `.apk` link arriving in WhatsApp
 * has no context and reads as something you should not tap — and whoever does tap it walks
 * straight into Chrome's "this file may harm your device", the unknown-sources prompt and
 * a Play Protect scan, with nobody having warned them. The page says what the app is and
 * walks through those warnings.
 *
 * The direct link is the *latest-release redirect*, never a versioned asset URL. A shared
 * message outlives the release it was written in — it sits in a thread for months — so a
 * pinned URL would keep handing out a stale build, and 404 outright once that release is
 * deleted.
 */
object ShareMessage {

    /**
     * @param version the installed version, shown so the recipient knows what they are
     *   getting. Omitted when unknown rather than printed as a blank.
     */
    fun build(version: String?, siteUrl: String, apkUrl: String): String {
        val suffix = version?.takeIf { it.isNotBlank() }?.let { " (גירסה $it)" }.orEmpty()
        return listOf(
            "היי! מוזמנים להתקין את \"שעון מעורר רוטט\"$suffix — שעון מעורר לאנדרואיד " +
                "שמעיר ברטט בתבנית שאתם בונים בעצמכם, נעצר מעצמו וחוזר לנודניק, בלי להעיר " +
                "אף אחד אחר ובלי לגעת בטלפון.",
            "",
            "📖 מה זה ואיך מתקינים: $siteUrl",
            "⬇️ הורדה ישירה לאנדרואיד: $apkUrl",
            "",
            "בהתקנה אנדרואיד מציגה כמה אזהרות — הן רגילות לכל אפליקציה שלא הותקנה מחנות " +
                "Play. בדף ההסבר יש צעד-אחר-צעד. אחרי ההתקנה האפליקציה מתעדכנת מעצמה.",
        ).joinToString("\n")
    }
}
