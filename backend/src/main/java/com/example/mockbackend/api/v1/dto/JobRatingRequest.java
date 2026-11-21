package com.example.mockbackend.api.v1.dto;

import com.example.mockbackend.domain.RatingScores;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of PUT /api/v1/jobs/{jobId}/ratings/{rater}. Read with {@link #from(JsonNode)} instead of Jackson binding:
 * binding into Integer would quietly turn 3.5 into 3 and "3" into 3, and a rating must be exactly the score the rater
 * picked. Unknown fields are ignored like everywhere in the contract.
 */
@Schema(description = "PUT /api/v1/jobs/{jobId}/ratings/{rater} 본문. 6항목은 docs/METRICS.md §4, 각 1~5 정수.")
public record JobRatingRequest(
        @Schema(minimum = "1", maximum = "5", description = "얼굴 형태", requiredMode = Schema.RequiredMode.REQUIRED) Integer faceShape,
        @Schema(minimum = "1", maximum = "5", description = "귀 모양/위치", requiredMode = Schema.RequiredMode.REQUIRED) Integer ears,
        @Schema(minimum = "1", maximum = "5", description = "체형 비율", requiredMode = Schema.RequiredMode.REQUIRED) Integer bodyProportion,
        @Schema(minimum = "1", maximum = "5", description = "털 색상", requiredMode = Schema.RequiredMode.REQUIRED) Integer furColor,
        @Schema(minimum = "1", maximum = "5", description = "무늬 재현", requiredMode = Schema.RequiredMode.REQUIRED) Integer pattern,
        @Schema(minimum = "1", maximum = "5", description = "텍스처 번짐/seam (5 = 결함 없음)", requiredMode = Schema.RequiredMode.REQUIRED) Integer textureArtifacts,
        @Schema(nullable = true, maxLength = MAX_COMMENT_LENGTH, description = "선택. 500자 이하. 빈 문자열은 null 로 저장한다.") String comment) {
    /** Unicode code points. */
    public static final int MAX_COMMENT_LENGTH = 500;

    /**
     * Strict reading: a JSON object whose six scores are integer literals 1~5 (not 3.0, "3" or true) and whose comment
     * is a string of at most 500 code points or null/absent. An empty or blank comment becomes null.
     * @throws ApiException 400 INVALID_REQUEST naming the first offending field
     */
    public static JobRatingRequest from(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw invalid("The body must be a JSON object with " + String.join(", ", RatingScores.NAMES));
        }
        int[] scores = new int[RatingScores.NAMES.size()];
        for (int i = 0; i < scores.length; i++) {
            String name = RatingScores.NAMES.get(i);
            JsonNode value = body.get(name);
            if (value == null || value.isNull()) {
                throw invalid(name + " is required");
            }
            if (!value.isIntegralNumber() || !value.canConvertToInt() || !RatingScores.inRange(value.intValue())) {
                throw invalid(name + " must be an integer from " + RatingScores.MIN + " to " + RatingScores.MAX + ", got " + value);
            }
            scores[i] = value.intValue();
        }
        JsonNode commentNode = body.get("comment");
        String comment = null;
        if (commentNode != null && !commentNode.isNull()) {
            if (!commentNode.isTextual()) {
                throw invalid("comment must be a string or null");
            }
            String text = commentNode.textValue().strip();
            if (text.codePointCount(0, text.length()) > MAX_COMMENT_LENGTH) {
                throw invalid("comment must be at most " + MAX_COMMENT_LENGTH + " characters");
            }
            comment = text.isEmpty() ? null : text;
        }
        return new JobRatingRequest(scores[0], scores[1], scores[2], scores[3], scores[4], scores[5], comment);
    }

    public RatingScores scores() {
        return new RatingScores(faceShape, ears, bodyProportion, furColor, pattern, textureArtifacts);
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest(ErrorCode.INVALID_REQUEST, message);
    }
}
