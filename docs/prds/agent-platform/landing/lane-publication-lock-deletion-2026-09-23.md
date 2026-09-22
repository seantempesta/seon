---
type: landing
status: landed-on-refactor-agent-platform
created: 2026-09-23
tags: [agent-platform, publication, datahike]
---

# Publication lock deletion — audit rows 11 and 16, 2026-09-23

Audit: `docs/research/agent-platform/dependency-already-does-it-audit-2026-09-23.md`
§1 rows 11 and 16. Opus 5.5 lane taking over the stopped Codex lane of the same
name. The Codex lane's decision boundary (row 16 needed an explicit arity on
`fn/report-identities`, its option 2) is answered by the audit row itself, which
prescribes exactly that arity; it is implemented here.

## Commits

| commit | subject | paths |
|---|---|---|
| `a102a8403` | Replace the source publication lock with Datahike's expected head (row 11) | `src/seon/cluster.clj`, `src/seon/cluster/source.clj`, `resources/seon/schemas/seon.source.edn`, `test/seon/cluster/source_test.clj`, `test/seon/cluster/publication_lock_test.clj` (new) |
| `87c4228f7` | Stop fabricating a transaction report across two commits (row 16) | `src/seon/fn.clj`, `src/seon/cluster/source.clj`, `test/seon/cluster/publication_delta_test.clj`, `test/seon/cluster/publication_report_test.clj` (new) |
| `20f319608` | Digest the report regression's namespace rows (row 16 follow-up) | `test/seon/cluster/publication_report_test.clj` |

Only this lane's hunks were staged (`git apply --cached --recount` of a
hunk-selected patch for `cluster.clj`/`source.clj` in `a102a8403`, which then
held reload-per-declaration hunks; `git commit --only` for the others).

## Row 11 — what changed

Deleted from `seon.cluster`: the `ReentrantLock` monitor, holder atom, holder
token dynamic var, phase/progress writes into the holder, acquisition bound
(`event-silence-backstop-ms`) and its timeout refusal, and the
`with-source-refresh-monitor!` wrapper around `refresh-source!`,
`publication-base!`, `source/publish!` and `source/record-results!`
(−99 lines in `cluster.clj`). The `:seon.operator.lock/*` keys were never
declared in any schema resource (`git grep operator.lock a614fb898 -- resources`
is empty), so there is no schema family to retract; the only writers were the
deleted lines (`rg 'seon\.operator\.lock' src test resources bin` is empty at
`a102a8403`).

Loser rule: both publication and test recording already call
`d/force-branch!` with `:expected-current-commit`. Datahike (gitlink
`41c79c1a70f108cf969b8c5ec6d3ba81c8835eb8`) checks the head before writing
(`reference-code/datahike/src/datahike/versioning.cljc:371-378`) and again
inside `k/update` of the branch key (`:425-437`), raising
`{:type :stale-branch-head :branch :expected-current-commit :current-commit}`.
`source/stale-publication-error` translates that once into the declared
`:seon.source/publication-error` (branch, expected commit, actual head,
`:seon.error/*` base); any other failure propagates unchanged.
`publish!` → `:seon.source/publish-result`, `record-results!` →
`:seon.source/test-recording-result`, `refresh-source!` returns the error
without adopting, `publication-base!` throws it. The initial-creation race
(no expected commit) keeps its existing `::stale-publication` refusal via
`registry/branch!`'s `created?`.

Regression `seon.cluster.publication-lock-test/concurrent-publication-refuses-the-stale-head-without-a-monitor-wait`:
two publications against one expected head; the first is paused at
"publication branch head" (after its scratch work, before `force-branch!`); the
second completes while the first is still paused (no monitor wait), then the
first refuses with `:stale-branch-head`, expected = base commit, actual = the
winner's commit; `current-src` holds the winner's rows only; no scratch branch
survives. `stale-basis-is-refused-by-the-transaction-writer` pins the
transaction writer's `:datahike/expected-basis-t` refusal
(`reference-code/datahike/src/datahike/writing.cljc:875-890`).

## Row 16 — what changed

