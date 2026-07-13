---
type: reference
status: active
tags: [reference, agent, database, schema, flow]
---

# Library grounding — read the real source before you build

> **For every build-agent on a Core-lane phase:** before you touch code, READ
> the `reference-code/…:LINE` ranges this doc lists for your phase. They do two
> things at once — (1) **ground the design claim** in the actual library so you
> never guess semantics (guessing produces confident, wrong Clojure), and (2)
> **demonstrate idiomatic Clojure** so you write in the grain of these libs
> instead of JS/Java-shaped code. This is mandatory, not optional. The companion
> [[datahike-primer]] is the narrative "work in datahike's grain" mindset; this
> doc is the concrete file:line map + the validation findings.

Every claim below was read firsthand against the vendored source and marked
✓ confirmed / ⚠ build-note-or-doc-refinement. Sibling docs: [[architecture]]
(the map), [[data-model]] (schema), [[agent-runtime]] (loop), [[roadmap]] (the
checklist).

## Per-phase read-first map

Read these BEFORE writing the phase. Full notes per library are below.

| Phase | Read FIRST (`reference-code/` + our bridge) | Why it grounds the phase |
|---|---|---|
| **1** rename | `src/seon/db/internal.cljs:147-360` (the malli→datahike bridge) | know what every renamed attr bridges to (identity / component / EDN) |
| **2** keystone (seed-copy, render engine) | `src/seon/render/sci.cljs:335-430` (`invoke-bounded`); datahike `db/transaction.cljc:730` (`retract-components`) | renders run SCI-bounded + never-throw; blocks cascade-retract as components |
| **3** purpose seed | `src/seon/schema.cljc:193` (`register!`); `src/seon/db/internal.cljs:1211,1359,1370` (`ensure-datahike-attrs!`) | the seed registers a schema — register! ≠ bridge |
| **4** bootstrap-forms | `src/seon/eval.cljs` (eval/record path); datahike `db/transaction.cljc:873` (`compare-and-swap`) | `:core`-quiet evals; the fence |
| **5** root + route schema | datahike `db/transaction.cljc:873` (`compare-and-swap`) + `:1138-1140` (in-tx db); `db/utils.cljc:109-148` (`entid`); reitit `trie.cljc:60`, `reitit-ring/.../ring.cljc:14-16` | `start!` open-race fence + ordering; route schema → reitit |
| **6** `my.*` schemas | `data-model` §2 + `src/seon/db/internal.cljs:286-360`; `db/transaction.cljc:730` | the three ref kinds (todo-parent is a PLAIN ref, NOT a component) |
| **7** `:seon/error` consolidation | malli `core.cljc:2643` (`explain`), `error.cljc:374` (`humanize`), `impl/util.cljc:19` (`-error`); malli `core.cljc:164` (`Tag`) + `:1044` (`-orn-schema`) | the error VALUE model + identify-kind-by-`:orn` |

## datahike — the data + concurrency model

Read: `reference-code/datahike/src/datahike/db/transaction.cljc`,
`…/db/utils.cljc`, and our bridge `src/seon/db/internal.cljs`.

### CAS-as-fence — `compare-and-swap` (transaction.cljc:873) ✓

```clojure
(defn compare-and-swap [db report op-vec]
  (let [[_ e a ov nv] op-vec
        e (dbu/entid-strict db e)               ; lookup-ref RESOLVES → eid
        nv (if (dbu/ref? db a) (dbu/entid-strict db nv) nv)
        datoms (dbi/search db [e a])]
    (if (nil? ov)                               ; OPEN-race arm
      (if (empty? datoms) [add] (raise "expected nil"))
      (let [ov (if (dbu/ref? db a) (dbu/entid-strict db ov) ov)]
        ... (if (= v ov) [add] (raise "expected " ov))))))  ; WORK-fence arm
```

- **Work-fence** `[:db.fn/cas [:seon.agent/id id] :seon.agent/run OV NV]` with
  `OV == NV == the run`: current==run re-asserts (idempotent `:db/add`);
  current≠run RAISES → the WHOLE tx aborts (eval batch + result write die
  together). ✓
- **Open-race** `… :seon.agent/run nil NV`: absent→add, present→raise. ✓
- **Lookup-ref entity** resolves via `entid-strict` (utils.cljc:141) — but
  `entid` (utils.cljc:109) REQUIRES the lookup attr be `:db/unique` (line 122).
  `:seon.agent/id` is identity→unique ✓.
- **Atomic abort**: `log/raise` throws ex-info `{:error :transact/cas}`; it
  propagates out of `transact-tx-data`. `transact!*` (our bridge,
  internal.cljs:1333) converts it to a `{::ok? false …}` envelope — never crashes
  the pod.

⚠ **Doc-refinement (agent-runtime.md:115).** The fence is written
`… :seon.agent/run [run R] [run R]`. `:seon.agent/run` is a **ref**, so OV/NV
must be an eid or a lookup-ref `[:seon.agent.run/id R]`, never `[run R]`.

