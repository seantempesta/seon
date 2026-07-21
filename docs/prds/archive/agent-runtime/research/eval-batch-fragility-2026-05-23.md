---
type: research
status: active
tags: [research, cljs, pod, agent]
---

# eval-batch! fragility deep-dive — concerns (a) and (c) (2026-05-23)

## TL;DR

**Concern (a) — `set!` on `*cljs-warning-handlers*` is a true process-global mutation.** `raw-eval` (`src/seon/eval.cljs:315-322,332`) captures the current handler chain into a local, calls `set!` on `cljs.analyzer/*cljs-warning-handlers*` (a `^:dynamic` var defined at `cljs/analyzer.cljc:494`), then restores in the cljs.js callback. CLJS dynamic vars without a `binding` frame mutate the root, and CLJS has no per-thread binding stack — so the `set!` lasts across every fiber until restore. `cljs.js/eval-str` does NOT accept a `:warning-handler` option (verified by reading `cljs/js.cljs:1038-1136` — `*cljs-warning-handlers*` is never threaded through `bound-vars` and never appears in the `binding` block at 1052-1063). A `binding` wrapper at the call site doesn't help: `eval-str` is callback-driven, the binding pops before the analyzer runs. **The only robust fix is to install our handler ONCE at boot and never mutate it again** — make the handler a multi-tenant dispatcher keyed on a value that IS fiber-local (an AsyncLocalStorage-scoped warning-bucket). Ship after the walker; size: ~30 lines in `seon.eval`, no caller changes.

**Concern (c) — what hot-reload of `seon.eval` mid-batch actually breaks.** Less than the prior research said. The vars defined by in-flight evals DO land on `globalThis` regardless of `eval-batch!`'s fate, because `cljs.js/eval-str`'s emit step (`js.cljs:1129` `(*eval-fn* evalm)`) runs `goog.globalEval` on the compiled JS as soon as the analyze-and-emit pipeline completes, BEFORE the callback fires that resolves `raw-eval`'s Promise. The detect-and-tee DB write IS at risk: if the `cljs.eval` callback fires after the new `seon.eval` reload, `record-eval!` still works (it's just `db/transact!`), but the program-graph tee — which the MVP is wiring INTO `eval-batch!` — could miss the entity. The "lost in-flight defs" framing is wrong; the real blast radius is "the agent's `!current-ns` atom and the `:seon.eval` DB row may be missing for forms whose Promise was in flight at reload time." Plus: `init-version` rotation invalidates `@!compile-state`, the new state has none of the agent's defs in its analyzer cache, so subsequent eval batches treat them as undeclared (the `truly-undeclared?` globalThis fallback at `eval.cljs:285-294` saves runtime resolution but warnings still surface). Remediation: document the constraint and ship `(user/pause-and-reload!)` — full reload-resilience requires moving more state into `defonce` and a versioned compile-state pool, which is over-engineering for a dev-loop annoyance.

## Concern (a) — `set!` of `*cljs-warning-handlers*` is global

### What set! actually does

The relevant code is `src/seon/eval.cljs:296-354`. Specifically:

```clojure
;; eval.cljs:313-332
[compile-state form-str ns-sym analyze-deps?]
(let [warnings (atom [])
      prev-h  ana/*cljs-warning-handlers*]              ; capture root
  (js/Promise.
    (fn [resolve reject]
      (set! ana/*cljs-warning-handlers*                  ; MUTATE root
            [(fn [type _env extra]
               (when (#{:undeclared-var :undeclared-ns} type)
                 (swap! warnings conj
                        (assoc extra :seon.eval/warning-type type))))])
      (cljs/eval-str compile-state form-str 'seon.dynamic
        {...}
        (fn [{:keys [error value ns]}]
          (set! ana/*cljs-warning-handlers* prev-h)      ; restore
          ...)))))

```

The var being mutated is `cljs.analyzer/*cljs-warning-handlers*`, defined at `cljs/analyzer.cljc:494-495`:

```clojure
(def ^:dynamic *cljs-warning-handlers*
  [default-warning-handler])

```

It's used by every analyzer warning emission point — `analyzer.cljc:765-767`:

