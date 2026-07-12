---
type: prd
status: active
tags: [prd, agent, web, architecture]
---

# Agent-FSM roadmap — we are here → the target

The single **we-are-here** doc, re-baselined **2026-07-02** against a full
code-verification sweep (every claim below carries file:line evidence from
live `src/` + `config/`). The target lives in the canonical design set at
`docs/seon/architecture/` — [[architecture]], [[data-model]],
[[agent-runtime]], [[ui]], [[toolkit]], [[observability]],
[[library-grounding]] — all present-tense. THIS doc holds what is actually
built, what is not, the corrections to beliefs the docs carried, and the
dependency-ordered path to close the gap. **No parallel systems: every gap
closes by a replace-in-place or a delete, never a `-v2`.**

## Shipped 2026-07-02 — this session's landings (agent-fsm → main)

Capstone before the merge. All suite-green, most live-drive verified:

- **inspect.ai tool parity COMPLETE** — `seon.agent.shell` (run + py-run,
  `SEON_SHELL`), `seon.agent.web/fetch` (undici + readability→markdown,
  blob-stored, SSRF-gated, non-2xx = `ok?`-true-with-status),
  `seon.agent.fs/edit-file` (line-range + exact-match). bash/python/file/edit/
  grep/web all exist on the fs capability template.
- **`my.blob`** — content-addressed store (`put!`/`get`/`text`/`concat!`), the
  disk tier; big text lives behind a `:my.blob/hash` ref.
- **`my.plan`** (renamed from `seon.agent.todo`) — planning graph:
  `needs`/`parent` deps, `:active`/`:blocked` status, goal/expect/pace, a
  derived position anchor, a WINDOWED render (frontier + recent tail, dropped
  interior). Drive-grounded.
- **Nothing wedges** — `race-timeout` bounds every LLM attempt + `run-loop!`
  await; a hung call fails the turn as a value, never parks. Sync-CPU residual
  = the worker tier (W1.5).
- **fn-row re-seed heals all derived fields** — the boot dedup refreshed only
  presence, not spec/source/doc; now refreshes the full field set with a
  `:core-seed` provenance guard (healed 110 drifted rows).
- **Architecture docs consolidated** — the single target set lives in
  `docs/seon/architecture/` (+ new context/observability/laws + vision
  think-in-clojure); the legacy FSM design docs archived; overview retired.
- **tx-meta provenance rule** taught across skills + data-model (provenance
  rides the tx entity, never a domain attr).

**Carried to `feature/agent-ctx`** (the next chunk): required-key resolution
at the eval boundary (Phase 1 built + green, held as a patch), current-ns
render-fn auto-run → block/tile twins, the `my.*`-as-namespace-scribed-entity
composition, canvas = last-updated-tile. Design:
`research/explicit-deps-injection-2026-07-02.md`.

**Still open (agent-ctx or follow-up):** root-world-at-`/` (W3), spawn
capability gate + roles (W4), observability turn-capture (W1),
`:seon.agent/purpose` → `:my.agent/purpose` (W7 tail).

## Baseline — VERIFIED SHIPPED (do not rebuild, do not re-litigate)

- **Config-driven agent-init.** ONE manifest drives the whole agent context:
  `seon.config/resolve-agent-context` (config.cljs:712) →
  `seed-default-ctx!` (agent.cljs:467); `:seon.config/agent-context` /
  `root-context` / `namespaces` / `render` / `routes` live in
  `config/system.edn` + `config/acme.edn`. `#profile`/`loadouts` are ripped
  as mechanisms (one stale comment survives, client.cljs:2182).
