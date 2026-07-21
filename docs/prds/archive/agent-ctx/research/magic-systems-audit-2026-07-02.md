---
type: research
status: active
tags: [research, agent]
---

# Magic-systems audit — what every special case papers over (2026-07-02)

> AUDIT unit, read-only. Trigger: the /solve scratch-seed conn-swap collisions
> ([[calibration-run-2026-07-02]]) and the owner's ask: "this scratch seed shit
> seems fragile — I want to understand all 'special' or magic systems we have
> and see what they are papering over." Scope: the ACTIVE pod
> (`src/seon/*.cljs`), the wire-server (`src/seon/server/*.clj`), shared
> `.cljc`. Every claim cites file:line read during this audit; costs cite
> issue notes or logged incidents. Two parallel fix agents were in flight
> during this audit (the origin-forge warn; SCI fail-loud) — noted, not
> touched. Two mid-audit additions folded in from the coordinator: the
> /solve `schema/*schemas` + shared-compile-state globals
> ([[multi-db-wire-server-swarms-2026-07-02]]) and the router
> cached-projection regression (acme-harness-agents-route-drift Part 2).

## TL;DR — the ranked top 5 + the one load-bearing root fix

Ranked by blast radius (what breaks, how silently, how wide):

1. **The /solve ambient-global cluster: the `seon.db/*conn*` single dynamic
   root, PLUS the `schema/*schemas` registry root, PLUS the ONE shared
   bootstrap compile-state — all swapped or shared per scratch world**
   (`serve.cljs solve-once!` :525-536, `client.cljs boot-seed!` /
   `start-agent!`). Papers over: no fiber-local db scope — even though the
   fiber-local machinery (two AsyncLocalStorage stores) already exists and
   carries agent-id + tx-context — and no per-world story at all for the
   schema registry or the compile-state. Cost already paid: deterministic
   15% hard-fail at /solve concurrency 2 (each burning a full 300s), silent
   `ok? true`-but-nothing-committed data loss on scratch conns,
   restore-order fragility, compiled defs leaking across samples
   ([[multi-db-wire-server-swarms-2026-07-02]] serial-only globals list).
   **FIX-ROOT** (conn + registry) / the shared compile-state is a deeper
   accepted limit — see detail. 
2. **The boot/scratch seed runs INSIDE an agent scope, so the origin-forge
   guard is warn-only** (`db/internal.cljs:1044-1083`,
   `client.cljs:2426`, `serve.cljs:537`). Papers over: the seed path needs a
   provenance privilege the model can't express, so a real security-ish
   invariant (agents cannot forge `:core-seed`) is a `console.warn` that
   fires 27+ times per /solve sample and can never be enforced. **ENFORCE.**
3. **SCI bounding's silent fall-through to the UNBOUNDED compiled path**
   (`render/sci.cljs:185-196, 441-451`). Papers over: the SCI lexical-env
   reconstruction is lossy (it RE-PARSES `:seon.ns/source` for aliases
   instead of reading structured data the analyzer already produced), so any
   reconstruction gap silently removes the pod's only hang protection for
   that tile. Live: `my.plan.internal/plan-block` boots unbounded on every
   fresh cluster. **FIX-ROOT** (fail-loud fix in flight).
4. **The dual compile worlds** — host bundle vs bootstrap compile-state —
   compensated by four stacked mechanisms in `seon.eval`: `guarded-load`'s
   host-bundle fallback, `relink-registry!` after every load,
   `truly-undeclared?`'s globalThis probing, `ensure-analyzer-ns!` priming.
   Each patched a real incident (registry stomp 2026-06-10 severed every
   schema process-wide). Individually defensible; collectively the largest
   accumulation of heuristics in the codebase. **LEGITIMATE short-term /
   FIX-ROOT long-horizon.**
5. **Instrumentation's identity-erasure workarounds** — `async-fn?`
   ctor-name detection, the once-per-process gate (`!instrumented?`), and
   `skip-syms` (`instrument.cljc:43-83, 400-509`). Papers over: wrapping a fn
   erases its asyncness and the CLJS analyzer strips fn metadata, so facts
   about a fn must live in hand-maintained symbol sets and process-wide
   flags. Cost paid: a pod-wedging bug class (double-instrument → every
   `^:async` fn misrouted), and `skip-syms` blocked the `my.plan`
   required-key work until 2026-07-02. **FIX-ROOT.**

Arrived mid-audit and ranking just below the top 5: **the router's CACHED
route projection with no tx-listener to self-heal** (entry 5b below) — a
derive-don't-store violation whose live cost is the acme pod serving ZERO
db-seeded routes (every core page 302-loops) while the readiness probe
passes.

