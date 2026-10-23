package home.iptv

import home.iptv.feature.guide.guideTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideTimeTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun date(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()

    @Test fun timelineAddsRussianDateOnlyOutsideToday() {
        val now = date("2026-10-23T12:00:00+03:00")
        assertEquals("12:00", guideTime(now, now, zone))
        assertEquals("сб, 24 окт, 12:00", guideTime(date("2026-10-24T12:00:00+03:00"), now, zone))
        assertEquals("чт, 22 окт, 12:00", guideTime(date("2026-10-22T12:00:00+03:00"), now, zone))
    }

    @Test fun midnightAndYearBoundaryUseTheLocalCalendarDay() {
        val now = date("2026-12-31T23:59:00+03:00")
        assertEquals("23:59", guideTime(now, now, zone))
        assertEquals("пт, 1 янв, 00:00", guideTime(date("2027-01-01T00:00:00+03:00"), now, zone))
        assertEquals("20:59", guideTime(now, now, ZoneId.of("UTC")))
    }
}