- **Context model.** `seon.agent.ctx` blocks, seed-COPY into
  `:seon.agent/ctx` at creation, priority-sorted render, override =
  scope-aware variadic `install!`/`remove!`. No provider, no render-merge
  (the old roadmap's Phase 1–2 keystone: DONE).
- **Loop / run / turn.** Two independent bounds enforced (turn-limit +
  deadline watchdog, run.cljs:19-46); every work tx leads with the in-tx
  `:db.fn/cas` work-fence (run.cljs:198-201, turn.cljs:244); the ONE ticker
  (schedule.cljs:226); cron-as-data with `:fn` symbols; bootstrap = a quiet
  `:seon.eval` turn (loop.cljs:459), no seeded greeting message.
- **Per-agent LLM.** `:seon.ai/agent-provider` + `:seon.ai/agent-thinking`
  overlays (ai.cljs:244-370), routed per turn (client.cljs:1927,1962) —
  the lever the forensic-agent design rides on.
- **Transcript.** Age-band eviction + eval `result-decay`
  (transcript.cljs:62-84), byte-frozen aged clips, dials per cluster
  (system.edn:172; acme.edn:89-91).
- **Namespace context.** Three render tiers in `namespaces-block`
  (namespaces.cljs:375-439): FULL real source = the current ns + per-agent
  `::full-source` pins; COMPACT CARD = the current ns's `:require`s;
  DROPPED = the rest. The signature-whitelist mode was killed; presence-sets
  (`::full-source`/`::with-tests`) + `:seon.config/namespaces` are the
  curation dials (`:always` is boot-storage selection, NOT render
  selection).
- **Web.** reitit router derived from `:seon.route/*` datoms
  (router.cljs), the gzip whole-element morph channel + time-travel bar
  (datastar.cljs), `/agent/{id}/debug` via `inspect/ctx-preview`.
- **`/call` gate.** Real, not a stub — but NARROW: it authorizes only
  agent-authored `:seon.fn`s in the caller's own home ns
  (call.cljs:83-100).
- **`/solve` + the inspect.ai bridge (case-1).** `handle-solve!`
  (serve.cljs:615) mints a scratch agent on an isolated conn via the
  unified `init-agent!` (client.cljs:2011); honesty (timeout → empty
  reply) proven; first benchmark ran (B2 memory-QA; an output-discipline
  system-text fix lifted pass^4 0→1.0).
- **`reconcile!`.** Provenance-scoped upsert + retract-stale in one atomic
  tx (state.cljs:58-69, cascades via `retractEntity`); boot uses it for
  routes + skills with scope `#{:config}`.
- **`:kind` purge.** Zero stored entity-kind discriminators in the pod;
  value-enums (`:seon.error/kind`, `:seon.warn/kind`) deliberately remain
  (owner call #66-B pending on the word itself).

## Corrections ledger — beliefs the docs carried that are FALSE

Stated once so nobody re-asserts them:

- **`/world` is NOT retired.** `/` 302-redirects TO `/world`
  (serve.cljs:206-218); the all-agents roster is a dedicated `world-view`
  (datastar.cljs:94), not the root agent's page. The target ("/ IS the
  root agent's world", [[ui]]) is OPEN work — see W3.
- **"Compact cards were killed" and "everything renders full" are BOTH
  wrong.** The SIGNATURE-whitelist mode was killed; compact cards are LIVE
  and the default for the current ns's requires (namespaces.cljs:375-439,
  commits 164db342 + 2eeb3bd9). Beware: comments in config.cljs:65-71 and
  system.edn:26-33 still describe the older full-everywhere story — stale
  in-file comments, W6.
- **The `my.*` toolkit rename is mostly unbuilt.** Only
  `my.kb/data/ui/canvas/skills` exist; `my.files`/`my.search`/`my.shell`/
  `my.todo`/`my.test`/`my.blob` do not — verbs live under `seon.agent.*`.
  [[toolkit]] names the target; W5 owns the decision.
- **`start!` is not capability-gated** despite its docstring
  (agent.cljs:501) — it's a plain eval-callable alias of `create!`.
  Roles-as-capability-sets is unbuilt. W4.
- **`reconcile!` does not yet do backup/restore/reset** despite its
  docstring (state.cljs:5) — those verbs don't exist.
- **`SEON_PROFILE` is inert in the pod config path** (memoization keys on
  `SEON_CONFIG` only, config.cljs:341; "a variant is a SEPARATE file").
  Runbooks/skills that present it as a live selector are wrong. W6.
- Minor drift: `render/value.cljs:66,83` docstrings still claim env
  overrides that are "not pre-wired" (config.cljs:426-436).

## The gap, per design doc

- **[[observability]] — entirely spec-only; this is the current focus.**
  No `:seon.agent.turn/basis-t` (turn.cljs:54-95), reply not persisted,
  `my.blob` absent, `inspect/turn`/`turn-diff` absent (only `ctx-preview`,
  inspect.cljs:59), `SEON_DEBUG_CAPTURE` default off, `grep-graph` targets
  only fns/schemas/nses/evals (search/internal.cljs:320),
  `register-embeddable!` covers only `:seon.fn/source` + `:my.kb/body`
  (embed.clj:544,586).
- **[[toolkit]]** — shell/python/web/blob verbs missing; naming gap (above).
  Design readiness: `research/tool-designs-eval-2026-07-02.md` — shell
  BUILD-READY, python trivial after it, blob design-complete, web-fetch
  needs a short design pass, editor + browser owner-gated/deferred.
- **[[agent-runtime]]** — spawn gate + roles-as-capabilities open;
  dead-letter/hop-cap-ack, crash-supervision, tz-aware cron are the
  remaining narrow coordination gaps.
- **[[ui]]** — root-world-at-`/` convergence open (W3). Context finding
  (blob live-drive 2026-07-02): a large human paste renders verbatim in the
  transcript on later turns even after the agent blobbed it — big inbound
  message bodies should clip-with-pointer once stored (transcript render
  policy, composes with `my.blob`).
- **[[data-model]]** — `:seon.agent/purpose` → `:my.agent/purpose` move
  still open; the **`seon.agent.todo` → `my.plan` rename + planning
  redesign SHIPPED** (W7, 2026-07-02 — `51a8cab8` + `1cda2948`).

## Build path — dependency-ordered workstreams

**W1 — Observability (NOW).** Order matters: blob store first, everything
else composes with it.

1. `my.blob` — content-addressed store + `put!`/`get`/`text` verbs on the
   fs-template envelope; blob-ref-as-data (hash + token estimate).
2. Always-on turn capture: `:seon.agent.turn/rendered-as-of` (the PRE-turn
   frozen basis-t — verified 2026-07-02: tx-meta already stamps
   turn/agent provenance on every tx, so this is the ONE missing
   coordinate), prompt → blob, reply → datom-or-blob by size, volatile
   inputs (embedding-hit ids, referenced `result/<id>`s) recorded on the
   turn. The `SEON_DEBUG_CAPTURE` gate + the write-only `logs/turns`
   tree are RETIRED (2026-07-02, C17): `seon.debug` deleted, gym driver
   reads prompts by blob hash.
3. `inspect/turn` (one bundle: prompt, as-of re-render, reply, evals,
   usage, visible messages) + `inspect/turn-diff` (block-level render diff
   + `db/since` datom delta) + `ctx-preview` over any t.
4. Widen search: `grep-graph` targets += messages/turns/blobs;
   `register-embeddable!` += message bodies + eval narrations (same ONE
   index).
5. The forensic agent: a seed verb composing `/solve` scratch-conn
   isolation + as-of + reconstructed ctx + a debug-brief block + a
   thinking model via per-agent provider.

**W1.4 — Explicit deps + auto-run (dedicated agent, in flight).** Two joined
pieces (design [[context]] "Explicit dependencies" + "Auto-run"): (1) the
**auto-injection wrapper** — a map-in fn declares injectables (`:seon.db/db`,
`:seon.agent/id`, `:seon.render/at`) as optional request keys; the eval
boundary fills declared-but-absent ones from a small registry, riding the ONE
Malli instrumentation layer (inject→validate→call). Makes deps explicit,
scope declared-by-signature, values replayable — WITHOUT ripping `with-agent`/
ALS (it stays the internal injection SOURCE). (2) **Auto-run** — the render
pass queries the program graph for the current ns's render-typed fns and runs
them through the same wrapper → block/tile twins. This is the affordance/
current-ns-context mechanism ([[think-in-clojure]]). Sequence the eval/
instrument work first (low collision); the ctx/render wiring coordinates with
the my.plan rename.

**W1.5 — Wedge-proofing (verified 2026-07-02, fix in flight).** The
async-park class closes by extending the EXISTING `seon.eval/race-timeout`
to the three unbounded awaits (`call-llm!` per-attempt, `run-loop!`'s bare
awaits) + the dangling-timer clear — no new mechanism (see
[[agent-runtime]] "Nothing wedges"). The sync-CPU residual (a `(loop []
(recur))` freezes the event loop incl. the watchdog; restart-only today)
is the isolation Tier-1 worker offload — `worker_eval.cljs` already proves
`vm.runInThisContext {timeout}` preemption but is a standalone diffusion
bundle, NOT pod-wired. Also open: the pod has no `interrupt_eval` (JVM
track only); `cljs.test/*current-env*` binding straddles `await`
(runner.cljs:542) — thread env explicitly.

**W2 — inspect.ai tools.** shell → python → web-fetch (design pass first);
substrate decision (owner: pod-in-sandbox recommended by spike + review)
before container work; fiber-local `*conn*` when parallel scoring matters.

**W3 — Root/world convergence.** Make `/` the root agent's world; retire
`/world` (or alias it); update `datastar-web-ui` + `browser-automation`
skills in LOCKSTEP (they currently match code, not the target).

**W4 — Capability gate.** Route `start!`/lifecycle through `/call` grants;
roles = grant-sets queried at the gate; THEN make the docstrings honest.

**W5 — Toolkit naming (owner decision).** Either execute the `my.*` rename
in place or re-target [[toolkit]] to the `seon.agent.*` names. One pass,
no aliases left behind.

**W5.5 — Staleness-on-change class (2026-07-02).** Persisted derived state
didn't refresh when its source changed. (a) **FIXED (`f9c8686c`):** the boot
re-seed deduped `:seon.fn` rows on symbol alone, so changed derived fields
(spec/source/doc/arglists) never refreshed — both shell drives failed
identically on the stale card. Now dedups on the full field set with a
`:core-seed` provenance guard; boot healed 110 drifted rows, sweep shows 0
remaining. (b) **OPEN:** existing agents' home-ns requires don't pick up a
changed config `:seon.eval/home-requires` (root has no `my.blob`/`shell`
card; fresh children do) — config-driven per-agent state must re-reconcile
on manifest change. One rule, `reconcile!`-extended, no parallel refresh
paths.

**W6 — Truth sweep.** Fix over-claiming docstrings (`start!`, `state.cljs`,
`render/value.cljs`, `db.cljs:668,1390` "kind" vocabulary), stale comments
(config.cljs:65-71 + system.edn:26-33 "renders full" story,
client.cljs:2182 loadout), purge `SEON_PROFILE` from runbooks + dead
plumbing (`bin/seon:136-144`, `bin/test-cljs:10-13`, agent-fsm
CLAUDE.md:98-99,214), declare the `:seon.ai/agent-*` overlay keys in the
`:seon.config/agent-context` schema (open-map typo hazard); skills:
`seon-context-config` rewrite + `datahike` fix DONE (6a0a2077); author the
observability skill when W1 lands.

**W7 — my.plan rename + planning redesign — SHIPPED 2026-07-02** (commits
`51a8cab8` rename + `1cda2948` redesign). `seon.agent.todo` → `my.plan`
everywhere (src/test/config; verb `add!` → `step!`, `depends!` → `needs!`,
block `:open-todos` → `:plan`, `::owner` → `::agent`); the [[data-model]]
§5.3 redesign landed: `:my.plan/needs` dependency edges, `:my.plan/status`
`:active`/`:blocked`, goal/expect/pace attrs, the DERIVED position anchor,
and the WINDOWED render (anchor + capped open frontier + ≤5
recently-completed tail; completed interior dropped from the prompt, stays
queryable). Live-proven on a fresh default cluster via `ctx-preview`
(anchor/verify/tail render; my.plan card carries the new verbs). Still open
from this workstream: `:seon.agent/purpose` → `:my.agent/purpose`.

## Open owner decisions

- W5 toolkit naming (rename vs re-target).
- case-2 execution substrate (pod-in-Docker recommended).
- `/world` retirement timing (W3).
- In-place file-editor verb (conflicts with "agents don't edit files").
- #66-B: purge the WORD `kind` from value-enums (taste, not correctness).

## Deferred forward ideas (parked — from root-os-vision)

The elegant end-states the root/OS vision named but that are DEFERRED until the
system is stable. Settled base cases (root base case, `start!`, restart-to-idle)
already live in [[agent-runtime]]; these are the unbuilt upgrades:

- **Wake-fn-symbol router.** Per-agent `:seon.agent/wake-fn` symbol + a
  Posh-style data-driven pre-filter over the tx/wire stream (re-check only wakes
  whose tracked datoms changed; the fn is the comprehensive check). Replaces the
  current single wake gate ([[agent-runtime]] "Triggering + fencing") as the ONE
  wake mechanism. Build general, upgrade later.
- **DB-filtered-view authz.** Role enforcement by construction — an agent asking
  for a db copy gets a FILTERED view of what its role may see. The end-state of
  W4's roles-as-grant-sets; do NOT build enforcement until stable.
- **`SYSTEM.md` + markdown-file content loading.** The system message (and large
  default-block text) loaded from markdown files referenced by the manifest, so a
  consumer swaps content by swapping files. Extends the config seam (config-driven
  init is built; the file-content layer is the deferred addition).

## Paused JVM track (convergence target)

> Everything below describes the **paused** JVM main-app track (`./bin/run`,
> nREPL 7888 / HTTP 8080, embedded in-process datahike on LMDB, core.async
> flow). It is NOT the present — it is the convergence target the two tracks are
> moving toward (JVM = authoritative server + DB writer, CLJS = on-device
> replica + local fn execution). Kept here, not deleted.

### How a namespace comes alive (JVM)

When the system needs a namespace to be alive, [[../../seon/components/namespace-lifecycle]]
calls `ensure-instance!`. This creates a [[../../seon/components/context]] atom (the
namespace's mutable state container), injects it as `*ctx*` via
`intern + .setDynamic`, and wires the page render function discovered from the
code graph. HTTP routes are registered by `seon.ns.routes` via a `route-patterns`
data var consumed by the web router. The [[../../seon/components/runtime]] registry tracks
that the instance exists, its status, and when it started.

### How state changes propagate (JVM)

A namespace's state lives in its ctx atom. A `swap!` fires atom watches: one
debounces and persists to Datahike via the [[../../seon/components/database]] layer,
another triggers a global SSE broadcast (`seon.web.sse/refresh-all!`). With
`::track-clients? true`, a third watch renders per-client fragments and pushes
them over each http-kit channel; the browser (Datastar) swaps them in without a
reload. The watches, persistence scheduling, and SSE push are wired
independently in ctx.clj, not through a unified pipeline.

### How cross-namespace calls work (JVM)

When namespace A calls namespace B (especially a B in a separate agent JVM), the
call routes through [[../../seon/components/flow-topology]] via `topology/request!` —
register a promise, inject a message into the flow topology, block until reply.
If the target is a remote agent JVM, [[../../seon/components/harness]] handles the TCP
hop: serialize with Nippy, send over the socket, wait, deserialize. The response
flows back through a reply-router that delivers the promise. This is the
[[../../seon/concepts/request-reply]] pattern — one mechanism for local and remote
invocation.

### How the system boots (JVM)

Startup is orchestrated by Integrant, configured via Aero, in two phases.
**Phase 1** brings up foundational services: in-process Datahike connections
(against the LMDB store on disk), schema registration, the runtime registry, the
connection manager. **Phase 2** builds on it: the infrastructure flow starts
(with a `flow/ping` sync barrier, 5 s), the runtime database initializes, the web
server binds ports, the code-graph scanner runs its first pass in a background
future, and function instrumentation activates. Integrant's dependency graph
enforces the phasing — the dependency chain IS the readiness gate.

### Three state tracking mechanisms (JVM)

The JVM track tracks namespace state in three places: the **ctx registry** (atom
in ctx.clj — the "live state" view: ctx atom, render fn, client set, scheduler),
the **runtime registry** (atom + Datahike in runtime.clj — the "administrative"
view: started-at, status, config), and **flow/ping** (the flow topology's
"infrastructure" view of which processes run). They can disagree (running in the
registry but no ctx atom; ctx atom but no flow process), which is the
accumulated-state problem the live CLJS track avoids by deriving everything from
the DB.