⚠ **Build-note (Phase 5 open-race ordering).** In `transact-tx-data` the op
dispatch binds `db (:db-after report)` — the RUNNING db (transaction.cljc:1138-1140),
so a CAS sees entities created earlier in the SAME tx. Order the open tx
`[{run-create-map} [:db.fn/cas … :seon.agent/run nil [:seon.agent.run/id R]]]`
(create first) — `entid-strict` RAISES on a missing run, so wrong order fails
the open.

### Component cascade-retract — `retract-components` (transaction.cljc:730) ✓

```clojure
(into #{} (comp (filter #(dbu/component? db (.-a %)))
                (map #(vector :db.fn/retractEntity (.-v %)))) datoms)
```

Per component datom emits `[:db.fn/retractEntity child]`, recursively. So
`[:db.fn/retractEntity agent]` cascades every `:seon.agent/ctx` block +
`:seon.agent/schedules`; a turn cascades its evals. ✓ data-model §2.1. NB:
`:my.plan/parent` is a PLAIN ref (not component) — the tree is NOT owned, so
retracting a parent does NOT delete children.

### The malli→datahike bridge — `src/seon/db/internal.cljs:147-360` ✓

Handles every shape Phases 5-7 add: `:seon.db/ref`→`:db.type/ref` (special-cased,
169/219); `{:seon.db/identity true}`→`:db.unique/identity` (349);
`{:seon.db/component true}`→`:db/isComponent` (350); `:symbol`→`:db.type/symbol`
(193, so `:seon.route/handler`/`:seon.agent.schedule/fn` store native);
mixed-`:or`→`:db.type/string` EDN (249); `[:and {props} base]` bridges on base +
lifts props (236/344); `[:vector :keyword]`→cardinality-many.

⚠ **KEY rule — `schema/register!` ≠ bridge-to-datahike.** `register!`
(`schema.cljc:193`) is in-memory (malli) only. The datahike bridge runs lazily at
transact time on `(extract-tx-attrs tx-data)` via `ensure-datahike-attrs!`
(internal.cljs:1211/1359/1370). So registering an IN-MEMORY-ONLY value shape —
`:seon.error/data :map`, `:seon.warn/check-response`, `:seon.derive/status` (all
`:map`, which the bridge cannot store) — is fine: they're never transacted as
entity attrs, so they never hit the bridge. **Only attrs you `transact!` must be
bridge-storable.** This is why the error value shapes (data-model §6) are correct
despite `:map` — they're in-memory values (§6.1), never entities.

### transact never throws — `transact!*` (internal.cljs:1333) ✓

Catches every commit-path throw (conn resolution, the validation gate,
`ensure-datahike-attrs!`, the datahike commit) → `{::ok? false …}` failure
envelope. Grounds data-model §6.1/§7 (transact failure → `:seon.db/error` under
`::error`, surfaced as a VALUE).

## malli — the error model + kind identification

Read: `reference-code/malli/src/malli/core.cljc`, `…/error.cljc`,
`…/impl/util.cljc`.

### The error model (data-model §6) ✓

- `explain` (core.cljc:2660) → `{:schema :value :errors}`, and **`nil` on a valid
  value** (the `when-let` at 2655) — not an empty-errors map. Build-note: test
  the nil case when constructing `:seon/error`.
- each error (`-error`, impl/util.cljc:19) = `{:path :in :schema :value :type}`
  (`:type` optional) — verbatim the doc's claim.
- `humanize` (error.cljc:374) is a **pure reduce over the explanation's `errors`**
  into a fresh structure, nil on valid. A pure VIEW; the explain map is the
  source. So `:seon.error/data` keeps the explain map and `:seon.error/message`
  is the humanize headline — both derive from the one explanation.

### Kind identification by `:orn` (data-model §3) ✓

- `Tag` (core.cljc:164) = `(defrecord Tag [key value])`; `tag`'s docstring: "used
  eg. for results of `parse` for `:orn` schemas." So `m/parse` of an `:orn` →
  `Tag{:key matched-branch :value …}` — read `(:key tag)`.
- `-orn-schema` parser (core.cljc:1073-1078): builds a parser per branch that
  returns `(reduced (tag k %))` on first success; the outer `reduce` tries
  branches **in written order, first match wins**. ✓
- ⚠ **Build-constraint:** malli returns the FIRST matching branch, not the best —
  so order `:orn` branches **most-specific-first** (most required attrs first), or
  a loose branch matches prematurely.

## SCI — capability surface + bounded eval (the never-crash render engine)

Read: `src/seon/render/sci.cljs:335-430` (`invoke-bounded`), `:92`
(`agent-authored-sym?`); vendored `reference-code/sci/src/sci/core.cljc:236-289`
(`eval-string`/`init`/`fork`, the `:namespaces`/`:deny`/`:classes` opts).

### `invoke-bounded` (render/sci.cljs:335) ✓

- `sci/init {:namespaces nsmap :classes base-classes :interrupt-fn …}` (413-416):
  agent code sees ONLY the curated `nsmap` rebuilt from the DB index (389-412);
  `fs`/`require`/`net` aren't in scope, so the symbol doesn't resolve →
  **capability-by-grant** (architecture / agent-runtime isolation §). ✓
