---
type: prd
status: draft
tags: [prd, schema]
---

# Schema + runtime unification — PRD draft (v2, post-review-of-existing-spec)

**Status:** Draft. Replaces v1 draft after re-reading `agent-runtime/v1.md` §7 and `resume-findings-2026-05-23.md`. Earlier draft mistakenly reinvented bootstrap.edn + the boot sequence — both already specified.

## 0. Drafting honesty

Read this session: my two prior research files (the authoritative content), `malli.instrument.{clj,cljs}`, `malli.registry.cljc`, `cljs.analyzer.api.cljc`, `schema.cljc` head, `agent.cljs` lines 100-240, `code.cljc`, `eval.cljs` lines 1-400, `repl.cljs`, `v1.md` §7 (boot/bootstrap/resume), `STATUS.md` head, `resume-findings-2026-05-23.md` Q1-Q8 + impl sketch.

NOT re-read: `v2.md`, `v3.md`, `platform.md`, `client.cljs` full, `db.cljs`, `eval-batch-fragility-2026-05-23.md`, `derive-not-store-2026-05-23.md` full body, `schema-state-architecture-audit-2026-05-23.md` body, prior research notes from earlier sessions.

The review-pass below names what THIS PRD adds on top of v1.md and resume-findings — and explicitly defers to those documents where they already speak.

## 1. Relationship to existing spec

**v1.md §7.3 already specifies `resources/seon/bootstrap.edn` + `bin/emit-bootstrap` script.** This PRD does NOT reinvent it. References that section by name.

**v1.md §7.4 + `resume-findings-2026-05-23.md` already specify `replay-program-graph!` in tx-id order against `@conn` (not `d/history`), bypassing `eval-batch!`, with failures landing as `:seon.log :warn`.** This PRD does NOT reinvent that. Adopts it.

**What this PRD adds** (the genuinely new pieces, not in v1.md or any prior research):

1. **CLJS instrumentation.** Wire `malli.instrument` into the pod. Substrate-side via build-time `mi/collect!`; agent-side via runtime `m/-register-function-schema!` from inside `eval-batch!`. Reporter routes failures into the existing `:seon.eval/error` envelope.
2. **The `*schemas` atom becomes a derived cache.** A tx-listener on `:seon.schema/source` writes mirrors into the atom. Substrate's load-time `register!` calls are deleted; bootstrap.edn becomes the only writer of `:seon.schema` entities. One source of truth.
3. **`:seon.transient` declaration + render section.** New entity for "this is process-state, not persisted." Substrate list curated from the 11-defonce audit; agent-side auto-tagged when an eval's new var is an `IDeref`.
4. **Schema/fn version-drift detection on resume.** Project `:seon.fn/arglists` / `:seon.fn/doc` / `:seon.fn/malli-schema` at tee time. On resume, if re-derived projection differs from persisted, log + flag.

That's it. Four concrete additions. Everything else routes through existing spec.

## 2. Problem (restated against v1.md baseline)

v1.md §7 lays out boot/bootstrap/resume cleanly, but three gaps remain:

1. **`:malli/schema` metadata on ~30 pod fns is currently inert.** No instrumentation runs in CLJS. (REPL-confirmed: `(m/function-schemas :cljs)` → `{}`.) v1.md doesn't address this.
2. **The in-process malli registry is rebuilt from load-time `register!` calls, not from the DB.** v1.md §7.3 transacts substrate entities to the DB but the registry is still populated by the side effect of namespace loading. Two sources of truth that happen to agree on first boot.
3. **No way to declare "this state vanishes on restart."** Agents that bind atoms inside an eval have no visible signal that the binding is transient.

## 3. Goals (revised, smaller)

1. Every `:malli/schema`-bearing fn is validated at call time. Failures land in `:seon.eval/error` automatically.
2. `*schemas` atom is populated by transacting `:seon.schema` entities, not by load-time `register!` side effects. Substrate and agent paths use the same mechanism.
3. Agent's render shows transient bindings (substrate + agent) so the persistence boundary is visible.
4. Resume detects substrate-vs-persisted version drift on `:seon.fn` and surfaces it.

## 4. Architecture (the small picture, sitting on v1.md §7)

