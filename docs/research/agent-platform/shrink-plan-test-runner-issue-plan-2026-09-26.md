---
type: plan
lane: shrink-test-opus
status: plan only; awaiting root rulings (S2, S4, O1/O2)
---
# Shrink plan: seon.test, seon.test.runner, seon.issue, seon.plan (2026-09-26)

Lane shrink-test-opus. The owner switched to plan-only mode, so this lane made no source edits. The four
files hold 7,742 lines: `src/seon/test.clj` 2,602, `src/seon/test/runner.clj` 1,891, `src/seon/issue.clj`
1,662 and `src/seon/plan.clj` 1,587.

Evidence sources:
- JVM-mode MCP reads on default, pid 89341 (HEAD b6660a34b). Every probe was read-only; the session was
  `shrink-test-opus`.
- `seon.profile` observations.
- A clj-kondo var-usage analysis of src, script and test with `--cache false`. The script is at
  `tmp/shrink-test-opus/dead.clj` and `callers.clj`.
- `rg` over bin, script, resources, .agents, src and test.
- Today's perf-admit landing and the task EDN.

Nothing here is installed. Timings are from the running default, not from a quiet machine.

## 0. Headline findings

1. **No kondo-dead definitions.** kondo reports five definitions with zero static users:
   - `issue/report` is used by `script/seon/dev/issues.clj`.
   - `test/isolated-members` and `test/declared-bound-ms` are used by `bin/test-check`.
   - `runner/var-generator` and `class-loader-generator` are used as `:gen/gen` in `seon.test.edn`.

   All five are live, so there is no free dead-code slice. The large savings are production-dead
   *features*, and removing them needs owner rulings (§3).
2. **Two whole features have no production data or production caller.** On default:
   - 0 `:my.plan/current-step` rows, 0 `:my.plan.item/id`.
   - 0 `:seon.issue/agent` rows (no issue workers).
   - 0 `:seon.issue/detector` rows.

   The only callers of `seon.issue/start!` and `generate!` are tests. Ruling 2.1 in
   `language-requirements-2026-09-24.md` says "issue, backlog and plan are derived views, never separate
   stores". Even so, every turn close still runs `plan/run-issue-tests!` and `plan/settle-call` →
   `issue/settle-call` → `issue/exhaust-tx` inside the writer: 92 calls, 855 ms total, 274 ms max.
3. **The slowest work in these files is repeated or proportional to the wrong thing.** Details are in §1.
   The worst item is `admit-run`, which does the same program-change check twice, both times inside the
   single writer.

## 1. Slow paths first

Profile totals since boot (calls / total ms / max ms):

| function | calls | total | max |
|---|---|---|---|
| seon.test/run | 9 | 256,353 | 121,189 |
| runner/run-var! (bodies) | 18 | 233,513 | 146,107 |
| test/member-result | 18 | 143,815 | 49,528 |
| runner/reach-digests | 33 | 58,219 | 10,175 |
| test/select | 9 | 48,289 | 13,234 |
| runner/reach-facts | 14 | 43,539 | 6,536 |
| test/changed-definition-symbols | 16 | 15,650 | 2,565 |
| test/admit-run (writer) | 9 | 14,992 | 4,285 |
| test/clean-request-handle | 3 | 11,289 | 5,365 |
| runner/program-written-since? | 9 | 8,879 | 2,744 |

Callees in other owners dominate the member and world costs:
- `cluster.registry/branch!`: 85 calls, 38.2 s total, max 31.5 s.
- `sci.eval/acquire!`: 119 calls, 24.7 s, max 3.1 s.
- `fn/published-index-rows`: 17 calls, 18.2 s.

Those rows are listed for root in §1.6 and are not in this lane's slices.

### 1.1 `admit-run` checks for a program change twice inside the writer (S1, recommended first)

**What it does.** `src/seon/test.clj:1192` sets the digest to the tested one only when
`(false? (runner/program-written-since? database basis))`. Otherwise it calls `runner/program-digest`,
which on the writer's in-transaction value (no commit id) returns its refusal. Any value other than
`false` therefore ends in `:seon.test/program-mismatch` at `:1235-1241`.

Line `:1254` then runs `changed-definition-symbols` on the same basis and refuses with the same kind.

