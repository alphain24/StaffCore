package io.github.alphain24.staffcore.gui;

import io.github.alphain24.staffcore.gui.NavigationHistory.Frame;
import io.github.alphain24.staffcore.gui.NavigationHistory.Move;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The rules the back arrow follows, walked through the paths that made a history necessary.
 * <p>
 * Screens are stand-in classes, because the rules compare classes and nothing else — which is
 * also why these run without a server.
 */
class NavigationHistoryTest {

	// Stand-ins for the real screens. Only their identity matters.
	static final class Panel {}
	static final class Section {}
	static final class PlayerList {}
	static final class PlayerFile {}
	static final class Punish {}
	static final class Duration {}
	static final class Reason {}
	static final class Confirm {}
	static final class Alts {}
	static final class Invsee {}
	static final class ItemDetails {}

	private static Frame<String> frame(Class<?> screen, String what) {
		return new Frame<>(screen, true, what);
	}

	private static Frame<String> confirm(String what) {
		return new Frame<>(Confirm.class, false, what);
	}

	@SafeVarargs
	private static NavigationHistory<String> walk(Frame<String>... path) {
		NavigationHistory<String> history = new NavigationHistory<>();
		history.apply(Move.ROOT, path[0]);
		for (int i = 1; i < path.length; i++) {
			history.apply(Move.FORWARD, path[i]);
		}
		return history;
	}

	@Test
	@DisplayName("back from the player list returns to the section it was opened from")
	void backFollowsTheSectionActuallyUsed() {
		// The bug: the player list is reached from Players, X-ray and Session replay, and its
		// arrow named the panel. Whichever section it came through is where back should go.
		NavigationHistory<String> history = walk(
				frame(Panel.class, "panel"),
				frame(Section.class, "X-ray & cheats"),
				frame(PlayerList.class, "list: xray"));

		assertEquals("X-ray & cheats", history.previous().payload());
		assertEquals("X-ray & cheats", history.stepBack().payload());
		assertEquals("panel", history.stepBack().payload());
	}

	@Test
	@DisplayName("back from the root has nowhere to go, and changes nothing")
	void nothingBehindTheRoot() {
		NavigationHistory<String> history = walk(frame(Panel.class, "panel"));

		assertNull(history.previous());
		assertNull(history.stepBack());
		assertEquals(1, history.size());
	}

	@Test
	@DisplayName("stepping back leaves the destination on top, so showing it replaces it")
	void theDestinationIsRedrawnInPlace() {
		NavigationHistory<String> history = walk(
				frame(Panel.class, "panel"),
				frame(Section.class, "Players"),
				frame(PlayerList.class, "list"));

		Frame<String> to = history.stepBack();
		// Showing it again arrives as a REPLACE of the same screen.
		history.apply(Move.REPLACE, frame(Section.class, "Players, redrawn"));

		assertEquals(2, history.size());
		assertEquals("Players, redrawn", history.current().payload());
		assertEquals(Section.class, to.screen());
	}

	@Test
	@DisplayName("a finished punishment returns to the file, and the flow is not behind it")
	void aReturnDropsTheFinishedFlow() {
		NavigationHistory<String> history = walk(
				frame(Panel.class, "panel"),
				frame(Section.class, "Punishments"),
				frame(PlayerList.class, "list"),
				frame(PlayerFile.class, "Steve"),
				frame(Punish.class, "punish"),
				frame(Duration.class, "duration"),
				frame(Reason.class, "reason"));

		// The reason is picked, the punishment lands, and the menu goes back to the file.
		history.apply(Move.UP, frame(PlayerFile.class, "Steve, after"));

		assertEquals("Steve, after", history.current().payload());
		assertEquals("list", history.stepBack().payload());
	}

