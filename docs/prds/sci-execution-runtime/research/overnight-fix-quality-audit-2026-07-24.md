---
type: research
status: active
tags: [research, runtime]
---

# Overnight fix-quality audit — 2026-07-23 night ladder (404bd02be..fe89babf5)

Audit question: were the 9-rung runtime fixes CLASS fixes (invariant moved to
one choke point, failure class made unrepresentable) or instance patches, and
were any tests weakened to go green? Every listed commit's full diff was read
plus the surviving source at HEAD (`codex/runtime-reliability-refactor`).

## Per-commit verdicts

| Commit | Subject | Verdict | Basis |
|---|---|---|---|
| 762424f91 | persist agents-run core faults | **INSTANCE-FIX** | Adds `error/record!` to ONE `.catch` (`src/seon/web/serve.cljs:1655`). ~10 sibling terminal catches still return 500 without persisting a fault datom (serve.cljs:494-496, 593-599, 670-673, 721-726, 1709-1716, 1752-1755, 1787-1789). The wiki scar states the rule but no shared catch door was built. Test added is strong (asserts the tx projection, real hook). |
| de1458b24 | accept config component pull selectors | **INSTANCE-FIX (borderline)** | Corrects the one wrong declared `:malli/schema` on `config-pull-pattern`, which instrumentation itself caught — the class (schema drift) is already guarded by instrumentation. Minor smell: `[:enum :seon.config/provider-descriptors :seon.config/model-variants]` (`src/seon/ai.cljs:571-575`) hand-mirrors the pattern's content instead of describing the shape (`[:map-of :qualified-keyword [:= '[*]]]`). |
| e21c85417 | hand LLM attempts to JVM claimant | **CLASS-FIX** | Deletes the pod's duplicate LLM transport (`pod-transport!`, `llm-phase!` removed from `turn.cljs`; `:llm` capability removed from the pod leaf) — one durable-attempt door remains. Widens `attempt-row` so every provider failure persists flat, capped evidence at the one evidence choke point (`src/seon/agent/turn/llm.cljc:187`). Superseded path deleted in the same refactor, per policy. |
| 094e7a7e6 | claimant phase-error settlement | **CLASS-FIX (one flagged deviation)** | `settle-phase-error!` in `drive-claim!` (`src/seon/agent/driver.cljc:~400-460`) settles ANY phase leaf's error value — the nothing-wedges class closed at one driver choke point, not per-phase. Deviation: `invocation-configuration!` now reads `SEON_LLM_ATTEMPT_TIMEOUT_MS` via `(System/getenv)` at claim time (`src/seon/agent/driver/host.clj:81`, `src/seon/config/resolve.cljc:1583`) — runtime reading an environment variable violates config-through-DB; the durable-timeout requirement was removed rather than the config seeding fixed. |
| 62cd2348b | claimant remote identity allocation | **CLASS-FIX** | `db.id/allocate!` becomes ONE portable entry: db-value → `seon.db` facade, conn → local writer, same candidate grammar/retry on both (`src/seon/db/id.cljc:1475-1497`). Formerly CLJS-only reader-conditional bodies made portable, which is what enabled 3fd9137f6's deletion. Local `#?(:clj (defmacro await ...))` shim matches the established repo-wide `.cljc` pattern (12+ files). |
| 356519dd0 | database facade off Babashka path | **INSTANCE-FIX (necessary follow-up)** | One-line `#?(:bb ... :as-alias)` repair of the breakage 62cd2348b introduced on the operator path. Symptom of the tri-tier reader-conditional class; acceptable. |
| 08942c9f9 | reconcile claimant allocation follow-up | **DOCS ONLY** | Ledger/issue updates, no source. Correct ledger hygiene. |
| fdba88aad | claimant reply/eval acquisition | **INSTANCE-FIX ×2** | (a) hardcodes `[:seon.config/id "cluster"]` at yet another site — the config-identity class it instance-patches is closed 12 minutes later by 7b16ca694; (b) `(:text response)` → `(:seon.ai/text response)` fixes the empty-reply-blob bug at one read site. The bare-key class remains at the interim pod adapters (`src/seon/ai/anthropic.cljs:347`, `src/seon/ai/openai_compat.cljs:530`, `src/seon/ai/dispatch.cljs:85,101`) — all `.cljs` self-host territory that dies at the great deletion, so residue is fenced but real until then. |
| 7b16ca694 | unify cluster config lookup identity | **CLASS-FIX** | ONE owner: `cluster-config-lookup-ref` def at `src/seon/config/resolve.cljc:914`; all 17 files now reference it (verified: zero `[:seon.config/id "cluster"]` literals remain in `src/`; a handful survive in tests, e.g. `test/seon/web/serve_test.cljs:160+`, `test/seon/db/datahike/schema_test.clj:673` — low risk, but they would not catch a future identity change). |
| 0ae0fda9e | claimant schema lookup committed projection | **INSTANCE-FIX** | Rebinds the single `schema-definition` wrapper to the committed projection (`src/seon/host/context.clj:284` `committed-schema-definition`). Correct, but no general rule prevents the next wrapper from reading process-local `seon.schema` state instead of the committed projection — the fix is one wrapper, not an acquisition invariant. Output schema is `:any` (schema forms are genuinely polymorphic; acceptable but undocumented as such). |
| f6dd94682 | terminalize timed-out active turns | **CLASS-FIX** | Generalizes `phase-error-close-tx-data` → `terminal-close-tx-data` (`src/seon/agent/turn/core.cljc:159`): ONE fenced tx builder terminalizes a turn (any phase, any open attempts) + closes the run atomically, used by both the pod timeout close (`src/seon/agent/run.cljs:637`) and the claimant phase-error path (`src/seon/agent/driver.cljc:441`). Adds `terminal-or-displaced-result` custody re-check before settling. |
| 3fd9137f6 | portable allocation contract | **CLASS-FIX (deletion)** | Deletes `host-allocate!` — a full second allocation mechanism with its own 16-attempt retry loop inside `host/context.clj` — and routes the sci wrapper through the one `db.id/allocate!` behind `db/*leaf*` binding. Net −54 lines. This is exactly the "one mechanism" policy executed. |
| a385b2cb6 | close claimant memory-layer defects | **DOCS ONLY** | Issue-note closes + ledger; the code was 0ae0fda9e/f6dd94682. |
| 60d09ef38 | complete no-dispatch claimant replies | **INSTANCE-FIX / mild SMELL** | Adds `no-dispatch-reply?` (`src/seon/agent/driver/host.clj:338`) — a second reply classifier that branches BEFORE the planner. But `plan-execution` already represents an empty program as `:no-roots` placement (`src/seon/program/plan.cljc:324-327`), and `execution-plan-disposition` (`src/seon/agent/driver.cljc:279`) is the documented pre-dispatch classification choke point — it simply lacks a `:no-roots` arm, so an empty reply fell into the `(nil? selected-tier)` steering error. The class fix is a `:no-roots → :no-dispatch` disposition arm; instead there are now two classifiers over the same question. The `planning-root-resolution` addition (installed-binding namespaces into known-namespaces) is computed and fine. |
| 170d97862 | provision exact-plan capability namespaces | **CLASS-FIX (computed rule)** | `provision-plan-bindings!` (`src/seon/agent/driver/host.clj:277`) derives namespaces from the execution plan's `:seon.execution/required-bindings` capability manifest — computed from the indexed plan, no hand list — and installs through the one wrapper registry (`context/install-registered-wrappers!`). |

