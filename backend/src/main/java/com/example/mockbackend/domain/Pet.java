package com.example.mockbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A pet in the web library (table "pets", docs/api/openapi.yaml tag pets). Jobs point at it with Job.petId; a pet does
 * not list its jobs itself. {@code coverJobId} is the job the user chose as the cover; the API answers it only while it
 * is still a COMPLETED job of this pet and otherwise falls back to the newest COMPLETED one (PetService).
 * Only PetService writes pets, so saving a loaded copy is safe here (unlike Job, see Job.petId).
 * New columns must be nullable (boxed) types: ddl-auto=update cannot add a NOT NULL column to a table that has rows.
 */
@Entity
@Table(name = "pets")
@Getter
@Setter
@NoArgsConstructor
public class Pet {
    public static final int MAX_NAME_LENGTH = 40;
    public static final int MAX_NOTE_LENGTH = 500;

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    /** Plain VARCHAR without a CHECK constraint (PetSpeciesColumnConverter). */
    @Convert(converter = PetSpeciesColumnConverter.class)
    @Column(nullable = false, length = 16)
    private PetSpecies species;

    @Column(length = MAX_NOTE_LENGTH)
    private String note;

    /** The cover the user chose (PATCH coverJobId); null = automatic. */
    @Column(length = 36)
    private String coverJobId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
