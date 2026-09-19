---
type: research
status: complete
created: 2026-09-19
tags: [review, test-system, selection, runner]
---

# Review: the uncommitted Stage 1 slice, and the distance to the owner's test-system target

Read end to end before writing: the
[test-system PRD](../plan/test-system-is-the-database-prd-2026-09-17.md),
the [stage 1–3 design](../plan/test-system-stage1-3-design-2026-09-17.md),
[stage 0](test-system-stage0-design-2026-09-17.md),
[stage 1](test-system-stage1-2026-09-17.md),
[stage 2](test-system-stage2-2026-09-17.md),
[reach digest](reach-digest-2026-09-16.md),
[selection efficiency](selection-efficiency-2026-09-17.md), and
[AGENTS.md](../../../../AGENTS.md) §5. Then `src/seon/test.clj`,
`src/seon/test/selection.clj`, `src/seon/test/runner.clj`,
`src/seon/test/cache.clj`, `src/seon/test/fast.clj`, `src/seon/fn.clj`,
`resources/seon/schemas/seon.test*.edn`, `bin/test`, `bin/test-fast`.

Read-only lane: no JVM, no gate, no edit. Every line number below is the
working tree at the time of review (`git status` snapshot of the session);
`git show HEAD:<path>` was used where the claim is about HEAD.

## 0. What is already at HEAD, and what the uncommitted slice actually is

A large part of what the PRD calls Stage 1 and Stage 3 **already landed**, and
the surviving uncommitted diff is smaller than it looks:

- The **fact model** is committed: `resources/seon/schemas/seon.test.run.edn:21-29`
  and `:88-97` declare `cluster`, `input-digest`, `policy`, `change-basis-t`,
  `members`, `covered-by`, `selection-tx`; `seon.test.member.edn` and
  `seon.test.report.edn` exist.
- The **writer transactions** are committed: `seon.test.runner/admit-run`
  (`src/seon/test/runner.clj:2298` at HEAD carried the full body),
  `claim-member` (`:2372`), `complete-members` (`:2564`), `record-tx`
  (`:2853`), `reusable-result` (`:2885`), `commit-results!` (`:2974`).
- The **union reverse walk** is committed: `seon.fn/gate-sets`'s request-map
  arity (`src/seon/fn.clj:1439-1451`, commit `fe2f1e816`), one frontier and
  one seen set over all seeds.
- The **symbol edges** are committed: `:seon.fn/calls`
  (`resources/seon/schemas/seon.fn.edn:31`), `:seon.fn/references` (`:50`),
  `:seon.test/reach` (`resources/seon/schemas/seon.test.edn:2`) are all
  `[:set {:seon.db/index true} :seon.program/edge-symbol]`.
- The **publication input digest** the stage-1 note reported as owed is
  committed: `resources/seon/schemas/seon.source.edn:12`,
  `src/seon/cluster/source.clj:496`, `:577`, `:703`,
  `seon.test.cache/test-input-digest` (`src/seon/test/cache.clj:175`).
- `seon.test.fast` has **no selection copy** at all
  (`src/seon/test/fast.clj:14-33` takes explicit namespaces only), so the
  design's "delete fast's membership copy" item is already moot.

The uncommitted slice is therefore: the `select` function and its helpers,
the relocation of `admit-run` from the runner to `seon.test`, the wiring of
`check` to that owner, the new `seon.test.selection.edn` schema, one shell
inventory addition in `bin/test`, and one stale coordinator adapter.

## 1. The Stage-1 diff, hunk by hunk

### H1 — `bin/test:721-726`, `:784-792`, `:837-861`: snapshot input inventory

Copies `test-input-paths.txt` into a worker checkout, writes the immutable
`git ls-tree -rz` inventory of the archived commit plus overlay paths into
the run root, and excludes that generated file from the two
`ls-files --others` drift checks.

**SOUND.** It is the only file-side evidence the design's "Inputs outside the
program graph" section permits, it is derived from the exact archived commit
rather than the live index, and the consumer (`seon.test.cache`) is already
committed. NUL delimiters preserve paths. No test covers it directly; its
consumer is covered by `seon.test.selection-test/changed-inputs-are-decided-by-content-not-modification-time`
(`test/seon/test/selection_test.clj:239-...`), which runs on a real temp git
repository, not a fixture stand-in.

**Recommendation: FINISH** (0 remaining hours — it is complete as written).
Gate: `bin/test --paths bin/test -- seon.test.selection-test`.

