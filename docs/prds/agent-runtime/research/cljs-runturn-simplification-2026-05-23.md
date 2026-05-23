---
type: research
status: completed
tags: [research, cljs, agent, async]
---

# CLJS `run-turn!` simplification — 2026-05-23

Deep-dive on the friction Sean hit composing `(db/with-tx-context …
(fn [] …))` with `await`-using bodies, plus an audit of whether
`run-turn!` is too big. All claims REPL-verified against the live pod
(`pid=42687`, `feature/agent-runtime` @ `abc236c` + uncommitted work
on `agent.cljs`). CLJS source is at `/Users/sean/src/clojurescript/`;
datahike at `/Users/sean/src/seon/reference-code/datahike/`.

## TL;DR

- **Only two forms make CLJS `await` legal: `(defn ^:async name [...]
  …)` and `(fn ^:async name [...] …)`.** Both put `^:async` on the
  **name symbol**. `(fn ^:async [] …)` (metadata on the arglist
  vector) and `^:async (fn [] …)` (metadata on the outer form) BOTH
  fail at compile time. This is by deliberate design — see analyzer
  `analyzer.cljc:2317–2322`, including a `#_`-commented note from
  Michiel Borkent that metadata-on-form was disabled because it
  produces a MetaFn that hurts interop.
- **`with-tx-context` does NOT need its thunk to be `^:async`.** It
  calls `(.run als-instance merged f)`. Whatever `f` returns flows
  back — including a Promise. AsyncLocalStorage propagates context
  across `await`s inside the Promise chain. **REPL-verified**: the
  context is visible both before and after an `await` boundary inside
  the inner `defn ^:async` helper, with the wrapping thunk just `(fn
  [] (my-async-helper))` (no metadata).
- **The reference pattern lives in `src/seon/eval.cljs:660–661`** —
  `eval-batch!` uses `(db/with-tx-context tx-context (fn ^:async
  run-one-entry! [] …))`. Note the **named** inline async fn: `fn
  ^:async run-one-entry!`. That's the one inline form that works,
  but the cleanest pattern is **don't go inline at all** — extract
  named `defn ^:async` helpers and pass a plain `(fn [] (helper …))`
  to `with-tx-context`.
- **`run-turn!` IS too big.** 7 sequential steps with 5 separate
  `await (db/transact! …)` calls + an LLM call + an eval batch, all
  in one let-block. Target shape: split into ~4 small `defn ^:async`
  helpers (`open-turn!`, `record-assistant-message!`,
  `close-turn!`), have a top-level body under 30 lines that just
  threads `agent-id`/`session-id`/`turn-id` through them inside one
  `with-tx-context` scope.
- **The four `:seon.db/opts {:tx-meta {…}}` blocks that v1.md §6.1
  explicitly emits per step are dead weight today.** Auto-merge from
  `with-tx-context` already does this (`db.cljs:677–688` →
  `db.cljs:735`). Wrap once, drop the per-call `:tx-meta` map. Five
  `:seon.db/opts` literals collapse to one outer `with-tx-context`.
- **Datahike-cljs `:db.fn/call` works.** Verified end-to-end. Tx-fns
  run inside the writer and produce tx-data from db state. Body is
  SYNC (no `await` inside), but multiple read-then-write operations
  can fold into one tx. Useful for the "compute next turn-index from
  current turn count" pattern.
- **Anti-pattern 1:** trying to put `^:async` on inline anonymous
  fns. It will not work. Either name the fn (and put metadata on the
  name) or — much better — pull the body out into a defn.
- **Anti-pattern 2:** carrying explicit `:seon.db/opts {:tx-meta
  {…}}` on every transact when a `with-tx-context` scope is open.
  Redundant; the merge happens at `db.cljs:735`.
