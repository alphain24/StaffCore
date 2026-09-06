package io.github.alphain24.staffcore.modules.staffmode;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.gui.menu.BlockHistoryMenu;
import io.github.alphain24.staffcore.gui.menu.ContainerLogMenu;
import io.github.alphain24.staffcore.gui.menu.GriefMenu;
import io.github.alphain24.staffcore.gui.menu.InvseeMenu;
import io.github.alphain24.staffcore.gui.menu.PlayerActionsMenu;
import io.github.alphain24.staffcore.gui.menu.PlayerListMenu;
import io.github.alphain24.staffcore.gui.menu.PunishMenu;
import io.github.alphain24.staffcore.gui.menu.StaffPanelMenu;
import io.github.alphain24.staffcore.modules.freeze.FreezeModule;
import io.github.alphain24.staffcore.modules.teleport.TeleportModule;
import io.github.alphain24.staffcore.modules.vanish.VanishModule;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The hotbar a staff member gets in staff mode, and the interactions behind it.
 * <p>
 * Tools are recognised by their item type alone. That is safe here precisely because
 * entering staff mode stashes the real inventory and hands out a known nine slots — a
 * compass in a staff-mode hotbar is always <em>the</em> compass, so there is no need to
 * hang custom NBT off it and no way for a player to forge one.
 */
public final class StaffToolset {
	private StaffToolset() {}

	/** Where each tool sits, what it looks like, and what it does. */
	public enum Tool {
		PANEL(0, Items.NETHER_STAR, "Staff Panel", Theme.ACCENT,
				"Everything StaffCore can do, one click away."),
		PLAYERS(1, Items.COMPASS, "Online Players", Theme.TEXT,
				"Browse everyone online and act on them."),
		INSPECT(2, Items.BOOK, "Inspect", Theme.TEXT,
				"Right-click a player to open their file."),
		FREEZE(3, Items.PACKED_ICE, "Freeze", 0x8FD3FF,
				"Right-click a player to lock them in place."),
		INVSEE(4, Items.CHEST, "Inventory", 0xC8A165,
				"Right-click a player to see what they are carrying."),
		PUNISH(5, Items.NETHERITE_AXE, "Punish", Theme.BAD,
				"Right-click a player to open the punish menu."),
		INSPECT_BLOCK(6, Items.STICK, "Inspect Block", 0xC8A165,
				"Right-click a block to see its history."),
		VANISH(7, Items.ENDER_EYE, "Vanish", 0x9B7EDE,
				"Toggle invisibility."),
		RANDOM_TP(8, Items.ENDER_PEARL, "Jump To", 0x5BE8C0,
				"Teleport to a random player who is not staff.");

		public final int slot;
		public final Item item;
		public final String label;
		public final int color;
		public final String blurb;

		Tool(int slot, Item item, String label, int color, String blurb) {
			this.slot = slot;
			this.item = item;
			this.label = label;
			this.color = color;
			this.blurb = blurb;
		}

		/** True when this tool acts on a player you right-click. */
		public boolean targetsEntity() {
			return this == INSPECT || this == FREEZE || this == INVSEE || this == PUNISH;
		}

		/** True when this tool acts on a block you right-click. */
		public boolean targetsBlock() {
			return this == INSPECT_BLOCK;
		}

		public ItemStack stack() {
			return tag(build());
		}

		private ItemStack build() {
			Icon icon = Icon.of(item).name(label, color).lore(blurb).gap();
			if (targetsEntity()) {
				icon.action("Right-click a player", "use this tool");
			} else if (targetsBlock()) {
				icon.action("Left-click a block", "read its history")
						.action("Right-click a block", "the same");
			} else {
				icon.action("Right-click", "activate");
			}
			return icon.glow().build();
		}

		public static Tool byItem(Item item) {
			for (Tool t : values()) {
				if (t.item == item) return t;
			}
			return null;
		}
	}

	// ---------------------------------------------------------------- identifying

	/** NBT key stamped on every staff tool. */
	private static final String MARKER = "StaffCoreTool";

	/**
	 * Stamps a tool so it can be recognised anywhere.
	 * <p>
	 * Recognising tools by item type alone was fine while they only ever existed inside
	 * staff mode, but the moment one can leak — dropped, traded, kept through a crash — the
	 * mod needs to tell a staff compass from a player's compass. A marker in custom data
	 * does that, and a player cannot forge one without creative-mode NBT editing, which is
	 * itself a finding.
	 */
	private static ItemStack tag(ItemStack stack) {
		CompoundTag marker = new CompoundTag();
		marker.putBoolean(MARKER, true);
		CustomData.set(DataComponents.CUSTOM_DATA, stack, marker);
		return stack;
	}

