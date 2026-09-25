---
type: research
status: complete
lane: census-red-baseline-3
created: 2026-09-25
---

# Default's red baseline, third census (current data only, 2026-09-25 01:15Z)

This census reads no stale data. Root reran every red and unknown test on HEAD between 00:00Z and 01:40Z. Nothing
was executed for this census. The data came from MCP `eval_clj` in JVM mode, read-only, via
`(@#'seon.problems/failed-tests @(seon.cluster.boot/connection "default"))` (problems.clj:373). That read returned
383 entries: **365 red and 18 typed unknown**, in 318 ms. Failure text is `seon.test/failure-message`
(test.clj:55), clipped to 2,500 characters. The method is the one from red-baseline-census-2026-09-25.md
(dd6d1a2ac):
- an ordered substring classifier kept outside the repo;
- a hand read of the 132 leftovers;
- verification of each suspected owner against `src/` and `git log -S`.

Every red here was recorded on the current program, so the staleness class is gone. Line numbers are `deftest`
lines under `test/seon/` unless another path is shown.

## Ranked classes (365)

| # | class | n | verdict | smallest fix / owner |
|---|---|---:|---|---|
| C | hand-built rows the writer refuses (digest, component, symbol-as-string, message/to, no test row) | 58 | HARNESS-S5 H1 | build rows through owners; per-file sweep |
| E | bound: 23 duration-only, 12 "did not exit", 8 event waits of 20 s | 43 | BOUND | see §E; prompt_test and test_reaching dominate |
| A | agent row without `:seon.agent/branch` | 40 | HARNESS-S5 H1 | write agents with `agent/creation-tx` (branch-call) |
| B | "Isolation requires a fresh owned branch": fixture agent row names the label branch `:cluster-turn-test` | 33 | HARNESS-S5 H1 | one helper, cluster/turn_test.clj:131 `agent-row` |
| K | spy, stand-in or `with-redefs` not reached (counters, ledgers, call-preparation plans) | 33 | HARNESS-S5 H6/H7/H3 | miss counts or durable facts; delete needless spies |
| G | refusal member read from the pre-flat location (`:seon.error/data`, retired booleans) | 27 | UPDATE-TEST | read the flat member the owner now writes |
| N | single-owner assertions (schema, db, mcp window, config manifest) | 27 | FIX-SRC ×17, probe ×10 | §N, grouped by owner file |
| H | test calls src with a retired shape, contract or deleted Var | 25 | UPDATE-TEST | per test; rule commits in §H |
| J | SCI: elided arguments arrive as arity 0; names unresolved in acquired contexts | 23 | **FIX-SRC** | src/seon/sci/eval.clj + call_preparation.clj |
| I | clean world lacks, or unexpectedly has, default's data (root, `default` config, adopted commit) | 22 | HARNESS-S5 | inherited-default conversion (S2a follow-up) |
| M | render selection falls back to the attribute printer, `:ambiguous` or empty candidates | 15 | probe → FIX-SRC or H1 | seon.render selection; §M |
| F | `seon.problems-test` did not compile (`error/resolution` absent from the JVM at run time) | 12 | RERUN | it resolves now; no change needed |
| D | a write names another cluster's branch (two worlds) | 4 | HARNESS-S5 H2 | genuine sibling world |
| L | singles: store already open, worker-global drift, empty clj-kondo cache | 3 | H8 / platform / operational | §L |

No red reaches a subject deleted today. None of the tests under test/ names `carry-derived-projection`,
`projection-delta`, `submit!!`, `execute-work!`, `log-report`, `log-line`, `publication-base!` or
`reserve-cluster!`, so there is **no DELETE class**. `db-test/uncarried-read-reports-one-projection-fallback-per-call`
(db_test.clj:~160) exercises `seon.db/projection-fallback` (db.clj:1230), which still exists. It is filed under H,
because the pull it runs now returns a carried value rather than a fallback.

## FIX-SRC, grouped by owner file (one lane per file)

