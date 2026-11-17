package com.company.seed.feature.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import com.company.seed.common.actor.SystemActor;
import com.company.seed.common.api.PageQuery;
import com.company.seed.feature.sample.application.SampleRecordService;
import com.company.seed.feature.sample.domain.SampleRecord;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;

/**
 * DEMO ONLY. The sample module bootstraps on its own (backend-architecture.md §3 rule 6): only
 * {@code sample}, the shared {@code common} and {@code security} modules, and {@code config} (the
 * clock), and its use cases work end to end against PostgreSQL.
 */
@ApplicationModuleTest(extraIncludes = "config")
@Import({TestDatabase.class, TestSecurity.class})
class SampleModuleTests {

  @Autowired private SampleRecordService service;

  @Test
  void bootstrapsAloneAndRunsItsUseCases() {
    String name = "module-" + UUID.randomUUID();

    SampleRecord created =
        SystemActor.call("system:module-test", () -> service.create(name, "standalone"));

    assertThat(service.get(created.id()).name()).isEqualTo(name);
    assertThat(service.get(created.id()).createdBy()).isEqualTo("system:module-test");
    assertThat(service.list(PageQuery.of(0, 100)).items())
        .extracting(SampleRecord::id)
        .contains(created.id());
  }
}
