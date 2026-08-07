---
type: research
status: complete
tags: [research, runtime, sci]
---

# The environment as a value on the SCI ctx — what the vendored fork actually supports

Source read: the vendored SCI fork at `reference-code/sci/` at pin
`2db3358` ("Protect compiled Var metadata from SCI"). Every claim below
carries a `file:line` into that checkout, or into `src/` for the Seon
side. Read end to end: `src/sci/core.cljc`, `src/sci/ctx_store.cljc`,
`src/sci/impl/opts.cljc`, `src/sci/impl/vars.cljc`, `src/sci/impl/fns.cljc`,
`src/sci/impl/types.cljc`, `src/sci/impl/interop.cljc`,
`src/sci/impl/interpreter.cljc`, `src/sci/addons/future.clj`,
`doc/interop-control.md`, plus the relevant regions of
`src/sci/impl/analyzer.cljc` and `src/sci/impl/evaluator.cljc`; and, for the
Seon side, the complete
[ambient injection r2 PRD](../plan/ambient-injection-prd-2026-08-05-r2-draft.md)
and the ctx-construction and turn-fork regions of `src/seon/sci/eval.clj`.

## Verdict first

**The ctx can be the one environment carrier, and it is the only candidate in
SCI that is not thread-local.** The decisive fact is that the ctx is an
immutable value that is *closure-captured* by every interpreted function and
*passed as an argument* to every analyzed node's eval, so it survives any
thread hop that the evaluated code can create. Everything else SCI offers for
"host code reads the app's environment" — `sci.ctx-store/*ctx*`, sci dynamic
vars, Clojure dynamic vars — is thread-local and is exactly the failure the
2026-08-07 audit found.

The one genuine gap is that **host functions installed by `copy-var` receive
no ctx argument** — that is precisely why `sci.ctx-store` exists upstream, and
precisely why Seon currently falls back to `binding [db/*conn* …
effect/*request-context* …]` at `src/seon/sci/eval.clj:1710-1723`. Closing
that gap is the whole job, and this fork already contains the seam for it
(the `wrap` parameter of `return-call`, `analyzer.cljc:1718,1730,1740`).

## 1. What a ctx contains and controls

### Shape

On the JVM the ctx is a `defrecord Ctx` with declared fields `bindings env
features readers reload-all check-permissions interrupt-fn
host-interop-observer built-in-call-observer recur-target params parents
closure-bindings fn-expr` (`opts.cljc:207-219`). The comment above it
(`opts.cljc:203-206`) states why those are fields rather than extmap keys:
`assoc` with a non-field key copies the record *and* rebuilds its extmap.
Additional keys are still legal and are used by SCI itself —
`:allow`, `:deny`, `:reify-fn`, `:proxy-fn`, `:deftype-fn`, `:unrestricted`,
`:main-thread-id` are all assoc'd onto the record in `opts/init`
(`opts.cljc:274-285`). So Seon's `::custody` and `::projection-state`
(`src/seon/sci/eval.clj:1446-1462`) are ordinary extmap keys — supported, but
each one costs an extmap rebuild on every `assoc` of the ctx. A single
`:seon/environment` key holding one map is materially cheaper than N keys.

`:env` is the one mutable cell: an atom holding `{:namespaces :imports
:load-fn :class->opts :raw-classes :ns-aliases :public-class}`
(`opts.cljc:47-63`). Everything else on the ctx is an immutable value.

### What it controls

- **Namespaces and Vars.** `:namespaces` is merged into the env's namespace
  map at init (`opts.cljc:17-40`); `:bindings` is a deprecated alias for
  `:namespaces {'user …}` (`opts.cljc:270-271`, `core.cljc:312`).
- **Host functions.** `copy-var`/`copy-ns` (`core.cljc:76-109`, `477-640`)
  produce `sci.lang.Var`s whose roots are ordinary Clojure functions. **They
  are invoked with evaluated arguments only** — `return-call` generates
  `(f arg0 arg1 …)` and `eval/fn-call` does `(apply f args)`
  (`analyzer.cljc:1718-1748`, `evaluator.cljc:398-420`). *No ctx reaches a
  host function.* This is the single most important structural fact for the
  design.
