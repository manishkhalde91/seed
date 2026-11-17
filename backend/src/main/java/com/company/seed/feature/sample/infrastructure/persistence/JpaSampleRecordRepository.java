package com.company.seed.feature.sample.infrastructure.persistence;

import com.company.seed.common.api.PageQuery;
import com.company.seed.common.api.PageResult;
import com.company.seed.feature.sample.domain.SampleRecord;
import com.company.seed.feature.sample.domain.SampleRecordRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

/**
 * DEMO ONLY. {@link SampleRecordRepository} on JPA. Writes are flushed at once, so a duplicate name
 * or a stale version fails inside the use case's call, translated to Spring's {@code
 * DataIntegrityViolationException} / {@code OptimisticLockingFailureException}. Restore is a native
 * update, because {@code @SQLRestriction} hides a deleted row from JPQL.
 */
@Repository
class JpaSampleRecordRepository implements SampleRecordRepository {

  /** API sort field → entity property. */
  private static final Map<String, String> SORT_PROPERTIES =
      Map.of("name", "name", "createdAt", "createdAt");

  /** Only a deleted row: an active one keeps its version. */
  private static final String RESTORE =
      """
      UPDATE sample.sample_record
         SET deleted_at = NULL, deleted_by = NULL,
             updated_at = :updatedAt, updated_by = :updatedBy, version = version + 1
       WHERE id = :id AND deleted_at IS NOT NULL
      """;

  private final SampleRecordSpringDataRepository records;
  private final EntityManager entityManager;
  private final DateTimeProvider auditTime;
  private final AuditorAware<String> auditor;

  /**
   * @param auditTime the time JPA auditing writes ({@code common.auditing}), so a restore is
   *     audited exactly like an update
   * @param auditor the actor JPA auditing writes
   */
  JpaSampleRecordRepository(
      SampleRecordSpringDataRepository records,
      EntityManager entityManager,
      DateTimeProvider auditTime,
      AuditorAware<String> auditor) {
    this.records = records;
    this.entityManager = entityManager;
    this.auditTime = auditTime;
    this.auditor = auditor;
  }

  @Override
  public SampleRecord save(SampleRecord sampleRecord) {
    if (sampleRecord.version() == null) {
      return SampleRecordPersistenceMapper.toDomain(
          records.saveAndFlush(SampleRecordPersistenceMapper.toNewEntity(sampleRecord)));
    }
    SampleRecordJpaEntity entity =
        records
            .findById(sampleRecord.id())
            .orElseThrow(
                () ->
                    new OptimisticLockingFailureException(
                        "Sample record " + sampleRecord.id() + " is no longer active"));
    SampleRecordPersistenceMapper.apply(sampleRecord, entity);
    return SampleRecordPersistenceMapper.toDomain(records.saveAndFlush(entity));
  }

  @Override
  public Optional<SampleRecord> findById(UUID id) {
    return records.findById(id).map(SampleRecordPersistenceMapper::toDomain);
  }

  @Override
  public PageResult<SampleRecord> findPage(PageQuery query) {
    Page<SampleRecordJpaEntity> page =
        records.findAll(PageRequest.of(query.page(), query.size(), sort(query)));
    return new PageResult<>(
        page.getContent().stream().map(SampleRecordPersistenceMapper::toDomain).toList(),
        query.page(),
        query.size(),
        page.getTotalElements());
  }

  @Override
  public List<SampleRecord> findAllActive() {
    return records.findAllByOrderByCreatedAtAscIdAsc().stream()
        .map(SampleRecordPersistenceMapper::toDomain)
        .toList();
  }

  @Override
  public boolean restore(UUID id) {
    entityManager.flush();
    int restored =
        entityManager
            .createNativeQuery(RESTORE)
            .setParameter("updatedAt", Instant.from(auditTime.getNow().orElseThrow()))
            .setParameter("updatedBy", auditor.getCurrentAuditor().orElseThrow())
            .setParameter("id", id)
            .executeUpdate();
    // A managed instance of this record (deleted earlier in the transaction) would keep the
    // pre-restore state; the next read loads the row again.
    entityManager.detach(entityManager.getReference(SampleRecordJpaEntity.class, id));
    return restored == 1;
  }

  /** The query's orders, then the ID, so that equal values page deterministically. */
  private static Sort sort(PageQuery query) {
    List<Sort.Order> orders = new ArrayList<>();
    for (PageQuery.Order order : query.sort()) {
      String property = SORT_PROPERTIES.get(order.field());
      if (property == null) {
        throw new IllegalArgumentException("Not a sortable sample record field: " + order.field());
      }
      orders.add(
          order.direction() == PageQuery.Direction.DESC
              ? Sort.Order.desc(property)
              : Sort.Order.asc(property));
    }
    orders.add(Sort.Order.asc("id"));
    return Sort.by(orders);
  }
}
