package io.github.alphain24.staffcore.diagnostic;

import io.github.alphain24.staffcore.StaffCore;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies at boot that everything StaffCore hooks into still exists.
 * <p>
 * Almost every mixin in this mod is deliberately non-fatal: a Minecraft update that moves
 * one injection point should cost you that feature, not your server. The problem with that
 * trade is the failure is <em>silent</em> — vanish stops hiding people from the tab list and
 * nothing anywhere says so, which is exactly how a moderation tool becomes untrustworthy.
 * <p>
 * This closes that gap without giving up the uptime. Every method a non-fatal mixin targets
 * is looked up reflectively at start. Anything missing is reported once, loudly, by name,
 * with the feature it belongs to — so the failure mode becomes "the log told me freeze
 * broke" instead of "freeze doesn't work and nobody knows why".
 * <p>
 * Two questions get asked, not one. <em>Is the target still there</em> catches the common
 * failure, which is Mojang renaming something. <em>Did our code actually get injected into
 * it</em> catches the rest — a mixin can fail to apply while its target sits untouched, and
 * that failure is otherwise completely silent.
 * <p>
 * The second question is answered conservatively, and on purpose. It reports a problem only
 * when it can positively establish one; every other outcome is silence. A diagnostic that
 * fires on a healthy server trains everybody to ignore it, which costs more than never
 * having written it — see {@link #applied} for what it takes as proof either way.
 */
public final class StartupCheck {
	private StartupCheck() {}

	/**
	 * One thing we hook, and what breaks if it has moved.
	 *
	 * @param handler the name of our injected handler method, or null where there is nothing
	 *                to look for. Together with {@code mixinClass} this is what turns the
	 *                check from "the method still exists" into "our code is actually in it".
	 * @param mixinClass the mixin that should have contributed that handler, matched against
	 *                Mixin's own merge metadata — see {@link #applied}
	 */
	private record Target(Tier tier, String feature, String className, String method,
			String handler, String mixinClass, String... paramTypes) {}

	/**
	 * How much a hook failing to apply matters.
	 * <p>
	 * Two tiers was one too few. The login gate is {@code required: true} and takes the
	 * server down with it; the other thirty are {@code defaultRequire: 0} and cost you a
	 * feature. That is the right trade for command spy, and the wrong one for the hooks that
	 * fill the grief log — because a missing logging hook does not switch a feature off, it
	 * leaves the feature running on data that is quietly incomplete.
	 * <p>
	 * That distinction is the whole point. A rollback computed from a block log with the
	 * PLACE half missing does not do nothing; it restores the wrong blocks and charges
	 * somebody for them. Degraded operation is fine when the degradation is visible and
	 * bounded, and unacceptable when it silently changes what a correct-looking answer means.
	 */
	public enum Tier {
		/** The server must not run without it. Fails at load, loudly. */
		REQUIRED,
		/**
		 * The owning feature hard-disables if this does not apply. Commands refuse and name
		 * the broken hook; {@code InventoryGateway} rejects writes from the subsystem.
		 */
		IMPORTANT,
		/** Costs you that feature and nothing else. The old behaviour for everything. */
		OPTIONAL
	}

	private static final String MIXIN_PKG = "io.github.alphain24.staffcore.mixin.";

	private static final List<Target> TARGETS = List.of(
			new Target(Tier.REQUIRED, "Bans and maintenance login gate", "net.minecraft.server.players.PlayerList",
					"canPlayerLogin", "staffcore$gateLogin", MIXIN_PKG + "PlayerListMixin",
					"java.net.SocketAddress", "net.minecraft.server.players.NameAndId"),
			new Target(Tier.OPTIONAL, "Vanish — join suppression and tab list", "net.minecraft.server.players.PlayerList",
					"placeNewPlayer", "staffcore$restoreVanishEarly", MIXIN_PKG + "VanishJoinMixin",
					"net.minecraft.network.Connection",
					"net.minecraft.server.level.ServerPlayer",
					"net.minecraft.server.network.CommonListenerCookie"),
			new Target(Tier.OPTIONAL, "Vanish — leave suppression", "net.minecraft.server.players.PlayerList",
					"remove", "staffcore$forgetVanishAfterLeaveLine", MIXIN_PKG + "VanishLeaveMixin",
					"net.minecraft.server.level.ServerPlayer"),
			new Target(Tier.OPTIONAL, "Vanish — tab-list withholding", "net.minecraft.server.players.PlayerList",
					"broadcastAll", null, null, "net.minecraft.network.protocol.Packet"),
			new Target(Tier.OPTIONAL, "Vanish — announcement suppression", "net.minecraft.server.players.PlayerList",
					"broadcastSystemMessage", "staffcore$suppressVanishedBroadcast",
					MIXIN_PKG + "VanishJoinMixin",
					"net.minecraft.network.chat.Component", "boolean"),
			new Target(Tier.OPTIONAL, "Vanish — locator bar", "net.minecraft.server.waypoints.ServerWaypointManager",
					"createConnection", null, null, "net.minecraft.server.level.ServerPlayer",
					"net.minecraft.world.waypoints.WaypointTransmitter"),
			new Target(Tier.OPTIONAL, "Vanish — server-list ping", "net.minecraft.server.MinecraftServer",
					"buildPlayerStatus", "staffcore$hideFromPing",
					MIXIN_PKG + "vanish.VanishStatusMixin"),
			// On LivingEntity, not Entity. It was on Entity and did nothing: LivingEntity
			// overrides isPushable, so the override won for every player and every mob while
			// the health check happily reported the target present.
			new Target(Tier.OPTIONAL, "Vanish — entity collision",
					"net.minecraft.world.entity.LivingEntity",
					"isPushable", "staffcore$notPushable",
					MIXIN_PKG + "vanish.VanishLivingCollisionMixin"),
			new Target(Tier.OPTIONAL, "Vanish — block placement", "net.minecraft.world.entity.Entity",
					"blocksBuilding", null, null),
			// The one that actually makes a vanished player invisible. Worth verifying by
			// handler rather than by target alone: the target is a public vanilla method
			// that will not vanish, so "it exists" proves nothing about our code being in it.
			new Target(Tier.OPTIONAL, "Vanish — entity visibility", "net.minecraft.world.entity.Entity",
					"broadcastToPlayer", "staffcore$hideFromTracker",
					MIXIN_PKG + "vanish.VanishBroadcastMixin",
					"net.minecraft.server.level.ServerPlayer"),
			new Target(Tier.OPTIONAL, "Vanish — /list", "net.minecraft.server.commands.ListPlayersCommand",
					"format", "staffcore$hideFromList",
					MIXIN_PKG + "vanish.VanishPlayerListCommandMixin",
					"net.minecraft.commands.CommandSourceStack", "java.util.function.Function"),
			new Target(Tier.OPTIONAL, "Vanish — chunk loading", "net.minecraft.server.level.DistanceManager",
					"addPlayer", "staffcore$skipSpawnCounting",
					MIXIN_PKG + "vanish.VanishSpawnChunkMixin",
					"net.minecraft.core.SectionPos", "net.minecraft.server.level.ServerPlayer"),
			new Target(Tier.OPTIONAL, "Command spy", "net.minecraft.commands.Commands",
					"performCommand", "staffcore$spy", MIXIN_PKG + "CommandsMixin",
					"com.mojang.brigadier.ParseResults", "java.lang.String"),
			new Target(Tier.IMPORTANT, "Staff tools cannot be dropped", "net.minecraft.world.entity.player.Inventory",
					"dropAll", "staffcore$keepToolsOutOfTheWorld", MIXIN_PKG + "InventoryDropMixin"),
			// Without this, creeper and TNT damage never reaches the log at all, and the area
			// reads as though nothing happened there.
			new Target(Tier.IMPORTANT, "Explosion damage log", "net.minecraft.world.level.ServerExplosion",
					"interactWithBlocks", "staffcore$recordExplosion", MIXIN_PKG + "ExplosionMixin",
					"java.util.List"),
			// Fire is the classic griefing tool, and without this the blocks it eats are
			// never recorded - only the flint-and-steel that started it.
			new Target(Tier.IMPORTANT, "Fire damage log", "net.minecraft.world.level.block.FireBlock",
					"checkBurnOut", "staffcore$recordBurnToAir", MIXIN_PKG + "FireSpreadMixin",
					"net.minecraft.world.level.Level", "net.minecraft.core.BlockPos", "int",
					"net.minecraft.util.RandomSource", "int"),
			// Without this, item recovery can only search the ground nearby: it misses
			// anything already in somebody's pocket and anything in an unloaded chunk.
			new Target(Tier.IMPORTANT, "Item pickup log", "net.minecraft.world.entity.item.ItemEntity",
					"playerTouch", "staffcore$recordPickup", MIXIN_PKG + "ItemPickupMixin",
					"net.minecraft.world.entity.player.Player"),
			// The PLACE half of the grief log. Never checked until now, which mattered more
			// than any of the ones that were: with it missing the log records breaks and not
			// placements, so a rollback inverts half a history. Blocks somebody put down stay
			// down and the area is reported as though they only ever took things away.
			new Target(Tier.IMPORTANT, "Grief log - block placement",
					"net.minecraft.world.item.BlockItem",
					"place", "staffcore$logPlace", MIXIN_PKG + "BlockItemMixin",
					"net.minecraft.world.item.context.BlockPlaceContext"),
			// What makes container theft visible. Without it the container log never learns
			// what changed inside a chest, so the theft undo runs on nothing and reports that
			// nothing was taken.
			new Target(Tier.IMPORTANT, "Container log - open and close",
					"net.minecraft.server.level.ServerPlayer",
					"doCloseContainer", "staffcore$recordContainerChanges",
					MIXIN_PKG + "ContainerCloseMixin")
	);

	/**
	 * Runs the check and logs the outcome.
	 *
	 * @return the features whose hooks have gone missing, empty when everything is present
	 */
	/** How one hooked feature came out of the check. */
	public enum Health {
		/** The target is there and our code is in it. */
		WORKING,
		/** The target method has moved or gone. */
		MISSING,
		/** The target is there but the mixin did not apply. */
		NOT_APPLIED,
		/** Present, and this build cannot prove either way. */
		UNVERIFIED
	}

	/** One line of the health report, for the log and for the in-game screen alike. */
	public record Finding(String feature, String className, String method, Health health,
			String detail, Tier tier) {

		public boolean isBroken() {
			return health == Health.MISSING || health == Health.NOT_APPLIED;
		}

		/** Broken, and broken in a way that must stop the owning feature running. */
		public boolean disablesFeature() {
			return isBroken() && tier == Tier.IMPORTANT;
		}
	}

	/**
	 * Features that must not run, because a hook they depend on did not apply.
	 * <p>
	 * Feature granularity rather than a global switch, because the alternative is choosing
	 * between two bad answers: take the whole mod down over a broken vanish hook, or let a
	 * rollback run on a block log that is missing its PLACE half. Neither is the right
	 * response to one injection point moving.
	 */
	private static java.util.Set<String> disabled = java.util.Set.of();

	/** Whether a named feature has been switched off by a failed hook. */
	public static boolean isDisabled(String feature) {
		return disabled.contains(feature);
	}

	/** Every feature currently switched off, for a command that needs to explain itself. */
	public static java.util.Set<String> disabledFeatures() {
		return disabled;
	}

	/**
	 * The reason a feature is off, phrased for somebody who has to fix it.
	 * <p>
	 * Naming the hook is the point. "Rollback is unavailable" sends an admin looking through
	 * config; "BlockItemMixin did not apply to BlockItem#place" tells them it is a Minecraft
	 * update and which injection point moved.
	 */
	public static String whyDisabled(String feature) {
		for (Finding finding : report) {
			if (finding.feature().equals(feature) && finding.disablesFeature()) {
				return finding.feature() + " is unavailable: " + finding.detail()
						+ " (" + finding.className() + "#" + finding.method() + ")";
			}
		}
		return null;
	}

	private static List<Finding> report = List.of();

	/**
	 * The last health report. Empty until the check has run.
	 * <p>
	 * Kept because a log line scrolls past during boot and is gone; an admin who wants to
	 * know whether vanish is working an hour later has nowhere else to look.
	 */
	public static List<Finding> report() {
		return report;
	}

	public static List<String> run() {
		List<String> broken = new ArrayList<>();
		List<Finding> findings = new ArrayList<>();
		int verified = 0;
		int unconfirmed = 0;

		for (Target target : TARGETS) {
			if (!exists(target)) {
				broken.add(target.feature());
				findings.add(new Finding(target.feature(), simple(target.className()),
						target.method(), Health.MISSING,
						"The method this hooks has moved or been removed.", target.tier()));
				StaffCore.LOGGER.error("[StaffCore] Missing hook: {}#{} - \"{}\" will not work",
						simple(target.className()), target.method(), target.feature());
				continue;
			}

			// The target is still there. Whether our code is in it is a separate question,
			// and the more useful one — a mixin can fail to apply for reasons that have
			// nothing to do with the method having moved.
			if (target.handler() == null) {
				findings.add(new Finding(target.feature(), simple(target.className()),
						target.method(), Health.WORKING, "Target present.", target.tier()));
				continue;
			}

			switch (applied(target)) {
				case YES -> {
					verified++;
					findings.add(new Finding(target.feature(), simple(target.className()),
							target.method(), Health.WORKING, "Applied and verified.", target.tier()));
				}
				case NO -> {
					broken.add(target.feature());
					String why = MixinFailureRecorder.reasonFor(target.mixinClass());
					findings.add(new Finding(target.feature(), simple(target.className()),
							target.method(), Health.NOT_APPLIED,
							why != null ? why : simple(target.mixinClass()) + " did not apply.",
							target.tier()));

					StaffCore.LOGGER.error(
							"[StaffCore] \"{}\" is broken - {} did not apply to {}#{}",
							target.feature(), simple(target.mixinClass()),
							simple(target.className()), target.method());
					if (why != null) StaffCore.LOGGER.error("[StaffCore]     {}", why);
				}
				// Not evidence of a problem — only that this build cannot tell either way.
				// Debug, not warn: an admin reading the log should not have to decide whether
				// a line they cannot act on matters.
				case UNKNOWN -> {
					unconfirmed++;
					findings.add(new Finding(target.feature(), simple(target.className()),
							target.method(), Health.UNVERIFIED,
							"Target present; could not confirm the mixin applied.", target.tier()));
					StaffCore.LOGGER.debug("[StaffCore] Could not confirm \"{}\" applied to {}#{}",
							target.feature(), simple(target.className()), target.method());
				}
			}
		}

		// A mixin can fail without owning a hook in the table above — the table covers the
		// ones worth naming a feature for, not all thirty. Reporting the rest by class name
		// beats letting them fail silently just because nobody wrote them an entry.
		for (var entry : MixinFailureRecorder.failures().entrySet()) {
			boolean named = TARGETS.stream()
					.anyMatch(t -> entry.getKey().equals(t.mixinClass()));
			if (named) continue;

			broken.add(simple(entry.getKey()));
			// Tier unknown: nobody wrote this one an entry, so there is nothing that says how
			// much it matters. Optional is the honest guess and the safe one — treating an
			// unclassified failure as important would take features down on a guess.
			findings.add(new Finding(simple(entry.getKey()), "—", "—",
					Health.NOT_APPLIED, entry.getValue(), Tier.OPTIONAL));
			StaffCore.LOGGER.error("[StaffCore] {} did not apply", simple(entry.getKey()));
			StaffCore.LOGGER.error("[StaffCore]     {}", entry.getValue());
		}

		report = List.copyOf(findings);

		// Anything IMPORTANT that did not apply switches its feature off here, before the
		// first command can run against data it should not trust.
		java.util.Set<String> off = new java.util.LinkedHashSet<>();
		for (Finding finding : findings) {
			if (finding.disablesFeature()) off.add(finding.feature());
		}
		disabled = java.util.Set.copyOf(off);

		for (String feature : disabled) {
			StaffCore.LOGGER.error("[StaffCore] DISABLED: \"{}\" will refuse to run rather than "
					+ "work from incomplete data.", feature);
		}

		summarise(broken, verified, unconfirmed);
		return broken;
	}

	/**
	 * One readable block instead of a wall of stack traces.
	 * <p>
	 * Mixin already prints the full failure above, at length and three times over. What an
	 * admin needs from us is the count and the list — whether this is "everything is fine"
	 * or "two features are off, here they are" — which is a decision they can act on in one
	 * glance rather than by reading four hundred lines of injector internals.
	 */
	private static void summarise(List<String> broken, int verified, int unconfirmed) {
		int total = TARGETS.size();

		if (broken.isEmpty()) {
			StaffCore.LOGGER.info("[StaffCore] Health check: {}/{} hooks present, {} verified applied{}.",
					total, total, verified,
					unconfirmed == 0 ? "" : " (" + unconfirmed + " unverifiable)");
			return;
		}

		StaffCore.LOGGER.error("[StaffCore] ---------------------------------------------");
		StaffCore.LOGGER.error("[StaffCore] Health check: {}/{} features working. {} broken:",
				total - broken.size(), total, broken.size());
		for (String feature : broken) {
			StaffCore.LOGGER.error("[StaffCore]   - {}", feature);
		}
		StaffCore.LOGGER.error("[StaffCore] The server runs normally without them.");
		StaffCore.LOGGER.error("[StaffCore] Full detail is in the Mixin warnings above.");
		StaffCore.LOGGER.error("[StaffCore] ---------------------------------------------");
	}

	/** Whether our code actually made it into the target method. */
	private enum Applied { YES, NO, UNKNOWN }

	/**
	 * Whether the mixin behind this hook actually applied.
	 * <p>
	 * Asked of Mixin rather than inferred, and the history is worth keeping because both
	 * inferences were wrong in opposite directions. Looking for the handler under its
	 * declared name reported every working mixin as broken, because Mixin renames merged
	 * handlers. Loosening the match then reported two genuinely broken mixins as fine —
	 * Mixin merges the handler method into the target <em>before</em> wiring the injection
	 * up, so a failed wiring leaves the method sitting there to be found. The presence of a
	 * handler proves the mixin was processed, not that it took effect, and no reflective
	 * signal distinguishes those cases because the difference is in the target's bytecode.
	 * <p>
	 * {@link MixinFailureRecorder} takes the answer straight from Mixin's own
	 * {@code onApplyError} callback instead. A hook whose mixin is not in that list, and
	 * whose target method is still present, is working.
	 * <p>
	 * {@code UNKNOWN} is reserved for a target we cannot even load. Everything else is now
	 * a definite answer, which is the point — a diagnostic nobody can trust is worse than
	 * none, and this one has been on both sides of that.
	 */
	private static Applied applied(Target target) {
		try {
			Class.forName(target.className(), false, StartupCheck.class.getClassLoader());
		} catch (ClassNotFoundException | RuntimeException | LinkageError e) {
			return Applied.UNKNOWN;
		}
		return MixinFailureRecorder.failed(target.mixinClass()) ? Applied.NO : Applied.YES;
	}

	/** True when the named method or field is still present on the class. */
	private static boolean exists(Target target) {
		try {
			// initialize = false: this is a diagnostic, and it has no business running a
			// class's static initialisers as a side effect of looking at its methods.
			Class<?> owner = Class.forName(target.className(), false,
					StartupCheck.class.getClassLoader());

			// A target with no parameter list may be a field — checked first so
			// `blocksBuilding` and friends can be verified the same way as methods.
			if (target.paramTypes().length == 0 && hasField(owner, target.method())) {
				return true;
			}
			for (java.lang.reflect.Method m : owner.getDeclaredMethods()) {
				if (m.getName().equals(target.method()) && matches(m, target.paramTypes())) return true;
			}
			return false;
		} catch (ClassNotFoundException e) {
			return false;
		} catch (RuntimeException | LinkageError e) {
			// A check that throws must not be the reason a server fails to start.
			StaffCore.LOGGER.warn("[StaffCore] Could not verify {}: {}", target.feature(), e.toString());
			return true;
		}
	}

	private static boolean hasField(Class<?> owner, String name) {
		for (java.lang.reflect.Field f : owner.getDeclaredFields()) {
			if (f.getName().equals(name)) return true;
		}
		return false;
	}

	private static boolean matches(java.lang.reflect.Method m, String[] paramTypes) {
		Class<?>[] actual = m.getParameterTypes();
		if (actual.length != paramTypes.length) return false;
		for (int i = 0; i < actual.length; i++) {
			if (!actual[i].getName().equals(paramTypes[i])
					&& !actual[i].getSimpleName().equals(paramTypes[i])) {
				return false;
			}
		}
		return true;
	}

	private static String simple(String className) {
		int dot = className.lastIndexOf('.');
		return dot < 0 ? className : className.substring(dot + 1);
	}
}