- `:interrupt-fn deadline-interrupt-fn` + the `!deadline` volatile (413-421): the
  wall-clock bounded eval IS SCI's interrupt. ✓
- The OUTER `try`: *"invoke-bounded must NEVER throw"* → any failure becomes the
  `{…/error <:seon.db/error>}` envelope. ✓ never-crash for the render engine.
  Bounding is FAIL-LOUD: when SCI can't run a `my.*` render fn (missing source,
  an alias the stored `:seon.ns/source` doesn't carry, a runtime throw), the
  caller renders a `:seon/error` block IN PLACE — there is NO fallback to the
  unbounded compiled path (a hang there would wedge the single-threaded pod;
  the never-wedge property holds unconditionally). `{…/interrupt true}` still
  signals the deadline trip (caller recovers the tile).
- The agent ns env is reconstituted from `:seon.fn/source`/`:seon.ns/source` rows
  each call — **code-as-data: the runtime IS the database**.

## The wire boundary — the CAS fence executes AT the writer ✓ (critical)

Read: `src/seon/db.cljs:399` (`cas-assert`) + `:422` (`transact!`),
`src/seon/store/wire.cljs:12-21` (the forwarder), `src/seon/server/wire.clj`
(the JVM writer), `src/seon/db/internal.cljs:1294-1311` (the wire report).

- The pod has datahike-cljs but its WRITER is a `:seon-wire` PWriter: `d/transact!`
  forwards the **raw tx-data** (the `:db.fn/cas` op included — pure data) over the
  UDS to `seon.server.wire`, which runs `d/transact` against the **authoritative**
  JVM conn (client.cljs:517 "the SOLE writer"). So **`compare-and-swap` runs at the
  single writer against total-ordered state, NOT the pod's replica** — the fence is
  sound across the wire. A CAS failure returns as a `{::db/ok? false …}` value.
- **Use `db/cas-assert`, do NOT hand-write the CAS vector.** It builds the
  no-op fence as data: `(db/cas-assert [:seon.agent/id id] :seon.agent/run
  [:seon.agent.run/id run-id])` → `[:db.fn/cas … V V]`, leading the work-tx
  (db.cljs:399-420). This is the canonical fence; the docs' `[run R]` was shorthand.
- The wake `listen!` is PROVEN by current operation: after a wire commit the pod
  re-derefs and fires native `d/listen` listeners with a synthesized tx-report
  (store.wire.cljs:20-21); `install-wake-trigger!` already runs in the live pod.

✓ **The open race is SOLVED + live-proven — read it, keep it.** `open-run!`
(run.cljs:215-274) already opens a run in ONE atomic tx and the inline comment
(262-265) pins the exact mechanism: the run-row (`:db/id "run"` + identity
`:seon.agent.run/id`) is placed **FIRST**, then `[:db.fn/cas [:seon.agent/id id]
:seon.agent/run nil [:seon.agent.run/id run-id]]` uses the **lookup-ref** as NV (a
tempid is NOT resolvable in a CAS NV slot). The run-row is processed first → the
lookup-ref resolves against the just-added run → the CAS sets the pointer; a racing
second open sees the pointer set and its whole tx fails. This runs through the wire
to the JVM writer and is live-proven (the night-build). The db.cljs:488-490 warning
is about entity-map ref SLOTS, not an explicit CAS op, so there is no real tension.
**Phases 4-5 KEEP `open-run!`/`close-run!`/`run-fence` unchanged** — do NOT rebuild
the run lifecycle; build the bootstrap/seed/`start!` layer AROUND it. `close-tx-data`
(run.cljs:281) is the matching work-fence-on-close pattern (pure, unit-testable).

## Instrumentation — write `:malli/schema` normally; it WORKS ✓

Read: `src/seon/instrument.cljc:109-161` (`collect-registrations` + `collect!`).

- `collect!` is a compile-time MACRO: it walks every first-party CLJS ns via the
  analyzer, reads each public def's `:meta → :malli/schema`, and expands to
  `register-target!` calls. So **a `:malli/schema` on a public fn IS collected and
  instrumented at every rebuild** — the old "analyzer strips fn-meta" worry is
  handled by reading at compile time.
