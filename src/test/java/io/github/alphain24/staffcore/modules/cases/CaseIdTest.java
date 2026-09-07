package io.github.alphain24.staffcore.modules.cases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alphabet is the whole design, so it is the thing worth pinning.
 * <p>
 * A case id gets read aloud over voice while two people look at different screens, typed into
 * chat from a screenshot, and pasted into Discord. An id containing both {@code O} and
 * {@code 0} is one somebody eventually mistypes into a lookup that reports "no such case" —
 * and they conclude the case is gone rather than that they typed it wrong. That failure is
 * quiet, blames the tool, and is entirely avoidable by choosing letters.
 */
class CaseIdTest {

	@Test
	@DisplayName("the alphabet contains nothing that can be misread aloud or on screen")
	void ambiguousCharactersAreAbsent() {
		for (char c : new char[] { 'I', 'L', 'O', 'U' }) {
			assertFalse(CaseId.ALPHABET.indexOf(c) >= 0,
					c + " is in the alphabet. I and L are unreadable next to 1, O next to 0, "
							+ "and U is excluded so no id can spell something staff would "
							+ "rather not read out.");
		}
		assertEquals(32, CaseId.ALPHABET.length(), "Crockford base32 is 32 symbols");
		assertEquals(CaseId.ALPHABET.length(),
				new HashSet<>(CaseId.ALPHABET.chars().boxed().toList()).size(),
				"a repeated symbol would quietly shrink the keyspace");
	}

	@Test
	@DisplayName("generated ids use only that alphabet")
	void generatedIdsAreInAlphabet() {
		for (int i = 0; i < 500; i++) {
			String id = CaseId.generate();
			assertEquals(CaseId.LENGTH, id.length());
			for (char c : id.toCharArray()) {
				assertTrue(CaseId.ALPHABET.indexOf(c) >= 0,
						"generated '" + c + "', which is not in the alphabet");
			}
		}
	}

	@Test
	@DisplayName("what somebody types back is folded to what was generated")
	void misreadingsAreForgiven() {
		// Somebody reading 0 aloud produces O at the other end about half the time. Rejecting
		// that is rejecting the person rather than the typo, and it is exactly why those four
		// letters are absent from the alphabet: it leaves them free to mean something here.
		assertEquals("01234567", CaseId.normalise("O1234567"), "O folds to 0");

		assertEquals(CaseId.normalise("ABCDEF01"), CaseId.normalise("abcdef01"),
				"case should not matter; nobody types an id in caps from a screenshot");
		assertEquals(CaseId.normalise("ABCDEF01"), CaseId.normalise(" ABCDEF01 "),
				"a pasted id brings whitespace with it");
		assertEquals(CaseId.normalise("ABCDEF01"), CaseId.normalise("ABCD-EF01"),
				"people insert a dash to make it readable");
	}

	@Test
	@DisplayName("each ambiguous letter folds to the character it looks like")
	void foldingIsSpecific() {
		assertEquals("00000000", CaseId.normalise("OOOOOOOO"));
		assertEquals("11111111", CaseId.normalise("IIIIIIII"));
		assertEquals("11111111", CaseId.normalise("LLLLLLLL"));
		assertEquals("VVVVVVVV", CaseId.normalise("UUUUUUUU"));
	}

	@Test
	@DisplayName("anything that is not one of ours is refused rather than guessed at")
	void rubbishIsRejected() {
		// Refusing loudly beats normalising into a valid-looking id for a case that does not
		// exist, which sends staff looking for a record rather than for their typo.
		assertNull(CaseId.normalise(null));
		assertNull(CaseId.normalise(""));
		assertNull(CaseId.normalise("SHORT"), "too short");
		assertNull(CaseId.normalise("WAYTOOLONGID"), "too long");
		assertNull(CaseId.normalise("ABCDEF!1"), "punctuation is not in the alphabet");
		assertFalse(CaseId.isValid("ABCDEF 1"));
	}

	@Test
	@DisplayName("a round trip through normalise leaves a generated id untouched")
	void generatedIdsAreAlreadyCanonical() {
		for (int i = 0; i < 200; i++) {
			String id = CaseId.generate();
			assertEquals(id, CaseId.normalise(id),
					"normalising a freshly generated id changed it, so the two disagree about "
							+ "what an id is");
		}
	}

	@Test
	@DisplayName("ids are spread across the keyspace rather than clustered")
	void idsDoNotCollideInPractice() {
		// Not a proof, and not trying to be: 40 bits means collisions are possible and the
		// store checks for them. What this rules out is a generator that is quietly returning
		// far fewer distinct values than the arithmetic assumes — a broken random source, or
		// an off-by-one that pins a character.
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 20_000; i++) seen.add(CaseId.generate());

		assertEquals(20_000, seen.size(),
				"generated " + (20_000 - seen.size()) + " duplicate(s) in twenty thousand, "
						+ "which at 40 bits should be vanishingly unlikely — the generator is "
						+ "not using the keyspace it thinks it is");
	}
}
