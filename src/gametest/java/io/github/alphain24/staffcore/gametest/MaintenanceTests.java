package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.control.ControlModule;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Maintenance mode, driven through the gate vanilla actually consults.
 * <p>
 * This is the one hook in the mod marked {@code required: true} — a login gate that fails
 * open is a banned player walking back in, so the server is not allowed to run without it.
 * The boot check confirms it applied; nothing until now confirmed it decides correctly.
 * <p>
 * {@code canPlayerLogin} is called by vanilla before a connection becomes a player, so it can
 * be called here the same way with a made-up address and profile. That is a real exercise of
 * the mixin rather than a test of the module's boolean.
 */
public class MaintenanceTests {

	private static final InetSocketAddress SOMEWHERE =
			new InetSocketAddress("203.0.113.7", 25565);

	/** What vanilla asks. Null means "let them in"; a component is the kick screen. */
	private Component askTheGate(MinecraftServer server, UUID id, String name) {
		return server.getPlayerList().canPlayerLogin(SOMEWHERE, new NameAndId(id, name));
	}

	/**
	 * Whether the refusal is <em>ours</em>.
	 * <p>
	 * Null-versus-not is the wrong question here. The gametest server reports itself full, so
	 * vanilla refuses every login on its own account well after our injection has declined to
	 * — and a test asserting "no refusal" would fail while the gate worked perfectly, then be
	 * "fixed" by weakening it until it stopped testing anything. Matching the actual screen
	 * asks the question that was meant: did maintenance mode turn this away.
	 */
	private boolean refusedByMaintenance(Component answer) {
		return answer != null
				&& answer.getString().equals(ControlModule.maintenanceScreen().getString());
	}

	/**
	 * Runs {@code body} with maintenance mode on, and puts it back.
	 *
	 * <h2>It sets the flag; it does not clear the server</h2>
	 * This was the one genuinely destructive thing in the suite, and it was destroying things.
	 * {@code toggleMaintenance} disconnects every non-staff player in the player list, and
	 * gametests inside a batch run <b>at the same time</b> — so every time this ran it kicked
	 * whatever mock players other tests were part way through using. Five and seven of them,
	 * measured. The tests that lost their player failed with "Failed to invoke test method",
	 * which reads as an unrelated internal error.
	 * <p>
	 * It was believed safe because {@code Harness} said mock players are not in the player
	 * list. They are: {@code makeMockServerPlayerInLevel} calls
	 * {@code PlayerList.placeNewPlayer}, confirmed from the 26.2 bytecode.
	 * <p>
	 * So this sets the flag without the kick. Nothing is lost: what these tests assert is that
	 * {@code canPlayerLogin} refuses the right people, which reads the flag. Emptying the
	 * server is a separate behaviour and would need a test that owns its own player.
	 */
	private void withMaintenance(GameTestHelper helper, Runnable body) {
		ControlModule control = Mods.control();
		MinecraftServer server = Harness.server(helper);

		boolean wasOn = control.isMaintenance();
		if (!wasOn) control.setMaintenance(server, true, false);
		try {
			Harness.check(helper, control.isMaintenance(), "maintenance did not switch on");
			body.run();
		} finally {
			if (control.isMaintenance() != wasOn) control.toggleMaintenance(server);
		}
	}

	@GameTest
	public void maintenanceRefusesAnOrdinaryLogin(GameTestHelper helper) {
		MinecraftServer server = Harness.server(helper);
		UUID stranger = UUID.nameUUIDFromBytes("stranger".getBytes());

		Harness.check(helper, !refusedByMaintenance(askTheGate(server, stranger, "Stranger")),
				"the maintenance screen was shown before maintenance was even on");

		withMaintenance(helper, () -> {
			Harness.check(helper, refusedByMaintenance(askTheGate(server, stranger, "Stranger")),
					"maintenance mode let an ordinary player past the gate, which is the whole "
							+ "of what it is for");
		});

		Harness.check(helper, !refusedByMaintenance(askTheGate(server, stranger, "Stranger")),
				"maintenance stayed on after being switched off, locking everybody out");
		helper.succeed();
	}

	@GameTest
	public void maintenanceStillLetsOperatorsIn(GameTestHelper helper) {
		MinecraftServer server = Harness.server(helper);

		// Without this the setting is a way to lock yourself out of your own server, which
		// is the failure nobody discovers until they need to get back in.
		withMaintenance(helper, () -> {
			ServerPlayer admin = Harness.mockPlayer(helper);
			server.getPlayerList().getOps().add(
					new net.minecraft.server.players.ServerOpListEntry(admin.nameAndId(),
							net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER, false));
			try {
				Harness.check(helper, server.getPlayerList().isOp(admin.nameAndId()),
						"the test could not make this player an operator, so what follows "
								+ "would pass for the wrong reason");
				Harness.check(helper,
						!refusedByMaintenance(askTheGate(server, admin.getUUID(), Harness.name(admin))),
						"maintenance mode locked out an operator");
			} finally {
				server.getPlayerList().getOps().remove(admin.nameAndId());
			}
		});
		helper.succeed();
	}

	/** Makes a mock player an operator, which is what the maintenance node resolves to here. */
	private void asStaff(MinecraftServer server, ServerPlayer player, Runnable body) {
		server.getPlayerList().getOps().add(
				new net.minecraft.server.players.ServerOpListEntry(player.nameAndId(),
						net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER, false));
		try {
			body.run();
		} finally {
			server.getPlayerList().getOps().remove(player.nameAndId());
		}
	}

	@GameTest
	public void theKickTakesPlayersAndLeavesStaff(GameTestHelper helper) {
		MinecraftServer server = Harness.server(helper);
		ServerPlayer player = Harness.mockPlayer(helper);
		ServerPlayer staff = Harness.mockPlayer(helper);

		// Blocking new logins alone is half a feature: without this, whatever the server was
		// closed to fix is still being played through by everybody already on. The half that
		// goes wrong the other way is kicking the staff who turned it on.
		asStaff(server, staff, () -> {
			var targets = ControlModule.kickTargets(java.util.List.of(player, staff));

			Harness.check(helper, targets.contains(player),
					"maintenance would leave an ordinary player online, so closing the server "
							+ "does not close it");
			Harness.check(helper, !targets.contains(staff),
					"maintenance would kick the staff member who turned it on");
			Harness.checkEquals(helper, 1, targets.size(), "wrong number selected");
		});
		helper.succeed();
	}

	@GameTest
	public void aKickedPlayerIsActuallyDisconnected(GameTestHelper helper) {
		ServerPlayer player = Harness.mockPlayer(helper);

		Harness.check(helper, !player.hasDisconnected(), "already gone before we started");

		// The selection above decides who; this is the half that has to actually happen to
		// them. Driven through the same helper the kick uses.
		io.github.alphain24.staffcore.compat.Mc.disconnect(player,
				ControlModule.maintenanceScreen());

		Harness.check(helper, player.hasDisconnected(),
				"the kick selected this player and then did not remove them");
		helper.succeed();
	}

	@GameTest
	public void theKickScreenSaysSomething(GameTestHelper helper) {
		// A refusal with an empty screen is a disconnect with no reason, which reads to a
		// player as the server being broken rather than closed.
		String text = ControlModule.maintenanceScreen().getString();
		Harness.check(helper, text != null && !text.isBlank(),
				"the maintenance kick screen is empty");
		helper.succeed();
	}
}
