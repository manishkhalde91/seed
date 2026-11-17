package com.company.seed.common.auditing;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.persistence.AuditedThing;
import com.company.fixtures.persistence.PersistenceTest;
import com.company.fixtures.persistence.PersistenceTestSupport;
import com.company.fixtures.security.TestJwt;
import com.company.seed.common.actor.SystemActor;
import com.company.seed.security.Roles;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/** Audit columns filled from the clock and the current actor (data-and-migrations.md §4). */
@PersistenceTest
class AuditingPersistenceIT extends PersistenceTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private UUID createThroughTheApi(String token, String path, String name) {
    ResponseEntity<String> response =
        client(token).post().uri(path + "?name=" + name).retrieve().toEntity(String.class);
    assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
    String data = JSON.readTree(response.getBody()).path("data").asString();
    return UUID.fromString(data);
  }

  @Test
  void createInsideASystemScopeIsAuditedAsThatScope() {
    UUID id = SystemActor.call("system:test", () -> create(unique("scoped"))).getId();

    AuditedThing stored = repository.findById(id).orElseThrow();
    assertThat(stored.getCreatedBy()).isEqualTo("system:test");
    assertThat(stored.getUpdatedBy()).isEqualTo("system:test");
    assertThat(stored.getCreatedAt()).isEqualTo(START);
    assertThat(stored.getUpdatedAt()).isEqualTo(START);
    assertThat(stored.getVersion()).isZero();
    assertThat(timestamp(id, "created_at")).isEqualTo(START);
    assertThat(timestamp(id, "updated_at")).isEqualTo(START);
  }

  @Test
  void createOutsideAnyScopeIsAuditedAsAnonymous() {
    UUID id = create(unique("anonymous")).getId();

    AuditedThing stored = repository.findById(id).orElseThrow();
    assertThat(stored.getCreatedBy()).isEqualTo("anonymous");
    assertThat(stored.getUpdatedBy()).isEqualTo("anonymous");
    assertThat(stored.getCreatedAt()).isEqualTo(START);
    assertThat(stored.getUpdatedAt()).isEqualTo(START);
  }

  @Test
  void aWriteInAnAuthenticatedRequestIsAuditedAsTheTokensUser() {
    String token = TestJwt.builder().subject("u-1").roles(Roles.USER).sign();

    UUID id = createThroughTheApi(token, "/probe-db/things", unique("user"));

    AuditedThing stored = repository.findById(id).orElseThrow();
    assertThat(stored.getCreatedBy()).isEqualTo("user:u-1");
    assertThat(stored.getUpdatedBy()).isEqualTo("user:u-1");
  }

  @Test
  void aSystemScopeInsideAnAuthenticatedRequestWinsOverTheUser() {
    String token = TestJwt.builder().subject("u-1").roles(Roles.USER).sign();

    UUID id = createThroughTheApi(token, "/probe-db/things/system-scoped", unique("system"));

    AuditedThing stored = repository.findById(id).orElseThrow();
    assertThat(stored.getCreatedBy()).isEqualTo("system:x");
    assertThat(stored.getUpdatedBy()).isEqualTo("system:x");
  }

  @Test
  void updateKeepsTheCreationAuditAndMovesTheUpdateAudit() {
    UUID id = SystemActor.call("system:test", () -> create(unique("updated"))).getId();
    clock.advance(Duration.ofMinutes(1));

    AuditedThing loaded = repository.findById(id).orElseThrow();
    loaded.rename(loaded.getName() + "-renamed");
    repository.saveAndFlush(loaded);

    AuditedThing stored = repository.findById(id).orElseThrow();
    assertThat(stored.getCreatedBy()).isEqualTo("system:test");
    assertThat(stored.getCreatedAt()).isEqualTo(START);
    assertThat(stored.getUpdatedBy()).isEqualTo("anonymous");
    assertThat(stored.getUpdatedAt()).isEqualTo(START.plus(Duration.ofMinutes(1)));
    assertThat(stored.getVersion()).isEqualTo(1);
    assertThat(timestamp(id, "updated_at")).isEqualTo(START.plus(Duration.ofMinutes(1)));
  }
}