`fn/report-identities` has two arities: the writer report, and
`(before after datoms)`; the report arity delegates. `changed-identities`
(`cluster/source.clj:213`) and the catch-up assertion in
`publication_delta_test.clj` pass the two database values and the
`d/since`/`d/history` datoms instead of a map with fabricated
`:tempids {}`/`:tx-meta {}`. `rg ':tempids|:tx-meta' src/seon/fn.clj` finds only
transaction request metadata (`:2716 :2825 :3398 :3432`), none inside
`report-identities` (`:3093-3118`), whose body reads only the two databases
and the datoms' `:e`. Other callers (`fn.clj:2277 :3399 :3435`,
`cluster.clj:1476`, `source.clj:536`) hand real writer reports and keep the
report arity.

Regression `seon.cluster.publication-report-test/writer-reports-and-their-composed-datoms-name-the-same-changes`:
for a doc change and an entity retraction, each real report gives the same
identities through both arities; composed report datoms and the retained
history datoms across both transactions both name the changed and the
retracted namespace.

## Verification

`bin/test-fast --paths test/seon/cluster/publication_lock_test.clj test/seon/cluster/source_test.clj -- seon.cluster.publication-lock-test seon.cluster.source-test`
at `a102a8403`, run `e838074339ff` (31.7 s wall): 5 errors, all fixture setup
refusing `:my.note/note` partition — the overlay `d73e0a6c` was 44 commits
behind (appended to `docs/seon/issues/test-fast-runs-on-a-published-base-older-than-heads-schema-validator.md`).

Therefore each tested commit was frozen with `git archive <commit>` into
`tmp/publication-lock-lane/head-<commit>` (reference-code linked), a base was
built there with the installed `cluster/publication-base!`, and the named
namespaces ran under `arm/initialize-contracts!` (1,699 instrumented, mode
`:panic`) with `clojure.test/run-tests` via the disposable
`tmp/publication-lock-lane/run-tests.clj`. Results were not recorded in the
source authority (the recorder refuses a checkout outside the repository);
this is not a `bin/test` gate.

| subject | namespaces | result | tests ms / wall s | log |
|---|---|---|---|---|
| `a102a8403` | publication-lock, source | 26 pass, 2 fail, 1 error; both publication-lock tests green | 8,817 / 30.05 | `tmp/publication-lock-lane/row11-fresh.log` |
| `a614fb898` files, same base | source | same 2 fail + 1 error (pre-existing) | 6,381 / 28.48 | `pre-commit-source-test.log` |
| `87c4228f7` | lock, report, delta, source | 40 pass, 2 fail, 5 error; report test errored at fixture write (definition digest) | 11,421 / 27.86 | `head-87c-tests.log` |
| `87c4228f7` + `20f319608` test | report, lock | **22 pass, 0 fail, 0 error** | 5,270 / 21.76 | `report-test.log` |
| `1d8838629` files, same base | delta | same 3 errors (pre-existing) | 6,494 / 26.78 | `delta-pre.log` |

The pre-existing reds (`source-tombstone-provenance-does-not-prevent-live-removal`,
`flat-scratch-write-refusal-retires-the-candidate`, and three
`publication-delta-test` members) are identical before and after both commits;
filed as `docs/seon/issues/fixture-namespace-rows-lack-the-required-definition-digest.md`.
Consequence: the converted catch-up assertion in
`transaction-report-identities-select-only-changed-program-rows` does not run
(fixture setup refuses first); the same history-datoms path is exercised green
by the report regression.

HEAD loads: each run above required and armed the full program at the frozen
commit. The live default (PID 51528) was not touched; no hot reload or adoption
was attempted. `clj-kondo` reported no errors on the touched files (other
errors seen in `fn.clj` came from a foreign in-flight `src/seon/program.cljc`
syntax break, not edited here).

## Schema resource proof

The row-11 commit touches `resources/seon/schemas/seon.source.edn` (adds
`:publication-error`, `:publish-result`; widens `:refresh-result` and
`:test-recording-result`). Before the 2026-09-23 owner ruling reached this lane,
a from-zero boot ran on the `a102a8403` archive:
`bin/seon --root tmp/zero-root reset --force`, exit 0, `:seon.boot/ready-ms 176798`,
wall 201.64 s; `down` exit 0; root deleted. Rows appended to
`docs/seon/issues/from-zero-boot-takes-minutes.md`.