**The single most load-bearing root fix: put the conn into the ONE
request-scoped AsyncLocalStorage context that already exists.**
`db/internal.cljs` already runs fiber-local ALS stores for agent-id and
tx-context (and an open cleanup issue, `als-unify-tx-meta.md`, wants them
merged into one). Adding the conn to that same scope simultaneously: (a)
kills the /solve conn-collision class (NOTE: `POD_MAX_SAMPLES=1` stays
LOCKED per the owner-ratified multi-db design — the registry +
compile-state globals remain; this is a correctness fix, parallelism =
more pods), (b) fixes the silent scratch-conn data loss
(`eval-scratch-conn-no-commit.md` — `*conn*` binding lost across the
`cljs.js` await boundary), (c) lets the seed establish an explicit
core-seed scope instead of borrowing an agent scope, which unblocks
flipping the origin-forge guard from warn to enforcement (#2), and (d) is
the tooling-lane item the calibration run already named. One fix, four
paper-overs retired.

## Inventory table (ranked by blast radius)

| # | Mechanism | Where | Papers over | Verdict |
|---|---|---|---|---|
| 1 | `*conn*` single root + `schema/*schemas` root + shared compile-state (`set!`-swap scratch worlds) | `serve.cljs:495-613` (`:525-526,536,609-613`), `client.cljs:2274,2378` | no fiber-local db scope (ALS exists but conn isn't in it); no per-world registry/compile-state | FIX-ROOT |
| 2 | Seed-inside-agent-scope → origin-forge warn-only | `db/internal.cljs:1044-1083`, `client.cljs:2426`, `serve.cljs:537` | provenance model can't express "privileged seed" | ENFORCE |
| 3 | SCI silent unbounded fallback (`warn-fallback-once!`) | `render/sci.cljs:185-196,441-451` | lossy env reconstruction from re-parsed source | FIX-ROOT |
| 4 | SCI env rebuild re-parses `:seon.ns/source` for aliases | `render/sci.cljs:230-256` | require/alias data not stored structurally | FIX-ROOT |
| 5 | Dual-compile-worlds cluster (4 mechanisms) | `eval.cljs:615-702,782-893` | `:analyze-deps? false` + host bundle vs compile-state split | LEGITIMATE now / FIX-ROOT eventually |
| 5b | Router serves a CACHED route projection; no tx-listener rebuilds it (boot-time `rebuild!` only) | `web/router.cljs` (`!ring-handler`/`rebuild!`/`install!` at :65-342; ONE post-seed `rebuild!` at `serve.cljs:905`) | derive-don't-store violated: routes are datoms but the served router is a stored snapshot with no self-heal | FIX-ROOT |
| 6 | `async-fn?` ctor-name detection + once-per-process instrument gate | `instrument.cljc:400-509` | wrappers erase asyncness; no per-var instrumented record | FIX-ROOT |
| 7 | `skip-syms` hardcoded exemption set | `instrument.cljc:43-83` | analyzer strips fn metadata → symbol set is the only channel | FIX-ROOT (shrunk; residual) |
| 8 | `core-ns-set` replay-skip + `fn-less-compiled-roots #{"my.kb"}` + `prune-core-ghosts!` | `client.cljs:1067-1104,2441-2447` | compiled-vs-authored fact derived from name sets, not provenance | FIX-ROOT |
| 9 | `!solve-deps` / `!create-agent-fn` atom injection; late `lookup-value` resolution | `serve.cljs:110-145`, `render/sci.cljs:493-497` | namespace require cycles | FIX-ROOT (small) |
| 10 | Home-ns aliases don't resolve in agent `my.*` nses (#73) | documented `src/seon/CLAUDE.md` | alias env not established for agent-authored nses; worked around by "fully qualify" docs | FIX-ROOT |
| 11 | tx-feed poll pump + event-loop-alive rpc timer | `store/internal/wire_node.cljs` (fixed) | poll-based feed + single-threaded pod stalls | FIX-ROOT (pub-socket migration queued) |
| 12 | `maybe-await-value` auto-await + pending→`result/<id>` | `eval.cljs:1606-1655` | agents can't write `await` (self-host top-level limit) | LEGITIMATE |
| 13 | `!next-budget-ms` one-shot ambient budget | `eval.cljs:85-104` | no per-form arg channel in the eval protocol | LEGITIMATE (caveat) |
| 14 | Repair layer (parinferish indent-mode auto-balance) | `repair.cljc` | LLM delimiter fallibility | LEGITIMATE |
| 15 | `result/<id>` globalThis stash + reserved `result` ns + graceful miss + cap | `eval.cljs:896-948` | values that can't round-trip the DB | LEGITIMATE |
| 16 | `agent-authored-sym?` ns-prefix regex routing | `render/sci.cljs:94-109` | needs a compiled-core vs agent-editable discriminator | LEGITIMATE (owner-settled) |
| 17 | `SEON_TILE_SCI` / `SEON_INSTRUMENT` kill-switches | `render/sci.cljs:92`, `instrument.cljc:170` | rollback safety for risky wrappers | LEGITIMATE (behavior forks — audit periodically) |
| 18 | `instrument-from-db!` degrade rows (no-var / bad-spec / unresolvable-schema) | `instrument.cljc:425-473` | stale persisted fn rows from prior sessions/renames | LEGITIMATE (warned, non-fatal) |
| 19 | `"root"` agent-id shape exemption | `agent.cljs:77-87` | minted-id shape over-specified for the base case | LEGITIMATE (minor) |
| 20 | Capability gates `SEON_SHELL`/`SEON_WEB`/`SEON_FS_*` (deny-when-unset) | `agent/shell/internal.cljs:83`, `agent/web/internal.cljs:53`, `agent/fs/internal.cljs:78-96` | nothing — genuine capability gates | LEGITIMATE |
| 21 | `race-timeout` Promise.race (can't fire under a blocked loop) | `eval.cljs:167-180` | JS has no preemption; SCI (#3) is its compensation | LEGITIMATE (platform) |
| 22 | Server registry legacy dual-shape snapshot reader | `server/registry.clj:470-478` | old persisted snapshot format never migrated | FIX-ROOT (trivial) |
| 23 | `seon.repl/parse-forms` compat re-export | `repl.cljs:59-66` | callers never updated after the `.cljc` move | FIX-ROOT (trivial delete) |
| 24 | Fail-soft `catch :default` density (9 in sci.cljs, 22 in eval.cljs, 10 in client.cljs, 9 in render.cljs) | render/eval paths | the never-crash doctrine on a single-threaded pod | LEGITIMATE doctrine — but it is the substrate that lets #3-class silent degradation exist |

Counts: **FIX-ROOT 12 · ENFORCE 1 · LEGITIMATE 12** (several legitimate
entries carry caveats noted below).

## Per-mechanism detail

### 1. The /solve ambient-global cluster (`*conn*`, `*schemas`, compile-state) — FIX-ROOT

**What.** `seon.db/*conn*` is one dynamic root. Every "give me a different
world" path mutates it with `set!` and restores in `finally`:

- `solve-once!` (`serve.cljs:525-613`) — saves `*conn*` + the FULL
  `schema/*schemas` registry snapshot (:525-526), `set!`s `*conn*` to a
  fresh `:memory` conn, seeds the core, runs the sample, restores both in
  `finally` (:609-613). Docstring says SERIAL-ONLY. Two more ambient
  globals ride along ([[multi-db-wire-server-swarms-2026-07-02]] "serial-
  only globals" §): the registry swap is a SECOND root with the same
  serial-only property (fiber-local `*conn*` alone doesn't fix it), and
  every sample evals against the ONE `repl/ensure-bootstrap!` compile-state
  (`serve.cljs:536`) — facts are isolated per world, but COMPILED DEFS leak
  across samples, which disqualifies per-pod concurrency for codegen rows
  regardless of conn locality. Also load-bearing from that doc: the scratch
  conn is pod-local `:memory` datahike-cljs (`client.cljs open-agent-conn!`)
  — the wire-server is never involved in a /solve world.
- `boot-seed!` (`client.cljs:2274, 2344`) — same pin/restore pattern.
- `start-agent!` (`client.cljs:2378`) — `set!`s the root to the cluster
  conn permanently (which is exactly why /solve must NOT call it and uses
  `init-agent!` via injected deps instead — a second special case caused by
  the first).

**Deficiency papered over.** CLJS `binding` doesn't survive `await`, so the
code went to a mutable root — but the pod ALREADY solved fiber-locality for
agent-id and tx-context with Node `AsyncLocalStorage`
(`db/internal.cljs:26-58` — "V8 instruments" the async continuations). The
conn was simply never added to that scope. Reads default to `@*conn*`
everywhere (`db.cljs:340` — "bound for you"), so the ambient-read
convenience is the thing being protected.

**Cost already paid.**

- [[calibration-run-2026-07-02]]: at effective concurrency 2, ~15% of
  samples hard-fail — the newer sample's `set!` swaps the world under the
  older one; its entities vanish from the CURRENT conn; cas write-error →
  `halt superseded` → the host poll goes blind and burns the full 300s;
  `turns=0` reported despite 3 turns in the log. Per-pod /solve ceiling
  pinned at 1; parallel scoring requires N disposable pods.
- `docs/seon/issues/eval-scratch-conn-no-commit.md`: a
  transact on a scratch conn via the eval path returns `{:seon.db/ok? true}`
  but never commits — the `*conn*` binding is lost across the `cljs.js`
  await boundary. Silent data loss.
- Restore-order fragility: interleaved `finally` restores CAN leave the pod
  on a scratch conn (calibration doc, "restore-order dependent") — currently
  fenced only by never running concurrent samples.

**Root fix.** Put the conn in the request-scoped ALS (ideally the ONE
unified store `als-unify-tx-meta.md` already wants): `current-conn` reads
ALS-first, root-second; `with-conn` (or an extended `with-agent`) scopes it.
`solve-once!` becomes `(db/with-conn scratch-conn …)` and stops touching
the root at all. The `schema/*schemas` registry needs its own decision:
fiber-local or per-world registry, or explicitly keep /solve at 1-per-pod
and take only the correctness win (the multi-db doc's slice 3 poses exactly
this choice). The shared compile-state has NO cheap per-world fix — the
multi-db doc's honest verdict is that fiber-local conn is "a correctness
fix with a modest concurrency bonus", and N lightweight pods on one
wire-server is the real parallelism architecture; treat compile-state
sharing as an accepted per-pod limit, not something to paper over next.
Effort: moderate — the ALS plumbing exists; the risk is the long tail of
ambient `@*conn*` reads in trigger/listener code that fire outside any
scope (each needs a deliberate "which world?" answer). Already named as
the tooling-lane lever in the calibration doc and
`docs/prds/agent-ctx/CLAUDE.md` (turn-6 recall gap candidate root).

### 2. Seed-inside-agent-scope → warn-only origin-forge guard — ENFORCE

**What.** `warn-on-seed-origin-forge!` (`db/internal.cljs:1070-1083`): when
an agent scope is active AND the tx claims `:seon.db/origin :core-seed`,
log a `console.warn` and count — then commit UNCHANGED. The block comment
(`internal.cljs:1044-1063`) is explicit: the intended enforcement is to
override the origin to `:agent`, but the legitimate boot seed
(`start-agent!` → `(db/with-agent primary …)` wrapping `boot-seed!`,
`client.cljs:2426-2440`) runs inside the booting agent's scope, so
enforcement would re-stamp every boot-seed tx and break the cross-agent
visibility the seed depends on. `/solve` then COPIED the pattern
deliberately: `solve-once!` wraps its scratch seed in
`(db/with-agent seed-primary …)` "so its txs carry the live provenance
shape — identical to the gym" (`serve.cljs:537-540`).

**Deficiency papered over.** The provenance model has no way to say "this
code path is PRIVILEGED to write `:core-seed`" other than "no agent is in
scope" — and the seed wants both an agent-id (for tx attribution) and the
core origin. The guard exists because the inspector's `on-tx` fan-out
TRUSTS `:core-seed` to push to every watching agent's pane — a forging
agent could wake all its peers' renders. So a real invariant is a warning.

**Cost already paid.** 27+ warnings per /solve sample (calibration doc,
anomalies table) — pure wolf-crying that trains everyone to ignore the one
warning that would matter. The enforcement TODO has sat since 2026-06-09.
Downstream, mechanism #8 (replay-skip) can't lean on origin provenance
while origin is forgeable, so it derives the compiled/authored split from
name sets instead. A fix agent is investigating the warn in parallel with
this audit — not touched here.

**Root fix.** Two options, both small once #1 lands: (a) run the seed
OUTSIDE agent scope (the tx-meta agent-id can be passed explicitly — it's
just a tx-meta key, per `merge-tx-context-into-opts` precedence,
`internal.cljs:1018-1041`); or (b) the `*core-seed-allowed*` capability
binding the TODO already sketches, established only by `boot-seed!`. Then
flip the guard to override+warn. Effort: small; the blocker is purely the
boot-path ordering (#23's lane per the TODO).

### 3 + 4. SCI bounding: silent unbounded fallback + source re-parsing — FIX-ROOT

**What.** Agent-authored canvas/render fns run interpreted under SCI so a
wall-clock interrupt can abort a sync loop (the pod's ONLY protection —
`race-timeout` can't fire when the one thread is blocked,
`render/sci.cljs:13-16`). To interpret the fn, the code rebuilds its
lexical environment: re-parse the stored `:seon.ns/source` string for
`:require` aliases (`ns-requires`, `sci.cljs:230-256`), enumerate members
from globalThis + the `:seon.fn` index (`expose-ns`, `sci.cljs:258-324`),
every step fail-soft (`catch :default _ nil`). When ANYTHING in that
reconstruction fails, `invoke-bounded` returns
`{:seon.render.sci/fallthrough true}` and the caller renders the tile on
the UNBOUNDED compiled path, with a once-per-symbol warning
(`warn-fallback-once!`, `sci.cljs:185-196`).

**Deficiency papered over.** Two distinct ones:

- (#4) The require/alias facts are stored only as SOURCE TEXT and
  re-derived by a reader at render time — violating the code-as-data rule
  ("don't re-parse source when the analyzer already produced the structured
  data", CLAUDE.md). A seeded ns whose aliases didn't make it into
  `:seon.ns/source` (the `my.plan.internal` case) is unreconstructable.
- (#3) Because reconstruction is known-lossy, the failure mode was made
  fail-open ("a working fn is never broken by bounding") — trading a
  correctness guarantee for a safety guarantee, silently.

**Cost already paid.**
`docs/seon/issues/archive/sci-bounding-fallback-plan-block.md`: every
fresh boot logs `my.plan.internal/plan-block could not run under SCI
bounding (Unable to resolve symbol: db/*conn*)` and renders it unbounded —
a hang there would freeze the whole pod. Also surfaced in the calibration
run's anomaly table. The residual class is honest in the ns docstring
(`sci.cljs:53-61`): native host loops/regex are unbounded regardless
(Layer 2 killable worker deferred).

**Root fix.** Store the require graph structurally (the analyzer emits it;
`:seon.ns/requires` already exists for topo-sort per memory) and build the
SCI env from datoms, not a reader over a string; make reconstruction
failure fail-loud (a `:seon/error` block instead of an unbounded render —
the owner discussion in the issue note leans this way; a fix agent has it
in flight). The name-prefix routing itself (`agent-authored-sym?`,
`sci.cljs:94-109`) is SETTLED as legitimate: `my.*` = agent-editable
territory, bounded uniformly, no provenance special-casing — because agents
can redefine any `my.*` fn, name IS the right discriminator there.

### 5. The dual compile worlds — LEGITIMATE now, the biggest long-horizon debt

**What.** The pod has two code worlds: the HOST BUNDLE
(`out/client/main.js`, compiled by shadow) and the BOOTSTRAP compile-state
(`cljs.js`, which evals agent forms with `:analyze-deps? false`). The
analyzer literally does not know what the host has loaded, and loading
bundle JS can re-run library top-levels against live state. Four stacked
compensations in `seon.eval`:

- `truly-undeclared?` (`eval.cljs:615-685`): a 3-step heuristic (macro
  short-circuit → analyzer :defs → munged globalThis probes with a
  cljs.core fallback) deciding whether an `:undeclared-var` warning is real.
- `guarded-load` (`eval.cljs:782-853`): host-bundle fallback (answer
  `{:lang :js :source ""}` when the ns is live on globalThis) + the
  DB-layer branch (reconstitute agent nses from datoms) + rethrow.
- `relink-registry!` after EVERY load (`eval.cljs:831`): because loading
  `malli.core$macros.js` once re-ran `set-default-registry!` and severed
  every seon schema process-wide (live incident 2026-06-10, logged in the
  docstring — broke replay, record-eval!, POST /agents/new).
- `ensure-analyzer-ns!` (`eval.cljs:855-893`): pre-prime a real `(ns …)`
  eval before any def into a fresh ns, because `:def-emits-var` +
  a missing `::namespaces` entry throws `Assert failed: (ana/ast? sym)`
  intermittently.

**Deficiency papered over.** One compile-state is not the source of truth
for what code exists. Every mechanism here is a bridge between the two
worlds' views.

**Cost already paid.** The registry stomp (process-wide schema loss); the
`my.kb` require failures cascading into `Cannot set/read properties of
undefined` for every def in the ns (docstring, 2026-06-11); the
intermittent analyzer asserts (root-caused 2026-06-17). Each is now fixed
AT ITS SYMPTOM by one of the four mechanisms.

**Verdict.** Each mechanism is well-documented, incident-grounded, and
individually the right call under self-host constraints — LEGITIMATE. But
name the cluster honestly: four heuristics compensating one architectural
split. Any future "one compile world" move (e.g. the bootstrap
compile-state as the sole authority, host bundle registered into it at
boot) retires all four at once. Effort: large; not a near-term item; worth
a line in `laws.md`/architecture so nobody adds a fifth compensation
without seeing the cluster.

### 5b. The router's cached route projection with no self-heal — FIX-ROOT

**What.** Routes are datoms (`:seon.route/*`, reconciled by the boot seed),
but the SERVED router is a cached reitit ring-handler in an atom
(`web/router.cljs:65-82` — `!ring-handler` + `!router-config`). It is
rebuilt at exactly two moments: load-time `install!` (supplement-only —
`*conn*` is nil, so ZERO db routes project) and ONE post-seed
`router/rebuild!` in `serve/start!` (`serve.cljs:905`). The route
tx-listener that would make the cache track the datoms does not exist —
the code says so itself, twice: "when Core wires a route tx-listener, on
every route tx" (`router.cljs:13, 321`).

**Deficiency papered over.** Derive-don't-store, violated at the exact
seam the concept doc warns about: a stored snapshot of a derivable value
with a manual refresh instead of a reactive derivation. The one-shot
`rebuild!` is a boot-ORDER bet — it assumes the seeded route tx is visible
in the pod's local replica by the time `start!` runs.

**Cost already paid.** LIVE regression
(`docs/seon/issues/acme-harness-agents-route-drift.md`
Part 2, observed 2026-07-02 ~18:45Z): the acme pod serves ONLY the static
supplement — `GET /` is an INFINITE 302 loop, `/agent/root` 302s, every
db-seeded core route falls to `not-found` — while the route rows verifiably
exist in the store (wire REPL pull) and `POST /solve` keeps serving. Worse,
it is UNDETECTABLE by the supervisor: `ready_check`'s `curl -f /` passes on
the 302 the broken router emits. The issue's (unverified) hypothesis is
replica lag at `start!` time — precisely the race a tx-listener-derived
router cannot have.

**Root fix.** Wire the route tx-listener the docstrings already reserve
space for (`db/listen!` on route-attr datoms → `rebuild!`), making the
cached handler a memoized derivation that self-heals on any route tx —
boot ordering stops mattering. Optionally also a readiness probe that
distinguishes "core routes serving" from "not-found 302" (issue AC).
Effort: small (rebuild! already takes no args by design for exactly this
caller — `router.cljs:81`). Note: `serve.cljs` had uncommitted edits by
another agent during this audit — coordinate.

### 6 + 7. Instrumentation: `async-fn?`, the once-gate, `skip-syms` — FIX-ROOT

**What.**

- `async-fn?` (`instrument.cljc:400-407`): asyncness detected by
  `constructor.name == "AsyncFunction"` on the live var.
- The once-per-process gate (`!instrumented?`, `instrument.cljc:482-509`):
  a second `instrument-from-db!` pass would read the FIRST pass's wrapper
  (a plain `Function` that returns a Promise), mis-detect every `^:async`
  fn as sync, route Promises through the sync output validator, and wedge
  the pod (`:malli.core/invalid-output` from ticker + wake loop). So the
  full pass runs exactly once per process.
- `skip-syms` (`instrument.cljc:43-83`): a hardcoded FQ-symbol set of fns
  exempted from instrumentation because they are the errors-are-values
  envelope surface. Currently 3 whole nses (`seon.agent.search`,
  `seon.agent.fs`, `seon.agent.message`) + `[seon.db transact!]`. The
  `my.plan` verbs were REMOVED 2026-07-02 (they now ride the injecting
  wrapper — the `ce903dbf`-era fix confirmed in source comment
  `instrument.cljc:72-79` and `docs/prds/agent-ctx/CLAUDE.md`).

**Deficiency papered over.** Identity/metadata erasure: (a) wrapping a fn
erases the fact it was async, and nothing records "this var is already
wrapped", so the system infers both from ctor names and process flags; (b)
the CLJS analyzer strips `:malli/schema` metadata markers, so "this fn owns
its own validation" can't be a schema property or fn metadata — only a
hand-maintained symbol list survives compilation
(`instrument.cljc:62-67`).

**Cost already paid.** The double-instrument wedge class (documented at
`instrument.cljc:483-492`; also CLAUDE.md's standing gotcha). `skip-syms`
blocked the `my.plan` required-key resolution work until its 2026-07-02
shrink. The once-gate means a core fn whose persisted spec changes
mid-process isn't re-instrumented until restart (accepted, but implicit).

**Root fix.** Stamp the wrapper: set a property on the wrapper fn object
(e.g. `.-seon$async` / `.-seon$wrapped`) at wrap time and have `async-fn?` /
the gate read it — per-var truth instead of a process flag, making
re-instrumentation idempotent by construction. For `skip-syms`: the
envelope-surface fact belongs on the `:seon.fn` DB row (the program graph
survives compilation even though metadata doesn't) — `instrument-from-db!`
already reads rows, so it could read an `:seon.fn/envelope-verb?` flag teed
at registration instead of a symbol set. Effort: small-moderate. Residual
risk today is LOW (the set is 4 entries and shrinking), so this ranks below
the top 3.

### 8. `core-ns-set` replay-skip + the hand exception set — FIX-ROOT

**What.** Resume replays agent-authored nses from the DB;
`agent-ns-set` = all `:seon.ns/name` rows MINUS `(core-ns-set)`
(`client.cljs:696-704`). `core-ns-set` is derived from live var-meta of the
boot roster (good — build-derived, not hand-typed) PLUS
`fn-less-compiled-roots #{"my.kb"}` — a hardcoded exception for a compiled
ns that owns no indexed var (`client.cljs:1067-1073`) — plus the
SEON_EXTRA_SRC downstream nses. The reserved-prefix rule (`seon.*`/`my.*`
refused for extra-src, `client.cljs:1029-1057`) and `prune-core-ghosts!`
(boot-index GC, `client.cljs:2441-2447`) are companion mechanisms.

**Deficiency papered over.** The compiled-vs-agent-authored fact is not a
property of the ns ROW — it's re-derived every boot from name membership.
The DB has an origin provenance model that SHOULD answer this
(`:core-seed` rows = boot-indexed, `:agent` rows = authored), but replay
can't trust it: partly because origin is forgeable (#2), partly because the
row's first-assertion origin isn't currently consulted.

**Cost already paid.** The `my.kb.instruction` dead-teachings incident
(comment at `client.cljs:2441-2445`): a DELETED core ns fell out of
`core-ns-set`, its ghost rows were misclassified as agent corpus and
replayed back into the live compile-state — which is why
`prune-core-ghosts!` now exists (a mechanism to compensate a mechanism).
`fn-less-compiled-roots` is the uniformity canary: the derivation misses a
whole class (fn-less nses) and the miss is patched by name.

**Root fix.** Scope replay by tx provenance (first-assertion origin
`:core-seed`/`:config` = never replay; `:agent`/`:replay` = replay) — the
exact discrimination `seon.state/reconcile!` already uses for its managed
scope (`state.cljs:8-16`). Requires #2 first (origin must be
enforce-trustworthy). Then `fn-less-compiled-roots` and most of
`prune-core-ghosts!` dissolve. Effort: moderate, sequenced after #2.

### 9. Require-cycle injection atoms — FIX-ROOT (small)

**What.** `serve.cljs` can't require `seon.client` (cycle), so the /solve
deps arrive via `(reset! !solve-deps …)` from `seon.client` at load time
(`serve.cljs:110-145`, ditto `!create-agent-fn`); until then /solve answers
503. `render/sci.cljs:493-497` late-resolves
`seon.agent.message/message!` via `seval/lookup-value` "to avoid a require
cycle (render → render.sci → message → ctx → render)".

**Deficiency papered over.** Real require cycles in the ns graph. The
CLAUDE.md "don't be a dumbass" list names this exact trap ("I'll put it in
a fresh ns to avoid the require cycle → wrong; fix the cycle").

**Cost.** A boot window where /solve 503s (harmless so far); invisible
coupling — the deps wiring is only discoverable by reading both files;
`lookup-value` resolution silently no-ops if the target ever renames
(`when-let` at `sci.cljs:497`).

**Root fix.** Break the cycles: the message-send seam and the
mint-agent/boot-seed seam are small protocol surfaces that belong in a leaf
ns both sides require. Effort: small per cycle.

### 10. Home-ns aliases don't resolve in `my.*` nses (#73) — FIX-ROOT

Documented weakness (`src/seon/CLAUDE.md`): the `db/`, `plan/` aliases the
context teaches don't resolve inside agent-authored `my.*` nses; agents
must fully qualify there. The workaround is DOCUMENTATION — the prompt
teaches around the defect. Cost: a standing class of agent eval failures
and prompt caveats; also the same alias-establishment gap that starves the
SCI env rebuild (#4) — one root (alias data not established/stored for
agent nses), two symptoms. Fix is on the tooling-lane list (#73).

### 11. tx-feed poll pump + event-loop-alive rpc timer — FIX-ROOT (queued)

`tx-feed-pump-timeouts.md` (status: completed for the timer fix): wall-clock
rpc timeouts fired spuriously because the single-threaded pod's multi-second
sync windows (seed eval, instrumenting ~550 fns) expire timers before the
buffered reply can be read. The fix measures timeout in event-loop-ALIVE
time (a coalescing 250ms interval) — clever, correct, and itself a
compensation for the real shape: the feed is POLL-based (~18 UDS
connections/sec, a JVM thread per connection). The named root fix — migrate
to the pub socket push channel that `seon.server.broadcast` already
maintains — is a queued post-merge unit. The alive-timer stays legitimate
even after (any rpc under a stalling pod needs it), but the pump's
per-50ms reconnect load disappears.

### 12-15. The eval-surface ergonomics — LEGITIMATE

- **`maybe-await-value`** (`eval.cljs:1606-1655`): auto-awaits returned
  Promises so agents get data, never Promise objects. Papers over a hard
  platform truth (self-host: top-level `await` throws; agents can't be
  taught Promise plumbing). Timeout → `:pending` → the live handle stashed
  at `result/<id>`, resolved on re-reference; `(defer …)` opts out. This is
  a designed contract, transparent, and documented everywhere the agent
  looks. Keep.
- **`!next-budget-ms`** (`eval.cljs:85-104`): `(seon.eval/budget ms)` sets
  a one-shot ambient override consumed by exactly the next form's
  auto-await, carefully reset on every path so it can't leak. Ambient state
  by design because the agent's protocol unit is "a form", which has no arg
  channel. Legitimate, but it is the kind of side-channel that multiplies —
  hold the line at one.
- **The repair layer** (`repair.cljc`): parinferish indent-mode, accepted
  ONLY when the output changed AND re-reads, with the full delimiter diff
  surfaced as a `↻` breadcrumb the agent sees (`repair-note`). The honest
  scope section names what it CANNOT fix. This is the model
  auto-heal: conservative gate + total transparency. The residual risk
  (wrong-but-valid structure from misleading indentation) is disclosed to
  the agent in-band. Keep.
- **`result/<id>` stash tier** (`eval.cljs:896-948`): the three-tier
  storage rule's volatile tier. Reserved-ns-by-convention (`result` —
  live-checked absent at boot), capped (`SEON_EVAL_RESULT_VARS_CAP`,
  default 200), pruned ids give a graceful miss instead of a raw
  undeclared error. Papers over "live values can't all round-trip the DB" —
  which is a fact, not a fixable deficiency. Keep.

### 16-21. Gates, switches, exemptions — LEGITIMATE with caveats

- **`agent-authored-sym?` prefix routing** — settled (see #3/#4 detail).
- **Kill-switches**: `SEON_TILE_SCI=0` (unbounds ALL tiles) and
  `SEON_INSTRUMENT` (disables all runtime validation) are rollback levers
  for the two riskiest wrappers. Legitimate as shipped-with-a-new-wrapper
  insurance; the caveat is they never expire — once the wrapper has months
  of soak, a fork nobody exercises is untested-path risk. Worth a periodic
  "can this switch retire?" pass. The capability gates
  (`SEON_SHELL`/`SEON_WEB`/`SEON_FS_ROOT`/`READ_ONLY`/`LOCK`) are a
  different class: deny-when-unset capability grants, uniform, tabled in
  `docs/seon/components/capability-gates.md`. Genuinely legitimate.
- **Env breadth generally**: ~70 `SEON_*` names in `src/`. Nearly all reads
  are mediated through `seon.config` accessors with manifest-first
  precedence (the `#env`-in-`config/system.edn` pattern, `config.cljs:98,
  443-446`), which honors the ONE-config rule. The outliers that read
  `platform/env-val` directly (`SEON_REQ_SOCK`, `SEON_CLUSTER_DIR`,
  `SEON_WEB*` internals) are launch-wiring, acknowledged as such at
  `config.cljs:353`.
- **`instrument-from-db!` degrade rows**: stale persisted fn rows (prior
  session, renamed schema) are left uninstrumented with a warn rather than
  aborting boot (`instrument.cljc:425-473`). Boot-resilience done right:
  loud, counted in the stats map, never fatal.
- **`"root"` id exemption** (`agent.cljs:77-87`): one literal id outside
  the 14-char minted shape, expressed IN the schema
  (`[:or [:= "root"] :seon.db/id]`) rather than as a code branch — the
  least-magic way to have a base case. Fine.
- **`race-timeout`**: honestly documented as unable to fire under a blocked
  loop; SCI bounding is its compensation for the sync-hang class. Platform
  limit, not a paper-over.

### 22-23. Trivia — FIX-ROOT (delete-class)

- `server/registry.clj:470-478`: reads BOTH the current
  `{:registry … :agents …}` snapshot shape and the legacy bare-map shape.
  One-time migration + delete the branch.
- `repl.cljs:59-66`: `parse-forms` re-export "for callers that still
  reference" the old name. Update the callers, delete the alias.

### 24. The fail-soft substrate — a doctrine-level observation

`catch :default` counts: 22 in `eval.cljs`, 10 in `client.cljs`, 9 each in
`render.cljs` and `render/sci.cljs`. The never-crash doctrine is CORRECT
for a single-threaded pod (one uncaught throw blanks every agent + the UI),
and most catches convert to `:seon/error` values or logged warns. But it is
the substrate that makes #3-class silent degradation POSSIBLE: when every
layer degrades gracefully, a defect's only trace is a warn line in a log
nobody tails. The pattern to watch for in review: `catch :default _ nil`
(swallow-to-nil) vs catch-to-error-value — the former appears mostly in the
SCI env-reconstruction helpers, which is exactly where the live silent
failure lives. Not a mechanism to remove; a lens for reviewing new code.

## Uncertainties (honest gaps)

- **Whether the /solve seed's `with-agent seed-primary` wrap is
  load-bearing beyond mirroring the gym** — the comment says "so its txs
  carry the live provenance shape"; I did not trace which downstream reader
  actually requires the agent-id on seed txs (candidates: the inspector
  fan-out, derived context sections). If nothing requires it, option (a)
  in #2 is even cheaper.
- **The full ambient-`@*conn*` read inventory** — I did not enumerate every
  trigger/listener that reads the root outside a scope; the #1 fix's true
  cost is that tail, not the ALS plumbing.
- **`seon.warn` / gym / worker_eval** were skimmed, not deep-read;
  `worker_eval.cljs` has its own `SEON_BOOTSTRAP` path and warning-handler
  `set!` that mirror the main eval's — I did not audit whether they've
  drifted from the main path (a potential parallel-mechanism smell worth a
  focused look if the worker lane becomes active).
- **The router regression's root** (5b) — the replica-lag-at-`start!`
  hypothesis is the issue note's, UNVERIFIED; the audit finding (stored
  projection, no tx-listener, one-shot rebuild) stands regardless of which
  race instance bit acme, but the root-cause confirmation is the fix
  agent's job.
- **JVM-track `.clj` files** were excluded per scope (paused track) except
  `server/*.clj`; `embed.clj`/`indexing.clj` env reads were noted but their
  internals not audited.

## Recommended fix order

1. **Conn into the ALS scope** (#1) — retires the /solve collision class,
   the scratch-conn silent data loss, the schema-registry swap dance, and
   the per-pod scoring ceiling in one move; the ALS already exists and an
   open issue already wants the stores unified.
2. **Seed outside agent scope + flip origin-forge to enforcement** (#2) —
   ends the 27-warns-per-sample wolf-crying and makes provenance
   trustworthy, which is the precondition for #8's replay-by-provenance.
3. **Structured requires + fail-loud SCI fallback** (#3/#4, coordinated
   with the in-flight fix agent) — closes the pod's one silent
   hang-protection hole and fixes the same alias root as #73.
4. **Route tx-listener** (#5b) — smallest of the four (the no-arg
   `rebuild!` hook is already carved out), fixes a LIVE regression the
   readiness probe can't see, and converts the last big stored-projection
   surface to derive-don't-store. Coordinate on `serve.cljs` (uncommitted
   edits by another agent at audit time).
