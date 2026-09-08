package io.github.alphain24.staffcore.modules.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four numbers a staff member is looking at while deciding whether to act.
 * <p>
 * Sending the packets needs a connection, so what is checked here is the content: that the
 * panel says the same thing the report does, and that it does not quietly reassure.
 */
class ReplaySidebarTest {

	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	private static XraySweep.Finding finding(double p) {
		return new XraySweep.Finding("Steve_", "overworld", -16, 4000, 40, 300, 30, p);
	}

	@Test
	@DisplayName("the finding comes first, because it is the finding")
	void theOrderAnswersQuestions() {
		List<String> lines = ReplaySidebar.lines(finding(1e-6), 0);

		assertTrue(lines.get(0).startsWith("Chance:"),
				"the panel opens with something other than the conclusion: " + lines.get(0));
		assertTrue(lines.get(0).contains("million") || lines.get(0).contains("thousand"),
				"the chance is not in words: " + lines.get(0));
	}

	@Test
	@DisplayName("both fractions are shown, so the claim can be checked rather than taken")
	void theArithmeticIsVisible() {
		// A staff member should be able to see how the number was reached. "30 of 40 ore" and
		// "300 of 4000 dug" is the whole calculation, and it is the half an accused player
		// can argue with.
		List<String> lines = ReplaySidebar.lines(finding(1e-6), 0);
		String all = String.join(" | ", lines);

		assertTrue(all.contains("30 of 40"), "the ore fraction is missing: " + all);
		assertTrue(all.contains("300 of 4000"), "the excavated fraction is missing: " + all);
		assertTrue(all.contains("overworld"), "nothing says where this was: " + all);
	}

	@Test
	@DisplayName("no decoy line when there were no decoy hits")
	void zeroIsNotReassurance() {
		// "Decoys broken: 0" would be the most reassuring line on the panel, and it is not
		// reassurance — canaries are off entirely when a bulk anti-xray is installed, so a
		// zero means "no separate signal" rather than "this player passed a test".
		assertFalse(String.join(" ", ReplaySidebar.lines(finding(1e-6), 0)).contains("Decoys"));
		assertTrue(String.join(" ", ReplaySidebar.lines(finding(1e-6), 2)).contains("Decoys broken: 2"));
	}

	@Test
	@DisplayName("an unremarkable dig says so rather than showing nothing")
	void theQuietCaseStillReads() {
		List<String> lines = ReplaySidebar.lines(finding(0.8), 0);

		assertEquals(4, lines.size(), "the panel changed shape for an ordinary result");
		assertTrue(lines.get(0).contains("no more than chance"),
				"an ordinary dig should say so plainly: " + lines.get(0));
	}
}