Incremental proof per the ruling (`tmp/publication-lock-lane/incremental-probe.clj`,
scratch store published at `87c4228f7`): replacing `seon.source.edn` with its
`a614fb898` version (retiring `:seon.source/publish-result` while `publish!`
still declares it) and calling
`(seon.cluster/refresh-source! "tmp/root" ["resources/seon/schemas/seon.source.edn"] nil dir)`
refused in 5,636 ms with
`{:type :malli.core/invalid-schema :data {:schema :seon.source/publish-result}}`;
nothing published — restoring the resource returned the unchanged commit
`6ab2eac7-442e-513b-9b55-dc5ef9292161`, `:seon.source/built? false`, 153 ms.
The refusal names the member but not the surviving contract and is not the 1.3e
typed refusal: publication defect filed as
`docs/seon/issues/retiring-a-contract-referenced-schema-member-refuses-as-raw-malli.md`.

## Timings (every operation over 1 s)

| operation | wall | phases / note |
|---|---|---|
| from-zero boot, `a102a8403` archive | 201.64 s | ready-ms 176,798 — defect, from-zero-boot issue row |
| `publication-base!` export over booted root | 39.10 s | JVM start + require + export — defect, same issue |
| `publication-base!` over empty root, `87c4228f7` | 166.04 s | full index + export — defect, same issue |
| `bin/seon export` (live root) | 0.49 s | |
| `bin/test-fast --paths`, run `e838074339ff` | 31.7 s | fixture refused — defect, filed |
| focused armed test runs (five) | 21.8–30.1 s | tests 5.3–11.4 s; the rest JVM + arming — filed `a-focused-test-jvm-spends-twenty-seconds-before-its-first-test.md` |
| incremental refresh, retire attempt | 5.64 s | inside an 18.5 s JVM |
| incremental refresh, restore | 0.15 s | |

## Cleanup

The Codex lane's worktree `tmp/publication-lock-wt` was removed (symlinks
unlinked first) and `tmp/publication-lock-root` deleted; no live holders.
Snapshot directories deleted the same way. Retained: `tmp/publication-lock-evidence/`
(Codex lane evidence) and `tmp/publication-lock-lane/` logs and scripts.

RESET NEEDED: no.

## Review follow-up — commit `874918765`, datahike fork `fbd1ad2d`

Review: `docs/research/agent-platform/review-publication-lock-deletion-2026-09-23.md`.
Every finding was checked against source before editing; none was falsified.

| finding | verified at | disposition |
|---|---|---|
| P1-1 analysis loses its expected head | `full-source-refresh!` omitted `:seon.source/expected-commit-id`; `publish!` defaulted to a reread head | **fixed**: the captured commit is passed; `publish!` refuses a moved head before scratch work with `publication-error` (Datahike's `:stale-branch-head` shape). Initial creation (no publication captured) passes no expectation, as before |
| P1-3 success returns another writer's commit | `publish!` reread `registry/branch-commit-id` after `force-branch!` | **fixed at the dependency**: fork `fbd1ad2d` makes `force-branch!` return the commit it installed and verified under the roster permit (`versioning.cljc` readback, spec `:ret :uuid`, fork regression asserts it equals the branch head). `publish!` returns that commit; initial creation returns the scratch commit `registry/branch!` installed |
| P1-4 operator init narrows the refusal | `boot.clj` `select-keys` on the result | **fixed**: refusal returned whole; `seon.operator.edn` `:response` names `:seon.source/publication-error` |
| P1-2 adoption not serialized/guarded | confirmed | **partly fixed** (see below) |
| P2 publication-base! consistency | confirmed | **fixed**: export, manifest and provenance name the refresh's own commit; after `export/export!` the head is compared and a move refuses by name. The export still copies the live store (no commit-scoped export exists) — a moved head is detected, not prevented |
| P2 regression cannot prove the contest | confirmed | **fixed for publication**: new `both-admitted-publications-contest-the-guarded-update-and-return-their-own-head` holds both publishers at `publication branch head` after scratch work, releases them together: exactly one winner returning the installed head, one typed loser. Adoption contest not covered (no development instance in the fixture) |
| P2 recorder obscures refusal / missing contracts | confirmed | **fixed**: `record-snapshot!` keeps the stale-head refusal (with `:seon.source/refused-test-run`); `full-source-refresh!` and `commit-persistent-results!` declare input and output |

P1-2 now: development adoption refuses before its first write when
`current-src` no longer names the published commit, and its record transaction
carries `adoption-guard-tx`, which in the writer requires the cluster's adopted
commit to be the one it started from and `current-src` to still name the
adopted commit. This closes the reviewer's exact schedule (A publishes and
pauses; B publishes and adopts; A resumes — A refuses at admission, or at its
record). **Remaining gap:** two adoptions admitted at the same head can still
interleave their row writes (`declaration-changes`, `seon.fn/index!`,
`seon.issue/adopt!`) and JVM reloads; the loser's record refuses, but a stale
row written after the winner's is not undone. Three options for the
coordinator:

