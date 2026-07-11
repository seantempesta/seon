---
type: research
status: active
tags: [research, agent, context]
---

# In-process mutable-state audit — every atom/volacanvas/set! in the CLJS pod

> **ADOPTED (2026-07-10):** V1 (the 6 config memo caches → a `:seon.config`
> singleton) is BUILT — caches deleted, caps/dials are datoms. V2
> (`shell !jobs` records → datoms) and V3 (`eval/!timeout-ms` → the singleton)
> are DEFERRED as their own small units: V3 changes `set-timeout-ms!`'s
> sync→async contract on an agent verb in the hot eval path; V2 is sizable
> (touches the jobs render + shell verbs). The injection-seam sprawl (§7)
> stays; this migration adds ONE more seam (db→config `config-view`) by the
> same sanctioned pattern.

Companion to `config-db-reactivity-audit-2026-07-10.md` (read that first for
the config-manifest half). This one answers the owner's follow-up: *"Why is it
in an atom? We should be writing to the DB and only reading from the DB. Audit
all uses of atoms too."*

Scope: `src/seon/**/*.cljs`, `src/my/**/*.cljs`, and the `.cljc` they load.
The paused JVM `.clj` track is excluded. "code says" = read from the source;
"I infer" = my reasoning where the code does not state it.

## 1. Executive summary

**Total module-level mutable holders: ~61** (`def`/`defonce` of `atom` or
`volatile!`), plus ~15 function-local `(let [x (atom …)])` accumulators that
are transient reduction helpers, not state — those are non-issues and are
bucketed out (§2, row "local accumulators").

Breakdown of the 61 module-level holders:

