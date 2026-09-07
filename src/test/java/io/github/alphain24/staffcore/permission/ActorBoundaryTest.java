package io.github.alphain24.staffcore.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Actor} is a real boundary, not a wrapper around a player.
 * <p>
 * The distinction is the whole value. A type that carries a {@code ServerPlayer} and asks it
 * questions lazily looks like a boundary in a diagram and is not one: it is still
 * unconstructible in a test, still unreachable from a bot thread, and the coupling it was
 * supposed to remove has simply moved one level down where it is harder to see.
 * <p>
 * So these check the properties rather than the intent — that the record cannot hold a live
 * object, that it is constructible with nothing running, and that it resolves permissions
 * eagerly rather than keeping something to ask later.
 */
class ActorBoundaryTest {

	/** Everything a live server would drag in. None of it may appear in the record. */
	private static final Set<String> FORBIDDEN = Set.of(
			"ServerPlayer", "MinecraftServer", "ServerLevel", "Level", "Entity",
			"CommandSourceStack", "Connection", "PlayerList");

	@Test
	@DisplayName("the record holds no live server object, directly or transitively")
	void nothingLiveIsRetained() {
		for (RecordComponent component : Actor.class.getRecordComponents()) {
			String type = component.getType().getSimpleName();
			assertFalse(FORBIDDEN.contains(type),
					"Actor." + component.getName() + " is a " + type + ". Keeping one means the "
							+ "coupling moved rather than went: this would still be "
							+ "unconstructible in a test and unreachable from a Discord thread.");
		}

		// UUID, String, enum, Set, boolean — all plain data.
		assertEquals(5, Actor.class.getRecordComponents().length,
				"Actor gained a component; check it is not something live");
	}

	@Test
	@DisplayName("it is constructible with no server running at all")
	void headlessConstruction() {
		// No bootstrap, no registries, no world. If this test needed either, the type would
		// have failed at the thing it exists for.
		Actor discord = Actor.of(UUID.randomUUID(), "Alice", Actor.Source.DISCORD_LINKED,
				Set.of(Nodes.PUNISH, Nodes.APPROVE));

		assertTrue(discord.has(Nodes.PUNISH));
		assertFalse(discord.has(Nodes.ROLLBACK));
		assertTrue(discord.isAccountable(), "a linked Discord user is a person");
	}

	@Test
	@DisplayName("it carries identity, a resolved permission set, and a source")
	void theThreeThingsAPolicyNeeds() {
		Actor actor = Actor.of(UUID.randomUUID(), "Bob", Actor.Source.PLAYER, Set.of(Nodes.BAN));

		assertTrue(actor.id() != null, "identity");
		assertFalse(actor.nodes().isEmpty(), "a resolved permission set");
		assertEquals(Actor.Source.PLAYER, actor.source(), "where the instruction came from");
	}

	@Test
	@DisplayName("permissions are a snapshot, not a live question")
	void resolutionIsEager() {
		Actor actor = Actor.of(UUID.randomUUID(), "Bob", Actor.Source.PLAYER, Set.of(Nodes.BAN));

		// has() must be a set lookup. If it called Permissions.check under the hood it would
		// need a player, and one decision could answer differently halfway through because a
		// permissions plugin reloaded.
		assertTrue(actor.nodes().contains(Nodes.BAN));
		assertTrue(actor.has(Nodes.BAN));
		assertFalse(actor.has(Nodes.RELOAD));
	}

	@Test
	@DisplayName("the resolved set is immutable, so nothing can be granted after the fact")
	void permissionsCannotBeAddedLater() {
		Actor actor = Actor.of(UUID.randomUUID(), "Bob", Actor.Source.PLAYER, Set.of(Nodes.BAN));

		try {
			actor.nodes().add(Nodes.RELOAD);
			throw new AssertionError("the permission set is mutable — anything holding an Actor "
					+ "could grant itself a node");
		} catch (UnsupportedOperationException expected) {
			// Right answer.
		}
	}

	@Test
	@DisplayName("every node StaffCore defines is enumerable, so none is silently denied")
	void nodeEnumerationIsComplete() throws Exception {
		Set<String> all = Actor.all();

		// Resolution walks this list. A node missing from it would be absent from every
		// actor's set and therefore denied to everybody, which is the quiet kind of wrong.
		assertTrue(all.contains(Nodes.PUNISH));
		assertTrue(all.contains(Nodes.APPROVE));
		assertTrue(all.contains(Nodes.AUDIT_ADDRESSES));
		assertTrue(all.contains(Nodes.RATE_LIMIT_EXEMPT));

		long declared = java.util.Arrays.stream(Nodes.class.getDeclaredFields())
				.filter(f -> f.getType() == String.class)
				.filter(f -> java.lang.reflect.Modifier.isStatic(f.getModifiers()))
				.count();
		assertEquals(declared, all.size(),
				"the enumeration missed a node; anything it misses is denied to everyone");
	}