**Why the second check is redundant.** The attributes `changed-definition-symbols` watches are a subset of
`program-digest-read-attributes`. Proven live: the set difference is `#{}` against 166 attributes. So when
`:1254` is reached with `snapshot?` false, no watched datom was written since the basis, and the result is
always empty. The check can never refuse. It only costs as-of and history queries on an uncached writer
value: part of the 16 × `changed-definition-symbols` calls and of admit-run's 4.3 s maximum, all
serialized behind the one writer.

**Fix.** Delete `:1254-1256` (3 lines).

**Regressions.** Admission refusals in `test/one_request_test.clj`, `test/admission_digest_test.clj` and
`test_test.clj`: the program-mismatch refusal must still fire when the program changed after the tested
basis. That path now goes through the digest.

**Risk.** Low. Snapshot admission already skipped this line.

**Related open task.** The rest of admission's cost is task f868dc9e74f5: `program-written-since?`
filters about 170 attributes of history, about 75 ms per admission. It is cheap on committed values: 174
ms for a 50-transaction window, while `program-revisions` costs 0.37 ms. The task's fix is to carry
Datahike attribute revisions in the provenance. It stays that task.

### 1.2 Named selection pays for the observed-change walk it does not need (S2)

**What it does.** `select` (`test.clj:828`, `:856-858`) always runs:
- `changed-definition-symbols` from the last completed run's basis to now (817 ms for a 200-transaction
  window with 1,156 symbols; `definition-digests` is 471 ms of that, for as-of plus current);
- then `functions/gate-sets` over the whole observed change.

The perf-admit landing measured this at 4.3 s when the basis is old.

**What a `:named` request uses it for.**
1. The `:reaches-changed` reason label.
2. The reuse guard `(not (observed-reached test-symbol))` at `:1004`.

The reuse guard is already implied. A green reuse also requires the member's stored reach digest to
equal the current one, and that digest covers every reached definition digest and schema form.

**Fix.** Compute `observed-changed` and `observed-reached` only when the policy is `:incremental`, or
when a request names nothing. That is about 2 changed lines.

**What it shortens.** A focused `bin/test-check --ns` request is `:named`, so its select should approach
the warm named figure measured here: 57 ms the first time, 6.8 ms after.

**Regressions.**
- `test/selection_test.clj`
- `test/scoped_first_run_test.clj`
- `cluster/publication_reuse_test.clj`

**Risk.** Medium. Named results lose the `:reaches-changed` label, and one reuse is now decided by the
reach digest alone. Needs root's or Astra's agreement that the digest is the authority, which the
digest's own docstring and B4 §2a already claim.

### 1.3 A cold reach-index derivation takes one to three queries per graph depth (S3)

**Measured.** `runner/reach-digests` for 20 tests on a fresh program took 1,284 ms: `reach-facts` 836 ms
over 98 `seon.db/q` calls. The same call warm took 2.4 ms.

**What it is proportional to.** Graph depth × number of identity attributes. `reach-facts` (`:723-772`)
runs a separate identity query for each of `:seon.fn/sym`, `:seon.test/sym` and `:seon.schema/key` on
every breadth-first round, then a facts query over those entities. Each `seon.db/q` costs about 8 ms
here; that wrapper cost is db task "the-query-wrapper-costs-36x-datahike".

**Fix.**
- One query per round that binds `[?identity ?token]` tuples and returns `?entity ?attribute ?value`
  directly (Datahike `:in $ [[?a ?v]] …`). That drops the separate identity reads: about 3× fewer calls.
- `reach-row` re-reads specs and schema forms with `edn/read-string` on every refresh. Key it by the
  row's definition digest through `seon.cache` instead of recomputing.

**Status.** Predicted, not measured. The slice must measure it with the same 20-test probe (form under
§5).

**Size.** About −10 lines.

**Regressions.**
- `test/reach_test.clj`
- `test_reaching_test.clj`
- `test/selection_test.clj`

### 1.4 The reach index keeps its own caches, one of them an atom (S4, after S3)

**Current mechanism.** `reach-holders` and `reach-lineage` (`runner.clj:861-869`) are
`cache/lru-cache-factory` instances. Their thresholds, 16 and 64, are code constants, which breaks the
CLAUDE.md rule that a cache's size is a configuration dial. `reach-holders` holds an atom of a delay
claimed by compare-and-set.

`reach-content` (`:876`) keys the holder by *every* definition digest and schema form in the database:
11,703 plus 3,548 tuples, 12 ms per call. That is O(program) on every call.

