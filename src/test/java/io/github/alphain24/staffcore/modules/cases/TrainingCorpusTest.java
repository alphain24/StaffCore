package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a threshold is allowed to be justified against.
 * <p>
 * The failure this prevents is quiet and slow. On a busy server most cases are closed because
 * nobody had time, not because somebody looked and disagreed with the detector — so if every
 * cleared case counted, the corpus would fill with timeouts and a threshold validated against
 * it would drift towards whatever staff had capacity for.
 * <p>
 * Nothing here would throw if it went wrong. The number would simply get larger and mean less.
 */
class TrainingCorpusTest {

	@TempDir
	Path world;

	private Storage storage;

	@BeforeEach
	void open() {
		storage = StaffCore.storage();
		storage.open(world);
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	private void closedCase(String id, String status, String reason) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement(
				"INSERT INTO cases (id, subject_uuid, subject_name, status, severity, summary, "
						+ "opened_at, opened_by, closed_at, resolution_reason) "
						+ "VALUES (?,?,'Steve_',?,80,'x',?,'SYSTEM',?,?)")) {
			ps.setString(1, id);
			ps.setString(2, UUID.randomUUID().toString());
			ps.setString(3, status);
			ps.setLong(4, System.currentTimeMillis());
			ps.setLong(5, System.currentTimeMillis());
			ps.setString(6, reason);
			ps.executeUpdate();
		}
	}

	@Test
	@DisplayName("only a case somebody investigated and cleared counts as a negative")
	void deliberateClearsOnly() throws SQLException {
		closedCase("AAAAAAA1", "cleared", Resolution.INVESTIGATED_INNOCENT.stored());
		closedCase("AAAAAAA2", "cleared", Resolution.NOT_INVESTIGATED.stored());
		closedCase("AAAAAAA3", "cleared", Resolution.INVESTIGATED_UNCLEAR.stored());
		closedCase("AAAAAAA4", "stale", Resolution.STALE.stored());
		closedCase("AAAAAAA5", "cleared", Resolution.SUBJECT_LEFT.stored());
		closedCase("AAAAAAA6", "cleared", Resolution.DUPLICATE.stored());

		var corpus = TrainingCorpus.composition();

		assertEquals(1, corpus.negatives(),
				"six closed cases produced " + corpus.negatives() + " negatives. Only the one "
						+ "somebody investigated is a statement about the detector.");
		assertEquals(5, corpus.excludedTotal());
	}

	@Test
	@DisplayName("an actioned case is a positive whatever note was attached")
	void actionedIsAgreement() throws SQLException {
		// Somebody punished a player over it. That is as clear a statement that the detector
		// was right as this system can produce, and it does not need a reason to say so.
		closedCase("BBBBBBB1", "actioned", null);
		closedCase("BBBBBBB2", "actioned", Resolution.INVESTIGATED_INNOCENT.stored());

		assertEquals(2, TrainingCorpus.composition().positives());
		assertEquals(0, TrainingCorpus.composition().negatives(),
				"an actioned case was also counted as a clear");
	}

	@Test
	@DisplayName("a case closed before reasons existed is unlabelled, not innocent")
	void nullIsNotAJudgement() throws SQLException {
		// Backfilling these as investigated would invent a judgement nobody made, and every
		// invented one would be a vote that the detector was wrong.
		closedCase("CCCCCCC1", "cleared", null);
		closedCase("CCCCCCC2", "cleared", null);

		var corpus = TrainingCorpus.composition();
		assertEquals(0, corpus.negatives());
		assertEquals(2, corpus.unlabelled());
		assertEquals(0, corpus.usable());
	}

	@Test
	@DisplayName("an unreadable reason is excluded rather than guessed at")
	void garbageDoesNotBecomeANegative() throws SQLException {
		// The migration failure mode. A value the enum cannot read must not resolve to the one
		// value that counts, because that is how a schema change becomes a shifted threshold.
		closedCase("DDDDDDD1", "cleared", "probably-fine");

		assertEquals(0, TrainingCorpus.composition().negatives());
		assertEquals(1, TrainingCorpus.composition().unlabelled());
	}

	@Test
	@DisplayName("the description always names both halves")
	void sizeIsNeverQuotedAlone() throws SQLException {
		// "Validated against 209 resolved cases" and "against 9 real clears and 200 timeouts"
		// are the same query. A caller must not be able to quote the first.
		closedCase("EEEEEEE1", "cleared", Resolution.INVESTIGATED_INNOCENT.stored());
		for (int i = 0; i < 8; i++) {
			closedCase("EEEEEE" + (10 + i), "stale", Resolution.STALE.stored());
		}

		String described = TrainingCorpus.composition().describe();
		assertTrue(described.contains("1 usable"), "the usable count is missing: " + described);
		assertTrue(described.contains("8 excluded") || described.contains("8 stale"),
				"the excluded count is missing: " + described);
		assertTrue(described.contains("stale"),
				"the description does not say what was excluded: " + described);
	}

	@Test
	@DisplayName("a small corpus says it is too small rather than reading as a mandate")
	void thereIsAFloor() throws SQLException {
		closedCase("FFFFFFF1", "cleared", Resolution.INVESTIGATED_INNOCENT.stored());
		assertFalse(TrainingCorpus.composition().isEnoughToTuneAgainst(),
				"one cleared case reported as enough to move a threshold on");

		for (int i = 0; i < 40; i++) {
			closedCase("GGGGG" + String.format("%03d", i), "actioned", null);
		}
		assertTrue(TrainingCorpus.composition().isEnoughToTuneAgainst());
	}

	@Test
	@DisplayName("the labelled set contains only what the composition counts as usable")
	void nothingExcludedLeaksIntoTheData() throws SQLException {
		// A caller that has to remember to filter is one that will forget, and forgetting
		// means two hundred timeouts arriving as two hundred players the detector was wrong
		// about.
		closedCase("HHHHHHH1", "cleared", Resolution.INVESTIGATED_INNOCENT.stored());
		closedCase("HHHHHHH2", "cleared", Resolution.NOT_INVESTIGATED.stored());
		closedCase("HHHHHHH3", "stale", Resolution.STALE.stored());
		closedCase("HHHHHHH4", "actioned", null);

		var labelled = TrainingCorpus.labelled();
		assertEquals(TrainingCorpus.composition().usable(), labelled.size(),
				"the labelled set and the composition disagree about what is usable");
		assertEquals(2, labelled.size());
		assertTrue(labelled.stream().anyMatch(TrainingCorpus.Labelled::cheating));
		assertTrue(labelled.stream().anyMatch(l -> !l.cheating()));
	}

	@Test
	@DisplayName("an empty corpus says so rather than reporting a clean zero")
	void nothingYetIsStated() {
		var corpus = TrainingCorpus.composition();

		assertEquals(0, corpus.usable());
		assertFalse(corpus.isEnoughToTuneAgainst());
		assertTrue(corpus.describe().contains("No usable cases yet"),
				"an empty corpus should say so: " + corpus.describe());
	}
}
