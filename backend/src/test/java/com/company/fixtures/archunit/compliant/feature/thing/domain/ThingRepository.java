package com.company.fixtures.archunit.compliant.feature.thing.domain;

import com.company.fixtures.archunit.compliant.common.api.PageResult;

/** Repository interface in feature/<module>/domain, using the shared common.api type. */
public interface ThingRepository {

  PageResult<Thing> findPage(int page, int size);
}
