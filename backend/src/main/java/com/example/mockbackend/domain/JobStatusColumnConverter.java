package com.example.mockbackend.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JobStatus ↔ VARCHAR by name. A converter instead of {@code @Enumerated(STRING)} on purpose: for @Enumerated,
 * Hibernate 6 adds {@code check (status in (...))} to the column, ddl-auto=update never alters that constraint,
 * and an existing database would then reject a JobStatus value added later.
 */
@Converter
public class JobStatusColumnConverter implements AttributeConverter<JobStatus, String> {
    @Override
    public String convertToDatabaseColumn(JobStatus status) {
        return status == null ? null : status.name();
    }

    @Override
    public JobStatus convertToEntityAttribute(String column) {
        return column == null ? null : JobStatus.valueOf(column);
    }
}
