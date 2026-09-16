package io.github.alphain24.staffcore.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleTextAsciiTest {

	@Test
	@DisplayName("alert typography becomes its ASCII look-alike, and ASCII text is returned as it was")
	void ascii() {
		assertEquals("Steve - uncovered 6 veins... (99%) - case ABCD", ConsoleText.ascii(
				"Steve \u2014 uncovered 6 veins\u2026 (99%) \u2014 case ABCD"));
		assertEquals("3x diamond -> vault - 'quoted' \"too\"", ConsoleText.ascii(
				"3\u00D7 diamond \u2192 vault \u00B7 \u2018quoted\u2019 \u201Ctoo\u201D"));
		assertEquals("a ? b ? c", ConsoleText.ascii("a \uD83D\uDD28 b \u00E9 c"));
		assertEquals("one two", ConsoleText.ascii("one\ntwo"));
		assertEquals("", ConsoleText.ascii(null));

		String plain = "Console banned Steve: griefing";
		assertSame(plain, ConsoleText.ascii(plain));
		assertTrue(ConsoleText.ascii("x \u2014 y").chars().allMatch(c -> c >= 32 && c <= 126));
	}
}
