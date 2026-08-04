---
type: prd
status: active
tags: [prd, runtime, curation, issues, ledger]
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
commits by index surgery. Incident closes fully when that hunk's owner
is confirmed and the file's interleaved edits are both committed.

## A. Blockers with fix lanes running

| # | Problem | Elegant solution | Status |
|---|---|---|---|
| A1 | Same-instant receipts render in lexical id order — live bootstrap scrambles | order by the run's numeric form-ordinal FACT at the one `entry-order` seam; one ≥11-receipt regression | **DONE** — commits `2e6f1344e`/`d03a5b7bc`, issue archived; live proof: 13 same-instant bootstrap receipts render in exact plan order |
| A2 | Capability walk fails OPEN on host-bound capability Vars (`capability-free-references?` true for `(my.fs/read …)`) | the walk classifies host `clojure.lang.Var` like any resolved var; unclassifiable ⇒ unproven (fail closed); regression through the real cluster ctx | **DONE** — commits `bcee99a74`/`cce3d5a14`, issue archived, live proof (`my.fs/read` ⇒ capability-free false); owning gate 31 tests green |

## B. Ugly output — fix lanes running (root-cause + declared producers)

| # | Problem | Elegant solution | Status |
|---|---|---|---|
| B1 | `seon.db/transact!` renders ~2 MB for a 7-datom write (`:db-before`/`:db-after` serialized whole) | agent-facing return is the transaction-report projection (tx id, commit id, datom count, tempids, bounded datoms); declare `:seon.render/ai`+`html` producers for the report shape | lane `ugly-db-faces` |
| B2 | `:seon.db/rejected` faces show bare entity ids — agent cannot see WHICH agent owns a namespace | rejection error VALUE carries the conflict as data with the owner resolved to its identity attribute; face renders it | lane `ugly-db-faces` |
| B3 | agent creation returns ~3 MB | creation returns the useful projection (agent id, namespace, cluster, bootstrap run id); declared producers for the agent shape | lane `ugly-creation-config-faces` |
| B4 | `config/effective` ⇒ `{}` on live forked/scratch clusters ⇒ 5 KB per-dial missing-key wall (hits `eval.drive`/`bootstrap-drive` caps) | fix the empty read at its root; failure is ONE flat `:seon.error` naming cluster + missing config facts | lane `ugly-creation-config-faces`; related issue: forked-cluster-inherits-the-ancestors-config-cluster-name |
| B5 | every `eval_clj` mode:jvm exception reports the io-prepl serving frame, not the throw site | served error envelope carries the actual throw-site location as data | committed pending: fix done + regression in mcp_test.clj, uncommitted until cluster.clj untangles (see A0) |
| B6 | leaked host NPE `"fut" is null` through the prepl path | root-cause the nil future; that path returns a flat `:seon.error` naming the real cause | lane `ugly-dev-envelope` |
| B7 | `runtime_status` ⇒ ~19k tokens of duplicated unscoped JSON | scope + dedupe the status projection structurally (per-cluster once, bounded problem summaries) | **DONE** — commit `07fd06a51`; fresh result 1,772 bytes, one selected cluster, bounded counts |

## C. Held — owner files occupied by running lanes

| # | Problem | Elegant solution | Blocked on |
|---|---|---|---|
| C1 | `defn` returns a string; a stock REPL prints `#'ns/name` (REPL parity, most common agent form) | the eval result carries the var face like `def` does; fix at the result-shaping seam in `seon.sci.eval` | lane `defn-var-face` dispatched (A2 landed, file free) |
| C2 | error receipts without triage render as ambiguous naked prose | error receipts render as execution errors (the REPL-parity face), from the receipt's own error data | lane `receipt-error-triage-face` dispatched (A1 landed, file free) |

## D. Issues filed by lanes — need owners/lanes (not yet dispatched)

| # | Problem | Elegant solution | Issue |
|---|---|---|---|
| D1 | identical redeclaration churns 69 datoms (34 retractions) — `changed-attributes` compares pulled vs desired shape | equality over declared content before building retractions; idempotent redeclare ⇒ 0 datoms | [program-row-replacement-churns-identical-redeclarations.md](../../../seon/issues/program-row-replacement-churns-identical-redeclarations.md) |
| D2 | forked cluster inherits the ancestor's `:seon.config` cluster name | fork rewrites the one config identity fact at fork time (it is data on the branch) | [forked-cluster-inherits-the-ancestors-config-cluster-name.md](../../../seon/issues/forked-cluster-inherits-the-ancestors-config-cluster-name.md) |
| D3 | `:summary` detail tier is inert (`best-summary` output byte-identical to `:full`) | one detail policy honored by the three text producers; owned by the existing render-token-budgets issue | existing issue |
| D4 | no public render-unit constructor — seven required keys discovered via five successive contract failures | one documented constructor fn with a complete contract | to file |
| D5 | `seon.effect/capabilities` NPEs on a symbol absent from the graph and violates its own output contract | absent symbol ⇒ empty set or flat error value per its declared contract; regression | to file |
| D6 | contract violations nest a `pr-str`'d print-face tree as a string inside `:seon.error/data` | error data carries the face as DATA, rendered by the error's producer | to file |
| D7 | capped collections render a bare `… :seon.sci.admit/elided` keyword (no how-much/how-to-requery) | enrich the ordinary value (count + requery handle), never a synthetic notice (08-01 finding) | to file |
| D8 | `my.*` docstrings are maintenance diaries (205 tokens where ~60 teach) | rewrite for the actual reader (they render into agent context) | to file |
| D9 | two graders regex over form source | the missing receipt→declaration ref (fact), then delete the regexes | to file |
| D10 | bootstrap ordinal-0 attributed `my.agents.root` while ordinal 1 (`in-ns`) is `user` | attribution follows the plan's namespace designation consistently | to file (bootstrap owner) |
| D11 | live default cluster: 813 occurrences of one `seon.instrument/contract-violated` signature + 7 stale `seon.cluster` fault vars | diagnose the one signature at its source; motivation case for curation | to file / probe |
| D12 | unrestorable program row says the capability Var is "absent from the program graph" though its graph row exists | the unrestorable reason states the ACTUAL condition (unproven capability call), from the classification that produced it | to file (found by A2 lane) |
| D13 | `test/seon/cluster/agent_test.clj:89` supplies only compute configuration while `flow.clj:475` now requires compute AND io facts — foreign boundary break | whichever lane changed the flow contract updates the fixture in the same beat; needs owner identification via git log | **DONE** — commit `8127dc987` repairs all three stale direct fixtures; the combined gate cleared the configuration refusal, then stopped at the unrelated `disarm-has-a-provider-derived-loud-backstop` retryability assertion in the concurrently dirty tree |
| D14 | `disarm-has-a-provider-derived-loud-backstop` fails at `agent_test.clj:870` (`agent/armed` nil) after the D13 repair | judge only once `cluster.clj`'s interleaved in-flight hunks are committed — dirty-tree suspicion first, per the verify-before-attributing rule | open; re-run at the A0 full close |

## E. Design-level (owned by the PRD, not issues)

- Missing facts F1–F10 — [session-curation-prd-2026-08-04.md](session-curation-prd-2026-08-04.md) §3, waves W1–W4.
- `agent-namespace` string-builds `my.agents.<id>` — read the assignment
  fact; overlaps existing `evals-ignore-the-agents-assigned-namespace`.
- Session-image rows bypass `program-row-tx` (F10) — route through the
  one admission seam.
- Owner questions Q1–Q4 (PRD §9) — awaiting ruling.
- AGENTS.md `sci/fork` vocabulary row corrected (landed with the PRD).
