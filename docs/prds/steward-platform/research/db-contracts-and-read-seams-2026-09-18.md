---
type: research
status: open
created: 2026-09-18
tags: [seon.db, contracts, errors, pull]
---

# `seon.db` contracts, the derived pulled form, and three read seams

Continues the lane that landed `1695b43b2` ("db: declare database operation
error facets") and died on an external usage limit with uncommitted partial
work in `src/seon/db.clj` and `resources/seon/schemas/seon.db.edn`. That
partial work is continued here, not discarded.

## 1. The declared error union (§1q)

Ruling §1q: every function's output contract lists, as an `:or` union, the
error facets it can return. `1695b43b2` declared the union once as
`:seon.db/error-result` (`resources/seon/schemas/seon.db.edn:3`) and applied
it to `q`, `pull`, `pull-many`, `transact-call`, `transaction-result` and
`transact!`. **Item 1 was not complete at that commit**: ten more `seon.db`
read/write owners still declared a bare `:seon.error/value` in an output
position, so any classified facet they returned was undeclared. They are
declared now.

| Function | Declared output union |
|---|---|
| `seon.db/db` | `[:or :seon.db/database-value :seon.db/error-result]` |
| `seon.db/supplied-database-value` | `[:or :seon.db/database-value :seon.db/error-result]` |
| `seon.db/supplied-connection` | `[:or :seon.db/connection :seon.db/error-result]` |
| `seon.db/q` | `[:or :seon.schema/value :seon.db/error-result]` (landed `1695b43b2`) |
| `seon.db/pull` | `[:or :nil :seon.db/pulled-entity :seon.db/error-result]` |
| `seon.db/pull-many` | `[:or [:vector [:or :nil :seon.db/pulled-entity]] :seon.db/error-result]` |
| `seon.db/entity` | `[:or :nil :seon.db/pulled-entity :seon.db/error-result]` |
| `seon.db/datoms` | `[:or :seon.db/datoms :seon.db/error-result]` |
| `seon.db/index-page` | `[:or :seon.db/index-page-result :seon.db/error-result]` |
| `seon.db/commit-id` | `[:or :nil :uuid :seon.db/error-result]` |
| `seon.db/committed-value-identity` | `[:or :nil :map :seon.db/error-result]` |
| `seon.db/history` / `as-of` / `since` | `[:or :seon.db/database-value :seon.db/error-result]` |
| `seon.db/diff` | `[:or :seon.db.diff/result :seon.db/error-result]` |
| `seon.db/arity-mismatches` | `[:or :seon.fn/arity-mismatch-report :seon.db/error-result]` |
| `seon.db/read-evidence-current?` | `[:or :boolean :seon.db/error-result]` |
| `seon.db/read-evidence-changes` | `[:or :seon.db/datoms :seon.db/error-result]` |
| `seon.db/transact!` / `transact-call` | `[:or :seon.db/transaction-report\|-result :seon.db/error-result]` (landed `1695b43b2`) |
| `seon.db/replay-read` (new contract) | `[:or :seon.schema/value :seon.db/error-result]` |

Input positions keep `:seon.error/value`: an upstream refusal passed INTO a
read is the pass-through the reader returns verbatim, not a facet it produces.

## 2. The pulled form is derived, never mirrored (§ pulled-form study)

`seon.db/pull` and `pull-many` now validate every non-nil map they return
against `seon.schema/pulled-form-in` for `(schema-key, selector)` on the
value's carried projection, using the exact literal selector Datahike was
handed (`src/seon/db.clj`, `pull-call` → `validate-pulled-result` →
`validate-pulled-value`).

- The schema key comes from `:schema-key` on the argument map when the caller
  supplies it (newly declared on `:seon.db/pull-options` and
  `:seon.db/pull-many-options`), otherwise from the entity's OWN attributes:
  the declared `:seon.program/row-schema` of the schema that states them
  (`pulled-entity-schema-key`). Identity is never inferred from a name.
- A refused derivation refuses the read (`::invalid-pulled-result` /
  the derivation's own `:seon.schema/unsupported-pull-selector`).
- A `nil` element is the declared absence of an entity and carries no form.
- A pulled map whose row schema the present attributes do not uniquely
  declare is `::unknown-pull-schema`, naming `:schema-key` as what to supply.

`:seon.db/pulled-entity` names that guarantee in the contract. The derived
registry key is per (schema-key, selector) and cannot be named statically in
a fixed contract; `seon.schema/pulled-schema-key` is where it lives.

## 3. The three read seams (critical findings #18, #19, #20)

- **#18, `read-declarations`.** It returned a table whose `::installed-schema`
  was `nil` for any value that was not a database, and `edn-encoded?` then
  answered false for EVERY attribute: each decoded value silently lost its
  declared decoding, with no signal anywhere. It now returns
  `:seon.db/unreadable-declarations` instead, and the new `with-declarations`
  seam refuses before any decode continuation runs. Every consumer in
  `seon.db` branches on it: `pull-call`, `replay-read`, `q`, `datoms-call`,
  `index-page`, `diff`, `read-evidence-changes`.
- **#19, `replay-read`.** Its `case` over `:seon.db/read-operation` had four
  arms and no default, so a fifth value threw `IllegalArgumentException`
  naming nothing into the since-diff that runs before every agent turn. It
  carries an input contract naming `:seon.db/read-request` (whose declared
  `:seon.db/read-operation` is the enum) AND a typed default arm,
  `::unknown-read-operation`, naming the attribute and the offending value.
- **#20, `derive-projection-from-database`.** A refused read handed on as `db`
  produced a projection with an empty forms table, and every downstream
  `storable-attribute-in?` answered from it. The derivation now refuses with
  `:seon.schema/invalid-projection-source`, naming the offending value. The
  refusal is delivered as `ex-info` carrying the complete diagnostic value
  rather than as a returned value, because `projection-from-database`'s
  output contract is `::projection` and more than one hundred callers read it
  as one; the sibling refusals inside `projection-from-rows` already use that
  delivery. **This is the one design decision in this note that a reviewer
  may want to overturn** — the alternative is widening the output union of
  `projection-from-database`, which is a cross-owner change.

## 4. Two write bounds, decided by the write's own provenance (§1r)

`transact-call` derives the per-write bound from the transaction's own
`:tx-meta` provenance (`agent-provenance?`), not from a second access-control
mechanism:

- `:seon.db/user` naming an agent (a `[:seon.agent/id …]` lookup ref, or a
  resolved entity carrying `:seon.agent/id`) → the short, loud
  `:seon.config.db/write-time-limit-ms` dial, unchanged in shape.
- root/system provenance — a `:seon.db/process` write, or any write with no
  agent user — → **no per-write bound**; the operation's own lifecycle
  deadline (publication, boot, reset, adoption) is the one that reports.
- A bounded-out agent write now returns its refusal WITH the transaction data
  (`:seon.store/transaction` in the refusal's evidence) so root can re-run it.

## 5. Regressions

| Regression | Namespace | Proves |
|---|---|---|
| `a-refused-declarations-read-refuses-decoding` | `seon.db-test` | #18: the refusal, its member, and that no decode continuation runs |
| `an-unknown-read-operation-refuses-naming-the-attribute` | `seon.db-test` | #19: typed refusal naming `:seon.db/read-operation` |
| `pull-validates-its-result-against-the-derived-pulled-form` | `seon.db-test` | a real canonical-fixture pull validates; an absent entity is nil; a wrong-typed expectation fails |
| `the-write-bound-derives-from-the-writes-own-provenance` | `seon.db-test` | §1r provenance decision for agent, process and absent user |
| `an-agent-write-that-does-not-deliver-refuses-at-the-declared-bound` | `seon.db-test` | §1r: the dial still fires for an agent write (the pre-existing bound test, re-based on agent provenance) |
| `a-system-write-carries-no-per-write-bound` | `seon.db-test` | §1r: the same blocked writer does not refuse a system write |
| `a-refused-projection-source-never-yields-a-projection-with-no-forms` | `seon.schema-test` | #20 |

## 6. Verification boundary and tallies

**No test tally is claimed.** Three `bin/test-fast` launches were started and
none produced one:

1. `bin/test-fast --paths src/seon/db.clj src/seon/schema.clj
   resources/seon/schemas/seon.db.edn test/seon/db_test.clj
   test/seon/schema_test.clj -- seon.db-test seon.schema-test
   seon.instrument-test` — **the overlay refused before launching tests**,
   naming the dirty caller files held by other lanes:
   `src/seon/cluster/source.clj src/seon/test.clj src/seon/test/runner.clj
   test/seon/cluster/source_test.clj test/seon/test/runner_test.clj`.
   Iteration therefore used the plain form, which runs the working tree
   including every other lane's half-edits.
2. The plain baseline run was stopped: at 18 minutes it had begun 10 of the
   three namespaces' tests, and the source under it had already advanced.
3. The plain run on final source reached four errors, all of them the same
   canonical-fixture refusal, and was stopped to diagnose it (§7).

`bin/seon --root tmp/db-probe-root init` converged from this tree
(`:current-src` commit `6aac8556-d975-5c21-a424-b8eeb7fdd25d`, digest
`482a4a55e…`, 11,190 entities, 124 s); the root was downed and deleted.
No `default` cluster operation was performed. No cold gate was run; the
orchestrator still owes `bin/test --paths` plus `--platform`.

## 7. The initialization break, and exactly which change caused it

`seon.cluster/transact-initialization!` (`src/seon/cluster.clj:1268-1300`)
decides a row is ready with
`(:db/id (db/pull database [:db/id] [attribute value]))`. It needs the two
outcomes to stay distinguishable: **nil is a legitimate absence** (the row is
not ready YET), a flat error is a refused read.

**The dead lane's partial `pull-request-parts` broke that, and it did so
before the pull ever ran.** For a lookup-ref entity id it took the entity's
attribute set to be `#{(first entity-id)}` — the identity attribute alone,
whether or not the entity existed — derived candidate schema keys from that
one attribute, and returned `::unknown-pull-schema` from `pull-call` whenever
the candidate set was not a singleton. So `db/pull` returned a flat error for
BOTH a missing entity and a present one, `(:db/id <error>)` was nil for every
row, no row was ever ready, and the loop refused with "Initialization lookup
refs do not resolve."

The measured trigger in the canonical fixture is
`[:seon.ai.model/provider-id "openrouter"]` — an entity that **exists**
(eid 35926, attributes `:seon.ai.model/openai-chat-completions`,
`:seon.ai.model/output-token-wire-key`, `:seon.ai.model/provider-id`,
`:seon.config.ai/api-key-variable`, `:seon.config.ai/endpoint`). Nothing
declares a `:seon.program/row-schema` for any of them — that property exists
on a handful of attributes only (`seon.fn`, `seon.fn.file`, `seon.ns`,
`seon.lint`, …) — so the schema key was undecided and a successful read
became a refusal.

**The repair, measured:** an undecided schema key is a gap in the DECLARED
FACTS, not a bad read. `validate-pulled-result` now validates only when the
key is named (by the caller's `:schema-key`, by `:seon.program/row-schema`,
or by a unique projection shape-index match whose required attributes are all
present), returns `nil` elements unchanged, and returns an unnamed pulled map
unchanged. With that in place the instrumented probe counted **0 refused
pulls** during canonical fixture construction, and the fixture's remaining
refusal is a FOREIGN in-flight edit, not this work:

```text
Canonical fixture base construction failed: First-party program namespace
seon.test-runner-test could not be loaded for the evaluation context.
Cause: Unable to resolve var: dev-cache/digest-file! in this context
at seon/test_runner_test.clj:48:20.
```

`dev_cache.clj` and `test/seon/test_runner_test.clj` are held by other
agents; the same dependency is already recorded in
[the wrapper-enforcement note](error-wrapper-enforcement-2026-09-18.md).
While it stands, no canonical-fixture regression in this tree can run, this
note's seven included.

## 8. Findings handed on, not fixed

- **Callers that do not carry a `:schema-key`.** Every `seon.db/pull` and
  `pull-many` caller whose pulled entity has no uniquely declared row schema
  is returned unvalidated today. The named repair is the follow-up list in
  `research/pulled-form-derivation-2026-09-17.md` §"Exact database follow-up"
  (the thirteen fixed-selector readers plus `seon.eval/of-agent`), which
  carries the schema key at the call site. A per-caller census was not taken:
  the fixture blocker above stops the instrumented run that would produce it.
- **`:seon.program/row-schema` is declared on a handful of identity
  attributes only.** Until it is a general fact, the shape-index match is what
  names most entities, and neither names a transaction entity.
- **`projection-from-database` still refuses by throwing.** See §3.