1. Guard every adoption transaction with the same writer-side head check
   (recommended): thread `adoption-guard-tx` as leading tx data into
   `index!` (a declared optional index-request member), `declaration-changes`
   and `issue/adopt!`. Guarantee: no stale adoption write commits after a newer
   publication; the transactor orders the rest. Cost: one declared member in
   `seon.fn` index-request, edits to `fn.clj` and `issue.clj` (currently held by
   other lanes), ~1 h. Gives up: nothing for rows; JVM reload order stays
   last-writer (both reload current files).
2. Adopt only at the evaluation boundary through one per-cluster admission
   (the plan's target: loaded namespaces advance between evaluations). Guarantee:
   one adoption at a time by construction. Cost: design across the flow/turn
   owners; hours. Gives up: immediate hook-time adoption.
3. Accept the residual and repair on the next request: after a refused record,
   the next adoption computes its delta from the recorded commit. Guarantee:
   none for identities the stale write clobbered that the next delta omits.
   Cost: nothing. Gives up: correctness under concurrent editors.

Verification (`git archive` of HEAD `9d029820d` plus exactly this commit's
hunks, fork at `fbd1ad2d`, base from `cluster/publication-base!`, armed
contracts, disposable `run-tests.clj`): `seon.cluster.publication-lock-test`
(5 tests incl. the three new), `seon.cluster.publication-report-test`,
`seon.cluster.source-test` — 11 tests, 49 pass, 2 fail, 1 error; the three reds
are the pre-existing `source-test` pair filed in
`fixture-namespace-rows-lack-the-required-definition-digest.md`. Fork
regression `datahike.test.versioning-test/guarded-force-rejects-a-stale-head`
green (8.9 s JVM). The staged `cluster.clj`/`boot.clj` differ from the tested
snapshot only by other lanes' hunks committed to HEAD meanwhile (`04ceaf4ad`
and predecessors); this lane's hunks are byte-identical.

Measurement clock: `docs/prds/steward-platform/research/measure-publication-path-2026-09-22.sh`
cannot run under the current rules: it creates a `git worktree` and pays a
from-zero `bin/seon start` on a fresh root before any adoption clock, both
ruled out (no worktrees; no scratch boot, owner 2026-09-23). No clock row is
claimed. The script needs a mode that clocks publication and adoption against
an already-held root.

**Default needs a JVM restart** for the datahike gitlink bump (`41c79c1a` →
`fbd1ad2d`); the running default loaded the old `force-branch!` (returns nil),
and `publish!` in new source would read nil as its commit. RESET NEEDED: a
default JVM restart after this commit is adopted (orchestrator).

### Follow-up timings

| operation | wall | note |
|---|---|---|
| fork versioning test JVM | 8.9 s | |
| `publication-base!` over empty root (snapshot) | 93.3 s | full index — defect, from-zero-boot issue row |
| armed focused run, 3 namespaces | 66.9 s | tests 38.9 s (three `with-store` publication tests each copy the canonical store) — defect, extends `a-focused-test-jvm-spends-twenty-seconds-before-its-first-test.md` |

