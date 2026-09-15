package io.github.alphain24.staffcore.modules.accountability;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.Nodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate 2 asks for a test that tries to get round the rate limits and the approval check.
 * <p>
 * This is that test, and it is written in the spirit the gate intends: not "does the happy
 * path work" but "here are the ways somebody would actually get round this, and each one is
 * closed". Most controls fail not because the check is wrong but because there is a second
 * route that never reaches it.
 */
class BypassAttemptTest {

	private Approvals approvals;

	@BeforeEach
	void setUp() {
		approvals = new Approvals();
		StaffConfig.get().requireTwoPersonApproval = true;
		StaffConfig.get().approvalExpiryMinutes = 10;
		StaffConfig.get().maxPunishmentsPerMinute = 6;
		StaffConfig.get().maxRollbacksPerMinute = 3;
	}

	// ------------------------------------------------------ the structural check

	/**
	 * The most important assertion here, and the only one that survives somebody rewriting
	 * the commands.
	 * <p>
	 * A rate limit checked in {@code /staff ban} would leave the GUI, the API and any future
	 * Discord path unlimited — three ways in that each have to be remembered separately. So
	 * the check lives in the service every path already funnels through, and this asserts that
	 * placement rather than the behaviour, because the behaviour is only worth anything if the
	 * placement is right.
	 */
	@Test
	@DisplayName("the rate limit is enforced in the service, not in the command layer")
	void theLimitIsWhereEveryPathGoes() throws Exception {
		String punish = java.nio.file.Files.readString(java.nio.file.Path.of(
				"src/main/java/io/github/alphain24/staffcore/modules/punish/PunishmentModule.java"));
		String grief = java.nio.file.Files.readString(java.nio.file.Path.of(
				"src/main/java/io/github/alphain24/staffcore/modules/grief/GriefModule.java"));

		assertTrue(punish.contains("limits()") && punish.contains("RateLimits.Kind.PUNISHMENT"),
				"PunishmentModule.apply must check the limit itself. If this moved to the "
						+ "command, every non-command path is unlimited and nothing here would "
						+ "notice.");
		assertTrue(grief.contains("limits()") && grief.contains("RateLimits.Kind.ROLLBACK"),
				"GriefModule.rollback must check the limit itself");

		// And apply() must remain the only way in. A second entry point that skipped the
		// check would make the first one decorative.
		List<String> entryPoints = new ArrayList<>();
		for (Method m : Class.forName("io.github.alphain24.staffcore.modules.punish.PunishmentModule")
				.getDeclaredMethods()) {
			if (m.getName().equals("apply")) entryPoints.add(m.toString());
		}
		assertFalse(entryPoints.isEmpty(), "apply() has gone; the funnel is somewhere else now");
	}

	// --------------------------------------------------------- self-approval

	@Test
	@DisplayName("the person who staged an action cannot approve it, whatever they hold")
	void nobodyApprovesTheirOwn() {
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");

		// The situation this exists for is one account acting alone — compromised, or angry.
		// Permission is not the question: an approval somebody can grant themselves is a
		// confirmation prompt in a costume.
		Approvals.Outcome self = approve(alice, "Alice", staged.id());

		assertFalse(self.approved(), "a staff member approved their own staged action");
		assertTrue(self.refusal().toLowerCase().contains("yourself"),
				"and the refusal should say why rather than looking like a missing id");
	}

	@Test
	@DisplayName("renaming does not make you a different person")
	void identityIsByUuidNotName() {
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");

		// Names change. The check is on UUID precisely so that changing one does not turn a
		// self-approval into a valid second signature.
		Approvals.Outcome renamed = approve(alice, "AliceTheSecond", staged.id());
		assertFalse(renamed.approved(), "the same account approved its own action under a new name");
	}

	@Test
	@DisplayName("a second person can approve, so the mechanism is not simply broken")
	void somebodyElseCanApprove() {
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");

		Approvals.Outcome other = approve(UUID.randomUUID(), "Bob", staged.id());
		assertTrue(other.approved(), other.refusal());
		assertEquals(staged.id(), other.staged().id());
	}

	@Test
	@DisplayName("an approval is single-use, so one signature cannot run an action twice")
	void approvalsAreConsumed() {
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");

		assertTrue(approve(UUID.randomUUID(), "Bob", staged.id()).approved());

		// Replaying the id would turn one agreement into unlimited executions, which for a
		// mass rollback is the difference between one mistake and a pattern of them.
		assertFalse(approve(UUID.randomUUID(), "Carol", staged.id()).approved(),
				"a staged action was approved twice");
	}

