package com.socialcoisa.rede.notifications.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.notifications.domain.Notification;
import com.socialcoisa.rede.shared.domain.TimestampIdCursor;

public interface NotificationRepository {

	void save(Notification notification);

	Optional<Notification> findById(String id);

	List<Notification> findByUserId(String userId, TimestampIdCursor before, int limit);

	void markRead(String notificationId);

	void markAllReadByUser(String userId);

}
