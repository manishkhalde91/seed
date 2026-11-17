package com.company.fixtures.persistence;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A persistence IT: the running application on its own throwaway database ({@code seed_fixture}),
 * migrated with the main and the fixture migrations ({@link PersistenceFixtures} appends the
 * fixture location and schema to the main ones), with {@link PersistenceFixtures} wired in. Every
 * class carrying it shares one application context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = TestDatabase.DATABASE_PROPERTY + "=seed_fixture")
@Import({TestDatabase.class, TestSecurity.class, PersistenceFixtures.class})
public @interface PersistenceTest {}