	@Test
	@DisplayName("guessing an id does not approve anything")
	void idsCannotBeGuessedIntoExistence() {
		assertFalse(approve(UUID.randomUUID(), "Bob", "00000000").approved());
		assertFalse(approve(UUID.randomUUID(), "Bob", "").approved());

		// Ids are random rather than sequential for this reason: approving an action you
		// never saw is the same failure as self-approval, reached from the other side.
		UUID alice = UUID.randomUUID();
		Approvals.Staged first = stage(alice, "Alice");
		Approvals.Staged second = stage(alice, "Alice");
		assertFalse(first.id().equals(second.id()), "ids repeat");
	}

	@Test
	@DisplayName("a staged action expires rather than waiting forever")
	void stagingIsNotPermanent() {
		StaffConfig.get().approvalExpiryMinutes = 0;   // treated as "never expires"
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");
		assertFalse(staged.isExpired(), "0 should mean no expiry");

		StaffConfig.get().approvalExpiryMinutes = 10;
		Approvals.Staged old = new Approvals.Staged("OLD", Approvals.Action.IP_BAN, alice,
				"Alice", "summary", "detail", System.currentTimeMillis() - 60L * 60_000L);

		// An hour-old intention approved by somebody who was not there when it was staged is
		// a signature on something nobody remembers, which is the opposite of the point.
		assertTrue(old.isExpired());
	}

	@Test
	@DisplayName("nobody without an accountable identity can approve")
	void nobodyIsNotASecondPerson() {
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");

		// Console, RCON, a scheduled task and an unlinked Discord user are one case rather
		// than four, and this is where that pays: none of them had to be special-cased in
		// Approvals, and a fifth way in would be refused without anybody adding a branch.
		for (Actor nobody : new Actor[] {
				Actor.console(),
				Actor.system(),
				Actor.of(null, "rcon", Actor.Source.RCON, java.util.Set.of()),
				Actor.of(UUID.randomUUID(), "timer", Actor.Source.SCHEDULED, java.util.Set.of()),
				Actor.of(UUID.randomUUID(), "someone", Actor.Source.DISCORD_UNLINKED,
						java.util.Set.of(Nodes.APPROVE)) }) {

			Approvals.Outcome outcome = approvals.approve(nobody, staged.id());
			assertFalse(outcome.approved(),
					nobody.source() + " approved a staged action despite being nobody");
			assertTrue(outcome.refusal().length() > 40,
					"and the refusal should explain rather than deny: " + outcome.refusal());
		}

		// Still there afterwards — a refused approval must not consume the staged action, or
		// a console attempt would quietly cancel somebody's pending work.
		assertTrue(approvals.find(staged.id()).isPresent(),
				"a refused approval consumed the staged action");
	}

	@Test
	@DisplayName("holding the approve node does not make you accountable")
	void permissionIsNotIdentity() {
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");

		// The console holds every node there is. If the check were a permission check, it
		// would pass — which is exactly the confusion the rule is written to avoid.
		assertTrue(Actor.console().has(Nodes.APPROVE), "the console does hold the node");
		assertFalse(approvals.approve(Actor.console(), staged.id()).approved(),
				"and still cannot approve, because the question is who is answerable");
	}

	@Test
	@DisplayName("the console can still stage, because proposing is not approving")
	void consoleMayPropose() {
		// Deliberate. An automated job that proposes a mass rollback for a human to confirm
		// is a perfectly good arrangement; it is the confirmation that needs a name behind it.
		Approvals.Staged staged = approvals.stage(Actor.console(),
				Approvals.Action.MASS_ROLLBACK, "nightly cleanup", "detail");

		assertTrue(approvals.find(staged.id()).isPresent());
		assertTrue(approve(UUID.randomUUID(), "Bob", staged.id()).approved(),
				"and a person can confirm what the console proposed");
	}