1. **src/seon/sci/eval.clj** (J, 12 of 23; also N adoption-diagnostic):
   - `acquire-program!` refuses a name that does not name an installed SCI Var (eval.clj:2328), e.g.
     cluster/turn_test.clj `acquisition-orders-agent-authored-refer-targets-and-ignores-alias-cycles`.
   - "Unable to resolve symbol: my.plan/plan" in an acquired context: call_preparation_test.clj:858.
   - A retained branch loses its def: dev/mcp_bridge_test.clj:683.
   - `acquisition-refusal` breaks its own output contract with a string `:seon.sci.eval/row-member`:
     adoption_diagnostic_test.clj:10, error_test.clj:76.
   - Documentation and referral contexts are not identical after an update: sci/documentation_test.clj:170/233/272.
   - Branch-execution interpreted callers: sci/branch_execution_test.clj:412.
   - Suspect: 2f4ea22d9 (18:10, "the request branch acquires no SCI program"). Probe one acquired ctx first.
2. **src/seon/call_preparation.clj** (J, 7; K, 7):
   - A contracted probe called with its connection elided arrives with argument count 0, and `sci.impl.analyzer`
     refuses it: call_preparation_test.clj:631/690/797, instrument_test `a-sovereign-sci-fork-acquires-its-own-recorder`
     (`seon.id/valid?`), issue_test `issue-worker-opening-links-its-issue` (`seon.bootstrap/help-value`).
   - `refused-read` gets nil reads (call_preparation.clj:1018): call_preparation_test.clj:364.
   - The 7 plan-cache assertions (:492/:519/:336…) share this owner. Settle J first, then decide whether they are H6.
3. **src/seon/db.clj** (N, 3):
   - `read-declarations` (db.clj:1580) calls `diagnostic` with no `:seon.error/operation`, which throws an NPE at
     db.clj:182: db_test `a-refused-declarations-read-refuses-decoding`.
   - The elided write arity no longer commits: db_test.clj:1726.
   - Nested native reports come back `:seon.sci.admit/opaque`: db_test.clj:1106.
4. **resources/seon/schemas/seon.boot.edn:55 and seon.operator.edn:49**: `[:fn seon.cluster/socket-server?]` lacks
   `:error/message` (schema_test.clj:526).
5. **src/seon/schema.clj**:
   - An orphan `;;; Development Helpers` banner is still present at schema.clj:4130. Open items blames it for the
     reader round-trip failure (sci/reader_test).
   - The incremental projection differs from the full build: schema_test.clj:1646/1722.
   - A second function names `:seon.schema.projection/predicate-functions`: schema_test.clj:1320.
6. **src/seon/schema/datahike.clj**:
   - `storable-properties-in` receives a nil schema (datahike.clj:270): fn_test `publication-writes-one-definition-digest-per-declaration-family`.
   - Observation attributes get `:db.unique/identity`: schema_test.clj:1471.
   - The `:db.secondary/only` rule does not refuse: schema/datahike_test `unsupported-database-attributes-refuse-at-one-rule`.
   - Parity property set: schema/datahike_test.clj:118. This may instead be UPDATE-TEST of `datahike_parity.edn`.
7. **src/seon/cluster.clj (MCP projection) / seon.render.value**: the 8 KB value window is exceeded at 24–25 KB, and
   elisions lose their paths: cluster/mcp_test.clj:112, :149, :172.
8. **src/seon/cluster/prompt.clj:410**: `capture-mismatch` returns a map against its `[:or :nil refusal]` output:
   cluster/prompt_test `a-replay-under-a-selecting-budget-reconstructs-its-own-capture`.
9. **resources config manifest (`config/default-manifest-path`)**: the shipped EDN lacks the
   `:seon.config.agent/*`, `ai.backup/*` and `ai.retry/*` dials (config_test.clj:151). Also
   `seon.schema/canonical-definition` returns a non-EDN form (config_application_test).
