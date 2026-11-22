package com.example.mockbackend.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * PetSpecies ↔ VARCHAR by name, for the same reason as JobStatusColumnConverter: {@code @Enumerated} would add a CHECK
 * constraint that ddl-auto=update never changes, so a species added later could not be stored in an existing database.
 * A value this build does not know (a database written by a newer build) reads as OTHER instead of failing the list.
 */
@Converter
public class PetSpeciesColumnConverter implements AttributeConverter<PetSpecies, String> {
    @Override
    public String convertToDatabaseColumn(PetSpecies species) {
        return species == null ? null : species.name();
    }

    @Override
    public PetSpecies convertToEntityAttribute(String column) {
        if (column == null) {
            return null;
        }
        try {
            return PetSpecies.valueOf(column);
        } catch (IllegalArgumentException unknown) {
            return PetSpecies.OTHER;
        }
    }
}
