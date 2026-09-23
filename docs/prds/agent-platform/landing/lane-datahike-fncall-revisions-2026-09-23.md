---
type: evidence
status: fork 0c01b5fe landed and pushed; gitlink bumped; default JVM restart and a seon.db carried-projection follow-up owed
---
# Lane datahike-fncall-revisions (schedule #24q), 2026-09-23

Owner: `reference-code/datahike` (the maintained fork), the superproject gitlink and
this note. There are no Seon `src/` edits.

## Premise check: the stated cause was false

The writer-cost residue said `writer.cljc:249` builds the modified attributes from
the input tx-data, so a `:db.fn/call` counts as "unknown". That is not the code.
`writing/cache-revision-attributes` (`writing.cljc:587`, parent `fbd1ad2d`) reads
the report's **effective datoms**. Probed in default's JVM, which loads the fork
from `reference-code/datahike/src` (form in `lane.fncall-probe`):

```clojure
(d/transact c [{:a 1 :b 1}])
(d/transact c [[:db.fn/call (fn [db] [{:a 2}])]])
;; => rev-attrs [:a]; :a advanced to the new commit, :b unchanged, no conservative revision
```

On default's own connection, the head context had no `:conservative-revision`. The
latest commit had advanced only `seon.dev.mcp.artifact/{id,digest}`.

**The real cause.** A *speculative* value has no `:cache-context` at all, whether it
is a transaction function's argument or a `with`/report `db-after`. `core/with`
clears it at `core.cljc:136`, and `writing/complete-db-update` cleared it again.
Seon's `carried-projection` (`db.clj:1397`) therefore takes the content-key tier for
every in-transaction value. That tier costs about 10 ms per value (measured below).
`seon.sci.eval/revision-basis` sees an empty basis for the same reason.

## Fix (fork commit `0c01b5fe`, pushed to `origin/main`)

- `datahike.db/advance-cache-context`: the one revision-advance rule. The committed
  `query/advance-query-cache-context` now delegates to it.
  `datahike.db/modified-attributes` moved here, and `writing/modified-attributes`
  delegates to it.
- `datahike.db/speculative-cache-context basis db tx-data` builds the context for a
  speculative value. The basis revisions are kept. Each attribute the effective
  datoms wrote gets a fresh `random-uuid` revision. A schema attribute advances the
  conservative revision. `:commit-id` is dropped and `:committed? false` is set.
  Two cases return nil, so the value stays detached and is never claimed current:
  a basis without connection-id and generation, and a non-`DB` basis.
- `db/transaction.cljc`: a `:db.fn/call` or ident transaction function receives
  `(in-transaction-db db report)`. That is the in-flight value with the context
  derived from `::effective-tx-data` so far. The finished report's `db-after` gets
  the context from its `:tx-data` before `validate-report`.
- `writing/complete-db-update` keeps that context. The writer therefore threads it
  to the next queued transaction, and the commit loop still replaces it with the
  committed context. `install-secondary-index!` writes no datoms, so it detaches
  its value (`clear-cache-context`). `load-entities-with` already detaches.
- The shared query cache is unchanged. `db-cache-key` accepts only committed
  identity (`db.cljc:385`), so speculative values still never enter it.
- There is a CHANGELOG entry under 0.8 Features.

## Proof

**Fork tests.** The new file is `test/datahike/test/speculative_cache_context_test.clj`.
Its five tests:

- a fn-call commit advances `:s/a`, leaves `:s/b` and the conservative revision
  unchanged
- the in-flight value in two queued writer transactions keeps `:s/b`'s revision
  and gets a fresh `:s/a` revision, has `committed? false`, has no commit-id, and
  has the same connection and generation as the head
- a `with` value has no committed identity, inherits untouched revisions and
  queries correctly
- an in-flight schema write gets a fresh conservative revision
- an `empty-db` basis stays nil (the conservative case)

The run set was 25 namespaces: the new file, query-cache, transact, db, core, api,
committed-report, commit-graph, listen, purge, secondary-dynamic, secondary-index,
secondary-versioning, optimistic, tx-instant-monotonic, cross-tx-vt, upsert, tuples,
entity-spec, query-single-flight, head-cache, connector-release, filter, components
and ident. Script: `scratchpad/run.clj`.

| tree | tests | pass | fail | error |
|---|---|---|---|---|
| fork `0c01b5fe` | 224 | 1309 | 3 | 1 |
| parent `fbd1ad2d` (git-archive snapshot) | 224 | 1300 | 12 | 1 |

The parent fails the new tests: 8 assertions (the committed fn-call test passes on
the parent, as the premise check predicted). The residual reds are identical on
both trees and pre-date this change:

- `config-mismatch-does-not-open-or-alter-cache-generation:698`
- `mismatched-opening-does-not-reserve-a-reference:239`
- `config-mismatch-returns-its-acquired-reference:260`
- `test-metrics-hht` (error)
- `tx-report-happy-path-converges-to-conn:310`: failed on the parent and in one of
  two fork runs, so it is flaky.

