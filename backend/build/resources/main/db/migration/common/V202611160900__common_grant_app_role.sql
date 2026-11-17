-- Role grants for the application user (data-and-migrations.md §10). Flyway creates the common
-- schema itself (spring.flyway.schemas), owned by the role it runs as: DB_OWNER_USERNAME.
-- ${app_role} is the application user (DB_USERNAME); ${flyway:user} is the migrating owner;
-- ${flyway:table} is the history table (spring.flyway.table).

-- The application may use the common schema, but never create, alter or drop anything in it.
GRANT USAGE ON SCHEMA common TO "${app_role}";

-- Every table and sequence the owner creates from now on, in any schema, is readable and writable
-- by the application. Each module migration still grants USAGE on its own schema.
ALTER DEFAULT PRIVILEGES FOR ROLE "${flyway:user}"
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES FOR ROLE "${flyway:user}"
  GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

-- The history table predates these defaults; the application only reads it (readiness check).
GRANT SELECT ON common."${flyway:table}" TO "${app_role}";
