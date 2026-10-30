package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class TimestampIdPaginationTests {
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper mapper;
	@Autowired JdbcTemplate jdbc;
	private static final Instant BOUNDARY = Instant.parse("2030-01-01T12:00:00Z");

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 50})
	void messageTimestampTiesRemainReachable(int limit) throws Exception {
		var reader = account();
		String conversation = conversation(reader);
		String path = "/conversations/" + conversation + "/messages";
		List<String> expected = new ArrayList<>();
		// At the default boundary: 49 newer rows and two rows in the same second.
		for (int i = 0; i <= limit; i++) {
			String id = "paging_" + UUID.randomUUID();
			Instant at = i < limit - 1 ? BOUNDARY.plusSeconds(limit - i) : BOUNDARY;
			jdbc.update("insert into messages(id,conversation_id,sender_id,text,created_at,media_spoiler,e2e) values(?,?,?,'Mensagem',?,false,false)",
				id, conversation, reader.userId(), Timestamp.from(at));
			expected.add(id);
		}
		assertThat(ids(page(reader, path, limit + 1, null))).containsExactlyInAnyOrderElementsOf(expected);
		expected.subList(limit - 1, limit + 1).sort(Comparator.reverseOrder());
		assertThat(drain(reader, path, limit == 50 ? null : limit)).containsExactlyElementsOf(expected)
			.doesNotHaveDuplicates();
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 20})
	void notificationTimestampTiesRemainReachable(int limit) throws Exception {
		var reader = account();
		List<String> expected = new ArrayList<>();
		for (int i = 0; i <= limit; i++) {
			Instant at = i < limit - 1 ? BOUNDARY.plusSeconds(limit - i) : BOUNDARY;
			expected.add(notification(reader, "sistema", at));
		}
		assertThat(ids(page(reader, "/notifications", limit + 1, null))).containsExactlyInAnyOrderElementsOf(expected);
		expected.subList(limit - 1, limit + 1).sort(Comparator.reverseOrder());
		assertThat(drain(reader, "/notifications", limit == 20 ? null : limit))
			.containsExactlyElementsOf(expected).doesNotHaveDuplicates();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void hiddenNotificationPagesRetainTheirRawPosition(boolean reverseBlock) throws Exception {
		var reader = account();
		String hiddenActor = "hidden_" + UUID.randomUUID();
		jdbc.update("insert into user_blocks(blocker_id,blocked_id,created_at) values(?,?,?)",
			reverseBlock ? hiddenActor : reader.userId(), reverseBlock ? reader.userId() : hiddenActor,
			Timestamp.from(Instant.now()));
		notification(reader, hiddenActor, BOUNDARY.plusSeconds(1));
		notification(reader, hiddenActor, BOUNDARY.plusSeconds(1));
		String visible = notification(reader, "sistema", BOUNDARY);
		JsonNode first = page(reader, "/notifications", 1, null);
		assertThat(ids(first)).isEmpty();
		assertThat(first.get("nextCursor").isNull()).isFalse();
		assertThat(drain(reader, "/notifications", 1)).containsExactly(visible);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void legacyTimestampAndInvalidCursorContract(boolean messages) throws Exception {
		var reader = account();
		String conversation = messages ? conversation(reader) : null;
		String path = messages ? "/conversations/" + conversation + "/messages" : "/notifications";
		// An old timestamp can only resume strictly before that second.
		if (messages) {
			for (int i = 0; i < 3; i++) {
				jdbc.update("insert into messages(id,conversation_id,sender_id,text,created_at,media_spoiler,e2e) values(?,?,?,'Mensagem',?,false,false)",
					"legacy_" + UUID.randomUUID(), conversation, reader.userId(),
					Timestamp.from(i < 2 ? BOUNDARY : BOUNDARY.minusSeconds(1)));
			}
			assertThat(ids(page(reader, path, 2, BOUNDARY.toString()))).hasSize(1);
		} else {
			notification(reader, "sistema", BOUNDARY);
			String older = notification(reader, "sistema", BOUNDARY.minusSeconds(1));
			assertThat(ids(page(reader, path, 2, BOUNDARY.toString()))).containsExactly(older);
		}
		assertThat(page(reader, path, 2, " ").get("items").isArray()).isTrue();
		assertThat(page(reader, path, 2, BOUNDARY.toString()).get("items").isArray()).isTrue();
		for (String invalid : List.of("invalid", "2030-02-99T00:00:00Z", "v1.!", opaque("bad|id"),
				opaque(BOUNDARY + "|"), opaque(BOUNDARY + "|id|extra"), opaque(BOUNDARY + "|" + "a".repeat(256)),
				"v1." + "a".repeat(1501), "v2.unknown", "v1._w")) {
			mvc.perform(get(path).header("X-Session-Token", reader.token()).param("cursor", invalid))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
		}
	}

	private TestAuthHelper.AuthSession account() throws Exception {
		var reader = new TestAuthHelper(mvc, mapper).authenticate("cursor_" + UUID.randomUUID().toString().substring(0, 8));
		jdbc.update("delete from notifications where user_id=?", reader.userId());
		return reader;
	}

	private String conversation(TestAuthHelper.AuthSession reader) throws Exception {
		var other = account();
		var result = mvc.perform(post("/conversations").header("X-Session-Token", reader.token())
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("kind", "DIRECT", "participantIds", List.of(other.userId())))))
			.andExpect(status().isCreated()).andReturn();
		return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String notification(TestAuthHelper.AuthSession reader, String actor, Instant at) {
		String id = "paging_" + UUID.randomUUID();
		jdbc.update("insert into notifications(id,user_id,kind,actor_id,title,body,is_read,created_at) values(?,?,'SYSTEM',?,'Aviso','Resumo',false,?)",
			id, reader.userId(), actor, Timestamp.from(at));
		return id;
	}

	private JsonNode page(TestAuthHelper.AuthSession reader, String path, Integer limit, String cursor) throws Exception {
		var request = get(path).header("X-Session-Token", reader.token());
		if (limit != null) request.param("limit", limit.toString());
		if (cursor != null) request.param("cursor", cursor);
		return mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private List<String> ids(JsonNode page) {
		return page.get("items").valueStream().map(item -> item.get("id").asText()).toList();
	}

	private List<String> drain(TestAuthHelper.AuthSession reader, String path, Integer limit) throws Exception {
		List<String> result = new ArrayList<>();
		String cursor = null;
		for (int i = 0; i < 100; i++) {
			JsonNode page = page(reader, path, limit, cursor);
			result.addAll(ids(page));
			if (page.get("nextCursor").isNull()) return result;
			String next = page.get("nextCursor").asText();
			assertThat(next).isNotEqualTo(cursor);
			cursor = next;
		}
		throw new AssertionError("Pagination did not terminate");
	}

	private String opaque(String value) {
		return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}
}
