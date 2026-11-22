package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.Pet;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** A pet with its jobs, newest first (createdAt desc), as PetService reads them. */
public record PetWithJobs(Pet pet, List<Job> jobs) {

    /**
     * The cover: the job the user chose while it is still a COMPLETED job of this pet, otherwise the newest COMPLETED
     * job, otherwise none (docs/api/openapi.yaml PetResponse.coverJobId).
     */
    public Optional<Job> cover() {
        String chosen = pet.getCoverJobId();
        if (chosen != null) {
            Optional<Job> stillValid = jobs.stream()
                    .filter(job -> job.getId().equals(chosen) && job.getStatus() == JobStatus.COMPLETED)
                    .findFirst();
            if (stillValid.isPresent()) {
                return stillValid;
            }
        }
        return jobs.stream().filter(job -> job.getStatus() == JobStatus.COMPLETED).findFirst();
    }

    /** createdAt of the newest job, null without jobs. */
    public Instant latestJobAt() {
        return jobs.isEmpty() ? null : jobs.get(0).getCreatedAt();
    }
}
