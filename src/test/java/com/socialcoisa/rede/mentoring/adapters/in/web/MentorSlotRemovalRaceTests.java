package com.socialcoisa.rede.mentoring.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.socialcoisa.rede.mentoring.application.MentorService;
import com.socialcoisa.rede.mentoring.application.port.out.MentorRepository;
import com.socialcoisa.rede.shared.web.ApiException;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class MentorSlotRemovalRaceTests {

	@Autowired MentorService mentoring;
	@Autowired JdbcTemplate jdbc;
	@MockitoSpyBean MentorRepository repository;

	@ParameterizedTest(name = "weekly={0}, bookingWins={1}")
	@CsvSource({"false,true", "true,true", "false,false", "true,false"})
	void bookingAndRemovalRespectTheFirstCommittedMutation(boolean weekly, boolean bookingWins) throws Exception {
		String marker = UUID.randomUUID().toString();
		String owner = "removal_owner_" + marker;
		String mentor = mentoring.create(owner, List.of("Java"), "Gratuito", null).id();
		var slot = mentoring.createSlot(mentor, owner, Instant.now().plusSeconds(86400),
			Instant.now().plusSeconds(90000));
		var read = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		var pausedThread = new AtomicReference<Thread>();
		org.mockito.stubbing.Answer<Object> pauseAfterRead = invocation -> {
			Object snapshot = invocation.callRealMethod();
			if (Thread.currentThread() == pausedThread.get()) {
				read.countDown();
				assertThat(resume.await(15, TimeUnit.SECONDS)).isTrue();
			}
			return snapshot;
		};
		if (weekly && bookingWins) {
			doAnswer(pauseAfterRead).when(repository).findSlotsByMentor(mentor);
		} else {
			doAnswer(pauseAfterRead).when(repository).findSlotById(slot.id());
		}

		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var delayed = executor.submit(() -> {
				pausedThread.set(Thread.currentThread());
				return bookingWins ? remove(weekly, mentor, slot.id(), owner) : book(mentor, slot.id(), marker);
			});
			try {
				assertThat(read.await(15, TimeUnit.SECONDS)).isTrue();
				if (bookingWins) {
					assertThat(book(mentor, slot.id(), marker)).isEqualTo("SCHEDULED");
					assertBooked(slot.id());
				} else {
					assertThat(remove(weekly, mentor, slot.id(), owner)).isEqualTo("REMOVED");
					assertThat(slotCount(slot.id())).isZero();
				}
			} finally {
				resume.countDown();
			}
			assertThat(delayed.get(20, TimeUnit.SECONDS)).isEqualTo(bookingWins
				? (weekly ? "REMOVED" : "SLOT_BOOKED") : "SLOT_UNAVAILABLE");
		}
		if (bookingWins) {
			assertBooked(slot.id());
		} else {
			assertThat(slotCount(slot.id())).isZero();
			assertThat(sessionCount(slot.id())).isZero();
		}
	}

	private String remove(boolean weekly, String mentor, String slot, String owner) {
		try {
			if (weekly) {
				mentoring.saveAvailability(mentor, owner, List.of());
			} else {
				mentoring.deleteSlot(mentor, slot, owner);
			}
			return "REMOVED";
		} catch (ApiException conflict) {
			assertThat(conflict.status()).isEqualTo(409);
			return conflict.code();
		}
	}

	private String book(String mentor, String slot, String marker) {
		try {
			mentoring.scheduleSession(mentor, "removal_guest_" + marker, slot, "Oi");
			return "SCHEDULED";
		} catch (ApiException conflict) {
			assertThat(conflict.status()).isEqualTo(409);
			return conflict.code();
		}
	}

	private void assertBooked(String slot) {
		assertThat(slotCount(slot)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select available from mentor_slots where id = ?", Boolean.class, slot)).isFalse();
		assertThat(sessionCount(slot)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select status from mentor_sessions where slot_id = ?", String.class, slot))
			.isEqualTo("SCHEDULED");
	}

	private int slotCount(String slot) {
		return jdbc.queryForObject("select count(*) from mentor_slots where id = ?", Integer.class, slot);
	}

	private int sessionCount(String slot) {
		return jdbc.queryForObject("select count(*) from mentor_sessions where slot_id = ?", Integer.class, slot);
	}
}