- **Anti-pattern 3:** doing `(:seon.session/id (current-session id))`
  on every step. Pull once at the top of the let, thread the id.
  v1.md spec sketch did this; current `run-turn!` does it once on
  line 508 then re-derives at line 519. Pull both `session-id` and
  `turn-idx` into the outer `let` shape.

## Q1 — `^:async` on inline fns in shadow-cljs

### Source: `await` macro and the `fn*` analyzer

`/Users/sean/src/clojurescript/src/main/clojure/cljs/core.cljc:975–977`:

```clojure
(core/defmacro await [expr]
  (core/assert (:async &env) "await can only be used in async contexts")
  (core/list 'js* "(await ~{})" expr))
```

`(:async &env)` is set ONLY by the `fn*` analyzer. From
`/Users/sean/src/clojurescript/src/main/clojure/cljs/analyzer.cljc:2317–2322`:

```clojure
async (or
       ;; NOTE: adding async on fn form turns it into a MetaFn which isn't great for interop, let's discourage it - Michiel Borkent
       #_(:async (meta form))
       (:async (meta name))
       (:async (meta (first form))))
env (assoc env :async async)
```

Three potential sources, **only two live**:

1. `(:async (meta form))` — the metadata on the whole `(fn …)` form (i.e. `^:async (fn […] …)`). **`#_`-commented out** with the rationale that it produces a MetaFn that breaks interop.
2. `(:async (meta name))` — the metadata on the fn's **name symbol** (i.e. `(fn ^:async name [] …)` or via `defn`). ✅
3. `(:async (meta (first form)))` — the metadata on the `fn`/`fn*` symbol itself. In practice you'd write `(^:async fn [] …)` to set this — but the reader attaches `^:async` to whatever comes NEXT, which is the form, not the symbol. So this path is effectively never reached from user code; it's an internal escape hatch.

### REPL transcript (live pod `default` session, 2026-05-23)

**Probe 1 — `(fn ^:async [] …)`** (metadata on arglist):

```
(let [f (fn ^:async [] (await (.resolve js/Promise 42)))]
  (f))
;; ✗ COMPILE FAIL
;; AssertionError: Assert failed: await can only be used in async contexts
;; (:async &env)
;;   cljs.core/await (core.cljc:976)
```

**Probe 2 — `(fn ^:async my-name [] …)`** (named inline async fn):

```
(let [f (fn ^:async my-async-fn [] (await (.resolve js/Promise 42)))]
  (f))
;; ✓ => #object [Promise [object Promise]]
```

**Probe 3 — `^:async (fn [] …)`** (metadata on form):

```
(let [f ^:async (fn [] (await (.resolve js/Promise 42)))]
  (f))
;; ✗ COMPILE FAIL — identical error to Probe 1
```

**Probe 4 — `defn ^:async name`**:

```
(defn ^:async my-async-helper [] (await (.resolve js/Promise 42)))
(my-async-helper)
;; ✓ => #object [Promise [object Promise]]
```

### Verdict

Two viable shapes for an async fn in CLJS:

| Form | Works | Why |
|---|---|---|
| `(defn ^:async name [...] …)` | ✓ | `^:async` lands on `name` symbol; defn → fn → fn*; analyzer reads `(:async (meta name))` |
| `(fn ^:async name [...] …)` | ✓ | Same path, inline |
| `(fn ^:async [...] …)` | ✗ | `^:async` lands on the **arglist vector**, not on a name (there is none); analyzer never sees it |
| `^:async (fn [...] …)` | ✗ | Metadata on the form is deliberately disabled (`#_`-commented in analyzer.cljc:2319) |
| `^:async (fn name [...] …)` | ✗ | Same — outer-form metadata disabled |

**The one inline shape that works is the named `fn`.** That's exactly
what `eval.cljs:661` does: `(fn ^:async run-one-entry! [] …)`. Sean's
intuition to name the inline fn would have unblocked him; the missed
detail was that the `^:async` must precede a name, not the arglist.

## Q2 — `with-tx-context` f-arg contract

