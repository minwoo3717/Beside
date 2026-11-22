package com.example.mockbackend.service;

/**
 * Where GET /api/v1/admin/overview takes its {@code inference} object from (the GPU / inference service state).
 * InferenceHealthMonitor (profile real) implements it with its latest reading; mock has no bean, AdminService asks
 * through an {@code ObjectProvider} and answers {@code inference: null} ("GPU 상태: 정보 없음" on the dashboard).
 */
@FunctionalInterface
public interface InferenceStatusSource {

    /** The current inference status, serialized as JSON as it is; null when unknown. Must not block (called every 5 s). */
    Object inferenceStatus();
}
