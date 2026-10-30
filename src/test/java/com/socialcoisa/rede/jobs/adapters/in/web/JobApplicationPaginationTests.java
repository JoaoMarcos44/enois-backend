package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class JobApplicationPaginationTests {

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private JdbcTemplate jdbc;

	@Test
	void applicationsAdvanceThroughHistoryInApplicationOrder() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var candidate = auth.authenticate("application_paging");
		Instant posted = Instant.parse("2026-01-01T00:00:00Z");
		Instant applied = Instant.parse("2026-02-01T00:00:00Z");
		String prefix = "job_paging_" + candidate.userId();
		for (int i = 0; i < 3; i++) {
			String id = prefix + i;
			TestDatabase.insertJob(id, "Paginação " + i, "Fixture", "Remoto", "CLT", "PLENO",
				List.of("Java"), posted.plusSeconds(i));
			TestDatabase.insertJobApplication(id, candidate.userId());
			// A última candidatura é para a vaga mais antiga: a ordem é da candidatura.
			jdbc.update("update job_applications set applied_at = ? where job_id = ? and user_id = ?",
				Timestamp.from(applied.minusSeconds(i)), id, candidate.userId());
		}
		var first = mockMvc.perform(get("/jobs/applications").param("limit", "1")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(prefix + 0))
			.andExpect(jsonPath("$.items[0].applied").value(true))
			.andReturn();
		String cursor = objectMapper.readTree(first.getResponse().getContentAsString()).get("nextCursor").asText();
		var second = mockMvc.perform(get("/jobs/applications").param("limit", "1").param("cursor", cursor)
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(prefix + 1))
			.andReturn();
		cursor = objectMapper.readTree(second.getResponse().getContentAsString()).get("nextCursor").asText();
		mockMvc.perform(get("/jobs/applications").param("limit", "1").param("cursor", cursor)
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(prefix + 2));
	}

	@Test
	void equalApplicationTimestampsUseApplicationIdAsTieBreaker() throws Exception {
		var candidate = new TestAuthHelper(mockMvc, objectMapper).authenticate("application_ties");
		Instant at = Instant.parse("2026-03-01T00:00:00Z");
		String prefix = "job_ties_" + candidate.userId();
		for (int i = 0; i < 3; i++) {
			String id = prefix + i;
			TestDatabase.insertJob(id, "Empate " + i, "Fixture", "Remoto", "CLT", "PLENO", List.of(), at);
			TestDatabase.insertJobApplication(id, candidate.userId());
			jdbc.update("update job_applications set applied_at = ? where job_id = ? and user_id = ?",
				Timestamp.from(at), id, candidate.userId());
		}
		String cursor = null;
		for (int i = 2; i >= 0; i--) {
			var request = get("/jobs/applications").param("limit", "1")
				.header("X-Session-Token", candidate.token());
			if (cursor != null) request.param("cursor", cursor);
			var result = mockMvc.perform(request)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(prefix + i))
				.andReturn();
			cursor = objectMapper.readTree(result.getResponse().getContentAsString()).get("nextCursor").asText(null);
		}
		if (cursor != null) {
			mockMvc.perform(get("/jobs/applications").param("limit", "1").param("cursor", cursor)
					.header("X-Session-Token", candidate.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
		}
	}


	@Test
	void pagesOfTwoIncludeClosedJobsAndExcludeDeletedOnes() throws Exception {
		var candidate = new TestAuthHelper(mockMvc, objectMapper).authenticate("application_two");
		Instant at = Instant.parse("2026-04-01T00:00:00Z");
		String prefix = "job_two_" + candidate.userId();
		for (int i = 0; i < 4; i++) {
			String id = prefix + i;
			TestDatabase.insertJob(id, "Duas " + i, "Fixture", "Remoto", "CLT", "PLENO", List.of(), at);
			TestDatabase.insertJobApplication(id, candidate.userId());
			jdbc.update("update job_applications set applied_at = ? where job_id = ? and user_id = ?",
				Timestamp.from(at.minusSeconds(i)), id, candidate.userId());
		}
		jdbc.update("delete from job_applications where job_id = ?", prefix + 0);
		jdbc.update("delete from jobs where id = ?", prefix + 0);
		jdbc.update("update jobs set status = 'CLOSED', closed_at = ? where id = ?", Timestamp.from(at), prefix + 1);
		var first = mockMvc.perform(get("/jobs/applications").param("limit", "2")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].id").value(prefix + 1))
			.andExpect(jsonPath("$.items[0].status").value("CLOSED"))
			.andExpect(jsonPath("$.items[1].id").value(prefix + 2))
			.andReturn();
		String cursor = objectMapper.readTree(first.getResponse().getContentAsString()).get("nextCursor").asText();
		mockMvc.perform(get("/jobs/applications").param("limit", "2").param("cursor", cursor)
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(prefix + 3))
			.andExpect(jsonPath("$.nextCursor").isEmpty());

		mockMvc.perform(get("/jobs/applications").param("limit", "2").param("cursor", at.minusSeconds(2).toString())
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(prefix + 3));

		var other = new TestAuthHelper(mockMvc, objectMapper).authenticate("application_other");
		mockMvc.perform(get("/jobs/applications").param("cursor", cursor)
				.header("X-Session-Token", other.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void malformedApplicationCursorReturnsClientError() throws Exception {
		var candidate = new TestAuthHelper(mockMvc, objectMapper).authenticate("application_cursor");
		mockMvc.perform(get("/jobs/applications").param("cursor", "2026-04-01T00:00:00Z|invalid")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
	}

}
