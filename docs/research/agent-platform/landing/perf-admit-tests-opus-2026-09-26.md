---
type: landing
lane: perf-admit-tests-opus
status: two fixes committed (d10d79061, bd3ecbda8), unadopted; the dominant costs are in held files (tasks 361088de3d56, 4bb788513862)
---
# perf-admit-tests-opus — defn admission and focused test request cost (2026-09-26)

Lane perf-admit-tests-opus. Measured on default pid 70849 (HEAD a34078273) and, after root's
`start --head`, pid 75849 (8e4ac24c9). Own MCP branches `perf-admit-tests-opus` and
`perf-admit-tests-opus-2`, both retired; scratch JVM namespaces removed. Commits: d10d79061,
bd3ecbda8, plus this landing and its tasks.

## Broken first

- **Neither commit is loaded in default yet.** Default runs a committed archive; root adopts.
  The after-numbers below are REPL proofs on captured values, not a re-run request.
- **seon.contracts-plan-test is red (27 failures, 2 members)**, independent of cost: a
  fixture's contract names `:example/order-row`, refused as "not declared in the candidate
  projection", so `largest-customer` never installs and every later assertion fails; one member
  ran 8,574 ms against its 5,000 ms bound. Not investigated further (not a cost defect).
- **seon.sci.documentation-test: 14 failures, 2 errors**; members over the 5 s bound:
  `a-contract-mistake-offers-its-documentation-as-a-read` 7,117 ms,
  `directory-observes-a-function-installed-after-context-acquisition` 5,681 ms,
  `an-absent-declaration-is-a-typed-statement-not-an-empty-one` 5,063 ms.
- **MCP SCI branch runs answered `events []`** for `(inc 1)` and `(inc 2)` on a fresh branch
  (pid 70849); the next form answered normally. `script/seon/dev/mcp.clj` `run-form` reads
  `@connection` right after `evaluate-source!` for `run-receipts`; with fcee85161 (durability
  follows fire-and-forget) that read can precede the receipts. Not reproduced on pid 75849
  (one try); no task filed. If it recurs, that read must wait on the settlement's own value.
- The seon.fn.edn task `gate-sets-map-arity-gets-a-supplied-database` (merge-cut1) is a real
  selection defect in my files, but the fix is task e4c8eac6a69a (delete the positional arity),
  whose callers are in held files (`src/my/program.clj`, `src/seon/issue/detect.clj`). Not done.

## Slowdown 1 — a contracted defn on a fresh branch (875 ms reported; 3,735 ms measured)

`(defn smoke-inc2 "Adds one." {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))`, profile
growth around one MCP run (`seon.profile/begin` / `explain`, inclusive, concurrent work included):

| run | evaluate-source! | installation-covers-program-change? | db write (with + transact!) | first-branch arity-base | gate-function-install |
|---|---|---|---|---|---|
| pid 70849, 1st defn on branch | 3,105 ms | 800 | 1,755 + 268 | 1,666 | 163 |
| pid 70849, 2nd defn | 970 ms | 303 | 103 + 118 | — | 95 |
| pid 75849, 1st defn on branch | 3,735 ms | 1,636 | 730 + 772 | 794 | — |

What a defn adds is not in my files; seon.fn and seon.test contribute about 95-163 ms (the gate).

