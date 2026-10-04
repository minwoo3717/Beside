package com.example.mockbackend.domain;

/**
 * GLB asset variant (docs/asset/GLB_SPEC.md). Constants are lower-case on purpose: the wire value
 * ({@code ?variant=base}, {@code AssetInfo.variant}) is the constant name, so Jackson, Spring's
 * request-parameter conversion and springdoc all agree without extra mapping.
 */
public enum AssetVariant {
    /** Body mesh only (single node "base"). */
    base,
    /** Body plus hair mesh (nodes "base" and "hair"). Not produced by the mock worker. */
    hair
}
