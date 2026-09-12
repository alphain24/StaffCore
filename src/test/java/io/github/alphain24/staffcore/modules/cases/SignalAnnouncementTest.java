package io.github.alphain24.staffcore.modules.cases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every finding reaches somebody, however quietly.
 *
 * <h2>The bug this closes</h2>
 * Contraband detection worked and was invisible. A player carrying bedrock was swept up,
 * matched against the rules, and recorded as a signal — and then nobody was told, because the
 * announcer returned early for any signal that attached to no case.
 * <p>
 * Attaching to no case is correct: contraband confidence is 45 and
 * {@code caseAutoOpenSeverity} is 70, so one banned item does not open an investigation. That
 * is the design and it is a good one. What was wrong is that <b>"no case" was treated as "say
 * nothing"</b>, and those are different questions. A finding can be worth a glance without
 * being worth an investigation, and most of them are.
 * <p>
 * The visible result was a config key documented as alerting "the moment a player is seen
 * holding one" that alerted nobody, ever. It failed the way this project's bugs usually fail:
 * silently, while every component did exactly what it was written to do.
 *
 * <h2>Why the decision is a function</h2>
 * The announcement needs a server, a module registry and a live alert channel to deliver.
 * Deciding what to say needs none of those, so it is separated and checked here — the thing
 * that broke was the decision, not the delivery.
 */
class SignalAnnouncementTest {

	private static Signal signal() {
		return Signal.of(Signal.Type.CONTRABAND, UUID.randomUUID(), "Bob", 45,
				"has 3× bedrock in their inventory", "security");
	}

	private static CaseStore.Landing unattached() {
		return new CaseStore.Landing(signal(), null, false);
	}

	private static CaseStore.Landing joined() {
		return new CaseStore.Landing(signal(), "ABCD2345", false);
	}

	private static CaseStore.Landing opened() {
		return new CaseStore.Landing(signal(), "ABCD2345", true);
	}

	@Test
	@DisplayName("a finding that opens no case is still announced")
	void theQuietPathSaysSomething() {
		CaseModule.Announcement said = CaseModule.decide(unattached());

		assertNotNull(said, "a signal that attached to nothing produced no announcement at all, "
				+ "so contraband detection runs and tells nobody — which is what it did");
		assertFalse(said.message().isBlank(),
				"the announcement is empty, which reaches staff as a blank line");
		assertTrue(said.message().contains("bedrock"),
				"the announcement does not say what was found: " + said.message());
	}

	@Test
	@DisplayName("it is said quietly, because one banned item is not an emergency")
	void theQuietPathIsQuiet() {
		// The other half of the fix. Making every contraband find loud would have closed the
		// bug and opened a worse one: staff who learn to dismiss the channel stop reading the
		// findings that matter.
		assertFalse(CaseModule.decide(unattached()).loud(),
				"a single banned item was announced loudly. An alert staff learn to dismiss "
						+ "teaches them to dismiss the ones that matter.");
	}

	@Test
	@DisplayName("it says why no case was opened")
	void theQuietPathExplainsItself() {
		// Without this the next question is always "why is there no case for this", and the
		// answer is a config value nobody has in their head.
		String message = CaseModule.decide(unattached()).message();

		assertTrue(message.contains("threshold"),
				"the announcement does not explain why no case was opened: " + message);
	}

	@Test
	@DisplayName("opening a case is loud, joining one is not")
	void theOtherTwoPathsKeepTheirVolume() {
		// The control. If everything were quiet, the test above would pass for the wrong
		// reason and a signal serious enough to open an investigation would arrive as a
		// murmur.
		assertTrue(CaseModule.decide(opened()).loud(),
				"a signal that opened a case was announced quietly");
		assertTrue(CaseModule.decide(opened()).message().contains("ABCD2345"),
				"the loud announcement does not name the case it opened");

		assertFalse(CaseModule.decide(joined()).loud(),
				"joining an existing case interrupted everybody a second time about the same "
						+ "player");
		assertTrue(CaseModule.decide(joined()).message().contains("ABCD2345"),
				"the announcement does not name the case it joined");
	}

	@Test
	@DisplayName("no landing produces silence")
	void nothingIsEverSilent() {
		// The property that actually matters, asserted directly rather than left implied by
		// the three cases above. Whatever a signal does on arrival, something is said about it.
		for (CaseStore.Landing landing : new CaseStore.Landing[] {
				unattached(), joined(), opened()}) {

			CaseModule.Announcement said = CaseModule.decide(landing);
			assertNotNull(said, "a landing produced no announcement");
			assertFalse(said.message().isBlank(),
					"a landing produced a blank announcement, which is silence with extra steps");
		}
	}
}
