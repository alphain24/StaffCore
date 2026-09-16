package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.ConfigFolder;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.permission.Rank;
import me.lucko.fabric.api.permissions.v0.OfflinePermissionCheckEvent;
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.ServerOpListEntry;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StaffCore on a server where the permissions API is on the classpath and no permissions mod is
 * listening, which is what a server gets from any mod that ships the API inside its own jar.
 * <p>
 * The game-test run carries the real fabric-permissions-api for exactly this. Before the fix, an
 * operator on such a server held nothing, {@code /staff} was missing from the command tree, and
 * {@code permissions.json} was never written.
 * <p>
 * Two of these tests register a stand-in permissions mod. It answers only about accounts these
 * tests made, and says {@code DEFAULT} about everybody else, which is what the API says with
 * nobody listening — so it changes nothing for the other tests running alongside.
 */
public class PermissionApiTests {

	/** Accounts the stand-in refuses {@code staff.gui}. */
	private static final Set<UUID> DENIED = ConcurrentHashMap.newKeySet();
	/** Accounts the stand-in grants {@code staff.gui}. */
	private static final Set<UUID> GRANTED = ConcurrentHashMap.newKeySet();
	/** Offline accounts the stand-in is still loading, and never finishes. */
	private static final Set<UUID> LOADING = ConcurrentHashMap.newKeySet();
	private static boolean listening;

	private static synchronized void standInPermissionsMod() {
		if (listening) return;
		listening = true;
		PermissionCheckEvent.EVENT.register((source, permission) -> {
			if (!(source instanceof CommandSourceStack stack) || stack.getEntity() == null
					|| !Nodes.STAFF_GUI.equals(permission)) {
				return TriState.DEFAULT;
			}
			UUID id = stack.getEntity().getUUID();
			if (DENIED.contains(id)) return TriState.FALSE;
			if (GRANTED.contains(id)) return TriState.TRUE;
			return TriState.DEFAULT;
		});
		OfflinePermissionCheckEvent.EVENT.register((id, permission) -> LOADING.contains(id)
				? new CompletableFuture<>() : CompletableFuture.completedFuture(TriState.DEFAULT));
	}

	private static void op(MinecraftServer server, ServerPlayer player) {
		server.getPlayerList().getOps().add(new ServerOpListEntry(player.nameAndId(),
				LevelBasedPermissionSet.OWNER, false));
	}

	private static String group(String key, String... nodes) {
		String name = "gametest-" + key.substring(0, 8).toLowerCase(java.util.Locale.ROOT);
		PermissionGroups groups = PermissionGroups.get();
		groups.groups.put(name, new ArrayList<>(List.of(nodes)));
		groups.players.put(key.toLowerCase(java.util.Locale.ROOT), name);
		return name;
	}

	private static void ungroup(String key, String name) {
		PermissionGroups groups = PermissionGroups.get();
		groups.players.remove(key.toLowerCase(java.util.Locale.ROOT));
		groups.groups.remove(name);
	}

	private static boolean staffCommandOffered(MinecraftServer server, ServerPlayer player) {
		var node = server.getCommands().getDispatcher().getRoot().getChild("staff");
		return node != null && node.canUse(player.createCommandSourceStack());
	}

	@GameTest
	public void anOperatorHasStaffWhenTheApiIsPresentAndNothingAnswers(GameTestHelper helper) {
		MinecraftServer server = Harness.server(helper);
		Harness.check(helper, Permissions.apiPresent(),
				"the permissions API is not on the game-test classpath, so this proves nothing");
		Harness.check(helper, PermissionGroups.get() != null,
				"permissions.json was not loaded because the permissions API is present");
		Harness.check(helper, Files.exists(ConfigFolder.permissions()), "permissions.json was never written");
		Harness.check(helper, PermissionGroups.get().operatorsBypass,
				"operatorsBypass is off in the test server, so what follows would fail for the wrong reason");

		ServerPlayer admin = Harness.namedPlayer(helper);
		ServerPlayer player = Harness.namedPlayer(helper);
		ServerPlayer helperStaff = Harness.namedPlayer(helper);
		op(server, admin);
		String group = group(helperStaff.getUUID().toString(), Nodes.STAFF_GUI);
		try {
			Harness.check(helper, Mc.isModerator(admin),
					"the test could not make this player an operator, so what follows would pass for the wrong reason");
			Harness.check(helper, Permissions.check(admin, Nodes.STAFF_GUI),
					"an operator was refused staff.gui because the permissions API is present");
			Harness.check(helper, Permissions.check(admin, Nodes.RELOAD), "an operator was refused staff.reload");
			Harness.check(helper, staffCommandOffered(server, admin), "/staff is missing for an operator");

			Harness.check(helper, !Permissions.check(player, Nodes.STAFF_GUI),
					"a player in no group and not an operator was given staff.gui");
			Harness.check(helper, !staffCommandOffered(server, player), "/staff was offered to an ordinary player");

			Harness.check(helper, Permissions.check(helperStaff, Nodes.STAFF_GUI),
					"a player whose group grants staff.gui was refused it");
			Harness.check(helper, !Permissions.check(helperStaff, Nodes.RELOAD),
					"a player was given a node their group does not hold");
			Harness.check(helper, staffCommandOffered(server, helperStaff), "/staff is missing for staff in a group");
		} finally {
			server.getPlayerList().getOps().remove(admin.nameAndId());
			ungroup(helperStaff.getUUID().toString(), group);
		}
		helper.succeed();
	}

