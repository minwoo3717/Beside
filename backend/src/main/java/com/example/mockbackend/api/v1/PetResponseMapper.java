package com.example.mockbackend.api.v1;

import com.example.mockbackend.api.v1.dto.JobResponse;
import com.example.mockbackend.api.v1.dto.PetDetailResponse;
import com.example.mockbackend.api.v1.dto.PetResponse;
import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.Pet;
import com.example.mockbackend.repository.JobRunRepository;
import com.example.mockbackend.service.PetWithJobs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pet (internal, with its jobs) → PetResponse / PetDetailResponse (docs/api/openapi.yaml tag pets). */
@Component
@RequiredArgsConstructor
public class PetResponseMapper {
    private final JobResponseMapper jobMapper;
    private final JobRunRepository jobRuns;

    public PetResponse toResponse(PetWithJobs value) {
        Pet pet = value.pet();
        String coverJobId = value.cover().map(Job::getId).orElse(null);
        return new PetResponse(pet.getId(), pet.getName(), pet.getSpecies(), pet.getNote(), pet.getCreatedAt(),
                pet.getUpdatedAt(), value.jobs().size(), coverJobId,
                coverJobId == null ? null : jobMapper.thumbnailUrl(coverJobId), value.latestJobAt());
    }

    public PetDetailResponse toDetail(PetWithJobs value) {
        List<JobResponse> jobs = value.jobs().stream().map(jobMapper::toResponse).toList();
        return PetDetailResponse.of(toResponse(value), jobs, modelVersions(value.jobs()));
    }

    /** jobId → modelVersion of the job's last finished run (largest attempt); jobs without one are left out. */
    private Map<String, String> modelVersions(List<Job> jobs) {
        if (jobs.isEmpty()) {
            return Map.of();
        }
        Map<String, JobRun> last = new HashMap<>();
        for (JobRun run : jobRuns.findByJobIdIn(jobs.stream().map(Job::getId).toList())) {
            last.merge(run.getJobId(), run, (a, b) -> a.getAttempt() >= b.getAttempt() ? a : b);
        }
        Map<String, String> versions = new LinkedHashMap<>();
        for (Job job : jobs) {
            JobRun run = last.get(job.getId());
            if (run != null && run.getModelVersion() != null && !run.getModelVersion().isBlank()) {
                versions.put(job.getId(), run.getModelVersion());
            }
        }
        return versions;
    }
}