```
                  ┌──────────────────────────────┐
                  │  resources/seon/bootstrap.edn │   (v1.md §7.3 — already spec'd)
                  └──────────────┬───────────────┘
                                 │ first-boot transact
                                 ▼
                  ┌──────────────────────────────┐
                  │     datahike :seon (LMDB)    │
                  │   :seon.ns / :seon.schema /  │
                  │   :seon.fn / :seon.transient │
                  │   + agent log                │
                  └──────────────┬───────────────┘
                                 │ tx-listener on :seon.schema/source  ← THIS PRD
                                 ▼
                  ┌──────────────────────────────┐
                  │   *schemas atom + -function-schemas* atom    │
                  │   (derived caches; never written directly)   │
                  └──────────────┬───────────────────────────────┘
                                 │ (mi/instrument!)  ← THIS PRD
                                 ▼
                  ┌──────────────────────────────┐
                  │   globalThis var wraps        │
                  │   (every fn with :malli/schema)
                  └──────────────────────────────┘
                                 │
                                 ▼
                  ┌──────────────────────────────┐
                  │   replay-program-graph!       │  (resume-findings spec'd)
                  │   tx-id order, bypasses       │
                  │   eval-batch, fails to        │
                  │   :seon.log                   │
                  └──────────────────────────────┘

```

Three invariants:

- **DB is the source of truth for typed surface.** `*schemas` and `-function-schemas*` derived from `:seon.schema` and `:seon.fn` entities. Wipe an atom; replay from DB; byte-identical.
- **Identity attrs upsert; bootstrap is idempotent.** `:seon.schema/key`, `:seon.fn/sym`, `:seon.ns/name`.
- **Substrate and agent share the surface.** Distinguished only by `:seon.{ns,fn,schema}/substrate? true`.

## 5. The `schema/register!` body — the change

Per the schema-registry research §6 rule 3. Repeated here for clarity:

```clojure
(defn register!
  "Register schema for key k.

   Body is context-aware:
   - Bootstrap path (called from bootstrap.edn loader OR from a legacy
     ns-load-time register! site during migration): direct atom write,
     fast, no DB roundtrip.
   - Agent-eval path (called from inside cljs.js eval; *tx-context* ALS
     carries {:agent-id ... :turn-id ... :eval-id ...}): write a
     :seon.schema entity. The boot-installed tx-listener mirrors that
     write into *schemas. Same end state, plus persistence."
  {:malli/schema [:=> [:cat :qualified-keyword :any] :any]}
  [k schema]
  (let [ctx tx/*tx-context*]
    (if (or (nil? ctx) (:seon.db/bootstrap? ctx))
      (swap! *schemas assoc k schema)
      (db/transact! (:seon.db/conn ctx)
                    [{:seon.schema/key    k
                      :seon.schema/source (pr-str schema)
                      :seon.schema/ns     [:seon.ns/name (keyword (namespace k))]}]))))

```

What we are NOT doing:

- NOT writing our own malli. The `mr/composite-registry` + `mr/mutable-registry` + `m/-register-function-schema!` + `mi/-replace-fn` API is stable and fits us exactly. Sean asked "do we read malli's source and write our own?" — **no**. The "different registries" worry was about malli's composite LAYERING, not parallel competing globals.
- NOT replacing `mi/collect!`. For substrate, it runs CLJ-side at build time and emits into bootstrap.edn (§6.5). For agent eval, we call `m/-register-function-schema!` DIRECTLY because we already have the analyzer state.
- NOT inventing a parallel registry. Malli's composite gets one mutable layer; function-schemas atom is malli's own.

## 6. Files affected

### 6.1 `seon.schema` (`src/seon/schema.cljc`)

