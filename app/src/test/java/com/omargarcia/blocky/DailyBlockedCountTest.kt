package com.omargarcia.blocky

import com.omargarcia.blocky.utils.DateGroupHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class DailyBlockedCountTest {

    private val locale = Locale.US

    data class MockBlockedCall(
        val phoneNumber: String,
        val timestamp: Long
    )

    @Test
    fun testDailyCountFiltersCallsFromPreviousDays() {
        val calendar = Calendar.getInstance(locale).apply {
            set(2026, Calendar.OCTOBER, 11, 14, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val currentNow = calendar.timeInMillis

        val helper = DateGroupHelper(
            todayText = "Today",
            yesterdayText = "Yesterday",
            locale = locale,
            nowMillis = currentNow
        )
        val startOfToday = helper.startOfTodayMillis

        val calls = listOf(
            MockBlockedCall("+521111111111", startOfToday - 3600_000L), // Yesterday (1 hr before midnight)
            MockBlockedCall("+522222222222", startOfToday + 60_000L),   // Today (1 min after midnight)
            MockBlockedCall("+523333333333", currentNow),               // Today (current time)
        )

        val todayCallsCount = calls.count { it.timestamp >= startOfToday }
        assertEquals(2, todayCallsCount)
    }

    @Test
    fun testMidnightRolloverResetsDailyCount() {
        // Day 1
        val calDay1 = Calendar.getInstance(locale).apply {
            set(2026, Calendar.OCTOBER, 11, 23, 50, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val timeDay1 = calDay1.timeInMillis
        val helperDay1 = DateGroupHelper("Today", "Yesterday", locale, timeDay1)

        val blockedCall = MockBlockedCall("+529999999999", timeDay1)

        val countDay1 = listOf(blockedCall).count { it.timestamp >= helperDay1.startOfTodayMillis }
        assertEquals(1, countDay1)

        // Day 2 (midnight crossed, 20 minutes later)
        val calDay2 = Calendar.getInstance(locale).apply {
            set(2026, Calendar.OCTOBER, 12, 0, 10, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val timeDay2 = calDay2.timeInMillis
        val helperDay2 = DateGroupHelper("Today", "Yesterday", locale, timeDay2)

        val countDay2 = listOf(blockedCall).count { it.timestamp >= helperDay2.startOfTodayMillis }
        assertEquals(0, countDay2)
    }
}
