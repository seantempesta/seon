---
type: research
status: active
tags: [research, sci, falsification]
---

# Sci interrupt ground truth: the fork is fine, Seon's arming is not

## Verdict

**The owner's doubt is correct, and the filed blocker's recommended fix is
wrong.** The prior lane's observed SYMPTOMS reproduce exactly — a function
defined in eval 1 runs unbounded when called in eval 2, and `acquire!`-installed
functions are unbounded — but its mechanical account ("runs with NO limit",
"acquire!-installed fns are unguarded") is imprecise in the way that matters,
and its conclusion (entrance-time ThreadLocal indirection **inside the sci
fork**) is unnecessary. The cross-eval function IS guarded: sci calls the
closed-over `interrupt-fn` on every entrance, faithfully. What it calls is a
**stale, deliberately-disarmed** closure of Seon's own making — `arm`'s
scheduled task was cancelled by `stop!`, so its flag can never flip true again.
Proof that this is Seon's defect and not sci's: the pattern in the fork's own
`doc/interrupt.md:26-33` **does** bound a cross-eval function (probe C fires at
0 ms), and a stable per-run guard fixes both escape paths with **zero changes to
the fork** (probes H, I, P, Q). No fork modification is warranted. One new,
independent Seon bug was found along the way (§6): `interrupted?` does not walk
the cause chain, so most real time-limit interrupts are misrecorded as ordinary
agent errors.

## 1. Every runtime site that consults an interrupt signal

There are exactly **two** consultation paths in the fork, and they have
different timing semantics. This distinction is the whole story.

### Path 1 — interpreted `fn`/`loop` entrance: closed over at CREATION

`reference-code/sci/src/sci/impl/fns.cljc` generates every interpreted function.
In all three generators the read is a `let` binding placed **outside** the
returned `fn`:

- `:39-40` and `:52` (arity-0), `:63-64` and `:77` (arity-n), `:151-152` and
  `:166` (the 20+/loop default).