- Add `current-keys` (3-line read accessor; needed by detect-and-tee atom-diff per the analyzer research).
- Rewrite `register!` body per §5.
- Add `install-registry-sync!` — tx-listener installer that mirrors `:seon.schema/source` writes into `*schemas`.
- Add `seed-from-db!` — one-pass seed of `*schemas` from existing `:seon.schema` entities, used at boot before the listener is installed (so we don't miss the initial bootstrap-load entities).

Keep: every existing setup of `:inst`, `:seon.flow/dynamic`, `:seon.db/ref`, etc. These are simple-schema types, not data schemas — they stay as load-time defonces.

### 6.2 `seon.dev.instrument` (new, `src/seon/dev/instrument.cljs`)

`(:require [malli.instrument :as mi])`. Bundle adds ~150KB (malli.instrument + malli.generator + test.check transitively). Sean confirmed: bundle additions are free.

Exposes:

- `(start! {:report report-fn})` — calls `mi/instrument!`. Idempotent. Called from `seon.client/-main` after bootstrap-load + replay.
- `(instrument-fn! ns sym)` — single-fn variant for agent-eval'd defns. Filters by var.
- `(report-fn type data)` — the reporter. Throws ex-info with structured ex-data so `seon.eval/eval`'s catch site packages it as `:seon.error/kind :malli.instrument` plus `:malli.instrument/fn-name`, `:malli.instrument/arg-index`, `:malli.instrument/expected-schema`, `:malli.instrument/actual-value`.
- `(refresh!)` — re-instrument all (called when `seon.eval`'s `init-version` rotates, mirroring the compile-state hot-reload pattern).

### 6.3 `seon.eval` (`src/seon/eval.cljs`) — detect-and-tee adds instrumentation hook

Per the analyzer research Q9(a) detect-and-tee sketch, with one addition:

```clojure
;; After (raw-result :ok true) and the analyzer-diff detects newly-added vars:
(doseq [{:keys [ns sym var-map]} added-vars
        :let [schema (-> var-map :meta :malli/schema)]
        :when schema]
  ;; Register the function-schema so mi/instrument! can find it.
  (m/-register-function-schema! ns sym schema {:metadata-schema? true} :cljs identity)
  ;; Instrument JUST this fn (don't re-instrument the world).
  (instrument/instrument-fn! ns sym))

;; And: detect transient (IDeref values for new defs)
(doseq [{:keys [ns sym var-map]} added-vars
        :let [v (when var-map (eval/lookup-value (symbol (str ns) (str sym))))]
        :when (and v (satisfies? IDeref v))]
  (db/transact! (:conn ctx)
                [{:seon.transient/sym         (str ns "/" sym)
                  :seon.transient/ns          [:seon.ns/name (keyword (str ns))]
                  :seon.transient/kind        :other
                  :seon.transient/declared-by :agent}]))

```

No changes to the eval-failure path: instrumentation throws → `eval`'s catch → `seon.error/->map` → `:ok false :error <map>`. The reporter's ex-data lands in `:seon.error/data` automatically.

### 6.4 `seon.client` (`src/seon/client.cljs`) — boot sequence

Adopts the resume-findings Q4 recommended sequence + adds instrumentation start. Reference v1.md §7 for slots 1-3 and 7.5 (start-session).

```
1. ensure-bootstrap!                      ; v1.md §7.2 — compile-state ready
2. open-agent-conn!                       ; v1.md §7 — datahike conn
3. assert-preconditions!                  ; v1.md §7.1
4. if database-empty?                     ; v1.md §7.3
     bootstrap-phase!                     ;   transact resources/seon/bootstrap.edn
5. (schema/seed-from-db! conn)            ; THIS PRD — one-pass *schemas seed
6. (schema/install-registry-sync! conn)   ; THIS PRD — listener for future writes
7. (fn-schema/seed-from-db! conn)         ; THIS PRD — one-pass -function-schemas* seed
8. (instrument/start! {:report report-fn}) ; THIS PRD — one mi/instrument! pass
9. (replay-program-graph! conn cs)        ; resume-findings — re-eval :source in tx order
10. (resume-policy/decide! conn)          ; THIS PRD — mark interrupted turn :error
11. setup-agent-ns!                       ; v1.md §7 — substrate atoms after replay (defensive)
12. start-session!                        ; v1.md §7.5
13. start-server!                         ; HTTP + SSE
14. start-agent-loop!                     ; ready

```

The instrument-before-replay ordering matters: when replay re-evals a `:seon.fn/source` containing `:malli/schema` metadata, the per-form analyzer-diff (we should run detect-and-tee on replay too, IF the source carries new schemas not yet in the registry — but per resume-findings §Q5 replay bypasses eval-batch and thus bypasses detect-and-tee). So **on resume, detect-and-tee does NOT fire**; instead, the function-schemas seed in step 7 + instrument in step 8 covers all persisted fns; step 9 just rebinds globalThis. Open question (§9) for whether per-replay-fn instrumentation needs explicit re-application.

### 6.5 `seon.bootstrap` — defer to v1.md §7.3

v1.md §7.3 already specifies:

- `resources/seon/bootstrap.edn` — checked in, ordered entity vector.
- `bin/emit-bootstrap` — build-time emitter (~80 LOC).
- Single transact with `:tx-meta {:seon.db/origin :system}`.
- Intra-tx lookup-ref resolution handles ordering.

This PRD changes one thing: the emitter must also extract `:malli/schema` metadata from substrate defns into a `:seon.fn/malli-schema` attr on each `:seon.fn` entity (so the seed-from-db! at boot step 7 can register function-schemas). v1.md doesn't say this explicitly; small extension.

### 6.6 `seon.transient` (new, `src/seon/transient.cljc`)

Per schema-registry research Q5. Schemas + curated `resources/seon/transient.edn` for substrate; agent-side auto-tag in eval-batch.

### 6.7 New entities added to `seon.agent.cljs`

```clojure
;; Already exists: :seon.ns/name, :seon.ns/source, :seon.fn/sym,
;; :seon.fn/ns, :seon.fn/source, :seon.schema/key, :seon.schema/ns,
;; :seon.schema/source

;; New (this PRD):
(schema/register! :seon.ns/requires      [:vector :keyword])
(schema/register! :seon.ns/substrate?    :boolean)
(schema/register! :seon.ns/at            :inst)

(schema/register! :seon.fn/arglists      :string)
(schema/register! :seon.fn/doc           :string)
(schema/register! :seon.fn/private?      :boolean)
(schema/register! :seon.fn/specced?      :boolean)
(schema/register! :seon.fn/malli-schema  :string)
(schema/register! :seon.fn/substrate?    :boolean)
(schema/register! :seon.fn/at            :inst)

(schema/register! :seon.schema/substrate? :boolean)
(schema/register! :seon.schema/at         :inst)

```

After the migration in §7 step 5 lands, these are ALL persisted by detect-and-tee; v1.md §7.3 emitter populates substrate entries with `:substrate? true`.

## 7. Migration plan

Compressed to 7 steps from the 11 in the research note:

| # | Step | Files | Reversible? |
|---|------|-------|-------------|
| 1 | `seon.schema/current-keys` (3 LOC). | `schema.cljc` | yes |
| 2 | Bundle `malli.instrument` + add `seon.dev.instrument.cljs`. Build-time `mi/collect!` over substrate nses populates `-function-schemas*` at boot. Wire `instrument/start!` in `client/-main`. | new file, `client.cljs`, build hook | yes |
| 3 | Detect-and-tee in `eval-batch!` per-form loop (analyzer-diff + atom-diff + per-fn `instrument-fn!`). | `eval.cljs` | yes |
| 4 | Extend v1.md's emitter to write `:seon.fn/malli-schema` and the projection attrs. Add the new schemas to `seon.agent.cljs`. | `bin/emit-bootstrap` + `agent.cljs` | yes |
| 5 | Rewrite `schema/register!` per §5. Install tx-listener. Run BOTH old (load-time register!) and new (DB-via-listener) paths in parallel. Smoke-test the registry diff is empty. | `schema.cljc`, `client.cljs` | yes |
| 6 | Delete load-time `register!` calls from substrate `seon.*` files. Bootstrap.edn is sole authority. | every file with substrate `register!` | NO — breaking commit; the only one |
| 7 | `:seon.transient` schema + curated EDN + render section. Pause/resume smoke test (`bin/seon stop pod && bin/seon start pod`, assert running runtime matches pre-pause). | new files + `agent.cljs` | yes |

Step 6 is the only irreversible commit. Steps 1-3 deliver instrumentation; 4-6 deliver unified bootstrap; 7 delivers full pause/resume + transient surface.

## 8. Risks

- **Step 2 (substrate instrument) reveals real schema-vs-impl bugs.** Expected and desirable. Don't ship to main until green.
- **Tx-listener loop on substrate-load.** Listener fires on `:seon.schema/source` writes during bootstrap; if it tries to transact BACK, infinite loop. Mitigation: `*tx-context* :seon.db/bootstrap? true` short-circuits the listener's DB-write path; only updates the atom.
- **Resume against substrate drift.** Agent's persisted `:seon.fn/source` references a schema/fn the substrate retired. Per v1.md §7.4 it lands as `:seon.log :warn`; agent regenerates on next turn. This PRD's `:seon.fn/malli-schema` projection enables a sharper render: "the persisted schema for `alice/foo` is `[:=> ...]`; substrate has retired `:bob/x` referenced inside it."
- **Build-time `mi/collect!` reads analyzer state for ~20 substrate nses.** Requires the build to import every seon.* ns so the analyzer sees them. If a substrate ns is reachable only via dynamic require, its `:malli/schema` annotations won't get collected. Audit at step 2.
- **Datahike-cljs `d/listen!` semantics.** Assumed to match JVM datahike (sync callback after tx commit). Confirm by REPL probe before step 5.

## 9. Open questions

1. **Re-instrument on hot-reload?** When `seon.eval`'s `init-version` rotates, does `mi/instrument!` need to be re-applied? Probably — globalThis vars stay wrapped, but the wrapper closes over the prior compile-state. Need a probe to confirm whether wrappers survive hot-reload of `malli.instrument` itself.
2. **bootstrap.edn regen trigger.** Re-emit on every `clj -M:cljs compile client`? CI gate fails on diff? Recommend both: regen unconditionally on compile, CI fails on uncommitted diff.
3. **Agent schema deletion verb.** `(seon.schema/retract! ::foo)` that retracts the `:seon.schema` entity? Or rely on upsert with a new shape? Defer; not in v1 scope.
4. **Instrument scope policy.** `#{:input :output}` is the malli default. Cheap path: `#{:input}` for agent-defined fns (catches caller mistakes, halves cost). Lean: keep `:input :output` for both, optimize later if hot path.
5. **Reporter ↔ error-envelope contract.** Lock the namespaced-key shape in step 2's PR. Schemas: `:malli.instrument/fn-name :symbol`, `:malli.instrument/arg-index :int`, `:malli.instrument/expected-schema :any`, `:malli.instrument/actual-value :any`, `:malli.instrument/scope [:enum :input :output]`. Register them in `seon.dev.instrument.cljs`.
6. **Replay-phase instrumentation.** When `replay-program-graph!` re-evals a `:seon.fn/source` with `:malli/schema` metadata, the analyzer captures it but the function-schemas atom + instrumentation patching are done in step 7+8 of §6.4's boot order. Steps 7-8 happen BEFORE replay (step 9) — so when replay rebinds the var on globalThis, the instrumented wrapper from step 8 is overwritten by the fresh JS emission. **Fix: after replay finishes, run a `instrument/refresh!` to re-patch.** Confirm via probe.
7. **`:seon.transient` for substrate connections vs computed caches.** The 11-defonce audit lumps "datahike conn" with "memoization cache". Different `:seon.transient/kind` values? Lean: yes, two values (`:connection` vs `:cache`), agent sees them grouped in the render.

## 10. References

- `agent-runtime/v1.md` §7 — boot/bootstrap/resume spec. Owns sections 7.1-7.5.
- `agent-runtime/research/analyzer-driven-extraction-and-resume-2026-05-24.md` — detect-and-tee Q9(a) sketch + `cljs.analyzer.api` toolkit Q2. The :requires-source for `:seon.ns/requires`.
- `agent-runtime/research/schema-registry-unification-and-resume-2026-05-24.md` — registry layering Q1, instrumentation flow Q2, bootstrap-from-DB Q3, transient Q5, full design Q6.
- `agent-runtime/research/resume-findings-2026-05-23.md` — `replay-program-graph!` impl spec; bypass-eval-batch decision (Q5); failure-to-log (Q6); boot sequence (Q4).
- `agent-runtime/research/schema-state-architecture-audit-2026-05-23.md` — the 11-defonce inventory feeding the curated `transient.edn`.
- `agent-runtime/research/eval-batch-fragility-2026-05-23.md` (cited in STATUS.md) — ALS-routed warning handlers; `!current-ns` elimination. Cross-cutting concern; should land BEFORE step 3 of this PRD.
- `reference-code/malli/src/malli/instrument.cljs` (159 LOC) — the wrapping impl we're adopting.
- `reference-code/malli/src/malli/instrument.clj` lines 60-170 — `mi/collect!` macro for build-time substrate scan.
- `reference-code/malli/src/malli/registry.cljc` — `composite-registry` / `mutable-registry` / `set-default-registry!`.

## 11. Review against existing spec — what changed from v1 draft

The previous draft of this PRD got four things wrong against v1.md and resume-findings; this draft corrects them:

1. **Re-spec'd bootstrap.edn.** v1.md §7.3 owns this. Stripped my section; pointer only.
2. **Re-spec'd replay (using a topo-sort over `:seon.ns/requires`).** v1.md §7.4 + resume-findings spec tx-id order against `@conn`. Tx-id is provably topological per resume-findings Q3 because lookup-refs enforce ns-before-fn at write time. Adopted theirs.
3. **Routed replay through `eval-batch!`.** resume-findings §Q5 explicitly recommends bypassing eval-batch so detect-and-tee doesn't re-fire as no-op upserts. Adopted theirs.
4. **Independent boot sequence.** resume-findings Q4 has the v1-aligned sequence with one defensive ordering tweak. Adopted theirs + inserted instrumentation slots.

The genuinely new content remaining: **instrumentation + tx-listener + `:seon.transient` + projection-attrs**. Everything else is already shipped or scheduled in v1.md.
