package io.github.alphain24.staffcore.gui;

import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens menus, and remembers the way back.
 * <p>
 * Every open is deferred onto the server thread with {@code server.execute}. That matters
 * because most opens happen from inside a button handler, i.e. while the outgoing menu is
 * mid-click; swapping {@code containerMenu} underneath the code that is still using it is
 * how server-side GUIs end up desynced. One tick of delay costs nothing and removes the
 * whole class of problem.
 * <p>
 * Each open also moves the viewer's {@link NavigationHistory}, which is what the back arrow
 * follows. The four entry points were already chosen by what the move <em>is</em> — a fresh
 * panel, a step deeper, a return, a redraw — for the sake of the sound, so the history reads
 * the same decision rather than asking every menu to make a second one.
 */
public final class Guis {
	private Guis() {}

	@FunctionalInterface
	public interface Factory {
		Gui create(int containerId, Inventory playerInventory, ServerPlayer viewer);
	}

	/**
	 * What it takes to show a frame again.
	 *
	 * @param reentry the screen's own way back in, or {@code null} to rebuild from the factory
	 * @param label   the breadcrumb, for the back arrow of whatever comes after it
	 */
	private record Entry(Component title, Factory factory, Runnable reentry, String label) {}

	/**
	 * Keyed by viewer, and dropped when their menus close for real or they disconnect — a
	 * path is only meaningful while the chain of screens it describes is still open.
	 */
	private static final Map<UUID, NavigationHistory<Entry>> HISTORY = new ConcurrentHashMap<>();

	/** First open of a session — the chest-opening sound. Starts a fresh history. */
	public static void open(ServerPlayer player, Component title, Factory factory) {
		show(player, title, factory, Sound.OPEN, NavigationHistory.Move.ROOT);
	}

	/** Stepping deeper into the menu tree. */
	public static void navigate(ServerPlayer player, Component title, Factory factory) {
		show(player, title, factory, Sound.FORWARD, NavigationHistory.Move.FORWARD);
	}

	/** Returning to a screen that is known to be above this one. */
	public static void goBack(ServerPlayer player, Component title, Factory factory) {
		show(player, title, factory, Sound.BACK, NavigationHistory.Move.UP);
	}

	/**
	 * Swapping the screen with no audible transition — used for in-place refreshes, and by a
	 * few screens that play a sound of their own before opening.
	 */
	public static void silent(ServerPlayer player, Component title, Factory factory) {
		show(player, title, factory, Sound.NONE, NavigationHistory.Move.REPLACE);
	}

	/** Back to the root of the tree, with the back sound rather than the opening one. */
	public static void home(ServerPlayer player, Component title, Factory factory) {
		show(player, title, factory, Sound.BACK, NavigationHistory.Move.ROOT);
	}

	/**
	 * One level back along the path this viewer actually took.
	 *
	 * @param fallback where to go when there is no path — the screen was opened by a command,
	 *                 or the menus were closed in between; {@code null} closes the screen
	 */
	public static void back(ServerPlayer player, Runnable fallback) {
		MinecraftServer server = Mc.server(player);
		if (server == null) return;

		server.execute(() -> {
			NavigationHistory<Entry> history = HISTORY.get(player.getUUID());
			NavigationHistory.Frame<Entry> previous = history == null ? null : history.stepBack();

			if (previous == null) {
				if (fallback != null) fallback.run();
				else player.closeContainer();
				return;
			}

			Entry entry = previous.payload();
			if (entry.reentry() != null) {
				// The screen's own way in, which re-runs whatever its opener checks. Invsee is
				// the reason: its edit session ends when it closes, so rebuilding it from the
				// old factory would hand back a screen holding a session that no longer exists.
				Sfx.back(player);
				entry.reentry().run();
			} else {
				show(player, entry.title(), entry.factory(), Sound.BACK,
						NavigationHistory.Move.REPLACE);
			}
		});
	}

	/** Forgets a viewer's path. Called when their menus close for real, and on disconnect. */
	public static void forget(UUID viewer) {
		HISTORY.remove(viewer);
	}

	public static void close(ServerPlayer player) {
		MinecraftServer server = Mc.server(player);
		if (server == null) return;
		server.execute(player::closeContainer);
	}

	private enum Sound { OPEN, FORWARD, BACK, NONE }

	private static void show(ServerPlayer player, Component title, Factory factory, Sound sound,
			NavigationHistory.Move move) {

		MinecraftServer server = Mc.server(player);
		if (server == null) return;

		server.execute(() -> {
			// Tell the outgoing menu this is a hop, not a close, so it stays quiet — and so it
			// does not forget the path the incoming menu is about to extend.
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

			// The history moves only once the screen really opened, and only then is its
			// class known — which is what the rules compare.
			if (player.containerMenu instanceof Gui incoming) {
				record(player, incoming, title, factory, move);
			}
		});
	}

	private static void record(ServerPlayer player, Gui incoming, Component title,
			Factory factory, NavigationHistory.Move move) {

		NavigationHistory<Entry> history =
				HISTORY.computeIfAbsent(player.getUUID(), id -> new NavigationHistory<>());

		history.apply(move, new NavigationHistory.Frame<>(incoming.getClass(),
				incoming.returnable(),
				new Entry(title, factory, incoming.reentry(), label(title))));

		NavigationHistory.Frame<Entry> previous = history.previous();
		if (previous != null) {
			incoming.arrivedFrom(previous.payload().label());
		}
	}

	/**
	 * {@code StaffCore › Punish › Notch} becomes {@code Punish › Notch}; the bare panel title
	 * becomes "the staff panel". The brand is on every title and says nothing about which one.
	 */
	static String label(Component title) {
		String text = title.getString();
		int cut = text.indexOf(" › ");
		return cut < 0 ? "the staff panel" : text.substring(cut + 3);
	}
}