	@Test
	@DisplayName("a confirmation is never stepped back into")
	void confirmationsAreNotReturnable() {
		NavigationHistory<String> history = walk(
				frame(Panel.class, "panel"),
				frame(Section.class, "World"));
		history.apply(Move.FORWARD, confirm("roll back 400 blocks?"));

		// Confirming opens the result. The confirm screen must not sit under it, or back
		// would offer "Do it." a second time.
		history.apply(Move.FORWARD, frame(PlayerList.class, "the result"));

		assertEquals(3, history.size());
		assertEquals("World", history.previous().payload());
		assertEquals("World", history.stepBack().payload());
	}

	@Test
	@DisplayName("a same-screen hop is a refresh, not a new level")
	void sameScreenReplacesRatherThanStacks() {
		NavigationHistory<String> history = walk(
				frame(Panel.class, "panel"),
				frame(PlayerList.class, "page of everyone"));
		history.apply(Move.FORWARD, frame(PlayerList.class, "filtered"));

		assertEquals(2, history.size());
		assertEquals("panel", history.stepBack().payload());
	}

	@Test
	@DisplayName("a linked account's file stacks on top of the first one")
	void loopsThroughAltsStillStepBackOneAtATime() {
		NavigationHistory<String> history = walk(
				frame(Panel.class, "panel"),
				frame(PlayerFile.class, "Steve"),
				frame(Alts.class, "Steve's alts"),
				frame(PlayerFile.class, "Alex"));

		assertEquals("Steve's alts", history.stepBack().payload());
		assertEquals("Steve", history.stepBack().payload());
	}

	@Test
	@DisplayName("a silent hop to a new screen is a step deeper; to a known one, a return")
	void replaceReadsTheHistoryToDecide() {
		NavigationHistory<String> history = walk(
				frame(Panel.class, "panel"),
				frame(PlayerFile.class, "Steve"));

		// Invsee and item details open silently because they play their own sound.
		history.apply(Move.REPLACE, frame(Invsee.class, "inventory"));
		history.apply(Move.REPLACE, frame(ItemDetails.class, "a sword"));
		assertEquals(4, history.size());

		// Item details' own back redraws the inventory silently: a return, not a fifth level.
		history.apply(Move.REPLACE, frame(Invsee.class, "inventory, redrawn"));
		assertEquals(3, history.size());
		assertEquals("Steve", history.previous().payload());
	}

	@Test
	@DisplayName("returning to a screen that is not in the path starts the path there")
	void aReturnToSomewhereUnvisitedStartsAgain() {
		// Opened by /staff punish, so the file was never on the path.
		NavigationHistory<String> history = new NavigationHistory<>();
		history.apply(Move.FORWARD, frame(Punish.class, "punish"));
		history.apply(Move.FORWARD, frame(Reason.class, "reason"));

		history.apply(Move.UP, frame(PlayerFile.class, "Steve"));

		assertEquals(1, history.size());
		assertNull(history.stepBack(), "so the file's own fallback decides, not a stale flow");
	}

	@Test
	@DisplayName("opening the panel fresh forgets the old path")
	void rootForgets() {
		NavigationHistory<String> history = walk(
				frame(Panel.class, "panel"),
				frame(Section.class, "Players"),
				frame(PlayerList.class, "list"));
		history.apply(Move.ROOT, frame(Panel.class, "panel again"));

		assertEquals(1, history.size());
		assertNull(history.stepBack());
	}

	@Test
	@DisplayName("the path is bounded, and loses its oldest end first")
	void bounded() {
		NavigationHistory<String> history = new NavigationHistory<>();
		history.apply(Move.ROOT, frame(Panel.class, "panel"));
		for (int i = 0; i < NavigationHistory.LIMIT * 2; i++) {
			history.apply(Move.FORWARD, frame(i % 2 == 0 ? PlayerFile.class : Alts.class,
					"hop " + i));
		}

		assertEquals(NavigationHistory.LIMIT, history.size());
		assertEquals("hop " + (NavigationHistory.LIMIT * 2 - 2), history.previous().payload());
	}

	@Test
	@DisplayName("a breadcrumb title becomes a label without the brand")
	void labels() {
		assertEquals("the staff panel", Guis.label(Theme.title()));
		assertEquals("Punish › Notch", Guis.label(Theme.title("Punish", "Notch")));
	}
}
