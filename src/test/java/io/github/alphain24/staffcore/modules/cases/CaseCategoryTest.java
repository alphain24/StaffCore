package io.github.alphain24.staffcore.modules.cases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which kind of case a signal lands in. */
class CaseCategoryTest {

	private static Signal signal(Signal.Type type, String detail) {
		return Signal.of(type, UUID.randomUUID(), "Steve", 70, detail, "test");
	}

	@Test
	@DisplayName("detectors say what they are")
	void detectorsMapDirectly() {
		assertEquals(CaseCategory.GRIEFING, CaseCategory.of(signal(Signal.Type.MASS_GRIEF, "x")));
		assertEquals(CaseCategory.CHEATING, CaseCategory.of(signal(Signal.Type.XRAY, "x")));
		assertEquals(CaseCategory.CHEATING, CaseCategory.of(signal(Signal.Type.ANTICHEAT, "x")));
		assertEquals(CaseCategory.CHEATING, CaseCategory.of(signal(Signal.Type.CANARY, "x")));
		assertEquals(CaseCategory.ILLEGAL_ITEMS, CaseCategory.of(signal(Signal.Type.CONTRABAND, "x")));
		assertEquals(CaseCategory.BAN_EVASION, CaseCategory.of(signal(Signal.Type.ALT_MATCH, "x")));
	}

	@Test
	@DisplayName("a report is sorted by what the reporter wrote")
	void reportsAreReadForTheirWords() {
		assertEquals(CaseCategory.GRIEFING, CaseCategory.of(signal(Signal.Type.REPORT,
				"Alex reported: he griefed my house with tnt")));
		assertEquals(CaseCategory.CHEATING, CaseCategory.of(signal(Signal.Type.REPORT,
				"Alex reported: flying and killaura")));
		assertEquals(CaseCategory.CHAT, CaseCategory.of(signal(Signal.Type.REPORT,
				"Alex reported: spamming chat")));
		assertEquals(CaseCategory.ILLEGAL_ITEMS, CaseCategory.of(signal(Signal.Type.REPORT,
				"Alex reported: duping diamonds")));
		assertEquals(CaseCategory.OTHER, CaseCategory.of(signal(Signal.Type.REPORT,
				"Alex reported: being annoying")),
				"nothing recognisable should go to Other, not be guessed");
	}

	@Test
	@DisplayName("the reporter's own name is not part of the claim")
	void reporterNameIsIgnored() {
		assertEquals(CaseCategory.CHAT, CaseCategory.of(signal(Signal.Type.REPORT,
				"Griefer123 reported: spamming")));
	}

	@Test
	@DisplayName("short words are matched whole, so butterfly is not flying")
	void noFalseMatchesInsideWords() {
		assertEquals(CaseCategory.OTHER, CaseCategory.fromWords("built a butterfly garden"));
		assertEquals(CaseCategory.OTHER, CaseCategory.fromWords("he reached the end first"));
		assertEquals(CaseCategory.OTHER, CaseCategory.fromWords("halt, who goes there"));
	}

	@Test
	@DisplayName("what staff type is understood, and nonsense is refused")
	void parsing() {
		assertEquals(CaseCategory.GRIEFING, CaseCategory.parse("griefing"));
		assertEquals(CaseCategory.CHEATING, CaseCategory.parse("hacking"));
		assertEquals(CaseCategory.ILLEGAL_ITEMS, CaseCategory.parse("illegal-items"));
		assertEquals(CaseCategory.CHAT, CaseCategory.parse("spam"));
		assertNull(CaseCategory.parse("banana"));
		assertEquals(CaseCategory.OTHER, CaseCategory.ofStored(null), "a case from before kinds");
	}
}
