---
type: issue
status: superseded
severity: blocker
tags: [issue, schema, runtime, wave/live-drive-context]
---

# Classify the generated-at diagnostic as a read refusal

## Problem

The Drive 1 observation attributed Datahike's unknown-attribute log for
`:seon.cluster.run/generated-at` to the generated-run writer. On that premise,
the issue asked for a declaration or install-order repair at the schema seam.

## Evidence

The preserved cluster log records the message at
`2026-08-14T05:39:52.591002Z`. The driver's fact observer had already returned
after the generated run settled: the receipt landed at `05:39:42Z`, the run
closed at `05:39:45Z`, and the bounded observer returned at `05:39:52Z`.
`docs/prds/sci-execution-runtime/research/drive-1-report-2026-08-14.md`
§Driver probe hygiene identifies the later operation precisely: a diagnostic
pull requested the two undeclared attributes
`:seon.cluster.run/generated-at` and
`:seon.cluster.run/generation-complete-at`. It returned
`:seon.db/invalid-read` and did not transact.

The read path explains why the log resembled a writer rejection. Datahike's
pull resolves selector attributes through `resolve-datom`, whose
`validate-attr-ident` reports unknown attributes with
`:error :transact/schema` even on a read
(`reference-code/datahike/src/datahike/db/utils.cljc:176-199`). `seon.db/pull`
catches that dependency exception and returns a flat invalid-read value
(`src/seon/db.clj:1093-1112`). The dependency's internal error vocabulary does
not identify the caller as a transaction.

A read-only JVM probe against preserved root `tmp/drive-1-root`, cluster
`default`, repeated the driver's selector at basis `t=536871063`:

```clojure
{:installed? false
 :basis-before 536871063
 :basis-after 536871063
 :result {:seon.error/kind :seon.db/invalid-read
          :seon.db/invalid-read true}}
```

The probe emitted the same `Bad entity attribute ... :transact/schema` log
line while leaving the basis unchanged. Current `src/` and `resources/`
contain no producer or declaration for either diagnostic attribute. The
preserved generated run itself exists and settled before the diagnostic pull,
which independently rules out an abort of its opening transaction on that
attribute.

## Owner

The database read boundary and the evidence classification in this note.

## Resolution

Superseded in the commit that archives this note. No schema attribute was
added: generation time is not a production fact in the current model, and an
unused declaration would preserve the false premise.

`test/seon/db_test.clj` now repeats the two-attribute diagnostic pull against a
real run entity. It asserts that both attributes are uninstalled, each result
is `:seon.db/invalid-read` naming the dependency attribute, the database basis
does not advance, and the run remains readable through installed attributes.

Focused gate:

```text
bin/test seon.db-test
Ran 31 tests containing 293 assertions.
0 failures, 0 errors.
```

## Acceptance

An unknown attribute requested by a diagnostic pull is a typed invalid-read,
never evidence of a write, and the recurring regression proves that the read
cannot advance the database basis.
