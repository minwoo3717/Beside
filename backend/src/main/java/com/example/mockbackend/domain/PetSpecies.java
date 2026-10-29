package com.example.mockbackend.domain;

/**
 * Species of a pet in the library (docs/api/openapi.yaml PetResponse.species). Clients treat unknown values as UNKNOWN,
 * so adding a value is a contract addition. Stored as plain VARCHAR through PetSpeciesColumnConverter.
 */
public enum PetSpecies {
    DOG,
    CAT,
    OTHER
}
