package io.github.alphain24.staffcore.modules.security;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.modules.staffmode.StaffToolset;
import io.github.alphain24.staffcore.module.Module;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Passive checks that answer "is this player's stuff possible?".
 * <p>
 * Every finding is a <em>flag</em>, never an action. These heuristics catch honest
 * accidents (a creative-mode test item that escaped) as often as they catch cheating,
 * so the module's job ends at putting evidence in front of a human.
 */
public class SecurityModule implements Module {

	@Override
	public String id() {
		return "security";
	}

	@Override
	public String displayName() {
		return "Security";
	}

	/** Confiscated items are held here rather than destroyed. */
	private final ContrabandVault vault = new ContrabandVault();

	public ContrabandVault vault() {
		return vault;
	}

	public enum Severity { INFO, SUSPICIOUS, IMPOSSIBLE }

	public record Flag(Severity severity, String kind, String detail) {
		public int color() {
			return switch (severity) {
				case INFO -> Theme.MUTED;
				case SUSPICIOUS -> Theme.WARN;
				case IMPOSSIBLE -> Theme.BAD;
			};
		}
	}

	// ---------------------------------------------------------------- full sweep

	public List<Flag> check(ServerPlayer target) {
		List<Flag> flags = new ArrayList<>(scanItems(target));
		flags.addAll(scanMining(target));
		if (flags.isEmpty()) {
			flags.add(new Flag(Severity.INFO, "CLEAN", "Nothing out of the ordinary."));
		}
		return flags;
	}

	// ------------------------------------------------------------- compiled rules

	private StaffConfig rulesFrom;
	private IllegalItems.Ruleset illegalRules = IllegalItems.Ruleset.EMPTY;
	private IllegalItems.Ruleset operatorRules = IllegalItems.Ruleset.EMPTY;

	/**
	 * The configured lists, resolved against the registry once per config load.
	 * <p>
	 * This used to resolve on every call — and {@link #isIllegal} is called per stack, so
	 * scanning one inventory parsed the whole list forty-three times. A reload replaces the
	 * config object, so comparing identity is enough to know the compiled copy is stale.
	 */
	private void ensureRules() {
		StaffConfig cfg = StaffConfig.get();
		if (rulesFrom == cfg) return;

		illegalRules = IllegalItems.compile(cfg.illegalItems);
		operatorRules = IllegalItems.compile(cfg.operatorItems);
		rulesFrom = cfg;

		IllegalItems.report("illegalItems", illegalRules);
		IllegalItems.report("operatorItems", operatorRules);
	}

	/** What the contraband rules currently cover, for the config screen and {@code /staff status}. */
	public IllegalItems.Ruleset illegalRules() {
		ensureRules();
		return illegalRules;
	}

	public IllegalItems.Ruleset operatorRules() {
		ensureRules();
		return operatorRules;
	}

	// ---------------------------------------------------------------- item checks

	/** Over-stacked stacks and enchantments above the vanilla ceiling. */
	public List<Flag> scanItems(ServerPlayer target) {
		List<Flag> flags = new ArrayList<>();
		StaffConfig cfg = StaffConfig.get();

		ensureRules();
		IllegalItems.Ruleset illegal = illegalRules;
		IllegalItems.Ruleset operator = operatorRules;
		Set<Item> staffTools = StaffToolset.toolItems();
		boolean onDuty = Mods.staffMode().isActive(target);

		Inventory inv = target.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			scanStack(flags, inv.getItem(i), illegal, operator, staffTools, onDuty, "inventory");
		}