**Fix.** Move to the central `seon.cache` (cache-central lane, `src/seon/cache.clj`):
- Key by commit through `runner/program-revisions`, which is O(1) (0.37 ms).
- Keep the lineage fallback for incremental derivation.
- Delete the atom-of-delay claim and let `seon.cache`'s `lookup-or-miss` own single derivation.

**Deletes.** `reach-holders`, `reach-cache`, `reach-content`, the compare-and-set loop in `reach-entries`
(`:906-953`) and `lineage-keys`: about −50 lines, plus the atom.

**Behaviour change.** Branches with byte-equal program rows but different commits no longer share one
index. Each derives incrementally from its parent's lineage entry instead, a sub-second refresh. Owner
ruling needed, since this is a cache-key change, which CLAUDE.md "Reuse content-valid work" governs.

**Regressions.**
- `test/reach_test.clj`, which covers equal-content sharing and must be revised if the ruling is taken
- `test_reaching_test.clj`

### 1.5 Turn-close settlement for features with no rows (resolved by O1/O2, or by S5 if those are refused)

**Current cost.** Every closing turn runs `plan/run-issue-tests!` (`turn.clj:3681`, `:3801`, `:5250`). The
writer runs `[:db.fn/call #'plan/settle-call agent-id]`, which appends `[:db.fn/call #'issue/settle-call]`,
which appends `[:db.fn/call #'exhaust-tx]`. That is three nested writer functions per close:
- 855, 540 and 517 ms total over 92 closes;
- 274 ms max;
- sub-millisecond on a committed value, which leaves the in-writer cost unexplained.

**S5, only if O1 and O2 are refused.** `issue/settle-call` returns `[]` without the `exhaust-tx` call when
the agent has no open issue: it already queried for it. −1 nested writer call per close, about 3 lines.

### 1.6 Slow callees in other owners (for root; not this lane's files)

- **`cluster.registry/branch!`: max 31.5 s.** This is `clean-request-handle`'s world branch and every
  member branch, and is the largest single wall-clock item in a test request.
- **`sci.eval/acquire!`: 119 calls, mean 207 ms, max 3.1 s.** It runs once per member.
- **`seon.db/q` wrapper.** An existing db task: 8-12 ms per call inclusive here.
- **`test/member-result` (my file).** Its cost is the body plus acquisition. Nothing in its own code is
  disproportionate, and tasks 845e1bce2592 and bc7224ccaa61 already cover world cost.

## 2. Deletions within the four files (no owner ruling needed)

Proof method:
- kondo `var-usages` across src, script and test, with `--cache false`;
- `rg` over bin, script, resources (schemas and task EDN), .agents, src and test for the qualified name,
  the quoted symbol and `requiring-resolve` strings.

| # | what | proof | lines | risk | regression |
|---|---|---|---|---|---|
| D1 | `admit-run`'s second program-change check | §1.1 (attribute subset, live) | −3 | low | one_request / admission_digest |
| D2 | `runner/run-selected-tests` 2-arity | only caller is the 3-arity call at `runner.clj:394` (rg) | −1 | none | load |
| D3 | `issue.clj` hand-built error maps (21 sites of `{:seon.error/at (java.util.Date.) :seon.error/layer … :seon.error/operation …}`) → `seon.error.refusal/diagnostic`, the one builder (CLAUDE.md "one way to build an error") | pattern count `grep -c` = 21; `diagnostic` already used by runner/test | ≈ −40 | low: same keys; `diagnostic` adds `:seon.error/chain` only for a Throwable | issue_test, issue_settlement_test (error shapes) |
| D4 | `runner/execution-read`, `test/selection-read!` and the four inline copies of the same three-key "is this a refusal" test (`test.clj` selection-seeds ×2, admit-run ×2) collapse onto one helper. `seon.db/read-refusal?` exists (`db.clj:195`); the plan's `read-result!` already uses it | rg | ≈ −20 | low: `read-refusal?` must accept the same maps; verify at REPL first | selection_test, runner_test |
| D5 | `test/green-members` public with one internal caller → private | kondo: 0 external users; rg none | 0 | none | — |

If O1 is refused, `plan.clj` also has 22 hand-built error maps with the same key copied three times per
site. `query-deadline:650-658` and `done-query-result:737-748` show it: `:seon.error/offending`,
`:seon.error/data`, and `(get {…} :my.plan.item/id)` of a literal map. Converting them to `diagnostic`
saves about 60 lines. If O1 is taken, the whole file goes.

