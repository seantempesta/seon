---
type: prd
status: active
tags: [prd, runtime, curation]
---

# Findings ledger — session-curation lane sweep (2026-08-04)

The owner's standing direction for this wave: **every problem the lanes
find gets an elegant root-cause solution — fix the function not to
return garbage, declare the render producers, never patch the symptom —
and everything is tracked here until closed.** This is the living
ledger; update the Status column in the same beat as any lane return or
commit. Evidence lives in the eight lane reports indexed by
[session-curation-prd-2026-08-04.md](session-curation-prd-2026-08-04.md).

Solution-shape legend: the elegant fix named per row is the ruled
direction; a lane departing from it reports why before landing.

## A0. PLATFORM INCIDENT — RESOLVED by clean-boot proof (2026-08-04)

Scratch boots failed transiently in `seon.cluster/ensure-entity!` during
concurrent in-flight edits. Resolution evidence: the dev-envelope lane
published `current-src` and booted `incident-envelope-0804` through
`agents` and `web` in an isolated root — clean, and the reported
`seon.sci.kernel/invoke` contract failure did NOT reproduce. ATTRIBUTION
CORRECTION (the 08-03 lesson again): the orchestrator first named the
dev-envelope lane's edits as the cause; its evidence cleared it — the
`ensure-entity!` hunk is a concurrent edit believed to belong to the
creation/config lane, whose ownership confirmation is pending as it
commits by index surgery. FULLY CLOSED: the creation/config lane confirmed and committed its hunks (`89fe1a287`), the dev-envelope lane verified and committed the remainder (`c683c7149`), and `cluster.clj` is clean.

## A0-2. PLATFORM INCIDENT — render-receipt-ai NPE (resolved 2026-08-04)

Every receipt in a live bootstrap drive fails `seon.cluster.run/render-receipt-ai`
with `Number.doubleValue()` on null; message rendering fails similarly
(22/22 Arm A transcripts invalid; evidence:
[bootstrap-baseline-2026-08-04.md](../research/bootstrap-baseline-2026-08-04.md)).
Attribution was falsified rather than assumed: W1 receipt facts and bounded
database faces were not the cause. Commit `4a65f9c7a6` supplied
`:seon.config.eval/time-limit-ms` to the transcript renderer, while the SCI
invocation contract requires `:seon.sci.eval/time-limit-ms`; the missing value
reached `long` in `seon.sci.kernel/own-arm`. Commit `5e5f28fb1` corrects the
input at the producer and adds the missing-numeric-fact regression. A live
drive rendered a complete transcript without an invocation failure (3 tests /
13 assertions). The baseline experiment is unblocked.

## A. Blockers with fix lanes running

| # | Problem | Elegant solution | Status |
|---|---|---|---|
| A1 | Same-instant receipts render in lexical id order — live bootstrap scrambles | order by the run's numeric form-ordinal FACT at the one `entry-order` seam; one ≥11-receipt regression | **DONE** — commits `2e6f1344e`/`d03a5b7bc`, issue archived; live proof: 13 same-instant bootstrap receipts render in exact plan order |
| A2 | Capability walk fails OPEN on host-bound capability Vars (`capability-free-references?` true for `(my.fs/read …)`) | the walk classifies host `clojure.lang.Var` like any resolved var; unclassifiable ⇒ unproven (fail closed); regression through the real cluster ctx | **DONE** — commits `bcee99a74`/`cce3d5a14`, issue archived, live proof (`my.fs/read` ⇒ capability-free false); owning gate 31 tests green |
| A3 | Same-transaction messages render and schedule `0,1,10,11,2...` | declare the numeric source-vector ordinal at message commit; order work and both projections by numeric/temporal facts | **DONE** — commit `7cfb2435f`, issue archived; focused gate 36 tests / 232 assertions; fresh-cluster proof recorded ordinals `0..11` in one transaction and both projections returned all twelve in numeric order |

## B. Ugly output — fix lanes running (root-cause + declared producers)

