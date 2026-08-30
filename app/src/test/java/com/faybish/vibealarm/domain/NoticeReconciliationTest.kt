package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The reverse reconciliation: a posted notice notification with no unread row behind it is
 * a launcher badge nothing in the app explains or can clear — the bug this exists to heal.
 */
class NoticeReconciliationTest {

    private fun orphans(posted: List<Int>, unread: Set<Long>) =
        NoticeReconciliation.orphanedNoticeIds(posted, unread)

    @Test
    fun `a notice with no unread row behind it is an orphan`() {
        assertThat(orphans(posted = listOf(400_010), unread = emptySet()))
            .containsExactly(400_010)
        assertThat(orphans(posted = listOf(300_007), unread = emptySet()))
            .containsExactly(300_007)
    }

    @Test
    fun `a notice whose alarm still has an unread row is kept`() {
        assertThat(orphans(posted = listOf(400_010, 300_010), unread = setOf(10L))).isEmpty()
    }

    /** Both kinds share the alarm's rows: one unread row keeps both faces alive. */
    @Test
    fun `only the alarm without rows is retired`() {
        val posted = listOf(400_003, 400_007, 300_007)

        assertThat(orphans(posted, unread = setOf(7L))).containsExactly(400_003)
    }

    /**
     * The firing and snoozed notifications live in other id ranges and are owned by the
     * live ring — cancelling them from here would silence an alarm in progress.
     */
    @Test
    fun `live-ring notifications are never touched`() {
        val posted = listOf(100_010, 200_010)

        assertThat(orphans(posted, unread = emptySet())).isEmpty()
    }

    @Test
    fun `nothing posted, nothing to do`() {
        assertThat(orphans(emptyList(), unread = setOf(1L))).isEmpty()
    }
}
