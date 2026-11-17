package com.company.fixtures.archunit.repolocation.feature.thing.application;

/** Violation: a feature repository interface outside feature/<module>/domain. */
public interface ThingRepository {

  void save(String thing);
}