```clojure
`(let [recur# recur
       interrupt-fn# (:interrupt-fn ~'ctx)]     ; read ONCE, at fn creation
   (fn arity-0 []
     ...
     (loop []
       (when-not (nil? interrupt-fn#) (interrupt-fn#))   ; entrance check
       ...)))
```

The value read is a **record field**, not a map entry: `Ctx` is a `defrecord`
with `interrupt-fn` declared as a positional field
(`src/sci/impl/opts.cljc:203-217`, with the comment explaining that fields are
used because non-field `assoc` rebuilds the extmap on a hot path). `->ctx`
(`:219-227`) and `merge-opts` (`:300-303`) are the only constructors.

Consequences, all verified:

- The check is **not** a dynamic var, volatile, ctx re-read, or
  `Thread/interrupted` poll. It is a plain closed-over local.
- The `ctx` used to evaluate the body (`types/eval ~'body ~'ctx ...`,
  `:53`, `:78`, `:167`) is likewise the **creation-time** ctx.
- `sci/fork` is `(update ctx :env #(atom @%))` (`src/sci/core.cljc:318-323`) —
  a record `update`, so it **preserves** `interrupt-fn` and every other field.
- `(assoc ctx :interrupt-fn f)` therefore always yields a **new** `Ctx` object.
  Probe E: `sci.impl.opts.Ctx`, `identical?` to the source ctx = `false`. A
  record is immutable, so there is no way to arm a ctx a function has already
  captured. This is by construction, not an oversight.

### Path 2 — opt-in core overrides: read LIVE at CALL time

`src/sci/interrupt.cljc:16-18` states it outright: "Each override reads
`:interrupt-fn` from the current context at call time". `get-interrupt-fn`
(`:25-30`) is `(:interrupt-fn ctx)`, and the overrides obtain the ctx from the
dynamic store, e.g. `sci-range` (`:57-65`) calls `(store/get-ctx)` per
invocation. The store is bound to the **live per-eval ctx** by
`src/sci/impl/interpreter.cljc:82` — `(store/with-ctx ctx ...)` — where
`sci.ctx-store/*ctx*` is an ordinary dynamic var (`src/sci/ctx_store.cljc:9-13`).

Verified (probe M/N): with `{'clojure.core si/clojure-core}` and an armed ctx,
`(reduce + (range))` throws `core-override`; the same override on an unarmed
fork returns `10` normally. So the fork **already has** a live-read interrupt
path — it simply is not the one used for interpreted fn entrances, and for good
reason (a dynamic-var deref on the hottest path in the interpreter).

### The interrupt signal itself

`interrupt!` (`src/sci/interrupt.cljc:32-41`) throws an `ex-info` carrying a
private marker: `(assoc data :sci.impl/interrupt utils/interrupt-marker)`. The
marker is an identity object (`src/sci/impl/utils.cljc:47`) with
`interrupt-ex?` as its reader (`:51-56`). `eval-try`
(`src/sci/impl/evaluator.cljc:81`, `:129-172`) refuses to hand a marked
exception to user `catch` clauses, and when `:interrupt-fn` is configured runs
`finally` itself so a throwing `finally` cannot mask a pending interrupt
(the `#1044` fix). When no `:interrupt-fn` is set it takes a plain host
try/catch path that costs nothing (`:129-133`).

### Fork history — the design record

`git -C reference-code/sci log -- src/sci/interrupt.cljc doc/interrupt.md`:

| Commit | Meaning |
|---|---|
| `aac6078` | **Optional interruption support (#1039)** — the original feature: the per-fn-entry hook and `:interrupt-fn` option. |
| `8aa4836`, `08e13a6` | docs (`doc/interrupt.md`). |
| `aa6895b`, `9fa8e46` | regex + more interruptible core functions (JVM) — the `sci.interrupt` override namespace, i.e. Path 2. |
| `f9e2731` | **`sci.interrupt/interrupt!` for uncatchable interrupts (#1044)** — the private marker and the `finally` handling. |
| `68b2e46` | message casing. |

Nothing in that history ever promised that arming a ctx retroactively bounds
functions created earlier. The feature is documented as "called on every `fn`
body entry" (`doc/interrupt.md:6-8`) and the doc's own examples all create the
interrupt-fn and the code in one `eval-string` call.

## 2. Independent reproduction — what actually happens

`tmp/sci_interrupt_ground_truth_probe.clj`, `clojure -M:dev`, 500 ms limit,
written from the source reading above before opening the prior lane's script.
Probe B/D replicate Seon's `arm` shape exactly
(`src/seon/sci/eval.clj:246-282`): a `ScheduledThreadPoolExecutor` task flips a
flag at the deadline; the interrupt-fn does **no** clock arithmetic; `stop!`
**cancels** the task.

```
A same-eval armed:            interrupted, msg "time-limit", 505 ms
B cross-eval SEON arm:        NOT interrupted, ran to completion, 1244 ms
C cross-eval DOC time-limit:  interrupted, msg "doc-time-limit", 0 ms
D acquire!-shape unarmed:     NOT interrupted, ran to completion, 1047 ms
E eval-ctx type: sci.impl.opts.Ctx | identical to run-ctx? false
```

**B and D reproduce the prior lane's symptoms.** **C is the discriminator.**
Both B and C call a function defined in a previous eval on the same run ctx. C
uses the interrupt-fn the fork's own documentation prescribes — one that reads
the clock on each call — and it fires **immediately** (0 ms), because eval 1's
deadline is long past. So:

- The entrance check **is** running for cross-eval functions. The function is
  not "unguarded".
- The difference is entirely in **what Seon's interrupt-fn does**. Seon moved
  the clock read out of the interrupt-fn (a sound optimisation) into a
  scheduled task, and then cancels that task on disarm. The result is a closure
  that is called on every entrance and always answers "not expired" — a
  permanently disarmed guard.

The three candidate diagnoses the task named, resolved:

| Candidate | Verdict |
|---|---|
| "the interrupt-fn closure is stale" | **True but incomplete.** It is stale AND affirmatively disarmed by `stop!`. Staleness alone would have caused an immediate interrupt (probe C), not a hang. |
| "read live, but Seon armed a different ctx object" | **False as stated.** The value is genuinely not re-read. But it is true that `assoc` produces a different object (probe E), and that is why no arming can reach an existing fn. |
| "Thread/interrupt-based and works fine" | **False.** No thread-interrupt path exists in the entrance check. |

### Why D (acquire!) is the same bug in a starker form

The run loop forks one ctx per run (`src/seon/cluster/loop.cljc:967`) and calls
`acquire!` on it (`:970`) before any evaluation arms anything. That ctx's
`interrupt-fn` field is `nil`, so every re-created agent function closes over
`nil` and the entrance check is skipped by `(when-not (nil? interrupt-fn#) ...)`
forever. Here the prior lane's word "unguarded" is exactly right.

## 3. The fix is in Seon, not in the fork

Hypothesis: if Seon's interrupt-fn were a **stable object per run** that
consults a *mutable* flag rather than a per-eval closure that gets cancelled,
every function the run ever creates would capture the same live guard and the
existing sci mechanism would work unchanged.

`tmp/sci_interrupt_fix_probe.clj` — one stable `interrupt-fn` installed on the
run ctx *before* any `defn`; `arm!` schedules the flip, `disarm!` cancels the
task **and clears the flag**:

```
H stable-guard cross-eval:        interrupted, 502 ms
I stable-guard acquire!-shape:    interrupted, 501 ms
J after disarm, short work:       result 3 (no spurious interrupt)
K armed run:                      interrupted, 303 ms
K sibling run (concurrent thread): ran to completion, 708 ms
F fork cost:                      50 ns
```

Both escape paths close. Disarm is exact. Sibling isolation holds: two run ctxs
with two guards on two threads, one armed at 300 ms and one at 60 s — the armed
one is interrupted and the sibling is untouched. **No change to
`reference-code/sci` was made or needed.**

## 4. Shapes, cost, and the recommendation

Entrance cost was measured directly (interpreted `loop` + `step` call,
10 M entrances):

| Interrupt-fn body | ns/entrance | Note |
|---|---|---|
| interpreter baseline (no interrupt-fn) | **8.3** | 83 ms / 10 M — confirms the ~7.8 ns figure in circulation |
| thread-scoped flag, correctly type-hinted | **+1.3** | `ThreadLocal.get` + `AtomicBoolean.get` |
| the same read, **reflective** | **+232** | a 28x tax — see the trap below |

**Trap worth recording:** any reflection inside the interrupt-fn is
catastrophic, because the body runs on every interpreted entrance. An
unhinted `(.get some-def)` measured 232 ns/entrance against 1.3 ns hinted.
Seon's current `arm` is reflection-free (`long-array` locals infer `[J`), and
any replacement must stay that way and be checked with
`*warn-on-reflection*`.

### S1 — one stable guard per run, single flag

Install one `interrupt-fn` object on the run ctx at fork time, before
`acquire!`; `arm!`/`disarm!` flip and clear one flag.

- Sites: `src/seon/sci/eval.clj:246-282` (`arm` becomes run-scoped),
  `:763-764` (stop assoc'ing a per-eval interrupt-fn),
  `src/seon/cluster/loop.cljc:967-970` (install before `acquire!`).
- Cost: ~1 ns/entrance. Fork semantics: safe — one guard per run ctx,
  siblings isolated (probe K).
- Residue: the flag is run-scoped, so if one run's functions are ever invoked
  on a second thread concurrently with that run's own eval (a renderer, which
  is precisely the ruled use case), both share one flag.

### S2 — one stable guard per run, THREAD-SCOPED flag (recommended)

Same, but the stable interrupt-fn reads the executing thread's own flag.
`tmp/sci_interrupt_threadlocal_probe.clj`:

```
P thread-guard, unarmed-defined fn:  interrupted, 401 ms
Q armed thread:                      interrupted, 405 ms
Q unarmed thread (same ctx, same fn object): ran to completion
R cost: +1.3 ns/entrance (hinted)
```

- Same three edit sites as S1, plus the flag becomes a `ThreadLocal`.
- Arming is exact per executing thread, so a renderer invocation and the run's
  own eval can carry independent limits while sharing one ctx and one guard
  object. Disarm exactness verified (J). Sibling-fork isolation is inherited
  and strengthened.
- **Recommended.** It costs the same as S1, removes S1's only residue, and is
  the shape the ruled renderer design needs.

### S3 — modify the fork (entrance reads `store/get-ctx`)

Change `fns.cljc:40,64,152` to read the interrupt-fn from the dynamic ctx-store
at entrance instead of closing it over.

- **Rejected.** It puts a dynamic-var deref on the interpreter's hottest path
  for every user of the fork; it diverges the fork from upstream for no gain;
  and probes H/I/P/Q show the requirement is fully met without it. Path 2
  already demonstrates live reads where they are affordable.

## 5. Corrections to the filed issue

`docs/seon/issues/sci-time-limit-does-not-bind-previously-defined-functions.md`
(reported here, not edited):

1. **Keep** the two escape paths, the `severity: blocker`, and both file:line
   citations — all verified. The issue body is notably more accurate than the
   research doc's headline: it already names the cancelled-task reason.
2. **Correct the framing.** The title and the phrase "Form 2 calling `(f)`
   spins with no limit" should say the function runs with a **stale,
   disarmed** limit, not with none. The distinction is load-bearing: it is
   what makes this a Seon defect rather than a sci one.
3. **Retract the recommended fix.** Entrance-time ThreadLocal indirection
   *inside the fork* is not required. The fix is a stable per-run guard in
   `seon.sci.eval` + `seon.cluster.loop` (S2), with no fork change.
4. **Re-scope.** This is not a fork blocker. It blocks the renderer-through-
   the-door ruling only until S2 lands, which is a bounded change at three
   sites in first-party code.
5. **Add** the `interrupted?` cause-chain defect below as a separate issue —
   it is independent and currently corrupts the evidence for this one.

Similarly, §0 of
`docs/prds/sci-execution-runtime/research/sci-door-ctx-sharing-2026-07-31.md`
("The time limit does not survive the function that was defined under it")
should be amended: the *limit* survives as a live call, the *arming* does not.

## 6. New independent defect — `interrupted?` misses wrapped interrupts

Found while probing. When the interrupt fires **inside an interpreted function
call** (as opposed to a bare top-level `loop`), sci's
`rethrow-with-location-of-node` (`src/sci/impl/utils.cljc:121-151`) wraps it in
a `:sci/error` `ex-info` to attach location and callstack. Observed chain:

```
0 clojure.lang.ExceptionInfo | msg: time-limit | ex-data: (:type :line :column :message :sci.impl/callstack :file)
1 clojure.lang.ExceptionInfo | msg: time-limit | ex-data: (:sci.impl/interrupt)
```

The marker is on the **cause**, not the top-level throwable. Seon's
`interrupted?` (`src/seon/sci/eval.clj:213-221`) reads only the top level:

```clojure
(contains? (ex-data throwable) :sci.impl/interrupt)
```

so it returns `false` for the common case. Consequences: `src/seon/sci/eval.clj:907`
records `:seon.eval/outcome :error` instead of `:time`, and
`src/seon/sci/admit.clj:269` fails to recognise the interrupt. This is visible
in probes A, H and I (`:direct-marker? false, :cause-marker? true`) and hidden
in probe K only because the spin there had no interpreted call frame.

Fix: walk the cause chain, using sci's own reader rather than the raw key —
`sci.impl.utils/interrupt-ex?` is the marker's owner (`:51-56`). This stays one
owner for the question, as the docstring intends.

## 7. Secondary claims verified

**Fork cost.** Measured **50 ns** over 100 000 forks after warmup (probe F).
The prior lane's 72 ns is the same order; both confirm forking is cheap and the
base/fork split is sound. `sci/fork` is `(update ctx :env #(atom @%))`
(`src/sci/core.cljc:318-323`) — it copies the env atom only.

**"Fork isolates new names only; base-Var re-defs leak to siblings."**
**Confirmed** (probe G). With `(defn shared [] :base)` in the base, then two
forks `f1`/`f2`, and `f1` defining `brand-new` and redefining `shared`:

```
G new name in sibling:          Unable to resolve symbol: brand-new   (isolated)
G base-Var redef in sibling:    :redefined                            (LEAKED)
G base-Var redef in base:       :redefined                            (LEAKED)
```

A Var that exists in the base is a shared object; `fork` copies the env map but
not the Vars it points at, so `bindRoot` on an inherited Var mutates state every
sibling and the base observe. New names go into the fork's own env atom and stay
local. The queued base-Var isolation ruling rests on a correct premise.

## Probe inventory

All load-only (`clojure -M:dev`), no cluster and no database writes.

- `tmp/sci_interrupt_ground_truth_probe.clj` — A–G: baseline, cross-eval under
  Seon's arm, cross-eval under the doc's arm, acquire! shape, ctx identity,
  fork cost, fork isolation.
- `tmp/sci_interrupt_fix_probe.clj` — H–L: stable-guard fix, disarm exactness,
  cross-thread sibling isolation, entrance cost, interrupt detectability.
- `tmp/sci_interrupt_threadlocal_probe.clj` — P–R: the recommended
  thread-scoped shape and its per-thread exactness.
- Inline probes (recorded in §1, §4, §6): ctx-store live-read of the core
  overrides; the exception cause chain; the hinted-vs-reflective entrance cost.