- What the analyzer DOES strip is custom metadata MARKERS — so opt-out is
  **computed structurally** (`seon.instrument/async-unwrappable?`: the async
  flag + the live fn's arity shape + the schema form), never a per-fn marker
  and never a name list.
- `^:async` fns: the `:malli/schema` describes the RESOLVED value; simple
  fixed-arity `:=>` fns get the Promise-aware **injecting wrapper**
  (input sync + output on resolution); async variadic/multi-arity fns
  (e.g. `seon.db/transact!`) have NO correct wrapper today and register
  nothing — their bodies are the validation boundary. So an `^:async` fn
  returning a Promise of `::transact-response` is schema'd as
  `… ::transact-response` (db.cljs:501-506).
- `SEON_INSTRUMENT` is the kill-switch (default ON).

## reitit — routing-as-data (Phase 5 schema design)

Read: `reference-code/reitit/modules/reitit-core/src/reitit/trie.cljc:60`
(`split-path`), `…/reitit-ring/src/reitit/ring.cljc:14-16`
(`http-methods`/`Endpoint`), `…/reitit-core/src/reitit/core.cljc:54` (`Match`).

- **Path syntax** `split-path` (trie.cljc:60) defaults to `{:syntax #{:bracket
  :colon}}` — BOTH `/agent/{id}` and `/agent/:id` parse. The doc's
  `:seon.route/pattern "/agent/{id}"` is valid. ✓
- **Route shape**: reitit data routes are `[path data]`; for ring, `data` nests
  HTTP-method keys → `Endpoint [data handler path method middleware]`
  (ring.cljc:16); `http-methods` (ring.cljc:14) = `#{:get :head :post :put :delete
  …}`. So a `{:seon.route/pattern :method :handler :middleware}` row maps to
  `["/agent/{id}" {:get {:handler <sym> :middleware […]}}]`. ✓ Phase 5 schema is
  sound.
- ⚠ **Build-note (db->routes, Phase 8/UI):** GROUP rows by `:seon.route/pattern`,
  nest per `:seon.route/method`, resolve `:seon.route/handler` via
  `eval/lookup-value`, and wrap the optional `:seon.route/middleware` keyword
  through reitit's registry. One pattern + N methods = N rows collapsing into
  one reitit path.

## Phase 1-2 keystone — ctx merge→seed-copy + the render engine

Read: `src/seon/ctx.cljs` (the merge/render machinery) + `src/seon/render.cljs`
(the engine). The whole transformation:

- `core-default-ctx` (ctx.cljs:1604) = the hardcoded default block catalog →
  becomes the PRIVATE boot-only seed set consumed by `install!` (Phase 2).
- `gather-sections` (ctx.cljs:1737) = the render-time merge `defaults ∪ agent-own`,
  tagging each `:seon.ctx/agent?` → **DELETED**. `context-root` (ctx.cljs:1821)
  rewritten to read the agent's OWN complete `:seon.agent/ctx`, decode, sort, stop.
- The byte-stable cache split keys on `:seon.ctx/priority ≤ 20` (render-context-ai,
  ctx.cljs:1921-1928), NOT the `agent?` tag — survives seed-copy. The stable
  tie-break loses `agent?`; use `(juxt priority name)` (name is the unique key).
- The render engine: `render` (render.cljs:645) injects the `:seon.render/render`
  recursion handle (660) + a try/catch guard (663-666 → `:seon/error` in Phase 7);
  `resolve-slot` (606) does string/hiccup-verbatim, agent-symbol→SCI-bounded
  (`agent-authored-sym?`, render/sci.cljs:92), core-symbol→direct. Phase 2e
  generalizes the handle into `(slot :name)` (look the block up by
  `:seon.agent.ctx/name` in the agent's `:seon.agent/ctx`).
- `:seon.render/ai` flips required→optional on the block (html-only blocks).
- `render-context` (ctx.cljs:1852) carries a per-agent full-prompt `:seon.render/ai`
  OVERRIDE on the entity — orthogonal to blocks, survives unchanged.

## Output bounding — value-renderer + result-mechanism (REPLACES the char budget)

> **DECISION (owner, 2026-06-27): DROP the per-agent char budget.** Phase 2 deletes
> `apply-agent-budget`, `agent-section-char-budget`, and its truncation marker
> (ctx.cljs:1693-1802). The seeded blocks grow predictably (transcript 53k /
> namespaces 18k chars live — they get ROLLING WINDOWS later, not a char cap); the
> real unbounded-growth risk is agent EVAL OUTPUT, bounded at the eval-render layer.

Read: `src/seon/render/value.cljs` (the structural value renderer — ALREADY the
default for every eval value), `src/seon/eval.cljs:700-784` (the `result/<id>`
mechanism + `result-var-ref?`), `src/seon/ctx.cljs:372-470` (the render caps).

The machinery already shipped:
- `seon.render.value/sample` (value.cljs:248) → a depth+breadth-bounded SKELETON
  (max-depth 3 / max-items 8 / max-keys 8, env-overridable) that PRESERVES
  navigation paths (keys/indices), per-node type+count, the homogeneous column-set
  (`… +129 more each {:a :b :c}`), lazy-safe head sampling, opaque-handle markers.
  `render-ai` (value.cljs:393) appends the `result/<id>` drill hint.
- The `result/<id>` mechanism (eval.cljs:736-784): every eval value is stashed in
  `globalThis.result.<id>` (+ an analyzer def), last 200 kept; the agent drills with
  `(get-in result/<id> […])` / `filter` / `count` WITHOUT re-running.
- Current caps (owned by `seon.config`, `:seon.config/render` manifest section;
  ctx.cljs reads them): `eval-render-cap` 1500 (echoed source+stdout),
  `result-body-render-cap` 16384 ≈ 4k tokens (the citable `;;=>` body, skeleton'd +
  row-capped 50; ONE owner since C32 — eval's persistence clip reads the same knob
  as a chars/4 token budget), `message-render-cap` 4000.

**The target model — three tiers, bound the VIEW not the storage:**

1. **Incidental eval output → the bounded skeleton** (`sample` + `result-body-render-cap`
   ≈ 4k tokens) + the `result/<id>` handle. Already shipped; this IS the "reasonable
   default." Don't dump a possibly-accidental huge result into context.
2. **Intentional query against a result → high tolerance (~50k tokens / unbounded).**
   When the eval form REFERENCES a `result/<id>` var (a deliberate drill —
   `result/<id>`, `(get-in result/<id> …)`, `(filter … result/<id>)`), the agent
   explicitly asked for that data: render with a loosened skeleton + a much higher
   char cap (new `SEON_RESULT_DRILL_CAP` ≈ 200000 chars ≈ 50k tokens), so it SEES
   the data instead of a re-skeleton. Detect by broadening `result-var-ref?`
   (eval.cljs:750, today only the bare symbol) to "the form's source references a
   `result/<id>` token." (Phase 2 / eval-render refinement.)
3. **Seeded blocks → predictable now, ROLLING WINDOW later** (e.g. transcript last-N
   turns). Deferred; tracked in [[roadmap]].

This replaces the blunt char budget with bounding at the actual growth source, reusing
the shipped value-renderer + result-mechanism. The html half (the collapsible
value-explorer over `render-html-data`, value.cljs:427) is UI's lane.

## Idioms to internalize — stop writing JS/Java-shaped Clojure

Concrete examples (file:line) of the patterns to imitate:

- **Build maps with optional keys via `cond->`, never mutation** —
  `malli->datahike-attr` (internal.cljs:344-350): start with the base map,
  conditionally `assoc`.
- **Transduce, don't stack intermediate seqs** — `(into #{} (comp (filter…)
  (map…)) coll)` (transaction.cljc:730), not `(set (map… (filter…)))`.
- **Expression-oriented `cond`/`if`/`when`** — every branch yields a value; no
  early-return statements (the whole bridge + `compare-and-swap`).
- **nil-punning** — `explain`/`humanize` return `nil` for "valid"; `when errors`
  / `when-let` lean on it. Don't invent an empty sentinel.
- **Errors are data** — `(log/raise msg {:error :ns/kw …})` (ex-info) and our
  `{::ok? false …}` envelopes; never throw a bare string, never `try` for control
  flow.
- **`reduce` with `reduced` for short-circuit** — the `:orn` first-match
  (core.cljc:1078), the explainer early-out (core.cljc:1071).
- **Higher-order build-once, call-many** — `explainer`/`parser` build a fn once;
  `explain`/`parse` are one-shots over it (core.cljc:2643/2668).
- **Destructure at every binding** — `[_ e a ov nv]`,
  `{:keys [tempids db-after]}`, `{:keys [wrap resolve] :or {…} :as options}`.
- **`reify` protocols for behavior objects** — malli schemas are reified
  `IntoSchema`/`Schema` (core.cljc:1044), not naked maps.
- **`:malli/schema` multi-arity = `:function` with one `:=>` per arity** —
  `invoke-bounded` (render/sci.cljs:360-370). Lower arities delegate to the full
  one with schema-valid defaults.
- **Volatiles only for genuine per-call runtime state** — `!deadline`/`!input`
  (render/sci.cljs), the same category the reactive-context rule allows (compile
  state, DB conn). Never an atom for derivable state.

## Validation summary — doc-refinements found (fold into the canonical docs)

1. ⚠ agent-runtime.md:115 — fence OV/NV `[run R]` → `[:seon.agent.run/id R]`
   (lookup-ref), since `:seon.agent/run` is a ref.
2. ✓ Phase 5 open-race is SOLVED + live-proven at `run.cljs:215-274` (`open-run!`):
   run-row first, CAS NV = lookup-ref, resolves against the in-tx run. KEEP the run
   lifecycle (`open-run!`/`close-run!`/`run-fence`) — build the seed/`start!` layer
   around it, do NOT rebuild it.
2a. ✓ Build the fence with `db/cas-assert` (db.cljs:399), never a hand-written
   `:db.fn/cas` vector. The CAS executes at the SOLE writer, so the fence is sound
   across the wire (store.wire.cljs:12-21).
2b. ✓ Instrumentation: write `:malli/schema` normally (collected at compile time,
   instrument.cljc:109); opt-out is STRUCTURAL (`seon.instrument/async-unwrappable?`
   — async fns with no correct wrapper shape), NOT a metadata marker and NOT a
   name list; `^:async` fns are schema'd on the RESOLVED value.
3. ✓ Make explicit in data-model: `register!` ≠ datahike-bridge; in-memory `:map`
   value shapes (the `:seon/error` family, `:seon.warn/check-response`,
   `:seon.derive/status`) never bridge — only transacted attrs do.
4. ✓ data-model §3 — note the `:orn` branch-order constraint (most-specific-first;
   malli returns first-match).
5. ✓ Build-note for error construction: `explain` returns `nil` on valid.
6. ✓ `:my.plan/parent` is a plain ref (NOT a component) — confirmed correct in
   the doc; the grounding makes the "no cascade" consequence explicit.

## Lane-U grounding — reitit + the gzip-morph live channel (Phase 8)

> **Lane-U section** (appended; Core owns everything above). Read these BEFORE
> building Phase 8. The live channel is PROVEN — `src/seon/web/datastar.cljs` IS the
> reference implementation; read it first, then the sources it ports from.

### reitit is CLJS-clean — read the source, don't fear the Java

Read: `reference-code/reitit/modules/reitit-core/src/reitit/core.cljc:331-383`
(`router`), `…/reitit-core/src/reitit/trie.cljc:346-350` (`compiler`),
`…/reitit-ring/src/reitit/ring.cljc:360-420` (`ring-handler` +
`reloading-ring-handler` just below).

- **`router` (core.cljc:331-383)** is pure data→router: `resolve-routes` → conflict
  detection (path + name) → `compile-routes` → pick an impl (`single-static-path-
  router` / `lookup-router` / `trie-router` / `mixed-router`) by whether routes are
  wild. All `.cljc`, no host types — runs in Node as-is. `match-by-name` (reverse
  routing) and build-time conflict detection are the wins over the hand-rolled `cond`.
- **`trie/compiler` (trie.cljc:346-350)** is the ONLY place the impl forks on host:
  `#?(:cljs (clojure-trie-compiler) :clj (java-trie-compiler))`. **CLJS ALWAYS gets
  the pure-Clojure trie**; the `JavaSegmentRouter` is `:clj`-only and never loaded in
  the pod. This is why the build delta is a Maven dep, not source-paths.
