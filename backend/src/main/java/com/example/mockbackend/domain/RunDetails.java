package com.example.mockbackend.domain;

/**
 * What one run produced beyond its status: the inference numbers of docs/METRICS.md §2 as the /infer answer
 * reported them, whether that answer was the passthrough sample, the model version it named, the size of the
 * base GLB the server stored, and how the first photo was preprocessed for it (2026-11). JobFinisher writes them into
 * the job_runs row and the events.jsonl line next to the server timings. Every field is nullable; {@link #NONE} is for
 * runs without an /infer answer and without preprocessing (mock worker).
 */
public record RunDetails(Long inferMs, Long gpuPeakMB, Long modelParams, Long outputVertices, Long outputTriangles,
                         Long convertMs, Long glbBytes, Boolean passthrough, String modelVersion,
                         Long preprocessMs, String preprocessResult, String preprocessVersion) {

    public static final RunDetails NONE = new RunDetails(null, null, null, null, null, null, null, null, null);

    /** The /infer facts alone, without preprocessing (as before 2026-11). */
    public RunDetails(Long inferMs, Long gpuPeakMB, Long modelParams, Long outputVertices, Long outputTriangles,
                      Long convertMs, Long glbBytes, Boolean passthrough, String modelVersion) {
        this(inferMs, gpuPeakMB, modelParams, outputVertices, outputTriangles, convertMs, glbBytes, passthrough,
                modelVersion, null, null, null);
    }

    /** These details plus how the first photo was preprocessed (METRICS §1: preprocessMs, -Result, -Version). */
    public RunDetails withPreprocess(Long ms, String result, String version) {
        return new RunDetails(inferMs, gpuPeakMB, modelParams, outputVertices, outputTriangles, convertMs, glbBytes,
                passthrough, modelVersion, ms, result, version);
    }
}
