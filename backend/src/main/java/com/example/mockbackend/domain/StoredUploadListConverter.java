package com.example.mockbackend.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * Job.uploads ↔ one JSON text column. A job has at most 10 photos and they are always read with the job,
 * so a JSON column is simpler than a child table and needs no lazy loading.
 */
@Converter
public class StoredUploadListConverter implements AttributeConverter<List<StoredUpload>, String> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<StoredUpload>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<StoredUpload> uploads) {
        try {
            return JSON.writeValueAsString(uploads == null ? List.of() : uploads);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize job uploads", e);
        }
    }

    @Override
    public List<StoredUpload> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(JSON.readValue(column, TYPE));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read job uploads column", e);
        }
    }
}
