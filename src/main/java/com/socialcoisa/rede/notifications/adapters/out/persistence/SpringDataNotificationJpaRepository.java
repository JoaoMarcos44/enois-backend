package com.socialcoisa.rede.notifications.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Modifying;

interface SpringDataNotificationJpaRepository extends JpaRepository<NotificationJpaEntity, String> {

	@Query("""
			SELECT n FROM NotificationJpaEntity n WHERE n.userId = :userId
			AND (:before IS NULL OR n.createdAt < :before OR (n.createdAt = :before AND n.id < :beforeId))
			ORDER BY n.createdAt DESC, n.id DESC""")
	List<NotificationJpaEntity> findByUserId(@Param("userId") String userId, @Param("before") Instant before,
			@Param("beforeId") String beforeId, Pageable pageable);

	@Modifying
	@Query(value = "UPDATE NotificationJpaEntity n SET n.read = true WHERE n.userId = :userId")
	@Transactional
	void markAllReadByUserId(@Param("userId") String userId);

}