### H2 — `bin/test:760-762`: `seon.test.selection` → `seon.test.cache` in the bb preamble

Moves the babashka `source-inputs`/`input-digests` call to the cache owner.

**SOUND**, and required: `seon.test.selection` is now a two-function shim
(`src/seon/test/selection.clj:26-48`) delegating to `seon.test.cache`.
**FINISH** (0 hours).

### H3 — `src/seon/test.clj:560-570` `selection-facts`

`(db/q '[:find ?entity ?attribute ?value :in $ [?attribute ...] :where
[?entity ?attribute ?value]] database attributes)` reduced into a nested map.
The caller (`src/seon/test.clj:650-676`) passes **35 attributes**, including
`:seon.fn/sym`, `:seon.fn/ns`, `:seon.fn/file`,
`:seon.program/analyzed-source-digest` — i.e. every function row in the
program graph, every file row, every run and member, materialised into one
in-memory map on every `select` call. `select` then walks `(keys facts)`
five separate times (`:678`, `:682-685`, `:688`, `:697`, `:795-802`),
including a `doseq` that refuses if **any** function or test row lacks an
analysis digest (`:680-685`).

**HALF-BAKED.** This is the exact shape the
[selection-efficiency research](selection-efficiency-2026-09-17.md) rejected:
that note's whole finding is that AVET frontier traversal from the changed
seeds is the mechanism (14.181 ms worst seed) and that anything scanning the
population is the wrong half. The change half was measured at 24–25 ms with
`since`; this hunk adds an unbounded whole-graph scan *in front of* both. It
is also a fetch-at-call-time projection rebuild (AGENTS.md §2.1's named
performance killer). The stage-1 landing note's own measurements
(15/15/21 indexed reads, 39–104 ms) are measurements of the **gate-sets walk
only**, on a fixture population — they are not measurements of `select`.

**Recommendation: RESET this hunk** (keep `select`, replace its acquisition).
The fresh spec must say: `select` reads (a) the changed identities through
`(db/since (db/history db) basis)` on the declared attribute list, (b) the
reverse union walk through `seon.fn/gate-sets`' request arity, (c) the
cluster's run/member rows through an entity-bounded query rooted at
`[?run :seon.test.run/cluster ?cluster]`, and (d) per-candidate eligibility
facts through indexed seeks on the selected symbols only. No query whose
`:where` is a bare `[?e ?a ?v]` over a population attribute. It must also
state that "every row has an analysis digest" is a **publication** invariant
checked at publication, not a per-selection O(population) sweep — otherwise
every gate pays for it. Measure and record the before/after.
Lane-hours: **6–10**.

### H4 — `src/seon/test.clj:572-612` `changed-definition-symbols`

Two history/since queries returning changed function and test symbols,
including namespace binding rows mapped to their definition symbols.

**SOUND.** It reads both additions and retractions, joins eids to identities
in `$history` rather than the current db (so a retracted identity still seeds
its old symbol), and covers the attribute list the design names
(`:seon.fn/source`, `/spec`, `/calls`, `/references`, `/sym`,
`:seon.test/sym`, `/source`, `/subject`, `/platform`, `/fixture`,
`/fixture-observation`, `/long`, `:seon.schema.admission/source`).
Covered by `selection_test.clj:137-146` (spec and reference edits) and
`:150-159` (deletion and recreation under a new eid — asserted positively
against exact expected symbol sets).
**FINISH** (0 hours).

### H5 — `src/seon/test.clj:613-809` `select`

The stage-1 owner. Refuses without an explicit cluster (`:626`), validates
the installed edge schema is indexed symbol-many (`:643-648`), derives
eligibility, derives the last completed and last green run from member
terminal facts (`:734-749`), derives outstanding obligations since that green
(`:758-766`), computes changed symbols, calls the union walk (`:775`), and
returns members with reason sets.

**SOUND in shape, HALF-BAKED in two places.**

Sound: the reason enum matches the design (`:platform`, `:reaches-changed`,
`:named`, `:first-run`); the green basis is *derived* from run/member facts
rather than read from `tmp/test-basis/green-basis.edn`; the widening value
names which of `:missing-basis`/`:removed-file`/`:outside-program-graph`
applied; the zero-member unchanged green case is implemented and asserted
(`selection_test.clj:108`); an open admission does not discharge an
obligation (`:135`); input-digest change forces a `:first-run` widening
(`:163-168`).