	public static boolean isStaffTool(ItemStack stack) {
		if (stack.isEmpty()) return false;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return false;
		CompoundTag marker = new CompoundTag();
		marker.putBoolean(MARKER, true);
		return data.matchedBy(marker);
	}

	/** Every item type used by the toolset, for a cheap first-pass check. */
	public static Set<Item> toolItems() {
		Set<Item> out = new HashSet<>();
		for (Tool tool : Tool.values()) {
			out.add(tool.item);
		}
		return out;
	}

	// ------------------------------------------------------------------ equipping

	public static void give(ServerPlayer player) {
		Inventory inv = player.getInventory();
		for (Tool tool : Tool.values()) {
			inv.setItem(tool.slot, tool.stack());
		}
		player.containerMenu.broadcastChanges();
	}

	// -------------------------------------------------------------- inspect mode

	/**
	 * Staff who have left inspect mode on, so any block click reports its history.
	 * <p>
	 * The stick works and will keep working, but it occupies a hand and a hotbar slot, and
	 * an investigation is a stretch of time rather than one click — you look at the chest,
	 * then the wall behind it, then the floor. Holding a mode is what that actually is.
	 * <p>
	 * Not persisted. A mode that quietly survives a restart and then eats your first
	 * pickaxe swing is worse than one you have to turn on again.
	 */
	private static final java.util.Set<java.util.UUID> inspecting = new java.util.HashSet<>();

	/** @return whether inspect mode is now on */
	public static boolean toggleInspectMode(ServerPlayer staff) {
		if (!inspecting.add(staff.getUUID())) {
			inspecting.remove(staff.getUUID());
			return false;
		}
		return true;
	}

	public static boolean isInspecting(ServerPlayer staff) {
		return inspecting.contains(staff.getUUID());
	}

	public static void forget(java.util.UUID player) {
		inspecting.remove(player);
	}

	// -------------------------------------------------------------------- wiring

	private static boolean registered;