| # | Problem | Elegant solution | Status |
|---|---|---|---|
| B1 | `seon.db/transact!` renders ~2 MB for a 7-datom write (`:db-before`/`:db-after` serialized whole) | agent-facing return is the transaction-report projection (tx id, commit id, datom count, tempids, bounded datoms); declare `:seon.render/ai`+`html` producers for the report shape | **DONE** — commit `59edb37fa` |
| B2 | `:seon.db/rejected` faces show bare entity ids — agent cannot see WHICH agent owns a namespace | rejection error VALUE carries the conflict as data with the owner resolved to its identity attribute; face renders it | **DONE** — commit `59edb37fa` |
| B3 | agent creation returns ~3 MB | creation returns the useful projection (agent id, namespace, cluster, bootstrap run id); declared producers for the agent shape | **DONE** — commit `89fe1a287`; creation returns the four-field projection, producers declared; issue archived |
| B4 | `config/effective` ⇒ `{}` on live forked/scratch clusters ⇒ 5 KB per-dial missing-key wall (hits `eval.drive`/`bootstrap-drive` caps) | fix the empty read at its root; failure is ONE flat `:seon.error` naming cluster + missing config facts | **DONE** — commit `89fe1a287`; missing effective config is one concise error preserved by result-caps |
| B5 | every `eval_clj` mode:jvm exception reports the io-prepl serving frame, not the throw site | served error envelope carries the actual throw-site location as data | **DONE** — commit `c683c7149`; MCP clients must restart to load the new bridge |
| B6 | leaked host NPE `"fut" is null` through the prepl path | root-cause the nil future; that path returns a flat `:seon.error` naming the real cause | **DONE** — commit `c683c7149` |
| B7 | `runtime_status` ⇒ ~19k tokens of duplicated unscoped JSON | scope + dedupe the status projection structurally (per-cluster once, bounded problem summaries) | **DONE** — commit `07fd06a51`; fresh result 1,772 bytes, one selected cluster, bounded counts |

## C. Held — owner files occupied by running lanes

| # | Problem | Elegant solution | Blocked on |
|---|---|---|---|
| C1 | `defn` returns a string; a stock REPL prints `#'ns/name` (REPL parity, most common agent form) | the eval result carries the var face like `def` does; fix at the result-shaping seam in `seon.sci.eval` | **DONE** — commit `d6329faa4`; live proof: contracted defn now returns the Var face; parity issue updated |
| C2 | error receipts without triage render as ambiguous naked prose | error receipts render as execution errors (the REPL-parity face), from the receipt's own error data | **DONE** — commit `c91de41a5` |

## D. Issues filed by lanes — need owners/lanes (not yet dispatched)