Half-baked 1 — **convergence**: `complete?` requires
`completed-tx` **and** `terminated-tx` **and** three counts on **every**
member of the run (`:734-739`), and `green?` additionally requires
`pass-count` positive (`:740-748`); `last-green` is the last run all of whose
members are green (`:749`). `check-in-process` admits members it then deliberately
never executes: `deferred` (`:1248-1258`), `destructive` (`:1268-1285`) and,
on a platform red, everything after the failing platform test (`:1393-1397`).
Those members never receive a `completed-tx`, so their run never enters
`completed`, so `last-green` stays nil, so `first-run?` is true forever and
every subsequent request selects the full eligible set. The design's headline
promise — "repeat unchanged and observe zero executions" — therefore does not
hold through the only host that is wired up. A deliberate non-execution needs
its own terminal member fact so it is discharged rather than pending.

Half-baked 2 — **throw-based control flow**: `refuse!` (`:630-632`) throws
`ex-info` and the whole body is wrapped in `try`/`catch ExceptionInfo`
(`:808-809`). It is contained at the boundary and returns a flat
`:seon.error/value`, so it does not violate the error law outwardly, but a
200-line single `let` with eleven `_` binding side effects is the reason the
convergence hole above is hard to see. Not a blocker; note it for the rewrite
in H3.

**Recommendation: FINISH** (H5 keeps its logic; H3 replaces its reads).
Remaining work: a terminal member fact for deliberate non-execution
(deferred / destructive-excluded / platform-blocked), and one regression that
runs the same green rerun assertion **through `check`** rather than through
`select` alone. Lane-hours: **5–8**.

### H6 — `src/seon/test.clj:811-844` `selection-seeds` + `:846-855` `reaching`

Replaces `identity-tests`/`changed-reach` with one seed resolver feeding
`gate-sets`' union arity. Refuses an unknown identity with
`:seon.test/identity-unresolved` while accepting a symbol that survives only
as an incoming `:seon.fn/calls`/`/references`/`:seon.test/subject` edge in
history (`:826-833`) — exactly the design's "deleting B still selects tests
of A calling B".

**SOUND.** Covered positively at `selection_test.clj:112-114` (exact expected
set) and `:219-220` (a fileless SCI test is reached from `seon.id/id`).
**FINISH** (0 hours).

### H7 — `src/seon/test.clj:856-874` `selection-admission`, `:875-881` `check-request-admission`, `src/seon/test/runner.clj:2290-2296` `worker-request-admission`

Both hosts call the same function; the runner's entry is a
`requiring-resolve` shim.

**SOUND as a seam, HALF-BAKED as a proof.** The regression
`test/seon/test/runner_test.clj:24-33`
(`selection-is-one-function-on-both-hosts`) compares
`runner/worker-request-admission` with `seon-test/check-request-admission` —
but both are one-line calls to `seon.test/selection-admission`
(`src/seon/test/runner.clj:2296`, `src/seon/test.clj:881`). The assertion
`(= (stable worker) (stable check))` is true by construction and would remain
true if the worker never ran a test. It is the design's named standing
regression in name only. It does at least re-run the whole
`exercise-selection!` body against the shim, so it is not worthless — but it
does not prove "on both hosts".

**Recommendation: FINISH** — replace the tautological comparison with the
design's actual acceptance: admit through the worker entry, execute in
process with worker custody, assert the counts land on that run's member.
Lane-hours: **3–5**.

### H8 — `src/seon/test.clj:942-1040` `admit-run` (moved from the runner)

The HEAD body moved verbatim from `src/seon/test/runner.clj:2298` into
`seon.test`, with `(:seon.error/kind x)` replaced by `(error/error? x)`,
`program-digest`/`schema-database` requalified, plus **two new checks**:
a refusal when `changed-definition-symbols` is non-empty since the tested
basis (`:1003-1005`), and a refusal unless exactly one
`:seon.source/test-input-digest` exists and equals the request's
(`:1006-1011`). The runner's `admit-run` becomes a `requiring-resolve` shim
(`src/seon/test/runner.clj:2298-2303`).

