package com.company.fixtures.web;

import com.company.seed.common.validation.OnCreate;
import com.company.seed.common.validation.OnUpdate;
import com.company.seed.common.validation.Sensitive;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request DTO exercising nested objects, list elements, groups and {@code @Sensitive}. */
public record ProbeRequest(
    @Null(groups = OnCreate.class) @NotNull(groups = OnUpdate.class) Long id,
    @NotBlank @Size(max = 100) String name,
    @Size(max = 100) String description,
    @Min(1) Integer quantity,
    @Sensitive @Size(min = 12) String secret,
    @Valid ProbeAddress address,
    @Size(max = 3) List<@Valid ProbeItem> items) {

  public record ProbeAddress(@NotBlank String city, @Sensitive @Size(min = 12) String token) {}

  public record ProbeItem(@NotBlank String name) {}
}
