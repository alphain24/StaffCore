package dev.lebron.staffcore.gui;

import dev.lebron.staffcore.compat.Mc;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

/**
 * Opens menus.
 * <p>
 * Every open is deferred onto the server thread with {@code server.execute}. That matters
 * because most opens happen from inside a button handler, i.e. while the outgoing menu is
 * mid-click; swapping {@code containerMenu} underneath the code that is still using it is
 * how server-side GUIs end up desynced. One tick of delay costs nothing and removes the
 * whole class of problem.
 */
public final class Guis {
	private Guis() {}

	@FunctionalInterface
	public interface Factory {
		Gui create(int containerId, Inventory playerInventory, ServerPlayer viewer);
	}

	/** First open of a session — the chest-opening sound. */
	public static void open(ServerPlayer player, Component title, Factory factory) {
		show(player, title, factory, Sound.OPEN);
	}

	/** Stepping deeper into the menu tree. */
	public static void navigate(ServerPlayer player, Component title, Factory factory) {
		show(player, title, factory, Sound.FORWARD);
	}

	/** Stepping back up. */
	public static void goBack(ServerPlayer player, Component title, Factory factory) {
		show(player, title, factory, Sound.BACK);
	}

	/** Swapping the screen with no audible transition — used for in-place refreshes. */
	public static void silent(ServerPlayer player, Component title, Factory factory) {
		show(player, title, factory, Sound.NONE);
	}

	public static void close(ServerPlayer player) {
		MinecraftServer server = Mc.server(player);
		if (server == null) return;
		server.execute(player::closeContainer);
	}

	private enum Sound { OPEN, FORWARD, BACK, NONE }

	private static void show(ServerPlayer player, Component title, Factory factory, Sound sound) {
		MinecraftServer server = Mc.server(player);
		if (server == null) return;

		server.execute(() -> {
			// Tell the outgoing menu this is a hop, not a close, so it stays quiet.
			if (player.containerMenu instanceof Gui outgoing) {
				outgoing.markNavigating();
			}

			switch (sound) {
				case OPEN -> Sfx.open(player);
				case FORWARD -> Sfx.forward(player);
				case BACK -> Sfx.back(player);
				case NONE -> { }
			}

			player.openMenu(new SimpleMenuProvider(
					(containerId, inventory, p) -> factory.create(containerId, inventory, (ServerPlayer) p),
					title));
		});
	}
}