| # | Problem | Elegant solution | Issue |
|---|---|---|---|
| D1 | identical redeclaration churns 69 datoms (34 retractions) — `changed-attributes` compares pulled vs desired shape | equality over declared content before building retractions; idempotent redeclare ⇒ 0 datoms | **DONE** — commit `8763b4b17`; correct argument order, identical/no-op, changed/replacement, and bootstrap 13/13 regressions green; [issue archived](../../../seon/issues/archive/program-row-replacement-churns-identical-redeclarations.md) |
| D2 | forked cluster inherits the ancestor's `:seon.config` cluster name | fork rewrites the one config identity fact at fork time (it is data on the branch) | **DONE** — commit `89fe1a287`, issue archived (fixed by the creation/config lane alongside B4) |
| D3 | `:summary` detail tier is inert (`best-summary` output byte-identical to `:full`) | one detail policy honored by the three text producers; owned by the existing render-token-budgets issue | existing issue |
| D4 | no public render-unit constructor — seven required keys discovered via five successive contract failures | one documented constructor fn with a complete contract | to file |
| D5 | `seon.effect/capabilities` NPEs on a symbol absent from the graph and violates its own output contract | absent symbol ⇒ empty set or flat error value per its declared contract; regression | to file |
| D6 | contract violations nest a `pr-str`'d print-face tree as a string inside `:seon.error/data` | error data carries the face as DATA, rendered by the error's producer | [contract-violation-serializes-print-tree-inside-error-data.md](../../../seon/issues/contract-violation-serializes-print-tree-inside-error-data.md) |
| D7 | capped collections render a bare `… :seon.sci.admit/elided` keyword (no how-much/how-to-requery) | enrich the ordinary value (count + requery handle), never a synthetic notice (08-01 finding) | **DONE** — commits `e34eea186`/`aaaaf856b`/`e35e7b27f`; both sinks carry count, total, path, offset, profile, and digest/refusal; fresh SCI evaluation proof rendered 99,968 of 100,000 omitted with digest identity; [issue archived](../../../seon/issues/archive/elided-marker-carries-no-count-or-identity.md) |
| D8 | `my.*` docstrings are maintenance diaries (205 tokens where ~60 teach) | rewrite for the actual reader (they render into agent context) | **DONE** — commit `ed41a90f7`; tracked `src/my` docstrings fell from 2,040 to 1,187 estimated tokens (−853, 42%); focused gate: 30 tests / 156 assertions |
| D9 | two graders regex over form source | the missing receipt→declaration ref (fact), then delete the regexes | to file |
| D10 | bootstrap ordinal-0 attributed `my.agents.root` while ordinal 1 (`in-ns`) is `user` | attribution follows the plan's namespace designation consistently | to file (bootstrap owner) |
| D11 | live default cluster: 813 occurrences of one `seon.instrument/contract-violated` signature + 7 stale `seon.cluster` fault vars | diagnose the one signature at its source; motivation case for curation | to file / probe |
| D12 | unrestorable program row says the capability Var is "absent from the program graph" though its graph row exists | the unrestorable reason states the ACTUAL condition (unproven capability call), from the classification that produced it | to file (found by A2 lane) |
| D13 | `test/seon/cluster/agent_test.clj:89` supplies only compute configuration while `flow.clj:475` now requires compute AND io facts — foreign boundary break | whichever lane changed the flow contract updates the fixture in the same beat; needs owner identification via git log | **DONE** — commit `8127dc987` repairs all three stale direct fixtures; the combined gate cleared the configuration refusal, then stopped at the unrelated `disarm-has-a-provider-derived-loud-backstop` retryability assertion in the concurrently dirty tree |
| D14 | `disarm-has-a-provider-derived-loud-backstop` fails at `agent_test.clj:870` (`agent/armed` nil) after the D13 repair | judge only once `cluster.clj`'s interleaved in-flight hunks are committed — dirty-tree suspicion first, per the verify-before-attributing rule | open; re-run at the A0 full close |
| D15 | changed-test selector emits a 478K single-line result dominated by unrelated lint warnings; on timeout it deletes its operator root while leaving the child JVM alive (orphan `96727` verified + reaped by the orchestrator) | selector report gets a bounded structured face; teardown reaps its child BEFORE removing records (joins the existing changed-test-process-cleanup issue) | open |
| D16 | a 1 MB top-level string is blob-backed but still returns 262,147 inline characters through MCP | pass the projected print node to the evaluation face; retain one digest-backed remainder | **DONE** — commit `e03e4a7c9` |
| D17 | time-limit face exposes SCI's private interrupt marker as an opaque host object | retain kind/message/diagnostic record; omit interpreter-private marker from agent data | [time-limit-face-exposes-interpreter-interrupt-marker.md](../../../seon/issues/time-limit-face-exposes-interpreter-interrupt-marker.md) |
| D18 | a nested flat error hides the exception's own throw-site message | preserve bounded causal context without recursively nesting error objects | [nested-error-data-hides-the-throw-site-message.md](../../../seon/issues/nested-error-data-hides-the-throw-site-message.md) |
| D19 | clean isolated `runtime_status` returns a `seon.problems/problems` output-contract violation | make the bounded selected-cluster health projection satisfy its declared shape for partial signature rows | [dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md](../../../seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md) |
| D20 | clean isolated boot emits a contract core fault because `apply-report!` declares its string index ID as a map | separate metadata mode from process index ID and declare the actual input at the one search owner | [search-index-property-collides-with-process-index-id.md](../../../seon/issues/search-index-property-collides-with-process-index-id.md) |
| D21 | live contracted definitions allocate about 578 MB apiece | incrementally validate/install one contract; pin both latency and allocation class | [contracted-defn-rebuilds-the-whole-schema-projection.md](../../../seon/issues/contracted-defn-rebuilds-the-whole-schema-projection.md) |
| D22 | root-scoped status calls a prepl unreachable immediately after successful MCP evals through that coordinate | derive roster and cluster state from one reachable observation | [status-reports-a-live-mcp-proven-prepl-unreachable.md](../../../seon/issues/status-reports-a-live-mcp-proven-prepl-unreachable.md) |
| D23 | O4 "delegation broken" was the GRADER: `seon.eval.drive/terminal-state` scopes grading to the initiating run, which `my.run/wait` correctly closes — delegation itself live-proven end to end (send → peer wake → fn → reply → continuation → "42", zero error facts) | grade the causal EPISODE (trigger-caused run closure), not one run id | [bootstrap-o4-stops-before-causal-delegation-settles.md](../../../seon/issues/bootstrap-o4-stops-before-causal-delegation-settles.md), report `o4-delegation-diagnosis-2026-08-05.md` (`cdae0bd95`) |
| D25 | ugly output (2026-08-05 session sweep): a failure surface renders only "Renderer unavailable." with no evidence; the config AI face emits a 12-line model catalog against its 1–3-line contract (filed: cluster-config-and-bootstrap-plan-render-as-raw-maps.md); READY output polluted by the enormous apply-report! contract-fault face (existing D20 issue); MCP classpath-discovery failure envelope (existing D19 issue); collection would return ~136,800 UUIDs inline and :node-not-found embeds the whole store object (fix shapes in the sealed sweep design) | each fixed at its producer, never display-patched | partly filed; "Renderer unavailable." to file |
| D24 | ugly output (o4 lane, 2026-08-05): valid defn receipts render as `:seon.eval.drive/unreadable`; duplicated noisy io-prepl form envelopes; broken MCP classpath discovery in the lane's session | each is a producer fix at its owner, never display patching | to file (three notes) |

