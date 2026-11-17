package com.company.fixtures.archunit.appjpa.application;

import jakarta.persistence.EntityManager;

/** Violation: an application class importing jakarta.persistence. */
public class EntityManagerService {

  private final EntityManager entityManager;

  public EntityManagerService(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public EntityManager entityManager() {
    return entityManager;
  }
}
