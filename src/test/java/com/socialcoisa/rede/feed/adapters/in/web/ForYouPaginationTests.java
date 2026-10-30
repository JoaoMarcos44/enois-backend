package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ForYouPaginationTests {

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private PostRepository posts;
	private final Instant newest = Instant.parse("2026-01-01T12:00:00Z");
	private String communityId;

	@AfterEach
	void cleanUp() {
		if (communityId != null) TestDatabase.deleteCommunity(communityId);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 20})
	void visibleOverflowKeepsCursorWithoutSaturatingTheScan(int limit) throws Exception {
		var reader = account();
		List<String> expected = new ArrayList<>();
		for (int i = 0; i <= limit; i++) expected.add(seed(reader, i, null, null));

		var first = page(reader, limit, null);
		assertThat(ids(first)).containsExactlyElementsOf(expected.subList(0, limit));
		assertThat(first.path("nextCursor").isNull()).isFalse();
		var second = page(reader, limit, first.path("nextCursor").asText());
		assertThat(ids(second)).containsExactly(expected.get(limit));
		assertThat(second.path("nextCursor").isNull()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void exhaustedScanWithoutVisibleOverflowEnds(int count) throws Exception {
		var reader = account();
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < count; i++) expected.add(seed(reader, i, null, null));

		var response = page(reader, 2, null);
		assertThat(ids(response)).containsExactlyElementsOf(expected);
		assertThat(response.path("nextCursor").isNull()).isTrue();
	}

	@Test
	void likedTopicsReorderEachPageWithoutSkippingOrRepeatingPosts() throws Exception {
		var reader = account();
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			expected.add(seed(reader, i, null, i == 1 || i == 2 || i == 6 ? "TECNOLOGIA" : null));
		}
		mockMvc.perform(put("/me/preferences")
				.header("X-Session-Token", reader.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("likedTopics", List.of("TECNOLOGIA")))))
			.andExpect(status().isOk());

		var first = page(reader, 2, null);
		assertThat(ids(first)).containsExactly(expected.get(1), expected.get(0));
		List<String> collected = new ArrayList<>(ids(first));
		String cursor = first.path("nextCursor").asText();
		for (int i = 0; i < 4; i++) {
			var response = page(reader, 2, cursor);
			collected.addAll(ids(response));
			if (response.path("nextCursor").isNull()) break;
			String next = response.path("nextCursor").asText();
			assertThat(next).isNotEqualTo(cursor);
			cursor = next;
		}
		assertThat(collected).containsExactlyInAnyOrderElementsOf(expected).doesNotHaveDuplicates();
	}

	@Test
	void fullScanOfForeignCommunityPostsKeepsCursorToOlderVisiblePost() throws Exception {
		var author = account();
		var reader = account();
		mockMvc.perform(post("/network/connections/{target}", reader.userId())
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", UUID.randomUUID().toString()))
			.andExpect(status().isAccepted());
		mockMvc.perform(put("/network/connections/{target}/accept", author.userId())
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", UUID.randomUUID().toString()))
			.andExpect(status().isOk());
		var created = mockMvc.perform(post("/communities")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "ForYou pagination " + UUID.randomUUID(),
					"description", "Comunidade de teste", "topic", "TECNOLOGIA"))))
			.andExpect(status().isCreated()).andReturn();
		communityId = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();
		for (int i = 0; i < 6; i++) seed(author, i, communityId, "TECNOLOGIA");
		String older = seed(reader, 6, null, null);

		var first = page(reader, 2, null);
		assertThat(ids(first)).isEmpty();
		assertThat(first.path("nextCursor").isNull()).isFalse();
		var second = page(reader, 2, first.path("nextCursor").asText());
		assertThat(ids(second)).containsExactly(older);
		assertThat(second.path("nextCursor").isNull()).isTrue();
	}

	private TestAuthHelper.AuthSession account() throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(
			"fyp_" + UUID.randomUUID().toString().substring(0, 8));
	}

	private String seed(TestAuthHelper.AuthSession author, int age, String community, String topic) {
		String id = "fyp_" + UUID.randomUUID();
		posts.save(Post.publish(new PostId(id), new UserId(author.userId()), PostType.INSIGHT,
			"Publicacao de teste de paginacao", null, List.of(), List.of(), community, topic,
			newest.minusSeconds(age), AuthorIdentity.SOCIAL));
		return id;
	}

	private JsonNode page(TestAuthHelper.AuthSession reader, int limit, String cursor) throws Exception {
		var request = get("/feed").header("X-Session-Token", reader.token())
			.param("tab", "FOR_YOU").param("limit", Integer.toString(limit));
		if (cursor != null) request.param("cursor", cursor);
		var result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private List<String> ids(JsonNode page) {
		return page.path("items").valueStream().map(item -> item.path("id").asText()).toList();
	}
}