## E. Design-level (owned by the PRD, not issues)

| Fact | Status |
|---|---|
| F1 | **DONE** — cardinality-many run-to-runs `:seon.cluster.run/supersedes`; one active-runs rule joins receipt count, recent receipts, pinned receipts, and comment forms, including bootstrap pinning and chained `not-join` exclusion before token accounting |
| F2 | **DONE, corrected in W2** — the caller records the pre-open branch-head commit ID as `:seon.cluster.run/opening-commit-id`; a transaction function's database value retains the branch-origin commit and is not the fork point |
| F3 | **DONE** — `plan-call` records `:seon.cluster.run/starting-ns`; ordinary call planning supplies the assigned namespace and replay callers may supply it explicitly |
| F4 | **DONE** — terminal settlement commits `:seon.sci.eval/ending-ns`; a resumed fold seeds its namespace from the most recent committed ending namespace, falling back to the run's starting namespace |
| F5 | Pending W4 — [session-curation-prd-2026-08-04.md](session-curation-prd-2026-08-04.md) §3 |
| F6 | **PARTIAL** — commit `7cfb2435f` lifts the source-vector index out of the message id into `:seon.cluster.message/ordinal`; order consumers no longer compare or parse the id. The direct message→issuing-form ref required by S5 pinning remains pending W4. |
| F7–F10 | Pending W3–W4 — [session-curation-prd-2026-08-04.md](session-curation-prd-2026-08-04.md) §3 |
| F11 | **DONE** — commit `093670eff` indexes direct test→function refs from the same clj-kondo var-usage pass and stores them through the shared cardinality-many `:seon.fn/calls` attribute; `seon.fn/tests-reaching` derives the reverse transitive closure needed by the dependents-test gate. Independent verification: 33 focused tests / 200 assertions, zero failures or errors; the published `default` cluster reports 865 tests with direct call refs and resolves the `seon.fn/tests-reaching` dependent test. |

W2 is complete: `system-run-tx` is owned by `seon.cluster.run` (so
`seon.bootstrap` has no dependency on curation); proof and adopt are owned by
`seon.cluster.curate`. The isolated live gate
`session-curation-w2-live-1af76a71` proved and adopted a hand-authored revision,
rendered only the curated history, and retained the superseded original as a
queryable run.

- `agent-namespace` reads the committed assignment fact — **DONE** in commit
  `3a6264724`.
- Session-image rows bypass `program-row-tx` (F10) — route through the
  one admission seam.
- Owner questions Q1–Q3 are ruled in the plan README; Q4 is deferred.
- AGENTS.md `sci/fork` vocabulary row corrected (landed with the PRD).