		if (cfg.scanEnderChests) {
			Container ender = target.getEnderChestInventory();
			for (int i = 0; i < ender.getContainerSize(); i++) {
				scanStack(flags, ender.getItem(i), illegal, operator, staffTools, onDuty, "ender chest");
			}
		}
		return flags;
	}

	/**
	 * Checks one stack against every rule.
	 * <p>
	 * Shulkers and bundles are opened up and checked too: hiding a stack of spawners inside
	 * a shulker inside an ender chest is the obvious next move once a player learns their
	 * inventory gets scanned, and a check that stops at the top level rewards exactly that.
	 */
	private void scanStack(List<Flag> flags, ItemStack stack, IllegalItems.Ruleset illegal,
			IllegalItems.Ruleset operator, Set<Item> staffTools, boolean onDuty, String where) {

		if (stack.isEmpty()) return;

		if (stack.getCount() > Mc.maxStackSize(stack)) {
			flags.add(new Flag(Severity.IMPOSSIBLE, "OVERSTACK",
					stack.getCount() + "× " + plain(stack) + " in their " + where
							+ " (max " + Mc.maxStackSize(stack) + ")"));
		}

		if (operator.matches(stack)) {
			flags.add(new Flag(Severity.IMPOSSIBLE, "OPERATOR_ITEM",
					plain(stack) + " in their " + where + " — operator tooling"));
		} else if (illegal.matches(stack)) {
			flags.add(new Flag(Severity.IMPOSSIBLE, "ILLEGAL_ITEM",
					plain(stack) + " in their " + where + " — not obtainable in survival"));
		}

		// A staff tool outside staff mode means one leaked, which is worth knowing about
		// whether it was dropped by accident or handed over deliberately.
		if (!onDuty && staffTools.contains(stack.getItem()) && StaffToolset.isStaffTool(stack)) {
			flags.add(new Flag(Severity.IMPOSSIBLE, "STAFF_TOOL",
					plain(stack) + " in their " + where + " — a staff-mode tool outside staff mode"));
		}

		ItemEnchantments enchants = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
		for (Holder<Enchantment> holder : enchants.keySet()) {
			int level = enchants.getLevel(holder);
			int max = holder.value().getMaxLevel();
			if (level > max) {
				flags.add(new Flag(Severity.IMPOSSIBLE, "ILLEGAL_ENCHANT",
						plain(stack) + " has level " + level + " (max " + max + ")"));
			}
		}

		for (ItemStack nested : contentsOf(stack)) {
			scanStack(flags, nested, illegal, operator, staffTools, onDuty, where + " (inside " + plain(stack) + ")");
		}
	}

	/** Items stored inside a shulker box or bundle, or empty for anything else. */
	private static List<ItemStack> contentsOf(ItemStack stack) {
		List<ItemStack> out = new ArrayList<>();

		ItemContainerContents container = stack.get(DataComponents.CONTAINER);
		if (container != null) {
			container.nonEmptyItemCopyStream().forEach(out::add);
		}
		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) {
			bundle.itemCopyStream().forEach(out::add);
		}
		return out;
	}

	private static String plain(ItemStack stack) {
		return stack.getHoverName().getString();
	}

	/**
	 * True when this single stack would be flagged as impossible.
	 * <p>
	 * Used by confiscation so it takes exactly what the scan reported and nothing else —
	 * the button and the finding list can never disagree about what is about to be removed.
	 */
	public boolean isIllegal(ServerPlayer holder, ItemStack stack) {
		if (stack.isEmpty()) return false;
		StaffConfig cfg = StaffConfig.get();

		ensureRules();
		if (operatorRules.matches(stack)) return true;
		if (illegalRules.matches(stack)) return true;
		if (stack.getCount() > Mc.maxStackSize(stack)) return true;
		if (!Mods.staffMode().isActive(holder) && StaffToolset.isStaffTool(stack)) return true;

		ItemEnchantments enchants = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
		for (Holder<Enchantment> holderRef : enchants.keySet()) {
			if (enchants.getLevel(holderRef) > holderRef.value().getMaxLevel()) return true;
		}
		return false;
	}

	// -------------------------------------------------------------- mining checks

	/**
	 * X-ray heuristic over the grief log: what fraction of a player's recent mining was
	 * ore? A legitimate strip-miner sits far below the threshold because they break
	 * hundreds of stone blocks for every vein.
	 */
	public List<Flag> scanMining(ServerPlayer target) {
		List<Flag> flags = new ArrayList<>();
		XrayDetector.Report report = XrayDetector.analyse(Mc.name(target), 6L * 3_600_000L);
		if (report.confidence() == 0) return flags;

		Severity severity = report.isSuspicious() ? Severity.SUSPICIOUS : Severity.INFO;
		flags.add(new Flag(severity, "MINING",
				report.confidence() + "% confidence — " + String.join("; ", report.reasons())));
		return flags;
	}

	/** The full scored report, for the security screen's header. */
	public XrayDetector.Report miningReport(ServerPlayer target) {
		return XrayDetector.analyse(Mc.name(target), 6L * 3_600_000L);
	}

	// --------------------------------------------------------------- contraband watch

	/** How often online players are checked for contraband, in ticks. */
	private static final int CONTRABAND_INTERVAL = 40;

	private int contrabandTick;
	/** What has already been reported per player, so one bad item is one alert. */
	private final java.util.Map<java.util.UUID, java.util.Set<Item>> reported = new java.util.HashMap<>();

	/**
	 * Watches for illegal items appearing in a normal player's inventory, and tells staff
	 * the moment one does.
	 * <p>
	 * Waiting for somebody to run a security check means the first person to notice a
	 * duplicated spawner is whoever it gets used on. Two seconds is frequent enough to catch
	 * the item near where it came from — a chest, a trade, a dupe — and cheap enough that
	 * fifty players cost a few thousand set lookups, which is noise next to a single tick of
	 * entity movement.
	 * <p>
	 * Each (player, item type) pair alerts once. A player carrying a stack of bedrock
	 * around for an hour should not generate an alert every two seconds.
	 */
	private void watchContraband(MinecraftServer server) {
		StaffConfig cfg = StaffConfig.get();
		ensureRules();
		IllegalItems.Ruleset illegal = illegalRules;
		IllegalItems.Ruleset operator = operatorRules;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean onDuty = Mods.staffMode().isActive(player);

			sweepContainer(server, player, player.getInventory(), onDuty, illegal, operator, "inventory");

			// The ender chest is where anything worth hiding ends up: it follows the player,
			// no one else can open it, and it survives death. Leaving it out of the watch
			// meant the check was one click away from being defeated for good.
			if (StaffConfig.get().scanEnderChests) {
				sweepContainer(server, player, player.getEnderChestInventory(), onDuty,
						illegal, operator, "ender chest");
			}
		}
	}

	/** One pass over one of a player's own containers. */
	private void sweepContainer(MinecraftServer server, ServerPlayer player, Container held,
			boolean onDuty, IllegalItems.Ruleset illegal, IllegalItems.Ruleset operator, String where) {

		for (int i = 0; i < held.getContainerSize(); i++) {
			ItemStack stack = held.getItem(i);
			if (stack.isEmpty()) continue;

			// A staff tool outside staff mode is removed on sight, not merely reported.
			// Every route that creates one is blocked, so if one exists something went
			// wrong and leaving it in circulation compounds the problem.
			if (!onDuty && StaffToolset.isStaffTool(stack)) {
				// Vaulted rather than deleted, like every other confiscation. A leaked
				// staff tool is also the evidence of however it leaked, and destroying
				// it on sight throws that away along with the problem.
				vault.deposit(player, "system", stack.copy(),
						"Staff tool held outside staff mode (" + where + ")");

				// Through the gateway like every other removal, matched by identity so a
				// second copy of the same tool in another slot is dealt with on its own pass
				// rather than swept up under one audit row that names the wrong slot.
				ItemStack leaked = stack;
				io.github.alphain24.staffcore.inventory.InventoryGateway.removeMatching(player,
						io.github.alphain24.staffcore.inventory.InventoryGateway.Origin.CONFISCATION, "system",
						"staff tool held outside staff mode (" + where + ")",
						candidate -> candidate == leaked);

				alertOnce(server, player, stack.getItem(), "was keeping a staff tool ("
						+ plain(stack) + ") in their " + where + " — removed to the vault");
				continue;
			}

			if (onDuty) continue;

			boolean isOperator = operator.matches(stack);
			if (isOperator || illegal.matches(stack)) {
				alertOnce(server, player, stack.getItem(), "has "
						+ stack.getCount() + "× " + plain(stack) + " in their " + where
						+ (isOperator ? " — operator tooling" : " — not obtainable in survival"));
			}
		}
	}

	private void alertOnce(MinecraftServer server, ServerPlayer player, Item item, String detail) {
		Set<Item> seen = reported.computeIfAbsent(player.getUUID(), k -> new java.util.HashSet<>());
		if (!seen.add(item)) return;

		Mods.alerts().onSecurityFlag(server, Mc.name(player),
				"%s at %d, %d, %d".formatted(detail,
						player.getBlockX(), player.getBlockY(), player.getBlockZ()));
	}

	// ----------------------------------------------------------- container contraband

	/**
	 * What has already been reported at a container, so one bad chest is one alert.
	 * <p>
	 * Bounded and oldest-out: a long-running server visits a lot of containers, and a set
	 * that only ever grows is a memory leak with a moderation feature attached. Forgetting
	 * the oldest entries costs at worst a repeat alert about a chest nobody has opened in a
	 * very long time, which is not a failure.
	 */
	private final java.util.Map<String, Boolean> containerReported =
			java.util.Collections.synchronizedMap(
					new java.util.LinkedHashMap<>(256, 0.75f, true) {
						@Override
						protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
							return size() > 2048;
						}
					});

	/**
	 * Checks the contents of a container a player just opened or closed.
	 * <p>
	 * The inventory watch only ever sees what somebody is carrying, so the way to keep a
	 * banned item was to not carry it: put it in a chest and it was invisible until someone
	 * took it out again. That was the single largest hole in contraband detection and it
	 * needed no new machinery to close — the grief log already copies the contents of every
	 * container anyone opens, so the check rides along on an array that has been taken
	 * anyway. No polling, no scan of the world, no cost on a tick where nobody opened
	 * anything.
	 * <p>
	 * A staff tool found in a container is confiscated to the vault rather than reported,
	 * matching what happens when one is found on a player: every route that creates one is
	 * blocked, so if one is sitting in a chest something went wrong, and leaving it in
	 * circulation compounds it.
	 *
	 * @param opener whoever opened the container — named in the alert as the person who had
	 *               access, not as the person who put it there, which the log answers
	 */
	public void scanContainer(ServerPlayer opener, net.minecraft.core.BlockPos pos, String world,
			net.minecraft.world.Container container) {

		if (container == null || !StaffConfig.get().watchContainers) return;

		MinecraftServer server = Mc.server(opener);
		if (server == null) return;

		ensureRules();
		IllegalItems.Ruleset illegal = illegalRules;
		IllegalItems.Ruleset operator = operatorRules;

		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty()) continue;

			if (StaffToolset.isStaffTool(stack)) {
				vault.deposit(opener.getUUID(), Mc.name(opener), "system", stack.copy(),
						"Staff tool found in a container at %d, %d, %d".formatted(
								pos.getX(), pos.getY(), pos.getZ()),
						server);
				// gateway-exempt: a chest in the world, not a player's inventory. The vault
				// deposit above is the record of where the tool went.
				container.setItem(slot, ItemStack.EMPTY);
				container.setChanged();
				Mods.alerts().onSecurityFlag(server, Mc.name(opener),
						"a staff tool (%s) was sitting in a container at %d, %d, %d — removed to the vault"
								.formatted(plain(stack), pos.getX(), pos.getY(), pos.getZ()));
				continue;
			}

			boolean isOperator = operator.matches(stack);
			if (!isOperator && !illegal.matches(stack)) continue;

			String key = world + ":" + pos.asLong() + ":" + Mc.itemId(stack.getItem());
			if (containerReported.putIfAbsent(key, Boolean.TRUE) != null) continue;

			Mods.alerts().onSecurityFlag(server, Mc.name(opener),
					"a container at %d, %d, %d holds %d× %s%s — opened by %s".formatted(
							pos.getX(), pos.getY(), pos.getZ(), stack.getCount(), plain(stack),
							isOperator ? " (operator tooling)" : " (not obtainable in survival)",
							Mc.name(opener)));
		}
	}

	// ------------------------------------------------------------ background sweep

	private int sweepTick;
	private final java.util.Map<java.util.UUID, Integer> lastReported = new java.util.HashMap<>();
	private boolean sweepRegistered;

	@Override
	public void onEnable() {
		if (sweepRegistered) return;
		sweepRegistered = true;

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (StaffConfig.get().watchContraband && ++contrabandTick >= CONTRABAND_INTERVAL) {
				contrabandTick = 0;
				watchContraband(server);
			}

			int minutes = StaffConfig.get().xraySweepMinutes;
			if (minutes <= 0) return;
			if (++sweepTick < minutes * 60 * 20) return;
			sweepTick = 0;
			sweepMining(server);
		});
	}

	/**
	 * Scores every online player and alerts on anyone who crosses the threshold.
	 * <p>
	 * The point of running this on a timer rather than waiting for a report is that x-ray
	 * has no victim to complain — nobody files a report saying someone else found diamonds
	 * too easily. Without a sweep the detector only ever runs on players staff already
	 * suspect, which is exactly the population it adds least value for.
	 * <p>
	 * A player is only re-reported when their score climbs meaningfully, so a long session
	 * produces one alert rather than one every ten minutes.
	 */
	private void sweepMining(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			// Only staff who are actually clocked on are skipped. Excluding everyone with
			// staff.gui was wrong twice over: with no permissions plugin that node falls back
			// to op level, so every operator — including whoever is testing — was silently
			// never scored; and staff mining on their own time are not above suspicion.
			if (StaffConfig.get().xraySkipStaffOnDuty && Mods.staffMode().isActive(player)) continue;

			XrayDetector.Report report = XrayDetector.analyse(Mc.name(player), 6L * 3_600_000L);
			StaffConfig cfg = StaffConfig.get();

			int notice = cfg.xrayNoticeConfidence;
			boolean worthSaying = report.isSuspicious()
					|| (notice > 0 && report.confidence() >= notice);
			if (!worthSaying) continue;

			// Re-reported only when the score climbs meaningfully, so a long session produces
			// one line rather than one every sweep.
			int previous = lastReported.getOrDefault(player.getUUID(), 0);
			if (report.confidence() <= previous + 5) continue;
			lastReported.put(player.getUUID(), report.confidence());

			if (report.isSuspicious()) {
				Mods.alerts().onSuspiciousMining(server, Mc.name(player), report.headline());
			} else {
				// Deliberately not an alert. This exists so that silence is distinguishable
				// from absence — a server owner who never sees anything should be able to
				// tell "nobody is cheating" from "this has never run".
				notifyNearMiss(server, Mc.name(player), report);
			}
		}
	}

	/** A quiet heads-up for a score below the alert line. Explicitly not an accusation. */
	private void notifyNearMiss(MinecraftServer server, String player, XrayDetector.Report report) {
		for (ServerPlayer staff : server.getPlayerList().getPlayers()) {
			if (!io.github.alphain24.staffcore.permission.Permissions.check(
					staff, io.github.alphain24.staffcore.permission.Nodes.SECURITY_CHECK)) {
				continue;
			}
			staff.sendSystemMessage(Theme.info("Mining watch — " + player + " at "
					+ report.confidence() + "%, below the " + StaffConfig.get().xrayAlertConfidence
					+ "% alert line. Not a finding; /staff xray " + player + " for the detail."));
		}
	}

	public void forget(java.util.UUID player) {
		lastReported.remove(player);
		reported.remove(player);
	}

	public record MiningSample(int ore, int filler) {
		public int total() {
			return ore + filler;
		}

		public double ratio() {
			return total() == 0 ? 0 : ore / (double) total();
		}
	}

	/** Counts ore vs. filler broken by a player within the last {@code windowMs}. */
	public MiningSample mining(String playerName, long windowMs) {
		if (!StaffCore.storage().isReady()) return null;
		Connection c = StaffCore.storage().conn();

		String sql = """
				SELECT block, COUNT(*) AS n FROM block_log
				WHERE player_name = ? AND action = 'BREAK' AND created_at >= ?
				GROUP BY block
				""";
		int ore = 0;
		int filler = 0;
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, playerName);
			ps.setLong(2, System.currentTimeMillis() - windowMs);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String block = rs.getString("block");
					int n = rs.getInt("n");
					if (block.endsWith("_ore") || block.endsWith("ancient_debris")) ore += n;
					else filler += n;
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Security] mining sample failed", e);
			return null;
		}
		return new MiningSample(ore, filler);
	}

	// ------------------------------------------------------------------- sweeping

	/** Runs {@link #scanItems} across everyone online. Used by {@code /itemscanner}. */
	public List<String> sweep(MinecraftServer server) {
		List<String> hits = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			List<Flag> flags = scanItems(p);
			if (!flags.isEmpty()) {
				hits.add(Mc.name(p) + ": " + flags.size() + " flag(s)");
			}
		}
		return hits;
	}
}