Test-runner overlap with clojure.test: `run-selected-tests` already calls `clojure.test/test-vars`, and
`run-vars!` binds `test/report`. What remains is capture: per-var counts, failure identities and duration
bounds. clojure.test has no value-returning form of that. `report-event!` and `report-error!` re-print
what clojure.test's default `:fail` and `:error` methods print, for signature de-duplication. Replacing
them is blocked by task 32db32ffe15c (failure evidence stored as text), which must land first.

## 3. Deletions needing an owner ruling (priced options)

### O1: delete `seon.plan` and `my.plan` (the separate plan store)

**Evidence.**
- 0 plan rows on default.
- Ruling 2.1: plans are derived views of tasks, not a store.
- Production callers: `turn.clj` (settle, run-issue-tests!), `cluster/agent.clj`, `render/block.clj`,
  `render/transcript.clj`, `cluster/status.clj`, `bootstrap.clj`, `note.clj`, `my/note.clj`,
  `my/agent.clj`. They reference `my.plan` keys and render hooks.

**Size.**
- `plan.clj` 1,587 lines and `my/plan.clj` 173.
- Schemas: `my.plan.edn` 301, `my.plan.item.edn` 102, `seon.plan.edn` 14.
- Tests: `test/my/plan_test.clj` 607, `plan_api_test.clj` 95, `plan_test.clj` 45,
  `plan_completion_test.clj` 71, plus the plan parts of `html_views_test` and `render/value_test`.
- Total: about −1,760 source and −900 test lines.

**Also.** Removes one nested writer call per turn close (§1.5). `seon.test/stale` is then left with only
test callers and goes too, about −35 lines.

**Cost.** One coordinated slice across 9 files held by other lanes, so patches to root. `run-issue-tests!`
moves into `issue.clj` if O2 is refused.

### O2: delete the issue-worker lifecycle and detector generation

**Evidence.**
- 0 `:seon.issue/agent` rows and 0 `:seon.issue/detector` rows on default.
- The only callers of `start!` and `generate!` are tests.
- `seon.issue.detect` (332 lines) has only test callers.
- `seon.issue.opening` (233 lines) renders only for an agent that has an issue, which never exists.

**What goes from `issue.clj`.**

| definitions | lines |
|---|---|
| `turn-*` delays | 25-36 |
| `subject-row` … `generate!` | 805-980 |
| `tests-done-query` / `done?` | 1259-1302 |
| `create-tx`, `start-tx`, `exhaust-tx`, `settle-call`, `start!` | 1318-1538 |

That is about −450 lines in `issue.clj`, plus `detect.clj` 332 and `opening.clj` 233. Tests:
- `issue_generate_test` 195
- `issue/detect_test` 202
- most of `issue_settlement_test` 341
- the lifecycle parts of `issue_test`

**What stays.** `subject-id`, used by `my.program/breaks`. `status`, `add!` and `tests!`, used by
`my.issue`. The adoption and task ingestion code.

**Conflict to rule on.** Merge-task design §1 resolves unmigrated issues' assignment through
`:seon.issue/agent`, and only `start!` writes that attribute. Two choices:
- the task assignment (`seon.task`) becomes the only assignment;
- keep `start!` until merge-cut lands.

**Cross-owner edits.** `cluster/agent.clj:313` (opening), `cluster/wake.clj` (comment), `turn.clj` (via O1).

### O3: the Markdown issue indexer retires with the 7.11 migration, not as a shrink

**What it is.**
- `notes`, `parse-note`, the citation machinery, `index-tx`, `index!` and `report`: `issue.clj:25-530`,
  about 500 lines.
- `script/seon/dev/issues.clj`.

**Why it is not dead.** `cluster/source.clj:520` indexes the 589 `docs/seon/issues/*.md` on publication.

Spec 7.11 converts them into task EDN (the first slice after 7.9), and this code goes with that
migration. No action here.

### Priced options (simplest first)

1. **Recommended: O1 + O2 now, O3 at 7.11.**
   - Guarantee: nothing in production changes, since no rows exist, and turn close loses its nested
     writer calls.
   - Cost: 2 slices, about −2,550 source and −1,600 test lines. It touches 10 files held by other lanes
     (patches) and needs the merge-cut ruling on `:seon.issue/agent`.
   - Sacrifice: the issue-worker and plan features. They are recoverable from Git; per spec, tasks
     replace them.
