package com.socialcoisa.rede.shared.web;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.shared.domain.TimestampIdCursor;

@Component
public class CursorCodec {

	public Optional<Instant> decode(String cursor) {
		if (!StringUtils.hasText(cursor)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Instant.parse(cursor));
		}
		catch (RuntimeException exception) {
			throw new ApiException(400, "INVALID_CURSOR", "Cursor invalido.");
		}
	}

	public Optional<TimestampIdCursor> decodePosition(String cursor) {
		if (!StringUtils.hasText(cursor)) return Optional.empty();
		if (!cursor.startsWith("v1.")) {
			return decode(cursor).map(at -> new TimestampIdCursor(at, null));
		}
		try {
			if (cursor.length() > 1500) throw new IllegalArgumentException();
			byte[] bytes = Base64.getUrlDecoder().decode(cursor.substring(3));
			String payload = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
			String[] parts = payload.split("\\|", -1);
			if (parts.length != 2 || parts[1].isBlank() || parts[1].length() > 255) {
				throw new IllegalArgumentException();
			}
			return Optional.of(new TimestampIdCursor(Instant.parse(parts[0]), parts[1]));
		}
		catch (RuntimeException | CharacterCodingException exception) {
			throw new ApiException(400, "INVALID_CURSOR", "Cursor invalido.");
		}
	}

	public String encodePosition(TimestampIdCursor cursor) {
		String payload = cursor.createdAt() + "|" + cursor.id();
		return "v1." + Base64.getUrlEncoder().withoutPadding()
			.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
	}

	public String encode(Instant instant) {
		return instant == null ? null : instant.toString();
	}
}
