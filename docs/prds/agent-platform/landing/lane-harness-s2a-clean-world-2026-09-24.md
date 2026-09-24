---
type: landing
status: committed fe5dcc072, code only (owner load rule); nothing loaded or run; unadopted
lane: harness-s2a-clean-world
created: 2026-09-24
design: docs/research/agent-platform/one-test-harness/design-2026-09-24.md §1 "boot facts", §2 steps 1-2, §9c S2a
issue: docs/seon/issues/test-fixture-worlds-inherit-the-live-clusters-errors-and-messages.md
---

# Lane harness-s2a-clean-world (2026-09-24)

**Owner files, named first:** `src/seon/cluster.clj` (new `seed-cluster-facts!` only),
`src/seon/cluster/boot.clj` (its one caller hunk), `src/seon/test.clj` (the request world),
`test/seon/test/clean_world_test.clj` (new), `.agents/skills/clojure-testing/SKILL.md`,
and this note. All are released now. I touched no held file (`error.clj` and `fault_test.clj`
belong to fix-fault-record-cost).

**What exists and the approach.** `seon.test/run` acquired its request branch off the live
cluster's commit, and every member branched from that. So members inherited the cluster's
agents, turns, messages and errors. Now a cluster's request branches from the source commit
its adoption record names: the published program this JVM loaded, which holds program rows
only. Boot's own writer then adds the cluster facts on top. Members branch from that head
exactly as before.

## Broken or unproven first

- **Nothing has loaded or run** (LOAD RULE). clj-kondo reports 0 errors on the four Clojure
  files, and the skill citation checker reports 0 failures. That does not prove the code loads.
- **Expected red class (owner ruling 2026-09-24, "inherited agents disappear").** Members no
  longer see root or any other live agent. 59 test files mention `"root"`.
  - Tests that relied on the inherited root will go red. Examples: `maintenance_test`,
    `schedule_test`, `turn_boot_test`, `cluster/problem_routing_test`, `read_evidence_test`,
    `render_cache_test`.
  - These follow the ruling: the fix is in each test, which must seed the agents it needs. The
    platform is not to put root back.
  - Root should judge each red: an inherited-agent assumption, or a real defect.
- **The error_test counts from the issue should turn right.** Examples:
  `a-prepared-message-keeps-its-id-when-the-transaction-repeats` and
  `an-unattributable-throwable-goes-to-the-escalation-owner`. They had counted default's live
  rows.
  - The escalation test expects `{"root" 1}`. With no root in the member it may now fail in a
    different way. `error_test.clj` needs its own before-state.
- **Isolated executions are not clean yet.**
  - This covers an agent branch running `my.test/run`, a fixture's nested `seon.test/run`,
    and an MCP `branch`.
  - Their requests still branch from their own value. That value can hold candidate program
    rows the published commit lacks (agent-admitted tests, `define-agent-test!` in
    `one_request_test`).
  - With a published base, those tests would resolve to `:seon.test/identity-unresolved`.
  - The condition is the handle's declared `:seon.agent/mode :isolated`. The base-plus-overlay
    cut (S2-overlay) retires this branch.
  - A nested request made from a clean member is already clean, because its parent is.
  - Default live holds one agent-admitted row today (`my.agents.root/largest`). Entity ids of
    the same row differ between the cluster branch and `current-src` (adoption re-transacts
    them), so the overlay cannot be done by copying datoms. That is why it is not here.
- **The base is read without adoption exclusion.** An adoption between reading the base and
  running a member is caught by S3's validity and acquired-program checks, not prevented.
  Serialized preparation is S2c.
- **Initialization rows** (4 call-preparation rows, 5 model descriptors) come from the
  published commit, not from the live cluster. The probe found them equal today. A cluster
  whose descriptors were re-applied at runtime would test against the published ones.
- **Per-request cost is unmeasured.**
  - The request now makes about 4 extra transactions: config reconcile, process, cluster
    entity and source record. My estimate is tens of ms. The old ~55 ms acquisition remains.
  - Root measures it below. More than 50 ms, or more than 20%, means fixing before adoption.
  - I deliberately avoided `config/compile-manifest`: measured 376 ms and 406 ms per call,
    dominated by `seon.schema/declaration-projection` at 249 ms uncached.

## Change

