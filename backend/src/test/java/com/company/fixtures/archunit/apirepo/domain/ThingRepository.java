package com.company.fixtures.archunit.apirepo.domain;

import java.util.List;

public interface ThingRepository {

  List<String> findAll();
}