### Source: `db.cljs:409–437`

```clojure
(defn with-tx-context
  "Establish a tx-context for the dynamic extent of `f` (a 0-arg fn).
   …
   Returns whatever `f` returns — including a Promise, in which case
   the context propagates across `await` points inside `f` and any
   `^:async` fn `f` calls."
  [ctx-map f]
  (let [current (current-tx-context)
        merged  (merge current ctx-map)]
    (.run als-instance merged f)))
```

`f` is a **plain 0-arg fn**. `.run` invokes it once, captures its
return value, and returns it. The whole point of Node's
`AsyncLocalStorage` is that the context survives across the
microtask queue: any `await` resumes inside the same async-hook
scope, and the store is still visible. `f` therefore does NOT need
to be `^:async`. It just needs to **return a Promise** (or any
value) — and any awaiting downstream observes the context.

### REPL transcript (verified)

**Probe 5 — context survives across await in helper called from plain thunk**:

```clojure
(ns probe-tx (:require [seon.db :as db]))

(defn ^:async my-async-body []
  (let [ctx (db/current-tx-context)]
    (await (.resolve js/Promise 0))   ;; cross an await
    {:after-await-ctx (db/current-tx-context)
     :before-await-ctx ctx}))

(defn ^:async run-it []
  (await (db/with-tx-context {:seon.db/agent-id "probe-agent"
                              :seon.db/origin :system}
           (fn [] (my-async-body)))))      ;; <-- plain (fn []) NO ^:async

(.then (run-it) #(js/console.log "PROBE5-RESULT:" (pr-str %)))
```

Pod log:

```
PROBE5-RESULT: {:after-await-ctx  {:seon.db/agent-id "probe-agent", :seon.db/origin :system},
                :before-await-ctx {:seon.db/agent-id "probe-agent", :seon.db/origin :system}}
```

The context is identical before AND after the `await` — even though
the wrapping thunk `(fn [] (my-async-body))` carries no `^:async`
metadata. The Promise chain transparently carries the AsyncLocalStorage
context across.

### The canonical pattern (for callers to use)

```clojure
(defn ^:async do-step-1! [...] ...)   ; named helper
(defn ^:async do-step-2! [...] ...)   ; named helper

(defn ^:async caller [...]
  (await (db/with-tx-context
           {:seon.db/agent-id agent-id
            :seon.db/origin   :system
            :seon.db/turn-id  turn-id}
           (fn []                       ; <-- plain anonymous, NO :async
             (do-step-1! ...)            ; returns Promise
             ;; ^ careful: if you want sequential, await it. The
             ;;   thunk's return value is what flows back to caller.
             ))))
```

If the thunk body is `(do (do-step-1!) (do-step-2!) (do-step-3!))`,
the thunk returns the **last** Promise — but the first two were
never awaited and run concurrently. To sequence, write a `defn
^:async` helper that awaits each in turn and pass a thunk that calls
it. **That's the same pattern as `eval-batch!`'s
`run-one-entry!`** — except `run-one-entry!` is `fn ^:async name`
inline because it closes over `entry`/`turn-id`/etc.

## Q3 — `eval-batch!` reference pattern

### Source: `src/seon/eval.cljs:603–722`

The shape that ships in production today:

```clojure
(defn ^:async eval-batch!
  [compile-state parsed agent-ns-sym agent-id turn-id]
  (let [eids   (volatile! [])
        n-ok   (volatile! 0)
        n-fail (volatile! 0)]
    (doseq [entry parsed]
      (let [eval-id    (new-eval-id)
            tx-context {:seon.db/agent-id agent-id
                        :seon.db/eval-id  eval-id
                        :seon.db/origin   :agent}]
        (await
          (db/with-tx-context tx-context
            (fn ^:async run-one-entry! []
              (cond
                (and (= :read (:kind entry)) (false? (:ok? entry)))
                (do (await (record-eval! …)) (vswap! n-fail inc))

                :else
                (let [… start-ms (.now js/Date)
                      current-ns (await (read-current-ns …))
                      raw-result (await (eval compile-state source …))
                      …]
                  (await (record-eval! …))
                  (if (:ok result)
                    (vswap! n-ok   inc)
                    (vswap! n-fail inc)))))))
        (vswap! eids conj eval-id)))
    {:seon.eval/ids    @eids
     :seon.eval/n-ok   @n-ok
     :seon.eval/n-fail @n-fail}))
```

