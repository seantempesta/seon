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
`:my.todo/parent` is a PLAIN ref (not component) — the tree is NOT owned, so
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
- The OUTER `try` (374-377): *"invoke-bounded must NEVER throw"* → degrade to the
  compiled path. ✓ never-crash for Phase 2e's render engine. SCI is a safety net
  for hangs, never a correctness gate (returns `{…/interrupt true}` or
  `{…/fallthrough true}`, caller recovers).
- The agent ns env is reconstituted from `:seon.fn/source`/`:seon.ns/source` rows
  each call — **code-as-data: the runtime IS the database**.

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
  `eval/lookup-value`, map `:seon.route/middleware` keywords through reitit's
  registry. One pattern + N methods = N rows collapsing into one reitit path.

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
2. ⚠ Phase 5 — open-race tx must be `[create-run, cas]` (CAS sees the running
   in-tx db; `entid-strict` raises on a missing run).
3. ✓ Make explicit in data-model: `register!` ≠ datahike-bridge; in-memory `:map`
   value shapes (the `:seon/error` family, `:seon.warn/check-response`,
   `:seon.derive/status`) never bridge — only transacted attrs do.
4. ✓ data-model §3 — note the `:orn` branch-order constraint (most-specific-first;
   malli returns first-match).
5. ✓ Build-note for error construction: `explain` returns `nil` on valid.
6. ✓ `:my.todo/parent` is a plain ref (NOT a component) — confirmed correct in
   the doc; the grounding makes the "no cascade" consequence explicit.
