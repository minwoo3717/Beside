package com.example.mockbackend.domain;

import java.util.List;

/**
 * The six human quality scores of docs/METRICS.md §4, each 1 (nothing alike) ~ 5 (that very animal; for
 * textureArtifacts 5 = no defects). Names and order follow METRICS and change only through the contract meeting.
 */
public record RatingScores(int faceShape, int ears, int bodyProportion, int furColor, int pattern, int textureArtifacts) {
    public static final int MIN = 1;
    public static final int MAX = 5;
    /** docs/METRICS.md §4 names in order (request body, CSV columns, averages). */
    public static final List<String> NAMES = List.of(
            "faceShape", "ears", "bodyProportion", "furColor", "pattern", "textureArtifacts");

    public RatingScores {
        List<Integer> values = List.of(faceShape, ears, bodyProportion, furColor, pattern, textureArtifacts);
        for (int i = 0; i < values.size(); i++) {
            if (!inRange(values.get(i))) {
                throw new IllegalArgumentException(NAMES.get(i) + " must be between " + MIN + " and " + MAX);
            }
        }
    }

    public static boolean inRange(long score) {
        return score >= MIN && score <= MAX;
    }

    /** The scores in NAMES order. */
    public List<Integer> values() {
        return List.of(faceShape, ears, bodyProportion, furColor, pattern, textureArtifacts);
    }
}
