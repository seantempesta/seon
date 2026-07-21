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

## Platform response (2026-06-03, after commit `019d594`)

> Platform read of the **live** code, reconciling
> [m3-prep](m3-prep-2026-06-03.md)'s R1–R4. m3-prep was snapshotted *"as of
> commit `03f5135`"*; commit `019d594` ("conn-routing + listen! hook + per-DB
> broadcast") landed **after** it, so several "gated on platform" items are
> already done. Verified against `wire.clj`, `reactive.clj`, `store.clj`,
> `registry.clj`, `broadcast.clj`, `deps.edn`.

### Already done in live code (m3-prep listed as gated — now closed)

- **Conn-resolution wired into `wire.clj`.** `resolve-conn-for-req` +
  `handle-req` route every op by `agent-id`/`db-name` (`wire.clj:541-573`).
- **`d/listen!` + `::raw-broadcast` listener per conn.** Installed via the
  on-ensure-db hook (`wire.clj:318-325`), read-only, emits the db-name-tagged
  raw `tx` event (`raw-broadcast-listener-fn`, `wire.clj:299-311`).
- **Real `db-name` on the event** (no more `"default"`) — `wire.clj:280-288`.

### R1 — request-id on single `transact`: **DONE, not open.**

The single `transact` handler stamps `:seon.db/request-id` into tx-meta
(`wire.clj:363-367`), mirroring `transact-batch`; `seed-base-schema!`
(`wire.clj:70-79`) installs that attr so `:schema-flexibility :write` accepts it;
`on-tx!` reads it (`reactive.clj:230`). The own-tx-dedup chain (review issue 1)
is intact end-to-end. **Platform action:** add a regression test pinning
request-id → tx-meta → `changed-summaries` (it was fixed without one, and it's
load-bearing). No production change.

### R2 — `:schema-flexibility :write` install seam: **the real open item.**

`store.clj:121` opens every cluster conn `:schema-flexibility :write`, so
datahike rejects any attr lacking an installed `:db/ident`. `seed-base-schema!`
installs only `:seon.db/request-id` — **not** reactive's `:seon.subscription/*`
/ `:seon.fn/*` / `:seon.render/ai`, so a live `register-subscription!`
(`reactive.clj:192`) would fail against a real cluster conn (tests dodge it with
`:schema-flexibility :read`, `reactive_test.clj:17`). Resolution, in two parts:

- **Reactive lane:** install your own attrs via your **own** on-ensure-db hook,
  mirroring platform's `raw-broadcast-hook-installed?` (`wire.clj:318-325`).
  Platform will **not** add reactive attrs to `seed-base-schema!` — that would
  recouple platform → reactive and defeat the extension point. (Platform can
  expose a tiny `install-schema!` helper if you'd rather derive the `:db/ident`
  vector from the registered malli than hand-write it — say the word.)
- **Platform lane (the genuine deliverable):** the wire-server boots via
  `:writer` → `-m seon.server.wire` (`deps.edn:140`), which **never loads**
  `seon.server.reactive` (wire.clj doesn't `require` it, by design). So at
  runtime reactive's schema registrations **and** its on-ensure-db hook never
  fire — this is also coordination-item-2 (`:seon.fn` on the load path).
  Platform adds a thin boot entry that loads **both** `wire` and `reactive`
  while keeping `wire.clj` itself decoupled, and points `:writer` at it. Done by
  the orchestrator directly (shared boot seam), kaocha-verified.

### `register-subscription` / `unregister-subscription` ops — ownership

These `handle-op` methods land in `wire.clj` (platform's file) but are M3
reactive logic. **Decision: platform writes the thin wrappers** (resolve conn
via registry, Transit-decode, look up the per-db engine state, delegate to
reactive's pure fns); **reactive owns the pure logic in `reactive.clj`**
(m3-prep 2d). The wrappers are written against the agreed pure-fn signatures, so
reactive's `handle-register-subscription` / `handle-unregister-subscription` /
event-builder land first (or in parallel against a fixed signature).

### db-name keys — confirmed distinct, no unification

Three intentionally separate keys, no reuse: `:seon.server.reactive/db-name`
(string, the wire event), `:seon.server.store/db-name` (keyword, store config),
`:seon.server.registry/db-name` (keyword, routing). The reactive wire event's
db-name being a string is correct. ✓

### What MVP can resume NOW

All of m3-prep §2 (2a–2e) is headless and lives in reactive-lane files
(`reactive.clj` + `reactive_test.clj`) — **it never blocked on platform and can
proceed immediately**: register the subscription, `changed-summaries`,
`:seon.fn`, and summary schemas; write the `register/unregister-subscription`
**pure fns** and the event-builder with direct-call tests; and build the `:schema-flexibility
:write` install seam (your own on-ensure-db hook). The only ordering touchpoint:
publish the pure-fn signatures (2d) so platform's thin `handle-op` wrappers bind
to them. Platform's boot/load-path change and R1 regression test touch only
platform-lane files (`deps.edn` `:writer`, a new boot ns, `wire.clj` wrappers, a
platform test) — **zero overlap with reactive's files**, so both tracks run
concurrently.

### Platform delivered (commit `bb06be6`)

- **R1 wire-side regression test** — `test/seon/server/wire_request_id_test.clj`
  pins that `handle-op "transact"` stamps `:seon.db/request-id` into the commit's
  tx-meta (incl. the production `:schema-flexibility :write` path sealed by
  `seed-base-schema!`). Complements reactive's `request-id-rides-the-event`.
- **R2 platform half — the boot load-path** — `src/seon/server/boot.clj` loads
  **both** `seon.server.wire` and `seon.server.reactive` and delegates `-main` to
  `wire/-main`; `:writer` now boots `-m seon.server.boot` (`wire.clj` stays
  reactive-free). **Consequence for MVP:** your `register-on-ensure-db-hook!` (the
  integration plug) and your schema registrations now actually fire at
  server start — they didn't before (the server booted `-m seon.server.wire`,
  which never loaded reactive). Verified fresh-JVM: boot ns loads under the
  minimal `:writer` classpath; 13 server-test ns green.
- **R2 reactive half — yours:** `install-reactive-schema!` via your own
  on-ensure-db hook (the `:schema-flexibility :write` `:db/ident` install).
- **Phase B (platform) — staged, gated on `reactive/engine-state`.** The two
  `register-subscription` / `unregister-subscription` `handle-op` wrappers land in
  `seon.server.boot` (it already requires both wire + reactive), binding to the
  published signatures `(reactive/register-subscription state conn request)` /
  `(reactive/unregister-subscription state conn request)`. They need
  `(reactive/engine-state db-name)` (your NEXT item 1) to reach the per-db engine
  state the `::reactive` listener uses. Ping when `engine-state` lands.