	/**
	 * Hooks the two interaction events once. Both handlers bail immediately unless the
	 * player is actually in staff mode, so the cost on a normal server tick is a set lookup.
	 */
	public static void register(StaffModeModule staffMode) {
		if (registered) return;
		registered = true;

		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer staff)) return InteractionResult.PASS;
			if (!staffMode.isActive(staff)) return InteractionResult.PASS;
			if (!(entity instanceof ServerPlayer target)) return InteractionResult.PASS;

			Tool tool = Tool.byItem(staff.getItemInHand(hand).getItem());
			if (tool == null || !tool.targetsEntity()) return InteractionResult.PASS;

			useOnPlayer(staff, target, tool);
			return InteractionResult.SUCCESS;
		});

		// Left-click on a block: the same lookup, on the gesture people reach for first.
		// A stolen chest is punched, not right-clicked, and right-clicking one would open it.
		// Inspect mode is not part of the toolset and must not be gated behind it.
		//
		// This check used to sit below the staff-mode gate, which meant `/staff inspect`
		// silently did nothing unless you were also clocked on — the command neither
		// required staff mode nor said a word about it, so the observable behaviour was
		// "I turned inspect on and it just breaks blocks". It is opted into explicitly and
		// gated by its own permission; that is enough.
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> {
			// The guaranteed cancel. AttackBlockCallback should stop the break on its own,
			// but this is the last gate before the block actually goes, and a diagnostic
			// tool that occasionally destroys what you pointed it at is worse than useless.
			return !(player instanceof ServerPlayer staff) || !isInspecting(staff);
		});

		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer staff)) return InteractionResult.PASS;

			if (isInspecting(staff)) {
				if (denied(staff, Nodes.BLOCK_INSPECT)) return InteractionResult.FAIL;
				inspect(staff, pos);
				return InteractionResult.SUCCESS;
			}

			if (!staffMode.isActive(staff)) return InteractionResult.PASS;

			Tool tool = Tool.byItem(staff.getItemInHand(hand).getItem());
			if (tool == null || !tool.targetsBlock()) return InteractionResult.PASS;

			if (denied(staff, Nodes.ROLLBACK)) return InteractionResult.FAIL;
			inspect(staff, pos);
			return InteractionResult.SUCCESS;
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer staff)) return InteractionResult.PASS;

			// Cancelling here is what stops the chest opening underneath the history screen.
			// Above the staff-mode gate for the same reason as the attack handler.
			if (isInspecting(staff)) {
				if (denied(staff, Nodes.BLOCK_INSPECT)) return InteractionResult.FAIL;
				inspect(staff, hit.getBlockPos());
				return InteractionResult.SUCCESS;
			}

			if (!staffMode.isActive(staff)) return InteractionResult.PASS;

			Tool tool = Tool.byItem(staff.getItemInHand(hand).getItem());
			if (tool == null || !tool.targetsBlock()) return InteractionResult.PASS;

			if (denied(staff, Nodes.ROLLBACK)) return InteractionResult.FAIL;
			inspect(staff, hit.getBlockPos());
			return InteractionResult.SUCCESS;
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer staff)) return InteractionResult.PASS;
			if (!staffMode.isActive(staff)) return InteractionResult.PASS;

			Tool tool = Tool.byItem(staff.getItemInHand(hand).getItem());
			if (tool == null || tool.targetsEntity() || tool.targetsBlock()) return InteractionResult.PASS;

			useInAir(staff, tool);
			return InteractionResult.SUCCESS;
		});
	}

	// -------------------------------------------------------------------- actions

	/**
	 * Opens the history of the one block that was clicked.
	 * <p>
	 * Both routes now answer about this position and nothing else. The inspector used to
	 * open an area log for ordinary blocks, which answers "what happened around here" —
	 * a different question, and one that buries the block you actually pointed at under
	 * every neighbouring row. Widening out is still one click away from the new screen.
	 * <p>
	 * Containers keep their own view because the question there is almost always "who
	 * emptied this", not "who placed it": the chest is usually still standing and the
	 * diamonds are not.
	 */
	private static void inspect(ServerPlayer staff, net.minecraft.core.BlockPos pos) {
		if (staff.level().getBlockEntity(pos) instanceof net.minecraft.world.Container) {
			ContainerLogMenu.open(staff, pos);
		} else {
			BlockHistoryMenu.open(staff, pos);
		}
	}

	private static void useOnPlayer(ServerPlayer staff, ServerPlayer target, Tool tool) {
		switch (tool) {
			case INSPECT -> {
				if (denied(staff, Nodes.STAFF_GUI)) return;
				PlayerActionsMenu.open(staff, Mc.profile(target));
			}
			case FREEZE -> {
				if (denied(staff, Nodes.FREEZE)) return;
				boolean frozen = StaffCore.modules().require("freeze", FreezeModule.class).toggle(target);
				staff.sendSystemMessage(frozen
						? Theme.good(Mc.name(target) + " is frozen.")
						: Theme.info(Mc.name(target) + " is free to move."));
				Sfx.success(staff);
			}
			case INVSEE -> {
				if (denied(staff, Nodes.INVSEE)) return;
				InvseeMenu.open(staff, target);
			}
			case PUNISH -> {
				if (denied(staff, Nodes.PUNISH)) return;
				PunishMenu.open(staff, Mc.profile(target));
			}
			default -> { }
		}
	}

	private static void useInAir(ServerPlayer staff, Tool tool) {
		switch (tool) {
			case PANEL -> {
				if (denied(staff, Nodes.STAFF_GUI)) return;
				StaffPanelMenu.open(staff);
			}
			case PLAYERS -> {
				if (denied(staff, Nodes.STAFF_GUI)) return;
				PlayerListMenu.openForInspection(staff);
			}
			case VANISH -> {
				if (denied(staff, Nodes.VANISH)) return;
				StaffCore.modules().require("vanish", VanishModule.class).toggle(staff);
			}
			case RANDOM_TP -> {
				if (denied(staff, Nodes.TP)) return;
				jumpToRandomPlayer(staff);
			}
			default -> { }
		}
	}

	/**
	 * Teleports to a random non-staff player. Staff are excluded because the point of the
	 * tool is landing where something might be happening, and staff cluster at spawn.
	 */
	private static void jumpToRandomPlayer(ServerPlayer staff) {
		MinecraftServer server = Mc.server(staff);
		if (server == null) return;

		List<ServerPlayer> candidates = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p != staff && !Permissions.check(p, Nodes.STAFF_GUI)) candidates.add(p);
		}

		if (candidates.isEmpty()) {
			staff.sendSystemMessage(Theme.warn("Nobody to jump to — everyone online is staff."));
			Sfx.deny(staff);
			return;
		}

		ServerPlayer target = candidates.get(staff.getRandom().nextInt(candidates.size()));
		StaffCore.modules().require("teleport", TeleportModule.class).toPlayer(staff, target);
		staff.sendSystemMessage(Theme.info("Jumped to " + Mc.name(target) + "."));
	}

	private static boolean denied(ServerPlayer staff, String node) {
		if (Permissions.check(staff, node)) return false;
		staff.sendSystemMessage(Theme.bad("You do not have " + node + "."));
		Sfx.deny(staff);
		return true;
	}
}