```clojure
(defn warning [warning-type env extra]
  (doseq [handler *cljs-warning-handlers*]
    (handler warning-type env extra)))

```

### Semantics of `set!` on a CLJS dynamic var

CLJS dynamic vars do NOT have a thread-local binding stack — JS is single-threaded but cooperatively multitasking. The binding model in CLJS:

- `(binding [v new] body)` is `try (push v new) body (finally (pop))` — synchronous, lexically scoped.
- `(set!  v new)` ALWAYS writes to the root (and the current binding if one is active, but that binding will be popped on lexical exit).
- There is NO per-fiber binding propagation. `binding` does not survive an `await` or a callback boundary (the binding frame is popped when the synchronous `binding` form returns, which happens BEFORE the Promise resolves).

In `raw-eval`, there is no `binding` form at all — `set!` writes to the root. Between the set and the restore-on-callback, EVERY analyzer warning anywhere in the process sees agent A's handler chain.

### The multi-agent failure scenario

V1's stated goal includes "concurrent agents in one pod" (`v1.md` + multi-pod concurrency notes in `STATUS.md`). Concrete failure:

1. Agent A's `eval-batch!` is mid-form; `raw-eval` has run `set!` and is awaiting the cljs.js callback.
2. Concurrent fiber: Agent B's `eval-batch!` starts a new form; its `raw-eval` runs `let [prev-h ana/*cljs-warning-handlers*]` — captures A's handler as `prev-h`. Then `set!`s its own handler.
3. Some time later A's callback fires: `set! prev-h` restores what A captured (the ORIGINAL `[default-warning-handler]`). But B is still running; B's handler is now lost. The next analyzer warning in B's eval goes to `default-warning-handler` (which prints to stderr) instead of B's `warnings` atom.
4. B's callback eventually fires: `set! prev-h` — restores what B captured (A's handler). B's eval may have emitted `:undeclared-var` warnings that escaped to stderr; B's `truly-undeclared?` filter ran against an empty `@warnings`, so it returned `false` for all candidates → eval reported `{:ok true}` for a form that should have errored as `:undeclared-var`.

Different concrete scenario: the handlers don't get LOST, they get CROSS-WIRED. If A and B are both mid-eval and both fire warnings, A's `swap! warnings conj` runs from B's emitter callback (because B's set! installed a closure over B's `warnings` atom, but at that moment A's analyzer is also walking forms and calling the GLOBAL `*cljs-warning-handlers*` — which is now B's closure). Result: A's warnings end up in B's `warnings` atom. A's `truly-undeclared?` check sees an empty atom and approves a form that warned. B's check sees A's warning and may falsely reject a clean form.

Both failure modes are dataloss/falseneg/falsepos for the undeclared-var safety check — the exact contract `raw-eval`'s docstring (`eval.cljs:300-305`) claims to provide. Multi-agent v1 cannot ship while this is in place.

### Remediation options (ranked)

#### Option 1 — Install handler ONCE at boot, dispatch via fiber-local bucket (RECOMMENDED)

Replace `set!`-per-eval with a one-time `set!` at compile-state init that installs a dispatcher. The dispatcher reads a per-fiber warnings-bucket from `AsyncLocalStorage` (same mechanism `seon.db/with-tx-context` already uses). Each `raw-eval` opens an ALS scope with its own bucket.

```clojure
;; seon.eval — module init, runs ONCE per init-version
(defonce ^:private warnings-als
  (let [AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (AsyncLocalStorage.)))

(defn- install-warning-dispatcher!
  "Install the per-fiber warning dispatcher ONCE. Subsequent calls are
   no-ops. The dispatcher reads the active bucket from ALS; if no
   bucket is set, falls back to the default handler (preserves stderr
   warnings for non-eval analyzer callers — there shouldn't be any,
   but defense)."
  []
  (set! ana/*cljs-warning-handlers*
        [(fn [type _env extra]
           (when-let [bucket (.getStore warnings-als)]
             (when (#{:undeclared-var :undeclared-ns} type)
               (swap! bucket conj
                      (assoc extra :seon.eval/warning-type type)))))]))

;; in raw-eval (replaces the let/set!/restore):
(defn ^:async ^:private raw-eval [compile-state form-str ns-sym analyze-deps?]
  (let [warnings (atom [])]
    (js/Promise.
      (fn [resolve reject]
        (.run warnings-als warnings
          (fn []
            (cljs/eval-str compile-state form-str 'seon.dynamic
              {...}
              (fn [{:keys [error value ns]}]
                ;; @warnings now holds ONLY warnings from this fiber's eval
                (cond ...)))))))))

```

