package com.company.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import({TestDatabase.class, TestSecurity.class})
class SeedApplicationTests {

  @Test
  void contextLoads(ApplicationContext context) {
    assertThat(context.getBean(SeedApplication.class)).isNotNull();
  }
}