- **`seon.cluster/seed-cluster-facts! connection cluster-name compiled process`**:
  `config/apply-compiled!`, then `ensure-cluster-entity!`. It seeds no agent row.
  - Boot's `stand-cluster-runtime!` now calls it in place of the two direct calls.
    `seed-root-agent!` stays separate, for boot only.
  - Boot's `process` binding moved one line earlier. `:seon.boot/config-result` is now
    published after the cluster entity instead of before it.
- **`seon.test/clean-request-handle`** (private):
  - Base: the recording cluster row's `:seon.source/commit-id`. This is the same fact S3c
    records as `:seon.test.run/loaded-commit-id`, so world and provenance agree. A missing
    record throws by name.
  - It acquires an isolated branch at that base, then writes through the shared writer:
    - the cluster's effective config, taken as its complete manifest
      (`:seon.config/resolved-attributes` is its keys);
    - the cluster entity;
    - the fork's source record `{:seon.cluster/name … :seon.source/commit-id base}`, the same
      fact `registry/record-fork!` writes.
  - The writes happen under that branch's custody. Any refusal releases the branch and
    throws.
  - It returns the handle at the new head, so members branch from it.
- **"Clean" is derived, not hand-listed.** The world is the published commit, whose program
  partition is exactly what the regression compares via `seon.program/program-attributes`,
  plus boot's entities. No operational-attribute list exists anywhere in the change.
- **Sizes.**
  - `cluster.clj`: +15.
  - `boot.clj`: +3/−3.
  - `test.clj`: +60/−4, of which about 25 lines are docstring and comment.
  - New test: 49 lines.
  - Skill: +8/−3 for the member-world claim. I also corrected the 18 stale line citations
    the pre-commit checker refused.
  - Gross source additions: 78.

## Evidence (read-only MCP JVM probes, default pid 39156, root /Users/sean/src/seon)

| probe | observation |
|---|---|
| adopted vs published head | adopted `6ab59be6-…`, `current-src` head `6ab59c72-…`. The adopted commit is materializable (`source/commit-database`). |
| adopted commit contents | 4,788 functions, 2,127 tests; 0 errors, messages, agents, turns, cluster rows or config rows |
| live default | 16 errors, 16 messages, 1 agent (root), 1 cluster, 4,789 functions (one agent-admitted row) |
| effective as manifest | `compile-manifest` with `effective` as manifest, and with none, both give a desired row equal to live `config/effective` (empty diff, no missing keys), `no-provider true` |
| costs | `config/effective` 3.0 ms; `compile-manifest` 376/406 ms; `projection-from-database` on the adopted commit, first call 6.5 ms then 0.2 ms; comparing all 185 program-attribute datoms, adopted vs live, 49 ms |
| data partition in the published commit | model descriptors, issue files/commits, one instruction and two process rows are `:seon.data` rows present in the commit. So "clean" is "the published commit", not "no data-partition datoms". |

## Root runs (serially, after adopting fe5dcc072 or restarting from HEAD)

1. `bin/seon init --dev default --changed src/seon/cluster.clj src/seon/cluster/boot.clj src/seon/test.clj test/seon/test/clean_world_test.clj`
2. `bin/test-check default --test seon.test.clean-world-test/a-member-world-is-the-loaded-program-with-boot-facts-only`.
   - Asserts:
     - live root exists (the subject);
     - the member has zero `:seon.agent/id`, `:seon.error/id`, `:seon.message/about` and
       `:seon.turn/id` rows;
     - every program-partition attribute equals the published commit's;
     - the source record equals the base;
     - effective config equals live;
     - the only non-transaction entities added are the cluster, config and process entities.
   - Also record the request's `:seon.test.timing/acquire-ms` and elapsed time next to a
     parent-commit run of the same test, for the hot-path comparison.
3. `bin/test-check default --ns seon.test.one-request-test`. This covers the isolated/nested
   path, which should be unchanged.
4. `bin/test-check default --ns seon.error-test` (the issue's reds).
5. `bin/test-check default --ns seon.cluster.boot-test` and
   `bin/test --platform -- seon.cluster.armed-test` (boot's writer change; root only).
6. The affected-tier run, then triage reds that name a missing root or agent against the owner
   ruling.

RESET NEEDED: no. RESTART NEEDED: no. Boot's change applies at the next ordinary start.
