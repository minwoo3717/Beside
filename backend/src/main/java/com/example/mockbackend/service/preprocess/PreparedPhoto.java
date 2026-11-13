package com.example.mockbackend.service.preprocess;

import com.example.mockbackend.domain.RunDetails;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the cutout made of a job's first photo, for one run.
 *
 * @param path    the square PNG sent to /infer instead of the first photo; null when the original goes
 * @param name    its file name in upload mode ({original stem}-cutout.png, so the part is sent as image/png)
 * @param ms      preprocessMs: work time after the slot (null when off or busy)
 * @param result  preprocessResult: APPLIED | REUSED | FALLBACK:&lt;reason&gt; | OFF
 * @param version preprocessVersion: method, model hashes and settings (null when off)
 */
public record PreparedPhoto(Path path, String name, Long ms, String result, String version) {

    public static PreparedPhoto off() {
        return new PreparedPhoto(null, null, null, "OFF", null);
    }

    /** The photo paths for /infer: the cutout in place of the first, the rest as stored. */
    public List<Path> paths(List<Path> stored) {
        if (path == null || stored.isEmpty()) {
            return stored;
        }
        List<Path> paths = new ArrayList<>(stored);
        paths.set(0, path);
        return List.copyOf(paths);
    }

    /** The photo names for upload mode, the first replaced like {@link #paths}. */
    public List<String> names(List<String> original) {
        if (path == null || original.isEmpty()) {
            return original;
        }
        List<String> names = new ArrayList<>(original);
        names.set(0, name);
        return List.copyOf(names);
    }

    /** The run's details with these preprocessing facts (job_runs and events.jsonl). */
    public RunDetails apply(RunDetails details) {
        return details.withPreprocess(ms, result, version);
    }

    /** {original name without extension}-cutout.png */
    public static String cutoutName(String originalName) {
        String name = originalName == null || originalName.isBlank() ? "photo" : originalName;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem + "-cutout.png";
    }
}