10. **src/seon/render/web.clj:719**: `attribute-description` dereferences a nil registry: render/web_test
    `an-attribute-description-comes-from-the-projection-form`.

Probe-only N items (owner unconfirmed):
- await_owner_census_test.clj:29: ClassCastException, Symbol to Number, inside the census read.
- cluster/instruction_test.clj:105: `seon.render/walk` and `my.message/send` exist (render.clj:1803,
  my/message.clj:31), but the member world holds no arity rows for them.
- schema_usage_guard_test.clj:441.
- schema_test.clj:1176/1288/446/981.
- adoption_diagnostic_test.clj:32: 2,593 bytes against a 1,024-byte bound.

## Class details

**A. Agent rows without a branch, 40: HARNESS-S5 H1.** `:seon.agent/branch` has been a required entity member since
1ada78050 (09-22). `creation-tx` supplies it through `branch-call` (agent.clj:186–222, b2fcb5225). Examples:
my/agent_test.clj:11, render/web_debug_test.clj (6 tests), cluster/problem_routing_test.clj (4 tests). Fix: script
`{:seon.agent/id …}` fixture rows into `(agent/creation-tx {…})`. The largest files are web_debug (6),
problem_routing (4) and eight files with 2 each.

**B. Label-branch agents, 33: HARNESS-S5 H1 (custody).**
- The cause is `agent-row` at cluster/turn_test.clj:131–137. It writes `:seon.agent/branch (registry/cluster-branch
  "turn-test")`, which is not the member branch.
- The first acquisition creates `:cluster-turn-test`. Every later test's isolated create then finds that branch and
  throws at agent.clj:930.
- Examples: cluster/turn_test.clj:2620, :1405, :1907. There is one more in turn_test.clj, where the same label is
  written 10 times.
- Fix: drop the branch from `agent-row` so `branch-call` records the member's own branch. That is one helper edit for
  32 tests.

**C. Hand-built rows, 58: HARNESS-S5 H1.** Each subclass has the rule that makes it fail:

| subclass | n | rule | examples |
|---|---:|---|---|
| rows missing `:seon.program/definition-digest` | 26 | f27b96c19 | test_failure_facts_test.clj (16, fixture at :48), bootstrap_test.clj (3), wake_test |
| component validation of fixture schemas or unowned rows | 13 | 74d020fc6 | blob_test.clj (3), schema_usage_guard_test.clj (4), render/root_pull_test.clj (3) |
| "no surviving test definition" for hand-registered fixture tests | 7 | — | test_failure_facts_test, test_provenance_test.clj, test_runner_test |
| strings where symbols belong | 5 | 041102a76 | test/accretion_test.clj:19 (4 tests); the cluster/turn_test terminal seed |
| `my.note` fixture messages without `:seon.message/to` | 4 | — | my/note_test.clj:20, :52, :71 |
| other | 3 | — | issue_test's `seon.db/*conn*` as an issue id; web_test.clj:2441, which retracts a required ref; test_reaching_test.clj:552 |

**D. Sibling worlds, 4: HARNESS-S5 H2.** concurrency_test.clj:43, :200; config_test.clj:659; issue_test.clj:74.

**E. Bounds, 43: BOUND.** Default bound 5,000 ms.
- **Duration-only, 23.** These run 5,294–23,334 ms, median about 7,000 ms.
  - cluster/prompt_test accounts for 8, at 6.5–7.9 s. That is prompt composition on a fresh member world.
  - test_reaching_test accounts for 5, at 5.5–8.9 s. These run nested `seon.test/run` requests, so the cost is the
    inner request's acquisition and reach phase (f9de0d928, 532a364a0).
  - The worst are `turn-test/recovery-preserves-terminal-receipts-exactly` (23.3 s),
    `test.accretion-test/source-harvest-keeps-refusal-render-contracts-coherent` (19.0 s),
    `config-test/config-loads-the-packaged-population-in-a-fresh-jvm` (18.6 s; a fresh JVM is expected, so declare
    its bound), `sci.eval-test/static-and-runtime-contracted-definitions-publish-identical-facts` (16.6 s) and
    `flow-test/core-fault-signatures-bound-durable-and-stderr-output` (13.5 s).
