package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.api.DiscordBotStatus;
import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.gui.menu.SectionMenu;
import io.github.alphain24.staffcore.gui.menu.StaffPanelMenu;
import io.github.alphain24.staffcore.gui.menu.StaffSections;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The Discord section of the staff panel on a real server: every staff member can see whether the bot
 * is running, and only an owner sees what is wrong with it, which channels it posts to, and who is linked.
 */
public class DiscordPanelTests {

	private static final int BOT = 10;
	private static final int CHANNELS = 12;
	private static final int LINKED = 13;
	/** The Discord button on the panel itself. */
	private static final int PANEL_BUTTON = 25;

	private static final String PROBLEM = "the bot cannot send messages in the reports channel";

	private static final DiscordBotStatus RUNNING = new DiscordBotStatus(DiscordBotStatus.Phase.RUNNING,
			"connected as TestBot in Test Server", List.of(PROBLEM),
			List.of(new DiscordBotStatus.Channel("punishments", true, "posting"),
					new DiscordBotStatus.Channel("reports", false, PROBLEM),
					new DiscordBotStatus.Channel("staff-chat", false, "not set")),
			42);

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

	/** An item's name and every lore line, as one string to search. */
	private static String text(ServerPlayer viewer, int slot) {
		ItemStack item = viewer.containerMenu.getSlot(slot).getItem();
		ItemLore lore = item.get(DataComponents.LORE);
		return item.getHoverName().getString() + " / " + (lore == null ? ""
				: lore.lines().stream().map(Component::getString).collect(Collectors.joining(" / ")));
	}

	/**
	 * One test, in steps, because the bot's status is one value for the whole server: two tests setting
	 * it at once would each read the other's.
	 */
	@GameTest
	public void everyStaffMemberSeesWhetherTheBotRunsAndOnlyAnOwnerSeesWhatIsWrong(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer owner = Harness.namedPlayer(helper);
		String staffGroup = group(staff, Nodes.STAFF_GUI);
		String ownerGroup = group(owner, Nodes.STAFF_GUI, Nodes.RELOAD);
		// No companion runs in the game tests, so nothing else reports; this stands in for it.
		StaffCoreApi.reportDiscordBot(() -> RUNNING);
		Runnable cleanUp = () -> {
			StaffCoreApi.reportDiscordBot(null);
			ungroup(staff, staffGroup);
			ungroup(owner, ownerGroup);
		};

		helper.startSequence()
				.thenExecute(() -> {
					StaffPanelMenu.open(staff);
					StaffSections.discord(owner);
				})
				.thenExecuteAfter(2, () -> guarded(cleanUp, () -> {
					Harness.check(helper, text(staff, PANEL_BUTTON).contains("Bot: running"),
							"the panel button does not say whether the bot runs: " + text(staff, PANEL_BUTTON));
					// The section used to be for owners only; linking is for everybody.
					StaffSections.discord(staff);
				}))
				.thenExecuteAfter(2, () -> guarded(cleanUp, () -> {
					Harness.check(helper, staff.containerMenu instanceof SectionMenu,
							"staff.gui alone did not open the Discord section");
					Harness.check(helper, owner.containerMenu instanceof SectionMenu,
							"the Discord section did not open for the owner");

					String seen = text(staff, BOT);
					Harness.check(helper, seen.contains("Bot: Running"), "the bot's state is not shown: " + seen);
					Harness.check(helper, seen.contains("42 ms"), "the ping is not shown: " + seen);
					Harness.check(helper, seen.contains("1 thing(s) for an admin"),
							"staff are not told something is wrong: " + seen);
					Harness.check(helper, !seen.contains(PROBLEM), "staff were shown an owner's detail: " + seen);
					Harness.check(helper, text(staff, CHANNELS).contains("Needs " + Nodes.RELOAD),
							"the channels are not locked for staff: " + text(staff, CHANNELS));
					Harness.check(helper, text(staff, LINKED).contains("Needs " + Nodes.RELOAD),
							"who is linked is not locked for staff: " + text(staff, LINKED));

					String owned = text(owner, BOT);
					Harness.check(helper, owned.contains(PROBLEM), "the owner is not shown what is wrong: " + owned);
					String channels = text(owner, CHANNELS);
					Harness.check(helper, channels.contains("#punishments — posting"),
							"a working channel is not shown: " + channels);
					Harness.check(helper, channels.contains("#staff-chat — not set"),
							"an unset channel is not shown: " + channels);
					Harness.check(helper, text(owner, LINKED).contains("linked"),
							"the owner cannot see the linked accounts entry: " + text(owner, LINKED));

					// A companion whose report throws must not stop the section opening, or put what it
					// threw on screen.
					StaffCoreApi.reportDiscordBot(() -> {
						throw new IllegalStateException("token=abc.def.ghi");
					});
					owner.closeContainer();
					StaffSections.discord(owner);
				}))
				.thenExecuteAfter(2, () -> guarded(cleanUp, () -> {
					Harness.check(helper, owner.containerMenu instanceof SectionMenu,
							"a failing companion stopped the section opening");
					String seen = text(owner, BOT);
					Harness.check(helper, seen.contains("Bot: Not working"), "a failing report is not shown: " + seen);
					Harness.check(helper, seen.contains("IllegalStateException"), "the failure is not named: " + seen);
					Harness.check(helper, !seen.contains("abc.def.ghi"),
							"the companion's exception message reached the screen: " + seen);
					cleanUp.run();
				}))
				.thenSucceed();
	}

	/** Runs a step, and cleans up if it fails, so a failed check leaves nothing set for other tests. */
	private static void guarded(Runnable cleanUp, Runnable step) {
		try {
			step.run();
		} catch (RuntimeException | Error e) {
			cleanUp.run();
			throw e;
		}
	}
}
