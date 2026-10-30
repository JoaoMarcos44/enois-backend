package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertAll;
import java.math.BigDecimal;
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
class JobSalaryPrecisionTests {
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
	void salaryInputsRemainExactThroughCreateReadAndUpdate() throws Exception {
		var owner = eligibleOwner("salary_exact_");
		String[][] invalid = {
			{"-1500", null}, {"+1500", null}, {"1500abc", null}, {"1e3", null},
			{"1.2.3", null}, {"0,001", null}, {"1500,000", null}, {"10000000000", null},
			{"1500", "nonsense"}, {"1500", "1.2.3"}, {"1500", "-2000"},
			{"1500", "+2000"}, {"1500", "2000abc"}, {"1500", "2000,001"},
			{"1500", "10000000000"}, {"R$ R$ 1500", null}
		};
		var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
		for (int i = 0; i < invalid.length; i++) {
			final int index = i;
			checks.add(() -> {
				var payload = body(); payload.put("salaryMin", invalid[index][0]);
				if (invalid[index][1] != null) payload.put("salaryMax", invalid[index][1]);
				create(owner, payload, "invalid-" + index).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_SALARY_AMOUNT"));
			});
		}
		checks.add(() -> {
			var zero = body(); zero.put("salaryMin", "0");
			create(owner, zero, "zero").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SALARY_REQUIRED"));
			var inverted = body(); inverted.put("salaryMax", "1200");
			create(owner, inverted, "inverted").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SALARY_RANGE"));
		});
		assertAll("Malformed amounts must never become a different published salary", checks);
		assertThat(jdbc.queryForObject("select count(*) from jobs where posted_by=?", Integer.class, owner.userId())).isZero();
		String[][] accepted = {
			{"1500", "1500.00"}, {"1.500", "1500.00"}, {"1.500,50", "1500.50"},
			{"R$ 1.500", "1500.00"}, {"0.001", "1.00"}, {"1.50", "1.50"},
			{"0,01", "0.01"}, {"9999999999,99", "9999999999.99"}
		};
		String id = null;
		for (int i = 0; i < accepted.length; i++) {
			var payload = body(); payload.put("salaryMin", accepted[i][0]); payload.put("salaryMax", accepted[i][0]);
			var created = create(owner, payload, "valid-" + i).andExpect(status().isCreated()).andReturn();
			id = mapper.readTree(created.getResponse().getContentAsString()).path("id").asText();
			assertThat(jdbc.queryForObject("select salary_min from jobs where id=?", BigDecimal.class, id)).isEqualByComparingTo(accepted[i][1]);
			assertThat(jdbc.queryForObject("select salary_max from jobs where id=?", BigDecimal.class, id)).isEqualByComparingTo(accepted[i][1]);
			String publicMin = mapper.readTree(created.getResponse().getContentAsString()).path("salaryMin").asText();
			mvc.perform(get("/jobs/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.salaryMin").value(publicMin));
		}
		var edited = body(); edited.put("salaryMin", "2500,50"); edited.put("salaryMax", "3000,50");
		mvc.perform(put("/jobs/" + id).header("X-Session-Token", owner.token()).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(edited))).andExpect(status().isOk());
		for (String[] input : invalid) {
			var badEdit = body(); badEdit.put("salaryMin", input[0]);
			if (input[1] != null) badEdit.put("salaryMax", input[1]);
			mvc.perform(put("/jobs/" + id).header("X-Session-Token", owner.token()).contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(badEdit))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SALARY_AMOUNT"));
		}
		assertThat(jdbc.queryForObject("select salary_min from jobs where id=?", BigDecimal.class, id)).isEqualByComparingTo("2500.50");
		assertThat(jdbc.queryForObject("select salary_max from jobs where id=?", BigDecimal.class, id)).isEqualByComparingTo("3000.50");
	}
}
