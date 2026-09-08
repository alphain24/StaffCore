package io.github.alphain24.staffcore.util;

import io.github.alphain24.staffcore.config.StaffConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Times printed for people, in the two shapes they need.
 * <p>
 * The failures here are quiet ones. A timestamp with no zone on it is read as local by whoever
 * is reading it, which is how two staff members end up disagreeing about whether an incident
 * happened during someone's shift. A duration rounded the wrong way makes a ban screen say six
 * days when the seventh is still to come, which reads as the server lying.
 */
class TimeFormatTest {

	@AfterEach
	void resetZone() {
		StaffConfig.get().displayTimezone = "UTC";
	}

	@Test
	@DisplayName("every printed time names its zone")
	void nothingIsAmbiguous() {
		// A timestamp whose meaning depends on knowing the config is a timestamp that will be
		// misread by the one person who did not set it.
		String utc = TimeFormat.stamp(1_757_260_000_000L);
		assertTrue(utc.endsWith(" UTC"), "an unzoned stamp is read as local by the reader: " + utc);

		StaffConfig.get().displayTimezone = "Asia/Kolkata";
		String local = TimeFormat.stamp(1_757_260_000_000L);
		assertTrue(local.contains("GMT+05:30"), "the offset is missing from " + local);
		assertFalse(local.equals(utc), "the display zone changed nothing");
	}

	@Test
	@DisplayName("log times stay UTC whatever the display zone says")
	void logsDoNotFollowTheDisplayZone() {
		// A log is read later, elsewhere, by somebody who did not write it. Rendering it in
		// the writer's local time puts a silent offset in the record.
		String before = TimeFormat.utcStamp(1_757_260_000_000L);
		StaffConfig.get().displayTimezone = "America/New_York";

		assertEquals(before, TimeFormat.utcStamp(1_757_260_000_000L),
				"the log stamp moved when the display preference changed");
		assertTrue(before.endsWith(" UTC"));
	}

	@Test
	@DisplayName("an unusable zone falls back to UTC rather than failing")
	void abadZoneCostsAPreferenceNotTheRecord() {
		assertNull(TimeFormat.zoneOrNull("Middle/Earth"), "that is not a zone");
		assertNull(TimeFormat.zoneOrNull("GMT+25"), "nor is that");
		assertNotNull(TimeFormat.zoneOrNull("Europe/London"));
		assertEquals(ZoneId.of("UTC"), TimeFormat.zoneOrNull(""), "blank means UTC, not broken");

		StaffConfig.get().displayTimezone = "Middle/Earth";
		assertEquals(ZoneId.of("UTC"), TimeFormat.zone(),
				"a typo in the config should not take the ability to read a ban record with it");
		assertFalse(StaffConfig.validate().isEmpty(), "and it should be reported, not swallowed");
	}

	@Test
	@DisplayName("full() carries both halves, because each answers a different question")
	void relativeAndAbsoluteTogether() {
		long threeHoursAgo = System.currentTimeMillis() - 3 * 3_600_000L;
		String full = TimeFormat.full(threeHoursAgo);

		assertTrue(full.startsWith("3 hours ago"), "the relative half is wrong: " + full);
		assertTrue(full.contains("(") && full.endsWith("UTC)"),
				"the absolute half is missing from " + full);
	}

	@Test
	@DisplayName("a length keeps its remainder rather than rounding to a lie")
	void lengthsDoNotDistort() {
		// A single rounded unit has to choose between understating and overstating, and both
		// are wrong in a way a player notices. Ten days is not "2 weeks" and it is not "1
		// week" — it is ten days, and a ban screen that says otherwise is a screen the player
		// can catch out.
		assertEquals("10 days", TimeFormat.length(10 * 86_400_000L));
		assertEquals("6 days 20 hours", TimeFormat.length(6 * 86_400_000L + 20 * 3_600_000L));
		assertEquals("7 days", TimeFormat.length(7 * 86_400_000L), "an exact length stays exact");

		assertEquals("1 hour", TimeFormat.length(3_600_000L), "one of a thing is singular");
		assertEquals("30 minutes", TimeFormat.length(30 * 60_000L));
		assertEquals("45 seconds", TimeFormat.length(45_000L));
		assertEquals("0 seconds", TimeFormat.length(-5_000L), "a negative length is not a crash");
	}

	@Test
	@DisplayName("words() handles both directions, so an expiry reads as an expiry")
	void futureTimesReadAsFuture() {
		assertTrue(TimeFormat.words(System.currentTimeMillis() + 2 * 86_400_000L)
				.startsWith("in 2 days"));
		assertTrue(TimeFormat.words(System.currentTimeMillis() - 2 * 86_400_000L)
				.endsWith(" ago"));
		assertEquals("just now", TimeFormat.words(System.currentTimeMillis()));
	}
}
