package com.socialcoisa.rede.events.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.events.application.EventRecurrenceService;
import com.socialcoisa.rede.events.application.port.out.EventRepository;
import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.events.domain.EventAttendee;
import com.socialcoisa.rede.events.domain.EventRecurrence;
import com.socialcoisa.rede.identity.application.port.out.CredentialRepository;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.security.SuspiciousText;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Rituais (eventos recorrentes): a serie agenda a proxima ocorrencia uma vez so, avisa quem foi
 * (sem copiar a presenca) e para quando o dono desliga, apaga uma ocorrencia ou sai da rede.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventRecurrenceTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EventRecurrenceService recurrence;

	@Autowired
	private EventRepository events;

	@Autowired
	private NotificationService notifications;

	@Autowired
	private CredentialRepository credentials;

	@Autowired
	private SanctionGateReader sanctionGate;

	@Autowired
	private SuspiciousText suspiciousText;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void weeklyEventExposesRecurrenceSeriesAndNextDate() throws Exception {
		var host = auth.authenticate("evrec_host1");
		String date = LocalDate.now(ZoneOffset.UTC).plusDays(10).toString();

		JsonNode created = create(host, "evrec Show and tell", date, "WEEKLY", "evrec-create-1");

		assertThat(created.get("recurrence").asText()).isEqualTo("WEEKLY");
		assertThat(created.get("seriesId").asText()).isEqualTo(created.get("id").asText());
		assertThat(created.get("nextOccurrenceDate").asText())
			.isEqualTo(LocalDate.parse(date).plusWeeks(1).toString());

		mockMvc.perform(get("/events").param("recurring", "true").param("limit", "200"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + created.get("id").asText() + "')]").exists())
			.andExpect(jsonPath("$.items[?(@.recurrence == 'NONE')]").doesNotExist());
	}

	@Test
	void invalidRecurrenceAndMissingTimeAreRejected() throws Exception {
		var host = auth.authenticate("evrec_host2");
		String date = LocalDate.now(ZoneOffset.UTC).plusDays(10).toString();

		perform(host, body("evrec invalid", date, "19:00", "DAILY"), "evrec-bad-1")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_RECURRENCE"));
		perform(host, body("evrec sem hora", date, "a noite", "WEEKLY"), "evrec-bad-2")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_EVENT_TIME"));
	}

	@Test
	void schedulerCreatesExactlyOneNextOccurrenceAndNotifiesAttendeesWithoutCopyingThem() throws Exception {
		var host = auth.authenticate("evrec_host3");
		var attendee = auth.authenticate("evrec_guest3");
		LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
		JsonNode created = create(host, "evrec Live code review", yesterday.toString(), "WEEKLY", "evrec-create-3");
		String eventId = created.get("id").asText();
		events.saveAttendee(EventAttendee.attend(eventId, attendee.userId(), java.time.Instant.now()));

		recurrence.spawnNextOccurrences();
		recurrence.spawnNextOccurrences();

		String nextDate = yesterday.plusWeeks(1).toString();
		assertThat(events.existsInSeries(eventId, nextDate)).isTrue();
		Event next = events.findLatestInSeries(eventId).orElseThrow();
		assertThat(next.id()).isNotEqualTo(eventId);
		assertThat(next.date()).isEqualTo(nextDate);
		assertThat(next.title()).isEqualTo("evrec Live code review");
		assertThat(next.recurrence()).isEqualTo(EventRecurrence.WEEKLY);
		// Presenca nao e copiada: confirmar de novo e escolha da pessoa.
		assertThat(events.countAttendees(next.id())).isZero();

		var received = notifications.list(attendee.userId(), null, 50).stream()
			.filter(n -> "EVENT_NEXT_OCCURRENCE".equals(n.kind()))
			.toList();
		assertThat(received).hasSize(1);
		assertThat(received.get(0).href()).isEqualTo("/eventos?evento=" + next.id());
	}

	@ParameterizedTest
	@CsvSource({"31/01/2027, 2027-02-07", "2027-01-31, 07/02/2027"})
	void latestOccurrenceUsesParsedDateWhenCreationTimestampsTie(String firstDate, String laterDate) {
		String seriesId = "evrec_order_tie_" + firstDate.replace('/', '-');
		Instant createdAt = Instant.parse("2027-01-01T12:00:00Z");
		Event first = seriesOccurrence(seriesId, seriesId, firstDate, "evrec order first", "evrec_order_host", createdAt);
		Event later = seriesOccurrence(seriesId + "_next", seriesId, laterDate, "evrec order later", "evrec_order_host", createdAt);
		events.save(first);
		events.save(later);

		assertThat(events.findById(first.id()).orElseThrow().createdAt())
			.isEqualTo(events.findById(later.id()).orElseThrow().createdAt());
		assertThat(events.findLatestInSeries(seriesId).orElseThrow().id()).isEqualTo(later.id());
	}

	@Test
	void latestOccurrenceUsesScheduleEvenWhenItWasCreatedEarlier() {
		String seriesId = "evrec_order_older_created";
		Instant createdAt = Instant.parse("2027-01-01T12:00:00Z");
		events.save(seriesOccurrence(seriesId, seriesId, "31/01/2027", "evrec order first", "evrec_order_host", createdAt));
		Event later = seriesOccurrence(seriesId + "_next", seriesId, "2027-02-07", "evrec order later",
			"evrec_order_host", createdAt.minusSeconds(1));
		events.save(later);
		assertThat(events.findLatestInSeries(seriesId).orElseThrow().id()).isEqualTo(later.id());
		events.save(seriesOccurrence(seriesId + "_unreadable", seriesId, "sem data", "evrec order unreadable",
			"evrec_order_host", createdAt.plusSeconds(1)));

		assertThat(events.findLatestInSeries(seriesId).orElseThrow().id()).isEqualTo(later.id());
	}

	@Test
	void seriesWithoutReadableDatesHasNoLatestOccurrence() {
		String seriesId = "evrec_order_unreadable";
		events.save(seriesOccurrence(seriesId, seriesId, "sem data", "evrec order unreadable", "evrec_order_host",
			Instant.parse("2027-01-01T12:00:00Z")));

		assertThat(events.findLatestInSeries(seriesId)).isEmpty();
		assertThat(events.findLatestInSeries("evrec_order_missing")).isEmpty();
	}

	@Test
	void schedulerContinuesFromLatestOccurrenceWhenCreationTimestampsTie() throws Exception {
		var host = auth.authenticate("evrec_order_host");
		var attendee = auth.authenticate("evrec_order_guest");
		String seriesId = "evrec_order_worker";
		Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		Instant createdAt = now.minusSeconds(1);
		LocalDate latestDate = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(1);
		String firstDate = latestDate.minusWeeks(1).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu"));
		Event first = seriesOccurrence(seriesId, seriesId, firstDate, "evrec order first", host.userId(), createdAt);
		Event latest = seriesOccurrence(seriesId + "_next", seriesId, latestDate.toString(), "evrec order current",
			host.userId(), createdAt);
		first.changeRecurrence(EventRecurrence.WEEKLY, seriesId);
		latest.changeRecurrence(EventRecurrence.WEEKLY, seriesId);
		events.save(first);
		events.save(latest);
		events.saveAttendee(EventAttendee.attend(latest.id(), attendee.userId(), createdAt));
		var worker = new EventRecurrenceService(events, notifications, credentials, sanctionGate, suspiciousText,
			Clock.fixed(now, ZoneOffset.UTC), true);

		try {
			worker.spawnNextOccurrences();
			worker.spawnNextOccurrences();

			String nextDate = latestDate.plusWeeks(1).toString();
			assertThat(events.existsInSeries(seriesId, nextDate)).isTrue();
			Event next = events.findLatestInSeries(seriesId).orElseThrow();
			assertThat(next.date()).isEqualTo(nextDate);
			assertThat(next.title()).isEqualTo(latest.title());
			assertThat(events.countAttendees(next.id())).isZero();
			var received = notifications.list(attendee.userId(), null, 50).stream()
				.filter(n -> "EVENT_NEXT_OCCURRENCE".equals(n.kind())).toList();
			assertThat(received).hasSize(1);
			assertThat(received.get(0).href()).isEqualTo("/eventos?evento=" + next.id());
		}
		finally {
			events.updateSeriesRecurrence(seriesId, EventRecurrence.NONE);
		}
	}

	@Test
	void monthlyOnThe31stFallsOnTheLastDayAndReturnsToThe31st() {
		LocalDate anchor = LocalDate.of(2027, 1, 31);

		LocalDate feb = EventRecurrence.MONTHLY.next(anchor, anchor);
		LocalDate mar = EventRecurrence.MONTHLY.next(anchor, feb);
		LocalDate apr = EventRecurrence.MONTHLY.next(anchor, mar);

		assertThat(feb).isEqualTo(LocalDate.of(2027, 2, 28));
		assertThat(mar).isEqualTo(LocalDate.of(2027, 3, 31));
		assertThat(apr).isEqualTo(LocalDate.of(2027, 4, 30));
		assertThat(EventRecurrence.BIWEEKLY.next(anchor, anchor)).isEqualTo(LocalDate.of(2027, 2, 14));
	}

	@Test
	void onlyTheOwnerChangesRecurrence() throws Exception {
		var host = auth.authenticate("evrec_host5");
		var other = auth.authenticate("evrec_other5");
		String date = LocalDate.now(ZoneOffset.UTC).plusDays(10).toString();
		String eventId = create(host, "evrec AMA", date, null, "evrec-create-5").get("id").asText();

		changeRecurrence(other, eventId, "WEEKLY", "evrec-patch-5a")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_EVENT_OWNER"));

		changeRecurrence(host, eventId, "MONTHLY", "evrec-patch-5b")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.recurrence").value("MONTHLY"))
			.andExpect(jsonPath("$.seriesId").value(eventId));
	}

	@Test
	void onlyTheOwnerSeesCanManageRecurrence() throws Exception {
		var host = auth.authenticate("evrec_host9");
		var other = auth.authenticate("evrec_other9");
		String date = LocalDate.now(ZoneOffset.UTC).plusDays(10).toString();
		JsonNode created = create(host, "evrec Gestao", date, "WEEKLY", "evrec-create-9");
		String eventId = created.get("id").asText();
		assertThat(created.get("canManageRecurrence").asBoolean()).isTrue();
		String item = "$.items[?(@.id == '" + eventId + "')].canManageRecurrence";

		mockMvc.perform(get("/events/" + eventId).header("X-Session-Token", host.token()))
			.andExpect(jsonPath("$.canManageRecurrence").value(true));
		mockMvc.perform(get("/events").param("recurring", "true").param("limit", "200")
				.header("X-Session-Token", host.token()))
			.andExpect(jsonPath(item).value(org.hamcrest.Matchers.contains(true)));

		mockMvc.perform(get("/events/" + eventId).header("X-Session-Token", other.token()))
			.andExpect(jsonPath("$.canManageRecurrence").value(false));
		mockMvc.perform(get("/events").param("recurring", "true").param("limit", "200")
				.header("X-Session-Token", other.token()))
			.andExpect(jsonPath(item).value(org.hamcrest.Matchers.contains(false)));

		mockMvc.perform(get("/events/" + eventId))
			.andExpect(jsonPath("$.canManageRecurrence").value(false))
			.andExpect(jsonPath("$.createdBy").doesNotExist());
		mockMvc.perform(get("/events").param("recurring", "true").param("limit", "200"))
			.andExpect(jsonPath(item).value(org.hamcrest.Matchers.contains(false)));
	}

	@Test
	void settingNoneStopsTheSeries() throws Exception {
		var host = auth.authenticate("evrec_host6");
		LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
		String eventId = create(host, "evrec Game jam", yesterday.toString(), "MONTHLY", "evrec-create-6")
			.get("id").asText();

		changeRecurrence(host, eventId, "NONE", "evrec-patch-6")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.recurrence").value("NONE"))
			.andExpect(jsonPath("$.nextOccurrenceDate").doesNotExist());
		recurrence.spawnNextOccurrences();

		assertThat(events.findLatestInSeries(eventId).orElseThrow().id()).isEqualTo(eventId);
	}

	@Test
	void removedOccurrenceDoesNotSpawnNorComeBack() throws Exception {
		var host = auth.authenticate("evrec_host7");
		LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
		String eventId = create(host, "evrec Roda de conversa", yesterday.toString(), "WEEKLY", "evrec-create-7")
			.get("id").asText();
		recurrence.spawnNextOccurrences();
		Event next = events.findLatestInSeries(eventId).orElseThrow();
		assertThat(next.id()).isNotEqualTo(eventId);

		mockMvc.perform(delete("/events/" + next.id()).header("X-Session-Token", host.token())
				.header("Idempotency-Key", "evrec-del-7"))
			.andExpect(status().isOk());
		recurrence.spawnNextOccurrences();

		// Sem a parada da serie, a ocorrencia anterior voltaria a ser a mais recente e recriaria a data.
		assertThat(events.existsInSeries(eventId, next.date())).isFalse();
		assertThat(events.findById(eventId).orElseThrow().recurrence()).isEqualTo(EventRecurrence.NONE);
	}

	@Test
	void deletedHostAccountStopsTheSeries() throws Exception {
		var host = auth.authenticate("evrec_host8");
		LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
		String eventId = create(host, "evrec Clube do livro", yesterday.toString(), "WEEKLY", "evrec-create-8")
			.get("id").asText();
		mockMvc.perform(post("/auth/account/delete").header("X-Session-Token", host.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("password", "Test1234!"))))
			.andExpect(status().isOk());

		recurrence.spawnNextOccurrences();

		assertThat(events.findLatestInSeries(eventId).orElseThrow().id()).isEqualTo(eventId);
		assertThat(events.findById(eventId).orElseThrow().recurrence()).isEqualTo(EventRecurrence.NONE);
	}

	private Event seriesOccurrence(String id, String seriesId, String date, String title, String host, Instant createdAt) {
		Event event = Event.create(id, title, date, "19:00", "Online", "Énóis", null, null, null, host, createdAt);
		event.changeRecurrence(EventRecurrence.NONE, seriesId);
		return event;
	}

	private JsonNode create(TestAuthHelper.AuthSession host, String title, String date, String recurrence, String key)
			throws Exception {
		String response = perform(host, body(title, date, "19:00", recurrence), key)
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response);
	}

	private org.springframework.test.web.servlet.ResultActions perform(TestAuthHelper.AuthSession host, String body,
			String key) throws Exception {
		return mockMvc.perform(post("/events").header("X-Session-Token", host.token())
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private org.springframework.test.web.servlet.ResultActions changeRecurrence(TestAuthHelper.AuthSession user,
			String eventId, String value, String key) throws Exception {
		return mockMvc.perform(patch("/events/" + eventId + "/recurrence").header("X-Session-Token", user.token())
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("recurrence", value))));
	}

	private String body(String title, String date, String time, String recurrence) throws Exception {
		Map<String, Object> body = new HashMap<>(Map.of("title", title, "date", date, "time", time,
			"format", "Online", "host", "Énóis"));
		if (recurrence != null) {
			body.put("recurrence", recurrence);
		}
		return objectMapper.writeValueAsString(body);
	}

}
