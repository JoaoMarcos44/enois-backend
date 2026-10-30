package com.socialcoisa.rede.events.adapters.out.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.events.application.port.out.EventRepository;
import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.events.domain.EventRecurrence;

@Repository
class EventPersistenceAdapter implements EventRepository {

	private final SpringDataEventJpaRepository springData;
	private final SpringDataEventAttendeeJpaRepository attendeeRepo;
	private final SpringDataUserEventAttendanceTotalJpaRepository totalsRepo;
	private final Clock clock;

	EventPersistenceAdapter(SpringDataEventJpaRepository springData,
			SpringDataEventAttendeeJpaRepository attendeeRepo,
			SpringDataUserEventAttendanceTotalJpaRepository totalsRepo, Clock clock) {
		this.springData = springData;
		this.attendeeRepo = attendeeRepo;
		this.totalsRepo = totalsRepo;
		this.clock = clock;
	}

	@Override
	public void save(Event event) {
		springData.save(EventJpaEntity.fromDomain(event));
	}

	@Override
	public Optional<Event> findById(String id) {
		return springData.findById(id).map(EventJpaEntity::toDomain);
	}

	@Override
	public List<Event> findAll(String format, String state, String cityKey, Instant before, boolean recurringOnly,
			int limit) {
		PageRequest page = PageRequest.of(0, limit);
		List<EventJpaEntity> entities;
		if (state != null) {
			entities = springData.findByPlace(state, cityKey, StringUtils.hasText(format) ? format : null, before,
				recurringOnly, page);
		} else if (StringUtils.hasText(format)) {
			entities = springData.findByFormat(format, before, recurringOnly, page);
		} else {
			entities = springData.findAllBefore(before, recurringOnly, page);
		}
		return entities.stream().map(EventJpaEntity::toDomain).toList();
	}

	@Override
	public List<Event> findRecurring() {
		return springData.findByRecurrenceNot(EventRecurrence.NONE.name()).stream().map(EventJpaEntity::toDomain).toList();
	}

	@Override
	public Optional<Event> findLatestInSeries(String seriesId) {
		return springData.findBySeriesId(seriesId).stream().map(EventJpaEntity::toDomain)
			.filter(event -> event.scheduledDate().isPresent())
			.max(Comparator.comparing(event -> event.scheduledDate().orElseThrow()));
	}

	@Override
	public boolean existsInSeries(String seriesId, String date) {
		return springData.existsBySeriesIdAndDate(seriesId, date);
	}

	@Override
	@org.springframework.transaction.annotation.Transactional
	public void updateSeriesRecurrence(String seriesId, EventRecurrence recurrence) {
		springData.updateSeriesRecurrence(seriesId, recurrence.name());
	}

	@Override
	public void saveAttendee(com.socialcoisa.rede.events.domain.EventAttendee attendee) {
		attendeeRepo.save(EventAttendeeJpaEntity.create(attendee.eventId(), attendee.userId(), clock.instant()));
	}

	@Override
	public void removeAttendee(String eventId, String userId) {
		attendeeRepo.deleteByEventIdAndUserId(eventId, userId);
	}

	@Override
	public boolean isAttending(String eventId, String userId) {
		return attendeeRepo.existsByEventIdAndUserId(eventId, userId);
	}

	@Override
	public java.util.Set<String> findAttendingEventIds(String userId, java.util.Collection<String> eventIds) {
		if (eventIds == null || eventIds.isEmpty()) {
			return java.util.Set.of();
		}
		return java.util.Set.copyOf(attendeeRepo.findEventIdsByUserIdAndEventIdIn(userId, eventIds));
	}

	@Override
	public List<com.socialcoisa.rede.events.domain.Event> findNotSanitized() {
		return springData.findBySanitizedAtIsNull().stream().map(EventJpaEntity::toDomain).toList();
	}

	@Override
	public void deleteAttendees(String eventId) {
		attendeeRepo.deleteByEventId(eventId);
	}

	@Override
	public List<String> findAttendeeIds(String eventId) {
		return attendeeRepo.findUserIdsByEventId(eventId);
	}

	@Override
	public void addAttendanceToTotal(String userId) {
		var existente = totalsRepo.findById(userId);
		if (existente.isPresent()) {
			var total = existente.get();
			total.increment();
			totalsRepo.save(total);
			return;
		}
		totalsRepo.save(new UserEventAttendanceTotalJpaEntity(userId, 1));
	}

	@Override
	public List<com.socialcoisa.rede.events.domain.Event> findSanitizedBefore(java.time.Instant threshold) {
		return springData.findBySanitizedAtBefore(threshold).stream().map(EventJpaEntity::toDomain).toList();
	}

	@Override
	public void deleteById(String eventId) {
		springData.deleteById(eventId);
	}

	@Override
	public int countAttendees(String eventId) {
		return attendeeRepo.countByEventId(eventId);
	}

}
