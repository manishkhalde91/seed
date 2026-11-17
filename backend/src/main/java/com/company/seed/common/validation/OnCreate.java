package com.company.seed.common.validation;

import jakarta.validation.groups.Default;

/**
 * Validation group for rules that apply only when creating ({@code @Validated(OnCreate.class)}).
 * Extends {@link Default}, so the ungrouped constraints are checked as well.
 */
public interface OnCreate extends Default {}
