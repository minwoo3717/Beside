package com.example.mockbackend.service;

import com.example.mockbackend.api.v1.dto.CreatePetRequest;
import com.example.mockbackend.api.v1.dto.JobPetRequest;
import com.example.mockbackend.api.v1.dto.UpdatePetRequest;
import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.Pet;
import com.example.mockbackend.domain.PetSpecies;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.PetRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * The web library (docs/api/openapi.yaml tag pets): pets, which jobs belong to which pet, and the cover rule.
 * A missing resource in the path is 404 (PET_NOT_FOUND, JOB_NOT_FOUND); a pet or job named in a body that does not exist
 * or breaks a rule is 400 INVALID_REQUEST. Job.petId changes only through JobRepository.updatePetId/unassignPet, and
 * only here: every job is created unassigned (the public POST /api/v1/jobs takes no pet).
 * Changing a pet's jobs or cover and deleting a pet are synchronized on this service: a job put into a pet while that
 * pet is being deleted would otherwise keep the deleted pet's id and show up nowhere (neither under a pet nor as
 * unassigned). One server per database file (CLAUDE.md), so a lock inside this process is enough.
 */
@Service
@RequiredArgsConstructor
public class PetService {
    private final PetRepository petRepository;
    private final JobRepository jobRepository;
    private final JobService jobService;
    private final JobDeletionService jobDeletion;
    private final AtomicReference<Instant> lastStamp = new AtomicReference<>(Instant.EPOCH);
    private final Logger log = LoggerFactory.getLogger(PetService.class);

    /** @throws ApiException 400 INVALID_REQUEST */
    public PetWithJobs create(CreatePetRequest request) {
        if (request == null) {
            throw invalid("A JSON body with a name is required");
        }
        Instant now = now();
        Pet pet = new Pet();
        pet.setId(UUID.randomUUID().toString());
        pet.setName(name(request.name()));
        pet.setSpecies(request.species() == null ? PetSpecies.DOG : species(request.species()));
        pet.setNote(note(request.note()));
        pet.setCreatedAt(now);
        pet.setUpdatedAt(now);
        petRepository.save(pet);
        log.info("Pet created: {} species={}", pet.getId(), pet.getSpecies());
        return new PetWithJobs(pet, List.of());
    }

