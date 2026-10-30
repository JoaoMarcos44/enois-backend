package com.socialcoisa.rede.mentoring.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.mentoring.application.MentorService;
import com.socialcoisa.rede.mentoring.application.port.out.MentorRepository;
import com.socialcoisa.rede.mentoring.domain.MentorSlot;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class MentorBookingIntegrityTests {

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper mapper;
	@Autowired MentorService mentoring;
	@Autowired JdbcTemplate jdbc;
	@MockitoSpyBean MentorRepository repository;

	@Test
	void anExpiredPublishedSlotCannotCreateASession() throws Exception {
		var auth = new TestAuthHelper(mockMvc, mapper);
		var owner = auth.authenticate("mentor_expired_owner");
		var mentee = auth.authenticate("mentor_expired_guest");
		String mentorId = mentoring.create(owner.userId(), List.of("Java"), "Gratuito", null).id();
		String slotId = "slot_" + UUID.randomUUID();
		repository.saveSlot(MentorSlot.create(slotId, mentorId, Instant.now().minusSeconds(7200),
			Instant.now().minusSeconds(3600)));

		mockMvc.perform(post("/mentors/" + mentorId + "/sessions")
				.header("X-Session-Token", mentee.token())
				.header("Idempotency-Key", "expired-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("slotId", slotId, "message", "Quero aprender"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SLOT_EXPIRED"));

		assertThat(sessionCount(slotId)).isZero();
		assertThat(repository.findSlotById(slotId).orElseThrow().available()).isTrue();
	}

	@Test
	void anInvertedIntervalCannotBePublished() throws Exception {
		var owner = new TestAuthHelper(mockMvc, mapper).authenticate("mentor_interval_owner");
		String mentorId = mentoring.create(owner.userId(), List.of("Java"), "Gratuito", null).id();
		Instant start = Instant.now().plusSeconds(86400);

		mockMvc.perform(post("/mentors/" + mentorId + "/slots")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "interval-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("startsAt", start, "endsAt", start.minusSeconds(3600)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SLOT_INTERVAL"));

		assertThat(repository.findSlotsByMentor(mentorId)).isEmpty();
	}

	@Test
	void twoConcurrentBookingsCreateExactlyOneSession() throws Exception {
		String marker = UUID.randomUUID().toString();
		String owner = "mentor_race_owner_" + marker;
		String mentorId = mentoring.create(owner, List.of("Java"), "Gratuito", null).id();
		var slot = mentoring.createSlot(mentorId, owner, Instant.now().plusSeconds(86400),
			Instant.now().plusSeconds(90000));
		var bothRead = new CyclicBarrier(2);
		doAnswer(invocation -> {
			Object found = invocation.callRealMethod();
			bothRead.await(10, TimeUnit.SECONDS);
			return found;
		}).when(repository).findSlotById(slot.id());

		List<String> results;
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var futures = List.of("guest_a_", "guest_b_").stream()
				.map(prefix -> executor.submit(() -> {
					try {
						mentoring.scheduleSession(mentorId, prefix + marker, slot.id(), "Quero aprender");
						return "SCHEDULED";
					}
					catch (ApiException rejected) {
						return rejected.code();
					}
				})).toList();
			results = List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
		}
		assertThat(results).containsExactlyInAnyOrder("SCHEDULED", "SLOT_UNAVAILABLE");
		assertThat(sessionCount(slot.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("select available from mentor_slots where id = ?", Boolean.class, slot.id()))
			.isFalse();
	}

	@Test
	void anOngoingSlotCanStillBeBooked() throws Exception {
		String marker = UUID.randomUUID().toString();
		String owner = "mentor_ongoing_" + marker;
		String mentorId = mentoring.create(owner, List.of("Java"), "Gratuito", null).id();
		var slot = mentoring.createSlot(mentorId, owner, Instant.now().minusSeconds(1800),
			Instant.now().plusSeconds(1800));

		var session = mentoring.scheduleSession(mentorId, "guest_" + marker, slot.id(), "Quero aprender");

		assertThat(session.slotId()).isEqualTo(slot.id());
		assertThat(sessionCount(slot.id())).isEqualTo(1);
		assertThat(repository.findSlotById(slot.id()).orElseThrow().available()).isFalse();
	}

	@Test
	void aFailedSessionWriteRollsBackTheSlotReservation() {
		String marker = UUID.randomUUID().toString();
		String owner = "mentor_rollback_" + marker;
		String mentorId = mentoring.create(owner, List.of("Java"), "Gratuito", null).id();
		var slot = mentoring.createSlot(mentorId, owner, Instant.now().plusSeconds(86400),
			Instant.now().plusSeconds(90000));
		doAnswer(invocation -> {
			throw new IllegalStateException("Falha de gravacao simulada");
		}).when(repository).saveSession(org.mockito.ArgumentMatchers.any());

		assertThatThrownBy(() -> mentoring.scheduleSession(mentorId, "guest_" + marker, slot.id(), "Oi"))
			.isInstanceOf(InvalidDataAccessApiUsageException.class)
			.hasRootCauseInstanceOf(IllegalStateException.class);

		assertThat(sessionCount(slot.id())).isZero();
		assertThat(repository.findSlotById(slot.id()).orElseThrow().available()).isTrue();
	}

	@Test
	void aMissingStartIsRejectedAtTheHttpBoundary() throws Exception {
		var owner = new TestAuthHelper(mockMvc, mapper).authenticate("mentor_missing_start");
		String mentorId = mentoring.create(owner.userId(), List.of("Java"), "Gratuito", null).id();
		mockMvc.perform(post("/mentors/" + mentorId + "/slots")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "missing-start-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("endsAt", Instant.now().plusSeconds(3600)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SLOT_INTERVAL"));
		assertThat(repository.findSlotsByMentor(mentorId)).isEmpty();
	}

	private int sessionCount(String slotId) {
		return jdbc.queryForObject("select count(*) from mentor_sessions where slot_id = ?", Integer.class, slotId);
	}
}