	// -------------------------------------------------------- the accountability rule

	@Test
	@DisplayName("console, RCON, scheduled tasks and unlinked Discord are all the same case")
	void nobodyIsNobody() {
		// The point of stating it on the source: these are not four special cases, they are
		// one property with four spellings, and the next way in gets the answer for free.
		for (Actor.Source source : new Actor.Source[] {
				Actor.Source.CONSOLE, Actor.Source.RCON, Actor.Source.SCHEDULED,
				Actor.Source.DISCORD_UNLINKED, Actor.Source.SYSTEM }) {

			assertFalse(source.isAccountable(),
					source + " is treated as answerable for an action. It holds every "
							+ "permission or none and belongs to no account.");
		}

		assertTrue(Actor.Source.PLAYER.isAccountable());
		assertTrue(Actor.Source.DISCORD_LINKED.isAccountable());
	}

	@Test
	@DisplayName("a UUID alone is not accountability, and neither is a source alone")
	void bothHalvesAreRequired() {
		Actor noId = Actor.of(null, "Ghost", Actor.Source.PLAYER, Set.of());
		assertFalse(noId.isAccountable(), "a player source with no identity is still nobody");

		Actor scheduled = Actor.of(UUID.randomUUID(), "Timer", Actor.Source.SCHEDULED, Set.of());
		assertFalse(scheduled.isAccountable(),
				"an automated task with a UUID is still not a person");
	}

	@Test
	@DisplayName("a name-only actor is explicitly not accountable")
	void namedActorsAreNotPeople() {
		Actor named = Actor.named("Alice");

		// This is what the old bare-String actor really was. Making the type say so is the
		// improvement: a name somebody supplied is exactly what would be forged to attribute
		// an action to a colleague.
		assertFalse(named.isAccountable());
		assertTrue(named.nodes().isEmpty(), "and it holds nothing, so anything gated refuses it");
		assertEquals("Alice", named.name(), "the attribution still works");
	}

	@Test
	@DisplayName("the console gets a clear refusal, not a bare denial")
	void refusalsExplain() {
		var verdict = Accountable.require(Actor.console(), "approve a staged action");

		assertTrue(verdict.refused());
		String message = verdict.refusal();

		// Somebody typing this into a console at two in the morning needs to know the answer
		// is "get a second person", not "you lack a permission" — the second reading sends
		// them looking for a config key that will not help, and they will find one.
		assertTrue(message.contains("console") || message.contains("Console"),
				"the refusal should name what was refused: " + message);
		assertTrue(message.contains("responsible") || message.contains("held"),
				"and say why rather than just no: " + message);
		assertTrue(message.length() > 80, "a one-word denial teaches nobody anything");
	}

	@Test
	@DisplayName("an accountable actor passes the same rule")
	void thePassingCase() {
		Actor person = Actor.of(UUID.randomUUID(), "Alice", Actor.Source.PLAYER, Set.of());
		assertTrue(Accountable.require(person, "approve a staged action").allowed());
		assertTrue(Accountable.canApprove(person));
		assertFalse(Accountable.canApprove(Actor.console()));
	}

	@Test
	@DisplayName("the accountability rule lives in one place")
	void oneRuleNotFourSpecialCases() throws Exception {
		// The failure this guards against is a `getPlayer() == null` reappearing in a command,
		// a menu, or the Discord bridge — each of which would be a fourth copy of the rule,
		// drifting from the other three.
		Path source = Path.of("src", "main", "java");
		java.util.List<String> offenders = new java.util.ArrayList<>();

		try (var files = Files.walk(source)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String relative = source.relativize(file).toString().replace('\\', '/');
				if (relative.contains("permission/Accountable.java")) continue;
				if (relative.contains("permission/Actor.java")) continue;

				String body = Files.readString(file, StandardCharsets.UTF_8);
				if (body.contains("cannot be the second person")
						|| body.contains("Approval has to come from a player")) {
					offenders.add(relative);
				}
			}
		}
		assertTrue(offenders.isEmpty(),
				"the console rule has been restated outside Accountable in:\n  "
						+ String.join("\n  ", offenders));
	}
}
