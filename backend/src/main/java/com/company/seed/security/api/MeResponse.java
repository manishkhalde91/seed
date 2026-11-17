package com.company.seed.security.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The caller of {@code GET /api/v1/me}.
 *
 * @param subject the token's {@code sub}
 * @param name the token's {@code name}, else its {@code preferred_username}, else the subject
 * @param roles the caller's effective roles, including those implied by the role hierarchy ({@code
 *     ADMIN} brings {@code USER}), without the {@code ROLE_} prefix, sorted
 */
@Schema(requiredProperties = {"subject", "name", "roles"})
public record MeResponse(String subject, String name, List<String> roles) {}
