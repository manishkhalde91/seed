package com.company.fixtures.archunit.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Violation: a class using Jackson 2 databind. */
public class Jackson2Mapper {

  private final ObjectMapper mapper = new ObjectMapper();

  public ObjectMapper mapper() {
    return mapper;
  }
}
