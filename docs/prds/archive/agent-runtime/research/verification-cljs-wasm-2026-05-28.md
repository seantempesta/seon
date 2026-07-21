---
type: research
status: draft
tags: [research, agent, wasm]
---

# Verification: cljs.js-in-wasm readiness for a real Seon agent (2026-05-28)

Skeptical falsification run. Two agents converged on this file: one captured the
full eval battery against the **original May-21 green artifact**; one (and the
same author, post-rebuild) captured the **hang of the rebuilt artifact**. Both
bodies of evidence are preserved below — they are not in conflict, they describe
two different binaries.

## TL;DR

**No, not as-is — yes-with-substantial-caveats once two correctness defects are
fixed, the timeout layer is added, and the build is made reproducible.** The
prior "GREEN" claim was real but shallow: it exercised only function-call forms
(`+`, `reduce`, `map`, `str/upper-case`) that dodge every failure mode. Four
load-bearing findings:

1. **The most common Clojure forms are BROKEN even in the green artifact.**
   `(str 1 2 3)`, `defrecord`, `defprotocol`, `defmulti`, `deftype` all fail
   with `ReferenceError: clojure is not defined` during macroexpansion. Root
   cause isolated in the bundle (see below). A real agent writing `(str ...)`
   — nearly every program — hits this on form one.

2. **`(require ...)` of any not-pre-warmed namespace HARD-PANICS the instance**
   (`wstd block_on: ready list empty` → wasm `unreachable` trap). `seon.schema`
   and `malli.core` are bundled (JS + analyzer cache on disk) yet requiring them
   kills the guest, not returns an error.

3. **No per-eval timeout. A catastrophic-backtracking regex hangs 121s** (measured
   `(re-find #"(a+)+$" "aaaa…X")` → 121.62s). Gemini's regex fear is REAL.

