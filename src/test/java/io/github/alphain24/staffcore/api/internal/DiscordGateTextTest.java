package io.github.alphain24.staffcore.api.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Text arriving from Discord, before it becomes a line in game or a note on somebody's record.
 */
class DiscordGateTextTest {

	@Test
	@DisplayName("formatting codes are removed, so a Discord line cannot dress up as the server")
	void noFormattingCodes() {
		String typed = (char) 0xA7 + "c" + (char) 0xA7 + "l[Server] restarting now";
		String clean = DiscordGate.cleanText(typed, 256);
		assertFalse(clean.indexOf((char) 0xA7) >= 0, clean);
		assertEquals("cl[Server] restarting now", clean);
	}

	@Test
	@DisplayName("one line, whatever was pasted")
	void oneLine() {
		assertEquals("first second third", DiscordGate.cleanText("first\nsecond\r\n\tthird", 256));
	}

	@Test
	@DisplayName("capped at the limit, and nothing but whitespace is nothing")
	void capped() {
		assertTrue(DiscordGate.cleanText("x".repeat(1000), 256).length() <= 256);
		assertEquals("", DiscordGate.cleanText("   \n  ", 256));
		assertEquals("", DiscordGate.cleanText(null, 256));
	}
}