### Key observations

1. **Inline `(fn ^:async run-one-entry! [] …)` — the named-inline
   pattern.** This is necessary here because the body closes over
   `entry`/`turn-id`/the volatiles, so extracting to a top-level
   `defn` would require explicitly threading 4–5 args. Cost-benefit
   was inline. It works because the name carries `^:async`.

2. **Per-form scope is `with-tx-context`-wrapped.** The bundle keys
   `:seon.db/agent-id` + `:seon.db/eval-id` + `:seon.db/origin
   :agent` go into ALS for the duration of this entry. Every
   `record-eval!` → `db/transact!` inside picks them up automatically
   via `merge-tx-context-into-opts` (`db.cljs:677`).

3. **`record-eval!` is a top-level `defn ^:async`** (`eval.cljs:564`).
   It doesn't need to know about `with-tx-context` — it just calls
   `db/transact!`. The merge happens at the `transact!` boundary.

4. **No `:seon.db/opts {:tx-meta …}` literals at any transact site.**
   `record-eval!` calls `(db/transact! {:seon.db/tx-data tx-data})`
   only. The causality bundle reaches the tx purely through the
   ALS scope opened above.

### Pattern Sean's `run-turn!` should mimic

- Open ONE outer `with-tx-context` scope with `agent-id` + `session-id`
  + `turn-id` + `origin :system`.
- Body of the thunk is a `defn ^:async`-defined helper that does the
  7 steps in sequence with awaits.