Tally: 7 class-fix, 6 instance-fix (1 with smell), 2 docs-only.

## Cross-cutting answers

### 1. One structural cause under the ladder?

Not one — **three**, and they got different treatment:

1. **Duplicate mechanisms on the claimant path** (pod LLM transport, host-side
   allocation reimplementation). This class was properly DISSOLVED by
   deletion: e21c85417 and 62cd2348b+3fd9137f6 each deleted the second
   mechanism and left one owner. Best work of the night.
2. **Ad-hoc per-fact acquisition instead of one committed contract** — this is
   the hypothesized common cause, and it is real: config identity literals
   (7b16ca694), wrong singleton keyword (fdba88aad a), wrong response key
   (fdba88aad b), too-narrow pull-pattern schema (de1458b24), wrapper reading
   process-local registry instead of committed projection (0ae0fda9e). Each
   fact family got its OWN choke point (`cluster-config-lookup-ref`,
   `committed-schema-definition`, `acquire-planning-projection`,
   `invocation-configuration!`), but **no commit establishes a single
   claimant acquisition contract**, and nothing structurally prevents the next
   claimant read from inventing its own lookup ref, key spelling, or local
   registry read. Sequencing shows the instance-first pattern the owner
   feared: fdba88aad hardcoded `"cluster"` at 04:25, 7b16ca694 unified at
   04:37 — the class fix DID follow within the same night, but only for that
   one family.
