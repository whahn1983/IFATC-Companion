package com.h3consultingpartners.ifatccompanion.core.review

import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryKeyValueStore
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rating prompt.
 *
 * Every gate here exists to stop the app interrupting a pilot at a bad moment, and none of
 * them can be observed on a device — the store decides silently whether a prompt appears, so
 * a request that was correctly throttled and one that was never made look identical. The
 * arithmetic is the only place this is checkable at all.
 */
class ReviewRequestManagerTest {

    private val day = 86_400_000L
    private val clock = MutableClock(1_700_000_000_000L)
    private val store = InMemoryKeyValueStore()

    private fun manager() = ReviewRequestManager(store, clock).also { it.noteAppStarted() }

    private fun ReviewRequestManager.completeFlights(n: Int) = repeat(n) { recordFlightCompleted() }

    @Test
    fun `a brand-new install is never asked`() {
        // Three completed flights on day zero means a restore from backup, not three days of
        // flying. Both stores say not to ask on first launch.
        val review = manager()
        review.completeFlights(5)
        assertFalse(review.isEligible(), "asked on the day of install")
    }

    @Test
    fun `a pilot who has not finished a flight is never asked`() {
        val review = manager()
        clock.advance(30 * day)
        review.completeFlights(ReviewRequestManager.MINIMUM_COMPLETED_FLIGHTS - 1)
        assertFalse(review.isEligible(), "asked someone with nothing to rate")
    }

    @Test
    fun `an engaged pilot on an established install is asked`() {
        val review = manager()
        clock.advance(30 * day)
        review.completeFlights(ReviewRequestManager.MINIMUM_COMPLETED_FLIGHTS)
        assertTrue(review.isEligible())
        assertTrue(review.requestReviewIfAppropriate(ReviewRequestManager.Trigger.AFTER_FLIGHT_COMPLETE))
    }

    @Test
    fun `asking twice in a row asks once`() {
        // The second eligible moment arrives seconds later — parked, then the next session's
        // first call. Without the spacing rule the pilot is asked twice in a minute.
        val review = manager()
        clock.advance(30 * day)
        review.completeFlights(3)

        assertTrue(review.requestReviewIfAppropriate(ReviewRequestManager.Trigger.AFTER_FLIGHT_COMPLETE))
        assertFalse(review.requestReviewIfAppropriate(ReviewRequestManager.Trigger.BEFORE_FIRST_CALL))
    }

    @Test
    fun `the next ask waits out the spacing rule`() {
        val review = manager()
        clock.advance(30 * day)
        review.completeFlights(3)
        assertTrue(review.requestReviewIfAppropriate(ReviewRequestManager.Trigger.AFTER_FLIGHT_COMPLETE))

        clock.advance(119 * day)
        assertFalse(review.isEligible(), "asked a day early")
        clock.advance(2 * day)
        assertTrue(review.isEligible())
    }

    @Test
    fun `three prompts in a rolling year is the limit`() {
        val review = manager()
        clock.advance(30 * day)
        review.completeFlights(3)

        repeat(ReviewRequestManager.MAXIMUM_PROMPTS_PER_YEAR) {
            assertTrue(
                review.requestReviewIfAppropriate(ReviewRequestManager.Trigger.AFTER_FLIGHT_COMPLETE),
                "prompt $it was refused before the cap",
            )
            clock.advance(121 * day)
        }
        // A fourth eligible moment, correctly spaced, still inside the year.
        assertFalse(review.isEligible(), "overshot the yearly cap")
    }

    @Test
    fun `the yearly cap rolls rather than resetting on a date`() {
        // A fixed reset would let a pilot flying either side of it be asked twice as often
        // as the rule allows. The oldest prompt has to age out on its own anniversary.
        val review = manager()
        clock.advance(30 * day)
        review.completeFlights(3)
        repeat(3) {
            review.requestReviewIfAppropriate(ReviewRequestManager.Trigger.AFTER_FLIGHT_COMPLETE)
            clock.advance(121 * day)
        }
        assertFalse(review.isEligible())

        clock.advance(366 * day)
        assertTrue(review.isEligible(), "the oldest prompt never aged out")
    }

    @Test
    fun `the gate survives a relaunch`() {
        // Every threshold is measured in days and months, so a manager that forgot its
        // history on each launch would ask at every eligible moment forever.
        val first = manager()
        clock.advance(30 * day)
        first.completeFlights(3)
        assertTrue(first.requestReviewIfAppropriate(ReviewRequestManager.Trigger.AFTER_FLIGHT_COMPLETE))

        val relaunched = ReviewRequestManager(store, clock).also { it.noteAppStarted() }
        assertEquals(3, relaunched.completedFlights, "the engagement count was lost")
        assertFalse(relaunched.isEligible(), "the spacing rule was lost")
    }

    @Test
    fun `noting the app started twice does not move the install date`() {
        val review = manager()
        clock.advance(10 * day)
        review.noteAppStarted()
        review.completeFlights(3)
        // Had the second call re-stamped the date, the install would look 0 days old and the
        // pilot would never become eligible.
        clock.advance(1 * day)
        assertTrue(review.isEligible())
    }
}