- **"Did not exit", 12.** A body kept its thread past the check limit. `cluster.agent-test/wake-routing-conservation-property`
  (115.9 s remainder) and `routing-conservation-waits-for-terminal-evidence` (21 s) caused all 18 unknowns (below).
  The others are render/web_test `reconnect-is-repaint-wire-test` (84 s), `adoption-contract-freshness` (103 s),
  fn_test `indexing-uses-a-prebuilt-manifest-without-analysis` (35 s) and 7 more.
- **Event waits, 8.** Each waited 20 s for an event that was never published:
  - `armer-stopped` in cluster/agent_arming_test (2);
  - render and route faults in cluster/wake_test (3);
  - `committer-last-resort` in flow_test;
  - web_context and agent_test (1 each).

  Each names a terminal event that never fired. That is either a graph that never armed, which falls under design §1
  "graphs" (convert `arm!` callers to handle routing, aed1b3838), or a real routing regression. Probe
  agent_arming_test first.

**G. Pre-flat refusal members, 27: UPDATE-TEST.** Errors are flat data now. Members moved to the top level, and the
boolean kind flags were retired: 3b84644ef (09-17), 86c3a73d4 (09-20, plan kinds such as `:my.plan/not-owned` →
`:my.plan/unowned-item-id`) and bab838388 (09-23). Examples:
- reset_edges_test.clj:30 reads `[:seon.error/data :seon.program/referrers]`, while db.clj:4043 writes it top-level;
- config_test.clj:213 reads `:seon.config/key`, while config.clj:310 writes `:seon.config/error-key`;
- my/plan_test.clj:189, :322.

The files involved are config_test (4), reset_edges (4), plan_test (2), turn_test (2) and 15 singles.

**H. Retired shapes and contracts, 25: UPDATE-TEST.**
- `:seon.turn.loop/phase-failed` is gone (373820ffd, 3 tests: cluster/agent_test.clj:1250, turn_loop_test.clj:959,
  :1087).
- `:seon.error/declared-schema` is required at recording and `commit-tx` (a86e93e21 and 373820ffd, 4 tests).
- `seon.cluster/commit-fault!` was deleted (83bac0e78). cluster_test.clj:120 resolves it to nil, which throws an NPE.
- `my.plan/update!` and `my.program/breaks` now take a request map (my/plan_api_test.clj:11, :73;
  my/program_test.clj:126).
- turn `settle!`/`open-turn`/`record-attempt!` shapes (turn_test, 3 tests).
- A keyword stands in for the connection in `mcp-runtime-observation` (cluster/mcp_test, 2 tests).
- Test-entity pair evidence shape: render/entity_pairs_test.clj:90.

**I. Inherited default data, 22: HARNESS-S5.** A clean world (S2a fe5dcc072) branches from the published program
plus boot's cluster facts:
- It has **no** `root` agent. schedule_test.clj:295/246/387 expect root wakes. Also: boot_test `status-answers-…`
  ("no live cluster database", boot.clj:538); loop_proof_test and oversight_test (`Nothing found for
  [:seon.config/cluster …]`); issue_settlement_test (test.clj:1595, "no adopted source commit" for the fixture
  cluster); `my.plan-test/a-second-agent-renders-through-the-same-defaults` (no effective config row).
- It **does** carry `default`'s config row and `my.agents.root` namespace (config_test.clj:602 sees
  `"default"`, cluster/root_seed_digest_test.clj:17).

Fix: each test asks the attempt world for what it creates, never for default's fixtures.

**K. Spies and stand-ins, 33: HARNESS-S5.** These are H6 counters and H7 redefinitions:
- render/root_pull_test.clj:204/235/340; fn_test.clj:2667 (`packaged-forms-calls` 2 against 1);
  error_write_timing_test.clj:17; render_simplification_test.clj:824/848;
  web_test `unrelated-transaction-…`; mcp_test.clj:110; turn_backstop_test.clj:119.
