---
type: research
status: draft
tags: [research, agent]
---

# Tool research — `my.code` (`forget!` / undefine a defined symbol; maybe `rename!`)

The third symbol-lifecycle verb. The agent can DEFINE and REDEFINE (= upsert) a
fn / schema / test; it cannot REMOVE one — a wrong `(defn …)` lingers as a live
binding AND a `:seon.fn` row forever. `forget!` retracts whichever entity owns a
unique sym and drops the live binding, `:core-seed`-guarded; undo is free from the
bitemporal store. This note researches the best out-of-the-box implementation
given the self-hosted-CLJS-on-Node constraint, surveys what seon already has,
and recommends the wrap/build call + the composable map-in/map-out surface.

## TL;DR

- **Recommendation: HYBRID — thin-wrap-existing-seon + one tiny generalized floor
  primitive. Do NOT wrap an external library (none fits) and do NOT build fresh
  (90% already exists).** The retract, the core-guard, the undef pattern, and the
  bitemporal undo all exist in `seon.*` today. The single genuinely-new floor
  addition is to GENERALIZE the existing private `seon.eval/unbind-result-var!`
  (which already does exactly "dissoc the analyzer def + delete the munged
  globalThis property", but hard-coded to the `result` ns) into a public
  `seon.eval/undef-sym!` over an arbitrary FQ sym. `my.code/forget!` is the thin
  `:toolkit-seed` wrapper that orchestrates resolve → guard → retract → undef.
- **No library is the right answer.** JVM Clojure's `ns-unmap` is the correct
  DESIGN model (single-symbol undefine, not whole-ns reload) but is JVM-only — it
  mutates a namespace's Var→symbol mappings, and self-hosted CLJS has no
  first-class namespaces or Vars at runtime. The only CLJS analyzer helper,
  `cljs.analyzer.api/remove-ns`, is whole-NAMESPACE (wrong granularity). replumb's
  `ast.cljs` `dissoc-*` helpers undo a REQUIRER's `:uses`/`:requires` (the
  reference side), NOT a definer's `:defs` (the def side). Nothing — Clojure,
  CLJS, or npm — provides surgical single-DEF undefine for a self-hosted cljs.js
  runtime. seon's own `unbind-result-var!` IS that mechanism already.
- **One verb, three kinds, but "drop the live binding" dispatches on kind** — the
  catalog under-specifies this:
  - **fn / test** → undef the globalThis var + the analyzer `:defs` entry (a
    `deftest` is itself a 0-arg fn, so identical handling). `seon.eval/undef-sym!`.
  - **schema** → there is NO globalThis var; the live "binding" is the in-memory
    registry entry in `seon.schema/*schemas` (still drives transact!-boundary
    validation + instrumentation until process restart). Retracting the
    `:seon.schema` row does NOT clear it. This needs a second tiny floor verb,
    `seon.schema/unregister!` (`swap! *schemas dissoc k`), which also does NOT
    exist yet. Flag.
- **Core-guard: reuse + generalize one provenance rule.** `forget!` must refuse a
  `:core-seed` sym. The fn guard exists (`seon.eval/core-origin-fn-syms`); the
  schema guard exists INLINE in `tee-registered-schema!` (queries `:seon.schema/
  source` tx origin); tests have no `:core-seed`. Recommend extracting ONE
  `core-origin?` predicate keyed by identity-attr so all three kinds share the
  same rule (register-once discipline).
- **Composability win over the catalog sketch:** `forget!` should RETURN the
  retracted source as `:my.code/prior-source`, so undo is one thread —
  `(forget! 'my.x/foo)` → re-`eval` the returned source — with no separate
  `db/history` round-trip. forget! becomes reversible by re-eval, uniformly across
  fn/schema/test (all three sources are replayable forms).
