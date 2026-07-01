---
type: prd
status: draft
tags: [prd, agent, flow]
---

# Spec: unify agent init into ONE `init-agent!` + delete the boot turn

## Why

Agent init is smeared across three fns with overlapping steps and no single
configurable entry — a "one mechanism" violation, and it's what broke `/solve`
recording (the boot greeting turn). This spec unifies them into ONE deterministic,
configurable `init-agent!` and deletes the vestigial boot turn. It is a Core
`seon.client` boot-path change; it must preserve mint, hot-reload re-arm, and spawn.

Depth context: [[../inspect-seon-bridge-spike-2026-07-01]] §5g–§5i (the design +
blast radius + the ns-wiring-is-separate-from-the-turn finding).

## Ground truth (verified in source)

- **`:idle` = no open run** (`derive.cljs:84`). A freshly-minted agent with ZERO
  runs is already `:idle` and wakeable — the wake handler opens run #1 on the first
  message iff `:idle` (`loop.cljs:345`). No turn 0 is required to be ready.
- **The per-agent ns/require wiring is `setup-agent-ns!`** (`eval.cljs:1388`) — evals
  `(ns my.agent.<id> (:require [message][agent][lifecycle :refer …][schema][db][todo]))`.
  It is called by `boot-one-agent!` AND `arm-agent!`, NOT by the boot turn.
- **`bootstrap-turn!`** (`client.cljs:2224`) evals ONLY `(message/user "Hi…")` +
  `(wait …)` and closes the run `:waited`. It contributes ZERO wiring — pure
  ceremony that downstream code must then exclude (gym cause-scoping, `/solve`).
- **Production render/derive/wake are ALL zero-run-safe** (blast-radius audit): a
  zero-run agent renders (empty-transcript placeholder), derives `:idle`, and wakes
  correctly. The only HARD sites are two test/gym spots + the `/solve` heuristic.

## The two existing init fns being merged (their exact steps)

| Fn | Steps | Callers |
|---|---|---|
| `boot-one-agent!` (`client.cljs:2053`, `with-agent` scope) | `setup-agent-ns!` → `agent/boot!` (create entity) → *(on ok)* `install-wake-trigger!` → `runtime-id/host!`; propagates create!'s error envelope, does NOT arm on failure | `start-agent!` mint loop (`:2434`) |
| `arm-agent!` (`client.cljs:1966`) | `runtime-id/host!` → `setup-agent-ns!` → `install-wake-trigger!` (llm-fn re-derived via `current-llm-fn`; compile-state via `ensure-bootstrap!` when omitted) | `register-arm-hook!` → `!arm-child-fn` spawn hook (`:2003`); `rearm-wake-triggers!` hot-reload (`:2040`) |

The ONLY functional difference is `agent/boot!` (create the entity). Order differs
cosmetically (`arm-agent!` docstring even says "same order as the re-arm loop" —
proof of hand-synced copies). Both re-derive llm-fn/compile-state defensively.

## Hard invariant: ONE path at the end, zero parallel remnants

The whole point is ONE init mechanism. When this lands there must be EXACTLY ONE
function that wires an agent up (`init-agent!`) — `boot-one-agent!`, `arm-agent!`,
and `bootstrap-turn!` are DELETED, not deprecated-and-left. No `-v2`, no "old path
for X", no shim. A post-change grep for `boot-one-agent!`/`arm-agent!`/
`bootstrap-turn!`/`hello-source`/`park-source` across `src/` + `test/` must return
ZERO hits except the git history. Every one of the call points in the inventory
below routes through `init-agent!`. This is a "don't be a dumbass / turtles all the
way down" requirement, not a nice-to-have.

## Key-namespace decision (convention)

The current code is INCONSISTENT: `boot-one-agent!`/`arm-agent!` take
`:seon.agent/llm-fn` + `:seon.agent/compile-state` (plain UNREGISTERED map keys —
3+12+4 uses across agent/client/loop), while `start-agent!` takes BARE `:mint?`/
`:llm-fn`/`:purpose` (`client.cljs:2316`). `init-agent!` is a `seon.client` fn with a
REGISTERED `::init-agent-request` schema, so its keys must be registered + correctly
namespaced. Ruling: **`:seon.agent/id` + `:seon.agent/purpose` stay `:seon.agent/*`
(they ARE agent-entity attrs, already registered). `mint?`/`llm-fn`/`compile-state`
are CLIENT execution params → register them under `::` = `:seon.client/*`**
(`::mint?` `::llm-fn` `::compile-state`). This follows "keyword ns = code ns" (the fn
lives in `seon.client`) and does not churn the existing `:seon.agent/llm-fn` plain-key
uses elsewhere (out of scope — those are inside fns being deleted or untouched
primitives). Register `::llm-fn`/`::compile-state` as `fn?`/`:any`, `::mint?` `:boolean`.