**Why this works.** `AsyncLocalStorage` is fiber-local across `await` — Sean's memory note confirms this for `with-tx-context`, and `seon.db.cljs:391-393` proves it's already wired. The cljs.js callback runs inside the `.run` scope (callbacks scheduled via Node's microtask queue inherit the ALS context); analyzer warnings emitted from cljs.js's analyze pass run inside the same context (the trampoline doesn't break it because trampoline is synchronous — ALS context is preserved across `setTimeout`/microtask/promise boundaries by V8's `AsyncContext` instrumentation).

**Edge case to verify on live pod:** does cljs.js's trampolined `compile-loop` (which iterates synchronously through forms via the `trampoline` macro) preserve ALS context? It should — synchronous code always preserves the running context — but worth a live probe. Specifically: `(set!)` happens on the `complete` callback at `js.cljs:1122-1132`, which is called from `cache-source` (if present) or directly; cache-source isn't set in our opts, so the callback fires synchronously from the trampoline. Inside ALS scope: confirmed.

**Touch surface.** Three changes in `seon.eval`:
1. Add `warnings-als` defonce + `install-warning-dispatcher!`.
2. Call `install-warning-dispatcher!` at the end of `init-bootstrap!` (after `load-all-analysis-caches!`) — runs once per `init-version`.
3. Rewrite `raw-eval` to `.run` ALS instead of `set!`/restore. ~10 lines net.

Zero caller changes. No new dependency (Node `async_hooks` already required).

**Risk.** Two: (1) if `init-bootstrap!` is called more than once in a pod lifetime, the second `set!` over-installs the same dispatcher (harmless, idempotent). (2) If a non-eval caller of cljs.js's analyzer runs (e.g. shadow's hot-reload during dev), there's no bucket — the dispatcher no-ops the warning. That's a slight regression vs. today's `default-warning-handler` stderr print, but in our pod the only analyzer caller is `raw-eval`. Document.

#### Option 2 — Per-agent compile-state with per-agent handler

Each agent gets its own `compile-state` atom. Bake the handler into the agent's state at creation. `*cljs-warning-handlers*` would still be a global, but each agent's `raw-eval` would `set!` its own handler at the start of each eval AND THE GLOBAL WOULD STAY SET PER-AGENT.

Problems: (a) `*cljs-warning-handlers*` is a single global var, so per-agent compile-states share it anyway — doesn't fix anything. (b) Replicating the analyzer cache per agent is expensive (the substrate cache is ~1MB+ of transit data, loaded once at boot today). (c) Defeats the design where `lookup-value` and friends all assume a single compile-state.

Reject.

#### Option 3 — Document the limit, lock concurrent agents out for v1

Add a process-wide mutex around `raw-eval` (a Promise queue). Each eval awaits the prior one's completion before running. Solves correctness; serializes everything.

Problems: defeats v1's concurrent-agents goal. If we're going to do this, just keep `set!` as-is and document "single-agent-per-pod for v1." Sean's direction explicitly wanted concurrent-agents-in-one-pod for v1.

Reject for v1; viable as a stopgap if Option 1's ALS probe surfaces blockers.

### Option 1 sequencing

- **Before walker:** no blocker — walker is single-threaded (replays sequentially in one fiber). Option 1 doesn't need to land before resume.
- **Before MVP's detect-and-tee:** no conflict — detect-and-tee runs INSIDE `eval-batch!` after the form completes, doesn't touch warnings.
- **Before concurrent-agents (v1 alpha):** REQUIRED. The dataloss is undetectable from the user's seat — agents get spurious "undeclared var" rejections or, worse, spurious approvals. Ship Option 1 in the same window as the second-agent enablement.