**SOUND**, with one caveat: `seon.test.runner` is required by `seon.test`
(`src/seon/test.clj:13`), so the reverse direction must be
`requiring-resolve`; that is a cycle worked around rather than dissolved.
The design assigns admission to the runner ("Extend `runner/record-tx` with
phase-specific admission through `runner/admit-run`"), so this hunk **moves
the owner in the opposite direction from the design** without saying so. The
existing regressions still call `runner/admit-run`
(`test/seon/test_test.clj:255`, `test/seon/test/runner_test.clj:209`), so they
exercise the shim — fine, but the duplication is real.

**Recommendation: FINISH**, and in the same commit either (a) state in the
design that `seon.test` owns admission and delete the runner shim once its
callers migrate, or (b) move it back. Do not leave two spellings.
Lane-hours: **2–3**.

### H9 — `src/seon/test.clj:1201-1231` `check-admission` + `:1233-...` `check-in-process` rewrite

`check-admission` maps paths to definition symbols, calls `reaching`, and
hands the **result of that walk** to `select` as
`:seon.test/identities` with `:seon.test.run/policy :named` (`:1227`).
`check-in-process` then admits the selection to the writer before execution
(`src/seon/test.clj:1291-1294`) and orders platform members first
(`:1337-1342`), stopping on a platform red (`:1393-1397`).

**HALF-BAKED.** The admission-before-execution and the platform-first ordering
are sound and are what the design asks for. But the reach walk is done
*twice* and *outside* the selector: `check-admission` runs `gate-sets`, then
hands the answer to `select` as explicit identities, which makes every check
request a `:named` request. Consequences, all verifiable by reading:

1. No check ever produces a `:reaches-changed` reason; every member is
   `:named`.
2. The incremental baseline, the pending-obligation union and the
   zero-member green path in `select` (`:764-793`) are **dead on this path**
   — a `:named` request selects its names regardless of freshness.
3. The **long-test exclusion is gone**. `select`'s eligibility keeps a long
   test when `(named? entry)` (`src/seon/test.clj:699`), and every reached
   test is "named" here, while `long-excluded` is hard-coded `[]`
   (`src/seon/test.clj:1259`). The deleted guard's own comment stated why it
   existed: one declared-long test spends the whole
   `:seon.test/check-time-limit-ms` the selection shares. The edit hook's
   `check-adoption` (`src/seon/test.clj:1495`) is the caller that now pays it.
   The `:seon.test/long-excluded` reporting key is dead code.

The design says the opposite: "Fold `identity-tests`, `changed-reach`,
`reaching`, and namespace membership into the one selection derivation."

**Recommendation: FINISH** — `check` hands `select` its **changed
identities**, not their reach; `select` does the walk. Restore long exclusion
either as a `select` eligibility rule that is not defeated by `:named`, or as
an explicit `:seon.test/long-excluded` report at the check boundary.
Lane-hours: **4–6**.

### H10 — `src/seon/test.clj:1425-1440` `check` requires an explicit cluster

`check` now refuses `:seon.test/cluster-required` when no cluster is named,
instead of defaulting to `"default"`.

**SOUND** — it is ruling E1 ("the cluster is explicit, no magic"). Note it is
an input **narrowing**, i.e. breakage under AGENTS.md §2.5: `my.test/check`
(`src/my/test.clj:24-28`) only supplies a cluster when the agent's
environment carries one, so an agent whose environment lacks
`:seon.boot/cluster-name` now gets a refusal where it previously got a run.
`check-adoption` (`src/seon/test.clj:1495`) always supplies one.
**FINISH** — state the narrowing in the commit message and confirm the agent
environment always carries the cluster name. Lane-hours: **1**.

### H11 — `src/seon/test/runner.clj:3275-3304` `bulk-selection`

**DEAD, and actively broken.** This hunk dates to the earliest preserved WIP
(`tmp/orchestrator/worktree-patches/test-system-stage1-wip-2026-09-17.patch:382`)
and was never revisited when `select`'s contract changed. Three independent
proofs:

1. It calls `seon.test/select` with `:seon.test/changed`, `:seon.test/paths`,
   `:seon.test.selection/removed`, `:seon.test.selection/basis-known?` and
   **no** `:seon.test.run/cluster` (`:3286-3292`). `select`'s first branch
   (`src/seon/test.clj:617`) refuses `:seon.test/cluster-required` when the
   cluster is absent, and `bulk-selection:3293-3294` then throws
   `ex-info "Test selection refused."`.
2. It reads `(:seon.test/tests selected)` (`:3297-3299`). `select` never
   returns that key — `:seon.test/tests` is a **check-result** key
   (`resources/seon/schemas/seon.test.edn:263`, produced at
   `src/seon/test.clj:1306`). `select` returns
   `:seon.test.run/members`.
3. It builds the answer inside
   `((requiring-resolve 'seon.test-support/with-database) …)` (`:3291`) —
   the coordinator of a real gate creating the **canonical in-memory test
   fixture** and selecting the checkout's gate membership from that fixture
   population. Even if (1) and (2) were fixed, the population is wrong by
   construction, and production code would depend on a test-support helper.

Blast radius: `selection_mode` is `changed` for any `bin/test` with no named
namespaces (`bin/test:290`), so **bare `bin/test` is broken in this working
tree**. `--all`, `--full`, `--platform` and explicit-namespace runs take
other branches (`src/seon/test/runner.clj:3279-3281`,
`:4379 (when-not explicit? …)`) and are unaffected — which is why the
orchestrator's `bin/test --paths … -- <ns>` and `--platform` still work and
nobody noticed.

**Recommendation: RESET.** Shelve this hunk; restore the HEAD body of
`bulk-selection` (`git show HEAD:src/seon/test/runner.clj`) so bare
`bin/test` works, and write a fresh spec for the shell path. That spec must
say: the coordinator does **not** select; `bin/test` publishes the snapshot,
then evaluates `seon.test/selection-admission` **once, in the holding JVM
against the named cluster's real connection**, reads the admitted members
back, and launches workers with `(run-id, worker-index)`. It must forbid any
`requiring-resolve` of `seon.test-support` from `src/`. Lane-hours: **1** to
revert, **8–12** for the replacement (this is the Stage-3 launcher work).

### H12 — `src/seon/test/runner.clj:940-957` `unchanged?` platform suppression

Threads `::unchanged?` from `bulk-selection` so platform tests are skipped on
an unchanged bare run.

**DEAD by dependency** — its only producer is H11. Reset with H11; the
policy itself (platform members suppressed on an unchanged green bare
request) is correct and already implemented in `select` (`src/seon/test.clj:801`
`work?` guard). Lane-hours: **0**.

### H13 — `src/seon/test/runner.clj:2564-2590` `complete-members` accepts a claimless completion, and `:2996-3001` recorded-member routing

`complete-members` now tolerates absent worker/claim (the in-process host
admits without claiming), and `commit-results!` decides whether to read back
a member result by querying the run's `selection-tx` rather than by the
presence of a `claim-tx` in the completion.

**SOUND** — it is what makes stage-1 admission usable before stage-3 claims
exist, which the design explicitly requires ("Stage 3 adds claims, not the
first usable result"). Covered by `test/seon/test/runner_test.clj:170-176`
and `:277-283`, which now seed a `:seon.source/test-input-digest` on the
canonical fixture. **FINISH** (0 hours).

### H14 — `test/seon/test/selection_test.clj` (rewrite, +344/-…)

**SOUND, and the strongest part of the slice.** It runs on the canonical
fixture (`support/with-database`, `support/seed-cluster!`,
`support/transacted!` throughout), installs real program rows through
`functions/source-rows` (`:35-42`) rather than hand-written maps, evaluates a
real fileless SCI deftest through `sci.eval/evaluate` (`:198-215`), and
asserts **positive exact sets** (`expected` at `:94`, compared with `=` at
`:110`, `:139`, `:143`, `:157`, `:165`, `:174`) so two empty answers cannot
pass. Refusals are asserted by exact `:seon.error/kind`
(`:100-106`, `:159-161`, `:184-190`).

Does anything read absence as behaviour? Two assertions are bare emptiness —
`(is (= #{} (symbols (select!))))` at `:108` and `(is (empty? …))` at `:136`
— but each immediately follows a positive assertion over the same fixture in
the same body, so an accidental refusal or an empty population would have
failed earlier. Acceptable.

One genuine gap: `install-selection-program!` (`:34`) is a local helper
rather than `support/program-fn-row`; it does go through `transacted!`, so a
refused write surfaces. **FINISH** (0 hours).

### H15 — `resources/seon/schemas/seon.test.selection.edn` (untracked, 25 lines)

Declares `:seon.test.selection/request`, `/widening`, `/result`.

**SOUND.** Schema resources are discovered by classpath directory scan
(`src/seon/schema/edn.clj:24-25`, `"seon/schemas"`), so no registration list
needs editing. `/request` correctly makes `:seon.db/db` required. One
inconsistency to fix while finishing: `:seon.test.run/cluster` is
`{:optional true}` in the schema but `select` refuses without it — make it
required so the contract states the rule rather than the body alone.
**FINISH** (0.5 hours).

### Gate command for the sound parts

Once H11/H12 are reset and H3/H9's convergence holes are closed:

```sh
bin/test --paths src/seon/test.clj src/seon/test/runner.clj src/seon/fn.clj \
  resources/seon/schemas/seon.test.selection.edn bin/test \
  test/seon/test/selection_test.clj test/seon/test/runner_test.clj \
  test/seon/test_test.clj \
  -- seon.test.selection-test seon.test.runner-test seon.test-test seon.fn-test
bin/test --platform
```

### Verdict on (A)

The **selector and its regression are sound work** and should be finished,
not reset: `select`, `changed-definition-symbols`, `selection-seeds`, the
schema and `selection_test.clj` are the real deliverable and they are close.
The **shell adapter is dead and breaks bare `bin/test`** and must be reset
today. Two design-level holes — the O(population) acquisition and the
never-discharged member — are the difference between "selects correctly" and
"converges to zero on an unchanged rerun", which is the whole point.
Finish cost: roughly **22–34 lane-hours**, plus the **8–12** for the shell
replacement that H11's reset leaves owed.

## 2. The owner's target, capability by capability

> "the priority should be getting the test infrastructure moved into the final
> plan -- split between platform and then running efficiently in the runtime
> itself so we use the database to store which functions have changed and only
> re-run those even if multiple agents are requesting full runs we can then
> return the full results with confidence."

| # | Capability | State | Evidence | Stage that owns it |
|---|---|---|---|---|
| i | Platform tier isolated in worker JVMs; everything else in the runtime JVM | **PARTIAL** | Both hosts exist: in-process is `seon.test/check` (`src/seon/test.clj:1425`) and `run-owned` (`:489`); isolated workers are `bin/test`. The **split is not the one named**: `bin/test` launches worker JVMs for *every* tier, not only platform (`bin/test:1029-1055` computes `worker_count` for all modes). The in-process host does exclude declared destructive owners (`src/seon/test.clj:1268-1285`) and now orders platform first and stops on platform red (`:1337-1342`, `:1393-1397`). Regression: `test/seon/test/runner_test.clj:276` (`platform-claims-and-original-bounds-govern-bulk`). | PRD §0b + Stage 3 |
| ii | The database records which functions changed since the last green, and selection reruns only tests reaching them | **PARTIAL** | Landed: symbol edges (`resources/seon/schemas/seon.fn.edn:31`, `:50`), the union reverse walk `seon.fn/gate-sets` (`src/seon/fn.clj:1439`, commit `fe2f1e816`), `:seon.test/reach` digests (`src/seon/test.clj:1057`). Uncommitted: `changed-definition-symbols` (`src/seon/test.clj:572`) reading `since` over history, and `select` (`:613`). Missing: it is wired **only** to `check`, and `check` defeats the incremental path by sending `:named` (`src/seon/test.clj:1227`); the shell path is broken (H11). Regression: `seon.test.selection-test/selection-derives-bases-obligations-and-exact-symbol-reach` (`test/seon/test/selection_test.clj:193`). | Stage 1 |
| iii | An agent's "full run" answered from recorded evidence when nothing reaching it changed, with a stated basis and digest | **PARTIAL, and narrower than asked** | Landed for **one test at a time**: `seon.test.runner/reusable-result` (`src/seon/test/runner.clj:2885`) returns the recorded row with `:seon.test/unchanged true` and `:seon.test/recorded-basis-t`, gated on program digest, branch, absent tested-branch and exact recorded selection (`:2920-2930`). For a **batch**, `select`'s zero-member green rerun (`src/seon/test.clj:734-777`) is the mechanism and it is uncommitted, unreachable through `check`, and cannot converge while deliberate non-executions never complete (H5). No render of "these results, at this basis, at this digest" exists — the tally is still printed in the shell. | Stage 1 (selection) + Stage 4 (the confidence statement) |
| iv | Concurrent agents requesting runs share one in-flight execution and receive the same results | **PARTIAL** | Landed: `admit-run` reserves the complement and writes `:seon.test.run/covered-by` for members another run already holds (`src/seon/test.clj:1035-1055`, HEAD body in the runner); `claim-member` is the writer-side claim (`src/seon/test/runner.clj:2372`); the design's named regression `no-double-execution` exists at `test/seon/test/runner_test.clj:169`, with `platform-claims-and-original-bounds-govern-bulk` at `:276`; both use real process records and observed exit (`:209-250`, `:308-350`). Missing: **nothing in the batch path calls either**. A grep for `admit-run`/`claim-member` outside tests finds exactly one production caller, `src/seon/test.clj:1294` (the in-process check). `bin/test` still hands workers namespace lists. | Stage 3 |
| v | Results are durable facts, with blobs for oversized payloads | **LANDED** | `seon.test.member` counts and `seon.test.report` failure rows (`resources/seon/schemas/seon.test.member.edn`, `seon.test.report.edn`); blob staging at `src/seon/test/runner.clj:2466-2524` against `:seon.config.eval.result/blob-threshold`; written through `record-tx` (`:2853`) / `commit-results!` (`:2974`). Regression: the delta-recording and admitted-member checks in `test/seon/test/runner_test.clj` and `test/seon/test_test.clj:255-305`. | Stage 1/3 (done) |
| vi | `bin/test` becomes a launcher of the same functions | **MISSING** | `bin/test` is 1176 lines. It still computes the worker count in bash (`:1029-1055`), owns phase timing and formatting (`:627`, `:666`, `:680-689`), and the tally is still printed by `seon.test.runner/print-final-tally!` (`src/seon/test/runner.clj:4098`) from `finish-run!` (`:4197-4213`), with `worker-count` duplicated in Clojure at `:3319`. No `render-run` function exists. The file green basis survives: `seon.test.selection/read-basis`/`write-basis!` (`src/seon/test/selection.clj:212`, `:248`) and `record-green-basis!` (`src/seon/test/runner.clj:3306`) are all still live, although the design says stage 1 deletes them. | Stage 4 |

## 3. The shortest ordered sequence of lanes

Lane-hours are engineering estimates, not measurements.

**L0 — Unbreak bare `bin/test` (30 min, first, blocks nothing else).**
Owned: `src/seon/test/runner.clj` (`bulk-selection`, the `::unchanged?`
threading). Restore the HEAD bodies. Proof: `bin/test` with no arguments
reaches the selection announcement instead of throwing. Must land before any
gate is trusted.

**L1 — Finish the selector (8–12 h). Depends on L0 only for a clean gate.**
Owned: `src/seon/test.clj`, `resources/seon/schemas/seon.test.selection.edn`,
`test/seon/test/selection_test.clj`. Replace `selection-facts`' population
scan with seeded indexed reads (H3); make `check` hand `select` its changed
identities rather than their reach, restoring `:reaches-changed` reasons and
long exclusion (H9); add a terminal member fact for deliberate
non-execution so a run can complete (H5); make
`:seon.test.run/cluster` required in the request schema (H15). Proof:
`selection_test.clj` extended with a *through-`check`* green rerun selecting
zero, plus a recorded measurement of `select` on the canonical fixture at
1/10/100 seeds. Gate: `bin/test --paths … -- seon.test.selection-test
seon.test-test seon.fn-test`.

**L2 — Make the both-hosts regression real (3–5 h). Parallel with L3; file-disjoint from L4.**
Owned: `test/seon/test/runner_test.clj`, `src/seon/test/runner.clj`
(`worker-request-admission`). Replace the tautological shim comparison (H7)
with the design's `agent-test-runs-in-a-worker` shape: admit through the
worker entry, execute in process with worker custody, assert counts on the
member. Proof: that test, plus the fileless SCI case already in
`selection_test.clj:196`.

**L3 — Settle admission ownership (2–3 h). Parallel with L2.**
Owned: `src/seon/test.clj`, `src/seon/test/runner.clj`,
`docs/prds/steward-platform/plan/test-system-stage1-3-design-2026-09-17.md`.
Decide `seon.test` or `seon.test.runner` owns `admit-run`, delete the
`requiring-resolve` shim, migrate the test callers, amend the design in the
same commit (H8).

**L4 — The launcher calls the same functions (8–12 h). Needs L0+L1 landed; needs the platform tier green first.**
Owned: `bin/test`, `src/seon/test/runner.clj` (coordinator),
`src/seon/test/selection.clj` (delete `read-basis`/`write-basis!`),
`src/seon/test/runner.clj` (`record-green-basis!`). `bin/test` publishes the
snapshot, evaluates `seon.test/selection-admission` once in the holding JVM
against the named cluster, reads the admitted members back, launches workers
with `(run-id, index)`; workers claim through `claim-member`. Delete the bash
worker arithmetic and the file green basis. Proof: `no-double-execution` (`test/seon/test/runner_test.clj:169`) extended from the writer to the actual launcher, plus a real two-launcher gate. **Not
parallel with anything** — it touches the coordinator L2 and L3 also touch.

**L5 — The tally is a query (4–6 h). After L4.**
Owned: `src/seon/test/runner.clj` (`print-final-tally!`, `finish-run!`),
`bin/test`. Add `render-run`; `bin/test` prints it and exits from the run
verdict. This is where capability (iii)'s **confidence statement** actually
gets written: "N members, basis `:t`, program digest D, input digest I, M
reused from run R". Proof: the tally equals `(render-run db run-id)`.

**L6 — Split the hosts as the owner described (6–10 h). After L4.**
Owned: `bin/test`, `src/seon/test/runner.clj`. Make the isolated worker tier
**platform and destructive only**, and route everything else to the cluster
JVM through the in-process host. This is the capability (i) the tree does not
have; it is also the largest behavioural change and should not start before
L4 makes the launcher a launcher.

Parallelism: L2 and L3 can run together. L1 must precede L4. L4 must precede
L5 and L6. L0 precedes everything.

## 4. Where the binding design contradicts the owner's sentence

**A "full run" always executes.** The owner says "even if multiple agents are
requesting full runs we can then return the full results with confidence".
The stage 1–3 design says the opposite, twice:

- "Explicit all/full/platform requests retain their meaning and do not become
  silent incremental aliases."
  (`docs/prds/steward-platform/plan/test-system-stage1-3-design-2026-09-17.md`,
  "Request, basis and result contracts").
- "For an unchanged, previously green **bare** request, return zero members"
  — the no-op policy is scoped to *bare* only ("Platform/no-op policy,
  explicitly").

AGENTS.md §5 carries the same scoping: "An unchanged, previously green **bare**
request returns recorded evidence and executes zero tests", while "`--all`
adds every non-long test". And the PRD's acceptance sequence tests the
unchanged case only for the incremental command.

The code follows the design, not the owner: `select` (`src/seon/test.clj:777`)
computes `work?` as `(or first-run? (seq changed) (seq pending) (not= :incremental policy))`,
so any `:all`/`:full`/`:named` policy is unconditionally work, and
`:712` filters candidate runs to `(= :incremental (one % :seon.test.run/policy))`,
so an `:all` run can never establish or consume a baseline at all.

This is a real decision, not a wording slip: "return the full results with
confidence" means a full request must be answerable from recorded member
facts when the program digest, the input digest and the change basis all
match, and must state that it did so. Two further contradictions follow from
the same root:

- **`covered-by` covers only concurrent, in-flight runs.** `admit-run`
  reserves against runs with a matching digest that already hold the member
  (`src/seon/test.clj:1035-1055`), which answers "two launchers at once" but
  not "an agent asks an hour later and nothing changed". The owner's sentence
  is about the second case.
- **Reuse exists for one test, not for a set.** `reusable-result`
  (`src/seon/test/runner.clj:2885`) requires `(= #{test-symbol} (set selected))`
  (`:2929`) — a recorded run of many tests explicitly does **not** satisfy a
  single-test reuse request, and there is no batch equivalent.

**Recommendation:** amend the design so policy `:all`/`:full` is an
*eligibility scope*, not an execution promise: any member whose recorded
green evidence matches the current program digest, input digest and basis is
answered from the record and reported as `:unchanged` with its basis and
digests, regardless of policy; only the complement executes. That is one
sentence in the stage 1–3 design's "Platform/no-op policy" section, one in
AGENTS.md §5, and it removes the `(= :incremental …)` filter at
`src/seon/test.clj:712` and the `(not= :incremental policy)` term at `:777`.
It should be ruled before L1 lands, because L1 is where both lines are
written.

### One further inconsistency worth naming

The design states that stage 1 **deletes** `record-green-basis!`,
`reaching-selection`, `requested-changed-paths` and `selection.clj` after
relocating cache fingerprinting. None of that happened: `selection.clj`
still exists (`src/seon/test/selection.clj:212`, `:248` hold the file-backed
green basis), `record-green-basis!` is still called
(`src/seon/test/runner.clj:3306`), `requested-changed-paths` still feeds
the broken `bulk-selection`, and the file basis still has its own live
regression, `a-recorded-green-basis-round-trips`
(`test/seon/test/selection_test.clj:318`). The derived green basis in `select` and the file
green basis therefore **coexist** — two mechanisms for one fact, which
AGENTS.md §2.5 forbids. Deleting the file path is part of L4, not optional.