3. **Non-total settlement** (dropped phase errors, orphaned :evaling turns,
   unrepresentable empty replies). Largely unified: `settle-phase-error!` +
   `terminal-close-tx-data` are genuine choke points covering all phases. The
   exception is 60d09ef38's pre-planner `no-dispatch-reply?` branch (see Q3
   verdict table row) — the settlement class has one tx builder but now two
   reply classifiers.

### 2. Config identity: one owner or 17 corrected copies?

**One owner.** `cluster-config-lookup-ref` is defined once
(`src/seon/config/resolve.cljc:914`) and re-exported through `seon.config`;
all 17 files reference the def. Verified zero `[:seon.config/id "cluster"]`
literals remain in `src/`. Residue: several test fixtures still compare
against the literal (`test/seon/web/serve_test.cljs:160,201,279,368,407,458`,
`test/seon/execution/runtime_test.cljs:133`,
`test/seon/db/datahike/schema_test.clj:673,698,709`) — they pass today but
duplicate the identity by hand.

### 3. Timeout handling: one settlement choke point or per-phase?

**One tx-data choke point, two detection drivers.**
`turn.core/terminal-close-tx-data` is phase-generic (takes the current phase
and the open attempt ids, terminalizes attempts `:open → :crashed`, publishes
the turn, closes and releases the run in one fenced transaction) and is the
sole builder for both the pod stale-run/timeout close
(`src/seon/agent/run.cljs:637` via `active-turn-close-data`) and the claimant
phase-error settle (`src/seon/agent/driver.cljc:441`). Detection remains split
across the two claimant tiers (pod watchdog vs JVM driver), which is the
expected two-tier interim until the great deletion, not a per-phase split. No
per-phase special cases found.

### 4. Any test weakened/deleted to get green?

**No.** Scanned removed lines in every listed commit's test diffs: only
require-line churn and the identity-literal → owner-def substitutions.
The one candidate — dbc283252 renaming
`watchdog-interrupt-of-hung-http-is-a-flat-timeout` and dropping its
`"claimant deadline"`/`:seon.ai/outer-timeout?` assertions — is a legitimate
re-homing, not a weakening: those assertions moved into a NEW test
(`claimant-watchdog-timeout-is-a-flat-timeout`) that exercises the actual
owner (`driver.host/bounded-llm-transport!`) instead of asserting
claimant-branded behavior on the adapter. Net assertion coverage increased
everywhere (e21c85417 and f6dd94682 in particular added substantial
evidence-shape and settlement regressions).

### 5. Exact-plan capability namespaces: computed or hand list?

