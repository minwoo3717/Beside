package com.example.mockbackend.api.v1.dto;

import java.util.List;

/** GET /api/v1/admin/jobs: most recent jobs first (createdAt desc). */
public record AdminJobList(List<AdminJobSummary> items) {
}