	@GameTest
	public void aPermissionsModsAnswerWinsOverTheFileAndOperators(GameTestHelper helper) {
		standInPermissionsMod();
		MinecraftServer server = Harness.server(helper);

		ServerPlayer deniedOperator = Harness.namedPlayer(helper);
		ServerPlayer grantedPlayer = Harness.namedPlayer(helper);
		ServerPlayer deniedInGroup = Harness.namedPlayer(helper);
		op(server, deniedOperator);
		DENIED.add(deniedOperator.getUUID());
		GRANTED.add(grantedPlayer.getUUID());
		DENIED.add(deniedInGroup.getUUID());
		String group = group(deniedInGroup.getUUID().toString(), Nodes.STAFF_GUI, Nodes.FREEZE);
		try {
			Harness.check(helper, !Permissions.check(deniedOperator, Nodes.STAFF_GUI),
					"an operator kept a node the permissions mod refused");
			Harness.check(helper, Permissions.check(deniedOperator, Nodes.FREEZE),
					"an operator lost a node the permissions mod says nothing about");

			Harness.check(helper, Permissions.check(grantedPlayer, Nodes.STAFF_GUI),
					"a node the permissions mod granted was refused");
			Harness.check(helper, !Permissions.check(grantedPlayer, Nodes.FREEZE),
					"a node nobody granted was given to an ordinary player");

			Harness.check(helper, !Permissions.check(deniedInGroup, Nodes.STAFF_GUI),
					"the groups file overrode a node the permissions mod refused");
			Harness.check(helper, Permissions.check(deniedInGroup, Nodes.FREEZE),
					"the groups file stopped answering for a node the permissions mod leaves unset");
		} finally {
			server.getPlayerList().getOps().remove(deniedOperator.nameAndId());
			ungroup(deniedInGroup.getUUID().toString(), group);
		}
		helper.succeed();
	}

	@GameTest
	public void anOfflineAccountIsAnsweredUnlessThePermissionsModIsStillLoadingIt(GameTestHelper helper) {
		standInPermissionsMod();
		MinecraftServer server = Harness.server(helper);

		UUID ready = UUID.randomUUID();
		UUID loading = UUID.randomUUID();
		LOADING.add(loading);
		String readyGroup = group(ready.toString(), Nodes.STAFF_GUI);
		String loadingGroup = group(loading.toString(), Nodes.STAFF_GUI);
		try {
			Rank.Held answered = Rank.inGame(server, ready, "offline-ready");
			Harness.check(helper, answered.complete(), "an offline account nobody is loading was left unanswered");
			Harness.check(helper, answered.nodes().contains(Nodes.STAFF_GUI),
					"an offline account lost what its group grants: " + answered.nodes());
			Harness.check(helper, !answered.nodes().contains(Nodes.RELOAD),
					"an offline account was given what its group does not grant");

			Rank.Held pending = Rank.inGame(server, loading, "offline-loading");
			Harness.check(helper, !pending.complete() && pending.nodes().isEmpty(),
					"an account the permissions mod is still loading was answered anyway: " + pending.nodes());
			Harness.check(helper, !Rank.of(server, loading, "offline-loading").complete(),
					"a target the permissions mod is still loading was called fully known");
			Harness.check(helper, Rank.of(server, ready, "offline-ready").complete(),
					"a target nobody is loading was called unknown");
		} finally {
			LOADING.remove(loading);
			ungroup(ready.toString(), readyGroup);
			ungroup(loading.toString(), loadingGroup);
		}
		helper.succeed();
	}
}
