package com.example.mockbackend.domain;

/**
 * GLB asset variant (docs/asset/GLB_SPEC.md). Constants are lower-case on purpose: the wire value
 * ({@code ?variant=base}, {@code AssetInfo.variant}) is the constant name, so Jackson, Spring's
 * request-parameter conversion and springdoc all agree without extra mapping.
 * <p>
 * Only {@code base} since 2026-11-21: the {@code hair} variant left API v1 (REVIEW_CHECKLIST #4, nothing produced
 * it). Any other {@code ?variant=} value, {@code hair} included, fails the parameter conversion and
 * GlobalExceptionHandler answers 400 INVALID_REQUEST, whatever the job's state.
 */
public enum AssetVariant {
    /** The generated GLB of the job. */
    base
}