## Concern (c) — hot-reload of `seon.eval` mid-batch

### What state lives in `seon.eval`

From `grep -n "^(def\|^(defonce" src/seon/eval.cljs`:

| Line | Form | Hot-reload behavior |
|------|------|---------------------|
| 60 | `(defonce !timeout-ms (atom 10000))` | SURVIVES reload (defonce) |
| 70 | `(defonce ^:private !next-budget-ms (atom nil))` | SURVIVES |
| 99 | `(defonce ^:private timeout-sentinel #js {...})` | SURVIVES — important because `identical?` checks against it |
| 170 | `(def init-version (gensym "..."))` | ROTATES on reload (def, not defonce) — by design |
| 427 | `(def ^:private results-key-prefix "...")` | Rebound to same value — no effect |

Plus closed-over locals inside in-flight Promises: `compile-state`, `agent-ns-sym`, `agent-id`, `turn-id`, `warnings` atom, `prev-h` capture in `raw-eval`.

### What actually breaks on (user/reload) mid-batch

**Step through the failure scenario.** `eval-batch!` is at form N of M (M > N). Form N is mid-`await` on `raw-eval`'s Promise. User hot-reloads `seon.eval`.

1. **`init-version` rotates.** Next call to `ensure-bootstrap!` (`seon.repl.cljs:99-105`) sees `(identical? @!init-version seval/init-version)` is false → builds a fresh state, resets `!compile-state`, resets `!init-version`. The fiber holding the OLD state (via closure on `eval-batch!`'s `compile-state` arg) keeps using the old state for the rest of this batch.

2. **In-flight form N completes against the OLD compile-state.** The JS emitted by cljs.js has already been `goog.globalEval`'d at `js.cljs:1129` BEFORE the callback fires that resolves `raw-eval`'s Promise. So:
   - The var IS on `globalThis` (cljs.js writes vars at munged paths during emit, not during callback resolution).
   - The OLD compile-state's `:cljs.analyzer/namespaces` has the var's `:defs` entry.
   - The NEW compile-state's `:cljs.analyzer/namespaces` DOES NOT have the var.
   - Form N's `record-eval!` runs successfully (it's just `db/transact!` — unaffected by the reload).
   - `update-current-ns!` runs successfully (it's an eval against the OLD state — but the agent-ns's `!current-ns` atom lives on `globalThis` at the agent-ns munged path; the reset! works).

3. **Form N+1 starts.** It still uses the closed-over OLD `compile-state` — the `doseq` over `parsed` in `eval-batch!` captured the arg at function entry. So forms N+1..M run against the OLD state, see all the OLD analyzer entries. They work fine.

4. **NEXT call to `eval-batch!`.** Caller (`run-turn!`) calls `(ensure-bootstrap!)` first → gets the NEW state. Now the NEW state has the substrate analyzer cache (re-loaded) but NONE of the agent's defs from the prior batch. Cross-form refs to those defs trigger `:undeclared-var` warnings; `truly-undeclared?` walks `globalThis`, finds the var (it's still there), returns `false` → eval proceeds, no error.
   - **The `:undeclared-var` warning DOES get logged to stderr** with the current `set!` impl, because the eval-batch! warnings atom captures only the warning's metadata, and the default chain still fires the stderr print until our `set!` runs. Actually re-read raw-eval: `set!` clears the chain to ONLY our handler, so the default stderr print doesn't fire. So clean: no stderr noise, no false reject.
   - **But analyzer-level autocomplete / definition tracking is lost** in the NEW state — that's only a concern if some downstream tool reads `:cljs.analyzer/namespaces` for editor-grade info. We don't, in v1.

5. **Resume from DB** (separate boot): the persisted `:seon.eval` entities are intact. The persisted `:seon.fn` / `:seon.ns` / `:seon.schema` entities are intact (assuming detect-and-tee fired before the reload — see below). Resume walker replays them into the NEW state. Clean.

### Was the prior research's claim "loses in-flight defs" correct?

**No, not as stated.** Vars are on globalThis as soon as JS emits, which is before the callback resolves. The "loss" is restricted to:

- **Analyzer cache entries in the NEW compile-state** for in-flight defs — these are not reconstructed until resume on next boot, OR until the agent next refers to the var (which triggers an undeclared-var warning that `truly-undeclared?` swallows). Soft loss: no observable user-facing behavior change in v1.

- **Detect-and-tee DB writes** — MVP is wiring this INTO `eval-batch!`. If detect-and-tee fires AFTER `record-eval!` (i.e. as a side-effect of the eval-entity write), and the listener is registered on a `defonce`d listener structure that survives reload, the tee fires. If the tee is a direct `db/transact!` call inside `eval-batch!`'s body, it fires synchronously and lands before the next form runs — also fine.

- **The `eval-batch!` fn itself** — if reloaded mid-batch, the fiber holding the old closure finishes against the old code (in-flight Promises don't get hot-swapped). Subsequent calls use the new code. This is "lossless" in the sense that the in-flight batch finishes; "lossy" in the sense that the new code doesn't see the in-flight batch's state until the batch's record-eval! writes land in the DB.

### Tee idempotency claim (from prior research)

"Tee logic is naturally idempotent (identity-attr upserts handle re-defines as last-write-wins), so durable" — **confirmed correct** by inspection of MVP's spec: `:seon.fn/sym` / `:seon.ns/name` / `:seon.schema/key` are all `{:seon.db/identity true}` per `client.cljs/agent-bootstrap-attrs`. Re-tee of the same source produces an upsert with the same eid and either a no-op (same `:source` string) or a new tx-id (different `:source`). Either way no duplicate row, no data loss.

So the (c) blast radius is:

- **User has to re-run the form** if the form's record-eval! write was in flight at reload and somehow failed. But record-eval! is `db/transact!` which is synchronous-resolving — there's no way it's "in flight" relative to a same-fiber hot-reload (Node's microtask ordering means the await completes before the next event-loop tick).
- **Analyzer cache for those defs is empty in the new state.** Recoverable on resume.

**Realistic impact:** none observable in the current code path. The "annoyance" framing was correct; the "loses in-flight defs" framing was overstated.

### Remediation options

#### Option C1 — Document the constraint + ship `(user/pause-and-reload!)`

Add a helper that:
1. Sends an interrupt to the running agent (sets a flag the agent loop checks between turns).
2. Awaits in-flight eval-batch!'s completion (via a defonce'd `!in-flight` atom containing the latest batch Promise).
3. Calls `(user/reload)`.
4. Calls `(seon.agent/resume!)` (or equivalent) to restart the agent loop.

Cost: ~20 lines in `user.clj` (host JVM) + a `!in-flight` defonce + completion-tracking in `eval-batch!`. The `!in-flight` atom is the only `eval-batch!` change.

Benefit: zero ambiguity for the dev loop. Reload is always clean.

#### Option C2 — Move closed-over state into defonces

Make `eval-batch!`'s loop-state (n-ok, n-fail, eids volatiles) live in a `defonce`d atom keyed by batch-id. On reload, the new code can pick up the in-flight batch's state.

Problems: (a) Doesn't solve the core issue — the in-flight Promise still resolves against the OLD `eval` fn closure, not the new one. (b) Adds complexity for a dev-loop annoyance. (c) Batch-id keyed state needs cleanup logic to avoid leaks.

Reject as over-engineering.

#### Option C3 — Detect mid-batch reload and abort gracefully

