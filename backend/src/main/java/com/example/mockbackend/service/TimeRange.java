package com.example.mockbackend.service;

import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * The {@code from} (inclusive) / {@code to} (exclusive) query parameters of the admin exports as instants, with the same
 * rules and messages as adminRuns: ISO 8601 instants with Z or an offset, blank = open end, from after to = 400.
 */
public record TimeRange(Instant from, Instant to) {
    private static final Instant NO_LOWER_BOUND = Instant.EPOCH;
    private static final Instant NO_UPPER_BOUND = Instant.parse("9999-01-01T00:00:00Z");

    /** @throws ApiException 400 INVALID_REQUEST */
    public static TimeRange parse(String from, String to) {
        Instant lower = instant("from", from, NO_LOWER_BOUND);
        Instant upper = instant("to", to, NO_UPPER_BOUND);
        if (lower.isAfter(upper)) {
            throw ApiException.badRequest(ErrorCode.INVALID_REQUEST, "from (" + from + ") must not be after to (" + to + ")");
        }
        return new TimeRange(lower, upper);
    }

    private static Instant instant(String name, String value, Instant absent) {
        if (value == null || value.isBlank()) {
            return absent;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(ErrorCode.INVALID_REQUEST,
                    name + " must be an ISO 8601 instant such as 2026-11-06T00:00:00Z, got '" + value + "'");
        }
    }
}
