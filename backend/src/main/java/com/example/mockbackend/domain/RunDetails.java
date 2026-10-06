package com.example.mockbackend.domain;

/**
 * What one run produced beyond its status: the inference numbers of docs/METRICS.md §2 as the /infer answer
 * reported them, whether that answer was the passthrough sample, the model version it named, and the size of the
 * base GLB the server stored. JobFinisher writes them into the job_runs row and the events.jsonl line next to the
 * server timings. Every field is nullable; {@link #NONE} is for runs without an /infer answer (mock worker, failures).
 */
public record RunDetails(Long inferMs, Long gpuPeakMB, Long modelParams, Long outputVertices, Long outputTriangles,
                         Long convertMs, Long glbBytes, Boolean passthrough, String modelVersion) {

    public static final RunDetails NONE = new RunDetails(null, null, null, null, null, null, null, null, null);
}
