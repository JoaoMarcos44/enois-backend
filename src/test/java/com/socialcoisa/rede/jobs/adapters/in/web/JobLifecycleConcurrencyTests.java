package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import static org.mockito.Mockito.doAnswer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class JobLifecycleConcurrencyTests {
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper mapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired ReputationService reputation;
	@MockitoBean EmailService email;

	TestAuthHelper.AuthSession eligibleOwner(String prefix) throws Exception {
		var owner = new TestAuthHelper(mvc, mapper).authenticate(prefix + java.util.UUID.randomUUID().toString().substring(0, 8));
		assertThat(reputation.reputationOf(owner.userId())).isLessThanOrEqualTo(150);
		for (int i = 0; i < 10; i++) {
			for (var entry : Map.of(
				"/me/experiences", Map.of("title", "Experiência " + i, "company", "Fixture " + i, "startDate", "2020-01-01"),
				"/me/education", Map.of("school", "Escola " + i, "degree", "Formação " + i, "startDate", "2015-01-01"),
				"/me/certifications", Map.of("name", "Certificação " + i, "issuer", "Emissor " + i, "issueDate", "2020-01-01")).entrySet()) {
				mvc.perform(post(entry.getKey()).header("X-Session-Token", owner.token())
					.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(entry.getValue())))
					.andExpect(status().isCreated());
			}
		}
		for (int i = 0; i < 9; i++) {
			mvc.perform(post("/communities").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "community-" + i).contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("name", "Comunidade de teste " + i, "topic", "TECHNOLOGY"))))
				.andExpect(status().isCreated());
		}
		assertThat(reputation.reputationOf(owner.userId())).isEqualTo(154);
		return owner;
	}

	Map<String, Object> body() {
		var body = new java.util.LinkedHashMap<String, Object>();
		body.put("title", "Manutenção Java"); body.put("company", "Fixture");
		body.put("location", "Remoto"); body.put("type", "CLT"); body.put("level", "PLENO");
		body.put("salaryMin", "1500"); body.put("currency", "BRL"); body.put("salaryPeriod", "MONTH");
		body.put("description", "Manutenção do aplicativo."); body.put("skills", java.util.List.of("Java"));
		body.put("applicationFields", java.util.List.of()); body.put("communityAgreement", true);
		return body;
	}

	org.springframework.test.web.servlet.ResultActions create(TestAuthHelper.AuthSession owner,
			Map<String, Object> body, String key) throws Exception {
		var request = post("/jobs").header("X-Session-Token", owner.token())
			.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
		if (key != null) request.header("Idempotency-Key", key);
		return mvc.perform(request);
	}

	@MockitoSpyBean JobRepository repository;

	@Test
	void editingBeforeCloseCannotRestoreOpenState() throws Exception {
		race(false);
	}

	@Test
	void editingDuringCloseReadsTheCommittedClosedState() throws Exception {
		race(true);
	}

	private void race(boolean closeFirst) throws Exception {
		var owner = eligibleOwner("job_lifecycle_");
		var created = create(owner, body(), "create").andExpect(status().isCreated()).andReturn();
		String id = mapper.readTree(created.getResponse().getContentAsString()).path("id").asText();
		var loaded = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var secondAttempted = new CountDownLatch(1);
		org.mockito.stubbing.Answer<Object> pauseFirstRead = invocation -> {
			String thread = Thread.currentThread().getName();
			if (thread.equals("lifecycle-second")) secondAttempted.countDown();
			Object result = invocation.callRealMethod();
			if (thread.equals("lifecycle-first")) {
				loaded.countDown();
				assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
			}
			return result;
		};
		doAnswer(pauseFirstRead).when(repository).findById(id);
		doAnswer(pauseFirstRead).when(repository).findByIdForUpdate(id);
		try (var firstPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "lifecycle-first"));
				var secondPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "lifecycle-second"))) {
			var first = firstPool.submit(() -> change(owner, id, closeFirst));
			try {
				assertThat(loaded.await(15, TimeUnit.SECONDS)).isTrue();
				var second = secondPool.submit(() -> change(owner, id, !closeFirst));
				try {
					assertThat(secondAttempted.await(15, TimeUnit.SECONDS)).isTrue();
					try { second.get(1, TimeUnit.SECONDS); }
					catch (TimeoutException expectedWhileLocked) { /* The first transaction owns the row lock. */ }
				} finally { release.countDown(); }
				assertThat(first.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
				assertThat(second.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
			} finally { release.countDown(); }
		}
		var closed = mvc.perform(get("/jobs/" + id)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CLOSED"))
			.andExpect(jsonPath("$.closedAt").isNotEmpty())
			.andExpect(jsonPath("$.title").value("Título editado")).andReturn();
		String closedAt = mapper.readTree(closed.getResponse().getContentAsString()).path("closedAt").asText();
		change(owner, id, false);
		mvc.perform(get("/jobs/" + id)).andExpect(jsonPath("$.status").value("CLOSED"))
			.andExpect(jsonPath("$.closedAt").value(closedAt));
		var candidate = new TestAuthHelper(mvc, mapper).authenticate("lifecycle_candidate_" + java.util.UUID.randomUUID().toString().substring(0, 8));
		mvc.perform(post("/jobs/" + id + "/applications").header("X-Session-Token", candidate.token())
			.header("Idempotency-Key", "apply-closed").contentType(MediaType.APPLICATION_JSON).content("{\"answers\":[]}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_CLOSED"));
	}

	private org.springframework.test.web.servlet.MvcResult change(TestAuthHelper.AuthSession owner, String id, boolean close) throws Exception {
		if (close) return mvc.perform(post("/jobs/" + id + "/close").header("X-Session-Token", owner.token())).andReturn();
		var edited = body(); edited.put("title", "Título editado");
		return mvc.perform(put("/jobs/" + id).header("X-Session-Token", owner.token())
			.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(edited))).andReturn();
	}
}
