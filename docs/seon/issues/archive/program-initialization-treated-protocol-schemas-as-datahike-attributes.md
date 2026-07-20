---
type: issue
status: resolved
tags:
  - database
  - initialization
  - schema
severity: friction
tags: [issue]
---

# Program initialization treated protocol schemas as Datahike attributes

## Failure

A fresh Bun pod sent the complete registered Malli schema population during
database initialization. The JVM writer attempted to compile every registered
schema key as a Datahike attribute and stopped on
`:datahike.index-page/cursor`, whose canonical form is the request-only schema
`:seon.db.protocol/cursor`.

## Cause

`seon.db.writer/derive-declared-schema` did not distinguish queryable program
schema facts from stored entity attributes. Request, response, function, and
entity schemas all belong in the `:seon.schema` program population, while only
attributes named by a registered `[:map {:seon.db/entity true} ...]` schema
belong in Datahike's attribute schema.

## Resolution

Initialization now derives Datahike declarations from the entries of declared
stored entity schemas. The complete Malli population is still transacted as
program data. A writer regression proves that a request schema remains
queryable, its request field is not installed as a Datahike attribute, and a
stored entity attribute is installed before initial data is admitted.

## Evidence

- `test/seon/db/writer_initialization_test.clj`
- Clean Bun database initialization proceeds past canonical schema admission.
