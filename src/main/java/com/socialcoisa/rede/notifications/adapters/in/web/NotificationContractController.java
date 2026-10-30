package com.socialcoisa.rede.notifications.adapters.in.web;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.notifications.domain.Notification;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.domain.TimestampIdCursor;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/notifications")
class NotificationContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final NotificationService notificationService;
	private final CursorCodec cursorCodec;
	private final UserProfileRepository userProfileRepository;
	private final BlockedUsersReader blockedUsersReader;

	NotificationContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			NotificationService notificationService, CursorCodec cursorCodec,
			UserProfileRepository userProfileRepository, BlockedUsersReader blockedUsersReader) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.notificationService = notificationService;
		this.cursorCodec = cursorCodec;
		this.userProfileRepository = userProfileRepository;
		this.blockedUsersReader = blockedUsersReader;
	}

	@GetMapping
	PageResponse<NotificationView> notifications(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		TimestampIdCursor before = cursorCodec.decodePosition(cursor).orElse(null);
		// Bloqueio: notificacao de quem foi bloqueado (ou de quem bloqueou) nao aparece mais.
		Set<String> hidden = blockedUsersReader.hiddenIdsFor(user.id().value());
		List<Notification> fetched = notificationService.list(user.id().value(), before, limit);
		List<Notification> notifications = fetched.stream()
			.filter(n -> n.actorId() == null || !hidden.contains(n.actorId()))
			.toList();
		Map<String, UserProfile> profiles = resolveActors(notifications);
		var items = notifications.stream()
			.map(n -> {
				UserProfile p = profiles.get(n.actorId());
				return new NotificationView(n.id(), n.kind(), n.actorId(),
					p != null ? p.name() : null,
					p != null ? p.username() : null,
					p != null ? p.avatar() : null,
					p != null ? NameStyleView.from(p) : null,
					n.title(), n.body(),
					n.createdAt() != null ? n.createdAt().toString() : null,
					n.isRead(), n.href(), n.ctaLabel());
			})
			.toList();
		String nextCursor = fetched.size() == limit && !fetched.isEmpty()
			? cursorCodec.encodePosition(new TimestampIdCursor(fetched.get(fetched.size() - 1).createdAt(),
				fetched.get(fetched.size() - 1).id())) : null;
		return new PageResponse<>(items, nextCursor);
	}

	private Map<String, UserProfile> resolveActors(List<Notification> notifications) {
		Set<String> actorIds = notifications.stream()
			.map(Notification::actorId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		if (actorIds.isEmpty()) return Map.of();
		return userProfileRepository.findAllByIds(actorIds).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
	}

	record NotificationView(String id, String kind, String actorId, String actorName, String actorUsername,
			String actorAvatar, NameStyleView actorStyle, String title, String body, String createdAt, boolean read,
			String href, String ctaLabel) {
	}

	@PostMapping("/{notificationId}/read")
	ResponseEntity<String> read(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String notificationId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("notificationId", notificationId));
		return idempotency.execute(user.id(), "POST", "/api/notifications/{notificationId}/read", idempotencyKey, hash,
			() -> {
				notificationService.markRead(notificationId, user.id().value());
				return new CommandResponse(200, new StatusResponse(notificationId, "READ"));
			});
	}

	@PostMapping("/read-all")
	ResponseEntity<String> readAll(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("userId", user.id().value()));
		return idempotency.execute(user.id(), "POST", "/api/notifications/read-all", idempotencyKey, hash,
			() -> {
				notificationService.markAllRead(user.id().value());
				return new CommandResponse(200, new StatusResponse(user.id().value(), "ALL_READ"));
			});
	}

}