- **`rename!` — DEFER (compose, don't add).** `rename!` = read old source →
  `eval` it under the new sym → `forget!` the old. It is `forget!` + the agent's
  normal define move; build it only if it recurs in live drives. Shape given below.
- **Naming smell in the catalog:** the `my.code` sketch uses `:seon.code/*` for its
  OWN payload (sym/kind/ok?/response), but by the catalog's own rule
  ("tool-specific payload keys are `my.<tool>/*`; the shapes you THREAD are
  `seon.*`") those should be `:my.code/*`. Only the shared error map stays
  `:seon.error/*` and the discriminator references `:seon.result/ok?`. Recommend
  `:my.code/*` for the wrapper payload.

## The constraint that decides it: self-hosted CLJS on Node, no JVM

The agent's eval goes through the **cljs.js bootstrap** (`cljs/eval-str` +
`cljs.analyzer` state), NOT SCI (SCI is only the render-tile sandbox). Two
consequences pin the implementation language:

1. **Namespaces are not first-class runtime objects.** A "namespace" is an entry
   in the compiler-state map under `:cljs.analyzer/namespaces`, and each ns has a
   `:defs` map of `symbol → var-ast`. There is no `Namespace`/`Var` object, so
   there is nothing for a JVM-style `ns-unmap` to mutate.
2. **A def has TWO live materializations** the analyzer state + the emitted JS on
   the global object (shadow's munge scheme: `my.x/foo` → `globalThis.my.x.foo`,
   each segment `cljs.core/munge`'d). Plus, in seon, a THIRD: the persisted
   `:seon.fn` / `:seon.schema` / `:seon.test` datom (code-as-data — three views of
   one corpus). "Forget" must touch all three or it desyncs (a retracted row with
   a live globalThis binding = a callable ghost the agent can't see; the catalog
   already names both halves load-bearing — this note adds the third, the datom,
   and the schema-registry case).

A CLJS subtlety in our favour: cljs.js emits call sites as a property READ at call
time (`my.x.foo(...)`), not a captured closure. So after `delete globalThis.my.x.
foo`, any surviving caller throws loudly at runtime ("foo is not a function")
rather than silently holding a stale Var (the JVM `ns-unmap` caveat — "other
namespaces may still hold references to the un-mapped Var"). Loud is what we want.

## Options compared

### Option A — wrap an external library — REJECTED (none fits)

| Candidate | What it is | Why it does NOT fit |
|---|---|---|
| JVM `clojure.core/ns-unmap` | Removes ONE symbol's mapping from a namespace's Var table. The canonical single-symbol undefine — correct GRANULARITY model. | JVM-only. Operates on `Namespace`/`Var` objects that do not exist in self-hosted CLJS. Inspiration for the API, not the impl. |
| JVM `clojure.core/remove-ns` + `clojure.tools.namespace.repl/refresh` | Whole-namespace removal / dependency-tracked whole-ns reload. | Wrong granularity (forget ONE sym, not reload a ns) AND JVM-only. tools.namespace tracks file deps for a refresh workflow seon doesn't use (the DB is the running system; redefine = upsert). |
| `cljs.analyzer.api/remove-ns` | The ONLY CLJS analyzer removal fn. `(remove-ns state ns)`. | Whole-NAMESPACE, not single-def. Would nuke every sibling def in the ns. Wrong granularity. |
| replumb `ast.cljs` (`dissoc-symbol`/`dissoc-macro`/`dissoc-require`/`dissoc-all`/`dissoc-ns`) | A self-hosted-REPL library's compiler-state helpers. | These dissoc a REQUIRER ns's `:uses`/`:requires`/`:imports`/`use-macros` (undo a reference/alias), NOT a DEFINER's `:defs` (undo the definition). There is no `dissoc-def`. Solves un-requiring, not un-defining. |
| npm modules | — | None exist. Undefining a CLJS var is a cljs-compiler-state concern, not a Node concern. Node interop only supplies `Reflect.deleteProperty` (a builtin), which seon already uses. |

**Verdict:** there is no out-of-the-box library to wrap. The "best existing
implementation" of single-symbol CLJS undefine lives INSIDE seon already.

### Option B — build fresh — REJECTED (reinvents what exists)

A from-scratch `forget!` would re-derive: the core-origin provenance query, the
retract envelope, the munge→globalThis path walk, the analyzer `:defs` dissoc, and
the bitemporal undo. Every one of those exists as a tested `seon.*` surface
(below). Building fresh violates Don't-Be-A-Dumbass (one mechanism).

### Option C — thin-wrap-existing-seon + ONE generalized floor primitive — RECOMMENDED (hybrid)

Everything `forget!` needs is present except a public general undef. The honest
move is to generalize the private helper, not duplicate it.

## What seon already has (the floor `forget!` stands on)

| Need | Existing surface | File:loc | Reuse |
|---|---|---|---|
| Undef a sym (analyzer `:defs` dissoc + munged-globalThis delete) | `unbind-result-var!` (private; hard-coded to the `result` ns) | `eval.cljs` ~L1035 | GENERALIZE → public `seon.eval/undef-sym!` over any FQ sym |
| Munge-path walk to a live ns object | `lookup-ns-object` ("THE ONE munge scheme") | `eval.cljs` ~L303 | call it in `undef-sym!` to find the obj whose property to delete |
| Resolve a sym's runtime value (existence check) | `lookup-value` | `eval.cljs` ~L317 | optional: confirm the binding existed |
| The live agent compile-state | `seon.repl/!compile-state` (atom) | `repl.cljs` L76 | `undef-sym!` mutates this (same state evals use) |
| Core-origin guard for FNS | `core-origin-fn-syms` (+ `reject-core-overrides`) | `eval.cljs` ~L1702 | generalize to a `core-origin?` predicate by identity-attr |
| Core-origin guard for SCHEMAS (inline) | origin query in `tee-registered-schema!` | `eval.cljs` ~L1815 | fold into the same `core-origin?` |
| Retract an entity (history-preserving) | `db/transact!` `[:db.fn/retractEntity [<id-attr> v]]` | `db.cljs` L486 | the retract half, verbatim |
| Identity attrs (resolve which entity owns the sym) | `:seon.fn/sym` `[:string …]`, `:seon.test/sym` `[:string …]`, `:seon.schema/key` `:keyword` | `agent.cljs` L179 / `test/runner.cljs` L126 / `schema.cljc` | try in order to find `kind` |
| Bitemporal undo | `db/history` / `db/as-of` / `db/since` | `db.cljs` L987-L1018 | re-assert prior source = undo |
| Schema registry (the schema "live binding") | `seon.schema/*schemas` (private atom); **no `unregister!`** | `schema.cljc` L27, L225 | NEEDS a new tiny `seon.schema/unregister!` (`swap! *schemas dissoc k`) |

**Two tiny floor additions, both generalizations of one-liners that exist:**

1. `seon.eval/undef-sym!` — generalize `unbind-result-var!` from the `result` ns
   to an arbitrary FQ sym. Body (positional, floor verb):

   ```clojure
   (defn undef-sym!
     "Drop a defined sym's LIVE binding: dissoc its analyzer :defs entry and
      delete its munged property on globalThis. Inverse of an eval'd def; the
      general form of unbind-result-var!. Best-effort, never throws."
     ;; [:=> [:catn [::compile-state :any] [::fq-sym :symbol]] :boolean]
     [compile-state fq-sym]
     (let [ns-str (namespace fq-sym), nm (name fq-sym)]
       (when-some [obj (lookup-ns-object ns-str)]
         (js/Reflect.deleteProperty obj (cljs.core/munge nm)))
       (swap! compile-state update-in
              [:cljs.analyzer/namespaces (symbol ns-str) :defs] dissoc (symbol nm))
       true))
   ```

2. `seon.schema/unregister!` — the schema analog of "drop the live binding"
   (`swap! *schemas dissoc k` + drop any derived id-attr). Without it a forgotten
   schema's attr stays validation-live until restart.

## Recommended agent-facing API (map-in / map-out, threadable)

`my.code` is a `:toolkit-seed` editable wrapper (refer'd into the home ns beside
the lifecycle verbs). Payload keys are `:my.code/*`; the discriminator references
the shared `:seon.result/ok?`; failures carry the shared `:seon.error/*` map.

```clojure
;; tool-specific payload (catalog had these as :seon.code/* — should be :my.code/*)
(schema/register! :my.code/sym  [:or :symbol :keyword]) ; fn/test = symbol; schema = keyword
(schema/register! :my.code/kind [:enum :seon.fn :seon.schema :seon.test])
(schema/register! :my.code/prior-source :string)        ; the retracted source — re-eval to undo

(schema/register! :my.code/forget-request
  [:or :my.code/sym                                      ; sugar: a bare sym
       [:map [:my.code/sym :my.code/sym]]])              ; or the map form
(schema/register! :my.code/forget-response
  [:or [:map [:my.code/ok? [:= true]]   [:my.code/sym :my.code/sym]
             [:my.code/kind :my.code/kind] [:my.code/prior-source :my.code/prior-source]]
       [:map [:my.code/ok? [:= false]]  [:seon.error/message :string]
             [:seon.error/data :map]]])

(defn ^:async forget!
  "Remove a symbol you defined. Resolves the owning entity by its unique identity
   (:seon.fn/sym | :seon.test/sym | :seon.schema/key), REFUSES a :core-seed sym
   (you cannot delete the protected floor), retracts the entity (history retains
   every prior value), and drops the live binding (fn/test: undef globalThis +
   analyzer; schema: unregister from the registry). Errors are values; returns the
   retracted source as :my.code/prior-source so undo is one re-eval.
   (forget! 'my.x/old-helper)"
  ;; [:=> [:catn [::req :my.code/forget-request]] :my.code/forget-response]
  )
```

**The forget! pipeline (all pieces above):**

1. **Resolve kind** — try `:seon.fn/sym (str sym)`, then `:seon.test/sym (str sym)`,
   then `:seon.schema/key (keyword sym)`. None → `{:my.code/ok? false :seon.error/
   message "no fn/schema/test owns <sym>" :seon.error/data {:seon.error/kind
   :user-input}}`.
2. **Core-guard** — `(core-origin? @db/*conn* id-attr v)`; if core →
   `{:my.code/ok? false …kind :core-protected}` (same message family
   `reject-core-overrides` warns). A `:toolkit-seed` `my.*` wrapper is NOT
   `:core-seed`, so it IS forgettable — exactly the owned-tool semantics.
3. **Capture prior source** (`db/pull` the `:seon.fn/source` | `:seon.schema/
   source` | `:seon.test/source`) for `:my.code/prior-source`.
4. **Retract** — `db/transact! {:seon.db/tx-data [[:db.fn/retractEntity [id-attr v]]]}`.
5. **Drop live binding** (kind-dispatched): fn/test →
   `(seon.eval/undef-sym! @seon.repl/!compile-state sym)`; schema →
   `(seon.schema/unregister! k)`.
6. **Return** the success envelope.

**Threading (outputs feed inputs — the catalog's backbone):**

```clojure
;; forget → undo by re-eval, no history round-trip:
(let [{:my.code/keys [ok? prior-source]} (forget! 'my.x/old-helper)]
  (when ok? prior-source))            ; -> a replayable (defn …) string → (eval …)

;; resolve-from-grep → forget, threading a located hit's sym:
(->> (search/grep {:seon.search/pattern "defn old-"})
     :seon.items/items
     (map :my.code/sym)              ; if a match carries the sym; else read+parse
     (map forget!))                  ; each -> a RESULT envelope
```

The error envelope is the SHARED `:seon.error/*` map (RESULT backbone), so a
`forget!` failure threads identically to a `db/transact!` or `my.search` failure.

### `rename!` — DEFER; compose from `forget!` + define

```clojure
(schema/register! :my.code/rename-request
  [:map [:my.code/from :my.code/sym] [:my.code/to :my.code/sym]])
;; (rename! {:my.code/from 'my.x/foo :my.code/to 'my.x/bar})
;;   1. read foo's :seon.fn/source ; 2. (eval (s/foo/bar/ source))  → defines bar
;;   3. (forget! 'my.x/foo)                                          → drops foo
;; NOT atomic across the two txs, but the bitemporal store makes it recoverable.
```

Build `rename!` only if renames prove common in live drives (the catalog's "maybe
rename!"). It adds no mechanism — it is the two existing verbs in sequence.

## Composability alignment to the catalog shapes

- **RESULT** — `:my.code/ok?` references `:seon.result/ok?`; failures use the
  shared `:seon.error/*` map with `:seon.error/kind` `:user-input` (bad sym) vs
  `:core-protected` (floor-guarded) so the agent decides "fix my arg" vs "that's
  intentional." Conforms.
- **REF** — the sym IS the address; resolution uses the entity's own identity attr
  (`[:seon.fn/sym v]` is a lookup-ref, same family as `:seon.db/ref`). Conforms.
- **PATH / ITEMS** — not applicable (forget! is a single-sym verb, returns one
  envelope, not a collection); `:my.code/sym` threads back into `eval`/`history`,
  and `:my.code/prior-source` threads into `eval` for undo.
- **map-in / map-out** — bare-sym sugar OR `{:my.code/sym …}` in; one envelope
  out. Matches the catalog's stated `forget!` shape (modulo the `:seon.code/*` →
  `:my.code/*` rename this note recommends).

## Gotchas / findings to carry into the build

1. **Schema "drop binding" ≠ globalThis undef.** A schema has no global var; its
   live binding is `seon.schema/*schemas`. `forget!` of a schema MUST call a new
   `seon.schema/unregister!` or the attr stays validation-live (instrumentation +
   transact! boundary) until restart. `seon.schema/*schemas` is a private atom with
   no unregister today — a tiny floor add. (Highest-value finding; the catalog
   glosses it.)
2. **`:my.code/sym` is `[:or :symbol :keyword]`, not just `:symbol`.** fn/test
   identities are symbols (stored as strings); a schema key is a KEYWORD. The
   catalog's `:seon.code/sym :symbol` can't address a schema. Accept both;
   `(keyword sym)` for the schema lookup if a symbol is passed.
3. **Naming-rule violation in the catalog's own sketch:** `my.code`'s payload is
   `:seon.code/*` but should be `:my.code/*` (tool-specific). Only the threaded
   shapes (`:seon.error/*`, `:seon.result/ok?`) stay `seon.*`.
4. **Generalize, don't copy.** `unbind-result-var!` already does the fn/test undef
   for the `result` ns — generalize it to `undef-sym!`; do NOT author a second
   undef. Likewise fold the fn + schema core-origin queries into ONE
   `core-origin?` predicate keyed by identity-attr.
5. **Three stores stay in sync (code-as-data).** retract the datom AND undef the
   analyzer AND delete the globalThis prop (fn/test) / unregister (schema).
   Skipping any one leaves a ghost (catalog names two halves; this is the full
   three). A redefine already replaces all three — `forget!` is its inverse, so the
   invariant is symmetric.
6. **CLJS call sites re-read at call time** → a forgotten fn breaks callers LOUDLY
   (good), unlike JVM `ns-unmap` where closures keep the old Var silently. No extra
   work needed; just don't expect a quiet no-op.
7. **`undef-sym!` reads `@seon.repl/!compile-state`** — the SAME state the agent's
   evals mutate (consistent with `bind-result-var!`, which is handed compile-state
   from the eval pipeline). The wrapper passes it explicitly so the floor verb stays
   pure-ish.
8. **Undo: no `restore!` verb.** Returning `:my.code/prior-source` + the bitemporal
   store makes undo `(eval prior-source)` — one move. Add `restore!` only if forgets
   prove common AND error-prone in live drives (catalog agrees). Document the recipe
   in `db.examples`.

## Sources

- ns-unmap (single-symbol undefine, the granularity model) —
  <https://clojuredocs.org/clojure.core/ns-unmap>,
  <https://clojure.org/reference/namespaces> ("namespaces are mappings from simple
  symbols to Vars… other namespaces may still hold references to the un-mapped
  Vars").
- remove-ns (whole-ns, contrast) — <https://clojuredocs.org/clojure.core/remove-ns>.
- ClojureScript self-hosting + compiler state (`:cljs.analyzer/namespaces`, each ns
  a `:defs` map; `cljs.js/empty-state`) —
  <https://clojurescript.org/guides/self-hosting>,
  <https://github.com/clojure/clojurescript/wiki/Optional-Self-hosting>.
- `cljs.analyzer.api` — only `remove-ns` (whole-ns); no single-def removal —
  <https://cljs.github.io/api/compiler/cljs.analyzer.api/>.
- replumb `ast.cljs` `dissoc-*` helpers (requirer-side `:uses`/`:requires`/
  `:imports` removal, NOT def removal) —
  <https://github.com/arichiardi/replumb/blob/master/src/cljs/replumb/ast.cljs>.
- seon backing (read in-repo): `unbind-result-var!`/`lookup-ns-object`/
  `lookup-value`/`core-origin-fn-syms`/`reject-core-overrides`/
  `tee-registered-schema!` in `src/seon/eval.cljs`; `[:db.fn/retractEntity …]` +
  `history`/`as-of` in `src/seon/db.cljs`; `*schemas` + `register!` (no
  `unregister!`) in `src/seon/schema.cljc`; identity attrs in `src/seon/agent.cljs`,
  `src/seon/test/runner.cljs`; `!compile-state` in `src/seon/repl.cljs`.
- `docs/prds/agent-fsm/toolkit-catalog.md` — `my.code`/`forget!` spec + the four
  shared shapes (PATH/REF/ITEMS/RESULT).