- **Cause A (sci/eval.clj, held by fix-do-defn-opus)**: `installation-covers-program-change?`
  (`src/seon/sci/eval.clj:1961`) asks `[:find [?entity ...] :where [?entity]]` of
  `(db/since (db/history after) basis)`. An unbound pattern scans the whole EAVT history;
  Datahike has no transaction index (`reference-code/datahike/src/datahike/api/impl.cljc:148`
  `since` is a filter). Measured on default: 10 entities in the last 3 transactions, 2,025 ms.
  The call site already says so (`src/seon/turn.clj:5183`, "the program check below it walks the
  store"). Task **361088de3d56** (resources/seon/tasks/seon.sci.eval.edn).
- **Cause B (db.clj, held by the one-commit lane)**: the checked writer's first write on a fresh
  branch misses the arity and declaration caches, which key by content (`arity-content-key` reads
  ~78k datoms; `declaration-content-key` ~40k) because a writer's `:db-before` carries no commit
  id (`src/seon/db.clj:3817-3855`). Existing task **4bb788513862**; this landing added as evidence.

## Slowdown 2 — focused test requests for small namespaces

Re-run on pid 70849 through the same form `bin/test-check` sends (`seon.test/run` over the
cluster handle):

| request | members | elapsed | selection | clean-request-handle | member bodies |
|---|---|---|---|---|---|
| seon.contracts-plan-test | 2 | 18,933 ms | 1,603 | 2,127 | 8,990 + 3,472 |
| seon.sci.documentation-test | 11 | 45,503 ms | 4,338 | 3,926 | 30.2 s (11 bodies) |

Each is proportional to members × writes per member, and each write on a member branch costs
350-440 ms (`db/transact!` ×46 = 20,357 ms of the 45.5 s request; `validate-write-report` ×45
8,602 ms; `report-arity-comparison` ×2 3,813 ms). The same transaction through `seon.db/with`
on default's committed value costs 3-6 ms, so the cost is the fresh-branch cache miss (Cause B),
not the datoms written. The second cost is Cause A inside bodies that define functions
(`installation-covers-program-change?` 4,160 ms ×4 in contracts-plan, 1,646 ms ×1 in
documentation). Queries: `seon.db/q` ×739 18,346 ms inclusive, `with-declarations` ×9,169
(existing task the-query-wrapper-costs-36x-datahike).

Per request (once), not per member: checkpoint reuse works (one `checkpoint-default-…` branch,
key stable across calls); the overlay is 4 datoms (two namespace rows), 21 ms to derive;
`select` is 168 ms warm and 4.3 s when its comparison basis is old (the observed-change walk
`changed-definition-symbols` + `gate-sets`, whose result a `:named` request only uses as a
reason label and a reuse guard that the reach digest already covers). Reach digests: the first
derivation for a program is 787 ms, later calls 0.7 ms.

**Fixed in my files — per member, repeated work:** every effect-reaching member (11 of 11 in
the documentation request, because call reach from an SCI-evaluating test includes
`seon.effect/request*`) wrote its attempt agent and its turn as two checked commits
(`attempt-request-context`, `src/seon/test.clj:185`; 8,614 ms ×11). bd3ecbda8 writes them as
one transaction. Datahike applies a transaction's operations in order, so the turn's
`[:seon.agent/id …]` lookup resolves the agent written before it. REPL proof (pid 75849,
`seon.db/with` on default's value): the combined data pulls the same turn, agent, branch and
namespace as the two writes applied in sequence. Expected saving: one fresh-branch write per
effect member, about 350-440 ms each (≈4 s of the 45.5 s documentation request). Regression:
`seon.test.clean-world-test/a-member-world-is-the-loaded-program-with-boot-facts-only` now
asserts the attempt facts arrive in one transaction.

## Root's add-on (perf-boot-opus finding, my file seon.fn.clj) — d10d79061

`seon.fn/caller-files` relinted the direct callers of every function whose contract names a
changed schema, though publication lints with type-derived linters off
(`src/seon/fn/analyzer.clj:45-56`). Applied `tmp/perf-boot-opus/fn-caller-files.patch`
(+26/−46 in fn.clj, stale `declare` removed). REPL on default with `d/with` reports on
`seon.test.runner/reach-digests`: a spec-only change selected 7 files in 83 ms before and
`#{}` in 0.13 ms after. An arity `:min` change selected `#{}` **before** (the arity is a
component with no identity, so `report-identities` was empty and the old early return skipped
it) and its 7 caller files after: the old code missed real arity changes. publication_cache_test's
contract-only change now expects no caller relint (both the lint list and `caller-files`).
Tasks ff74379b6565 and e09ead5c0cd6 appended to resources/seon/tasks/seon.fn.edn (handles
verified with `seon.issue/task-handle`).

## Tasks (adoption write, cold start)

- d8f73961e44f (adoption write, `with-declarations` ×18,794): lives in seon.db and
  seon.cluster, which are held files. Same class as Cause B and the query-wrapper task.
- 56b1dad969bd (cold-start per-form cost): seon.sci.eval, held. The fresh-branch numbers
  above (3,735 ms first defn against 970 ms later) are evidence for it: Cause A plus Cause B.

## Net lines

src: fn.clj +26 −48; test.clj +9 −5. test: +8 −4. Tasks: +1 in seon.sci.eval.edn, +2 in seon.fn.edn.

## Root runs

- `bin/test-check default --test seon.test.clean-world-test/a-member-world-is-the-loaded-program-with-boot-facts-only`
- `bin/test-check default --test seon.test.effect-attempt-test/an-agent-capability-stub-settles-a-receipt-only-in-an-effect-reaching-attempt --include-long`
- `bin/test-check default --test seon.fn.publication-cache-test/lint-direct-callers-after-committed-declaration-changes --include-long`.
  The spec-only lint-list expectation (`["alpha.clj"]`) was derived by reading, not run.
- Then re-time `bin/test-check default --ns seon.sci.documentation-test` against 45,503 ms.
