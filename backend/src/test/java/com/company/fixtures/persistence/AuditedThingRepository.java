package com.company.fixtures.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Test-only Spring Data repository: no {@code delete*}; soft delete is a save, restore native. */
public interface AuditedThingRepository
    extends Repository<AuditedThing, UUID>, AuditedThingRestore {

  AuditedThing save(AuditedThing thing);

  AuditedThing saveAndFlush(AuditedThing thing);

  Optional<AuditedThing> findById(UUID id);

  List<AuditedThing> findAll();
}
