---
type: issue
status: partly-fixed (reds 1, 2, 4 fixed by lane red-fix; red 5 fixed by lane red-fix2; red 3 owned by task seon.turn.batch/a-repl-namespace-row-and-digest-are-red-on-head)
severity: friction
created: 2026-10-19
tags: [issue, test, program, schema]
---

# Five reds on HEAD 453e14aae: the open fault is the tests' inline entity entries

Check runs `operator-check-20261019T221517.594.edn` and `operator-check-20261019T221644.906.edn`
(HEAD `453e14aae`, again after `8c63f2b8d`): 5 red. None is new on `8c63f2b8d`, and none comes from the closed-runtime
lanes. The open fault on `seon.schema.datahike/malli->datahike-attr-in` (operation
`seon.schema.datahike/attribute-declaration`) is the same refusal as reds 1 and 2.

## Red 1 and 2: `a-declaration-added-after-the-first-call-is-a-cache-miss`, `declaring-an-attribute-on-a-program-entity-schema-is-sufficient`

Cause (producer): `seon.program/derived-shape` hands every attribute an entity schema owns, inline entries included, to
`seon.schema.datahike/malli->datahike-schema-in` to find its set-valued attributes. That bridge needs a registered
schema per attribute (`seon.schema.datahike/attribute-declaration`, `::attribute-absent`). Every packaged entry is
`[:k :k]`, a reference to a registered key, so it always has one. The two tests append an inline entry,
`[::declared-after {:optional true} :string]` and `[:sample/declared-schema {:optional true} :string]`, which no
registry holds, so `seon.program/shapes-in` throws.

Reproduces on default (JVM mode, read-only):

```clojure
(let [forms (seon.schema/registered-schemas)
      declared (update-in forms [:seon.fn/fn 2] conj [:sample/declared-schema {:optional true} :string])
      p (seon.schema/build-projection declared)]
  (seon.program/shapes-in p))
;; => ExceptionInfo "The attribute has no registered schema."
;;    (registry has no :sample/declared-schema; :seon.fn/source is registered)
```

Introducing commit: `derived-shape`'s set-attribute derivation is `3b86a7ba1` (2026-09-28, unchanged since); the
throwing bridge predates it (`828fd1272` only made `attribute-declaration` a value). First recorded red: `76a490909`
(2026-10-07, base `433214bc5`). The tests state "declaration on the entity schema is the whole requirement", the
design `3b86a7ba1` broke for inline entries. It was red already when `241c8ed09` was accepted (10-16).

Proposed fix at the producer: `derived-shape` derives set-valued membership from the entry's own value schema (its
third element, or the registered key it names), not from a registry lookup of the entry's attribute name, and
delete the `requiring-resolve` pair there. Then an inline entry is sufficient, as the tests assert, and
`attribute-declaration` is asked only of registered keys. The alternative (the tests register the attribute) keeps the
bridge and retires the design sentence in the test comments; the owner decides by `seon.program` spec.

Fixed (lane red-fix): `seon.program/derived-shape` reads set-valued membership from each entry's own compiled value
schema through the new `seon.schema.datahike/set-valued?`, so an inline entry needs no registered attribute and
`malli->datahike-attr-in` is no longer asked of unregistered names.

## Red 3: `indexed-and-evaluated-declarations-are-the-same-entities` (program_test.clj:1582)

Cause: the two producers store different `:seon.ns/source` for `sample.s1`: one single-line form
`(ns sample.s1 (:require [...] [...]))`, the other the fixture's multi-line text, so the namespace's
`:seon.program/definition-digest` differs. Already owned: `seon.turn.batch/a-repl-namespace-row-and-digest-are-red-on-head`
(red proof `e5c13285a`, 2026-10-16; see `docs/research/agent-platform/2026-10-18/red-triage.md`). The test was red
earlier still for other reasons (09-27 `red-triage-2026-09-27.md`, 10-07 census), so the first-red commit of this
assertion is not isolated.

## Red 4: `typed-cross-namespace-deletion-retracts-function-and-test` (program_test.clj:1210)

Cause: a retired assumption. The test settles a receipt (`seon.turn/receipt-settle-tx`) and expects the typed
deletion to retract both entities. Since `c537242c0` (2026-09-28, "the receipt's program-row path ... go") program
facts are the fold's (`seon.turn.batch/program-facts-call`): settling applies none, as the docstring of
`seon.turn/receipt-settle-tx` says. The test must drive the deletion through the one REPL path, or be replaced by a
row in the existing `my.program/ns-unmap!` tests. First recorded red `76a490909`.

Fixed (lane red-fix): the test is deleted. `seon.cluster.turn-test/ns-unmap-retracts-the-owned-function-after-the-terminal-commit`
already drives `ns-unmap` through the REPL path and asserts the owned function is retracted and the typed deletion
verifies (`seon.sci.eval/committed-row?`, which also covers the absent sibling test).

## Red 5: `seon.db-test/a-namespace-entity-change-re-derives-its-declarations-digests` (db_test.clj:2748)

Cause: a retired assumption, from `241c8ed09` (2026-10-16): `seon.program/declaration-digest` drops `:requires` from
the used resolver context. The test's `f` calls `clojure.set/union` fully qualified, so adding
`(:require [clojure.set])` to the namespace cannot change its digest, and `(not= entity-digest stored-digest)` is
false (both `d9a4ba26...`). The writer's re-derivation is not shown broken: the next assertion, digest equal to
`db/definition-digest-of` over the stored namespace, passes. First stored red: 2026-10-18 11:18 (`1d7019d98`); it
was not in red census 2 (`241c8ed09`) because `seon.db-test` was not run there. Not bisected with `check --at`:
`bin/seon check --at 241c8ed09 (and its parent)` refuses with this same schema error before any test runs (seed
template `a92ba5b94` against an older candidate), so those runs carry no signal.

Proposed fix: make `f` spell the namespace through an alias the require adds (`s/union` with
`(:require [clojure.set :as s])`), which `used-resolver-context` keeps, so the digest does change.

Fixed (lane red-fix2): the test's `f` spells `s/union` and the namespace row declares the alias `s` for `clojure.set`, so
the kept used resolver context changes with the require.

## Proof when fixed

All five green in one `bin/seon check --at HEAD` of `seon.program-test` and `seon.db-test`; the open fault clears when
no test (or producer) asks `malli->datahike-attr-in` for an unregistered attribute.
