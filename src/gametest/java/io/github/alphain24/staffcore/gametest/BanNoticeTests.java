package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.appeal.BanNotice;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.UUID;

/**
 * The appeal window, on a real server with a real ban.
 * <p>
 * A client cannot be driven through connection setup from a gametest, so what these prove is
 * the two things that would be dangerous or broken without anybody noticing: that the ban is
 * still refused everywhere except the one deferred call, and that the window is a packet the
 * client will accept rather than a protocol error that kicks it.
 */
public class BanNoticeTests {

	private static boolean isBanScreen(Component refusal) {
		return refusal != null && refusal.getString().contains("You are banned");
	}

	private static NameAndId banned(GameTestHelper helper) {
		NameAndId target = new NameAndId(UUID.randomUUID(), "BanNoticeT");
		// As the console: the server owner's own hand, so no staff rate limit that another
		// test in the batch might also be spending can refuse it.
		Punishment ban = Mods.punish().apply(Harness.server(helper), target, "CONSOLE",
				PunishmentType.BAN, 3_600_000L, "gametest ban", null, null,
				io.github.alphain24.staffcore.permission.Actor.console());
		Harness.check(helper, ban != null, "the test ban was not stored");
		return target;
	}

	@GameTest
	public void onlyTheLoginStageCheckIsEverDeferred(GameTestHelper helper) {
		NameAndId target = banned(helper);
		PlayerList players = Harness.server(helper).getPlayerList();
		InetSocketAddress address = new InetSocketAddress("127.0.0.1", 25565);

		Harness.check(helper, isBanScreen(players.canPlayerLogin(address, target)),
				"a banned profile was not refused by an ordinary check");

		// The login stage. Whatever vanilla says after — a full server, say — it must not be
		// the ban screen, because the ban was handed on to the window.
		Component atLogin = BanNotice.atLogin(() -> players.canPlayerLogin(address, target));
		Harness.check(helper, !isBanScreen(atLogin) && BanNotice.isPending(target.id()),
				"the login-stage check did not defer the ban to the appeal window");

		// The end-of-setup check, on the same thread straight afterwards. This is the one that
		// keeps banned players out, and it has to refuse exactly as before.
		Harness.check(helper, isBanScreen(players.canPlayerLogin(address, target)),
				"the check after the login stage let a banned profile in");

		// And after a login check that throws.
		try {
			BanNotice.atLogin(() -> {
				throw new IllegalStateException("simulated failure inside the login check");
			});
		} catch (IllegalStateException expected) {
			// the point is what happens next
		}
		Harness.check(helper, isBanScreen(players.canPlayerLogin(address, target)),
				"a login check that threw left the defer switched on");

		BanNotice.forget(target.id());
		helper.succeed();
	}

	@GameTest
	public void theWindowIsAPacketTheClientAccepts(GameTestHelper helper) {
		NameAndId target = banned(helper);
		Punishment ban = Mods.punish().activeBan(target.id());
		Harness.check(helper, ban != null && ban.isAppealable(),
				"a new ban should carry an appeal code");

		ClientboundShowDialogPacket packet = new ClientboundShowDialogPacket(
				Holder.direct(BanNotice.dialogFor(ban, URI.create("https://discord.gg/example"))));

		// The codec connection setup really uses. A dialog it rejects is not an error the
		// server sees: the client drops the connection with a protocol error and no reason.
		ByteBuf buffer = Unpooled.buffer();
		ClientboundShowDialogPacket.CONTEXT_FREE_STREAM_CODEC.encode(buffer, packet);
		ClientboundShowDialogPacket decoded = ClientboundShowDialogPacket.CONTEXT_FREE_STREAM_CODEC
				.decode(buffer);

		Harness.check(helper, decoded.dialog().value() instanceof MultiActionDialog,
				"the dialog did not come back as the window that was sent");
		MultiActionDialog window = (MultiActionDialog) decoded.dialog().value();
		Harness.checkEquals(helper, 2, window.actions().size(),
				"buttons after a round trip (Discord and appeal code)");
		Harness.check(helper, window.exitAction().isPresent(), "the Leave button was lost");
		helper.succeed();
	}
}