Have the in-flight batch check `(identical? init-version <captured-version>)` before each form. If different, log a warning and stop processing remaining forms (don't record them).

Problems: doesn't actually help — the in-flight form continues anyway. Just changes "lose analyzer cache for forms N+1..M" to "lose results for forms N+1..M." Strictly worse.

Reject.

#### Recommendation: Option C1

Concrete `pause-and-reload!` sketch:

```clojure
;; in seon.eval — add tracking
(defonce ^:private !in-flight-batches (atom #{}))

(defn register-batch! [batch-id promise]
  (swap! !in-flight-batches conj batch-id)
  (.finally promise (fn [] (swap! !in-flight-batches disj batch-id))))

;; in user.clj (or seon.dev)
(defn pause-and-reload! []
  ;; Set a "drain" flag the agent loop reads between turns:
  (seon.agent/request-pause!)
  ;; Wait until no batches in flight (poll, max 10s):
  (deref-await (fn [] (empty? @seon.eval/!in-flight-batches))
               {:timeout-ms 10000 :interval-ms 100})
  (user/reload)
  (seon.agent/resume!))

```

Effort: ~1 hour. Ship after walker, after detect-and-tee. No blocker for either.

## Additional concerns surfaced

These came up while reading `eval.cljs` end-to-end for (a)/(c). Separated for MVP triage.

### A1. `init-version` is a `def` not `defonce` — interaction with `seon.eval` requires

`seon.eval/init-version` (line 170) is `def` so reloads rotate it. But if another namespace requires `seon.eval` and captures `seval/init-version` at its own require time (e.g. into a local), that snapshot becomes stale. Current callers:
- `seon.repl/ensure-bootstrap!` reads `seval/init-version` at CALL time (not require time) — safe.
- No other readers in `seon.eval` itself.

Looks fine, but if any future caller does `(def my-version seval/init-version)` at top-level, they'll silently hold a stale gensym. Document or wrap in a fn.

### A2. `!next-budget-ms` is a process-global atom, shared across agents

`(defonce ^:private !next-budget-ms (atom nil))` (line 70). Agent A's `(budget 60000 form)` sets it; if Agent B's eval-batch! enters `maybe-await-value` before A's eval consumes the override, B picks up A's budget. Same multi-agent hazard as (a), narrower blast radius (a wrong timeout, not silent dataloss).

Same fix shape: an ALS-scoped bucket. Per-fiber override, defaults to global if no scope.

### A3. `setup-agent-ns!` rewrites the agent's atoms on every call

The docstring says "Idempotent: re-running resets atoms to initial values." But the source string at `eval.cljs:462-471` is unconditional `(def !session-id (atom ...))` — re-running clobbers any in-progress agent state. Combined with the recommended boot sequence in the prior research (`setup-agent-ns!` runs AFTER resume to defensively win the last-write race), this means:

- If an agent's source includes `(def !session-id ...)` for ANY reason (it shouldn't, but agents are unpredictable), resume replays it, then setup wipes it back to the substrate default. Probably correct semantics, but worth flagging in the resume design doc.

### A4. `read-current-ns` / `update-current-ns!` are full eval-str round-trips per form

For every form in a batch, `eval-batch!` does THREE evals: read-current-ns, the actual form, update-current-ns!. Two are pure atom operations dressed as eval-str calls — they go through the full read → analyze → emit → eval pipeline. Per-form overhead is non-trivial (analyze ~5-20ms for trivial forms based on Sean's memory of CLJS bootstrap timing).

Worth a dedicated optimization: read/write the atom via direct globalThis access (the same path `lookup-value` walks), bypassing eval-str entirely.

This is also where the user's reinforced suggestion lands — see below.

### A5. User's just-relayed point — eliminate `!current-ns` entirely

The user's message (relayed during research): the right fix isn't "read from `:seon.eval/ns` not the atom" — it's get rid of the atom.

- **Within-batch:** thread the ending-ns through the loop's local state (loop accumulator or batch-scoped volatile). Each form's eval takes the prior form's `:ns` as input.
- **Across batches:** `:seon.agent/current-ns` on the agent entity (already registered). End of batch: `(db/transact! {:seon.db/tx-data [{:seon.agent/id id :seon.agent/current-ns ending-ns}]})`. Start of batch: read from the agent entity.

This is **correct and stronger than the prior (b) mitigation.** It also closes part of (a)'s surface — one fewer process-global mutation. The atom is doing two jobs (within-batch threading + cross-batch persistence) and both have cleaner homes.

Touch surface: `eval-batch!` loop is rewritten to thread `current-ns` through (no more `read-current-ns` / `update-current-ns!` eval-str calls), plus one extra `db/transact!` at end of batch. `:seon.agent/current-ns` registration already exists (per memory note). Net: REMOVE `!current-ns`, `read-current-ns`, `update-current-ns!`, the `setup-agent-ns!` source-fragment for `!current-ns`. ADD: loop accumulator + end-of-batch transact.

**Sequencing:** this is squarely MVP's lane (touches `eval-batch!`'s core loop). Land in same patch as detect-and-tee — both touch the loop body, and detect-and-tee wants the ns-from-eval-entity contract anyway. The Platform-side fix for (a) is orthogonal — ALS bucket is about warning handlers, not ns tracking.