- Each step's transact is a one-liner — `(db/transact! {:seon.db/tx-data
  [...]})` — no explicit `:tx-meta`. Auto-merged.

## Q4 — `run-turn!` decomposition

### Step-by-step audit (current code: `agent.cljs:492–587`)

| Step | Lines | What it does | Tx? | Helper extraction? | Data dependencies |
|---|---|---|---|---|---|
| 1 | 505–525 | ensure session, open turn, set agent state :running, bump turns-since-user | tx#1 | `open-turn!` | needs agent-id; reads current session and previous turns-since-user |
| 2 | 527–533 | resolve composer symbol, build input map, call composer (sync) | none | `render-prompt` | reads agent entity |
| 3 | 535–539 | persist :seon.turn/prompt-text | tx#2 | could fold into step 1 (after compose) | needs turn-id + prompt-text |
| 4 | 541–543 | call LLM (await) | none | inline | needs prompt-text |
| 5 | 549–557 | record assistant message as turn component | tx#3 | `record-assistant!` | needs turn-id + reply-text |
| 6 | 563–568 | parse forms, eval-batch! | tx#N inside | inline (eval-batch! is the helper) | needs reply-text + agent-id + turn-id |
| 7 | 570–574 | close turn (:done) + agent state :idle | tx#4 | `close-turn!` | needs turn-id + agent-id |

5 transacts (step 1 + step 3 + step 5 + step 7 + N inside eval-batch).
Top-level body is ~85 lines of one `let`.

### Could any transacts collapse?

- **Step 1 + Step 3 (open-turn + prompt-text).** YES, but only if the
  composer runs BEFORE the open-turn tx. The composer is sync; it
  reads agent state, session, and existing turn components — none
  of which require the new turn entity to exist. **Order can swap:**
  render first, then open-turn with `:seon.turn/prompt-text` already
  attached. Saves one tx per turn. ⚠ One subtlety: composer's
  prompt may want to show the *upcoming* turn index. The current
  spec's `prompt-section` (agent.cljs:960–970) reads
  `(count (:seon.session/turns sess))` — i.e. the count BEFORE the
  new turn lands. So composing-before-open preserves correctness.
  (If you wanted "this is turn N+1, by the way", you'd need to
  read `next-turn-index` and pass it explicitly.)

- **Step 5 + Step 7 (record assistant + close turn).** YES if you
  fold the assistant-message child INTO the close-turn tx as a
  single `{:seon.turn/id … :seon.turn/status :done :seon.turn/messages
  [{...}]}` entity map. That's 2 transacts → 1. Datahike will treat
  it as one atomic write. The semantic difference is "if eval-batch
  throws, the assistant message is also missing" — which is
  actually the **correct** semantics: if the agent's reply text
  couldn't be eval'd, neither should it appear as "an assistant
  message" without its eval'd consequences. But the current code
  records the assistant message BEFORE eval-batch so the agent's
  text is preserved even on eval-batch crash. Tradeoff worth
  surfacing to Sean — record-after lets you collapse the tx but
  loses partial-failure record.

- **eval-batch!'s internal txs.** Each form is its own tx because
  per-form lifecycle (capture eval-id, scope with-tx-context, on
  failure record :ok? false + continue). Cannot collapse without
  losing partial-failure semantics that v1.md §4.4 explicitly
  requires.

### What v1.md §6.1 says about granularity

v1.md §6.1 lists 7 numbered steps (lines 1043–1102) with `_ (db/transact!
…)` lines for each. It says nothing about ordering being non-negotiable.
v1.md §4.4 (line 692–696) DOES mandate that eval-batch fail-partial
preserves successes. v1.md §9 acceptance criterion 11 (referenced
in the prior research) requires "one pull on a `:seon.turn/id` returns
prompt-text + messages + evals" — i.e. all the data must land as
turn-components. Doesn't constrain how many txs.

### Should open + close be a composite `with-turn!`?

Yes — that's the cleanest shape. Sketch:

```clojure
(defn ^:async with-turn!
  "Open a :seon.turn entity, run `body-fn` (a 0-arg async fn that returns
   Promise<{:seon.turn/messages [...]}>), and close the turn on success
   (:status :done) or failure (:status :error). Returns the closed turn
   pull. body-fn's return map's :seon.turn/messages folds into the close-tx."
  [{:keys [agent-id session-id turn-id prompt-text]} body-fn]
  ;; open tx with prompt-text already attached:
  (await (db/transact!
           {:seon.db/tx-data
            [{:seon.session/id session-id
              :seon.session/turns
              [{:seon.turn/id turn-id
                :seon.turn/at (js/Date.)
                :seon.turn/status :running
                :seon.turn/prompt-text prompt-text}]}
             {:seon.agent/id agent-id :seon.agent/state :running}]}))
  (try
    (let [body-result (await (body-fn))]
      ;; close tx folds in assistant messages from body-result:
      (await (db/transact!
               {:seon.db/tx-data
                [(merge {:seon.turn/id turn-id :seon.turn/status :done}
                        (select-keys body-result [:seon.turn/messages]))
                 {:seon.agent/id agent-id :seon.agent/state :idle}]}))
      body-result)
    (catch :default e
      (await (db/transact!
               {:seon.db/tx-data
                [{:seon.turn/id turn-id :seon.turn/status :error}
                 {:seon.agent/id agent-id :seon.agent/state :idle}]}))
      (throw e))))
```

### Target shape sketch (illustrative — NOT production code)

```clojure
(defn ^:async ensure-session! [agent-id]
  (or (current-session agent-id) (await (start-session! agent-id))))

