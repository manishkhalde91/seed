package com.company.seed.common.validation;

import jakarta.validation.groups.Default;

/**
 * Validation group for rules that apply only when updating ({@code @Validated(OnUpdate.class)}).
 * Extends {@link Default}, so the ungrouped constraints are checked as well.
 */
public interface OnUpdate extends Default {}
