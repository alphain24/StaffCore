package io.github.alphain24.staffcore.discord.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which failures leave a post waiting to be tried again, and which drop it: the network and Discord's
 * own servers pass; anything about the post itself does not.
 */
class PassingFailureTest {

	@Test
	@DisplayName("network trouble passes; a failure about the post does not")
	void passing() {
		assertTrue(JdaGateway.passing(new UncheckedIOException(new IOException("reset"))));
		assertTrue(JdaGateway.passing(new CompletionException(new IOException("reset"))));
		assertTrue(JdaGateway.passing(new CompletionException(new TimeoutException())));
		assertTrue(JdaGateway.passing(new RuntimeException(new java.net.SocketTimeoutException())));

		assertFalse(JdaGateway.passing(new IllegalStateException("bad embed")));
		assertFalse(JdaGateway.passing(new IllegalArgumentException("too long")));
		assertFalse(JdaGateway.passing(new CompletionException(new IllegalStateException())));
	}
}
