package com.company.fixtures.archunit.envelope.lookalike;

/** A look-alike envelope outside common.api: the rule must not accept it. */
public record ApiResponse<T>(boolean success, T data) {}