- **Classes and interop.** `:classes` becomes `:class->opts`
  (`opts.cljc:161-183`), and class resolution reads only that map
  (`interop.cljc:129-186`).
- **Evaluation policy.** `:interrupt-fn`, `:unrestricted`,
  `:host-interop-observer`, `:built-in-call-observer` (`opts.cljc:221-235`).

### What evaluated code can reach that the ctx did not install

Much less than one might fear, and the fork has deliberately tightened it:

- **No arbitrary class loading.** `resolve-type-hint` refuses to call
  `Class/forName`, with an explicit comment that doing so "let untrusted code
  load and static-initialize any class on the classpath"
  (`interop.cljc:244-257`); array descriptors are normalized and gated through
  the same allowlist (`interop.cljc:227-242`).
- **No reflection on an unregistered object.** Instance interop resolves the
  receiver's *runtime* class name against `class->opts` (or `:allow`) and
  throws `"Method … not allowed!"` otherwise
  (`evaluator.cljc:261-271`). So a Datahike connection, a flow work-launcher,
  or an environment record handed into evaluated code as a host object stays
  **opaque** unless its class is registered. `:closed` narrows further
  (`interop.cljc:146-164`, `doc/interop-control.md:1-60`).
- **Default classes are few** (`opts.cljc:101-118`) — note `sci.lang.Var` and
  `sci.lang.Type` are among them, so reflection on Var objects is open by
  default.
- **What IS reachable:** every Var in the fork's own `:namespaces`, via
  `resolve`, `ns-publics`, `ns-interns`, `intern`
  (`namespaces.cljc:1910`, `core.cljc:718-746`), and every ordinary Clojure
  value any installed function returns. **A plain Clojure map is fully
  transparent data.** If any installed function returns the environment map,
  agent code reads the connection straight out of it. That is the reach-around
  to design against, not interop.

## 2. Dynamic vars and thread boundaries

### sci's own dynamic vars are thread-local

`sci.impl.vars/dvals` is a `ThreadLocal` on the JVM (`vars.cljc:39-45`);
`push-thread-bindings`/`pop-thread-bindings` mutate that frame
(`vars.cljc:115-143`). Identical semantics to Clojure: a raw `Thread.`, an
`ExecutorService.submit`, or a virtual-thread hop starts at `top-frame` and
sees root values only.

Conveyance exists but is opt-in and narrow: `binding-conveyor-fn`
(`vars.cljc:166-184`) snapshots the frame into a wrapper fn.
`sci.core/future` (`core.cljc:220-228`) and the `sci.addons.future` install
(`addons/future.clj:8-12,38-47`) use it. Note `future` is **not** in SCI's
default namespaces — it is an addon. `binding-conveyor-fn` *is* exposed to
evaluated code (`namespaces.cljc:1755`).

### `sci.ctx-store/*ctx*` is a Clojure dynamic var

`ctx-store.cljc:9-13` — a plain `^:dynamic` Clojure Var. `eval-form` binds it
around evaluation (`interpreter.cljc:80-83`), and `eval-form*` re-`set!`s it to
the parents/closure-bindings-enriched ctx (`interpreter.cljc:50`).
`reset-ctx!` uses `alter-var-root` (`ctx-store.cljc:15-20`) — a *process-global*
root.

Consequence: `store/get-ctx` works on the evaluating thread and survives
`clojure.core/future` (which conveys Clojure thread bindings), but is `nil` on
a raw thread or a bare executor submit — where it throws "No context found"
(`ctx-store.cljc:32-36`). SCI itself depends on this for a large surface:
`require`, `resolve`, `ns-*`, `intern`, `alter-var-root`, multimethods,
protocols, `deftype`, hierarchies, `read`, `load` (≈70 call sites; see
`namespaces.cljc`, `protocols.cljc:166,258,288`, `hierarchies.cljc:10`,
`load.cljc:37`, `read.cljc:53,67`). **Those built-ins are already broken for
agent code that hops a thread**, independent of anything Seon adds. Fail-closed
(a throw) unless someone has called `reset-ctx!`, in which case they would
silently use the wrong cluster's ctx — Seon must never call `reset-ctx!`, and
does not (`grep` over `src/seon/sci/` finds no reference).

