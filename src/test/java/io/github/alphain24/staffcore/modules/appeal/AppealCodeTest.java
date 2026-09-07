package io.github.alphain24.staffcore.modules.appeal;

import io.github.alphain24.staffcore.util.ShortId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The code a banned player transcribes from a photograph of their own disconnect screen.
 * <p>
 * That is the whole design constraint. It is read off a phone camera by somebody who has just
 * been removed from a server, typed into a Discord box, and it is their only route back. A
 * character that reads two ways, or a check that refuses a plausible transcription, costs that
 * person the appeal — and the server a player who instead comes back on an alt.
 */
class AppealCodeTest {

	@Test
	@DisplayName("nothing in a code can be transcribed two ways")
	void theAlphabetIsUnambiguous() {
		// I/L against 1, O against 0. Somebody reading these off a screen at an angle will
		// get them wrong, and the point of leaving them out is that being wrong is then
		// recoverable rather than final.
		for (int i = 0; i < 200; i++) {
			for (char c : AppealCode.generate().toCharArray()) {
				assertTrue(ShortId.ALPHABET.indexOf(c) >= 0,
						"generated the ambiguous character '" + c + "'");
			}
		}
	}

	@Test
	@DisplayName("a plausible mistranscription is understood, not refused")
	void foldsWhatAPersonWouldActuallyType() {
		String code = "0123456789AB";

		assertEquals(code, AppealCode.normalise("O123456789AB"), "O read for 0");
		assertEquals(code, AppealCode.normalise("0I23456789AB"), "I read for 1");
		assertEquals(code, AppealCode.normalise("0L23456789AB"), "L read for 1");
		assertEquals(code, AppealCode.normalise("0123456789ab"), "typed in lower case");
		assertEquals(code, AppealCode.normalise("0123-4567-89AB"), "typed with the groups in");
		assertEquals(code, AppealCode.normalise("  0123456789AB "), "pasted with whitespace");
	}

	@Test
	@DisplayName("something that is not a code is refused")
	void refusesWhatItCannotBe() {
		for (String junk : new String[] {null, "", "0123", "0123456789ABCD", "0123456789A!"}) {
			assertNull(AppealCode.normalise(junk),
					"\"" + junk + "\" was accepted as a code");
		}
	}

	@Test
	@DisplayName("codes are not guessable from each other")
	void notSequential() {
		// Holding one code must tell you nothing about anybody else's. Filing appeals as
		// other people is a small mischief that is very annoying to unpick.
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 2000; i++) {
			assertTrue(seen.add(AppealCode.generate()), "a code repeated within 2000 draws");
		}
		assertEquals(AppealCode.LENGTH, seen.iterator().next().length());
	}

	@Test
	@DisplayName("the printed form is grouped, and reads back as itself")
	void printedInGroupsOfFour() {
		String code = AppealCode.generate();
		String shown = AppealCode.display(code);

		assertEquals(2, shown.chars().filter(c -> c == '-').count(),
				"twelve characters in one run is where somebody loses their place: " + shown);
		assertNotNull(AppealCode.normalise(shown), "the printed form did not read back");
		assertEquals(code, AppealCode.normalise(shown));
	}
}
