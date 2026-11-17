package com.company.fixtures.persistence;

import com.company.seed.common.auditing.SoftDeletableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.hibernate.annotations.SQLRestriction;

/**
 * Test-only soft-deletable entity: a partial unique index on {@code name}, a check constraint on
 * {@code quantity} and a Bean Validation constraint on {@code name}. The ID is assigned by the
 * application; Spring Data tells a new instance by its {@code null} version.
 */
@Entity
@Table(schema = "fixture", name = "audited_thing")
@SQLRestriction("deleted_at IS NULL")
public class AuditedThing extends SoftDeletableEntity {

  @Id private UUID id;

  @NotBlank private String name;

  private int quantity;

  protected AuditedThing() {}

  public AuditedThing(UUID id, String name, int quantity) {
    this.id = id;
    this.name = name;
    this.quantity = quantity;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public int getQuantity() {
    return quantity;
  }

  public void rename(String name) {
    this.name = name;
  }
}
