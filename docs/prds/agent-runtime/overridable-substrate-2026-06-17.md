---
type: prd
status: draft
tags: [prd, agent, cljs]
---

# Overridable substrate — per-function override, all the way down

## TL;DR

Seon becomes "everything overridable": the runtime IS the database, so a
consumer (or an agent) can replace a single substrate function — e.g. just
`seon.agent.message/reply!` — without owning, shipping, or recompiling the whole
`seon.agent.message` namespace. Per-FUNCTION granularity is the requirement, not
per-ns (Sean: "I don't want to overwrite a full namespace if they just want to
replace a single function").

This works because the `:client` build is a dev `:node-script` (`:none`)
bundle: cross-namespace calls emit as fresh reads of the munged `goog.global`
path at call time, so redefining one var is picked up by existing compiled
callers — PROVEN live (PROOF 1/2 below). The mechanism reuses the path replay
already walks: `seon.eval/eval` into the target ns with `{:ns 'seon.agent.message}`
(`eval.cljs:551`, the exact call `replay-one!` makes at `client.cljs:767`).

Three things change vs today:

1. **Replay-skip is lifted for override rows specifically.** Substrate rows are
   never replayed today (`query-program-graph-entries`, `client.cljs:665`) so a
   stored `(defn ^:async transact! …)` can't shadow the compiled fn. Overrides
   WANT exactly that shadow — but only for rows a consumer explicitly marked as
   overrides, replayed AFTER `index-substrate!` so the override wins.
2. **Fallback-to-compiled net.** A broken override (throws on eval, or fails its
   gate) must fall back to the compiled fn, never brick the boot. The compiled
   var is always restored by `index-substrate!` (`client.cljs:1284`) first; a
   failed override replay is logged and skipped, leaving the compiled var live.
   Retract = drop the override row; next boot has nothing to replay.
3. **A noop SEAM + hook registry for augmentation.** Raw override is
   last-write-wins (good for true replacement). For "react to every reply"
   (#27), ship default-noop seams (`fire-on-reply-hooks!`) that the compiled
   core calls — augmentation registers a hook, never monkeypatches `reply!`.

This PRD ABSORBS #27 (the on-reply hook is the canonical seam) and #28 (the
seed dir is the delivery vehicle, extended with an override-targeted sibling).

## Live-proof results (isolated sandbox)

All proofs ran `seon.eval/eval` against `@seon.repl/!compile-state` into scratch
`sandbox.*` nses, in the live pod's `default` REPL session. That session has NO
bound conn (`@seon.repl/!conn` is nil), so no `db/transact!` could reach the
Aria store, and bare `eval` (not `eval-batch!`) fires no detect-and-tee — zero
store writes, the live deployment untouched. Full record:
`tmp/vendor-test/README.md`.

- **PROOF 1 — cross-ns override is late-bound.** `sandbox.caller/call-f` (a
  compiled-style caller in another ns) returned `:v1`, then `:v2-overridden`
  after redefining ONLY `sandbox.target/f` — no recompile of the caller.
  Confirmed in the compiled bundle: `seon.db.transact_BANG_.cljs$core$IFn$…`
  and `seon.agent.run_agentic_loop_BANG_.call(null …)` read the global fresh at
  call time (`out/client/cljs-runtime/seon.agent.js`).
- **PROOF 2 — single-fn granularity.** After overriding only `f`,
  `[(f) (g)] = [:f-v3 :g-original]`. Sibling `g` kept its value. Each `defn`
  overwrites exactly one var slot.
- **PROOF 3 — re-export ALIAS hazard (load-bearing caveat).**
  `(def face-fn impl/impl-fn)` captures the VALUE at def time; overriding
  `impl/impl-fn` left `face-fn` on the old value
  (`(impl-fn)=:impl-v2 (face-fn)=:impl-v1`). This is exactly
  `seon.agent.reply_BANG_ = seon.agent.message.reply_BANG_;`
  (`seon.agent.js:391`). Override the DEFINING var; re-point known aliases.
- **PROOF 4 — call-through.** Capturing the prior fn in an atom before
  redefining yielded `"wrapped[base:42]"` — the override delegated to the
  original. This is the composition primitive.

## The override mechanism (per-fn)

### Data shapes (Malli, fully namespaced; no `:any`, no `[:maybe]`)

An override is just a `:seon.fn` row with extra provenance marking it as a
substrate override. Reuse the existing `:seon.fn/sym` / `:seon.fn/source` /
`:seon.fn/ns` shape (`client.cljs` `var->fn-row`); add:

```clojure
;; in seon.fn's schema home
(schema/register! :seon.fn/override-target
  ;; the canonical defining symbol this row overrides, e.g.
  ;; seon.agent.message/reply!  — present ⇒ this row is a substrate override
  :symbol)
(schema/register! :seon.fn/override-origin
  ;; provenance: where the override came from (audit + gate policy)
  [:enum :override-dir :agent :repl])
```

A row carrying `:seon.fn/override-target` is an override; its absence = ordinary
agent corpus (today's behavior, byte-identical). No new entity kind — overrides
are program-graph rows viewed through one more attribute (code-as-data:
[[docs/seon/concepts/code-as-data-runtime]]).

The install request, for the verb a consumer/agent calls:

```clojure
(schema/register! :seon.agent.reply/text :string)   ; (shared with #27)

(schema/register! :seon.override/install-request
  [:map
   [:seon.fn/sym             :symbol]   ; the var being (re)defined
   [:seon.fn/ns              :keyword]  ; its DEFINING ns (not an alias face)
   [:seon.fn/source          :string]   ; the (defn …) text
   [:seon.fn/override-target :symbol]
   [:seon.fn/override-origin {:optional true} [:enum :override-dir :agent :repl]]])

(schema/register! :seon.override/install-response
  [:map
   [:seon.override/installed? :boolean]
   [:seon.override/gated?     {:optional true} :boolean]  ; deferred by gate
   [:seon.override/error      {:optional true} :string]]) ; eval/validate failure
```

### Install: eval into the defining ns, then record

`install-override!` (new, in a small `seon.override` ns) is the verb. It:

1. Evals `:seon.fn/source` into `:seon.fn/ns` via `seval/eval` with
   `{:ns <defining-ns> :analyze-deps? false}` — the same call replay makes
   (`client.cljs:767`). On eval failure it returns
   `{:seon.override/installed? false :seon.override/error …}` and the compiled
   var stays live (PROOF: a failed `eval` never touches the var slot).
2. Re-points known re-export aliases (PROOF 3). The set of aliases is derived,
   not hand-typed: scan `substrate-vars` meta for `:seon.fn/alias-of`
   (a one-line addition to the `(def reply! message/reply!)` re-exports — tag
   them), and re-eval each alias `def` after the override lands.
3. Transacts the `:seon.fn` row with `:seon.fn/override-target` set, so it
   persists and replays on the next boot.

Per-fn granularity falls out: step 1 writes exactly one var slot (PROOF 2).

## How replay-skip changes

Today (`query-program-graph-entries`, `client.cljs:662-694`):

```clojure
(remove #(contains? (substrate-ns-set) (entry-ns-kw %)))   ; L665 — drops ALL substrate rows
```

Change: an override row (`:seon.fn/override-target` present) is NOT dropped even
though its ns is in `(substrate-ns-set)`. Concretely, the `remove` predicate
becomes "in substrate-ns-set AND NOT an override row". Plus a new ordering tier:

- Boot order is already: `boot-seed-store!` → `substrate-index-tx`
  (`client.cljs:1777`, restores every compiled `:seon.fn` row + the live vars
  are present from module load) → `prune-substrate-ghosts!` →
  `replay-program-graph!` (`client.cljs:1994`).
- Override rows must replay AFTER `index-substrate!` so they win, and that is
  already true: `replay-program-graph!` runs after the substrate index tx.
- WITHIN replay, override rows sort LAST (after `:ns` rows and ordinary agent
  defs), so an override that calls through to other agent corpus sees it
  defined. Extend the existing `sort-by (juxt …)` (`client.cljs:693`) with an
  override tier (`:ns`=0, agent-def=1, override=2).

The `#29` guard (`seval/effectful-bare-def?`, `eval.cljs:930`,
`client.cljs:680`) still applies to override rows — an override is a `defn`
(safe), but the loader rejects an effectful bare `def` override the same way.

## Fallback-to-compiled + retract

The irreducible safety property: a broken override can NEVER permanently brick
the pod, because the compiled var is restored every boot BEFORE any override
replays.

1. **Compiled var always live first.** Module load defines every compiled
   `seon.*` var; `index-substrate!` re-indexes their rows. The override only
   shadows the var by replaying AFTER that. If the override replay throws,
   `replay-one!` (`client.cljs:754`) catches it, `log-replay-failure!`
   (`client.cljs:780`) logs it, and the compiled var stays live. No brick.
2. **Publish gate for overrides.** Reuse the existing gate
   (`:seon.fn/specced?` + `:seon.test/last-passed-at > last-failed-at`,
   [[docs/seon/concepts/code-as-data-runtime]] "publish gate"). For a substrate
   override the gate is STRICTER, because a bad override blasts every agent: an
   override row replays only when (a) it specced+tested-green, OR (b) its
   origin is `:repl` (interactive, single-session, opt-in). `:override-dir`
   and `:agent` origins MUST pass the gate. A gated-but-failing override is
   skipped (the compiled fn serves), and surfaces via a reactive-context
   section ("overrides pending gate") — self-healing, no stored flag
   ([[docs/seon/concepts/reactive-context]]).
3. **Retract.** Retract the override row (`[:db/retract <e> :seon.fn/source]`
   or retract `:seon.fn/override-target` to demote it to ordinary corpus).
   Next boot, `query-program-graph-entries` reads the CURRENT db
   (`client.cljs:632`), so a retracted source is not in the replay set; the
   compiled var wins with nothing to shadow it. A consumer-facing
   `retract-override!` verb wraps this; an agent does it with one transact.
4. **Live revert.** To revert WITHOUT a reboot, re-eval the compiled source
   (stored as the substrate `:seon.fn` row's `:seon.fn/source`) into the
   defining ns — restoring the compiled body in the live compile-state.

## Composition / call-through + the seam decision

PROOF 4 shows raw call-through works (capture prior fn, delegate). But raw
prior-capture is last-write-wins and does NOT compose across N independent
packages cleanly (each captures whatever was live when IT loaded; load order
decides the chain, and a retract of a middle layer orphans the captures).

**Decision: ship default-noop SEAMs at interesting points; reserve raw override
for true replacement.**

- **Augmentation → seam.** For "fire on every reply" (#27), the compiled core
  calls `fire-on-reply-hooks!` at `ask-and-eval!`'s success branch
  (`agent.cljs:1066`, per [[docs/prds/agent-runtime/research/27-28-architecture-2026-06-16]]).
  The seam is a default-noop the compiled core ALWAYS calls; consumers
  `register-on-reply!` a symbol (resolved late via `seval/lookup-value`,
  `eval.cljs:286`). N packages each register independently — clean composition,
  retract = `unregister-on-reply!`, no monkeypatch of `reply!`. This is the #27
  mechanism, now framed as the general augmentation seam.
- **Replacement → override.** When a consumer wants DIFFERENT behavior (not
  additive) — e.g. a custom `transact!` that routes elsewhere — they override
  the fn. Call-through (capture prior in a `defonce` atom, PROOF 4) is the
  documented escape hatch for "mostly the same, one tweak", but two replacement
  packages targeting the SAME fn is a conflict the install verb must refuse
  (last-write-wins is a bug here): `install-override!` rejects a second
  override of an already-overridden target unless it declares
  `:seon.fn/override-target` chaining intent. Flag, don't silently stack.

Recommend seams at: `on-reply` (#27, build now), and — as future seams when a
real consumer asks — `on-turn-close`, `on-transact`, `on-agent-mint`. Each is a
noop the compiled core calls; adding one is a section-function-style addition,
not new machinery.

## Distribution: how a consumer delivers overrides

Per the real distribution model (verified against `bin/seon`,
`src/seon/platform.cljs`; the consumer does NOT have seon's `src/`):

| Mechanism | What it delivers | Path | Replay |
| --- | --- | --- | --- |
| Compiled bundle | the substrate kernel | `out/client/*` | n/a (module load) |
| `SEON_RUNTIME_ROOT` | artifact root | `platform.cljs:73` | n/a |
| `SEON_CLUSTER_DIR` | consumer's store | `bin/seon:59` | n/a |
| `SEON_EXTRA_SRC`+`_PRELOAD` | compiled `acme.*` exemplar code | `bin/seon:102-109` | replay-SKIP |
| `SEON_SEED_DIR` (#28) | recorded `my.*` product code | proposed | replayed corpus |
| **`SEON_OVERRIDE_DIR` (new)** | **substrate-targeted overrides** | **proposed** | **replayed, gated, wins** |

`SEON_OVERRIDE_DIR` is `SEON_SEED_DIR`'s sibling: a dir of `*.cljs` files whose
ns is a SUBSTRATE ns. The boot step (new, in `start-agent!` alongside the #28
seed step, after `replay-program-graph!`, `client.cljs:1994`):

1. `env-val "SEON_OVERRIDE_DIR"` (the `brand.cljs:88` pattern); nil/blank =
   no-op, byte-identical default.
2. List+read `*.cljs` (sorted, Node `fs`).
3. For each file, eval through the recording path AS an override: tag the
   resulting `:seon.fn` rows with `:seon.fn/override-target` (derived from the
   ns + each `defn` name) and `:seon.fn/override-origin :override-dir`, inside a
   `{:seon.db/origin :override-dir}` tx-context.
4. Idempotency (same as #28): on later boots the override rows already exist
   and REPLAY reconstitutes them; skip re-evaling files whose override rows are
   present (conn-dedup, `substrate-index-tx` shape, `client.cljs:1408`).
5. Gate: `:override-dir` origin overrides must pass the publish gate before
   they shadow the compiled var.

The seed dir (#28) is the home for `my.virtue` (recorded product code +
`register-on-reply!` wiring); the override dir is the home for substrate
replacements. A consumer with NO build uses both; a consumer with a build uses
`SEON_EXTRA_SRC` for compiled exemplar code. See `tmp/vendor-test/` for a worked
example (`consumer-seed/my_virtue.cljs`, `consumer-seed/override_reply.cljs`,
`extra-src/`).

## Blast radius / kernel reality (honest)

Sean chose "everything overridable", but there is an irreducible chicken-and-egg:
**the eval/replay/DB loop must be compiled and running to install (and later
replay) the override that overrides it.** You cannot override
`seval/eval`/`replay-program-graph!`/`db/transact!` purely-from-the-store,
because the store is read THROUGH those fns. The fallback-to-compiled net is
what makes attempting it survivable:

- The compiled kernel is ALWAYS present at module load and re-indexed by
  `index-substrate!` before any override replays. So even a broken override of
  `db/transact!` boots: the compiled `transact!` runs `index-substrate!` and the
  override replay (which then shadows it for subsequent calls). If the override
  is broken, replay logs+skips and the compiled `transact!` stays live.
- **Truly-unsafe targets (the kernel) — overridable but flagged.** Overriding
  any of these can make the NEXT boot fail to reach the override-replay step,
  so the override silently never takes (the pod boots on compiled code).
  `install-override!` warns LOUDLY for these and requires `:repl` origin
  (interactive only) + a passing gate:
  - `seon.eval/eval`, `eval-batch!`, `raw-eval` — the eval loop itself.
  - `seon.client/replay-program-graph!`, `query-program-graph-entries`,
    `index-substrate!`, `start-agent!` — the boot/replay loop.
  - `seon.db/transact!`, `query`, the conn manager — the persistence loop.
  - `seon.schema/register!` — the validation loop.
  A kernel override that breaks boot is recovered by clearing
  `SEON_OVERRIDE_DIR` (or retracting the row via the wire-server REPL) and
  rebooting on compiled code — documented in the recovery runbook.
- Everything ABOVE the kernel — `seon.agent.message/reply!`, `seon.agent/*`
  turn logic, render fns, context assembly — is freely overridable with the
  full fallback net and no special warning. This is the 95% case the ask is
  really about.

## Phased implementation checklist (small units)

1. **Schemas + alias tagging.** Register `:seon.fn/override-target`,
   `:seon.fn/override-origin`; tag the `seon.agent` re-export aliases with
   `:seon.fn/alias-of` (one-line metadata on the `(def reply! …)` forms).
2. **`seon.override` ns + `install-override!` / `retract-override!`** (map-in /
   map-out, the request/response schemas above). Eval into defining ns,
   re-point tagged aliases, transact the override row. Unit tests:
   install→call-through→retract→compiled restored.
3. **Replay-skip lift.** Extend `query-program-graph-entries`'s `remove`
   (`client.cljs:665`) to keep override rows; add the override sort tier
   (`client.cljs:693`). Test: an override row survives the substrate filter and
   sorts last.
4. **Publish gate for overrides.** Wire the gate so non-`:repl` override rows
   replay only when specced+tested-green; add the "overrides pending gate"
   reactive section.
5. **`fire-on-reply-hooks!` seam (absorbs #27).** Per the existing #27 design
   ([[docs/prds/agent-runtime/research/27-28-architecture-2026-06-16]]):
   `!on-reply-hooks` atom, `register-on-reply!`/`unregister-on-reply!`, fire at
   `agent.cljs:1066`, fail-soft, late-resolved. Frame it as the canonical
   augmentation seam.
6. **`SEON_OVERRIDE_DIR` boot step.** Mirror the #28 `SEON_SEED_DIR` step;
   tag rows as overrides; idempotency guard; gate. Depends on #29 landing.
7. **Kernel-target warnings + recovery runbook.** `install-override!` refuses
   kernel targets outside `:repl`; document `SEON_OVERRIDE_DIR`-clear recovery.

## Risks / open questions

- **`:static-fns` / inlining.** Proven late-bound under the dev `:none`
  `:client` build (PROOF 1; compiled call sites read the global fresh). A
  `:release` `:advanced` build would inline/flatten and BREAK override — the
  pod must stay dev-compiled (it already is, `shadow-cljs.edn:58-77`; no
  `:static-fns`, no `:optimizations`). Flag: document "overridable substrate
  requires the dev build" as an invariant; if a release build is ever wanted,
  override dies and this whole feature is off.
- **Re-export aliases (PROOF 3).** The `:seon.fn/alias-of` tagging must be
  complete or an override of a defining fn silently misses callers that go
  through an untagged alias. Audit every `(def x other-ns/x)` in `seon.*` at
  step 1; a checker (uniformity canary) that flags untagged re-exports would
  keep it honest.
- **Kernel targets.** Overriding the eval/replay/DB/schema loop is permitted
  but can no-op the override on the next boot. The fallback net prevents a
  brick but not a confusing "my override didn't take". The `:repl`-only +
  loud-warning posture is the mitigation; revisit if a consumer has a real
  kernel-override use case.
- **Multi-package ordering.** Two packages overriding the SAME target is a
  conflict (`install-override!` refuses silent stacking). Two packages
  AUGMENTING via `on-reply` compose cleanly (independent registrations). Push
  consumers toward seams for additive behavior; reserve override for
  replacement. Open: a declared chaining protocol if real demand appears.
- **Gate for `:repl` overrides.** `:repl`-origin overrides skip the gate (fast
  iteration) but then do NOT persist as gated rows by default — decide whether
  a `:repl` override that the dev wants to KEEP gets promoted to `:override-dir`
  via an explicit "publish" verb (recommended) vs auto-persists.

## Cross-references

- `tmp/vendor-test/` — the worked consumer example + full proof record.
- [[docs/seon/concepts/code-as-data-runtime]] — overrides are program-graph
  rows; the source→analyzer→DB→source circle.
- [[docs/seon/concepts/reactive-context]] — "pending gate" + "active overrides"
  surfaces are section fns, self-healing.
- [[docs/prds/agent-runtime/research/27-28-architecture-2026-06-16]] — the
  on-reply seam (#27) + seed-dir (#28) this absorbs.
- [[docs/prds/agent-runtime/substrate-asks-batch-2026-06-16]] — #27/#28/#29.
- `src/seon/eval.cljs` — `eval` (`:551`), `lookup-value` (`:286`),
  `effectful-bare-def?` (`:930`).
- `src/seon/client.cljs` — `query-program-graph-entries` (`:628`),
  `replay-program-graph!` (`:789`), `substrate-ns-set` (`:1039`),
  `index-substrate!` (`:1284`), `start-agent!` replay call (`:1994`).
- `src/seon/agent.cljs` — `ask-and-eval!` success branch (`:1066`, the #27
  fire-site).
- `out/client/cljs-runtime/seon.agent.js:391` — the re-export alias hazard
  (`seon.agent.reply_BANG_ = seon.agent.message.reply_BANG_;`).
