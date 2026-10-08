---
type: research
status: landing
date: 2026-10-08
lane: led-cacheb
---

# led-cacheb: one reach cache shared by all branches (LR-CACHEb)

What exists: `seon.test.runner/reach-declarations` (and `reach-graph`) are cached in `seon.cache` under
`reach-revision-key`, which was `[connection-id generation conservative-revision (reach attribute revisions)]`.
Datahike's revisions only compared inside one connection generation, so every agent branch derived the
whole-program declaration read again (task `seon.test.runner/reach-declarations-misses-on-every-branch`).
The approved design (OD-Q17, option 2 of `../led-cache/reach-key-notes.md`) makes revisions name commits
across connections and resolves a branch's revisions through its source's.

## The change

Datahike fork (`reference-code/datahike`, `86c3301a`, pushed to `seantempesta/datahike`
`seon/identity-join-first`):

- `connector.cljc` connect: the opening commit is the conservative revision.
- `db.cljc` `advance-cache-context`: a conservative advance (unknown attributes, schema attribute, merge)
  clears the attribute revisions.
- `connector.cljc` `deref-conn` (non-streaming writers only): a fetched commit other than the context's
  becomes the conservative revision and clears the attribute revisions. Before, that path kept the old
  revisions under a new commit id, which is wrong across connections and also within one.

Invariant: on every committed value, an attribute's revision, else the conservative revision, names a commit
whose datoms of that attribute (any but `:db/txInstant`) equal the value's. Datahike's own query cache still
compares within one connection generation (`query.cljc` `source-context-unchanged?`); its behaviour is
unchanged because a conservative change already invalidated every entry the cleared revisions could match.
Regression: `datahike.test.cross-connection-revision-test` (2 tests, 8 assertions).

Seon:

- `seon.db/remember-revisions!` records a committed value's revision context under its commit id in the
  `seon.cache` kind `:seon.db/commit-revisions` (first value wins; correct for any value of that commit).
- `seon.db/content-revisions` maps attributes to the commit whose content they equal: the value's own
  revision, else its conservative commit resolved through that commit's recorded revisions, recursively.
  Work: O(attributes × lineage depth) map lookups, no reads.
- `seon.cluster.registry/branch!` takes an optional `:seon.db/db` (the source value) and records it before
  `datahike.api/branch!`; `seon.cluster.agent/acquire-context!` and `my.agent/create-branch!` pass it.
- `seon.test.runner/reach-revision-key` becomes `(db/content-revisions database reach-attributes)`: no
  connection id, no generation.

Which step of the one happy path: the agent's `my.test/check` (and, with F4, the tests that run after every
code-changing pass). What it deletes: the per-branch whole-program declaration read (one derivation per
program instead of one per branch). Existing owner reused: Datahike's writer advances the revisions; Seon adds
no tracker.

## Measurements

Probe `test/seon/led_cacheb_probe_test.clj` (disposable, not committed): in a throwaway JVM
(`bin/seon check … --include-long`), three new agents each do `(+ 1 2)` (branch creation), then MCP SCI
`(my.test/check {:seon.test/changed ['seon.id-test/identity-is-a-function-of-the-values-structure]})` as agent
a (cold), a again (warm), b and c (new branches of the unchanged program).

Parent (`89d678ca3`, fork `e5453d96`):

| pass | wall ms | allocated | armed calls | reach-declarations | reach-graph |
|---|---:|---:|---:|---|---|
| a, cold JVM | 715 | 1.27 GB | 178k | 1 miss (309 ms) | 1 miss, 3 hits |
| a, warm | 97 | 111 MB | 41k | - | 1 hit |
| b, new branch | 349 | 441 MB | 155k | 1 miss (72 ms) | 1 miss, 3 hits |
| c, new branch | 338 | 435 MB | 159k | 1 miss (77 ms) | 1 miss, 3 hits |

Fork plus recording, runner key not yet changed (HEAD `75aa6990b` with the `seon.db`, registry and agent
edits; a check records one `:seon.db/commit-revisions` entry per member branch):

| pass | wall ms | allocated | armed calls | reach-declarations |
|---|---:|---:|---:|---|
| a, cold JVM | 799 | 1.28 GB | 181k | 1 miss |
| a, warm | 87 | 80 MB | 41k | - |
| b, new branch | 465 | 450 MB | 156k | 1 miss |
| c, new branch | 389 | 446 MB | 161k | 1 miss |

Live default after the restart on `75aa6990b`: the cluster value carries a conservative revision (its opening
commit) beside 97 attribute revisions, as the fork now promises.

Change with the runner key (`ead65e695`, measured at HEAD `9f43acf5e`, which also carries another lane's
konserve bump; throwaway root):

| pass | wall ms | allocated | armed calls | reach-declarations | reach-graph |
|---|---:|---:|---:|---|---|
| a, cold JVM | 745-918 | 1.28 GB | 182k | 1 miss | 1 miss, 3 hits |
| a, warm | 91-141 | 113-118 MB | 43-44k | - | 1 hit |
| b, new branch | 423-440 | 449-452 MB | 158k | 1 miss | 1 miss, 3 hits |
| c, new branch | 269-401 | 312-315 MB | 136k | 0 misses | 4 hits |

Agent c's first check reuses b's reach work: zero misses, about 120 MB and 22k armed calls less than the
parent's new-branch row (435 MB, 159k). Agent b still misses a's entry, and it should: the only differing key
entry is `:seon.program/definition-digest`, because the cluster wrote a new agent namespace row (which carries a
definition digest) between a's and b's branch creation. The key is per attribute, so any declaration digest
write, a namespace row included, changes it; narrowing that needs per-kind digests and is not in this row.

The `:seon.db/commit-revisions` lookups count 46-55 misses per check: `recorded-revisions` asks for commits
nothing recorded (each member branch's resolution ends at an unrecorded conservative commit). They read
nothing and retain nothing.

Live store: the landing check (`bin/lane commit`, default's root, store of default) published the candidate
in 16.2 s and adopted it in 6.6 s (1,541 identities) on disposable branches, tests green. Publication over
10 s is the existing `seon.cluster.source/publish!` over-bound task, not this slice's cost.

## Findings

- A gitlink bump makes every `bin/seon check` (so every lane's `bin/lane commit`) refuse with "JVM RESTART
  NEEDED: loaded dependencies changed" until root restarts default; a lane cannot prove a dependency change
  through `bin/lane`. Proven instead in a throwaway root (`bin/seon --root <scratch> start`, then
  `bin/seon --root <scratch> check`). Task `seon.cluster.source/a-gitlink-bump-blocks-every-lane-check-until-restart`.
- `bin/seon check --help` answers an "Unknown option." error map instead of the usage line.
- A red long test's report printed `expected:` without `actual:` when the actual value held agent reply text
  (first probe run); with a smaller value the actual printed. Task
  `seon.test/a-red-reports-expected-without-actual-for-a-large-value`.
- Datahike fork suite: `datahike.test.optimistic-test/tx-report-happy-path-converges-to-conn` failed once in
  the full run under load and passed alone twice (timing-dependent).
