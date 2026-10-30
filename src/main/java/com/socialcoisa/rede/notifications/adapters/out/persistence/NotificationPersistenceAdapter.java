package com.socialcoisa.rede.notifications.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.notifications.application.port.out.NotificationRepository;
import com.socialcoisa.rede.notifications.domain.Notification;
import com.socialcoisa.rede.shared.domain.TimestampIdCursor;

@Repository
class NotificationPersistenceAdapter implements NotificationRepository {

	private final SpringDataNotificationJpaRepository springData;

	NotificationPersistenceAdapter(SpringDataNotificationJpaRepository springData) {
		this.springData = springData;
	}

	@Override
	public void save(Notification notification) {
		springData.save(NotificationJpaEntity.fromDomain(notification));
	}

	@Override
	public Optional<Notification> findById(String id) {
		return springData.findById(id).map(NotificationJpaEntity::toDomain);
	}

	@Override
	public List<Notification> findByUserId(String userId, TimestampIdCursor before, int limit) {
		return springData.findByUserId(userId, before == null ? null : before.createdAt(),
			before == null ? null : before.id(), PageRequest.of(0, limit)).stream()
			.map(NotificationJpaEntity::toDomain)
			.toList();
	}

	@Override
	public void markRead(String notificationId) {
		springData.findById(notificationId).ifPresent(entity -> {
			entity.markRead();
			springData.save(entity);
		});
	}

	@Override
	public void markAllReadByUser(String userId) {
		springData.markAllReadByUserId(userId);
	}

}
