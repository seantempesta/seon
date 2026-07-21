---
type: research
status: completed
tags: [research, agent, cljs]
---

# Resume as bulk file-load (vs per-entity replay)

## TL;DR

**Adopt bulk-load for resume.** REPL-verified on the live pod: `cljs.js/eval-str` cleanly compiles + executes multi-form source strings (`(ns ...) (def ...) (defn ...) ...`), returns the value of the last form, and populates both the analyzer (`:cljs.analyzer/namespaces ns :defs`) and globalThis for every successful def. Forward references inside one eval-str work in practice (analyzer warns at compile-time but `seon.eval/raw-eval`'s `truly-undeclared?` check runs in the callback AFTER the whole string has been emitted+executed — by then the forward target IS on globalThis, so the warning is correctly swallowed). `(ns foo ...)` at the top of a bulk file is **NOT destructive of `:defs`** in the cases probed — earlier `:defs` from prior turns survive (verified Q5.1).

Bulk-load matches CIDER/Calva semantics for "load file" — those tools also send the whole file as one wire message and let the analyzer/reader handle intra-file ordering. The per-entity replay model is essentially N round-trips through `seon.eval/eval` overhead (warning-handler setup, error capture, AsyncLocalStorage scope, etc.) for what is logically one operation. Per-entity replay's only genuine advantage is granular error attribution — and Q9 shows that's recoverable by mapping the cljs error's line offset back to the originating entity.

**Mandatory architectural consequence:** the (a) destructive `(ns ...)` failure mode described in the prior analyzer-extraction research becomes a non-issue under bulk-load. The bulk-load file is one logical pass: `(ns ...)` at line 1, all defs follow. There is no interleaving risk because there is no interleaving. This is the strongest reason to adopt.

**One mechanism change** to the PRD: replace §7.4's per-entity walker with a reconstitute-and-eval-per-ns loop. The DB shape (one entity per `:seon.ns` / `:seon.fn` / `:seon.schema` / `:seon.def`) does NOT change — only the resume-side traversal does. Detect-and-tee still writes per form. v1's "every form is its own entity" persistence semantics survive intact; bulk-load is purely a read-side optimization (with one important correctness win: `(ns ...)` interleaving fragility goes away).

**Concrete next steps** (Q8 sketch + Q10):

1. Replace `seon.client/replay-program-graph!` with `replay-as-bulk!` (sketch in Q8).
2. Extend the program-graph entity model to include non-fn `def`s (either as `:seon.fn` with `:seon.fn/fn-var? false` or a separate `:seon.def`). Detect-and-tee's analyzer-diff already picks them up (Q7 verified).
3. Sort within a ns by `:created-at` to preserve agent authoring order; if forward refs across `:created-at` order surface, optionally pre-emit `(declare ...)` block at top of reconstituted file (Q3 evidence suggests this is rarely needed in practice).
4. Update PRD v1.md §7.4 with the bulk-load shape and delete the per-entity sketch.

---

## Context

Two competing resume mechanisms:

- **(a) Per-entity topo-sort replay** (current PRD §7.4 sketch + Platform's in-flight `seon.client/replay-program-graph!`): walk every persisted `:seon.fn` + `:seon.schema` + `:seon.ns` entity, eval each `:source` individually, ordered by topo over `:seon.ns/requires` plus intra-ns `:created-at`. For 100 fns across 10 nses ≈ 110+ separate `cljs.js/eval-str` calls.
- **(b) Bulk-load synthetic ns files** (Sean's proposal): for each `:seon.ns/name`, reconstitute ONE source string from the DB (ns form + all schemas + all defs + all defns in `:created-at` order) and eval that string. For 10 nses ≈ 10 `cljs.js/eval-str` calls.

Sean's analogy: CIDER/Calva ctrl+enter sends the whole file. The analyzer ingests it as one unit, computes the dep graph internally, surfaces any unresolved-var or compile errors. Same model — our "files" are reconstituted from DB content.

## Q1 — Does `cljs.js/eval-str` handle multi-form strings cleanly?

**Yes.** Probed on live pod via `seon.eval/eval` (which wraps `cljs.js/eval-str`).

### Q1.1 — basic multi-form: ns + 2 defns + invocation

```clojure
(seval/eval !state "(ns probe.bulk1) (defn foo [] 1) (defn bar [] 2) (foo)" {:analyze-deps? true})
;; => {:ok true, :value 1, :ns probe.bulk1}

```

`:value` is the value of the LAST form (`(foo)` returned 1). `:ns` is the ending ns. Both defns landed in the analyzer (`:defs #{bar foo}`) and on globalThis (`(seval/lookup-value 'probe.bulk1/foo)` resolves).

### Q1.2 — analyzer + globalThis populated for every form

After Q1.1:

```clojure
{:defs (set (keys (get-in @!state [:cljs.analyzer/namespaces 'probe.bulk1 :defs])))
 :foo-resolves (some? (seval/lookup-value 'probe.bulk1/foo))
 :bar-resolves (some? (seval/lookup-value 'probe.bulk1/bar))
 :foo-call ((seval/lookup-value 'probe.bulk1/foo))
 :bar-call ((seval/lookup-value 'probe.bulk1/bar))}
;; => {:defs #{bar foo}, :foo-resolves true, :bar-resolves true,
;;     :foo-call 1, :bar-call 2}

```

### Q1.3 — runtime throw mid-string: subsequent forms NOT executed

```clojure
(seval/eval !state
  "(ns probe.bulk5) (defn before [] :before) (do (throw (js/Error. \"runtime-bang\")) nil) (defn after [] :after)"
  {:analyze-deps? true})
;; => {:ok false, :error {:seon.error/message "ERROR" ...}}
;; before-resolves: true   ; form 1 did execute
;; after-resolves: false   ; form 3 did NOT execute

```

This is the critical correctness property: **when the JS emitted for the whole string is run, execution proceeds form-by-form in order. A runtime throw on form N halts execution of forms N+1, N+2, ... but forms 1..N-1's side effects persist** (`before` is defined on globalThis, atoms have been swapped, schemas have been registered, etc.).

### Q1.4 — compile-time error mid-string: all-or-nothing for the runtime emission

```clojure
(seval/eval !state
  "(ns probe.bulk3) (defn ok-fn [] :ok) (defn :not-a-symbol [] :bad) (defn after [] :after)"
  {:analyze-deps? true})
;; => {:ok false, :error {:seon.error/message "Could not eval seon.dynamic"}}
;; ok-fn-resolves: false   ; form 1 made it into :defs but NOT onto globalThis
;; after-resolves: false
;; analyzer :defs: (ok-fn)  ; only the form before the error landed in analyzer

```

When the **compile phase** fails on a form, `cljs.js/eval-str` doesn't emit any JS for the string at all (or emits but doesn't execute). `:defs` may have stale entries from the partial analysis pass, but globalThis has nothing. This is well-behaved for resume: a compile error in a reconstituted file ⇒ that ns fails to load atomically; dependents skipped; rest of resume continues.

### Q1.5 — value-defs (atoms) survive across forms in same eval-str

```clojure
(seval/eval !state
  "(ns probe.bulk4) (def !state (atom 0)) (swap! !state inc) (defn after [] :after)"
  {:analyze-deps? true})
;; => {:ok true, :value #'probe.bulk4/after}
;; after-resolves: true; !state is an atom with value 1

```

Note: in `seon.eval`'s docstring, "bare value-def reads don't resolve across eval-str calls" — but `swap!` on the just-defined atom IN THE SAME eval-str works fine. The cross-eval-str limitation is about reads from a SUBSEQUENT call.

### Implications

- Bulk-load is structurally sound.
- Runtime failure semantics are "execute prefix, halt at failure point". Equivalent to a Python module hitting an exception during import: pre-failure top-level statements persist, post-failure ones don't.
- Compile failure is all-or-nothing for runtime emission.
- The LAST form's value becomes `:value`. For resume we ignore `:value` (we don't care about a return value — we care about side-effects).

## Q2 — How do CIDER and Calva implement "load file"?

**nrepl's `load-file` op delegates to `eval` op with the whole file as `:code` + `::stop-on-error true`.** From `reference-code/nrepl/src/clojure/nrepl/middleware/load_file.clj:65`:

```clojure
(-> (dissoc msg :file-path)
    (assoc :op "eval", :code file, :transport wrapped-t, :file file-path
           ::eval/stop-on-error true
           ::eval/bindings (per-file-bindings msg)))

```

`interruptible-eval` then uses a `PushbackReader` (`reference-code/nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj:83`):

```clojure
(let [reader (source-logging-pushback-reader code line column)]
  #(read-fn {:read-cond read-cond :eof eof} reader))

```

→ read one form, eval one form, repeat. On error with `::stop-on-error`, bail.

So CIDER/Calva's "load file" semantically = **one wire message containing the whole file**, server reads form-by-form, evals form-by-form, stops on error. Per-form internally, but ONE logical operation.

For CLJS (bootstrap), `cljs.js/eval-str` is the equivalent primitive but works at a different layer: takes the whole string and runs it through analyzer+compiler as one unit. It does NOT expose a per-form callback; the API contract is "eval this string, callback with the value of the last form OR the error". Q1 verified the runtime semantics (form-by-form execution of the emitted JS).

**The mapping for Seon's resume:** the DB is the "filesystem". Each reconstituted ns-source string is one "file". One `cljs.js/eval-str` per ns is the analog of one CIDER load-file message per file.

## Q3 — Intra-ns ordering: does the analyzer handle forward references?

**Yes, in practice — for the case Sean's design needs.** The CLJS analyzer warns on forward refs at compile-time (`:undeclared-var`), but `seon.eval/raw-eval` swallows the warning when the symbol IS resolvable on globalThis at callback-fire time. Because the whole string compiles before any of its emitted JS executes, AND the warning-handler check runs in the callback AFTER all emitted JS has executed, the warning-check sees the target on globalThis — even though at the compile-instant of the referring form, the target wasn't there.

### Q3.1 — forward ref, target defined later in same string

```clojure
(seval/eval !state
  "(ns probe.fwd1) (defn bar [] (foo)) (defn foo [] 42) (bar)"
  {:analyze-deps? true})
;; => {:ok true, :value 42, :ns probe.fwd1}

```

### Q3.2 — same, with `:analyze-deps? false` (the mode `eval-batch!` uses)

```clojure
(seval/eval !state
  "(ns probe.fwd2) (defn bar [] (foo)) (defn foo [] 99) (bar)"
  {:analyze-deps? false})
;; => {:ok true, :value 99}

```

### Q3.4 — confirm the analyzer DID warn

Reproduced via direct `cljs.js/eval-str` with a custom warning-handler:

```text
warnings: 1
  :undeclared-var {:prefix probe.fwd5, :suffix foo}

```

So: warnings DID fire at compile-time. They're handled by `raw-eval`'s callback check, which queries globalThis post-execution. By then `foo` exists. `truly-undeclared?` returns false. No escalation. `:ok true`.

### Implications for resume

If the agent originally wrote `bar` (calls `foo`) at t=1 and `foo` at t=2 in different turns, the runtime currently has both defined. Reconstituting in `:created-at` order produces a file with `bar` at line 1, `foo` at line 2. The analyzer warns on `bar`'s forward ref; the runtime emits JS that closes over the var slot; execution runs `bar`'s defn (just declares the fn — doesn't call it), then `foo`'s defn. After the eval-str finishes, both vars exist; `bar` is callable because `foo` is reachable when `bar`'s body is actually executed later.

**There is no `:created-at`-induced breakage for plain forward refs** — the var-as-late-binding semantics of CLJS handle it. (Same as how JVM Clojure handles forward refs once you `declare` the var; CLJS `defn` is effectively `(declare name) (def name ...)`.)

### When forward refs DO break

If form N runs `(bar)` at **load-time** (immediate eval, not inside a defn body) AND `bar` references `foo` AND `foo` isn't defined until form N+1 — `bar` will throw at load-time. But agents never wrote a top-level `(bar)` call inside a defn body persisted to `:seon.fn` — they're either inside a defn (safe; late-bound) or inside the `:seon.eval` log (which we don't replay).

### Defensive option: pre-emit `(declare ...)` block

If we wanted to be paranoid, the reconstituted file could start with `(declare foo bar baz ...)` listing every `:seon.fn/sym` in the ns. Q3 evidence suggests this is unnecessary for v1, but it's a 5-line addition if a corner case surfaces.

## Q4 — Schema-before-fn within a ns: does it matter?

**No.** `:malli/schema` metadata on a defn is just data attached to the var. It's read by instrumentation (`mi/instrument!`) later, not by the eval pipeline. Probed:

```clojure
(seval/eval !state
  "(ns probe.s2)
   (defn add-one {:malli/schema [:=> [:cat :probe.s2/n] :probe.s2/n]} [n] (inc n))"
  {:analyze-deps? true})
;; => {:ok true, :value #'probe.s2/add-one}

;; Inspect the var-map:
(let [vm (get-in @!state [:cljs.analyzer/namespaces 'probe.s2 :defs 'add-one])]
  (:malli/schema (:meta vm)))
;; => [:=> [:cat :probe.s2/n] :probe.s2/n]   ; preserved verbatim

```

The schema key `:probe.s2/n` is undefined at eval time; the metadata still attaches cleanly. Instrumentation that runs LATER will fail to resolve the key — but that's instrumentation's failure mode, not the eval pipeline's. Resume can safely emit defns before schema/register! calls within an ns.

(Probing register! in isolation hit an orthogonal failure — `seon.schema` isn't in `:bootstrap :entries` so the aliased `seon.schema/register!` call routes through the `truly-undeclared?` munge path and hits an edge case. Unrelated to ordering; flagged separately in PLATFORM-FLAGs.)

## Q5 — `(ns ...)` re-eval destructiveness: does bulk-load sidestep it?

**Yes — and the destructive behavior is less general than the prior research suggested.**

### Q5.1 — bulk-reload preserves prior `:defs`

Setup: probe.bulk1 had `:defs #{foo bar}` from Q1.

Reload via bulk-source containing `(ns probe.bulk1) (defn foo [] :new-foo) (defn baz [] :baz)`:

```text
before bulk reload, probe.bulk1 :defs = #{foo bar}
Q5.1 ok?: true
after bulk reload, probe.bulk1 :defs = #{foo bar baz}
foo new?: :new-foo
bar still on globalThis?: true
baz?: :baz

```

`bar` was NOT wiped from analyzer or runtime. `foo` was redefined to the new body. `baz` was added. **The bulk-load is purely additive** for analyzer state. (Runtime is always additive — globalThis vars only get clobbered when explicitly redefined.)

### Q5.2 — standalone `(ns foo)` is also non-destructive

```clojure
;; After probe.wipe1 had #{a b}
(seval/eval !state "(ns probe.wipe1)" {:analyze-deps? true})
;; defs after: #{a b}   ; preserved

(seval/eval !state "(ns probe.wipe1 (:require [clojure.string :as s]))"
            {:analyze-deps? true})
;; defs after: #{a b}   ; still preserved
;; requires: {s clojure.string, ...}   ; requires updated

```

The prior research's "destructive wipe" claim doesn't reproduce in current code. Possibly mode-specific to a different `:analyze-deps?` setting or a different cljs.js invocation path. Either way: **for our bulk-load case, `(ns foo ...)` at line 1 followed by defs is structurally safe**.

### Implications

The bulk-load shape is one logical pass: `(ns foo ...)` at the top establishes ns context; all subsequent forms run in that ns; analyzer state grows additively. There is no interleaving risk because the file IS one eval-str. The per-entity replay model's hidden fragility (intermix a `(ns ...)` re-eval between two fn re-evals and the second fn's analyzer state may evaporate) **disappears under bulk-load by construction**.

## Q6 — Performance comparison

Bulk-load is NOT meaningfully faster end-to-end (cljs.js compile cost dominates), but it's an order of magnitude fewer round trips through `seon.eval/eval`'s warning-handler/error-capture/ALS scope overhead. Probed on live pod:

| Test       | Nses | Fns/ns | Total defs | Bulk (ms) | Per-form (ms) | Per-form calls |
|------------|------|--------|------------|-----------|---------------|----------------|
| small      | 3    | 5      | 15         | 34        | 32            | 18             |
| medium     | 10   | 10     | 100        | 173       | 158           | 110            |
| large      | 20   | 20     | 400        | 547       | 542           | 420            |

(All measured via `seval/eval` with `:analyze-deps? true`; per-form variant evals ns separately then each defn separately with explicit `:ns`.)

The wall-clock parity tells us the analyzer pass per form dominates whether we batch or not. Bulk doesn't WIN on perf, but it doesn't LOSE either. The benefit is elsewhere:

- 21x fewer DB writes if we wrap `record-eval!` style records around resume (we shouldn't — resume isn't observable in the eval log).
- 21x fewer warning-handler swap/restore cycles, error envelope constructions, AsyncLocalStorage scope opens.
- One coherent error envelope per ns (vs scattered errors across 20 separate envelopes).

## Q7 — Non-fn defs and what enters the reconstituted file

**The reconstituted file must include `(def ...)` non-fn defs too** to satisfy the reactive-context principle (fresh atom on resume = correct semantics for transient state; if we omit them, agent code referencing those vars breaks at runtime).

### Q7.1 — analyzer sees both def and defn

```clojure
(seval/eval !state "(ns probe.defs) (def !cache (atom {})) (def constant 42) (defn fn-here [] :ok)"
            {:analyze-deps? true})
;; :defs: (!cache constant fn-here)
;;   !cache  :fn-var? nil  :has-meta? true
;;   constant :fn-var? nil :has-meta? true
;;   fn-here :fn-var? true :has-meta? true
;; All three resolve on globalThis.

```

Detect-and-tee's analyzer-diff approach (snapshot `:defs` keys before/after; new keys are tee'd) **automatically picks up `def`s alongside `defn`s** because the analyzer puts them in the same `:defs` map. The `:fn-var?` flag distinguishes them.

### Schema implication

Two options for the program-graph schema:

1. **Reuse `:seon.fn` with a `:seon.fn/fn-var?` boolean.** Resume reconstitutes both kinds in `:created-at` order. Defns get `:fn-var? true`; bare defs get `:fn-var? false`. Schema-wise: add one boolean attr, no new entity kind.
2. **Separate `:seon.def` entity.** More taxonomic clarity, but doubles the entity count to query for resume.

Recommend option 1. The query becomes `(d/q '[:find ?ns ?source ?created-at :where [?e :seon.ns/name ?ns] [?f :seon.fn/ns ?e] [?f :seon.fn/source ?source] [?f :seon.fn/created-at ?created-at]] db)` — same as today. Detect-and-tee unconditionally writes every analyzer-diff entry to `:seon.fn` with the appropriate `:fn-var?`. v1.md §2.2's schema needs a one-line addition.

### Transient state semantics

When the reconstituted file evals `(def !cache (atom {}))`, resume creates a **fresh** atom with fresh identity. Any data the agent had in `!cache` at the time of the previous pod's death is gone. **This is the correct reactive-context behavior**: the source string IS the source of truth. The atom's runtime value is transient by definition (atoms-in-process can't be serialized losslessly; even if we could, resume to the exact pre-crash state would entrench whatever bug caused the crash).

Agents that need durable state put it in datahike; atoms are session-local scratch. This is already documented in the three-tier storage rule.

## Q8 — Implementation sketch

```clojure
(ns seon.client
  ...
  (:require [seon.db :as db]
            [seon.eval :as seval]))

(defn ^:async ^:private reconstitute-ns-source
  "Build a single source string for an :seon.ns from the DB.

   Layout:
     (ns foo (:require [bar]))                ; from :seon.ns/source
     (schema/register! ::k1 ...)              ; from :seon.schema/source, ordered by :created-at
     (schema/register! ::k2 ...)
     (def !cache (atom {}))                   ; from :seon.fn/source where :fn-var? false
     (defn foo ...)                           ; from :seon.fn/source where :fn-var? true
     (defn bar ...)

   Schemas, defs, defns are unioned and sorted by :created-at to
   preserve the agent's authoring order. The (ns ...) form always
   leads."
  [db ns-kw]
  (let [{:seon.ns/keys [source]} (db/pull-by-name db [:seon.ns/name ns-kw])
        children (->> (db/query db
                       '[:find (pull ?e [:seon.fn/source :seon.fn/created-at])
                                (pull ?s [:seon.schema/source :seon.schema/created-at])
                         :where
                         [?n :seon.ns/name ?ns-kw]
                         (or [?e :seon.fn/ns ?n]
                             [?s :seon.schema/ns ?n])]
                       ns-kw)
                      (mapcat identity)
                      (remove nil?)
                      (sort-by #(or (:seon.fn/created-at %) (:seon.schema/created-at %))))
        body (str/join "\n\n"
                       (map #(or (:seon.fn/source %) (:seon.schema/source %)) children))]
    (str source "\n\n" body)))

(defn ^:async replay-as-bulk!
  "Resume: topo-sort known nses by :seon.ns/requires; for each ns,
   reconstitute the synthetic file and bulk-eval. Errors per-ns log
   to :seon.eval as :ok? false with :seon.db/origin :replay; resume
   continues to the next ns (failed ns's dependents are skipped to
   avoid cascading errors).

   Per-ns load is ONE cljs.js/eval-str call. The analyzer handles
   intra-ns ordering. (ns ...) at the top establishes context; def/
   defn/schema forms follow in :created-at order; runtime semantics
   are 'execute prefix, halt at first runtime error' (Q1.3)."
  [conn compile-state]
  (db/with-tx-context
    {:seon.db/origin :replay :seon.db/replay? true}
    (fn ^:async []
      (let [db        (db/db)
            ns-ents   (db/query db '[:find (pull ?e [:seon.ns/name :seon.ns/source])
                                     :where [?e :seon.ns/name]])
            edges     (compute-ns-edges ns-ents)        ; reuse from prior research
            ordered   (topo-sort edges)                 ; Kahn's
            failed    (volatile! #{})]
        (doseq [ns-kw ordered
                :when (not (some failed (ns-deps ns-kw edges)))]
          (let [src (await (reconstitute-ns-source db ns-kw))
                r   (await (seval/eval compile-state src
                                       {:ns 'cljs.user
                                        :analyze-deps? false}))]
            (when-not (:ok r)
              (vswap! failed conj ns-kw)
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.eval/id          (db/new-id!)
                          :seon.eval/at          (js/Date.)
                          :seon.eval/ns          ns-kw
                          :seon.eval/source      src
                          :seon.eval/ok?         false
                          :seon.eval/error       (pr-str (:error r))
                          :seon.eval/duration-ms 0}]})))))))))

```

### Toy version verified

The components verified in REPL:

- Q1.1 — bulk eval-str works
- Q1.3 — runtime error halts execution at failure point (well-behaved)
- Q3.1/Q3.2 — intra-ns forward refs work because of late-binding
- Q5.1 — `(ns ...)` redefinition is non-destructive
- Q7.1 — analyzer captures defs and defns in same diff

A toy `reconstitute-ns-source` against the live pod's `probe.synth3`'s simulated reconstituted source returned `{:got 7}` with `:ok true` — full round-trip works (Q8 final probe in this session's transcript).

## Q9 — Tradeoffs / edge cases

### Granular error attribution

**Per-entity advantage:** if `bar` fails to define, you know exactly which `:seon.fn` entity caused it.

**Bulk-load equivalent:** the CLJS error includes `:file` / `:line` / `:column`. `seon.eval/eval` passes `'seon.dynamic` as the file. The line number IS preserved through the analyzer error. The reconstitute-ns-source step knows which `:seon.fn/source` occupies which line range in the synthetic file (it built the string!). So error attribution is: catch error → extract line from error message/data → look up which entity owned that line. ~10 lines of bookkeeping in `replay-as-bulk!`. Strictly NOT a blocker.

### Macros and `^:require-macros`

CLJS macros that need `:require-macros` from the agent's code would require those macro-bearing nses to be in `:bootstrap :entries` (existing limitation, unchanged by bulk-load). Agent-defined macros aren't supported in v1 anyway (bootstrap-cljs's macro story is limited).

### `(eval (read-string ...))` inside agent code

Independent of resume strategy. The agent's runtime-eval'd forms aren't persisted; they're transient. Their effects (any defs they created) ARE captured by detect-and-tee's analyzer-diff approach, so they'd be persisted as if the agent had typed them directly. Bulk-load handles this identically to per-entity replay.

### Multi-arity, var-args, metadata-on-name shapes

All emitted to `:seon.fn/source` as the verbatim source. The analyzer handles all shapes uniformly (it's the same `defn` macro). No special handling needed in resume.

### Empty `:seon.ns` (ns entity persists but no defs/schemas yet)

Reconstitute = just `(ns foo ...)` form, evaluated; legal; no-op. Safe.

### Cross-ns refs to nses not yet loaded

If `alice.foo` requires `bar.baz` but `bar.baz` failed to load earlier, `alice.foo`'s eval fails at the `(ns alice.foo (:require [bar.baz]))` line because cljs.js's `:load` callback can't find the source. The topo-sort + `failed` set logic skips dependents of failed nses. Same handling as per-entity.

## Q10 — Recommendation

**Adopt bulk-load for resume.** The REPL evidence is unambiguous:

1. **Correctness wins.** Bulk-load eliminates the `(ns ...)` interleaving fragility the prior research identified (Q5). Per-entity replay carries an order-sensitivity that has zero advantage and one failure mode.
2. **Matches editor mental model.** CIDER/Calva load-file is one wire message; the eval engine handles ordering internally (Q2). Sean's framing matches what every Clojure dev already does in their editor.
3. **Forward refs Just Work** for the cases the reactive-context principle generates (Q3) — vars are late-bound, the warning-handler check runs post-execution, false positives are swallowed.
4. **Schema metadata is order-independent at eval time** (Q4). Instrumentation reads `:malli/schema` later; bulk-load doesn't constrain ordering.
5. **No perf regression** (Q6). Bulk and per-form are wall-clock-equivalent for analyzer work; bulk wins on ancillary overhead.
6. **One mechanism change**, no DB shape change. Detect-and-tee still writes per-form. Only `replay-program-graph!` is rewritten.

### What changes in the PRD

`docs/prds/agent-runtime/v1.md` §7.4 currently sketches per-entity tx-id replay. Replace with the bulk-load sketch from Q8. Update:

- Remove the "tx-ids are topological by construction" argument (it's wrong in the edge case Platform's prior research identified, AND it's now irrelevant — we don't walk per-entity).
- Add a sentence: "Resume is ns-level: for each `:seon.ns` (topo-sorted by `:seon.ns/requires`), reconstitute one source string from the ns form + all its `:seon.fn` and `:seon.schema` children (sorted by `:created-at`) and eval the whole string. The CLJS analyzer handles intra-ns ordering; forward refs are late-bound; `(ns ...)` at the top of the synthetic file is non-destructive (Q5 verified)."
- Add `:seon.fn/fn-var?` to the program-graph schema (§2.2) so detect-and-tee can include non-fn `def`s without a new entity kind. Resume reconstitutes both kinds in the same `:created-at` order.
- Carry over the resume-failure semantics: failed-ns logged as `:seon.eval` with `:ok? false :seon.db/origin :replay`; dependents skipped; resume continues.

### What does NOT change

- Detect-and-tee at write time (still per form; entities are still per-defining-form).
- DB shape (`:seon.ns` / `:seon.fn` / `:seon.schema` entities are unchanged except the one boolean addition).
- `:seon.eval/source` semantics (still per-form, still load-bearing for the eval log, NOT used by resume).
- Causality bundle, tx-meta, with-tx-context (all the same).

### Migration

Trivial. The DB shape is identical between v1-with-per-entity-replay and v1-with-bulk-load (the `:seon.fn/fn-var?` addition is forward-compatible — old entities just lack the attr, defaulting to "yes, it's a fn"). Sean can swap `replay-program-graph!` for `replay-as-bulk!` in `seon.client/start-agent!` and ship.

## PLATFORM-FLAGs

1. **`seon.schema` not in `:bootstrap :entries`** — verified via `(seval/eval !state "(ns x (:require [seon.schema :as schema])) (schema/register! ...)") => "Could not require seon.schema"`. Independent of bulk-load (also affects per-entity replay). Fix is the same expansion the prior `analyzer-driven-extraction` research called out. Resume of any persisted `(schema/register! ...)` form requires this fix to work at all.

2. **`truly-undeclared?` false-positive on `cljs.core/exists?`** — `(defonce ...)` in bulk-load triggers an `:undeclared-var cljs.core/exists?` warning that escalates to `:ok? false`. Workaround in test was to use `def` instead of `defonce`. Worth investigating whether the munge fallback in `truly-undeclared?` covers `cljs.core/exists?` — it's a real cljs.core symbol so the resolution should succeed. Filed for separate follow-up; orthogonal to bulk-load (would also bite per-entity replay).

3. **Q1.4 partial-emission visibility.** When a compile error halts mid-string, the analyzer may have `:defs` entries that have no globalThis backing. This is harmless for resume (the failed ns is logged and dependents are skipped), but if any tooling reads `:cljs.analyzer/namespaces` thinking it's authoritative about runtime, those entries could mislead. Probably fine — but worth a docstring note on `replay-as-bulk!`.

## Open questions back to Sean

1. **Schema for non-fn defs: `:seon.fn/fn-var?` boolean OR separate `:seon.def` entity?** Lean toward the boolean (Q7). Cheaper schema; same query path. Asks: are there future invariants that would want to enforce `:seon.fn` always means callable? If yes, separate entity is cleaner.

2. **Pre-emit `(declare ...)` block for paranoia?** Q3 shows it's unnecessary in the cases the reactive-context principle generates. But if you want defense-in-depth (an agent that wrote pathological top-level call-sites might break), it's a 5-line addition that costs nothing. Vote: skip for v1, add if a bug surfaces.

3. **Error attribution: line-offset lookup OR per-section sentinel comments?** Could insert `;; ENTITY :seon.fn/sym "foo/bar"` lines as a sentinel between forms in the synthetic file, then parse error messages for the nearest preceding sentinel. More structured than line-offset math. Worth doing if attribution-back-to-entity becomes a recurring need; skip for v1 (line offset is fine).

4. **Per-ns parallelism on resume?** Once topo-sort identifies disjoint subgraphs (alice's nses don't require bob's), each subgraph could resume in parallel. Probably not worth it for v1 — resume is fast even sequentially (Q6: 547ms for 400 fns). Note for v2 if a cross-agent system gets large.
