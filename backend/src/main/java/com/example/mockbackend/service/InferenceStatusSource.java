package com.example.mockbackend.service;

/**
 * Where GET /api/v1/admin/overview takes its {@code inference} object from (the GPU / inference service state).
 * No bean implements it yet, so AdminService answers {@code inference: null} and the dashboard shows
 * "GPU 상태: 정보 없음". The GPU health monitor of PLAN S3 (InferenceHealthMonitor, profile real) is meant to implement
 * it at merge time and return the same object it puts into HealthResponse.inference; AdminService asks for it through
 * an {@code ObjectProvider}, so mock keeps working without one.
 */
@FunctionalInterface
public interface InferenceStatusSource {

    /** The current inference status, serialized as JSON as it is; null when unknown. Must not block (called every 5 s). */
    Object inferenceStatus();
}
