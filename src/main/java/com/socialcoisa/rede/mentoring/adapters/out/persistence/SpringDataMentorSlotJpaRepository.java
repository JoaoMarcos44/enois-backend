package com.socialcoisa.rede.mentoring.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface SpringDataMentorSlotJpaRepository extends JpaRepository<MentorSlotJpaEntity, String> {

	List<MentorSlotJpaEntity> findByMentorIdOrderByStartsAtAsc(String mentorId);

	@Modifying
	@Query("""
		update MentorSlotJpaEntity slot set slot.available = false
		where slot.id = :slotId and slot.mentorId = :mentorId and slot.available = true
		and slot.endsAt > slot.startsAt and slot.endsAt > :now
		""")
	int reserve(String slotId, String mentorId, Instant now);

	@Modifying
	@Query("""
		delete from MentorSlotJpaEntity slot
		where slot.id = :slotId and slot.mentorId = :mentorId and slot.available = true
		""")
	int deleteAvailable(String slotId, String mentorId);

}
