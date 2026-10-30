package com.socialcoisa.rede.mentoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.socialcoisa.rede.mentoring.application.port.out.MentorRepository;
import com.socialcoisa.rede.mentoring.domain.Mentor;
import com.socialcoisa.rede.mentoring.domain.MentorSlot;
import com.socialcoisa.rede.shared.web.ApiException;

class MentorSlotValidationTests {
	private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
	private final MentorRepository repository = mock(MentorRepository.class);
	private final MentorService service = new MentorService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

	@ParameterizedTest
	@MethodSource("invalidIntervals")
	void publicationRejectsMissingEqualAndInvertedEndpoints(Instant start, Instant end) {
		givenMentor();
		assertThatThrownBy(() -> service.createSlot("mentor", "owner", start, end))
			.isInstanceOfSatisfying(ApiException.class, error -> {
				assertThat(error.status()).isEqualTo(400);
				assertThat(error.code()).isEqualTo("INVALID_SLOT_INTERVAL");
			});
		verify(repository, never()).saveSlot(any());
	}

	@ParameterizedTest
	@MethodSource("invalidIntervals")
	void bookingRejectsLegacyInvalidIntervals(Instant start, Instant end) {
		givenMentor();
		when(repository.findSlotById("slot")).thenReturn(Optional.of(
			MentorSlot.create("slot", "mentor", start, end)));
		assertThatThrownBy(() -> service.scheduleSession("mentor", "guest", "slot", "Oi"))
			.isInstanceOfSatisfying(ApiException.class, error -> {
				assertThat(error.status()).isEqualTo(409);
				assertThat(error.code()).isEqualTo("INVALID_SLOT_INTERVAL");
			});
		verify(repository, never()).reserveSlot("slot", "mentor", NOW);
		verify(repository, never()).saveSession(any());
	}

	@ParameterizedTest
	@ValueSource(longs = {-1, 0})
	void bookingRejectsAnEndAtOrBeforeTheClock(long secondsAfterNow) {
		givenMentor();
		when(repository.findSlotById("slot")).thenReturn(Optional.of(
			MentorSlot.create("slot", "mentor", NOW.minusSeconds(3600), NOW.plusSeconds(secondsAfterNow))));
		assertThatThrownBy(() -> service.scheduleSession("mentor", "guest", "slot", "Oi"))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("SLOT_EXPIRED"));
		verify(repository, never()).reserveSlot("slot", "mentor", NOW);
		verify(repository, never()).saveSession(any());
	}

	private void givenMentor() {
		when(repository.findById("mentor")).thenReturn(Optional.of(
			Mentor.create("mentor", "owner", List.of("Java"), "Gratuito", null, null, NOW)));
	}

	static Stream<Arguments> invalidIntervals() {
		return Stream.of(Arguments.of(null, null), Arguments.of(null, NOW), Arguments.of(NOW, null),
			Arguments.of(NOW, NOW), Arguments.of(NOW, NOW.minusSeconds(1)));
	}
}
