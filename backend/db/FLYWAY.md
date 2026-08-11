# Flyway migration policy

Flyway is the only owner of application schema changes. Hibernate runs with
`ddl-auto: validate` and must not create or alter tables.

## Database states

### New empty database

Start the application against the empty schema. Flyway creates
`flyway_schema_history`, executes `V1` through the latest migration in order,
and Hibernate validates the resulting schema.

Do not enable `baseline-on-migrate` for an empty database. Doing so can skip
the migrations needed to create the schema.

### Existing schema that was manually updated through V9

The local `kbo_predictor` schema was backed up and registered once with the
official Flyway baseline operation at version 9. Its history contains one
`BASELINE` row with description `existing-schema-through-v9`.

Normal application configuration deliberately keeps
`baseline-on-migrate: false`. Another unmanaged, non-empty database must first
be compared with every migration and backed up. Only when it is proven to match
the V9 schema may an operator run the same one-time Flyway baseline at version
9. Never insert or edit rows in `flyway_schema_history` manually.

## Adding a schema change

1. Do not edit an already applied migration.
2. Add the next versioned file under `src/main/resources/db/migration`, for
   example `V10__add_example_column.sql`.
3. Make the SQL preserve existing rows. Add nullable columns first when a safe
   backfill is required, then enforce constraints in the same or a later
   migration.
4. Run the full backend test suite.
5. Verify an upgrade of a database at the previous version and a bootstrap of
   an empty database.
6. Restart once more and confirm Flyway reports `No migration necessary`.

`spring.flyway.clean-disabled` stays enabled. Application startup must never
use `clean`, `create`, or `create-drop` against an existing database.
