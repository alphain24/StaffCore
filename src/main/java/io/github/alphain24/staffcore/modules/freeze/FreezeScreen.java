package io.github.alphain24.staffcore.modules.freeze;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * What a frozen player sees: the world dark, and one thing written across the middle of it.
 * <p>
 * Chat was the wrong place to say it. A line every ten seconds scrolled away under whatever the
 * player was typing, and a player who had chat hidden never saw it at all. A title cannot be missed
 * or scrolled past, and blindness takes away the reason to look anywhere else — and the reason to
 * try to move.
 * <p>
 * Everything here is resent on a timer rather than sent once. A title fades, a milk bucket clears
 * an effect, and a client that reconnects has forgotten both; sending again every couple of seconds
 * costs a few packets and makes each of those a non-event.
 */
public final class FreezeScreen {
	private FreezeScreen() {}

	/** How long each blindness lasts; refreshed every second, so it never runs out while frozen. */
	static final int BLINDNESS_TICKS = 60;
	/** How long each title stays; resent before it would start to fade. */
	static final int TITLE_STAY_TICKS = 70;
	/** How often the title is sent again. */
	static final int TITLE_EVERY_TICKS = 40;

	private static final int ICE = 0x8FD3FF;
	private static final int TEXT = 0xFFFFFF;
	private static final int MUTED = 0xC8CDD2;

	/** The big line. */
	public static Component title() {
		return Component.literal("YOU ARE FROZEN")
				.setStyle(Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(ICE)));
	}

	/** Under it: what to do. */
	public static Component subtitle(String invite) {
		return Component.literal(hasInvite(invite)
						? "Join our Discord and contact staff"
						: "Contact a member of staff")
				.setStyle(Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(TEXT)));
	}

	/** Above the hotbar: where the Discord is, and that leaving is not a way out. */
	public static Component actionBar(String invite) {
		String where = hasInvite(invite) ? invite.strip() + "  |  " : "";
		return Component.literal(where + "Leaving the server is reported to staff")
				.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(MUTED)));
	}

	/**
	 * The one chat line a frozen player gets, and only when there is an invite to give: the title cannot
	 * be clicked, and a player told to join a Discord needs a way to reach it. Null without an invite.
	 */
	public static MutableComponent inviteLine(String invite) {
		if (!hasInvite(invite)) return null;
		return Component.literal("Join our Discord: ")
				.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(TEXT)))
				.append(io.github.alphain24.staffcore.modules.punish.PunishmentModule.inviteText(invite.strip()));
	}

	static boolean hasInvite(String invite) {
		return invite != null && !invite.isBlank();
	}

	/** The title, subtitle and action bar, held on screen until the next send. */
	public static void show(ServerPlayer player, String invite) {
		if (player.hasDisconnected()) return;
		player.connection.send(new ClientboundSetTitlesAnimationPacket(0, TITLE_STAY_TICKS, 10));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle(invite)));
		player.connection.send(new ClientboundSetTitleTextPacket(title()));
		player.connection.send(new ClientboundSetActionBarTextPacket(actionBar(invite)));
	}

	/**
	 * Blindness that is StaffCore's: ambient, without particles or an icon, which is also how
	 * {@link #clear} tells it apart from blindness the player got some other way.
	 */
	public static void blind(ServerPlayer player) {
		player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLINDNESS_TICKS, 0, true, false, false));
	}

	/** Whether this blindness is the kind {@link #blind} gives. */
	static boolean ours(MobEffectInstance effect) {
		return effect != null && effect.isAmbient() && !effect.isVisible() && !effect.showIcon()
				&& effect.getDuration() <= BLINDNESS_TICKS;
	}

	/** Takes the screen away: the titles, and the blindness if it is ours. */
	public static void clear(ServerPlayer player) {
		if (ours(player.getEffect(MobEffects.BLINDNESS))) player.removeEffect(MobEffects.BLINDNESS);
		if (player.hasDisconnected()) return;
		player.connection.send(new ClientboundClearTitlesPacket(true));
		player.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
	}
}
