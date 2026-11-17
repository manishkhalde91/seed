package com.company.fixtures.archunit.domainjpa.domain;

import jakarta.persistence.Entity;

/** Violation: a domain class importing jakarta.persistence. */
@Entity
public class JpaAnnotatedDomainObject {}