    /** Every pet in the order they were added, each with its jobs (newest first). */
    public List<PetWithJobs> list() {
        List<Pet> pets = petRepository.findAllOldestFirst();
        if (pets.isEmpty()) {
            return List.of();
        }
        Map<String, List<Job>> jobs = jobRepository.findByPetsNewestFirst(pets.stream().map(Pet::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(Job::getPetId, LinkedHashMap::new, Collectors.toList()));
        return pets.stream().map(pet -> new PetWithJobs(pet, jobs.getOrDefault(pet.getId(), List.of()))).toList();
    }

    /** @throws ApiException 404 PET_NOT_FOUND */
    public PetWithJobs get(String petId) {
        Pet pet = find(petId);
        return new PetWithJobs(pet, jobRepository.findByPetNewestFirst(pet.getId()));
    }

    /** Merge patch: only the fields sent change. @throws ApiException 404 PET_NOT_FOUND, 400 INVALID_REQUEST */
    public synchronized PetWithJobs update(String petId, UpdatePetRequest request) {
        Pet pet = find(petId);
        if (request == null) {
            throw invalid("A JSON object body is required");
        }
        if (request.nameSent()) {
            pet.setName(name(request.getName()));
        }
        if (request.speciesSent()) {
            pet.setSpecies(species(request.getSpecies()));
        }
        if (request.noteSent()) {
            pet.setNote(note(request.getNote()));
        }
        if (request.coverJobIdSent()) {
            pet.setCoverJobId(request.getCoverJobId() == null ? null : coverOf(pet, request.getCoverJobId()));
        }
        pet.setUpdatedAt(now());
        petRepository.save(pet);
        return get(petId);
    }

    /**
     * Without jobs: the pet's jobs go back to unassigned. With jobs: they are deleted (JobDeletionService), all or
     * nothing. @throws ApiException 404 PET_NOT_FOUND, 409 JOB_IN_PROGRESS (nothing deleted)
     */
    public synchronized void delete(String petId, boolean withJobs) {
        Pet pet = find(petId);
        if (withJobs) {
            List<Job> jobs = jobRepository.findByPetNewestFirst(pet.getId());
            jobDeletion.deleteAll(jobs);
            log.info("Pet deleted with its {} jobs: {}", jobs.size(), petId);
        } else {
            int unassigned = jobRepository.unassignPet(pet.getId());
            log.info("Pet deleted, {} jobs back to unassigned: {}", unassigned, petId);
        }
        petRepository.deleteById(pet.getId());
    }

    /**
     * Moves a job into a pet ({"petId": null} = unassigned), in any state. Leaving a pet also drops the job as that
     * pet's chosen cover. @throws ApiException 404 JOB_NOT_FOUND, 400 INVALID_REQUEST
     */
    public synchronized Job setJobPet(String jobId, JobPetRequest request) {
        Job job = jobService.getJobOrThrow(jobId);
        if (request == null || !request.petIdSent()) {
            throw invalid("The body must have a petId field (null to make the job unassigned)");
        }
        String petId = request.getPetId();
        if (petId != null && !petRepository.existsById(petId)) {
            throw invalid("Unknown petId: " + petId);
        }
        String previous = job.getPetId();
        jobRepository.updatePetId(jobId, petId);
        if (previous != null && !previous.equals(petId)) {
            petRepository.clearCover(previous, jobId);
        }
        log.info("Job {} moved from pet {} to pet {}", jobId, previous, petId);
        return jobService.getJobOrThrow(jobId);
    }

    // ------------------------------------------------------------------ checks

    /** Unknown and malformed ids alike (any string is looked up as is). */
    private Pet find(String petId) {
        return petRepository.findById(petId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, ErrorCode.PET_NOT_FOUND, "Pet not found: " + petId, null));
    }

    /** The chosen cover must be a COMPLETED job of this pet. */
    private String coverOf(Pet pet, String jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null || !pet.getId().equals(job.getPetId())) {
            throw invalid("coverJobId must be a job of this pet: " + jobId);
        }
        if (job.getStatus() != JobStatus.COMPLETED) {
            throw invalid("coverJobId must be a COMPLETED job; " + jobId + " is " + job.getStatus());
        }
        return jobId;
    }

    static String name(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > Pet.MAX_NAME_LENGTH) {
            throw invalid("name must be 1~" + Pet.MAX_NAME_LENGTH + " characters after trimming");
        }
        return name;
    }

    static PetSpecies species(String raw) {
        if (raw != null) {
            for (PetSpecies species : PetSpecies.values()) {
                if (species.name().equals(raw)) {
                    return species;
                }
            }
        }
        throw invalid("species must be one of DOG, CAT, OTHER");
    }

    static String note(String raw) {
        String note = raw == null ? "" : raw.strip();
        if (note.length() > Pet.MAX_NOTE_LENGTH) {
            throw invalid("note must be at most " + Pet.MAX_NOTE_LENGTH + " characters");
        }
        return note.isEmpty() ? null : note;
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest(ErrorCode.INVALID_REQUEST, message);
    }

    /**
     * Microsecond precision (what H2 keeps), so the value answered at creation equals the one read back later, and
     * strictly increasing within this server: on Windows Instant.now() can return the same value for milliseconds, and
     * two pets added in one tick would otherwise list in random (id) order.
     */
    private Instant now() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return lastStamp.updateAndGet(last -> now.isAfter(last) ? now : last.plus(1, ChronoUnit.MICROS));
    }
}
