package io.github.alphain24.staffcore.gui;

import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

/**
 * Every sound StaffCore plays, in one place, so the whole suite has a consistent voice.
 * <p>
 * The rules of the palette: navigation is quiet and high, state changes are pitched
 * (up for on, down for off), anything that lands on another player is loud and low.
 * Nothing here is played into the world — {@link Mc#sound} sends the packet to a single
 * client, so a vanished admin clicking through menus is still silent to everyone else.
 * <p>
 * If a {@code SoundEvents} constant stops resolving, this is the only file to touch.
 */
public final class Sfx {
	private Sfx() {}

	// ------------------------------------------------------------- menu navigation

	/** A menu opened. */
	public static void open(ServerPlayer p) {
		Mc.sound(p, SoundEvents.BARREL_OPEN, 0.45F, 1.5F);
	}

	/** A menu closed for good. */
	public static void close(ServerPlayer p) {
		Mc.sound(p, SoundEvents.BARREL_CLOSE, 0.45F, 1.5F);
	}

	/** Generic button press. */
	public static void click(ServerPlayer p) {
		Mc.sound(p, SoundEvents.UI_BUTTON_CLICK, 0.4F, 1.1F);
	}

	/** Stepping into a sub-menu. */
	public static void forward(ServerPlayer p) {
		Mc.sound(p, SoundEvents.AMETHYST_BLOCK_CHIME, 0.5F, 1.4F);
	}

	/** Going back up a level. */
	public static void back(ServerPlayer p) {
		Mc.sound(p, SoundEvents.AMETHYST_BLOCK_CHIME, 0.4F, 0.9F);
	}

	/** Page turned in a paginated list. */
	public static void page(ServerPlayer p) {
		Mc.sound(p, SoundEvents.BOOK_PAGE_TURN, 0.7F, 1.1F);
	}

	// ------------------------------------------------------------- state feedback

	public static void toggleOn(ServerPlayer p) {
		Mc.sound(p, SoundEvents.NOTE_BLOCK_PLING, 0.6F, 1.8F);
	}

	public static void toggleOff(ServerPlayer p) {
		Mc.sound(p, SoundEvents.NOTE_BLOCK_BASS, 0.6F, 0.7F);
	}

	/** Small win: note saved, report claimed, item scanned clean. */
	public static void success(ServerPlayer p) {
		Mc.sound(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7F, 1.3F);
	}

	/** Big win: a punishment landed, a rollback finished. */
	public static void bigSuccess(ServerPlayer p) {
		Mc.sound(p, SoundEvents.PLAYER_LEVELUP, 0.5F, 1.4F);
	}

	/** Clicked something you are not allowed to click. */
	public static void deny(ServerPlayer p) {
		Mc.sound(p, SoundEvents.VILLAGER_NO, 0.5F, 1.0F);
	}

	/** Something went wrong on our side. */
	public static void error(ServerPlayer p) {
		Mc.sound(p, SoundEvents.ANVIL_LAND, 0.35F, 1.9F);
	}

	// ------------------------------------------------------------- staff actions

	public static void staffModeOn(ServerPlayer p) {
		Mc.sound(p, SoundEvents.BEACON_ACTIVATE, 0.6F, 1.6F);
	}

	public static void staffModeOff(ServerPlayer p) {
		Mc.sound(p, SoundEvents.BEACON_DEACTIVATE, 0.6F, 1.6F);
	}

	public static void vanish(ServerPlayer p) {
		Mc.sound(p, SoundEvents.ENDERMAN_TELEPORT, 0.6F, 1.6F);
	}

	public static void unvanish(ServerPlayer p) {
		Mc.sound(p, SoundEvents.ENDERMAN_TELEPORT, 0.6F, 0.8F);
	}

	public static void teleport(ServerPlayer p) {
		Mc.sound(p, SoundEvents.ENDERMAN_TELEPORT, 0.5F, 1.2F);
	}

	public static void invsee(ServerPlayer p) {
		Mc.sound(p, SoundEvents.ENDER_CHEST_OPEN, 0.5F, 1.3F);
	}

	// ------------------------------------------------------- what the target hears

	public static void frozen(ServerPlayer target) {
		Mc.sound(target, SoundEvents.PLAYER_HURT_FREEZE, 0.9F, 1.0F);
	}

	public static void unfrozen(ServerPlayer target) {
		Mc.sound(target, SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.2F);
	}

	public static void warned(ServerPlayer target) {
		Mc.sound(target, SoundEvents.NOTE_BLOCK_PLING, 1.0F, 0.6F);
	}

	public static void muted(ServerPlayer target) {
		Mc.sound(target, SoundEvents.NOTE_BLOCK_BASS, 1.0F, 0.5F);
	}

	// -------------------------------------------------------------- broadcast pings

	/** Heard by staff when a ban lands. Deliberately the loudest thing in the mod. */
	public static void banBroadcast(MinecraftServer server, java.util.function.Predicate<ServerPlayer> audience) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (audience.test(p)) {
				Mc.sound(p, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.35F, 1.4F);
			}
		}
	}

	/** Heard by staff when a new report or alert comes in. */
	public static void alertPing(ServerPlayer staff) {
		Mc.sound(staff, SoundEvents.NOTE_BLOCK_BELL, 0.6F, 1.5F);
	}

	/** Heard by staff on a staff-chat message — quiet enough to sit under conversation. */
	public static void staffChatPing(ServerPlayer staff) {
		Mc.sound(staff, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.35F, 1.9F);
	}
}
