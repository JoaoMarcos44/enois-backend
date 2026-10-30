package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
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
class JobCreateIdempotencyTests {
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

	@Test
	void retriesReplayTheWholeResponseAndChangedPayloadsConflict() throws Exception {
		var owner = eligibleOwner("job_retry_");
		var payload = body();
		var first = create(owner, payload, "same-key").andExpect(status().isCreated()).andReturn();
		String firstBody = first.getResponse().getContentAsString();
		String id = mapper.readTree(firstBody).path("id").asText();
		create(owner, payload, "same-key").andExpect(status().isCreated()).andExpect(content().json(firstBody));
		assertThat(jdbc.queryForObject("select count(*) from jobs where posted_by=?", Integer.class, owner.userId())).isEqualTo(1);
		for (var changed : Map.<String, Object>of("salaryMax", "2000", "description", "Outra descrição.",
				"skills", java.util.List.of("SQL"), "applicationFields", java.util.List.of(Map.of("label", "Experiência", "type", "TEXT", "required", true))).entrySet()) {
			var different = new java.util.LinkedHashMap<>(payload);
			different.put(changed.getKey(), changed.getValue());
			create(owner, different, "same-key").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
		}
		create(owner, payload, "new-key").andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(id)));
		assertThat(jdbc.queryForObject("select count(*) from jobs where posted_by=?", Integer.class, owner.userId())).isEqualTo(2);
	}

	@Test
	void aMissingKeyUsesTheExistingRequiredKeyPolicy() throws Exception {
		var owner = eligibleOwner("job_key_");
		for (String key : new String[] {null, " "}) {
			create(owner, body(), key).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
		}
		assertThat(jdbc.queryForObject("select count(*) from jobs where posted_by=?", Integer.class, owner.userId())).isZero();
	}
}
