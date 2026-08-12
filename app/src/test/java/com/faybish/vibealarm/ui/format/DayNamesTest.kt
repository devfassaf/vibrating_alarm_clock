package com.faybish.vibealarm.ui.format

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.domain.ScheduleSummarizer
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.DayOfWeek
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The day picker showed "יו" seven times and no Saturday at all, which is how a Shabbat
 * alarm clock shipped without a way to select Shabbat. The labels come from our own
 * strings now, and this is the test that would have caught it: seven days, seven distinct
 * names, in both languages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayNamesTest {

    private fun localized(language: String): Context {
        val application: Application = ApplicationProvider.getApplicationContext()
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return application.createConfigurationContext(configuration)
    }

    @Test
    fun `every day has its own name in Hebrew`() {
        val context = localized("iw")
        val names = DayOfWeek.entries.associateWith { dayName(context, it) }

        assertThat(names.values.toSet()).hasSize(7)
        names.forEach { (day, name) ->
            assertWithMessage("$day").that(name).isNotEmpty()
        }
        assertThat(names[DayOfWeek.SUNDAY]).isEqualTo("ראשון")
        assertThat(names[DayOfWeek.SATURDAY]).isEqualTo("שבת")
    }

    @Test
    fun `every day has its own name in English`() {
        val context = localized("en")
        val names = DayOfWeek.entries.map { dayName(context, it) }

        assertThat(names.toSet()).hasSize(7)
        assertThat(names.first()).isEqualTo("Monday")
        assertThat(names.last()).isEqualTo("Sunday")
    }

    /** Two letters of the platform's Hebrew abbreviation is what broke: "יום א׳" → "יו". */
    @Test
    fun `the names are not the platform abbreviations that collided`() {
        val context = localized("iw")
        DayOfWeek.entries.forEach { day ->
            assertWithMessage("$day").that(dayName(context, day)).doesNotContain("יום")
        }
    }

    @Test
    fun `the picker offers all seven days, Saturday included`() {
        val week = ScheduleSummarizer.weekOrder(DayOfWeek.SUNDAY)

        assertThat(week).hasSize(7)
        assertThat(week.first()).isEqualTo(DayOfWeek.SUNDAY)
        assertThat(week).contains(DayOfWeek.SATURDAY)
    }
}
