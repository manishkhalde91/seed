package com.company.fixtures.archunit.compliant.feature.thing.api;

/** A request record without audit fields; the server fills them. */
public record CreateThingRequest(String name, String description) {}