	@Test
	@DisplayName("a linked Discord account cannot approve, because approving is running it")
	void discordCannotRunAStagedAction() {
		UUID alice = UUID.randomUUID();
		Approvals.Staged staged = stage(alice, "Alice");

		// Accountable, holding the node, and somebody other than the stager: every condition an
		// in-game approval needs. Still refused, because the approval is what executes the IP ban
		// or the rollback, and those do not run from Discord.
		Actor bob = Actor.of(UUID.randomUUID(), "Bob", Actor.Source.DISCORD_LINKED,
				java.util.Set.of(Nodes.APPROVE));
		assertTrue(bob.isAccountable());

		Approvals.Outcome outcome = approvals.approve(bob, staged.id());
		assertFalse(outcome.approved(), "a staged action was approved from Discord");
		assertTrue(outcome.refusal().contains("Discord"), outcome.refusal());
		assertTrue(approvals.find(staged.id()).isPresent(),
				"the refused approval consumed the staged action, so a Discord click cancelled it");

		// And the same person in game can.
		assertTrue(approve(bob.id(), "Bob", staged.id()).approved());
	}

	@Test
	@DisplayName("the three guarded actions are the irreversible ones")
	void theRightActionsAreGuarded() {
		var actions = List.of(Approvals.Action.values());
		assertEquals(3, actions.size(),
				"the guarded set grew. Each addition costs a second person being online, so "
						+ "it should be a decision rather than a default.");

		assertTrue(actions.contains(Approvals.Action.MASS_ROLLBACK));
		assertTrue(actions.contains(Approvals.Action.IP_BAN));
		assertTrue(actions.contains(Approvals.Action.INVENTORY_EDIT));
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Stages as somebody, calling the real method.
	 * <p>
	 * {@code Approvals} takes an identity rather than a {@code ServerPlayer} precisely so this
	 * is possible. An earlier version of this test reimplemented the approve logic through
	 * reflection because the signature demanded a player — which would have made it a test of
	 * a copy, passing happily while the real branch was broken. That is the mistake this whole
	 * project keeps finding, so the code moved rather than the test.
	 */
	private Approvals.Staged stage(UUID who, String name) {
		return approvals.stage(person(who, name), Approvals.Action.MASS_ROLLBACK,
				"roll back 4000 blocks at spawn", "detail");
	}

	private Approvals.Outcome approve(UUID who, String name, String id) {
		return approvals.approve(person(who, name), id);
	}

	/** A staff member, built without a server. This is what Actor is for. */
	private static Actor person(UUID who, String name) {
		return Actor.of(who, name, Actor.Source.PLAYER, java.util.Set.of(Nodes.APPROVE));
	}

	// ------------------------------------------------- the 2.3 guards, same door

	@Test
	@DisplayName("the rank and self guards sit in the service, beside the rate limit")
	void theNewGuardsAreWhereTheOldOneIs() throws Exception {
		// Same argument as the rate limit, and the same failure if it moves: a rank check in
		// /staff ban leaves the GUI and every later path able to punish upwards, and the one
		// that gets forgotten is the one somebody finds.
		String punish = Files.readString(Path.of(
				"src/main/java/io/github/alphain24/staffcore/modules/punish/PunishmentModule.java"));

		assertTrue(punish.contains("Rank.mayPunish"),
				"PunishmentModule.apply must check rank itself. Moved to the command, the "
						+ "GUI path punishes upwards and nothing here notices.");

		String grief = Files.readString(Path.of(
				"src/main/java/io/github/alphain24/staffcore/modules/grief/GriefModule.java"));
		assertTrue(grief.contains("RegionLock.acquire"),
				"GriefModule.rollback must take the region lock itself, for the same reason "
						+ "it checks its own rate limit.");
	}

	@Test
	@DisplayName("every punishment call site hands over an identity, not a name")
	void nothingPunishesAnonymously() throws IOException {
		// The guards key on the acting identity. A call site passing null gets the console
		// exemption and skips all of them — which is correct for the console and is a hole
		// anywhere else, so the shape to check is that nobody calls the funnel without one.
		List<String> anonymous = new ArrayList<>();
		Path source = Path.of("src", "main", "java");

		Pattern call = Pattern.compile("punish\\(\\)\\.apply\\(");

		try (Stream<Path> files = Files.walk(source)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String relative = source.relativize(file).toString().replace('\\', '/');
				String body = Files.readString(file, StandardCharsets.UTF_8);

				Matcher m = call.matcher(body);
				while (m.find()) {
					String args = arguments(body, m.end() - 1);
					if (args != null && !args.contains("Actor.of")
							&& !passesDeclaredActor(body, args)) {
						anonymous.add(relative + "  " + condense(args));
					}
				}
			}
		}

		assertTrue(anonymous.isEmpty(),
				"A punishment is issued without an acting identity:\n  "
						+ String.join("\n  ", anonymous)
						+ "\n\nThe rate limit, the rank guard and the self guard all key on it. "
						+ "Without one the call takes the console exemption, which is right for "
						+ "the console and a bypass anywhere else.");
	}