2. **O1 only.**
   - Cost: about −1,760 source. Leaves the issue workers and their per-close `issue/settle-call`
     (S5 trims it).
3. **Neither; only S1-S5 and D1-D5.**
   - Cost: about −130 lines. The features stay as production-dead code with a per-close writer cost.

## 4. Ordered loadable slices (slow first)

Each slice is one `git commit --only`. Before each commit:
- kondo `--cache false` over src, script and test: 0 errors;
- the contracts-compile check over the changed files;
- the schema resources still read.

Root runs the listed regressions.

| slice | content | files | est. net | regressions |
|---|---|---|---|---|
| S1 | D1: drop admit-run's redundant `changed-definition-symbols` refusal | test.clj | −3 | test/one_request_test, test/admission_digest_test, test_test |
| S2 | named select skips the observed-change walk | test.clj | ±2 | test/selection_test, test/scoped_first_run_test, cluster/publication_reuse_test |
| S3 | one query per reach round; `reach-row` keyed by digest | runner.clj | ≈ −10 | test/reach_test, test_reaching_test |
| S4 | reach cache onto `seon.cache` keyed by `program-revisions`; delete the holder atom and `reach-content` (owner ruling on the cache key) | runner.clj, cache dial resource | ≈ −50 | test/reach_test (revised), test_reaching_test |
| S5 | only if O2 is refused: `issue/settle-call` skips `exhaust-tx` without an open issue | issue.clj | −1 | issue_settlement_test |
| D2+D5 | 2-arity, privatize | runner.clj, test.clj | −1 | load |
| D4 | one refusal-read helper | test.clj, runner.clj | ≈ −20 | test/selection_test, test/runner_test |
| D3 | issue errors through `diagnostic` (skip the sites O2 deletes) | issue.clj, seon.issue.edn if shapes change | ≈ −30 | issue_test |
| O1 | delete the plan store | plan.clj, my/plan.clj, 3 schemas, tests + patches for 9 foreign files | ≈ −1,760 / −900 test | turn_test (close), render/value_test, html_views_test, cluster/agent tests |
| O2 | delete issue workers and detectors | issue.clj, issue/detect.clj, issue/opening.clj, seon.issue.edn, tests + cluster/agent.clj patch | ≈ −1,010 / −700 test | issue_test, my/program_test (breaks), cluster/agent_arming_test, namespace_agent_loop_test |

## 5. Probe forms (JVM mode, read-only, re-run before and after each slow slice)

```clojure
;; S3/S4: cold reach for 20 tests, then warm
(let [db (datahike.api/db (seon.cluster.boot/connection "default"))
      tests (vec (take 20 (datahike.api/q '[:find [?s ...] :where [_ :seon.test/sym ?s]] db)))]
  [(time (seon.test.runner/reach-digests db tests)) (time (seon.test.runner/reach-digests db tests))])
;; measured: 1,284 ms cold (reach-facts 836 ms, 98 db/q), 2.4 ms warm

;; S2: named vs incremental select
(seon.test/select {:seon.db/db db :seon.test.run/cluster [:seon.cluster/name "default"]
                   :seon.test/namespaces #{'seon.plan-test}})
;; measured: 57 ms first, 6.8 ms warm; incremental (missing basis) 1,650 ms
```

## 6. Defects noticed (no task written: plan-only mode; root to decide)

- **`plan.clj` refusal maps are scripted artifacts.** They carry literal-map lookups, for example
  `:my.plan/failed-query-item-id (get {…} :my.plan.item/id)` at `plan.clj:746`. This is a mechanical
  error-map sweep that nobody read back.
- **`runner/reach-holders` and `reach-lineage` break the CLAUDE.md cache law.** Their thresholds are code
  constants, not configuration dials. S4 fixes this.
- **The reach index holds an atom per content key, a smell under CLAUDE.md.** S4 removes it.
- **`runner/restore-live-cluster-schema!` may break a CLAUDE.md law.** It runs after every member and
  restores JVM-global registry state, which "No JVM-global test state or restoring reloaded roots"
  forbids. It is cheap (57 ms over 18 calls) and is a guard, not a feature. Needs a ruling before any
  deletion.
