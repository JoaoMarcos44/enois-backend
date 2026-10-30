package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class MessageReplyDeduplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void sameTextReplyingToDifferentMessagesIsStoredSeparately() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var a = auth.authenticate("reply_dedup_a");
		var b = auth.authenticate("reply_dedup_b");
		String conversationId = direct(a, b);
		String firstTarget = send(b, conversationId, Map.of("text", "Primeira pergunta"), "target-1").get("id").asText();
		String secondTarget = send(b, conversationId, Map.of("text", "Segunda pergunta"), "target-2").get("id").asText();
		JsonNode first = send(a, conversationId, Map.of("text", "Sim", "replyToId", firstTarget), "reply-1");
		JsonNode second = send(a, conversationId, Map.of("text", "Sim", "replyToId", secondTarget), "reply-2");
		assertThat(second.get("id").asText()).isNotEqualTo(first.get("id").asText());
		assertThat(second.get("replyToId").asText()).isEqualTo(secondTarget);

		JsonNode repeated = send(a, conversationId, Map.of("text", "Sim", "replyToId", secondTarget), "reply-3");
		assertThat(repeated.get("id").asText()).isEqualTo(second.get("id").asText());
		JsonNode plain = send(a, conversationId, Map.of("text", "Sim"), "plain-1");
		assertThat(plain.get("id").asText()).isNotIn(first.get("id").asText(), second.get("id").asText());
		assertThat(plain.path("replyToId").isNull()).isTrue();

		mockMvc.perform(get("/conversations/{id}/messages", conversationId)
				.header("X-Session-Token", b.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(5));
	}

	@Test
	void changingReplyTargetWithSameIdempotencyKeyIsRejected() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var a = auth.authenticate("reply_hash_a");
		var b = auth.authenticate("reply_hash_b");
		String conversationId = direct(a, b);
		String target = send(b, conversationId, Map.of("text", "Pergunta"), "hash-target").get("id").asText();
		Map<String, Object> payload = Map.of("text", "Sim", "replyToId", target);
		JsonNode first = send(a, conversationId, payload, "hash-reply");
		JsonNode replay = send(a, conversationId, payload, "hash-reply");
		assertThat(replay).isEqualTo(first);
		JsonNode normalized = send(a, conversationId, Map.of("text", "Sim", "replyToId", " " + target + " "), "hash-reply");
		assertThat(normalized).isEqualTo(first);
		JsonNode plain = send(a, conversationId, Map.of("text", "Sem citacao"), "hash-plain");
		JsonNode blank = send(a, conversationId, Map.of("text", "Sem citacao", "replyToId", "  "), "hash-plain");
		assertThat(blank).isEqualTo(plain);

		mockMvc.perform(post("/conversations/{id}/messages", conversationId)
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "hash-reply")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "Sim"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
	}

	private String direct(AuthSession a, AuthSession b) throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "direct-" + a.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private JsonNode send(AuthSession session, String conversationId, Map<String, ?> payload, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations/{id}/messages", conversationId)
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(payload)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}
}
