package io.github.alphain24.staffcore.modules.accountability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference a destructive command hands back has to survive being written down.
 * <p>
 * It travels by the least reliable routes there are: typed into a ticket, photographed off a
 * screen, read aloud over voice chat, remembered. Every one of those loses the hyphen, the
 * case, or both — so the parser has to take back rather less than it gave out, and has to say
 * so plainly when what it was handed is not one of these at all.
 */
class OperationIdTest {

	@Test
	@DisplayName("what it prints is what it reads back")
	void roundTrips() {
		for (OperationId.Kind kind : OperationId.Kind.values()) {
			OperationId.Ref ref = kind == OperationId.Kind.CASE
					? OperationId.of(kind, "4KX9QW1M")
					: OperationId.of(kind, 1234L);

			OperationId.Ref back = OperationId.parse(ref.toString());
			assertNotNull(back, kind + " did not survive its own format: " + ref);
			assertEquals(ref, back, "the reference changed on the way back");
		}
	}

	@Test
	@DisplayName("the letters are distinct, or one reference means two things")
	void lettersDoNotCollide() {
		Set<Character> seen = new HashSet<>();
		for (OperationId.Kind kind : OperationId.Kind.values()) {
			assertTrue(seen.add(kind.letter()),
					kind + " reuses the letter " + kind.letter() + ", so a reference is "
							+ "ambiguous and one of them resolves to the wrong record.");
		}
	}

	@Test
	@DisplayName("a reference typed from a ticket still resolves")
	void toleratesHowPeopleActuallyWriteThem() {
		OperationId.Ref expected = OperationId.of(OperationId.Kind.PUNISHMENT, 88L);

		for (String typed : new String[] {"P-88", "p-88", "P88", "p88", "  P-88  ", "#P-88"}) {
			assertEquals(expected, OperationId.parse(typed),
					"\"" + typed + "\" is how somebody will write it, and it did not resolve");
		}
	}

	@Test
	@DisplayName("something that is not a reference is refused rather than guessed at")
	void refusesWhatItCannotRead() {
		for (String junk : new String[] {null, "", "X", "Z-1", "1234", "P-", "P-12a", "-"}) {
			assertNull(OperationId.parse(junk),
					"\"" + junk + "\" parsed as a reference, which sends somebody looking for "
							+ "a record that was never there");
		}
	}

	@Test
	@DisplayName("a numeric kind with a non-numeric body is a typo, not another kind")
	void aTypoIsToldApartFromAMissingRecord() {
		// The distinction matters more than it looks. "Not a reference" sends somebody back to
		// check what they typed; "no such punishment" sends them looking for a deleted record,
		// and nothing in this mod is deleted — so they conclude something is very wrong.
		assertNull(OperationId.parse("P-4KX9"), "a case id under the punishment letter");
		assertNotNull(OperationId.parse("C-4KX9"), "but a case id is text and must be allowed");
	}

	@Test
	@DisplayName("every reference knows the command that opens it")
	void oneLookupForAllOfThem() {
		for (OperationId.Kind kind : OperationId.Kind.values()) {
			String command = OperationId.of(kind, 7L).command();
			assertTrue(command.startsWith("/staff op "),
					kind + " points at " + command + ". One command for every kind is the "
							+ "whole point: staff paste what they were given without first "
							+ "working out what sort of thing it is.");
		}
	}
}
