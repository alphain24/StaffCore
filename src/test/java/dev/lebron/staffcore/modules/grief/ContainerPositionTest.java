package dev.lebron.staffcore.modules.grief;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Querying a container's history by position.
 * <p>
 * A double chest is one inventory reached from two blocks, and a log row is written at
 * whichever half the player clicked. Two people using opposite halves therefore write to two
 * different coordinates. Every read used to ask about a single position, which meant it saw
 * half the history and presented it as the whole of it — and the failure was invisible in
 * exactly the way that matters: the screen looked fine, it just said nobody had taken
 * anything.
 * <p>
 * The SQL is built rather than fixed, so this pins the shape of it. What it cannot check is
 * the resolving of one block into two, which needs a world.
 */
class ContainerPositionTest {

	@Test
	@DisplayName("one position asks about one position")
	void singlePosition() {
		assertEquals("((x = ? AND y = ? AND z = ?))", ContainerWatch.anyPosition(1));
	}

	@Test
	@DisplayName("two positions are matched as alternatives, not as a range")
	void twoPositions() {
		String sql = ContainerWatch.anyPosition(2);

		assertEquals("((x = ? AND y = ? AND z = ?) OR (x = ? AND y = ? AND z = ?))", sql);
		assertTrue(sql.contains(" OR "), "the halves are alternatives — either block, not both");
	}

	@Test
	@DisplayName("the clause binds three parameters per position, in order")
	void bindingOrder() throws Exception {
		var recorded = new java.util.ArrayList<int[]>();

		// A stand-in that records what would have been bound, so the ordering is checked
		// without a database. Getting x, y and z out of step would silently query the wrong
		// block, which is the same invisible failure in a different disguise.
		var ps = (java.sql.PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
				ContainerPositionTest.class.getClassLoader(),
				new Class<?>[] { java.sql.PreparedStatement.class },
				(proxy, method, args) -> {
					if ("setInt".equals(method.getName())) {
						recorded.add(new int[] { (Integer) args[0], (Integer) args[1] });
					}
					return null;
				});

		int next = ContainerWatch.bindPositions(ps, 4,
				List.of(new BlockPos(10, 64, -20), new BlockPos(11, 64, -20)));

		assertEquals(10, next, "six parameters bound starting at index 4");
		assertEquals(6, recorded.size());

		// index, value
		assertEquals(4, recorded.get(0)[0]);
		assertEquals(10, recorded.get(0)[1]);
		assertEquals(64, recorded.get(1)[1]);
		assertEquals(-20, recorded.get(2)[1]);
		assertEquals(7, recorded.get(3)[0]);
		assertEquals(11, recorded.get(3)[1]);
	}
}