| Class | Count (approx) | Verdict |
|---|---|---|
| (1) SANCTIONED — genuinely stateful runtime artifact | ~45 | Correct. Cannot be datoms (live handles, compile-state, ALS, injection seams, dedup guards, in-flight fences). |
| (2) CACHE — memoized/derivable held for perf | ~5 | Mostly self-invalidating; the config caches are the exception (stale on a live edit — the owner's real concern). |
| (3) SHOULD-BE-DATOMS — behavior-changing, derivable/persistable, invisible to time-travel | ~3 | The real violations. All minor; none is agent-facing domain state. |
| (4) CONFIG-RELATED — the manifest memo caches + boot-config atoms | ~10 | Deep-dive in §3. Answers "why an atom". |

**The headline finding — and it is the honest one:** the pod's atoms are
*overwhelmingly legitimate*. Almost none holds agent-facing domain/knowledge
state — that already lives in datoms (plans, kb, turns, errors, blocks,
routes, schemas). The atoms hold exactly the four sanctioned categories the
reactive-context rule names: the DB conn, the CLJS compile-state, the
AsyncLocalStorage instances, and live socket/server handles — plus two
categories the rule *implies* but doesn't enumerate: **late-bound injection
seams** (a function stored to break a require cycle — a function is not a
datom) and **process-identity/in-flight bookkeeping** (which runs this pod
opened, which write-ids are mine, recursion fences). The `context = f(db)`
contract is honored for state; the atoms are the plumbing under it.

**The handful that actually matter:**

1. **The 6 config-manifest memo caches** (`config.cljs`) — the same finding as
   the companion audit's D5/D6. Process-global, stale on a live config edit,
   and (more importantly) the values they cache — render caps, dials, repair
   policy, ns-policy — are **not in the db at all**, so a failure's forensic
   replay / `as-of` cannot see the caps that were in force. This is the owner's
   contract violation. It is *deliberate* (owner carved render caps out as
   "process caps, not per-agent datoms", `config.cljs:94-102`) but it is the
   gap the owner is now questioning. (§3, §4-V1.)

2. **`seon.agent.shell/internal !jobs`** (`shell/internal.cljs:211`) — the
   running/finished shell-job table. The live `::shell/child` handle is
   sanctioned (a process handle is not a datom), but the *job record*
   (cmd/args/state/exit/truncated output) is process-lifetime only, invisible
   to the inspector, time-travel, and cluster-fork. The strongest genuine
   should-be-datom. (§4-V2.)

3. **`seon.eval/!timeout-ms`** (`eval.cljs:82`) — a process-global eval-timeout
   knob agents can mutate at runtime (`set-timeout-ms!`), invisible to replay.
   Minor but a real behavior-changing non-datom. (§4-V3.)

4. **`db/*conn*` single global root** (`set!` in `client.cljs`, not an atom) —
   the documented cross-agent conn-swap hazard. **Now sanctioned by ruling**
   (one-pod-per-cluster → one conn → single root is correct by construction;
   the `/solve` scratch-swap machinery was deleted). Included here because the
   task asks about `set!` on vars and because the *tx-meta + agent-id* that
   ride alongside it are correctly fiber-local via ALS — a worked example of
   the right fix (§2, §5).

Nothing here is a "parallel system for derivable agent state" of the kind the
reactive rule bans. The one systemic complexity artifact is the **sprawl of
late-bound injection atoms** (~14 of them: `!create-agent-fn`, `!mint-agent-fn`,
`!arm-child-fn`, `!tee-fn`, `!db-hooks`, `!fetch-impl`, `!lookup-impl`,
`!gemini-impl`, `!serper-impl`, `!rdeps`, router's three, `!extra-core-vars`) —
each breaks a require cycle by storing a closure. They are individually
correct; collectively they are a smell that the require graph forces a lot of
inversion. Flagged for the owner (§6), not a datom question.

## 2. Full inventory

Legend — Class: **S**=sanctioned, **C**=cache, **D**=should-be-datoms,
**CFG**=config-related. Scope: **PG**=process-global, **PA**=per-agent (via
scope), **PC**=per-cluster (== PG in one-pod-per-cluster). All module-level
holders are PG unless noted; the "race?" column flags cross-agent hazards.

| site | class | holds | writers → readers | lifecycle | race? | verdict |
|---|---|---|---|---|---|---|
| `repl.cljs:83 !compile-state` | S | the bootstrap CLJS self-host compile-state | `ensure-bootstrap!` → eval path | built once/boot, rebuilt on init-version rotate | shared, single-threaded eval | **Sanctioned.** Compile-state is the named exception; it is a live compiler object, not data. |
| `repl.cljs:90 !init-version` | S | gensym stamp pairing compile-state to eval reload | reload → `ensure-bootstrap!` | rotates on hot reload | no | Sanctioned. Hot-reload idempotency stamp. |
| `repl.cljs:92 !conn` | S | REPL-path conn slot | boot → repl helpers | set once | no | Sanctioned. Conn handle. |
| `client.cljs:401 !agent-conn` | S | the datahike conn (wire-backed) | boot → all reads | set once/boot | no | **Sanctioned.** THE conn — named exception. |
| `db/*conn*` (var, `set!` `client.cljs:2358,2443,2517,2550`) | S | current conn root (global slot) | boot re-arm → every `db/*` read | set once; re-set on reload that re-evals the def | **yes, historically** | Sanctioned by ruling (one-pod-per-cluster → one conn; scratch-swap deleted). tx-meta+agent-id ride ALS (fiber-local) — see §5. |
| `client.cljs:238 !state` | S(+D) | `{:boot-at :reload-count :heartbeat-id}` | boot/reload/heartbeat → status | boot-at once; reload-count++ per reload; heartbeat-id rotates | no | Sanctioned overall; **`:reload-count` is a derivable counter** stored in memory (minor D — reloads aren't logged as datoms, so not trivially derivable; leave it). |
| `client.cljs:1091 !extra-core-vars` | S | downstream preload's extra core vars (fn vars) | downstream boot → indexers | set at boot | no | Sanctioned — holds Vars (not data); injection of the extra-source corpus. |
| `eval.cljs:print-als` (`~:316`) | S | `AsyncLocalStorage` for per-eval print capture | install → eval-form `.run` | defonce, once | fixes race #64 | **Sanctioned** — named ALS exception; the *fix* for cross-fiber print bleed. |
| `eval.cljs:warnings-als` (`~:233`) | S | ALS for per-eval analyzer-warning capture | install → dispatcher | defonce, once | fiber-isolated | Sanctioned — ALS. |
| `eval.cljs:317 !orig-print-fns` | S | original `*print-fn*`/`*print-err-fn*` captured once | first install → dispatcher | once | no | Sanctioned — hot-reload-safe capture of process print sinks. |
| `eval.cljs:258/318 !warning/print-dispatcher-version` | S | init-version stamp for idempotent reinstall | install → itself | rotates on reload | no | Sanctioned — reload idempotency. |
| `eval.cljs:93 !next-budget-ms` | S | one-shot ms override for the very next auto-await | `budget` → `maybe-await-value` | set then cleared per form | transient call-context | Sanctioned — genuinely per-invocation, resets to nil. |
| `eval.cljs:985 !result-var-ids` | S | insertion-ordered ids of live `result/<id>` eval vars | eval → prune | grows/prunes per eval; dies with process | shared globalThis namespace | Sanctioned — tracks *volatile globalThis* vars that by definition can't be datoms; pairs with `agent/run !runs-this-process`. |
| `eval.cljs:82 !timeout-ms` | **D** | per-form eval wall-clock timeout (default 10000) | `set-timeout-ms!` (agent-callable) → eval | mutable at runtime | PG behavior knob | **Should-be-datom / config.** Changes eval behavior, not in db, invisible to replay. Minor. §4-V3. |
| `eval.cljs:175 timeout-sentinel` | S | `#js{}` identity sentinel for timeout race | const → eval | immutable | no | Sanctioned — an identity token, not state. |
| `schema.cljc:27 *schemas` | S(proj) | malli registry map of all registered schemas | `register!` → every validate | defonce; grows at boot | PG | Sanctioned runtime artifact (malli needs an in-memory registry instance). **It IS a projection** of the `:seon.schema` datom corpus, rebuilt from `register!` calls at boot — like the analyzer, code-as-data. Not a violation; do not "move to db" (malli reads this instance live). |
| `schema.cljc:35 seon-registry` | C | the one memoized composite registry instance | defonce → malli global | once | no | Cache (single instance for cheap `identical?` stomp-guard). Sanctioned. |
| `schema.cljc:208/214 !tee-fn / !last-tee` | S | late-bound register!→db tee hook + its last Promise | `seon.eval` injects → register! | injected once | no | Sanctioned — injection seam (breaks db→schema cycle) + await handle. |
| `render.cljs:311 !schema-cache` | C | `{:db … :tables …}` single-slot, identity-keyed | schema-tables → render | recomputed when db identity changes | no | **Cache, self-invalidating.** `code says` "computed once per db value". Correct perf escape hatch; no staleness (db value is immutable). |
| `error.cljs:271 !db-hooks` | S | injected transact!/basis-t persistence hooks | `seon.db` injects → record! | injected once at db load | no | Sanctioned — injection seam (db→error cycle). |
| `error.cljs:290 !pending` | S | in-memory error buffer before a conn exists | record! → flush on next persist | tier-3 volatile, drop-oldest@32 | no | **Sanctioned by construction** — exists *precisely because* the conn/hooks aren't up yet (very early boot). Cannot be a datom at that moment. |
| `error.cljs:308 !persists-inflight` | S | count of un-settled error transacts (recursion fence) | persist! → record! self-fault guard | ++/-- per persist | no | Sanctioned — in-flight fence; a counter that guards against recursive self-persist, not a reportable metric. |
| `error.cljs:395/419 !expecting-core-fault / !dev-eval-depth` | S | async depth brackets (test-fault expectation; dev-eval-in-progress) | brackets → escalate!/in-dev-eval? | ++/-- around a call span | no | Sanctioned — genuine async call-context depth (an ALS-adjacent pattern). |
| `agent.cljs:542 !arm-child-fn` | S | injected child-wake closure | client injects → start! | injected once | no | Sanctioned — injection seam (client→agent cycle). |
| `agent/loop.cljs:119 !loop-input` | S | per-run loop input, restored on re-arm | loop → transition | repopulated on re-arm | PA-keyed inside | Sanctioned — runtime loop plumbing; the run itself is datoms. `I infer` the map is keyed by run/agent so it's not a cross-agent clobber. |
| `agent/loop.cljs:623 !ticker` | S | `setInterval` handle | install → clear on re-install | one live timer | no | Sanctioned — live timer handle. |
| `agent/run.cljs:149 !runs-this-process` | S | set of run-ids opened by THIS pod process | run open → this-process-run? | grows; dies with process | PG process-identity | Sanctioned — *by definition* about "which runs' in-memory vars are live in THIS process". Cannot be a datom (it asserts a property of process memory). |
| `web/serve.cljs:65 !server` | S | bound `http.Server` | start! → stop! | one handle | no | Sanctioned — live server handle. |
| `web/serve.cljs:71 !sse-connections` | S | vector of open SSE `ServerResponse`s | connect/close → tx fan-out | grows/shrinks with clients | no | **Sanctioned** — live socket handles, not data. |
| `web/serve.cljs:95/122 !create-agent-fn / !mint-agent-fn` | S | injected agent-boot / mint closures | client injects → POST handlers | injected once | no | Sanctioned — injection seams (serve→client cycle). |
| `web/serve.cljs:97 !create-in-flight` | S | boolean creating-guard | POST /agents/new | flips per request | no | Sanctioned — request idempotency flag. |
| `web/debug.cljs:61 !sse-by-agent` | S | `{agent-id → [conns]}` debug SSE registry | connect/close → push | grows/shrinks | no | Sanctioned — live socket handles. |
| `web/debug.cljs:1009 !pending` | S | `{agent-id → timer}` push coalescer | schedule-push! | trailing 100ms timer | no | Sanctioned — debounce timer bookkeeping, self-clearing. |
| `web/datastar.cljs:58 !feeds` | S | vector of open feed `{res view-fn opened-at}` | connect/close → morph | grows/shrinks | no | Sanctioned — live SSE feed handles + their view thunks. |
| `web/datastar.cljs:172/189 !pending? / !installed?` | S | broadcast-debounce flag; listener-installed flag | schedule/install | flips | no | Sanctioned — coalescing/idempotency flags. |
| `web/router.cljs:69/75/82 !ring-handler / !same-origin-pred / !router-config` | S | cached reitit handler + injected pred + last serve-config | install!/rebuild! → dispatch | rebuilt on route-tx | no | Sanctioned — the handler is derived from route *datoms* (rebuilt on tx); the atoms cache the compiled handler + hold an injected fn. `code says` rebuild! re-derives from fresh route datoms. Correct: routes ARE datoms; this is the compiled projection + a perf cache. |
| `agent/web/internal.cljs:81/577 !policy-override / !search-config-override` | CFG | test-seam overrides for web policy / search cfg | tests set → policy/search-config | nil in prod | no | Config test-seam (§3). nil ⇒ read live config; only a hermetic test writes it. |
| `agent/web/internal.cljs:193/418/660/756 !lookup/!fetch/!gemini/!serper-impl` | S | test-seam impl overrides (DNS/fetch/gemini/serper) | tests set → call sites | nil in prod | no | Sanctioned — dependency-injection seams for hermetic tests (nil = real). |
| `agent/web/internal.cljs:357 !rdeps` | C | lazily-required readability deps | first use → extract | memoized once | no | Cache — optional-dep lazy load; degrades to regex if absent. Sanctioned. |
| `agent/shell/internal.cljs:211 !jobs` | S+**D** | `{job-id → record}` incl. live `::shell/child` handle + cmd/state/exit/output | shell verbs → status/exit | process-lifetime | PA via `::shell/agent-id` | **Hybrid.** Child handle = sanctioned (process handle). Job *record* (cmd/args/state/exit/truncated-output) = should-be-datoms — invisible to inspector/replay/fork. §4-V2. |
| `agent/fs/internal.cljs:104 !config` | CFG | fs grant config, `(env-bootstrap)` at load | boot → fs verbs (some read live env) | set at load; `fs-locked?` re-reads env live | no | Config-at-boot (§3). Held because consumed at ns-load, before conn. |
| `log.cljs:235 !config` | CFG | log file path/cap/keep | configure! → log writes | set at boot | no | Config-at-boot. `code says` moved from a dynvar because dynvars don't survive `await` in CLJS; "app-wide config, not per-agent". Consumed at ns-load. §3. |
| `my/blob.cljs:126 !dir` | CFG | blob dir path (beside cluster store) | boot/test → blob I/O | set at boot | no | Config-at-boot; atom "so an isolated harness can point it at its own dir; the live pod never changes it" (`code says`). §3. |
| `dev/runtime_id.cljc:32/33 !hosted / !cluster` | S | this process's hosted ids + cluster name (MCP resolver) | host!/cluster! → advertisement | grows; process-identity | PG | Sanctioned — process-identity for the resolver; asserts "who am I", can't be a foreign datom. |
| `store/wire.cljs:288 own-write-id set` | S | write-ids this conn issued (feed self-echo skip) | transact → listen adapter | grows/prunes | no | Sanctioned — in-flight/own-tx dedup against the broadcast feed. |
| `store/wire.cljs:446 !adapter` | S | wire adapter state (conn/replay watermark/feed-gen) | connect/feed → status | mutated continuously | no | Sanctioned — live socket + replay watermark bookkeeping; cannot be datoms (it's the transport layer feeding the store). |
| `store/internal/wire_node.cljs:47/48 !writer / !reader` | C | memoized transit-json codec instances | first use → enc/dec | once | single-threaded reuse | Cache — codec reuse, safe because transit clears per-message + pod single-threaded (`code says`). Sanctioned. |
| `worker_eval.cljs:113/506 !state / !core-names` (+ `114 !warnings` volatile) | S | the bb-SCI diffusion worker's self-host state + core-name set + per-eval warning sink | worker eval | separate process, strictly sequential | own process | Sanctioned — compile-state analog in a **separate single-threaded worker** process; `code says` sequential evals make the global warning sink race-free. |
| `render/sci.cljs:184/339/610 !bounding-warned / !source-fallback-noted / !recovering` | S | once-per-key warn dedup sets + in-flight tile-recovery guard | render → itself | grows | no | Sanctioned — dedup guards + in-flight fence; `code says` "volatile runtime state, not derivable — sanctioned". The *errors themselves* are recorded as datoms via `record!`; these only debounce log/record spam. |
| `config.cljs:433,567,748,772,826,857` — 6 memo caches | **CFG/C** | memoized `load-manifest` sections keyed by `SEON_CONFIG` | first read → ~40 accessors | `def` (not defonce) so hot-reload rotates | PG | **The owner's question.** Deep-dive §3; violation ranking §4-V1. Stale on live edit; cached values not in db. |
| `local accumulators` (~15: `eval.cljs`, `client.cljs`, `ctx.cljs:1850-52`, `ui/markdown.cljs`, `fs.cljs:592`, `diffusion/retrieval.cljs`, `search/internal.cljs`, `wire.cljs:558`, `wire_node.cljs:161-234`, etc.) | S | function-local `(let [x (atom …)])` reduction/parse accumulators | within one fn call | scoped to the call | no | **Non-issues.** Transient imperative-inside-a-fn accumulators. Idiomatic; not shared state. Excluded from concern. |

## 3. The config-atom deep-dive — "why is it an atom?"

There are **two distinct populations** the owner's question lands on.

### 3a. The 6 `load-manifest` memo caches (`config.cljs`)

`render-config-cache` (`:567`), `ns-policy-cache` (`:433`), `on-core-error-cache`
(`:748`), `repair-config-cache` (`:772`), `web-policy-cache` (`:826`),
`web-search-cache` (`:857`). Each is `(def ^:private … (atom {}))`, keyed by the
`SEON_CONFIG` env value.

**Why an atom (not the db)? Three real reasons, in order of weight:**

1. **Perf, and it is a *measured-shaped* memo, not architecture.** The cached
   thing is the result of `load-manifest` — an **aero read of
   `config/system.edn` from disk + EDN parse + `#env`/`#or` tag resolution**.
   `code says` (the accessor comments, e.g. `:392`, `:432`, `:571`) the memo
   exists so a per-render / per-fault / per-eval read doesn't re-parse the file.
   These accessors are hot: render caps run on *every* value render; the
   ns-policy runs on every namespace-block render; the on-core-error dial on
   every `:core` fault; repair policy on every agent eval. So the cache is a
   real perf escape hatch over a disk+parse cost. `I infer` the disk read is the
   dominant cost (aero + tag resolution), not the map lookup.

2. **`def` not `defonce` — deliberately, for hot-reload.** `code says`
   (`:771`, `:825`, `:432`) they are `def` so a hot reload of `seon.config`
   *rotates* the cache (picks up an edited manifest on the next reload). A pod
   restart obviously re-reads. `reset-render-cache!` (`:581`) exists *only for
   tests*. **This is the code-level WHY of the operational note "config edit →
   restart pod"** (companion audit B4/D6): a bare file edit with no reload/restart
   is invisible.

3. **Habit/history is NOT the reason** — the memo is justified by the disk cost.
   The *architectural* problem is not that it's memoized; it's what §3c covers:
   **the cached values were never written to the db at all.**

**What would break if these were transacted to the db and read from the db
instead:** nothing at *render* time — render-cap/dial reads happen when the conn
is up, so `(db/query …)` is available and sub-ms (memory-tier). The catch is
**bootstrap order** for a subset (§3c). The migration is real but bounded.

### 3b. The boot-config atoms (`fs/internal !config`, `log !config`,
`blob !dir`, web `!policy-override`/`!search-config-override`)

These hold config **values** (a grant map, a log path, a blob dir), or a
**test-override slot** (nil in prod → read live config).

**Why atoms?** Two reasons, both stated in the source:

- **Consumed at ns-load, before the conn exists.** `fs/internal !config` is
  `(atom (or (env-bootstrap) {}))` — evaluated when the ns loads. `log !config`
  `code says` it moved *out of a dynvar into an atom* because "dynvars don't
  reliably survive `await` in CLJS" and it's "app-wide config, not per-agent
  runtime state". `blob !dir` is set from `SEON_CLUSTER_DIR` at load.
- **A harness override seam.** `blob !dir` `code says` "an atom so an isolated
  harness (a hermetic test, bin/acme) can point it at its own dir; the live pod
  never changes it." The web `!*-override` atoms are the same: nil ⇒ live config,
  a test writes a literal to avoid staging a config file.

`I infer`: these are *not* the owner's target — they are boot-wiring and test
seams, not runtime-mutated behavior. The `fs-locked?` path even re-reads env
*live* on every call (`code says`), which is correct (the host owns that knob).

### 3c. Bootstrap order — which config is consumed BEFORE the conn

This is the crux of any "config → db at boot, read only from db" migration. The
values that **cannot** be a db read *at the moment they're first needed*:

- **`:seon.config/on-core-error` dial** (`error.cljs:462`) — a `:core` fault can
  fire *during boot, before the store connects* (a fault in store-connect
  itself). The escalation must read the dial with **no conn**. `code says`
  `error.cljs` sits below `seon.db` in the require graph precisely so it never
  depends on the conn.
- **`:seon.config/namespaces` policy** — read by the boot indexer
  (`client.cljs`) as it builds the initial corpus; `I infer` this runs around
  conn-up but the indexer's own selection happens at boot.
- **`fs/internal !config`, `log !config`, `blob !dir`** — consumed at ns-load
  (before any agent, before the conn).

Everything *else* the caches serve (render caps, value-render knobs, repair
policy, watchdog, schedule-breaker, web policy/search) is consumed **after** the
conn is up (at render / eval / fault / fetch time) — those *could* be db reads
today with zero bootstrap risk.

**So the precise answer to "why an atom":** the memo caches are an atom for
*disk-read perf* (§3a-1) with a `def`-rotation hot-reload story (§3a-2); the
boot-config atoms are an atom because they're consumed *before the conn exists*
and double as a test-override seam (§3b, §3c). Neither is "habit". But **the
config values are not in the db**, which is the actual contract gap — and that
gap is fixable for the after-conn majority without touching the before-conn
few.

## 4. Ranked violations (should-be-datoms + stale-cache hazards)

**V1 — Config caps/dials are not in the db (HIGH; == companion D5/D6).**
`config.cljs` 6 memo caches serve render caps, value-render knobs, repair
policy, ns-policy, on-core-error dial, web policy. These change agent-visible
behavior yet are **invisible to `as-of` / forensic replay / cluster-fork** — a
bug reproduced at a past basis-t sees today's caps, not the caps in force at the
failure. Stale on a live edit until reload/restart.
**db-backed fix shape:** transact a **`:seon.config` singleton entity** at boot
(one entity, one datom per resolved knob, stamped `:seon.db/origin :config` like
routes/skills already are). After-conn readers query it (sub-ms, memory tier).
Keep a tiny boot-time in-memory seed only for the before-conn few (§3c) — read
from that seed *once* to write the db, then everyone reads the db. Render caps
become replay-visible; a live edit reaches agents on the next boot-reconcile
exactly as routes/skills do today.

**V2 — `shell/internal !jobs` records are process-only (MED).** Running and
finished shell jobs (`shell/internal.cljs:211`) — cmd, args, state, exit code,
truncated output — live only in this atom, per-agent by `::shell/agent-id` but
**invisible to the inspector, time-travel, and fork**. A forensic replay of an
agent turn that shelled out cannot see what it ran or got back.
**db-backed fix shape:** persist a `:seon.shell.job/*` entity per job
(`::agent`, `::cmd`, `::args`, `::state`, `::exit`, `::started-at`, `::ended-at`,
plus output as a blob-ref for the truncation cap) on start and on exit
transition — the atom keeps *only* the live `::shell/child` handle (which
genuinely can't be a datom), the record derives from db. `code says` the exit
path already scopes a testrun persist to the agent, so the write seam exists.
This mirrors how errors/turns are already datoms.

**V3 — `eval/!timeout-ms` is a runtime-mutated non-datom knob (LOW).**
`eval.cljs:82`, agents call `set-timeout-ms!`. Behavior-changing, PG, invisible
to replay; a form that timed out in the past can't be reproduced with the
timeout it actually had.
**db-backed fix shape:** fold into the V1 `:seon.config` singleton (it's a
render/eval cap like the others); the one-shot `!next-budget-ms` stays an atom
(genuine per-invocation call-context).

**Stale-cache hazards (not violations, noted):** the config memo caches (V1) are
the only *stale-able* caches. `render.cljs:!schema-cache`, `store` codec
memos, `!rdeps`, `seon-registry` are all either identity-keyed (invalidate on db
value change) or immutable-once — no staleness.

**`:reload-count` (`client.cljs:238`)** — a stored counter, technically
derivable, but hot-reloads aren't logged as datoms so it's not free to derive.
Leave it; it's diagnostics, not agent-facing.

## 5. Multi-agent / scope hazards

One pod per cluster (settled ruling) means **process-global == cluster-global**,
so PG mutable state is *cluster-wide config*, which is mostly correct — every
agent in a cluster should see the same caps, routes, schemas, log path. The
hazards are only where per-*agent* or per-*fiber* isolation is needed:

- **`db/*conn*` single global root (`set!`)** — historically THE cross-agent
  hazard (turn-6 recall / `/solve` conn-swap collision, memory notes). **Now
  sanctioned**: one conn per cluster ⇒ single root is correct by construction;
  the scratch-swap machinery was *deleted*, not fixed. The values that *do* vary
  per agent/fiber — **tx-meta and agent-id** — correctly ride
  `AsyncLocalStorage` (`db/internal.cljs`), which V8 makes fiber-local across
  `await`s. This is the model answer: cluster-wide handle = global root;
  per-fiber context = ALS.
- **`eval.cljs` print/warning capture** — was a process-global `set! *print-fn*`
  straddling an `await`, which bled one agent's prints into another's bucket
  (race #64, `code says`). **Fixed** by moving to per-fiber ALS. Another worked
  example of the right fix.
- **`shell/internal !jobs`** — PG map keyed by job-id, scoped per agent via
  `::shell/agent-id`. Job-ids are unique, so no clobber, but cross-agent
  *visibility* (agent B can `status` agent A's job) is `I infer` intended /
  harmless. The datom fix (V2) would make the scoping a query filter.
- **`!result-var-ids` + globalThis `result/<id>` vars** — PG shared namespace
  across agents. Ids are unique; cross-agent visibility of eval vars is the
  documented shared-runtime behavior, not a race.

No PG holder was found that a *concurrent* agent could clobber to corrupt
another's state — the two that could (conn, print) were already moved to ALS or
fixed by the single-conn ruling.

## 6. What a full "config → db at boot, all reads from db" migration entails

Ordered, with risks and what legitimately stays process-local.

1. **Define a `:seon.config` singleton schema** — one entity, one registered
   attr per resolved knob (render caps, value-render knobs, repair policy,
   watchdog, schedule-breaker, ns-policy, on-core-error dial, eval timeout).
   Stamp `:seon.db/origin :config` so the existing `state/reconcile!`
   (`#{:config}` scope) heals/retracts it exactly like routes/skills. *This is
   the reuse — do not build a second reconcile.*

2. **Seed at boot inside `core-index-tx` / the `#{:config}` reconcile**
   (`client.cljs:2492-2514`) — resolve the manifest once (the existing
   `load-manifest`), transact the singleton. `code says` routes+skills already
   ride this path; config knobs join it.

3. **Repoint the ~40 after-conn accessors** to `(db/query …)` against the
   singleton. Keep the memo *shape* if a render-time `db/query` ever measures
   as hot (it won't at these datom counts — memory tier sub-ms), but the cache
   now keys on db value/identity (self-invalidating), not on `SEON_CONFIG`
   (never-invalidating). The staleness (V1/D6) disappears.

4. **Handle the before-conn few (§3c) explicitly** — the on-core-error dial, the
   boot ns-policy, and fs/log/blob config are read *before* the conn. Keep a
   *boot-time in-memory seed* for exactly these, read it *once* to write the db,
   and after that read from the db. i.e. the atom becomes a **seed source, not
   the runtime source of truth** — the same "config seeds the db, then derive"
   the owner asked for, honored even for the bootstrap edge.

5. **Delete the 6 `SEON_CONFIG`-keyed memo caches** and `reset-render-cache!`
   (test-only). The db value is now the cache key.

**Risks:**

- **Ordering** — the on-core-error dial *must* work if the store-connect itself
  faults. The boot seed (step 4) covers this: the dial's in-memory seed is set
  before connect is attempted; the db copy is a bonus for replay-visibility.
  Getting this wrong = a boot-time `:core` fault can't read its dial.
- **Test fixtures** — hermetic tests today write `!policy-override` /
  `render-config-cache` to inject config without a file. Post-migration they
  transact the singleton into their in-memory conn instead — *more* honest, but
  every such fixture must be updated. The web `!*-override` atoms can stay as
  test seams (nil ⇒ read the db) with minimal change.
- **Hot-reload** — the `def`-rotation trick disappears; a live config edit now
  reaches agents via the boot `#{:config}` reconcile (restart) OR a live
  `db/transact!` to the singleton (no restart) — *better* than today.

**What legitimately stays process-local (do NOT migrate):** the conn, the
compile-state, the ALS instances, every live socket/server/timer/child handle,
the injection-seam closures (functions aren't datoms), the in-flight fences
(`!persists-inflight`, own-write-id set, `!create-in-flight`), the
process-identity sets (`!runs-this-process`, `!hosted`/`!cluster`,
`!result-var-ids`), the dedup guards (`render/sci` warned sets), and the malli
`*schemas` registry instance (a live projection of the schema datoms, rebuilt at
boot — the code-as-data pattern, not a violation). These are the reactive rule's
named exceptions plus its two implied ones (injection seams, process identity).

## 7. Complexity artifacts found (owner-standing directive)

- **Injection-seam sprawl (~14 atoms)** — `!create-agent-fn`, `!mint-agent-fn`
  (`web/serve`), `!arm-child-fn` (`agent`), `!tee-fn` (`schema`), `!db-hooks`
  (`error`), `!fetch-impl`/`!lookup-impl`/`!gemini-impl`/`!serper-impl`/`!rdeps`
  (`agent/web/internal`), `!ring-handler`/`!same-origin-pred`/`!router-config`
  (`web/router`), `!extra-core-vars` (`client`). Each stores a closure to break
  a require cycle. Individually correct; collectively a signal that the require
  graph forces a lot of inversion. **Recommendation:** leave as-is (they hold
  functions, not data — not a datom question), but worth an owner note that the
  cycle-breaking pattern is this pervasive. Not fixable by "move to db".
- **Two config populations, two shapes** — the 6 memo caches vs the boot-config
  atoms serve the same "config" concept with different mechanisms.
  **Recommendation:** the §6 migration unifies them onto the `:seon.config`
  singleton + a boot seed. One mechanism.
- **`shell/internal !jobs` is a hybrid** (live handle + should-be-datom record).
  **Recommendation:** split per V2 — atom holds only the child handle, record is
  datoms. Ask the owner before doing it (it's tool-lane surface).

---

## Verdict

The pod is **not** riddled with atom-backed derivable state. Agent-facing
domain state already lives in datoms; the atoms are the sanctioned plumbing.
The owner's instinct is right about **one** population — the config manifest
(the 6 memo caches + the values they hold, never written to the db) — and that
is a bounded, worthwhile migration (§6) that reuses the existing `#{:config}`
reconcile and makes caps replay-visible. Beyond config, only `shell !jobs`
(record → datoms) and `eval/!timeout-ms` (fold into the config singleton) are
genuine should-be-datoms, both minor. Everything else — conn, compile-state,
ALS, live sockets, injection seams, in-flight fences, process identity, dedup
guards, the malli registry projection — is correctly process-local and cannot
be a datom.