	@Test
	@DisplayName("undo runs the one reversal each kind has, behind that kind's own node")
	void undoDoesNotReimplementAnything() throws IOException {
		// A second implementation of "put the blocks back" is a second thing that can
		// disagree with the first, and the one that disagrees is the one nobody tested. It is
		// also how a reversal ends up without the permission check and audit row it needs —
		// this test exists because the bypass test itself once had that bug.
		//
		// It used to require undo to dispatch through /staff rollback undo and /staff owed undo.
		// Those were duplicates of /staff undo and are gone, so the property is asserted
		// directly: one implementation of each reversal, which writes its own audit row, and
		// undo reaching it only after checking the node the removed commands required.
		String commands = Files.readString(Path.of(
				"src/main/java/io/github/alphain24/staffcore/command/StaffCommands.java"));

		assertEquals(1, occurrences(commands, "private static int undoRollback("),
				"there should be exactly one way rollbacks are undone");
		assertEquals(1, occurrences(commands, "private static int owedUndo("),
				"there should be exactly one way a debit is given back");
		assertTrue(body(commands, "private static int undoRollback(").contains("audit(ctx"),
				"undoing a rollback writes no audit row");
		assertTrue(body(commands, "private static int owedUndo(").contains("audit(ctx"),
				"giving back a debit writes no audit row");

		String undo = body(commands, "private static int undoRef(");
		int rollback = undo.indexOf("undoRollback(ctx");
		int debit = undo.indexOf("owedUndo(ctx");
		assertTrue(rollback > 0 && debit > 0,
				"/staff undo should reach the one reversal each kind has, not a copy of it");
		assertTrue(undo.substring(0, rollback).contains("Nodes.ROLLBACK"),
				"/staff undo R-<n> reverses a rollback without checking grief.rollback first");
		assertTrue(undo.substring(rollback, debit).contains("Nodes.ROLLBACK"),
				"/staff undo I-<n> gives back a debit without checking grief.rollback first");
	}

	private static int occurrences(String text, String needle) {
		int count = 0;
		for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) count++;
		return count;
	}

	/** From a method's signature to the next method's, which is enough to read what it calls. */
	private static String body(String text, String signature) {
		int start = text.indexOf(signature);
		assertTrue(start >= 0, "missing: " + signature);
		int next = text.indexOf("\n\tprivate static ", start + signature.length());
		int nextPublic = text.indexOf("\n\tpublic static ", start + signature.length());
		int end = next < 0 ? text.length() : next;
		if (nextPublic >= 0 && nextPublic < end) end = nextPublic;
		return text.substring(start, end);
	}

	/** The argument list of a call, given the index of its opening bracket. */
	/**
	 * Whether the last argument is a variable the same file declares as an {@code Actor} — a
	 * parameter handed down from whoever resolved it. A literal null, a name string or a
	 * field are still flagged; an identity that was resolved one call up is not anonymous.
	 */
	private static boolean passesDeclaredActor(String body, String args) {
		int comma = args.lastIndexOf(',');
		String last = (comma < 0 ? args : args.substring(comma + 1)).trim();
		if (!last.matches("[A-Za-z_]\\w*") || last.equals("null")) return false;
		return Pattern.compile("\\bActor\\s+" + last + "\\b").matcher(body).find();
	}

	private static String arguments(String body, int openIndex) {
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;

		for (int i = openIndex; i < body.length(); i++) {
			char c = body.charAt(i);
			if (inString) {
				if (escaped) escaped = false;
				else if (c == '\\') escaped = true;
				else if (c == '"') inString = false;
				continue;
			}
			switch (c) {
				case '"' -> inString = true;
				case '(' -> depth++;
				case ')' -> {
					depth--;
					if (depth == 0) return body.substring(openIndex + 1, i);
				}
				default -> { }
			}
		}
		return null;
	}

	private static String condense(String args) {
		String flat = args.replaceAll("\\s+", " ").trim();
		return flat.length() > 90 ? flat.substring(0, 87) + "..." : flat;
	}
}