### A6. `seon.dynamic` ns-name passed to cljs.js/eval-str

`raw-eval` calls `(cljs/eval-str compile-state form-str 'seon.dynamic {...})`. The second-to-last positional arg is `name`, which cljs.js uses as the source name for analyzer state cache keys (`evalm` map at `js.cljs:1117`). Hardcoding `seon.dynamic` means every eval's cache entry lands at the same key — implicit overwrite each call. If anything reads `(get-in @compile-state [::ana/namespaces 'seon.dynamic :cache])` it sees only the LAST eval's cache. Probably not a real concern (we don't read this), but the symbol is genuinely shared global state across agents (not just within agent). Flag.

## Recommended sequencing

| What | Lane | Blocks | Blocked by | When |
|------|------|--------|-----------|------|
| Walker (resume) ship | Platform | nothing | nothing | Now |
| MVP detect-and-tee + A5 (kill !current-ns) | MVP | — | — | In flight |
| (a) Option 1 — ALS warning dispatcher | Platform | concurrent-agents v1 alpha | walker shipped (so we don't conflict on `eval.cljs`) | After walker |
| A2 — ALS budget bucket | Platform | concurrent-agents v1 alpha | (a) ALS pattern landed | Same patch as (a) |
| (c) Option C1 — `pause-and-reload!` | Platform | — (dev-loop QoL only) | — | Any time |
| A1, A3, A6 | either | — | — | When touching the affected fns |

**Critical path for v1 alpha concurrent-agents:** (a) Option 1 + A2 (one patch, ~50 lines). Without these, multi-agent has undetectable false-pos/false-neg on the undeclared-var safety check AND wrong timeouts on Promise auto-await.

**Not on critical path:** (c) remediation is pure dev-loop QoL.

## Open questions for live MCP probing

1. **Does `AsyncLocalStorage` context propagate across cljs.js's `trampoline-safe` callback?** Cljs.js's `trampoline` (at `js.cljs:1050`) is synchronous, so context should preserve. But `complete` callback at line 1122 might fire from a different Promise resolution; verify with a probe: open `.run als bucket (fn [] (cljs/eval-str ... (fn [_] (deref-and-print (.getStore als)))))`. Confirm the callback sees the bucket.

2. **Does the warning-handler dispatcher get called from inside the trampoline's `binding` block?** `binding` at js.cljs:1052 establishes `*cljs-ns*`, `*compiler*`, etc — but NOT `*cljs-warning-handlers*`. So our root mutation (today) propagates. With Option 1's "set ONCE at boot" approach, the dispatcher fn is always the active handler; the bucket lookup happens inside the dispatcher. Probe: install dispatcher, run two concurrent `eval-str` calls (manually constructed Promise interleaving), verify each fiber's bucket gets ONLY its own warnings.

3. **Does cljs.js maintain any per-process state besides analyzer cache that could break under concurrent eval?** Quick scan of `js.cljs` `bound-vars` shows `*sm-data*` (source map data), `*compiler*` (the compile-state atom — shared, but datahike-style swap-only). Anything that mutates `@compile-state` from one fiber while another is reading is a race. Worth a separate concurrency audit if multi-agent moves into the critical path.

4. **`init-version` rotation: what's the actual time-cost of a fresh `init-bootstrap!`?** If it's <100ms, `pause-and-reload!` is trivial; if it's >1s, we should think about preloading the next state in the background.

5. **Does the prior research's recommended "walker bypasses eval-batch!" still hold given A5?** If A5 lands, eval-batch! no longer mutates `!current-ns` — so replay-through-eval-batch is safer (no atom drift). But the per-form `:seon.eval` write is still pollution. Stick with walker → `seval/eval` directly.
