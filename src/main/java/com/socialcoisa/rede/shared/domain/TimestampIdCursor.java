package com.socialcoisa.rede.shared.domain;

import java.time.Instant;
import java.util.Objects;

/** An ID-less position represents a legacy timestamp cursor. */
public record TimestampIdCursor(Instant createdAt, String id) {
	public TimestampIdCursor {
		Objects.requireNonNull(createdAt);
	}
}
