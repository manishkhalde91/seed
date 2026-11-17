package com.company.seed.feature.sample.domain;

import com.company.seed.common.api.PageQuery;
import com.company.seed.common.api.PageResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DEMO ONLY. The stored sample records, as if they were an in-memory collection
 * (backend-architecture.md §5). Deleted records are invisible to every read. There is no delete: a
 * record is soft-deleted with {@link SampleRecord#markDeleted} and saved.
 */
public interface SampleRecordRepository {

  /**
   * Inserts a new record or stores the changes of a loaded one, and returns it as stored (audit
   * fields and version filled in).
   */
  SampleRecord save(SampleRecord sampleRecord);

  /** The active record with this ID; empty when it is missing or deleted. */
  Optional<SampleRecord> findById(UUID id);

  /** One page of the active records, in the query's order (API field names). */
  PageResult<SampleRecord> findPage(PageQuery query);

  /** Every active record, oldest first (the export reads them all). */
  List<SampleRecord> findAllActive();

  /**
   * Clears the deletion of a deleted record, auditing it as an update.
   *
   * @return {@code false} when no deleted record has this ID (missing or active)
   */
  boolean restore(UUID id);
}