- Provider ledgers stay at 0: cluster/agent_test park-wake, pause, restamp and n-agent (H3 loopback).
- The `with-redefs [real-evaluate …]` in cluster/agent_test.clj:160 recurses through `fixture-evaluate`
  (agent_test.clj:75–78), overflowing the stack for core_functions_test and gen/loop_test.
- `with-redefs db/transact!` no longer reaches the web write path (web_test.clj:2138, 204 instead of 422/500).

**M. Render selection, 15: probe.** Expected fixture renderers print as `#:seon.ns{:name …}` (the attribute printer)
or select `:ambiguous`/empty candidates: render_simplification_test.clj:216, :694, :925 and 7 more; render/web_debug_test.clj:661;
render/web_test.clj:651. One probe decides whether the fixture render rows reach the member world (then H1) or
selection ignores the namespace stage (seon.render, FIX-SRC).

**F. Compile, 12: RERUN.** "Syntax error compiling at (seon/problems_test.clj:388:24)" is `(error/resolution …)`,
added by 80958c50b (18:27 local). The JVM that ran it lacked the Var, and the other 11 tests in that namespace recorded
"no executable test Var". `(resolve 'seon.error/resolution)` answers now, so one rerun of `seon.problems-test`
decides these.

**L. Singles, 3:**
- cluster/store_test.clj:127: the store is already open in pid 57919 (H8 destructive isolation).
- error_test.clj:495: worker-global drift (platform).
- schedule_test.clj:392: clj-kondo holds no analysis of `io`. Run `(seon.fn.analyzer/analyze-dependencies!)`.

## The 18 unknowns

All 18 carry `:seon.test.run/stopped :member-unfinished`. Each is typed "Never started", refusal
`:seon.test/population-unknown`.

| request | at (Z) | unfinished member | never started |
|---|---|---|---:|
| ed729f0e138c | 01:05:11 | `seon.cluster.agent-test/routing-conservation-waits-for-terminal-evidence` (did not exit, 21 s) | 4: agent-test `routing-proof-rejects-an-empty-production-subject`, `system-source-submission-uses-the-ordinary-durable-run`, `turn-start-has-the-same-declared-loud-completion-backstop`, `wait-closes-in-terminal-tx-test` |
| 28045eb07b2e | 01:07:12 | `seon.cluster.agent-test/wake-routing-conservation-property` (did not exit, 115.9 s) | 14: boot-reply-test (2), boot-test (1), prompt-test (3), publication-findings-test, publication-serialize-test, resume-artifact-routing-test, cluster.turn-test (5) |

Both are correct refusals: a live body blocks any further body. Rerun the 18 in one request that excludes the two
routing-conservation tests. Those two stay E-class until their terminal wait is found.

## Suggested order

1. Two helper-sized harness edits:
   - B (cluster/turn_test.clj `agent-row`, 33);
   - the scripted A sweep to `creation-tx` (40).
2. FIX-SRC lanes, one per file:
   - sci/eval.clj and call_preparation.clj (J, about 23 tests plus up to 7 K);
   - db.clj (3);
   - schema.clj and datahike.clj (about 7);
   - the MCP window (3);
   - the config manifest (2);
   - prompt.clj (1);
   - web.clj (1).
3. Mechanical UPDATE-TEST: G (27) and H (25).
4. Harness S5 campaign: C (58), I (22), K (33), D (4).
5. Rerun F (12) and the 18 unknowns.
6. BOUND: declare the fresh-JVM bound, and time prompt_test and test_reaching phases before touching bounds.

Limits:
- These are substring classes plus a hand read. Counts are ±3 at class borders: a test with several failures was
  put in its first-cause class.
- M, K-call-preparation and N-probe verdicts are unproven until probed.
- Nothing was re-executed.
- Timings: `failed-tests` read 318 ms, dump 291 ms, run-member pull 8 ms.
