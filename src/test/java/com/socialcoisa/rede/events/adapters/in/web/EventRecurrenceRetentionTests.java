package com.socialcoisa.rede.events.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.socialcoisa.rede.events.application.EventRecurrenceService;
import com.socialcoisa.rede.events.application.EventService;
import com.socialcoisa.rede.events.application.port.out.EventRepository;
import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabaseSupport;
import com.socialcoisa.rede.shared.security.Hashes;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "rede.security.signup-captcha-enabled=true",
    "rede.security.signup-guard-enabled=true",
    "rede.rate.post-guard-enabled=true",
    "rede.security.new-account-cooldown-seconds=30",
    "rede.rate.duplicate-window-minutes=30",
    "rede.origins.enforce=true"
})
@AutoConfigureMockMvc
@Import(EventRecurrenceRetentionTests.TimeConfiguration.class)
class EventRecurrenceRetentionTests {
    @DynamicPropertySource
    static void isolateTemporalDatabase(DynamicPropertyRegistry registry) {
        String configured = System.getProperty("spring.datasource.url", System.getenv("SPRING_DATASOURCE_URL"));
        registry.add("spring.datasource.url", () -> configured == null
            ? "jdbc:h2:mem:event_recurrence_retention;MODE=MySQL;DB_CLOSE_DELAY=-1" : configured);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EventRepository events;
    @Autowired private EventService service;
    @Autowired private EventRecurrenceService recurrence;
    @Autowired private MovingClock clock;
    @Autowired private JdbcTemplate jdbc;
    // This test's isolated database must not replace the JVM-wide TestDatabase binding.
    @MockitoBean private TestDatabaseSupport sharedDatabaseSupport;

    @ParameterizedTest
    @ValueSource(ints = {2026, 2027})
    void monthlyDaySurvivesRetentionThroughLeapAndNonLeapFebruary(int year) throws Exception {
        LocalDate anchor = LocalDate.of(year, 1, 31);
        clock.at(anchor.minusDays(1).atTime(18, 30).toInstant(ZoneOffset.UTC));
        String id = create(authenticate(), anchor.toString(), "MONTHLY");
        Event latest = events.findById(id).orElseThrow();
        for (int month = 1; month <= 13; month++) {
            latest = spawnAfter(latest);
            assertThat(latest.scheduledDate()).contains(anchor.plusMonths(month));
            clock.at(clock.instant().plus(Duration.ofHours(24)));
            service.sanitizeExpired();
        }
        assertThat(events.findById(id)).isPresent();
        String march = LocalDate.of(year + 1, 3, 31).toString();
        perform(get("/events/" + latest.id())).andExpect(jsonPath("$.nextOccurrenceDate").value(march));
        clock.at(LocalDate.of(year + 1, 2, 2).atTime(19, 0, 2).toInstant(ZoneOffset.UTC));
        service.sanitizeExpired();
        assertThat(events.findById(id)).isEmpty();
        perform(get("/events/" + latest.id())).andExpect(jsonPath("$.nextOccurrenceDate").value(march));
        latest = spawnAfter(latest);
        assertThat(latest.date()).isEqualTo(march);
        assertThat(events.existsInSeries(id, LocalDate.of(year + 1, 3, year == 2027 ? 29 : 28).toString()))
            .isFalse();
        assertThat(events.countAttendees(latest.id())).isZero();
        assertThat(spawnAfter(latest).date()).isEqualTo(LocalDate.of(year + 1, 4, 30).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WEEKLY", "BIWEEKLY"})
    void weeklyIntervalsStayUnchangedAfterOriginalIsPurged(String rule) throws Exception {
        LocalDate anchor = LocalDate.of(2027, 1, 31);
        clock.at(Instant.parse("2027-01-30T18:30:00Z"));
        String id = create(authenticate(), anchor.toString(), rule);
        Event latest = events.findById(id).orElseThrow();
        int interval = rule.equals("WEEKLY") ? 7 : 14;
        for (int elapsed = interval; elapsed <= 420; elapsed += interval) {
            latest = spawnAfter(latest);
            assertThat(latest.scheduledDate()).contains(anchor.plusDays(elapsed));
            clock.at(clock.instant().plus(Duration.ofHours(24)));
            service.sanitizeExpired();
        }
        assertThat(events.findById(id)).isEmpty();
        perform(get("/events/" + latest.id())).andExpect(jsonPath("$.nextOccurrenceDate")
            .value(latest.scheduledDate().orElseThrow().plusDays(interval).toString()));
    }

    private Event spawnAfter(Event previous) {
        clock.at(previous.expiresAt().orElseThrow().plusSeconds(1));
        recurrence.spawnNextOccurrences();
        recurrence.spawnNextOccurrences();
        return events.findLatestInSeries(previous.seriesId()).orElseThrow();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Client-Origin", "https://enois.ia.br"));
    }

    private TestAuthHelper.AuthSession authenticate() throws Exception {
        String prefix = "evanchor_" + UUID.randomUUID().toString().substring(0, 8);
        var challenge = mapper.readTree(perform(get("/auth/signup-challenge"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var numbers = java.util.regex.Pattern.compile("[0-9]+").matcher(challenge.get("question").asText());
        assertThat(numbers.find()).isTrue();
        int left = Integer.parseInt(numbers.group());
        assertThat(numbers.find()).isTrue();
        int right = Integer.parseInt(numbers.group());
        clock.at(clock.instant().plusMillis(1500));
        perform(post("/auth/signup").header("Idempotency-Key", "signup-" + prefix)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                "name", prefix, "role", "USER", "email", prefix + "@test.com", "password", "Test1234!",
                "acceptedTerms", true, "birthDate", "1990-05-10", "challengeId", challenge.get("challengeId").asText(),
                "challengeAnswer", left + right)))).andExpect(status().isCreated());
        String verificationToken = UUID.randomUUID().toString();
        jdbc.update("update pending_signups set token_hash = ? where email = ?",
            Hashes.sha256Hex(verificationToken), prefix + "@test.com");
        var verified = perform(post("/auth/email/verify")
            .header("Idempotency-Key", "verify-" + prefix).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("token", verificationToken))))
            .andExpect(status().isOk()).andReturn();
        String userId = jdbc.queryForObject("select id from users where email = ?", String.class,
            prefix + "@test.com");
        var session = new TestAuthHelper.AuthSession(TestAuthHelper.sessionToken(verified),
            TestAuthHelper.refreshToken(verified), userId);
        clock.at(clock.instant().plusSeconds(31));
        return session;
    }

    private String create(TestAuthHelper.AuthSession host, String date, String rule) throws Exception {
        String response = perform(post("/events").header("X-Session-Token", host.token())
            .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("title", "Recurrence " + UUID.randomUUID(),
                "date", date, "time", "19:00", "format", "Online", "host", "Enois", "recurrence", rule))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("id").asText();
    }

    static class MovingClock extends Clock {
        private volatile Instant current = Instant.parse("2026-10-30T18:30:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return current; }
        void at(Instant value) { current = value; }
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MovingClock eventRetentionClock() { return new MovingClock(); }
    }
}
