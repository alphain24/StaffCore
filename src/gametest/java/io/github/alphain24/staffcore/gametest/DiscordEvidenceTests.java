package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.DiscordEvidenceFile;
import io.github.alphain24.staffcore.api.DiscordEvidenceFiling;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.cases.CaseEvidence;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Phase 6.6 on a running server: evidence filed from Discord, behind the case permission, with only files
 * the companion really kept recorded as kept, and nothing that points outside the evidence folder.
 */
public class DiscordEvidenceTests {

	private static String snowflake() {
		return String.valueOf(100_000_000_000_000_000L + (long) (Math.random() * 800_000_000_000_000_000L));
	}

	private static String group(ServerPlayer player, String... nodes) {
		String name = "gametest-" + player.getUUID().toString().substring(0, 8);
		PermissionGroups groups = PermissionGroups.get();
		groups.groups.put(name, new ArrayList<>(List.of(nodes)));
		groups.players.put(player.getUUID().toString(), name);
		return name;
	}

	private static void ungroup(ServerPlayer player, String name) {
		PermissionGroups groups = PermissionGroups.get();
		groups.players.remove(player.getUUID().toString());
		groups.groups.remove(name);
	}

	private static DiscordUser link(GameTestHelper helper, ServerPlayer player, String... roleNodes) {
		DiscordUser user = new DiscordUser(snowflake(), "dc_" + Harness.name(player), Set.of(roleNodes));
		String code = Mods.discord().links().issueCode(player.getUUID(), Harness.name(player));
		Harness.check(helper, DiscordAccess.link(user, code).join().done(), "linking failed");
		return user;
	}

	/** Writes a file into the evidence folder the way the companion does, and describes it. */
	private static DiscordEvidenceFile kept(String caseId, String name, String text) throws Exception {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		String stored = caseId + "/" + sha + ".txt";
		Path path = DiscordAccess.evidenceFolder().resolve(stored);
		Files.createDirectories(path.getParent());
		Files.write(path, bytes);
		return new DiscordEvidenceFile(name, "text/plain", bytes.length, sha, stored, null);
	}

	@GameTest
	public void evidenceFromDiscordIsFiledBehindTheCasePermission(GameTestHelper helper) throws Exception {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer suspect = Harness.namedPlayer(helper);
		String group = group(staff, Nodes.STAFF_GUI);
		try {
			String caseId = Mods.cases().store().openManually(suspect.getUUID(), Harness.name(suspect), "Console",
					"gametest case", 40, CaseCategory.OTHER);
			DiscordUser user = link(helper, staff, Nodes.STAFF_GUI);

			// Nobody without the case permission on both sides gets as far as a download.
			DiscordUser stranger = new DiscordUser(snowflake(), "stranger", Set.of(Nodes.STAFF_GUI));
			Harness.check(helper, !DiscordAccess.mayFileEvidence(stranger, caseId).join().done(),
					"an unlinked account may file evidence");
			DiscordUser noRole = new DiscordUser(user.id(), user.name(), Set.of(Nodes.CHAT));
			Harness.check(helper, !DiscordAccess.mayFileEvidence(noRole, caseId).join().done(),
					"a role without the case permission may file evidence");
			var may = DiscordAccess.mayFileEvidence(user, caseId.toLowerCase(java.util.Locale.ROOT)).join();
			Harness.check(helper, may.done() && may.message().equals(caseId), "the case id was not given back as stored: " + may);
			Harness.check(helper, !DiscordAccess.mayFileEvidence(user, "NOPE0000").join().done(), "a case that does not exist");

			DiscordEvidenceFile real = kept(caseId, "chat log.txt", "Steve: I use x-ray");
			DiscordEvidenceFile outside = new DiscordEvidenceFile("sneaky.txt", "text/plain", 5,
					"00", "../../server.properties", null);
			DiscordEvidenceFile missing = new DiscordEvidenceFile("gone.txt", "text/plain", 5, "ab".repeat(32),
					caseId + "/" + "ab".repeat(32) + ".txt", null);
			DiscordEvidenceFile tooBig = new DiscordEvidenceFile("huge.mp4", "video/mp4", 900_000_000L, null, null,
					"larger than the 25 MB this server keeps");

			var filed = DiscordAccess.fileEvidence(user, new DiscordEvidenceFiling(caseId, "he admits it",
					"https://discord.com/channels/1/2/3", snowflake(), "Steve_", 1_700_000_000_000L,
					"I use x-ray", "message", List.of(real, outside, missing, tooBig))).join();
			Harness.check(helper, filed.done() && filed.message().contains("1 of 4"), "filing failed: " + filed.message());

			CaseEvidence.Item item = Mods.cases().evidence().forCase(caseId).stream()
					.filter(i -> i.kind() == CaseEvidence.Kind.DISCORD).findFirst().orElse(null);
			Harness.check(helper, item != null && item.describe().contains("he admits it"), "no Discord evidence on the case");
			Harness.checkEquals(helper, Harness.name(staff), item.addedBy(), "who filed it");

			var detail = DiscordAccess.evidenceItem(user, caseId, item.id()).join();
			Harness.check(helper, detail.answered(), "the evidence could not be read back: " + detail.refusal());
			var files = detail.value().files();
			Harness.checkEquals(helper, 4, files.size(), "files recorded");
			Harness.check(helper, files.get(0).storedPath() != null && DiscordAccess.keptFile(files.get(0)) != null
					&& Files.isRegularFile(DiscordAccess.keptFile(files.get(0))), "the real file is not recorded as kept");
			Harness.check(helper, files.get(1).storedPath() == null, "a path outside the evidence folder was recorded as kept");
			Harness.check(helper, files.get(2).storedPath() == null, "a file that is not there was recorded as kept");
			Harness.check(helper, files.get(3).notKeptWhy().contains("larger"), "why a file was not kept was lost");
			Harness.checkEquals(helper, "I use x-ray", detail.value().content(), "the message text");
			Harness.check(helper, DiscordAccess.keptFile(outside) == null, "keptFile resolved a path outside the folder");

			// A note alone is evidence; nothing at all is not.
			Harness.check(helper, DiscordAccess.fileEvidence(user, new DiscordEvidenceFiling(caseId, "seen at spawn",
					null, null, null, null, null, "command", List.of())).join().done(), "a note alone was refused");
			Harness.check(helper, !DiscordAccess.fileEvidence(user, new DiscordEvidenceFiling(caseId, "  ",
					null, null, null, null, null, "command", List.of())).join().done(), "an empty filing was accepted");

			// Reading it needs the case permission too.
			Harness.check(helper, !DiscordAccess.evidenceItem(noRole, caseId, item.id()).join().answered(),
					"evidence was read without the case permission");
		} finally {
			ungroup(staff, group);
		}
		helper.succeed();
	}
}
