package com.example.mockbackend.repository;

import com.example.mockbackend.domain.Pet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Library pets (table "pets"). */
public interface PetRepository extends JpaRepository<Pet, String> {

    /** In the order they were added (listPets). */
    @Query("select p from Pet p order by p.createdAt asc, p.id asc")
    List<Pet> findAllOldestFirst();

    /** Forgets the chosen cover {@code jobId} wherever it was chosen (the job is being deleted). */
    @Modifying
    @Transactional
    @Query("update Pet p set p.coverJobId = null where p.coverJobId = :jobId")
    int clearCover(@Param("jobId") String jobId);

    /** Forgets {@code jobId} as the chosen cover of {@code petId} (the job left that pet). */
    @Modifying
    @Transactional
    @Query("update Pet p set p.coverJobId = null where p.id = :petId and p.coverJobId = :jobId")
    int clearCover(@Param("petId") String petId, @Param("jobId") String jobId);
}
