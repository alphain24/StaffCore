package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.menu.PlayerListMenu;
import io.github.alphain24.staffcore.gui.menu.SectionMenu;
import io.github.alphain24.staffcore.gui.menu.StaffPanelMenu;
import io.github.alphain24.staffcore.gui.menu.StaffSections;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.component.ItemLore;

import java.util.stream.Collectors;

/**
 * The back arrow, on a real server with real containers.
 * <p>
 * The rules are tested headless in {@code NavigationHistoryTest}. What only a server can show
 * is the wiring: that an open is recorded only once the container really changed, that the
 * class compared is the screen's, and that a click on the arrow reaches the history at all.
 * The failure this replaces was not a wrong rule — it was every arrow ignoring the path.
 * <p>
 * Every screen change is deferred a tick by {@link Guis}, so each step waits before looking.
 */
public class NavigationTests {

	/** Both the section screens and the list put the arrow here. */
	private static final int BACK = 45;

	private static void expect(GameTestHelper helper, ServerPlayer staff, Class<?> screen,
			String whatWentWrong) {

		Harness.check(helper, screen.isInstance(staff.containerMenu),
				whatWentWrong + " — open screen is "
						+ staff.containerMenu.getClass().getSimpleName());
	}

	private static void click(ServerPlayer staff, int slot, ContainerInput how) {
		((Gui) staff.containerMenu).clicked(slot, 0, how, staff);
	}

	private static String lore(ServerPlayer staff, int slot) {
		ItemLore lore = staff.containerMenu.getSlot(slot).getItem().get(DataComponents.LORE);
		return lore == null ? "" : lore.lines().stream().map(Component::getString)
				.collect(Collectors.joining(" / "));
	}

	@GameTest
	public void backReturnsOneLevelAlongThePathTaken(GameTestHelper helper) {
		ServerPlayer staff = Harness.mockPlayer(helper);

		helper.startSequence()
				.thenExecute(() -> StaffPanelMenu.open(staff))
				.thenExecuteAfter(2, () -> {
					expect(helper, staff, StaffPanelMenu.class, "the panel did not open");
					StaffSections.antiCheat(staff);
				})
				.thenExecuteAfter(2, () -> {
					expect(helper, staff, SectionMenu.class, "the section did not open");
					PlayerListMenu.open(staff, PlayerListMenu.Purpose.XRAY);
				})
				.thenExecuteAfter(2, () -> {
					expect(helper, staff, PlayerListMenu.class, "the player list did not open");
					// The list's own fallback names the panel. Reached through a section, the
					// arrow has to say the section instead, or it is still lying.
					Harness.check(helper, lore(staff, BACK).contains("X-ray & cheats"),
							"the back arrow does not name the section it returns to: "
									+ lore(staff, BACK));
					click(staff, BACK, ContainerInput.PICKUP);
				})
				.thenExecuteAfter(2, () -> {
					expect(helper, staff, SectionMenu.class,
							"back from the player list skipped the section it was opened from");
					click(staff, BACK, ContainerInput.PICKUP);
				})
				.thenExecuteAfter(2, () -> expect(helper, staff, StaffPanelMenu.class,
						"back from a section did not reach the panel"))
				.thenSucceed();
	}

	@GameTest
	public void closingTheMenusForgetsThePath(GameTestHelper helper) {
		ServerPlayer staff = Harness.mockPlayer(helper);

		helper.startSequence()
				.thenExecute(() -> StaffPanelMenu.open(staff))
				.thenExecuteAfter(2, () -> StaffSections.players(staff))
				.thenExecuteAfter(2, () -> {
					expect(helper, staff, SectionMenu.class, "the section did not open");
					staff.closeContainer();
					// As a command would open it: nothing on screen before it.
					PlayerListMenu.open(staff, PlayerListMenu.Purpose.INSPECT);
				})
				.thenExecuteAfter(2, () -> {
					expect(helper, staff, PlayerListMenu.class, "the player list did not open");
					click(staff, BACK, ContainerInput.PICKUP);
				})
				// The Players section was closed before the list opened. Returning to it would
				// mean an old path survived the menus closing — the next command-opened screen
				// sending staff back to wherever they were an hour ago.
				.thenExecuteAfter(2, () -> expect(helper, staff, StaffPanelMenu.class,
						"back followed a path through screens that had been closed"))
				.thenSucceed();
	}

	@GameTest
	public void shiftClickHomeStillNeedsThePanelPermission(GameTestHelper helper) {
		// A mock player is not an operator and holds no staff nodes. Sub-screens can be opened
		// without staff.gui, so shift-click is a route to the panel that has to check it too.
		ServerPlayer outsider = Harness.mockPlayer(helper);

		helper.startSequence()
				.thenExecute(() -> PlayerListMenu.open(outsider, PlayerListMenu.Purpose.INSPECT))
				.thenExecuteAfter(2, () -> {
					expect(helper, outsider, PlayerListMenu.class, "the player list did not open");
					click(outsider, BACK, ContainerInput.QUICK_MOVE);
				})
				.thenExecuteAfter(2, () -> expect(helper, outsider, PlayerListMenu.class,
						"shift-click opened the staff panel for somebody without staff.gui"))
				.thenSucceed();
	}

	@GameTest
	public void homeForgetsThePathBehindIt(GameTestHelper helper) {
		ServerPlayer staff = Harness.mockPlayer(helper);

		helper.startSequence()
				.thenExecute(() -> StaffPanelMenu.open(staff))
				.thenExecuteAfter(2, () -> StaffSections.world(staff))
				.thenExecuteAfter(2, () -> {
					expect(helper, staff, SectionMenu.class, "the section did not open");
					StaffPanelMenu.home(staff);
				})
				.thenExecuteAfter(2, () -> {
					expect(helper, staff, StaffPanelMenu.class, "home did not reach the panel");
					// Nothing behind the root, and no fallback: back closes rather than
					// returning to the section home was pressed from.
					Guis.back(staff, null);
				})
				.thenExecuteAfter(2, () -> Harness.check(helper,
						!(staff.containerMenu instanceof Gui),
						"home left the old path behind the panel; back reopened "
								+ staff.containerMenu.getClass().getSimpleName()))
				.thenSucceed();
	}
}
