---
type: reference
status: active
tags: [reference, agent, database, flow]
---

# Platform review of the reactive-interface PRD — decisions record (2026-06-03)

> Point-in-time record of the platform track's review of
> [reactive-interface-prd-2026-06-03](reactive-interface-prd-2026-06-03.md):
> who decided what and why. The PRD is the living spec (resolutions folded in);
> this note preserves the reasoning + attribution the spec drops — same rationale
> as writing research to disk. Both tracks ratified the split below.

## Verdict

We are on the same page. The PRD's interface matches the P1 seam exactly —
`on-tx!` as the per-conn `listen!` callback, `changed-summaries` as a second
event type riding the per-DB broadcast (raw `tx` kept), read-only hook, basis-t
catch-up. The §7 platform-fit table maps 1:1 onto the P1 items. "MVP runs M1
headless ∥ platform runs P1 → converge at M3" is sound; the tracks don't block
each other.

Grounded against the live code this review: `::pub-chan` is `[:fn nil?]` (forced
nil, Wave-4 placeholder); `register-agent!` + the `{agent-id → db-name}` atom
already exist in `session.clj` (conn-resolution mechanism present, just not called
from `wire.clj`).

## §10 answers (platform → reactive)

1. **Registry wired into `wire.clj` in the same drop as the hook? — YES.** P1
   item 1 (conn-resolution-by-agent-id) and item 5 (the `listen!` hook) land
   together; they're interdependent. The mechanism exists
   (`(get @!registry (get @!agents agent-id))`); P1 wires it into `handle-op`. If
   anything slips, conn-resolution lands first (it unblocks
   `register-subscription`); the stub offer is a backstop only. (= §7 flag 1.)
2. **`::pub-chan [:fn nil?]`? — REMOVE it, don't widen.** Dead forced-nil
   placeholder. Per-DB broadcast (P1 item 4) is an `OutputStream` subscriber-set
   in `broadcast.clj` keyed by db-name, NOT a core.async channel on the registry
   entry. P1 deletes the slot (no-legacy rule); broadcast routing lives in
   `broadcast.clj`. It will not survive P1 as `[:fn nil?]`. (= §7 flag 2.)
3. **UI/consumer read model is plain `pull`/subscription? — CONFIRMED.** Summaries
   are persisted `:seon.render/*` datoms read by normal `pull`/`q`; consumers are
   driven by the writeback tx. No recompute-on-read, no different read model.
4. **`changed-summaries` carries originating `request-id`? — YES** (and it's
   load-bearing — see issue 1). Carried at the event level (one commit → one
   event); fits the envelope.
5. **Distinct `listen!` keys? — CONFIRMED.** Platform raw broadcast under
   `::raw-broadcast`, engine under `::reactive`. Today's raw broadcast is
   imperative (no collision yet); P1 moves it into a `::raw-broadcast` listener so
   both fire off the same `TxReport`. (= coordination item 3.)

## §7 flags

- Flag 1 (registry wired, not just renamed) → Q1: committed for P1.
- Flag 2 (`::pub-chan` throws) → Q2: removed in P1.
- Flag 3 (read-only-hook tension resolved by CLJS-render default) → agreed, no
  action.

## Coordination items (the shared seam / contract)

1. **`:seon.agent/id` — platform registers, reactive references.** Flipped from
   the PRD's proposal for load-order safety: the registry is foundational (the
   engine depends on it, not vice-versa) and P1 lands first and needs the id for
   routing. Platform registers the shared `:seon.agent/id` shape
   (`[:string {:min 1 :seon.db/identity true}]`) during the `session→registry`
   rename, replacing `:seon.server.session/agent-id`. One registration.