4. **The build is not reproducible — a fresh rebuild hangs forever on init.**
   Rebuilding the *unmodified baseline* (`clj -M:cljs release eval-smoke` +
   `./build-eval-smoke`) produces a wasm that times out (rc 124) with empty
   stdout/stderr and ~1.25s CPU over 180s wall on `(+ 1 2)` — parked, not
   computing. Current `wstd 0.6.5` + wasm-rquickjs under `wasmtime 44.0.1`
   deadlock during `init-bootstrap` (the exact MEMORY.md "wstd hangs on
   setTimeout-based parking" mode). The green 13MB artifact predates these pins,
   was gitignored, and `build-eval-smoke` `rm -rf`'d its build dir — so it is
   **currently unrecoverable**. This blocked verifying the macro fix.

Gemini's OOM/no-JIT/multi-second-compile fears did **NOT** materialize.

---

## Environment

- `wasmtime 44.0.1 (f302ebd6b 2026-04-30)`, macOS arm64 (Darwin 24.6.0).
- Bootstrap preopen: `/Users/sean/src/seon/out/bootstrap` (`ana/`, `js/`, `src/`,
  `index.transit.json`) mounted as `bootstrap`. Binary imports `wasi:http` so
  `-S http=y` is mandatory (without it the linker fails before any JS runs).
- Invocation: `wasmtime run -S http=y --dir out/bootstrap::bootstrap --invoke '<fn>' <wasm>`.
- Each `--invoke` is a fresh component instance — multi-form-in-one-instance
  state needs `eval-batch`; warm-vs-cold across invokes is not measurable from
  the CLI.

---

## PART A — Battery against the original GREEN artifact (verbatim)

These ran against `eval-smoke-build/target/wasm32-wasip2/release/eval_smoke.wasm`
(the May-21 13MB binary) BEFORE it was overwritten by today's rebuild. All
outputs verbatim.

### Test 1 — Error handling fidelity: PASS (2 semantic caveats)

| Form | Verbatim result |
|------|-----------------|
| `(this-does-not-exist 1 2)` | `{:ok false, :error "cannot read property 'call' of undefined"}` |
| `(+ 1 2` (unbalanced) | `{:ok false, :error "reader-error: Unexpected EOF while reading item 3 of list."}` |
| `(throw (js/Error. "boom"))` | `{:ok false, :error "boom"}` |
| `(this-fails) (def ok 99) ok` | `[{:ok false ...} {:ok true, :value-edn "#'cljs.user/ok"} ...]` — **recovery works, instance survives** |

Caveats (CLJS self-hosted semantics, not wasm):
- `(+ 1 :keyword)` → `{:ok true, :value-edn "\"1:keyword\""}` — type error is a
  WARNING + JS string-coerce, returns `:ok true`. No failure signal to agent.
- `(defn f [x] x) (f 1 2 3)` → arity is only a WARNING; `(f 1 2 3)` returns `1`,
  `:ok true`. Silent arity bugs.

### Test 2 — Macro/type surface: MIXED — major breakage

WORKS: destructuring (`3`), `case` (`:two`), `cond->` isolated (`2`), `(take 5 (range))`
(`(0 1 2 3 4)`), `for` (`(11 21 12 22 13 23)`), `loop/recur` isolated (`3`),
atoms (`@a` → `2`), transients (`[1 2 3]`), `and`/`or`/`->`/`when-let`/`assert`.

**BROKEN** — all fail `ReferenceError: clojure is not defined` at `:macroexpansion`:

| Form | Verbatim error |
|------|----------------|
| `(str 1 2 3)` | `{:ok false, :error "#error {... :clojure.error/symbol cljs.core$macros/str, :cause #object[ReferenceError ReferenceError: clojure is not defined]}"}` |
| `(defrecord Point [x y]) ... (str p)` | `->Point`/`map->Point` ok, `(:x p)` → `1`, but `(str p)` fails (str macro) |
| `(defprotocol Greet ...) (extend-protocol ...)` | `{:ok false, :error "core is not defined"}` then cascade |
| `(defmulti area :shape) (defmethod ...)` | `{:ok false, :error "...exists? ... clojure is not defined"}`; then `No protocol method IMultiFn.-add-method defined for type undefined` |

Proof it's the MACRO not the fn: `(apply str [1 2 3])` → `"123"` WORKS; `(cljs.core/str 1 2)` FAILS identically to `(str 1 2)`.

### Test 3 — Sustained eval / OOM: PASS — no OOM, linear

| Workload | Time (incl. ~3.3s cold) | Result |
|----------|------|--------|
| 200 sequential defns | 6.53s | 200 `:ok true`, 0 false |
| 500 sequential defns | 11.22s | 500 ok, 0 false |
| 1000 sequential defns | 19.46s | 1000 ok, 0 false, no degradation |
| `(vec (range 1000000))` + `(reduce + big)` | 4.10s | `1000000`, `499999500000` |
| `(reduce + (range 10000000))` | 6.55s | `49999995000000` |

No OOM, no GC death, linear. Gemini's OOM prediction did not materialize.

### Test 4 — Warm per-eval latency: PASS

- Cold (`eval-batch("nil")`): 3.27s real.
- 50 trivial `(+ i 1)`: 3.40s total → **~2.6ms/eval warm**.
- 200 trivial: 3.73s total → **~2.3ms/eval warm**.
- **defn (real agent code): ~16ms each** ((6.53−3.27)/200, (19.46−3.27)/1000).
  Sub-second per form. Gemini's "multi-second per form" is FALSE.

### Test 5 — cljs.test inside wasm: PASS

```
(require '[cljs.test :refer-macros [deftest is run-tests]])
(deftest my-test (is (= 4 (+ 2 2))))
(run-tests)

```
Verbatim stdout: `Testing cljs.user / Ran 1 tests containing 1 assertions. / 0 failures, 0 errors.`
All three forms `:ok true`. Works because cljs.test is force-loaded at init.

### Test 6 — Seon-flavored forms: FAIL (hard panic)

`(require '[seon.schema])` and `(require '[malli.core :as m])` both panic:

```
thread '<unnamed>' panicked at wstd-0.6.5/src/runtime/block_on.rs:59:
internal error: entered unreachable code: ready list empty, therefore root task should be ready.
... wasm trap: wasm `unreachable` instruction executed ... eval_smoke.wasm!eval-form

```
Both namespaces ARE bundled (`out/bootstrap/js/seon.schema.js`, `malli.core.js`,
analyzer caches present in `index.transit.json`). The async `boot/load` →
`fs.readFile`-callback path (shadow `bootstrap/node.cljs:141`) cannot resolve
under wstd from inside a running `^:async` eval. The guest DIES — not a clean error.

### Test 7 — Reader stress: MIXED

| Form | Verbatim result |
|------|-----------------|
| `(re-find #"[0-9]+" "abc123def")` | `"123"` |
| `(count "héllo→世界\n\t")` | `10` (unicode + escapes ok) |
| `(get-in {:a {:b {:c {:d {:e 42}}}}} [...])` | `42` (deep nesting ok) |
| `(re-find #"(a+)+$" "aaaa…X")` (30×a + X) | `:ok true` but **121.62s** — catastrophic backtracking, no timeout to stop it |

---

## PART B — The rebuilt artifact hangs (independently verified)

After Part A, the verifier attempted a one-line fix for the `clojure`-hoist bug
and rebuilt. The rebuild path (`build-eval-smoke`) `rm -rf`'d the build dir and
emitted to `pod-build/target/.../eval_smoke.wasm` (13.2MB, 20:43). That binary,
and a **subsequent rebuild from fully-reverted baseline source**, both hang:

| Entry point | Result |
|-------------|--------|
| `init-bootstrap()` | rc 124 @ 150s, stdout `[]`, stderr `[]` |
| `eval-form("(+ 1 2)")` | rc 124 @ 90s / 40s, empty output (baseline AND attempted-fix builds) |
| `eval-batch("(+ 1 2 3)")` | 180.17s real, **1.25s user CPU**, ~141MB RSS, no result |

The 1.25s-CPU-over-180s-wall profile means **parked, not compute-bound** — it
never reaches cljs.js compile. Empty stderr = no error envelope; a stuck agent
gets zero signal. Reverting the one-line change did NOT fix it, proving the
regression is the **toolchain/dep pins** (`wstd 0.6.5` `block_on` waker never
fires under wasmtime 44.0.1), not the source edit. The working green binary
predates these pins and is unrecoverable (gitignored + build dir wiped).

---

## Hard numbers

- Cold bootstrap (good artifact): **~3.3s**
- Warm trivial eval: **~2.3-2.6ms**; warm defn: **~16ms**
- Sustained-eval ceiling: **none hit** (1000 defns, 10M reduce, 1M-vec all OK, linear)
- Catastrophic regex: **121.62s**, no timeout
- Rebuilt artifact: **does not complete `(+ 1 2)`** (≥180s, 1.25s CPU, ~141MB RSS)
- Instance memory under sustained eval: not measured on the good artifact (was
  clearly not breaking); ~141MB RSS observed only during the rebuilt-artifact hang.

---

## What breaks (the honest list a real agent will hit)

1. **`str` macro + `defrecord`/`defprotocol`/`defmulti`/`deftype` → `ReferenceError: clojure is not defined`.**
   Root cause: `src/seon/wasm_eval_smoke.cljs` `init-bootstrap!` hoists
   `goog`/`cljs`/`shadow` to `globalThis` (~lines 66-68) but NOT `clojure`. The
   AOT bundle (`out/eval-smoke/main.js`) declares `var clojure={string:{}}`
   inside shadow's IIFE closure; macro-emitted code runs in global scope via
   `goog.globalEval` and references bare `clojure.*`. **Proposed one-line fix:**
   add `(js* "(typeof clojure !== 'undefined') && (globalThis.clojure = clojure)")`
   beside the existing hoists. **NOT VERIFIED** — the rebuild needed to test it hangs (Part B).

2. **`(require ...)` of a not-pre-warmed ns panics the instance.** Only
   init-loaded namespaces are safe. **Proposed fix:** eagerly load ALL bundled
   namespaces at init (the index has `:js-name` + dep order for every ns; drive
   `boot/load-namespaces` over the full set during init, where the event loop
   drains). Then `require` finds nothing to async-load → synchronous `cb`, no
   panic. NOT VERIFIED.

3. **No per-eval timeout → regex/while-true DoS** (121s observed). The
   `seon.eval` budget/timeout layer (omitted from eval-smoke by design) is
   mandatory before any agent uses this.

4. **Type and arity errors are silent** (`:ok true` + WARNING). Degrades the
   agent feedback loop.

5. **Top-level control-flow return values reported as `nil`.** `(if true 42 0)`,
   `(when true 5)`, `(loop ...)` at top level of a batch return `nil` because
   `eval-str` uses `:context :statement` (`wasm_eval_smoke.cljs:137`). The VALUE
   is computed correctly (`(def r (if true 42 0))` → r=42); only the reported
   top-level result is wrong. Misleading for REPL-style expression eval.

6. **The build is not reproducible — fresh rebuild hangs on init.** Alpha-blocker.
   Pin the wstd/wasm-rquickjs/wasmtime versions that produced the working May-21
   binary, or migrate off wstd's timer parking, before any further iteration.

---

## Gemini's predictions, scored

| Prediction | Observed | Verdict |
|------------|----------|---------|
| No-JIT 20-100x slowdown | 10M reduce 3.3s warm; 1M vec 0.8s; defn ~16ms | **Overblown.** Throughput fine. |
| OOM under sustained eval | 1000 defns / 10M reduce / 1M vec — no OOM, linear | **Did not materialize.** |
| Catastrophic regex backtracking | `(a+)+$` → **121.62s** | **CONFIRMED, severe.** |
| Multi-second compile latency | ~2.5ms trivial, ~16ms defn | **False.** Sub-second. |

Gemini was wrong on the perf/memory thesis (QuickJS handles the CLJS analyzer's
allocation fine) but right on regex. The real blockers are correctness defects
(macro global-scope bug, require panic), the missing timeout, and a
non-reproducible build — not the runtime's compute capacity.

---

## State of the tree after this verification

- `src/seon/wasm_eval_smoke.cljs` — reverted to baseline (attempted `clojure`-hoist
  fix removed; could not verify due to the build hang).
- `out/eval-smoke/main.js` + `out/bootstrap/` — rebuilt from baseline (0 warnings).
- `pod-host/wasm-tauri/eval-smoke-build/.../eval_smoke.wasm` — **now the BROKEN
  rebuild** (hangs on init). Original working May-21 binary is unrecoverable
  (gitignored; build dir `rm -rf`'d by `build-eval-smoke`). Code smell flagged:
  the canonical green artifact had no source-controlled provenance and a
  non-reproducible toolchain.

## wstd async-parking root cause + fix (2026-05-29)

**RESOLVED.** Both the build-hang (Symptom A) and the `require` hard-panic
(Symptom B) are ONE root cause, and it is NOT the wstd/wasmtime pins per se —
it is **`cljs.core.async` being in the bundle at all**. Removing it makes a
fresh, from-scratch rebuild eval `(+ 1 2)` → `3` sub-second AND makes `require`
of a not-pre-warmed ns return cleanly. Proven empirically (verbatim below).

### The exact parking site

`src/seon/wasm_eval_smoke.cljs` required `[datahike.api]`, which pulls in
`cljs.core.async` transitively (1004 refs in the old 7.7MB bundle; 0 in the new
2.6MB one). core.async's task dispatcher delivers via `goog.async.nextTick`:

```
cljs.core.async.impl.dispatch/queue-dispatcher
  -> goog.async.nextTick(process_messages)
       -> goog.global.setImmediate(cb)            ;; nextTick prefers setImmediate

```

Under wasm-rquickjs, `setImmediate` is wired (builtin/timeout.js:154) to:

```
setImmediate(cb) -> scheduleTimeout(cb, 0) -> timeoutNative.schedule(cb, 0, …)
  -> (builtin/timeout.rs:34) ctx.spawn(scheduled_task)
       -> (timeout.rs:101) wstd::task::sleep(Duration::from_millis(0)).await

```

`wstd::task::sleep` registers a `wasi:clocks/monotonic-clock` pollable in wstd's
reactor. The exported WIT fn runs under `block_on` (internal.rs:1758, and init at
1732/1738). Look at `wstd-0.6.5/src/runtime/block_on.rs`:

```rust
loop {
    match reactor.pop_ready_list() {
        None if reactor.pending_pollables_is_empty() => break,   // -> poll root once
        None => reactor.block_on_pollables(),                    // PARK on wasi:io/poll
        Some(runnable) => { … }
    }
}
// after break:
match root_task.poll(noop) { Ready(r)=>r, Pending=>unreachable!("ready list empty…") }

```

- **Symptom A (hang, ~1.25s CPU / 180s wall):** the spawned 0ms timer leaves a
  monotonic-clock pollable *pending*, so `block_on_pollables()` parks in
  `wasi:io/poll::poll`. Under wasmtime 44.0.1 that pollable's waker never drives
  the root task to completion → parks forever at ~0% CPU. (`drain_and_idle`'s own
  1ms sentinel sleep, internal.rs:1691, is the same family of timer.)
- **Symptom B (`require` panic):** same machinery, different drain timing — the
  ready list empties while the root future is still `Pending`, hitting the
  `unreachable!("ready list empty, therefore root task should be ready")` panic
  (block_on.rs:59) → wasm `unreachable` trap. The `wstd block_on: ready list
  empty` message in PART B is this exact line.

Microtask-based async (`fs.readFile`'s `queueMicrotask`, native CLJS
`^:async`/`await`) does NOT touch this path — rquickjs drains microtasks via
`rt.idle()` with no timer pollable. That's why the bootstrap loader's
`fs.readFile` callbacks were never the problem; core.async's `setImmediate` was.

### Why the May-21 green binary "worked" with core.async

It was built against an older wasmtime / wasm-rquickjs where the 0ms
monotonic-clock pollable's waker still fired. wasmtime 44's wasi:io/poll behavior
changed enough that wstd 0.6.5's `block_on_pollables` no longer gets woken for it.
The green binary's success was fragile (toolchain-version-dependent), not robust —
and `wstd 0.6.5 == 0.6.6` byte-for-byte in `src/runtime/`, so bumping wstd is NOT
a fix.

### Fix options + trade-offs

1. **(APPLIED) Drop `datahike.api` from eval-smoke.** eval-smoke is a cljs.js
   bootstrap smoke, not a datahike smoke (M1 already proved datahike-cljs loads).
   Zero core.async → no timer pollable → microtask-only async wstd drives cleanly.
   Effort: trivial (1-line require removal + rebuild). Cost: eval-batch can't run
   datahike-using deftests *in this bundle* until datahike has a core.async-free
   delivery path.
2. **Patch core.async's dispatcher to use `queueMicrotask` instead of
   `setImmediate`/`setTimeout`.** Fixes EVERY core.async-dependent lib (datahike,
   etc.) under wstd at once. `cljs.core.async.impl.dispatch` already has the seam
   (`queue-dispatcher` → `goog.async.nextTick`; `queue-delay` → `setTimeout`).
   Pointing the zero-delay path at `queueMicrotask` (which wstd drains) would let
   datahike back into the bundle. Effort: medium — a CLJS-side shim/patch of the
   dispatch ns, must preserve ordering semantics (nextTick-before-timer) and the
   actually-delayed `(timeout n)` path (which legitimately needs a timer and will
   still park unless the host provides a working timer reactor). Highest leverage;
   recommended as the real fix for getting datahike + agent libs into wasm.
3. **Provide a working wstd timer reactor / newer wasmtime+wstd combo.** Would fix
   the 0ms-sleep waker without touching CLJS. Effort: unknown (toolchain bisect to
   find the wasmtime version where the monotonic-clock waker fires; risk of other
   regressions). Doesn't help the legitimately-delayed timer case differently than
   option 2 and is the least-controlled lever.
4. **Pre-warm all nss at build (wizer).** Dodges Symptom B's runtime `require`
   only; does NOT fix Symptom A (init itself spawns the core.async timer). Rejected
   as a standalone fix.

### Verbatim rebuild result (the gate)

Fresh `./build-eval-smoke` from scratch (bundle rm'd, wrapper regenerated, full
cargo build, 0 warnings in CLJS), with `datahike.api` removed:

```
✓ eval-smoke wasm built: pod-build/target/wasm32-wasip2/release/eval_smoke.wasm
▸ wasmtime --invoke init-bootstrap…
"ok"
▸ wasmtime --invoke eval-form("(+ 1 2)")…
"{:ok true, :value-edn \"3\", :ns cljs.user}"

```

Additional bounded checks on the same binary (`timeout 40 wasmtime …`):

| Form | Result | EXIT |
|------|--------|------|
| `init-bootstrap()` | `"ok"` | 0 |
| `eval-form("(+ 1 2)")` | `{:ok true, :value-edn "3", :ns cljs.user}` | 0 |
| `(require '[clojure.set :as set]) (set/union #{1} #{2})` | `{:ok true, :value-edn "#{1 2}", …}` (Symptom B GONE) | 0 |
| `(require cljs.test) (deftest …) (run-tests)` | `Ran 1 tests … 0 failures, 0 errors.` | 0 |

### Provenance + artifacts

- The May-21 green binary is **unrecoverable** (only the May-28 BROKEN rebuild
  survived; backed up at `pod-host/wasm-tauri/tmp/eval_smoke.BROKEN-20260528.wasm.bak`).
- New working binary backed up: `pod-host/wasm-tauri/tmp/eval_smoke.GREEN-20260529-no-coreasync.wasm.bak`
  (8.1 MB, no core.async). Note: wasm output is not bit-deterministic across runs
  (embedded build metadata), but every fresh build is functionally green.
- Also fixed: `build-eval-smoke` checked for `seon_eval_smoke.wasm` but the crate
  emits `eval_smoke.wasm`, so the script false-failed AFTER a successful build.
  Corrected to the real filename.

### Changes (tree clean, committed-ready)

- `src/seon/wasm_eval_smoke.cljs` — removed `[datahike.api]` require + documented
  the core.async/wstd parking rationale in the ns docstring.
- `pod-host/wasm-tauri/build-eval-smoke` — corrected output wasm filename.
