# Database v1

`com.nitpicker.data.db.AppDatabase/1.json` is the unmodified Room/KSP schema export. `schema-v1.sql` records the approved CHECK-bearing DDL. Room does not export CHECK annotations; `AppDatabase.CONSTRAINTS` adds them on first database creation while preserving the generated columns and indices. Always construct databases through the AppDatabase factory builders so this callback runs. Runtime tests exercise CHECK, uniqueness, foreign-key cascades, claims and reopening. Future migrations must preserve these CHECKs explicitly.
