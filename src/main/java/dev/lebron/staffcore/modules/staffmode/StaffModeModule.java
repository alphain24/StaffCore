package dev.lebron.staffcore.modules.staffmode;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.config.StaffConfig;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Module;
import dev.lebron.staffcore.modules.alerts.AlertsModule;
import dev.lebron.staffcore.modules.vanish.VanishModule;
import dev.lebron.staffcore.storage.StateStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * On-duty state.
 * <p>
 * Entering stashes the real inventory, hands out {@link StaffToolset}, applies the
 * configured duty gamemode and vanishes you. Leaving reverses all four.
 * <p>
 * The stash is written to the database, not held in memory. That is the whole reason this
 * module was rewritten: a restart while anyone was clocked on used to destroy their
 * inventory outright, and the only thing the mod could do was apologise afterwards. Now
 * clocking on persists, disconnecting keeps you on duty, and rejoining puts you back
 * exactly as you were.
 */
public class StaffModeModule implements Module {

	@Override
	public String id() {
		return "staff_mode";
	}

	@Override
	public String displayName() {
		return "Staff Mode";
	}

	private final Set<UUID> inStaffMode = new HashSet<>();

	@Override
	public void onEnable() {
		StaffToolset.register(this);
	}

	public boolean isActive(ServerPlayer player) {
		return inStaffMode.contains(player.getUUID());
	}

	public int activeCount() {
		return inStaffMode.size();
	}

	/** Returns the new state: true = now on duty. */
	public boolean toggle(ServerPlayer player) {
		if (isActive(player)) {
			exit(player, true);
			return false;
		}
		enter(player);
		return true;
	}

	// ---------------------------------------------------------------------- enter

	private void enter(ServerPlayer player) {
		MinecraftServer server = Mc.server(player);
		if (server == null) return;

		GameType prior = player.gameMode();

		// Persist before touching anything. If the write fails we would rather refuse to
		// clock on than clear an inventory we cannot put back.
		if (!stashInventory(server, player)) {
			player.sendSystemMessage(Theme.bad(
					"Could not save your inventory, so staff mode did not start. Check the server log."));
			Sfx.error(player);
			return;
		}

		player.getInventory().clearContent();
		StaffToolset.give(player);
		inStaffMode.add(player.getUUID());
		StaffCore.state().setStaffMode(player.getUUID(), true, prior.getName());

		applyDutyMode(player);

		VanishModule vanish = StaffCore.modules().require("vanish", VanishModule.class);
		if (!vanish.isVanished(player)) {
			vanish.toggle(player);
		}

		player.sendSystemMessage(Theme.good("Staff mode on. Your inventory is saved and will come back."));
		Sfx.staffModeOn(player);
		announce(player, true);
	}

	private boolean stashInventory(MinecraftServer server, ServerPlayer player) {
		int size = player.getInventory().getContainerSize();
		ItemStack[] contents = new ItemStack[size];
		for (int i = 0; i < size; i++) {
			contents[i] = player.getInventory().getItem(i).copy();
		}
		StaffCore.state().saveStash(server, player.getUUID(), contents);
		// An entirely empty inventory legitimately stores no rows, so an empty stash is
		// only a failure when there was something to store.
		return hasNothing(contents) || StaffCore.state().hasStash(player.getUUID());
	}

	private static boolean hasNothing(ItemStack[] contents) {
		for (ItemStack stack : contents) {
			if (stack != null && !stack.isEmpty()) return false;
		}
		return true;
	}

	/** Survival, creative or spectator while on duty, per config. */
	private void applyDutyMode(ServerPlayer player) {
		GameType duty = StaffConfig.get().dutyGameMode();
		if (duty != null) {
			player.setGameMode(duty);
		}

		// Flight in every mode, so staff can move around a survival world freely.
		player.getAbilities().mayfly = true;

		// On duty means untouchable, vanished or not. A staff member investigating a mob
		// farm or standing in lava to read a grief log should not be part of the incident.
		if (StaffConfig.get().staffModeInvulnerable) {
			player.getAbilities().invulnerable = true;
			player.setInvulnerable(true);
		}

		applySpeed(player, speedOf(player));
		player.onUpdateAbilities();
	}

	/** Puts everything back the way a normal player expects it. */
	private void clearDutyMode(ServerPlayer player) {
		player.getAbilities().invulnerable = player.isCreative();
		player.setInvulnerable(false);
		player.getAbilities().mayfly = player.isCreative() || player.isSpectator();
		player.getAbilities().flying = false;
		player.getAbilities().setFlyingSpeed(DEFAULT_FLY_SPEED);
		player.noPhysics = false;
		noclip.remove(player.getUUID());
		noclipReturn.remove(player.getUUID());
		speeds.remove(player.getUUID());
		player.onUpdateAbilities();
	}

	// ------------------------------------------------------------------ movement

	/** Vanilla's flying speed, and the multipliers the toolset cycles through. */
	private static final float DEFAULT_FLY_SPEED = 0.05F;
	private static final int[] SPEED_STEPS = { 1, 2, 4, 8 };

	private final Set<UUID> noclip = new HashSet<>();
	private final Map<UUID, Integer> speeds = new HashMap<>();
	/** The gamemode to put a staff member back into when noclip is switched off. */
	private final Map<UUID, GameType> noclipReturn = new HashMap<>();

	public boolean isNoclip(ServerPlayer player) {
		return noclip.contains(player.getUUID());
	}