### What the ctx gives that dynamic vars do not

Two mechanisms carry the ctx *without* a thread-local:

1. **Closure capture in interpreted fns.** `fns/fun` closes over `ctx` and the
   generated arity fns evaluate the body with it: `(types/eval body ctx
   invoc-array)` (`fns.cljc:53,78,167`), with the fn's `:interrupt-fn` also
   lifted off the captured ctx at creation time (`fns.cljc:40,64,152`). The
   ctx captured is the *runtime* ctx present when the fn-creation node ran,
   because `fns/fun` is called inside a `->Node` body (`analyzer.cljc:363-370`,
   `398-420`), and on the JVM `->Node` expands to
   `(reify Eval (eval [this ctx bindings] body))` (`types.cljc:264-273`) —
   `ctx` there is the node's *argument*.
2. **Argument threading through nodes.** `t/eval` is always called as
   `(t/eval node ctx bindings)`; every analyzer node passes its own `ctx`
   argument down. The chain's root is `eval-form*`, which passes exactly the
   ctx you handed `sci/eval-form` (`interpreter.cljc:47-62`,
   `core.cljc:412-414`).

So: **whatever value is on the ctx you pass to `sci/eval-form` is visible, on
any thread, to every interpreted function created during that evaluation and
to every analyzed node.** An interpreted closure handed to a virtual-thread
executor still evaluates against its captured ctx. This is not theory — it is
how `:interrupt-fn` already reaches every fn body entrance in Seon.

### The fork model

`sci/fork` is one line: `(update ctx :env (fn [env] (atom (assoc @env
:sci/generation (utils/next-generation)))))` (`core.cljc:331-337`), with the
generation a fresh gensym (`utils.cljc:356-357`). Two properties matter:

- **All other ctx keys are carried through unchanged**, and the fork is a
  distinct immutable value, so `(assoc fork :seon/environment env)` cannot be
  observed by the parent or by a sibling fork. Per-fork environment values are
  free.