(defn render-prompt
  "Pure — sync composer call. Returns :string."
  [agent-id]
  (let [ent   (db/entity {:seon.db/ref [:seon.agent/id agent-id]})
        sym   (:seon.render/ai ent 'seon.agent/assemble-ctx)
        input (ai-render-input sym @db/*conn* agent-id ent)]
    (or (:seon.render/text (render/ai-render sym input)) "")))

(defn ^:async ask-and-eval!
  "Body of with-turn!: render the prompt, ask the LLM, eval the forms.
   Returns a map with :seon.turn/messages (the assistant message) and
   :seon.agent/eval-count."
  [{:keys [agent-id turn-id prompt-text llm-fn compile-state]}]
  (let [resp       (await (llm-fn prompt-text))
        reply-text (or (:text resp) "")
        parsed     (repl/parse-forms reply-text)
        batch      (await (seval/eval-batch! compile-state parsed
                                              (home-ns agent-id) agent-id turn-id))]
    {:seon.turn/messages
     [{:seon.message/id      (new-id!)
       :seon.message/role    :assistant
       :seon.message/content reply-text
       :seon.message/at      (js/Date.)}]
     :seon.agent/eval-count (:seon.eval/n-ok batch)}))

(defn ^:async run-turn!
  "v1.md §6.1 — one full turn."
  [{:seon.agent/keys [id llm-fn compile-state]}]
  (await
    (db/with-tx-context
      {:seon.db/agent-id id :seon.db/origin :system}
      (fn []
        (let [session    (await (ensure-session! id))
              session-id (:seon.session/id session)
              turn-id    (new-id!)
              prompt     (render-prompt id)]
          (db/with-tx-context
            {:seon.db/session-id session-id :seon.db/turn-id turn-id}
            (fn []
              (await
                (with-turn! {:agent-id id :session-id session-id
                             :turn-id turn-id :prompt-text prompt}
                  (fn []
                    (ask-and-eval! {:agent-id id :turn-id turn-id
                                    :prompt-text prompt
                                    :llm-fn llm-fn
                                    :compile-state compile-state})))))))))))
```

Top-level body of `run-turn!` is now ~12 lines. Two nested
`with-tx-context` scopes — outer carries agent+origin, inner adds
session+turn — and ALS merge semantics mean nested transacts get
the full bundle. All `^:async` markers are on `defn` names; no
inline-fn-metadata gymnastics required.

(Caveat: that draft drops the try/catch error-recovery branch from
the current `run-turn!`. `with-turn!` handles the turn's status flip
on error, but the OUTER catch that flips `:seon.agent/state :idle`
on a session-creation failure or prompt-render throw needs to stay.
Add a `try/catch` around the body or push that responsibility into
`with-turn!`. Implementation detail; the shape works.)

### Wins

- 7 numbered steps → 3 named helpers (`ensure-session!`, `render-prompt`,
  `ask-and-eval!`) + 1 bracketing combinator (`with-turn!`).
- 4 transacts in `run-turn!` body → 2 transacts (open + close,
  inside `with-turn!`), eval-batch's N stays unchanged. Saves the
  separate "persist prompt-text" tx and the separate "record assistant
  message" tx.
- Every transact in the pipeline auto-picks up agent+session+turn+
  origin tx-meta. Zero `:seon.db/opts {:tx-meta …}` literals.
- One try/catch in `with-turn!` covers turn-status flip; outer catch
  just needs to flip agent state.
- Top-level body ~12–15 lines.

## Q5 — Broader async ergonomics audit

Skimmed `src/seon/agent.cljs` and `src/seon/eval.cljs` for patterns
that read gnarly:

- **`run-turn!` is the worst offender.** 85-line let-block as
  audited above.
- **`run-agentic-loop!`** (`agent.cljs:589–633`) is fine — one
  `(loop [] …)` with `(await (run-turn! input))` at the top and
  branching on the result map. Clean.
- **`eval-batch!`** (`eval.cljs:603–722`) is dense but each named
  inline helper is doing real work; not a candidate for further
  decomposition without flattening into many small helpers that
  thread state through volatiles. Leave it.
- **`record-eval!`** (`eval.cljs:564–601`) is one `cond->` plus one
  `await`. Clean.
- **No `.then` chains in seon code.** Everything uses `await`.
  Browsing showed no places where someone manually chained Promises
  outside of pod-init / log-stream entry points (where `.then` is
  the right tool for fire-and-forget side effects).
- **`try/catch` patterns are healthy.** `run-turn!`'s outer
  try/catch is the only place where a top-level catch wraps the
  whole pipeline; it's there to guarantee `:seon.agent/state :idle`
  flip. The proposed `with-turn!` simplifies that: turn-status flip
  becomes the combinator's responsibility, and the outer catch only
  has to flip agent state.

Overall: the codebase uses `^:async` cleanly via `defn` everywhere
except the one named-inline helper in `eval-batch!`. No drift to
clean up beyond the `run-turn!` reshaping above.

## Q6 — Datahike-cljs tx-fns

### Source check

`reference-code/datahike/src/datahike/db/transaction.cljc:842–863`:

```clojure
:db.fn/cas (compare-and-swap db report op-vec)
:db/cas (compare-and-swap db report op-vec)
:db.fn/call (let [[_ f & args] op-vec]
              [report (apply f db args)])
```

The transaction op handler is in `.cljc` and works in both JVM and
CLJS. `:db.fn/call` is a direct fn invocation: `(apply f db args)`
where `f` is a literal CLJS fn passed in the tx vector. The fn runs
**inside the transactor** with the current db value as its first
arg and returns tx-data (which is then expanded as if the caller
had written it directly).

### REPL transcript (verified)

```clojure
(ns probe-txfn2 (:require [seon.db :as db] [datahike.api :as d]))
(defn ^:async go []
  (await (db/transact!
           {:seon.db/tx-data
            [[:db.fn/call
              (fn [_db]
                [{:seon.ns/name :seon.txfn-test
                  :seon.ns/source "(ns seon.txfn-test) ; via tx-fn"}])]]})))
(.then (go) #(...))
;; TXFN2-RESULT: true
;; PULL: {:seon.ns/name :seon.txfn-test, :seon.ns/source "(ns seon.txfn-test) ; via tx-fn"}
```

Tx-fns work. The fn ran inside the writer, produced one entity map,
and that entity is queryable post-tx.

### Constraints

- **Body must be SYNC.** No `await` inside the tx-fn — it runs
  inline in the writer; the transactor doesn't `await` the return
  value. Returning a Promise would just give datahike a JS Promise
  object as "tx-data" and fail.
- **`db` arg is the current db value** — full datalog/pull works
  against it. So you can read-then-write atomically: "find existing
  ns entity, append to its source, transact updated entity."
- **CAS is also available** (`:db.fn/cas` / `:db/cas`) — useful for
  optimistic concurrency on a single attr.

### Recommendation

- **Use for the detect-and-tee program-graph step** (v1.md §2.2,
  Patch 2 territory). The tee can run as `[:db.fn/call (fn [db]
  (concat eval-entity-tx (program-graph-tee db eval-id source …)))]`,
  reading current ns entity inside the tx to decide what to write —
  one atomic tx for "this eval landed AND its program-graph effects
  landed."
- **Don't use for run-turn! step composition** — the steps cross
  await boundaries (LLM call, eval batch); a tx-fn cannot span
  those.
- **Could use for "next-turn-index"** if `:seon.session/turn-count`
  is read-then-write inside one tx: `[:db.fn/cas <session-eid>
  :seon.session/turn-count current-count (inc current-count)]`.
  But the simpler shape today — derive `turn-index` from `(count
  turns)` at read time — already eliminates that race; storing
  `turn-count` AT ALL is something v1.md §2.1 documents as cut
  for that reason.

## Code smells found while reading

- **`agent.cljs:455–460`** — stale comment claims `:tx-meta`
  plumbing is "intentionally absent" pending Platform's Phase 3a.
  Per the prior research (mvp-spec-coherence-2026-05-23.md
  PLATFORM-FLAG 3), this is already shipped (`db.cljs:677–688`
  + `db.cljs:735`). Remove the comment when the rewrite lands.
- **`agent.cljs:508`** — `(:seon.session/id (current-session id))`
  is re-derived after `(when-not (current-session id) (await
  (start-session! id)))`. Two reads of the same session entity.
  Cleaner: `(let [session (or (current-session id) (await
  (start-session! id))) session-id (:seon.session/id session)] …)`.
  (Bonus: `start-session!` currently returns the session-id STRING,
  not the entity — different shape than `current-session` which
  returns an entity-map. Fix by having both return the same shape,
  or by binding both `session-id` and re-pulling the entity from
  the returned id.)
- **`agent.cljs:518–521`** — `(inc (or (:seon.session/turns-since-user
  (db/entity {:seon.db/ref [:seon.session/id session-id]})) 0))`
  inside the open-turn transact map. Reads the session entity AGAIN
  (third read of the same entity in this step). Pull
  `turns-since-user` along with `session-id` in the outer let.
- **`agent.cljs:576–578`** — the closing pull uses `'[*]` (per the
  prior research's audit item #8 for §6.1). Should be the nested
  pattern from v1.md:262–272 so the returned turn entity carries
  inlined messages + evals. Caller's `:seon.agent/eval-count
  n-ok` (line 578) is assoc'd onto that pull, which works on
  either shape, but downstream `run-agentic-loop!` only reads
  `:seon.turn/status` and `:seon.agent/eval-count` — neither
  forces the nested shape. So this is a "spec compliance" smell,
  not a runtime bug. Worth fixing in the rewrite.
- **`db.cljs:391–393`** — `defonce als-instance` requires
  `node:async_hooks` at top level. Per `db.cljs:385–388` comment,
  this is deliberate (fail-fast). Note for Phase 3 (WASM):
  `node:async_hooks` does not exist in wasm-rquickjs. The
  `with-tx-context` + `current-tx-context` abstraction layer makes
  this swappable, but the swap target needs a fiber-local equivalent
  in the WASM runtime. Tracked in CLAUDE.md but worth re-flagging
  because the proposed `run-turn!` shape relies on ALS-style
  propagation across `await`.

## Cross-references

- `src/seon/eval.cljs:603–722` — `eval-batch!` reference pattern
- `src/seon/eval.cljs:564–601` — `record-eval!` (top-level `defn ^:async`)
- `src/seon/db.cljs:391–407` — ALS instance + `current-tx-context`
- `src/seon/db.cljs:409–437` — `with-tx-context`
- `src/seon/db.cljs:677–735` — `merge-tx-context-into-opts` +
  `transact!` auto-merge
- `src/seon/agent.cljs:492–587` — current `run-turn!` (the target
  of this research)
- `src/seon/agent.cljs:589–633` — `run-agentic-loop!` (already clean)
- `/Users/sean/src/clojurescript/src/main/clojure/cljs/core.cljc:975–977`
  — `await` macro
- `/Users/sean/src/clojurescript/src/main/clojure/cljs/analyzer.cljc:2305–2322`
  — `fn*` analyzer's `:async` resolution (with Borkent's
  `#_`-commented rationale)
- `reference-code/datahike/src/datahike/db/transaction.cljc:842–863`
  — `:db.fn/call` / `:db.fn/cas` op handlers (available in CLJS)
- `docs/prds/agent-runtime/v1.md:1038–1106` — v1 §6.1 `run-turn!` spec
- `docs/prds/agent-runtime/research/mvp-spec-coherence-2026-05-23.md`
  — prior research; PLATFORM-FLAG 3 covers the stale `:tx-meta`
  comment.