**Computed.** `provision-plan-bindings!` derives the namespace set from the
plan's `:seon.execution/capability-manifest :seon.execution/required-bindings`
(itself derived by `plan-execution` from the indexed call graph) and installs
through the one registry mechanism. No literal namespace list exists in the
commit; the test enumerates namespaces only as fixture expectations. Compliant
with the computed-classification rule.

## Ranked class-level refactors to dispatch

1. **HTTP terminal-catch fault door (highest confidence, cheapest).**
   Class: terminal `.catch` returns 500 without persisting a core-fault
   datom. Choke point: one composition helper in `seon.web.serve` (record! +
   log + `write-status!` 500) used by every handler's terminal catch. Files:
   `src/seon/web/serve.cljs` (~10 sites: 494, 596, 670, 723, 1709, 1713,
   1752, 1787, 1832, 1919), `test/seon/web/serve_test.cljs`. Expected
   deletions: the 10 open-coded catch bodies collapse to one call; keep ONE
   regression (the 762424f91 test, generalized to the helper). Caveat:
   serve.cljs is pod-interim — if U9 great deletion retires these routes soon,
   fold this into that unit instead of a standalone lane.
2. **`:no-roots` disposition arm; delete the second reply classifier.**
   Class: pre-dispatch reply classification split across two mechanisms.
   Choke point: `execution-plan-disposition`
   (`src/seon/agent/driver.cljc:279`) gains a `:no-roots → :no-dispatch`
   arm; `driver.host/settle-reply!` consumes the disposition and drops
   `no-dispatch-reply?` (`src/seon/agent/driver/host.clj:338,411`). Files:
   driver.cljc, driver/host.clj, driver_core_test.cljc,
   agent_driver_writer_test.clj. Expected deletions: `no-dispatch-reply?` and
   its branch (~25 lines); the planner stays the single authority on what a
   program contains.
3. **Claimant attempt-timeout back through the database.**
   Class: runtime reading environment variables (config-through-DB
   violation, R41-adjacent). Choke point: reconcile
   `SEON_LLM_ATTEMPT_TIMEOUT_MS` into a config-singleton fact at manifest
   apply (where `config.resolve` already computes the attempt horizon), and
   have `invocation-configuration!` pull it with the other
   `claim-driver-attributes`. Files: `src/seon/agent/driver/host.clj:81`,
   `src/seon/config/resolve.cljc:1583-1610`, config seeding, writer test.
   Expected deletions: the `(System/getenv)` call at claim time; the env var
   remains a manifest-time input only.
4. **Committed-projection acquisition rule for host wrappers (design pass,
   then bounded implementation).** Class: a claimant wrapper reading
   process-local state (`seon.schema` registry, atoms) where the committed
   projection is authoritative — 0ae0fda9e fixed one wrapper by hand.
   Choke point candidate: wrapper registration declares its data source
   (committed-projection vs pure vs leaf) so the registry can enforce it,
   rather than each `::wrapper-fn` choosing. Files: `src/seon/host/context.clj`
   wrapper registry. Needs a short audit of the remaining wrappers first —
   not verified here which others (if any) still read local registry state.
5. **Retire the interim bare-key LLM projection (`{:text ...}`) with the
   great deletion.** Class: unnamespaced wire keys at the pod adapter
   boundary (`src/seon/ai/anthropic.cljs:347`,
   `src/seon/ai/openai_compat.cljs:530`, `src/seon/ai/dispatch.cljs:85,101`,
   docstring `src/seon/agent/loop.cljs:570`). No new lane — tag these files
   in the U9 deletion inventory so the class dies with the self-host path
   instead of being ported.

## Not verified

- Whether the live re-drive after 60d09ef38/170d97862 passed (the anchor
  itself marks the lifecycle live-proof as interrupted; this audit read
  diffs and source, not the running cluster).
- Whether any host wrapper besides `schema-definition` still reads
  process-local registry state (refactor 4 requires that audit).
- The full `bin/test-writer`/`bin/test-cljs` gates were not run here; verdicts
  rest on diff + source reading only.