## P1-2 option 1 — commit `40c9ebf5e`; resume costs — commit `7a9d33da4`

Ruling: option 1 (coordinator, 2026-09-23). Declared member
**`:seon.source/expected-head`** (`seon.source.edn`: `:seon.source/branch` +
`:seon.source/expected-commit-id`), optional on `:seon.fn/index-request`.
`seon.cluster.registry/head-guard-tx` refuses inside the transaction unless the
branch still names the expected commit in the transacting database's own
store, with Datahike's `:stale-branch-head` data. `seon.fn/index!` leads its
development reconciliation transaction with it; `development-source-refresh!`
passes it to `index!` and leads its schema declaration transaction
(`declaration-changes`) with it. Remaining site: `seon.issue/adopt!`
(`issue.clj`, held by the census R-PRED batch): the exact change is to lead
both `db/transact!` calls in `adopt!` (`src/seon/issue.clj:947,954` at
`40c9ebf5e`) with `[:db.fn/call registry/head-guard-tx expected-head]`, the
member passed from `development-source-refresh!` as a third argument.

Proof boundary: at this HEAD the canonical fixture requires a `seon.test/run`
execution handle on a live cluster; default does not load this code (JVM
probe: `(resolve 'seon.cluster.registry/head-guard-tx)` → false), and lanes
never adopt default. The committed regressions
(`stale-adoption-row-writes-refuse-at-the-writer`,
`schema-adoption-refuses-at-the-writer-after-a-publication-moves-the-head`)
are therefore **not executed**. The same behavior was probed under armed
contracts on a scratch store built by `publication-base!` from HEAD plus this
slice (`tmp/publication-lock-lane/guard-probe.clj`, log `guard-probe.log`):
current-src moved h1 → h2 by a guarded `force-branch!` (44 ms); schema
adoption expecting h1 refused `:stale-branch-head`, basis unchanged, attribute
absent (13 ms); expecting h2 accepted, attribute installed (90 ms);
`index!` of `seon.schema/registration-delta-form` expecting h1 refused,
basis unchanged (947 ms); expecting h2 converged (6,999 ms).

Resume costs (`lane-resume-in-seconds-2026-09-23` (e) and the seed finding),
probe `resume-probe.clj`: `accrete-schema-population!` with the branch's own
carried projection 469 ms, no transaction (that lane measured 3,240 → 333 ms);
`seed-root-agent!` first 9,668 ms (creation, cold), resume 6 ms, basis unchanged.

| operation | wall | note |
|---|---|---|
| `publication-base!` over empty root (snap3) | 127.5 s | defect, from-zero-boot class |
| guard probe JVM | 45.7 s | JVM + arming; `index!` of one identity 7.0 s — defect |
| resume probe JVM | 41.6 s | first root seed 9.7 s — defect |
| attempted armed test run (snap3) | 29.6 s | all 13 refused: fixture needs a `seon.test/run` handle |

## P1-2 last site — commit `3c55bb0f6`

`seon.issue/adopt!` has a fourth arity taking `:seon.source/expected-head`;
its write is led by `registry/head-guard-tx`. Regression
`seon.issue-head-guard-test/stale-issue-adoption-refuses-at-the-writer`
(not executable at this HEAD, as above). Probe `issue-probe.clj` under armed
contracts on a `publication-base!` scratch store: stale adoption threw
`:stale-branch-head` (expected h1, current h2), basis unchanged, 706 ms;
current-head adoption installed the issue, 8,209 ms. Base build 108.6 s,
probe JVM 34.7 s.

**Owed hunk in `cluster.clj`** (held by lane publication-work), in
`development-source-refresh!`:

```clojure
-             ((requiring-resolve 'seon.issue/adopt!) connection published-database issue-identities)
+             ((requiring-resolve 'seon.issue/adopt!) connection published-database issue-identities
+              expected-head)
```

`expected-head` is already bound in that `let` (commit `40c9ebf5e`).
