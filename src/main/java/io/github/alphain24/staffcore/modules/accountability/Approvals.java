package io.github.alphain24.staffcore.modules.accountability;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.permission.Accountable;
import io.github.alphain24.staffcore.permission.Actor;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two people, for the three things that cannot be undone by saying sorry.
 * <p>
 * A mass rollback, an IP ban and an inventory edit share a property the rest of the mod does
 * not: each can be catastrophic, each is easy to aim at the wrong target, and none of them is
 * reversible by a second command. A mass rollback overwrites whatever is genuinely there now;
 * an IP ban catches a household or, under CGNAT, a town; an inventory edit destroys items that
 * only existed in that inventory.
 * <p>
 * So one staff member stages the action and a second confirms it. The value is not really the
 * permission check — it is the second pair of eyes reading what is about to happen while
 * somebody says it out loud.
 *
 * <h2>Nobody approves their own</h2>
 * A staff member holding both nodes still cannot confirm their own staged action. That is the
 * whole mechanism: an approval you can grant yourself is a confirmation prompt with extra
 * steps, and the failure it is written for — one account, compromised or angry, acting alone —
 * is exactly the case where self-approval would let it through.
 * <p>
 * <b>The cost is real and worth stating.</b> On a server with one admin online, these three
 * operations are unavailable until somebody else logs in. That is the trade, it is deliberate,
 * and {@code requireTwoPersonApproval} turns it off for servers that would rather not make it.
 */
public final class Approvals {

	/** What kinds of action need a second person. */
	public enum Action {
		MASS_ROLLBACK("mass rollback"),
		IP_BAN("IP ban"),
		INVENTORY_EDIT("inventory edit");

		private final String label;

		Action(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/** An action waiting for a second person. */
	public record Staged(String id, Action action, UUID stagedBy, String stagedByName,
			String summary, String detail, long stagedAt) {

		public boolean isExpired() {
			long minutes = StaffConfig.get().approvalExpiryMinutes;
			return minutes > 0 && System.currentTimeMillis() - stagedAt > minutes * 60_000L;
		}
	}

	/** Why an approval was refused, or the staged action it unlocked. */
	public record Outcome(boolean approved, Staged staged, String refusal) {

		static Outcome refused(String why) {
			return new Outcome(false, null, why);
		}
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * Staged actions, in memory.
	 * <p>
	 * Deliberately not persisted. An approval that survives a restart is one somebody staged
	 * before the server went down and a second person confirms afterwards without either of
	 * them remembering the context — and the context is the entire value of the mechanism.
	 * Losing them on restart is the safe direction to fail.
	 */
	private final Map<String, Staged> pending = new ConcurrentHashMap<>();

	/** Whether this action needs a second person at all. */
	public boolean required(Action action) {
		return StaffConfig.get().requireTwoPersonApproval;
	}

	/**
	 * Records an intention and hands back the id a second person will quote.
	 *
	 * @param summary one line, shown to the approver — this is what they are agreeing to
	 * @param detail  everything else, for the audit row
	 */
	public Staged stage(Actor staff, Action action, String summary, String detail) {
		expireOld();

		String id = newId();
		Staged staged = new Staged(id, action, staff.id(), staff.name(), summary, detail,
				System.currentTimeMillis());
		pending.put(id, staged);

		StaffCore.LOGGER.info("[Approvals] {} staged a {} ({}): {}",
				staff.name(), action.label(), id, summary);
		return staged;
	}

	/**
	 * A second staff member confirms.
	 * <p>
	 * The self-approval refusal is the one branch worth reading twice. It fires on UUID, not
	 * on name, because a name can be changed and the whole point is that the same
	 * <em>person</em> cannot do both halves.
	 */
	public Outcome approve(Actor approver, String id) {
		// The accountability rule, applied once and in terms of the property rather than the
		// mechanism. Console, RCON, a scheduled task and an unlinked Discord user are the same
		// case: each holds every permission and belongs to no account, so each would satisfy
		// the letter of a two-person check and none of its purpose.
		//
		// Stated here rather than as a `getPlayer() == null` in the command, because the
		// command is only one of the ways in and the next one would arrive without it.
		var accountable = Accountable.require(approver, "approve a staged action");
		if (accountable.refused()) return Outcome.refused(accountable.refusal());

		expireOld();

		Staged staged = pending.get(id);
		if (staged == null) {
			return Outcome.refused("No staged action " + id
					+ ". It may have expired — they are only held for "
					+ StaffConfig.get().approvalExpiryMinutes + " minutes.");
		}

		// Null-safe because the stager may have had no identity: the console is allowed to
		// propose, and nobody can be the console, so an identity-less stager can never collide
		// with an approver — who by this point is guaranteed accountable and therefore has an
		// id. Dereferencing it blindly was an NPE on exactly the path the design intends.
		if (staged.stagedBy() != null && staged.stagedBy().equals(approver.id())) {
			// Holding both nodes does not help. An approval somebody can grant themselves is
			// a confirmation prompt wearing a costume, and the situation this exists for is
			// exactly one account acting alone.
			return Outcome.refused("You staged this yourself. A second staff member has to "
					+ "confirm it — that is the whole point of the check, and holding the "
					+ "approval permission does not change it.");
		}

		pending.remove(id);
		StaffCore.LOGGER.info("[Approvals] {} approved {}'s {} ({})",
				approver.name(), staged.stagedByName(), staged.action().label(), id);
		return new Outcome(true, staged, null);
	}

	/** Drops a staged action without running it. */
	public boolean cancel(String id, UUID by) {
		Staged staged = pending.get(id);
		if (staged == null) return false;

		// Anybody can cancel: refusing to let a second person stop something dangerous would
		// be a strange reading of a two-person rule.
		pending.remove(id);
		return true;
	}

	public Optional<Staged> find(String id) {
		expireOld();
		return Optional.ofNullable(pending.get(id));
	}

	/** Everything waiting, for the approver to see what is outstanding. */
	public java.util.List<Staged> waiting() {
		expireOld();
		return java.util.List.copyOf(pending.values());
	}

	private void expireOld() {
		pending.values().removeIf(Staged::isExpired);
	}

	/**
	 * Short, unguessable, and not sequential.
	 * <p>
	 * Guessable ids would let somebody approve an action they never saw, which is the same
	 * failure as self-approval reached a different way.
	 */
	private static String newId() {
		byte[] bytes = new byte[4];
		RANDOM.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes).toUpperCase(java.util.Locale.ROOT);
	}
}
