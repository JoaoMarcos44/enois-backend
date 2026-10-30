package com.socialcoisa.rede.mentoring.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.mentoring.application.port.out.MentorRepository;
import com.socialcoisa.rede.mentoring.domain.Mentor;
import com.socialcoisa.rede.mentoring.domain.MentorAvailability;
import com.socialcoisa.rede.mentoring.domain.MentorSession;
import com.socialcoisa.rede.mentoring.domain.MentorSlot;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class MentorService {

	/** Os horários da agenda são escolhidos em São Paulo, que é como a tela mostra e como o mentor pensa. */
	static final ZoneId AGENDA_ZONE = ZoneId.of("America/Sao_Paulo");
	/** Janela em que os horários são gerados a partir das regras semanais. */
	static final int GENERATION_DAYS = 28;

	public record AvailabilityRule(int dayOfWeek, List<String> times) {
	}

	private final MentorRepository repository;
	private final Clock clock;

	public MentorService(MentorRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional
	public Mentor create(String userId, List<String> expertise, String price, String banner) {
		return create(userId, expertise, price, banner, null);
	}

	@Transactional
	public Mentor create(String userId, List<String> expertise, String price, String banner, String meetingUrl) {
		if (repository.findByUserId(userId).isPresent()) {
			throw new ApiException(409, "ALREADY_MENTOR", "Voce ja esta cadastrado como mentor.");
		}
		String cleanUrl = sanitizeMeetingUrl(meetingUrl);
		String id = "mentor_" + UUID.randomUUID().toString().replace("-", "");
		Mentor mentor = Mentor.create(id, userId, expertise, price, banner, cleanUrl, clock.instant());
		repository.save(mentor);
		return mentor;
	}

	@Transactional
	public Mentor update(String mentorId, List<String> expertise, String price, String userId) {
		return update(mentorId, expertise, price, null, userId);
	}

	@Transactional
	public Mentor update(String mentorId, List<String> expertise, String price, String banner, String userId) {
		return update(mentorId, expertise, price, banner, null, userId);
	}

	@Transactional
	public Mentor update(String mentorId, List<String> expertise, String price, String banner, String meetingUrl,
			String userId) {
		Mentor mentor = requireOwner(mentorId, userId);
		Mentor updated = mentor.update(expertise, price, banner, sanitizeMeetingUrl(meetingUrl), clock.instant());
		repository.save(updated);
		return updated;
	}

	/** Sala externa (Meet/Jitsi...): opcional; se vier, tem que ser um link https de ate 500. */
	private String sanitizeMeetingUrl(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String url = raw.trim();
		if (!url.startsWith("https://") || url.length() > 500) {
			throw new ApiException(400, "INVALID_MEETING_URL", "Informe um link https valido de ate 500 caracteres.");
		}
		return url;
	}

	@Transactional
	public MentorSlot createSlot(String mentorId, String userId, java.time.Instant startsAt, java.time.Instant endsAt) {
		Mentor mentor = repository.findById(mentorId)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
		if (!mentor.userId().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao e o dono deste perfil de mentor.");
		}
		if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
			throw new ApiException(400, "INVALID_SLOT_INTERVAL", "Informe um intervalo de horario valido.");
		}
		String id = "slot_" + UUID.randomUUID().toString().replace("-", "");
		MentorSlot slot = MentorSlot.create(id, mentorId, startsAt, endsAt);
		repository.saveSlot(slot);
		return slot;
	}

	/** Remove um horario publicado; so o dono. Horario ja reservado nao pode sumir. */
	@Transactional
	public void deleteSlot(String mentorId, String slotId, String userId) {
		Mentor mentor = repository.findById(mentorId)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
		if (!mentor.userId().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao e o dono deste perfil de mentor.");
		}
		MentorSlot slot = repository.findSlotById(slotId)
			.orElseThrow(() -> new ApiException(404, "SLOT_NOT_FOUND", "Horario nao encontrado."));
		// O slot tem que pertencer ao mentor da URL; sem isso, o dono de um mentor apagava o
		// horario de outro passando o próprio mentorId (EN-03).
		if (!slot.mentorId().equals(mentor.id())) {
			throw new ApiException(404, "SLOT_NOT_FOUND", "Horario nao encontrado.");
		}
		if (!slot.available() || !repository.deleteAvailableSlot(slotId, mentor.id())) {
			throw new ApiException(409, "SLOT_BOOKED", "Este horario ja foi reservado.");
		}
	}

	@Transactional
	public MentorSession scheduleSession(String mentorId, String menteeId, String slotId, String message) {
		Mentor mentor = repository.findById(mentorId)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
		MentorSlot slot = repository.findSlotById(slotId)
			.orElseThrow(() -> new ApiException(404, "SLOT_NOT_FOUND", "Horario nao encontrado."));
		if (!slot.mentorId().equals(mentor.id())) {
			throw new ApiException(404, "SLOT_NOT_FOUND", "Horario nao encontrado.");
		}
		if (!slot.available()) {
			throw new ApiException(409, "SLOT_UNAVAILABLE", "Horario nao esta mais disponivel.");
		}
		java.time.Instant now = clock.instant();
		if (slot.startsAt() == null || slot.endsAt() == null || !slot.endsAt().isAfter(slot.startsAt())) {
			throw new ApiException(409, "INVALID_SLOT_INTERVAL", "Este horario possui um intervalo invalido.");
		}
		if (!slot.endsAt().isAfter(now)) {
			throw new ApiException(409, "SLOT_EXPIRED", "Este horario ja foi encerrado.");
		}
		if (!repository.reserveSlot(slotId, mentorId, now)) {
			throw new ApiException(409, "SLOT_UNAVAILABLE", "Horario nao esta mais disponivel.");
		}
		String id = "mentor_session_" + UUID.randomUUID().toString().replace("-", "");
		MentorSession session = MentorSession.schedule(id, mentorId, menteeId, slotId, message, now);
		repository.saveSession(session);
		return session;
	}

	@Transactional
	public void delete(String mentorId, String userId) {
		Mentor mentor = repository.findById(mentorId)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
		if (!mentor.userId().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao e o dono deste perfil de mentor.");
		}
		repository.delete(mentorId);
	}

	public List<Mentor> list(String expertise, java.time.Instant before, int limit) {
		return repository.findAll(expertise, before, limit);
	}

	public Mentor getById(String id) {
		return repository.findById(id)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
	}

	public List<MentorSlot> getSlots(String mentorId) {
		return repository.findSlotsByMentor(mentorId);
	}

	/**
	 * Proximo horario livre do mentor (ISO-8601) ou null quando nao ha agenda publicada.
	 *
	 * Antes o campo vinha sempre nulo na resposta, entao a tela nao tinha como dizer quando a pessoa
	 * atende nem por onde comecar o agendamento.
	 */
	public String nextAvailableSlot(String mentorId) {
		java.time.Instant now = clock.instant();
		return repository.findSlotsByMentor(mentorId).stream()
			.filter(MentorSlot::available)
			.filter(slot -> slot.startsAt() != null && slot.startsAt().isAfter(now))
			.sorted(java.util.Comparator.comparing(MentorSlot::startsAt))
			.map(slot -> slot.startsAt().toString())
			.findFirst()
			.orElse(null);
	}

	/** Inscrições recebidas pelo mentor (só o dono), com o horário de cada uma. */
	public List<MentorSession> sessionsOf(String mentorId, String userId) {
		requireOwner(mentorId, userId);
		return repository.findSessionsByMentor(mentorId);
	}

	public List<MentorAvailability> availabilitiesOf(String mentorId, String userId) {
		requireOwner(mentorId, userId);
		return repository.findAvailabilitiesByMentor(mentorId);
	}

	/**
	 * Substitui a disponibilidade semanal do mentor e reconcilia os horários publicados: gera os
	 * que faltam para os próximos {@link #GENERATION_DAYS} dias e retira os horários livres que não
	 * correspondem mais a nenhuma regra. Horário já reservado nunca é tocado.
	 */
	@Transactional
	public List<MentorAvailability> saveAvailability(String mentorId, String userId, List<AvailabilityRule> rules) {
		Mentor mentor = requireOwner(mentorId, userId);
		List<MentorAvailability> cleaned = validateRules(mentor.id(), rules);

		repository.deleteAvailabilitiesByMentor(mentor.id());
		cleaned.forEach(repository::saveAvailability);

		Instant now = clock.instant();
		Set<Instant> wanted = new HashSet<>();
		LocalDate today = LocalDate.ofInstant(now, AGENDA_ZONE);
		for (int day = 0; day < GENERATION_DAYS; day++) {
			LocalDate date = today.plusDays(day);
			int weekday = date.getDayOfWeek().getValue();
			for (MentorAvailability rule : cleaned) {
				if (rule.dayOfWeek() != weekday) {
					continue;
				}
				Instant startsAt = date.atTime(LocalTime.parse(rule.timeOfDay())).atZone(AGENDA_ZONE).toInstant();
				if (!startsAt.isAfter(now)) {
					continue;
				}
				wanted.add(startsAt);
			}
		}

		Set<Instant> existing = new HashSet<>();
		for (MentorSlot slot : repository.findSlotsByMentor(mentor.id())) {
			if (slot.startsAt() == null) {
				continue;
			}
			existing.add(slot.startsAt());
			boolean stale = slot.available() && slot.startsAt().isAfter(now) && !wanted.contains(slot.startsAt());
			if (stale) {
				repository.deleteAvailableSlot(slot.id(), mentor.id());
			}
		}
		for (Instant startsAt : wanted) {
			if (existing.contains(startsAt)) {
				continue;
			}
			String id = "slot_" + UUID.randomUUID().toString().replace("-", "");
			repository.saveSlot(MentorSlot.create(id, mentor.id(), startsAt, startsAt.plusSeconds(3600)));
		}
		return repository.findAvailabilitiesByMentor(mentor.id());
	}

	private Mentor requireOwner(String mentorId, String userId) {
		Mentor mentor = repository.findById(mentorId)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
		if (!mentor.userId().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao e o dono deste perfil de mentor.");
		}
		return mentor;
	}

	private List<MentorAvailability> validateRules(String mentorId, List<AvailabilityRule> rules) {
		if (rules == null || rules.isEmpty()) {
			return List.of();
		}
		List<MentorAvailability> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (AvailabilityRule rule : rules) {
			if (rule == null || rule.dayOfWeek() < 1 || rule.dayOfWeek() > 7) {
				throw new ApiException(400, "INVALID_DAY_OF_WEEK", "Dia da semana deve estar entre 1 (segunda) e 7 (domingo).");
			}
			List<String> times = rule.times() == null ? List.of() : rule.times();
			if (times.size() > 12) {
				throw new ApiException(400, "TOO_MANY_TIMES", "No maximo 12 horarios por dia.");
			}
			for (String raw : times) {
				String time = raw == null ? "" : raw.trim();
				LocalTime parsed;
				try {
					parsed = LocalTime.parse(time.length() == 5 ? time : "");
				} catch (DateTimeParseException e) {
					throw new ApiException(400, "INVALID_TIME", "Horario invalido; use o formato HH:mm.");
				}
				String normalized = String.format("%02d:%02d", parsed.getHour(), parsed.getMinute());
				if (!seen.add(rule.dayOfWeek() + "-" + normalized)) {
					continue;
				}
				String id = "avail_" + UUID.randomUUID().toString().replace("-", "");
				result.add(MentorAvailability.create(id, mentorId, rule.dayOfWeek(), normalized));
			}
		}
		result.sort(Comparator.comparingInt(MentorAvailability::dayOfWeek).thenComparing(MentorAvailability::timeOfDay));
		return result;
	}

}
