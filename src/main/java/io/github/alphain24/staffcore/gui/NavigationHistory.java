package io.github.alphain24.staffcore.gui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * The screens one staff member has stepped through, newest first.
 *
 * <h2>Why back needs a history at all</h2>
 * Every back arrow used to name its destination. That was correct when the panel was one
 * flat page and every screen had exactly one parent. It stopped being correct when the panel
 * grew sections: the player list is reached from Players, from X-ray and from Session replay,
 * and a hardcoded destination can be right for at most one of them. Most arrows pointed at the
 * panel itself, so "back" meant "start again" from anywhere two levels deep.
 * <p>
 * A history is the only thing that knows which parent this visit came through.
 *
 * <h2>How a screen change moves it</h2>
 * Screens are identified by class. A title or a lambda cannot be compared, and the class is
 * what staff mean by "the same screen" — Steve's file and Alex's file are both the file.
 * <ul>
 *   <li>{@link Move#ROOT} — the panel opened fresh. Everything before it is forgotten.</li>
 *   <li>{@link Move#FORWARD} — one level deeper. Pushed, unless it is the screen already on
 *   top (a filter or a refresh, not a new level) or the top is a screen that must never be
 *   returned to.</li>
 *   <li>{@link Move#UP} — an explicit return to a known screen, as after a punishment lands.
 *   Everything above that screen goes, so the finished flow is not one click behind it. When
 *   the screen is not in the history at all, the history starts again from it.</li>
 *   <li>{@link Move#REPLACE} — a redraw that happens to open a fresh container. If the screen
 *   is in the history it is a refresh or a return and is treated as {@link Move#UP}; if not,
 *   it is a hop that simply wanted no sound, and is treated as {@link Move#FORWARD}.</li>
 * </ul>
 *
 * <h2>Screens that are never returned to</h2>
 * A confirmation screen's button runs its action. Rebuilding one from history would put a
 * second "do it" in front of somebody who already did it, one click from a double ban. Such a
 * frame is replaced by whatever comes after it rather than buried under it, so there is
 * nothing to step back into.
 *
 * @param <T> what it takes to show a frame again; opaque here, so the rules test without a
 *            server
 */
final class NavigationHistory<T> {

	/**
	 * Deep enough for any real path — panel, section, list, file, sub-screen, detail is six —
	 * and small enough that a loop through linked accounts cannot grow it without bound.
	 */
	static final int LIMIT = 32;

	enum Move { ROOT, FORWARD, UP, REPLACE }

	record Frame<T>(Class<?> screen, boolean returnable, T payload) {}

	/** Head is the screen currently open. */
	private final Deque<Frame<T>> frames = new ArrayDeque<>();

	void apply(Move move, Frame<T> frame) {
		switch (move) {
			case ROOT -> {
				frames.clear();
				frames.push(frame);
			}
			case FORWARD -> forward(frame);
			case UP -> {
				if (!truncateTo(frame.screen())) frames.clear();
				frames.push(frame);
			}
			case REPLACE -> {
				if (truncateTo(frame.screen())) frames.push(frame);
				else forward(frame);
			}
		}
	}

	/**
	 * Steps back one level and returns the frame to show, or {@code null} when there is
	 * nothing behind the current screen.
	 * <p>
	 * The returned frame is left on top, because it is about to become the current screen
	 * again; showing it replaces it in place. On {@code null} nothing changes, so the caller's
	 * fallback decides where to go.
	 */
	Frame<T> stepBack() {
		if (frames.size() < 2) return null;
		frames.pop();
		// Defensive: forward() never buries an unreturnable frame, but a history that ever
		// held one should skip it rather than rebuild it.
		while (!frames.isEmpty() && !frames.peek().returnable()) {
			frames.pop();
		}
		return frames.peek();
	}

	/** The frame one step back from the current screen, for labelling the back arrow. */
	Frame<T> previous() {
		Iterator<Frame<T>> it = frames.iterator();
		if (!it.hasNext()) return null;
		it.next();
		while (it.hasNext()) {
			Frame<T> candidate = it.next();
			if (candidate.returnable()) return candidate;
		}
		return null;
	}

	Frame<T> current() {
		return frames.peek();
	}

	int size() {
		return frames.size();
	}

	private void forward(Frame<T> frame) {
		Frame<T> top = frames.peek();
		if (top != null && (top.screen() == frame.screen() || !top.returnable())) {
			frames.pop();
		}
		frames.push(frame);
		while (frames.size() > LIMIT) {
			frames.removeLast();
		}
	}

	/**
	 * Drops everything above and including the newest frame of this screen.
	 *
	 * @return whether there was one
	 */
	private boolean truncateTo(Class<?> screen) {
		int depth = 0;
		for (Frame<T> f : frames) {
			if (f.screen() == screen) {
				for (int i = 0; i <= depth; i++) frames.pop();
				return true;
			}
			depth++;
		}
		return false;
	}
}
