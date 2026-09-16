package io.github.alphain24.staffcore.discord.channels;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppealPanelTest {

	@Test
	@DisplayName("the panel says where the code is and carries one button that opens the form")
	void panel() {
		Outbound.Message panel = AppealPanel.message();
		assertTrue(panel.embed().title().contains("Appeal"));
		assertTrue(panel.embed().description().contains("ban screen") || panel.embed().description().contains("join"),
				panel.embed().description());
		assertTrue(panel.embed().description().contains("/appeal"));
		assertEquals(1, panel.rows().size());
		assertEquals(1, panel.rows().get(0).size());
		assertEquals(AppealPanel.BUTTON_ID, panel.rows().get(0).get(0).id());
		assertTrue(AppealPanel.BUTTON_ID.startsWith("sc:appealpanel:"));
		assertTrue(AppealPanel.FORM_ID.startsWith("sc:appealform:"));
	}
}