- **`ring-handler` (ring.cljc:360-420)** has BOTH a sync `([request])` and an async
  `([request respond raise])` arity, dispatching `(-> result method :handler)` after
  `match-by-path`. ⚠ **Sharp edge (ring.cljc:389):** the sync arity is
  `(or (handler request) (default-handler request))` — a matched handler that returns a
  FALSY value triggers a SECOND call to the default-handler. Our handlers all return the
  truthy hijack sentinel `{:seon.http/hijacked true}`, so the `or` short-circuits and the
  default never double-fires — but this makes "every router handler returns the truthy
  sentinel" a LOAD-BEARING invariant: a future handler that writes the socket then returns
  `nil` would have reitit re-invoke `legacy-default` → a double-write ("headers already
  sent") crash. Documented in `router.cljs`.
- **`reloading-ring-handler` (ring.cljc:406-420)** recreates the handler PER REQUEST from a
  0-arity thunk — good for REPL dev but wasteful per-request. ⚠ **We did NOT use it.** The
  router rebuilds on hot-reload via `install!` (serve re-runs it on `:dev/after-load`),
  caching one handler in `!ring-handler` — rebuilt once per reload, not once per request.
  Same "router is a pure derived value of the route datoms" goal, cheaper. (When Phase-5
  `db->routes` lands, `install!` rebuilds on the route-tx too.)
- **reitit-malli coercion is zero-interop** — it validates/coerces path-params /
  query / body against a route's `:parameters` malli schema with the SAME malli we
  already instrument with. No new validation surface; our malli-everything maps ride
  as open route-data with no friction.

### Build delta — Maven, in `:cljs`, NOT source-paths

`deps.edn :cljs :extra-deps` is Maven coords (`:mvn/version`). ADD
`metosin/reitit-ring {:mvn/version "0.10.1"}` + `metosin/reitit-malli {:mvn/version
"0.10.1"}` there (reitit-ring pulls reitit-core transitively). Do NOT put
`reference-code/reitit` on source-paths — the published CLJS artifact is clean;
vendoring the source would drag the `:clj`-only Java trie onto the classpath.

### The Node↔Ring adapter + the hijack sentinel

reitit speaks Ring (a request MAP → a response MAP); the pod speaks raw `node:http`
`(req, res)`. The ~20-line adapter builds a Ring request from the node `req` (method,
uri, query, headers) AND injects the raw node `req`/`res` under namespaced keys so the
streaming + static handlers can reach them. A handler that takes over the socket
itself (the SSE open, a static file pipe) returns `{:seon.http/hijacked true}`; the
adapter sees the sentinel and does NOT write a Ring response (the handler already owns
the stream). This is how the gzip SSE handler — which needs the raw `res` to `.pipe`
the gzip stream — coexists with reitit, which has zero streaming primitives by design.

### KEEP-VERBATIM — the interactivity floor + the capability gate

Read: `src/seon/web/reactive/transform.cljs:183-197` (`transform-hiccup`),
`src/seon/web/reactive/call.cljs` (`resolve-owning-agent`:63 / `granted-fn?`:82 /
`capability-check`:98 / `invoke!`:126 / `handle!`:203).

- `transform-hiccup` is a render-time server-side postwalk that rewrites a fn-call
  `(cancel-order! id)` or a fn-ref `submit-order!` in a handler slot into one standard
  datastar `@post` (the `?fn=`/`?args=` descriptor; `call-action`, transform.cljs:114).
  It is a PURE hiccup→hiccup transform — keep it + its render.cljs:439-452 wiring
  verbatim; reitit routes the door, this rewrites the body, they are orthogonal.
- The capability gate `handle!` resolves the owning agent FROM the fn symbol's
  namespace (`my.agent.<id>`), capability-checks (the fn must be a registered
  `:seon.fn` the agent owns), and only then `invoke!`s it SCI-bounded. The gate is the
  SECURE refusal point; reitit replaces only the FRAGILE URL dispatch. Phase 8 moves
  ONLY the door's registration to a `/agent/{id}/call` route datom — the gate's logic
  is untouched. (The `{id}` path segment is redundant with the fn's namespace and the
  gate still authorizes from the namespace; the segment is just the routing level. The
  kept `call-action` currently emits a flat `/call?fn=…` — repoint it at
  `/agent/{id}/call` when the route lands; that is the only edit to the floor.)

### The client/SSE contract — datastar v1, packetstar DITCHED

Read: `reference-code/datastar-clojure/libraries/sdk/src/main/starfederation/datastar/
clojure/consts.clj`; the proven `src/seon/web/datastar.cljs` (`patch-elements`:58).

- The wire event is **`datastar-patch-elements`**; each HTML line is one
  `data: elements <line>` dataline; the default patch mode is **`outer`**
  (`element-patch-mode-outer "outer"`, consts.clj) = morph the element with the
  matching id — so a whole-element morph needs NO selector/mode dataline. A blank line
  terminates the event. (consts.clj also lists `inner`/`remove`/`replace` and the
  `signals` dataline for `datastar-patch-signals`.)
- **packetstar.js is DITCHED.** Its per-tile `{id, html}` streaming protocol, the
  old slot-tree BFS diff, and the UI-side `since-t` replay are all DELETED. The
  default client is datastar.js; idiomorph does the granular DOM diff client-side, so
  the server pushes whole elements and never computes a diff.

### hyperlith — the model we ported

Read: `reference-code/hyperlith/src/hyperlith/core.clj:88-110`
(`defview`/`defaction`/`refresh-all!`), `…/impl/datastar.clj:122-181`
(`render-handler`), `…/examples/chat_atom/src/app/main.clj`; and the gzip-SSE proof
`reference-code/datastar-clojure/src/dev/examples/tiny_gzip.clj`.

- `defview` (core.clj:99-105) registers a shim-handler (the page, `[:get path]`) + a
  render-handler (the stream, `[:post path]`) at the SAME path — hyperlith disambiguates
  shim-vs-stream by HTTP METHOD (GET=page, POST=stream), so its `on-load-js` does
  `@post(window.location.pathname …)` with a `&u=` cache-buster (its comment: GET/POST
  cache headers collide on one URL). ⚠ **We DIVERGED — and the divergence is correct:**
  our shim and feed are SEPARATE paths (`/view` GET → `/view/feed` GET; `/agent/{id}`
  GET → `/agent/{id}/feed` GET). This matches datastar-clojure's OWN canonical example
  (`tiny_gzip.clj:31` — page at `/`, stream at a separate GET `/updates` via
  `(d*/sse-get "/updates")`), and because the two URLs differ there is NO GET/POST
  same-URL cache collision, so we need none of hyperlith's `&u=` hack. **The doc claim
  "no /feed, same path" was WRONG vs the live code — corrected here + in [[ui]]/coordination.**
- `render-handler` (impl/datastar.clj:122-181) is the engine: tap the `refresh-mult`
  through a `(dropping-buffer 1)` (= the drop-latest throttle), render on connect, then
  on each refresh re-run `(render-fn req)`, write `event: datastar-patch-elements\n
  data: elements ` + the streamed HTML, flush, and `send!`. `refresh-all!`
  (core.clj:107-110) is one global refresh signal — our `db/listen!` tx-listener plays
  that role.
- **Difference to note:** hyperlith compresses with **brotli** (`br-window-size`); our
  Node proof uses **gzip** (`zlib.createGzip` + `Z_SYNC_FLUSH`) because the Node zlib
  path is the proven one — `tiny_gzip.clj` is the datastar-clojure proof that small
  gzip-compressed SSE events flush fine (no server buffering). Same wire event,
  different `Content-Encoding`.

### Ops — streamed surfaces can't be browser-verified

A long-lived `text/event-stream` 503s through the in-tool chrome agent's network
layer, so do NOT try to verify the morph with a browser agent. Verify SERVER-SIDE: a
small Node client opens the gzip stream, gunzips, and asserts the payload changes on a
real datahike tx (the `seon.web.datastar` acme proof: agents view `1→2→1` on ADD/RETRACT,
~300ms post-commit, store clean). The final live-morph eyeball is the OWNER's, in real
Chrome.

### Idiom cheat-sheet (Lane-U)

- **Hiccup→hiccup postwalk for interactivity** — never string-concat HTML; rewrite the
  data (`transform-hiccup`).
- **The router is a derived VALUE** — `db->routes` is a pure fn of `:seon.route/*`
  datoms; `reloading-ring-handler` rebuilds it per request, no mutable route registry.
- **The stream is a fn of the db** — `view = f(db-as-of t)`; never accumulate UI state,
  never push a delta the agent computed. Re-render the whole element; let idiomorph
  diff.
- **Crash-proof every write** — guard `writableEnded`, error-handlers on gz + res,
  `req.on('close')` cleanup; one uncaught throw blanks every connection on the single
  pod thread.
- **Transient client state = datastar signals**, never DOM attrs — a morph must not
  clobber the input value / popover / time-slider.
- **The hijack sentinel** `{:seon.http/hijacked true}` keeps raw-socket handlers (SSE,
  static) verbatim under a Ring router.

### Validation re-grounding (2026-06-27) — findings vs the live code

Re-read hyperlith (`core.clj`, `impl/datastar.clj`), reitit (`reitit-ring/.../ring.cljc`),
datastar-clojure (`consts.clj`, `tiny_gzip.clj`) against `web/datastar.cljs`,
`web/router.cljs`, `ui/view.cljs`. The wire framing + view=f(db) + crash-guards all
hold. Findings, by severity:

- 🔴 **A — feed routing: doc/contract was WRONG, would 404 the feed at Phase 5.** The code
  uses separate GET feed paths (`/view/feed`, `/agent/{id}/feed`); the doc said "no /feed,
  same path." The code is the idiomatic choice (datastar-clojure `tiny_gzip.clj:31`). FIXED
  the routing contract here + [[ui]] + coordination Handoff #3 / Interface #2 so Phase-5
  `db->routes` SEEDS the feed routes (`/view/feed`, `/agent/{id}/feed` GET). Without this
  fix the feeds vanish when the static route vector is replaced by the db projection.
- 🟠 **B — no reconnect/retry hardening on the feed.** `shim-html`'s `data-init="@get('…')"`
  relies on datastar defaults; hyperlith sets `{retryMaxCount: Infinity, openWhenHidden:
  false}` + a `data-on:online__window` reconnect for an always-on view. For an agent
  dashboard that must survive pod restarts / sleep, add those to the `@get`. (Task.)
- 🟠 **E — historical time-travel is DESIGNED, not WIRED.** The feed view-fns close over
  `@db/*conn*` (current db) with no `t` thread + no time-slider signal. `view = f(db-as-of
  t)` is the model; the slider-signal → `db-as-of` path is unbuilt. Don't read the vision's
  "time travel" as shipped. (Task — it's a stated owner goal.)
- 🟡 **C — reitit falsy-handler double-invoke** (see `ring-handler` note above): an invariant,
  not a current bug; documented in `router.cljs`.
- 🟡 **D — `reloading-ring-handler` not used** (see note above): we rebuild-on-reload via
  `install!`; cheaper. Doc corrected.
- ⚪ **F — single-thread render, no CPU-pool offload.** hyperlith offloads render+compress to
  a CPU pool (`cp/on-cpu-pool`) so a slow render never blocks the accept loop; Node is
  single-threaded, so `broadcast!` renders+gzips every connection synchronously on the event
  loop. Fine at agent-dashboard scale (a handful of connections); a known ceiling if fan-out
  grows (then: `worker_threads`, or shard the render). Already implied by the "one uncaught
  throw blanks every connection on the single pod thread" guard rule.

Idiom wins folded in: hyperlith's `with-open` stream-resource scoping, `loop`/`recur` over
the refresh channel, `er/try-on-error` wrapping EVERY render (mirrors our per-tile guards),
and the `dropping-buffer 1` drop-latest throttle (our `setTimeout` coalescer is the Node
analog). Our `datastar.cljs` is in-grain: guarded renders, crash-proof writes, `view=f(db)`.

### How to task an agent with this grounding

> You are building Phase 8 (Lane-U) of Seon's agent-fsm, opus. READ FIRST, in order:
> `src/seon/web/datastar.cljs` (the PROVEN gzip-morph streamer — this is the
> reference, copy its shape), then [[ui]] (the target) + this Lane-U section's reitit
> / hijack / interactivity-floor / hyperlith pointers. Build reitit over
> `:seon.route/*` datoms (Maven `metosin/reitit-ring` + `reitit-malli` 0.10.1 in
> `deps.edn :cljs`, NOT source-paths); the router is a derived value (`db->routes` +
> `reloading-ring-handler`). KEEP the capability gate (`seon.web.reactive.call`) and
> `transform-hiccup` VERBATIM — move only the action door to a `/agent/{id}/call`
> route datom and repoint `call-action`'s path. The live channel is the gzip-morph
> stream (no slot-tree diff, no per-tile packet, no `since-t`); verify it SERVER-SIDE
> with a Node gunzip client on a real tx, never a browser agent; the final eyeball is
> the owner's in real Chrome.