2. **`:seon.fn` server-side — reactive registers, platform load-paths it.** It
   sits with the fresh §4 schema (the subscription's `render-fn` refs it). Reactive
   registers `:seon.fn` (`/sym` identity + `/source`); platform ensures that ns is
   on the wire-server load path so `ensure-db` installs it before any guest
   transacts a `:seon.fn` datom. The JVM only stores/validates `:seon.fn`, never
   runs it.
3. **`listen!` keys** — `::raw-broadcast` (platform) + `::reactive` (reactive),
   distinct. (= Q5.)

## Issues found (fold into PRD as decisions)

1. **`changed-summaries` MUST carry `request-id`; §5.2 schema didn't.** Because
   `::raw-broadcast` and `::reactive` are independent listeners, datahike fires
   them in nondeterministic order — a guest may process `changed-summaries` before
   the raw `tx` event for the same commit, so §5.3-step-1 dedup can't rely on the
   raw event arriving first. Add `request-id` to
   `:seon.reactive/changed-summaries-event` so the guest dedups on the changed
   event itself. Correctness, not polish.
2. **The agent→cluster bind flow is a gap — and it's the lane boundary.**
   `register-subscription` resolves conn by `agent-id`, requiring
   `{agent-id → db-name}` already populated. That's `register-agent!` —
   platform-owned (host populates it on guest→cluster bind). The agent **entity**
   datom (`:seon.agent/id` + `:seon.render/*`) is reactive-owned (guest-transacted).
   Two different things sharing the id value. Ordering: `ensure-db` →
   `register-agent!` → `register-subscription`. The bind caller in V2.0 (a
   `register-agent` op / cluster-config, P2) needs pinning; socket-REPL testing
   calls it directly.
3. **Engine cache + inverted index MUST be per-conn (per-cluster), not global.**
   One JVM hosts many clusters; a global cache cross-contaminates. `on-tx!`'s `ctx`
   carries `::conn`/`::db-name` and §4.5 rebuilds per `ensure-db` — make explicit
   that cache/index are keyed by db-name (one engine state per cluster).
4. **The `:any`s need an explicit decision.** `:seon.reactive/rows [:vector :any]`,
   `:seon.render/html :any`, `:seon.subscription/clause`/`patterns`. Sean is strict
   on `:any`. These are genuine hard-to-type boundaries (heterogeneous datalog
   rows, hiccup) — surface for explicit blessing or a tighter shape, don't let
   instrumentation catch it later. Not a blocker; a decision to bank.
5. **Subscription-registration cache ordering (minor).** The handler registers the
   sub in the engine cache **after** the sub's transact returns, so the registration
   tx doesn't route to the brand-new sub.

## The hook mechanism (decouples the lanes)

P1's `ensure-db` registers `::raw-broadcast` itself, plus exposes a per-conn
extension point (an atom of `(fn [conn db-name])` hooks `ensure-db` invokes) where
`seon.server.reactive` registers its `::reactive` listener. P1 does NOT `require`
the reactive ns; the `::reactive` wire-up is the reactive track's one-line plug at
M3 convergence. Ratified by both tracks ("the extension-point means neither ns
requires the other").

## Lane split (ratified 2026-06-03)

| | Platform lane | Reactive lane |
| --- | --- | --- |
| Code | `wire.clj`, `broadcast.clj`, `session.clj`→`registry` | `seon.server.reactive` (engine, `on-tx!`, sub handlers) |
| P1 / build | conn-resolution into `wire.clj`, the `listen!` hook + extension-point, real db-name, per-DB broadcast, remove `::pub-chan`, `::raw-broadcast` listener | the per-conn two-gate engine + (later) inverted index, register/unregister-subscription, `changed-summaries` emit |
| Schema | registers `:seon.agent/id` | fresh `:seon.subscription/*` + agent render attrs; registers `:seon.fn` server-side |
| Routing/binding | `register-agent!`, agent→cluster bind (P2) | the agent entity datom; the render-fn ref |
| Also | Rust host, platform tests, this decisions-record | `reference-code/posh` (reference only), reactive tests |

**Shared seam (the contract):** the extension-point; `:seon.agent/id` (platform
registers / reactive references); `:seon.fn` (reactive registers / platform
load-paths); the `changed-summaries` event with `request-id`; raw `tx` kept;
distinct listener keys; per-conn engine state.

**Convergence:** reactive M1 (headless engine) ∥ platform P1 → M3 (reactive's wire
ops plug into platform's hook + registry).

**M1 status (reactive, reported 2026-06-03):** engine core proven in the REPL —
two-gate dispatch, 4/4 cases correct, against real JVM datahike inside a real
`d/listen!`. Next: wrap into `src/seon/server/reactive.clj` (proper `on-tx!`
signature, fully-namespaced schemas, per-conn state) + tests.