**Seon consumption (default JVM, throwaway namespace `lane.fncall-probe`, read-only).**
The probe never redefined a default Var and never wrote default's connection.
Each iteration ran
`d/with` of `[[:db.fn/call (fn [_] [{:seon.dev.mcp.artifact/id <fresh> :seon.dev.mcp.artifact/digest "x"}])]]`
on default's head. That attribute is not in `seon.schema/projection-attributes`.

- **Parent:** `(seon.db/carried-projection v)` on the context-less value took
  9.8-10.9 ms per new value (8 runs).
- **Fork rule:** a lane copy of `speculative-cache-context`'s body was attached to
  the same value. `(#'seon.db/projection-cache-key v)` took 0.010-0.06 ms. The
  lookup in `seon.db/projection-cache` took 0.006-0.12 ms. It **hit** (8/8), the key
  equalled the head's key, and the result was `identical?` to the head's
  projection.
- A synthetic write to `:seon.schema.admission/source` (a projection attribute)
  produces a different key. That is the correct miss.
- For `seon.sci.eval` `program-basis` over the projection attributes, the
  unrelated-write basis equals the head's and the declaration-write basis differs.
  On the parent, the speculative basis is `{:attribute-revisions {}}`.

**Proof boundary.** The Seon side ran the fork's rule copied into a probe namespace
on the loaded (parent) Datahike, not the loaded fork code. The fork code itself is
proven by the fork tests. **Seon still takes the content tier** until
`seon.db/carried-projection` (db.clj:1382) branches on the value's
`:datahike.cache/connection-id` rather than `datahike.db/committed-value-identity`.
The revision tier should apply to any value carrying revisions. The `::commit` tier
applies to committed values only. That edit is Seon src, which is out of this lane.

**Hot path (writer/transact), parent vs fork, same JVM script `scratchpad/bench.clj`.**
The setup is a memory store with 200 attributes and 2,000 entities.

| probe | parent `fbd1ad2d` | fork `0c01b5fe` |
|---|---|---|
| `transact` one `:db.fn/call` datom (mean of 500) | 1.95 ms | 1.86 ms |
| `with` one `:db.fn/call` datom (mean of 2,000) | 0.045 ms | 0.045 ms |
| `transact` 1,500-datom `:db.fn/call` (mean of 20) | 20.5 ms | 18.8 ms |

There is no regression: the changes are within noise, and the added work is one
pass over the effective datoms.

## TIMINGS (over 1 s)

| operation | wall | justification |
|---|---|---|
| fork test JVM, 25 namespaces | 61-65 s (tests 38 s) | Cold `clojure -M:test` compiles Datahike from source, which is proportional to the dependency. The tests are 224 deftests. The fork has no warm test runner. This is a **defect over 10 s**, not filed (see below). |
| parent snapshot, same run | 60 s | same |
| fork focused run (2 namespaces) | 23 s (tests 3.1 s) | cold compile, as above |
| bench JVM (each tree) | 19-20 s | cold compile. The bench itself is under 2 s. |
| socket-REPL reload of 7 changed fork namespaces | 1.6 s | recompiles query.cljc (about 5k lines) and its dependents |

The cold Datahike JVM costs over 10 s on every fork verification. That is a defect,
and `docs/seon/issues/` is outside this lane's paths, so the orchestrator should
file it or extend an existing note. The fix would be a warm fork REPL or AOT
classes linked into a snapshot.

## Incidents

- My first socket-REPL port, 57391, was already held by another JVM (pid 16091,
  started 18:52, not mine). One harmless form, `(time (do (require 'clojure.test …)))`,
  reached it. It failed at compile with ClassNotFound and had no effect. After that
  I used an ephemeral port, and killed my JVM (pid 16809).

## Changed paths

- fork: `CHANGELOG.md`, `src/datahike/{db,query,writing}.cljc`,
  `src/datahike/db/transaction.cljc`,
  `test/datahike/test/speculative_cache_context_test.clj`
- superproject: gitlink `reference-code/datahike` `fbd1ad2d` → `0c01b5fe`, and this note

## Owed

- **JVM restart of default (RESET NEEDED: restart only).** Default loads Datahike
  from source, and its loaded classes are the parent's. Without a restart the
  gitlink bump is not live. No store reset is needed, and the cache-context is
  never persisted (`db->stored`, `writing.cljc:48`).
- **Seon follow-up (other owner, `src/seon/db.clj`).** In `carried-projection`,
  key the revision tier on `(:datahike.cache/connection-id (:cache-context source))`
  and keep `::commit` for committed values. Without this change Seon gains nothing
  on projections.
- **Seon readers that now see speculative contexts.** These should be reviewed by
  their owners:
  - `seon.db/index-evidence-current` (db.clj:1088): it now runs its exact index
    check on a speculative value of the same generation, which is sound.
  - `seon.render/branch-scope` (render.clj:1621): a speculative custody value now
    reports its branch's scope instead of `{}`.
  - `seon.sci.eval/revision-basis`: it now carries revisions, which is the intended
    effect.
- **Known conservative limit.** The writer thread's speculative revisions differ
  from the commit's revisions (a fresh uuid versus the commit-id). A memo keyed on
  an in-flight value therefore misses once against the committed value after each
  write to an attribute it reads. It never gives a false hit. After
  `load-entities`, the writer chain stays detached until the writer restarts.
