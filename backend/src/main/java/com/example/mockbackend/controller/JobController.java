package com.example.mockbackend.controller;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.service.JobServiceImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * v0 REST controller: /api/jobs. FROZEN — used by the browser Mock UI (static/app.js) and its tests.
 * Do not change behaviour here; new work goes to api.v1.JobV1Controller. Removal is decided in the
 * contract meeting after Unity has moved to /api/v1 (see docs/PLAN.md).
 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@Validated
@SuppressWarnings("deprecation")
public class JobController {
    private final JobServiceImpl jobService;
    private final Logger log = LoggerFactory.getLogger(JobController.class);

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> createJob(@RequestPart("photos") List<MultipartFile> photos) throws IOException {
        Job job = jobService.submitJob(photos);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Location", "/api/jobs/" + job.getId());
        return new ResponseEntity<>(headers, HttpStatus.ACCEPTED);
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<LegacyJobResponse> getJob(@PathVariable String jobId) {
        Job job = jobService.getJob(jobId);
        if (job == null) {
            log.warn("jobId={} not found", jobId);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(LegacyJobResponse.from(job));
    }

    @GetMapping("/{jobId}/result")
    public ResponseEntity<FileSystemResource> downloadResult(@PathVariable String jobId) {
        Job job = jobService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        if (job.getStatus() != JobStatus.COMPLETED) {
            // v0 keeps 400 here; v1 (/api/v1/jobs/{id}/asset) answers 409 per contract.
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        File file = new File(job.getResultPath());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        FileSystemResource resource = new FileSystemResource(file);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=sample-dog.glb");
        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(file.length())
                .contentType(MediaType.parseMediaType("model/gltf-binary"))
                .body(resource);
    }
}