	/**
	 * Fly through walls.
	 * <p>
	 * This is implemented as a swap to <strong>spectator</strong>, and that is not a
	 * shortcut — it is the only thing that works. The client runs its own collision, so a
	 * server-side {@code noPhysics} flag is simply ignored: the player still walks into the
	 * wall on their own screen and the server corrects them back. Vanilla's own check for
	 * "may I pass through blocks" is {@code isSpectator}, so that is what the client honours.
	 * <p>
	 * The duty gamemode is remembered and restored on the way out, so toggling noclip does
	 * not quietly strip a staff member of creative.
	 */
	public boolean toggleNoclip(ServerPlayer player) {
		if (!isActive(player)) return false;

		if (noclip.remove(player.getUUID())) {
			GameType back = noclipReturn.remove(player.getUUID());
			player.setGameMode(back != null ? back : StaffConfig.get().dutyGameMode());
			player.noPhysics = false;
			applyDutyMode(player);

			player.sendSystemMessage(Theme.info("Noclip off — back to " + describe(player.gameMode()) + "."));
			Sfx.toggleOff(player);
			return false;
		}

		noclip.add(player.getUUID());
		noclipReturn.put(player.getUUID(), player.gameMode());
		player.setGameMode(GameType.SPECTATOR);
		player.noPhysics = true;

		player.sendSystemMessage(Theme.good("Noclip on — fly through anything."));
		player.sendSystemMessage(Theme.info("You are in spectator while this is on, so you cannot interact."));
		Sfx.toggleOn(player);
		return true;
	}

	private static String describe(GameType mode) {
		return mode == null ? "survival" : mode.getName();
	}

	public int speedOf(ServerPlayer player) {
		return speeds.getOrDefault(player.getUUID(), SPEED_STEPS[0]);
	}

	/** Cycles 1× → 2× → 4× → 8× → 1×. */
	public int cycleSpeed(ServerPlayer player) {
		int current = speedOf(player);
		int index = 0;
		for (int i = 0; i < SPEED_STEPS.length; i++) {
			if (SPEED_STEPS[i] == current) {
				index = i;
				break;
			}
		}
		int next = SPEED_STEPS[(index + 1) % SPEED_STEPS.length];
		speeds.put(player.getUUID(), next);
		applySpeed(player, next);
		player.onUpdateAbilities();
		player.sendSystemMessage(Theme.info("Fly speed " + next + "×."));
		Sfx.click(player);
		return next;
	}

	private void applySpeed(ServerPlayer player, int multiplier) {
		player.getAbilities().setFlyingSpeed(DEFAULT_FLY_SPEED * multiplier);
	}

	// ----------------------------------------------------------------------- exit

	private void exit(ServerPlayer player, boolean announce) {
		MinecraftServer server = Mc.server(player);
		inStaffMode.remove(player.getUUID());

		player.getInventory().clearContent();
		restoreStash(server, player);

		StateStore.State stored = StaffCore.state().loadAll().get(player.getUUID());
		GameType prior = parseGameMode(stored == null ? null : stored.priorGamemode());
		player.setGameMode(prior != null ? prior : GameType.SURVIVAL);
		clearDutyMode(player);

		StaffCore.state().setStaffMode(player.getUUID(), false, null);

		VanishModule vanish = StaffCore.modules().require("vanish", VanishModule.class);
		if (vanish.isVanished(player)) {
			vanish.toggle(player);
		}

		player.sendSystemMessage(Theme.info("Staff mode off. Welcome back."));
		Sfx.staffModeOff(player);
		if (announce) announce(player, false);
	}

	private void restoreStash(MinecraftServer server, ServerPlayer player) {
		if (server == null) return;

		int size = player.getInventory().getContainerSize();
		ItemStack[] stored = StaffCore.state().loadStash(server, player.getUUID(), size);

		if (stored == null) {
			// Genuinely nothing on record — they clocked on with empty pockets, or the
			// database was unavailable at the time.
			player.sendSystemMessage(Theme.warn("No saved inventory was found to give back."));
			return;
		}

		for (int i = 0; i < size && i < stored.length; i++) {
			player.getInventory().setItem(i, stored[i]);
		}
		player.containerMenu.broadcastChanges();
		// Cleared only after a successful hand-back, so a crash mid-restore leaves the
		// stash intact for the next attempt.
		StaffCore.state().clearStash(player.getUUID());
	}

	private static GameType parseGameMode(String name) {
		if (name == null) return null;
		for (GameType type : GameType.values()) {
			if (type.getName().equalsIgnoreCase(name)) return type;
		}
		return null;
	}

	// ------------------------------------------------------------------ lifecycle

	/**
	 * Disconnecting on duty now <em>keeps</em> you on duty. The stash is on disk, so there
	 * is nothing to rescue, and a staff member who crashes mid-incident comes back with
	 * their tools rather than having been silently clocked off.
	 */
	public void onPlayerLeft(ServerPlayer player) {
		inStaffMode.remove(player.getUUID());
	}

	/** Re-applies duty state to somebody who was on duty when they last disconnected. */
	public void restoreOnJoin(MinecraftServer server, ServerPlayer player) {
		StateStore.State stored = StaffCore.state().loadAll().get(player.getUUID());
		if (stored == null || !stored.staffMode()) return;

		inStaffMode.add(player.getUUID());
		applyDutyMode(player);

		player.sendSystemMessage(Theme.good("You are still on duty — your inventory is saved."));
		player.sendSystemMessage(Theme.info("Clock off with /staff mode to get it back."));
		Sfx.staffModeOn(player);
	}

	private void announce(ServerPlayer player, boolean on) {
		MinecraftServer server = Mc.server(player);
		if (server == null) return;
		StaffCore.modules().get("alerts", AlertsModule.class).ifPresent(a ->
				a.onStaffAction(server, Mc.name(player) + (on ? " went on duty" : " went off duty")));
	}
}