## The design — `init-agent!` (in `seon.client`)

```clojure
(schema/register! ::mint?         :boolean)
(schema/register! ::llm-fn        fn?)
(schema/register! ::compile-state :any)
(schema/register! ::init-agent-request
  [:map
   [:seon.agent/id             :seon.agent/id]
   [::mint?                     {:optional true} ::mint?]          ; default false = re-arm existing
   [:seon.agent/purpose        {:optional true} :seon.agent/purpose] ; mint-only (create! gates on fresh?)
   [::llm-fn                    {:optional true} ::llm-fn]          ; omitted → (current-llm-fn)
   [::compile-state             {:optional true} ::compile-state]]) ; omitted → (repl/ensure-bootstrap!)

;; success = boot!'s {:seon.agent/id _ :seon.agent/ns _} (mint?) OR {:seon.agent/id id} (re-arm);
;; a FAILED create (mint?) returns the db error envelope as-is (errors are values).
(schema/register! ::init-agent-response
  [:or [:map [:seon.agent/id :seon.agent/id]] :seon.db/transact-response])

(defn ^:async init-agent!
  "THE ONE way an agent comes to life or re-arms IN-PROCESS. Deterministic, no
   turn-0 ceremony. Steps (fixed order):
     1. resolve cs = compile-state | (ensure-bootstrap!), llm = llm-fn | (current-llm-fn)
     2. setup-agent-ns!  — wire the home ns requires (the reflexive-verb wiring)
     3. (mint?) agent/boot! — create the entity (+ purpose); on FAILURE do NOT
        arm/host, return the error envelope (no ghost agent, task #21 stance)
     4. install-wake-trigger! {id llm cs}
     5. runtime-id/host! id
   Runs in a (db/with-agent id …) scope so every transact carries the id.
   Ready = :idle, zero runs, fully wired, wakeable."
  {:malli/schema [:=> [:cat ::init-agent-request] ::init-agent-response]}
  [{:seon.agent/keys [id purpose] ::keys [mint? llm-fn compile-state]}] …)
```

Notes:
- **Order = the merged order.** `setup-agent-ns!` FIRST (both current fns need the ns
  before anything else that assumes it); then create (mint only); then arm; then host.
  `install-wake-trigger!` MUST be last (its own note). This is a superset order that
  satisfies both current fns.
- **Error stance preserved:** on a failed `agent/boot!`/`create!`, return the envelope
  and do NOT arm the trigger or host the id (exactly `boot-one-agent!` today —
  no-entity → no ghost roster).
- **Re-arm path (`mint? false`)** skips `agent/boot!` entirely — it wires the ns +
  arms + hosts an EXISTING entity (exactly `arm-agent!` today). `setup-agent-ns!` is
  idempotent, `install-wake-trigger!` is idempotent (unlistens prior key) — safe to
  re-run per hot reload.
- **No turn 0.** `init-agent!` does NOT open a run or eval a greeting. The agent is
  `:idle` the moment its entity + trigger exist.

## Call points — EVERY one, and how it changes

### Production (`src/seon/client.cljs`)

1. **`start-agent!` mint loop (`:2434`)** — replace `(boot-one-agent! {id llm-fn
   compile-state (purpose when minted)})` with `(init-agent! {:seon.agent/id aid
   ::mint? (boolean (some #{aid} minted-ids)) ::llm-fn llm-fn
   ::compile-state compile-state :seon.agent/purpose (when minted purpose)})`.
   **DELETE the `(bootstrap-turn! …)` block at `:2447-2450`** (the mint-only turn 0).
   Keep the root system-view seed (`:2458`, unrelated).
2. **`register-arm-hook!` (`:2003`)** — `(arm-agent! {:seon.agent/id child-id})` →
   `(init-agent! {:seon.agent/id child-id ::mint? false})`. (Spawn hook: the child
   ENTITY already exists — `start!`/`create!` made it — so re-arm, not mint.)
3. **`rearm-wake-triggers!` (`:2040`)** — `(arm-agent! {:seon.agent/id id
   :seon.agent/compile-state cs})` → `(init-agent! {:seon.agent/id id ::mint? false
   ::compile-state cs})`.
4. **DELETE the fns:** `boot-one-agent!`, `arm-agent!`, `bootstrap-turn!`,
   `hello-source`, `park-source`. (Verify no other refs — swept: none beyond the
   above + gym.)

### Test / gym

