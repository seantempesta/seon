---
type: research
status: active
tags: [research, pod, cljs]
---

# Eval error envelope — why it nests deeply, and how to flatten

Triage notes for KI-3 in [[agent-repl-mvp-pre-2026-05-22#known-issues]].
Captured 2026-05-22 while implementing the fix.

## TL;DR

- The 4-deep nesting comes from `cljs.js/eval-str`. When a thrown
  exception escapes the compiled JS, cljs.js wraps it twice:
  inner layer `(ana/error aenv "ERROR" cause)`, outer layer
  `(ana/error aenv (str "Could not eval " form) cause)`. Source:
  `clojurescript/src/main/cljs/cljs/js.cljs:815-817` and `:841-847`.
- `seon.error/->map` then walks the chain via `ex-cause`, producing
  a `:seon.error/cause` recursion at each level. So an in-form
  `(throw (ex-info "boom" {:k 1}))` becomes:
  `{:message "Could not eval ..." :cause {:message "ERROR" :cause
   {:message "boom" :ex-data {:k 1}}}}` — three levels of indirection
  before the actionable data.
- The undeclared-var path the MVP agent's spec calls out
  (`:seon.eval/warning-type :undeclared-var`) actually only nests
  ONE level — `seon.eval/raw-eval` rejects with a raw `ex-info`
  before cljs.js's wrap path runs (see `eval.cljs:289-299` after
  the 2026-05-22 warning-handlers fix). So that specific KI-3
  example may already be flatter than the spec describes; the
  deep-nest case is for runtime throws inside eval'd forms.
- **Fix:** extend `seon.error/->map` to also produce a flattened
  `:seon.error/data` top-level key holding the deep-merged ex-data
  across the entire cause chain (deepest wins). The renderer reads
  one key; nothing has to walk `:seon.error/cause` to find the
  useful info.

## Source-grounded evidence

### cljs.js's wrap layers

`clojurescript/src/main/cljs/cljs/js.cljs:126-127`:

```clojure
(defn- wrap-error [ex]
  {:error ex})

```

The thrown-from-eval'd-code path (`:843-847`):

```clojure
(let [src (with-out-str (comp/emit ast))]
  (cb (try
        {:value ((:*eval-fn* bound-vars) {:source src})}
        (catch :default cause
          (wrap-error (ana/error aenv "ERROR" cause))))))

```

And the outer analyze-failure path (`:812-817`):

```clojure
res (try
      {:value (ana/analyze aenv form nil opts)}
      (catch :default cause
        (wrap-error
          (ana/error aenv
            (str "Could not eval " form) cause))))

```

`ana/error` is `(ex-info msg {} cause)` shape — it ALWAYS has a
`:cause` because the cause argument is passed to `ex-info`'s
3-arity. So each wrap is an ex-info whose ex-data is empty but
whose cause carries the prior level. By the time we catch from
`eval-str`'s callback, the rejection IS the wrapped exception.

### Our `error/->map` recursion

`seon.error/->map` (`src/seon/error.cljs:19-35`) calls `ex-cause`
at each level and recurses via `:seon.error/cause`, bounded to
depth 5. Each level's ex-data lives at `:seon.error/ex-data`.

So for a deep cause chain, the useful info (the original ex-info's
data) lives at the BOTTOM of the recursion, not the top. The
renderer needs to walk N levels of `:seon.error/cause` to find it.

### What `:seon.eval/warning-type` looks like today

After the warning-handlers fix at `eval.cljs:265-273` + the
explicit reject at `:289-299`, an undeclared-var REJECTS with a
fresh `ex-info` that has no cause. cljs.js's wrap-error path
isn't traversed — because our rejection is what propagates, not
cljs.js's `{:error <wrapped>}` callback shape.

So:

- Undeclared-var error → 1 level of nesting (just our ex-info).
- Runtime throw via `(throw (ex-info "x" {...}))` → 3 levels:
  outer "Could not eval ...", middle "ERROR", inner the user's
  ex-info.
- Runtime throw of a plain JS Error → similar 3 levels, with
  no ex-data at the bottom.

KI-3's spec example with 4 levels presumably came from before the
warning-handlers fix landed — when even undeclared-var crashed
through cljs.js's analyzer-error wrap.

## Fix design

### Generic flatten on `error/->map`

Walk the cause chain at conversion time, collect every `ex-data`,
deep-merge with deepest-wins semantics, store the result at a
new top-level key `:seon.error/data`. The renderer reads ONE key.
The existing `:seon.error/cause` recursion stays for callers that
care about the chain shape (rare).

```clojure
;; in seon.error
(defn- collect-ex-data-chain
  "Walk e and its ex-cause chain, returning [data-deepest-first ...].
   Bounded the same way ->map is — depth 5."
  [e]
  (loop [e e depth 0 acc ()]
    (if (or (nil? e) (>= depth 5))
      acc
      (recur (ex-cause e) (inc depth)
             (if (instance? cljs.core/ExceptionInfo e)
               (cons (ex-data e) acc)  ;; deepest goes first → wins on merge
               acc)))))

(defn ->map [...]
  ;; ... existing body ...
  (let [merged-data (apply merge {} (collect-ex-data-chain e))]
    (cond-> base
      ...
      (seq merged-data) (assoc :seon.error/data merged-data))))

```

### Why deepest-wins

The deepest ex-data is the user's original. Wraps add empty
ex-data above it. So deeper data should win in the merge. Empty
maps don't overwrite anything useful (merge skips no-op pairs
trivially when the value isn't present).

Edge case: if two non-empty ex-data maps share a key, deepest
wins — the user's intent takes precedence over cljs.js's wrap.
For seon-namespaced keys (`:seon.eval/warning-type`,
`:seon.error/kind`), only one layer ever sets them, so no
conflict in practice.

### What changes for callers

- `seon.eval/eval` returns `{:ok false :error <map>}` where the
  map gains a `:seon.error/data` key carrying the merged ex-data.
- Renderers (the recent-evals tile, the warnings tile per
  [[agent-repl-mvp-pre-2026-05-22#recent-evals-tile]]) read
  `(get-in r [:seon.error/data :seon.eval/warning-type])` instead
  of walking causes.
- Existing `:seon.error/ex-data` per-level keys stay — they're
  the same data, just per-level. Backwards-compatible.

## Open questions

- **`:seon.error/ex-data` vs `:seon.error/data`** — having both
  could confuse. Worth deciding whether to deprecate the per-level
  `:ex-data` now or wait for a renderer rewrite to confirm
  nothing depends on it.
- **Stack traces** — the per-level `:seon.error/stack` doesn't
  flatten naturally. Deepest stack is usually the most useful
  (the actual throw site). Worth considering whether to also
  promote `:seon.error/stack*` = deepest stack, top-level.

Both worth flagging in the PR, not blocking this fix.

## Reference

- KI-3 framing: [[agent-repl-mvp-pre-2026-05-22#known-issues]] §KI-3
- cljs.js wrap source: `~/src/clojurescript/src/main/cljs/cljs/js.cljs:126-127, :815-817, :841-847`
- Existing `->map`: `src/seon/error.cljs:19-35`
- Reject path that avoids the wraps:
  `src/seon/eval.cljs:289-299` (raw ex-info, no cljs.js wrap)
- ana/error definition: `~/src/clojurescript/src/main/clojure/cljs/analyzer.cljc` — `(ex-info msg {} cause)` shape
