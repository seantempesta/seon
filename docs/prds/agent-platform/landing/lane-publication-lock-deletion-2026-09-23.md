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