5. **`test/seon/gym/driver.cljs:1642` `ensure-agent!`** — replace `(client/bootstrap-turn!
   {id compile-state})` with nothing (delete the call). The comment block `:1618-1623`
   about "boot parity turn 0" updates: minted agents no longer run a turn 0. The
   `setup-agent-ns!` call at `:1638` STAYS (the gym wires the ns itself; it does not
   go through `init-agent!`). If `ensure-agent!` relied on the turn-0 run existing,
   confirm the scratch agent is `:idle` with no run (it is — create! + no run = idle).
6. **`test/seon/gym/driver_test.cljs:992`** — the `:b-sent-greeting-and-reply-to-user`
   predicate `[:count 2]` (greeting + reply) → `[:count 1]` (reply only). Re-key its
   name/comment to drop "greeting" (it still pins the double-identity-join direction bug).
7. **Gym cause-scoping + `agent-reply-text` q-from filter** (`driver.cljs` 760/803/809/
   860/886/1157) — LEAVE AS-IS this patch (they become vacuous but stay correct; a
   separate cleanup can simplify). Minimizes churn/risk.

### Not touched (verified)

- `setup-agent-ns!` / `install-wake-trigger!` direct callers in tests
  (`agent_loop_test`, `debug_test`, `eval/*_test`) — they test the PRIMITIVES
  directly, do not route through the boot fns. Unaffected.
- `agent/boot!` / `create!` — unchanged (called by `init-agent!` on the mint path).
- `agent_lifecycle_test.cljs:271/282` — tests `agent/boot!` directly; unaffected.

## `/solve` fixes (fold into the same commit set)

With the greeting gone, also fix the endpoint (`serve.cljs`):
- **Simplify the boot-idle heuristic** — `latest-run-start-ms` was skipping the boot
  greeting run; with no boot run it's near-moot. Keep the "run started ≥ injected-at"
  guard (still correctly rejects the pre-injection state) but drop the greeting-run
  rationale + fix the stale comments.
- **Timeout honesty (defect 3):** on `(> elapsed timeout-ms)` exit, emit
  `:closed_reason "timeout"` + `:timed_out true` (NEVER a stale `:waited`/`:completed`),
  AND close/interrupt the orphaned run (`run/close-run!` with `:closed-reason
  :superseded` or a new `:interrupted`) so it stops burning DeepSeek tokens.
- **Single snapshot (defect 2):** `(let [db @db/*conn*] …)` once per poll, pass it to
  both `derive-state` and the run-start read.

## Verification bar (live-prove EACH before commit — FULLY tested)

0. **ONE PATH gate (grep):** `grep -rn 'boot-one-agent!\|arm-agent!\|bootstrap-turn!\|
   hello-source\|park-source' src/ test/` returns ZERO hits. Exactly one init fn
   (`init-agent!`) exists; no parallel/old/`-v2` path remains.
1. **Mint** — `/agents/new` (or `/solve`) mints an agent that is `:idle` with ZERO
   runs and an EMPTY message log (no greeting). Confirm via DB read.
2. **Wake/resume** — send that agent a message; it opens run #1, runs, replies. A
   parked (idle) agent wakes on a new message.
3. **Hot-reload re-arm** — trigger `rearm-wake-triggers!` (a reload); the existing
   agents re-arm through `init-agent! mint? false` and still wake. (The `arm-agent!`
   path is load-bearing — prove it.)
4. **Spawn** — an agent `start!`s a child; the child arms (via the hook →
   `init-agent! mint? false`) and a message to it lands (not stranded).
5. **Gym suite green** — `bin/test-cljs` (the `[:count 2]→1` pin + `ensure-agent!`
   edit land; nothing else regresses).
6. **UI** — the world roster + `/agent/{id}` render a zero-run agent (idle, empty
   transcript placeholder) — no greeting-dependent render.
7. **`/solve` smoke** — known-answer task returns the ANSWER (not greeting),
   `:completed`, real turns; AND the 2s-timeout case returns `:closed_reason
   "timeout"` + `:timed_out true` (honest), with the orphaned run closed.

## Risk / stop conditions

- Shared `seon.client` on a multi-agent tree — surgical, explicit pathspecs, never
  `git add -A` (peers have staged work). Needs `bin/seon cluster reset default` to
  load the boot-path change (also clears the smoke's scratch agents).
- If removing turn 0 surfaces a hidden assumption not in the blast-radius map (some
  render/derive NPE on zero runs), STOP and report — do not force it.
- If the hot-reload re-arm or spawn path breaks through the unified fn, STOP — that's
  the highest-risk merge (two callers with different compile-state/llm-fn provenance).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
