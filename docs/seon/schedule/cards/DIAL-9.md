---
title: "DIAL-9: Stored-check limit is a dial"
type: reference
status: waits on PB-10
created: 2026-10-08
---

# DIAL-9: Stored-check limit is a dial

Ledger row DIAL-9 (group E). Source: `dials/dials.md` D18. Model: Sonnet. Host-bound (the writer, a schema
resource): throwaway check.

## Site (verified at `1fcdbf47a`)

`seon.db/stored-check-limit` (`src/seon/db.clj:4072-4074`, 200), read by `seon.db/stored-value-check`
(`:4076-4131`, at `:4121`, `:4127`): a schema registration judges the first 200 distinct entities holding the
attribute.

**Check first whether PB-10 deleted `stored-value-check`** (PB-10 removes the duplicated submitted-value walk; this
is the registration-time check, a different caller, but read PB-10's landing). If it survives, make it a dial; if
it was deleted, this row is dropped.

## Files

- `resources/seon/schemas/seon.config.db.edn`
- `src/seon/db.clj`
- `test/seon/db_test.clj`

## Schema

`:stored-check-limit [:int {:min 1 :seon.config/dial true :seon.config/default 200 :description "Distinct stored entities a schema registration's stored-data check judges per attribute."}]`

## Functions

Delete the `def`. `stored-value-check` takes the limit as an argument (contract gains `[:int {:min 1}]`); its
callers (`rg -n "stored-value-check" src`; `registration-check`, `:4133`) pass the registering cluster's
effective value (the registration request carries the database; read
`(:seon.config.db/stored-check-limit (config/effective db cluster))` where the cluster is known, else the declared
default from `config/defaults`).

## Proof

New `seon.db-test/stored-check-limit-comes-from-config`: three entities hold an attribute; registering a stricter
schema that two of them violate, with limit 1, reports at most one problem; with the default, two.

`--test seon.db-test --test seon.schema-test --test seon.config-defaults-test`

## Cost

D: none.

## Commit

`bin/lane commit <lane> -m "DIAL-9: the stored-check limit is a dial" --test seon.db-test --test seon.schema-test --test seon.config-defaults-test resources/seon/schemas/seon.config.db.edn src/seon/db.clj test/seon/db_test.clj`

## Dependencies

PB-10 (`db.clj`), DIAL-0 (`seon.config.db.edn`), UL-10 (`db.clj`).