- **Var mutation is copy-on-write across generations.** `utils/bind-root!`
  copies an inherited Var before mutating and interns the copy into the fork's
  own namespace map (`utils.cljc:362-379`); `eval-def` applies the same
  generation test (`evaluator.cljc:38-52`); the agent-facing `alter-var-root`
  routes through `bind-root!` (`namespaces.cljc:816-840`,
  `1717-1718`); built-in Vars and namespaces are read-only outside
  `:unrestricted` (`vars.cljc:24-33,282-293`). Fork commits `72150fd`
  ("Make forked Vars copy on write") and `6de1568` ("Mark shared stock Vars
  read-only") are ours.

Net: a fork cannot write a value into a Var that another fork reads. **The
only cross-fork escape left is a closure or object escaping through something
outside SCI's Var graph** — a database blob, a shared atom, a channel, a
returned value stored on the desk. That is the containment obligation.

## 3. Is there an idiomatic "ctx carries the environment" pattern?

Yes, and it is `sci.ctx-store` — and it does not fit Seon.

The upstream pattern is: build one ctx, `reset-ctx!` it into the global root,
and have every host shim call `(get-ctx)`. Babashka is the canonical example:
`(defn ctx [] (ctx-store/get-ctx))` in `babashka/impl/common.clj:10`, set once
via `ctx-store/reset-ctx!` at `babashka/main.clj:1003`, and read from host
shims such as `babashka/impl/clojure/core.clj:159`. SCI's own
`sci.core/alter-var-root` reads `store/*ctx*` the same way
(`core.cljc:249-257`), and `sci.interrupt` reads `:interrupt-fn` off
`(store/get-ctx)` in ~25 places (`interrupt.cljc:60-279`).

That works for babashka because **there is exactly one ctx per process and one
evaluation thread**. Seon has many clusters per JVM, a fresh generation-aware
fork per turn, and evaluation that legitimately hops threads. A global root is
wrong (wrong cluster); the thread-local `binding` form is exactly the audited
failure. So: the idiomatic upstream pattern is the wrong pattern here, and the
fork must supply the ctx to host shims *as an argument* instead.

SCI's own avoidance of global state is otherwise good: all mutable state lives
in the ctx's `:env` atom, per-ctx. The two genuine globals are
`ctx-store/*ctx*` and the `dvals` ThreadLocal.

## 4. The call-time hook: how it gets the ctx

**The seam already exists in this fork.** `return-call` takes a sixth
parameter `wrap`:

```clojure
~'[ctx expr f analyzed-children stack wrap]       ; analyzer.cljc:1718
…
((~'wrap ~'ctx ~'bindings ~'f) ~@args)            ; analyzer.cljc:1730
(eval/fn-call ~'ctx ~'bindings (~'wrap ~'ctx ~'bindings ~'f) …) ; :1740
```

`wrap` is an analysis-time-supplied function of `(ctx bindings f)` invoked at
**call time**, returning the callable actually invoked. It is already used for
binding-position calls (`analyzer.cljc:1540-1546`), self-recursive calls
(`analyzer.cljc:2112-2117`), CLJS var deref, and computed callees
(`analyzer.cljc:2155-2163`). Because these expansions sit inside `->Node`
bodies, the `ctx` handed to `wrap` is the **runtime ctx argument**
(`types.cljc:264-273`) — i.e. the fork's ctx, carried by closure/argument, not
by any thread-local.

That answers Q4 directly: **a call-preparation hook gets the ctx for free, as
an ordinary argument, with the right per-fork identity, on whatever thread the
call happens.** Providers can read `(:seon/environment ctx)` at call time.

Obstacles, concretely:

1. **The common path passes `wrap` as `nil`.** Direct Var and direct fn calls
   on the JVM supply `nil` (`analyzer.cljc:2085-2091`, `2098-2104`,
   `2146-2154`). Installing a general hook means changing `return-call` (or
   its callers) so that a ctx-installed hook is consulted — a fork change, as
   the r2 PRD already anticipates.
2. **`wrap` replaces the callee, it does not transform arguments.** The
   generated arities evaluate args inline and apply them positionally
   (`analyzer.cljc:1725-1741`). Argument *preparation* (P17's actual
   requirement) needs either a wrapper closure built per call site that
   re-shapes args, or a new hook shape. The existing `wrap` proves the plumbing
   works; it is not sufficient as-is.
3. **Observers are read from the ANALYSIS ctx, not the runtime ctx.**
   `(:built-in-call-observer ~'ctx)` at `analyzer.cljc:1719` is evaluated
   inside `return-call` itself, i.e. during analysis, and the resulting node
   closes over that observer. If Seon ever caches or shares analyzed nodes
   across forks, the baked observer would be the wrong fork's. Any new hook
   must be read from the **runtime** ctx inside the node body, not from the
   analysis ctx, or it inherits this bug. This is a real, present hazard in
   the fork's own `built-in-call-observer`.
4. **Arity specialization.** `gen-return-call` generates 20 specialized
   arities plus fused/constant-folded fast paths (`analyzer.cljc:1690-1748`).
   A hook check must be a nil test on a captured value, not a map lookup per
   call, or it taxes every call in the system.
5. **Host functions still receive no ctx.** The hook can *prepare arguments*
   for a host function, but the host function body itself still cannot read
   the ctx. For capability leaves that need the environment as a whole (not as
   an injected argument), the hook must inject it *as an argument*, or the
   leaf must be installed as a closure over the environment (see below).

## 5. Verdict — how to make "the environment is a value on the fork" the ONE mechanism

The soundest construction, in dependency order:

**(a) One key, one value.** Put a single `:seon/environment` map on the ctx
(replacing the current `::custody` / `::projection-state` / assorted keys), so
`assoc` costs one extmap rebuild (`opts.cljc:203-206`) and there is exactly
one thing to derive, one thing to prove present, and one thing to audit. Set
it on the per-turn fork, never on the shared base — `fork` returns a distinct
immutable value (`core.cljc:331-337`), so forks cannot see each other's.

**(b) Two carriers, both argument-passed, no thread-locals.**

- *Interpreted code:* nothing to do. Closure capture (`fns.cljc:53,78,167`)
  and node argument threading (`types.cljc:264-273`) already carry the fork's
  ctx across any thread the agent creates.
- *Host capability leaves:* deliver the environment as an **argument**, via a
  runtime-ctx-reading call-preparation hook in `return-call`
  (`analyzer.cljc:1718-1748`) — the P17 seam. Read the hook from the node's
  runtime `ctx` argument, not the analysis ctx, to avoid the
  `built-in-call-observer` staleness described above.
- *Fallback where the hook cannot apply* (a callable passed as a value with no
  Var identity): install that leaf **per fork as a closure over the
  environment** rather than as a shared `copy-var`. Copy-on-write
  (`utils.cljc:362-379`) guarantees the fork's Var is its own.

**(c) Delete the dynamic vars in the same change.** `seon.db/*conn*` and
`seon.effect/*request-context*` (bound at `src/seon/sci/eval.clj:1710-1723`)
must go, not coexist. As long as a provider *can* read a thread-local, a
thread hop silently degrades to the wrong environment instead of failing.

**(d) What must be true for nothing to observe a different environment.**
Five falsifiable invariants:

1. **No installed function ever returns the environment, or any value
   transitively containing it.** Plain Clojure data is fully transparent to
   evaluated code; interop opacity (`evaluator.cljc:261-271`) protects host
   objects only. This is the single highest-risk invariant. Enforce it at the
   admission seam, and give the environment a host record type that is *not*
   registered in `:classes`, so even a leak is inert.
2. **Never call `sci.ctx-store/reset-ctx!`.** A process-global root
   (`ctx-store.cljc:15-20`) would give every off-thread `get-ctx` — including
   SCI's own `require`/`resolve`/`intern` (`namespaces.cljc`, ~70 sites) — some
   other cluster's ctx instead of an honest throw. Add a regression asserting
   `sci.ctx-store/*ctx*`'s root is `nil`.
3. **No closure created in fork A is callable from fork B.** SCI's own Var
   graph guarantees this (copy-on-write, `utils.cljc:362-379`;
   `sci-alter-var-root`, `namespaces.cljc:816-840`; read-only stock Vars,
   `vars.cljc:282-293`). The gap is outside SCI: desk rehydration, database
   blobs, shared atoms, channel payloads. Every one of those must round-trip
   through *source*, re-evaluated in the receiving fork, never through a live
   function object. A fn object crossing a turn boundary is the bug class.
4. **Every hook reads the runtime ctx.** Any value lifted from the *analysis*
   ctx into a node (today: `:built-in-call-observer` at `analyzer.cljc:1719`;
   `:interrupt-fn` at `fns.cljc:40`) is pinned to the fork that analyzed it.
   That is correct only while analysis and execution share a fork — which is
   true today and would silently break under any node cache.
5. **The environment is required, never defaulted.** A missing
   `:seon/environment` must be a flat `:seon.error` value at the door, not a
   `nil` that a provider papers over. Same rule the r2 PRD already sets for
   unavailable ambients.

**What we do NOT need:** a second registry, a ctx-store variant, per-agent
interpreter contexts, or any conveyance machinery. SCI already conveys the ctx
correctly by construction; the fix is to stop reading environment state from
anywhere else.

## Open question worth an owner ruling

`binding-conveyor-fn` is exposed to evaluated code (`namespaces.cljc:1755`),
and the `future` addon is opt-in (`addons/future.clj:38-47`). If agents can
create threads at all, sci-var conveyance is a second, partial, thread-local
environment path. It should either be removed from the agent surface or
explicitly documented as carrying *no* environment — the environment travels
in the ctx, and only in the ctx.

## Reported friction

None of the output read here was agent-facing, so there is no ugly-render
finding to report from this lane.
