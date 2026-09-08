package io.github.alphain24.staffcore.modules.identity;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every name an account has used, and when.
 * <p>
 * A rename is the cheapest way to escape a reputation, and the only thing that survives it is
 * the UUID — which nobody types, reads or remembers. An old ban record naming somebody nobody
 * can find any more is almost always this.
 * <p>
 * The property that carries the value is {@code first_seen} never moving. Overwriting it on
 * every join would leave every name looking new, which is precisely the fact this exists to
 * contradict: "they have been called that since 2021" and "they started calling themselves
 * that last Tuesday" are different answers to the same question.
 */
class NameHistoryTest {

	@TempDir
	Path world;

	private Storage storage;
	private IdentityModule identity;

	private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

	@BeforeEach
	void open() {
		storage = StaffCore.storage();
		storage.open(world);
		identity = new IdentityModule();
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	@Test
	@DisplayName("a name is recorded once and then only touched")
	void firstSeenNeverMoves() throws InterruptedException {
		identity.recordName(PLAYER, "Steve_");
		long firstSeen = identity.namesOf(PLAYER).get(0).firstSeen();

		Thread.sleep(5);
		identity.recordName(PLAYER, "Steve_");

		List<IdentityModule.PastName> names = identity.namesOf(PLAYER);
		assertEquals(1, names.size(), "the same name twice made two rows");
		assertEquals(firstSeen, names.get(0).firstSeen(),
				"first_seen moved, so a name held for years now looks new");
		assertTrue(names.get(0).lastSeen() > firstSeen, "last_seen should follow the join");
	}

	@Test
	@DisplayName("every name is kept, newest first")
	void renamesAccumulate() throws InterruptedException {
		identity.recordName(PLAYER, "OldName");
		Thread.sleep(5);
		identity.recordName(PLAYER, "NewName");

		List<IdentityModule.PastName> names = identity.namesOf(PLAYER);
		assertEquals(2, names.size());
		assertEquals("NewName", names.get(0).name(), "the current name should read first");
		assertEquals("OldName", names.get(1).name());
	}

	@Test
	@DisplayName("a name leads back to the account that used it")
	void reverseLookupWorks() {
		// The direction that matters when a ban record names somebody nobody can find any
		// more: the account is still there, under a name the record has never heard of.
		identity.recordName(PLAYER, "OldName");

		assertEquals(List.of(PLAYER), identity.accountsEverCalled("OldName"));
		assertEquals(List.of(PLAYER), identity.accountsEverCalled("oldname"),
				"somebody typing a remembered name will not match its capitalisation");
		assertTrue(identity.accountsEverCalled("NeverUsed").isEmpty());
	}

	@Test
	@DisplayName("two accounts sharing a name are both returned")
	void aNameCanBeReused() {
		// Minecraft frees a name when an account changes it, so two accounts genuinely can
		// have used the same one. Returning only the first would point an investigation at
		// whichever happened to be inserted first.
		UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
		identity.recordName(PLAYER, "Shared");
		identity.recordName(other, "Shared");

		assertEquals(2, identity.accountsEverCalled("Shared").size());
	}

	@Test
	@DisplayName("nothing is recorded for a blank or missing name")
	void garbageIsNotStored() {
		identity.recordName(PLAYER, null);
		identity.recordName(PLAYER, "");
		identity.recordName(PLAYER, "   ");
		identity.recordName(null, "Steve_");

		assertTrue(identity.namesOf(PLAYER).isEmpty());
	}
}
