---
type: research
status: complete
tags: [research, runtime, flow]
---

# The environment mechanism, read out of core.async.flow's own source

Subject: how `clojure.core.async.flow` delivers a world to running code, what
it does (and pointedly does not do) about dynamic bindings across threads, and
what contract Seon's `seon.flow/submit!` / `submit!!` and proc args should
adopt so the environment always travels as data.

Vendored pin: `reference-code/core.async` at
`dc35f3e0d7bc2eef502e77982f48641f025c8051` — "[maven-release-plugin] prepare
release v1.10.874-alpha3", 2026-06-04.

Sources read end to end:

- `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` (346 lines)
- `.../clojure/core/async/flow/impl.clj` (323 lines)
- `.../clojure/core/async/flow/spi.clj` (95 lines)
- `.../clojure/core/async/flow/impl/graph.clj` (28 lines)
- `.../clojure/core/async/impl/dispatch.clj` (123 lines)
- `.../clojure/core/async.clj` (the `thread-call` / `go` binding sites)
- `.../clojure/core/async/impl/go.clj` (state-machine binding frame handling)
- `src/seon/flow.clj` (945 lines), plus the two live submission call sites
  `src/seon/effect.clj:505-519` and `src/seon/cluster/loop.clj:329-342`

## 1. How flow delivers configuration/world to procs

**The mechanism is one word: `:args`, and its destination is the proc's
state.** There is no other channel for a world.

The chain, end to end:

1. **Declaration.** `create-flow`'s config is `{:procs {pid proc-def}}` where
   proc-def is `{:proc :args :chan-opts}`, and `:args` is documented as "a map
   of param->val which will be passed to the process ctor"
   (`flow.clj:80-84`).
2. **Retention.** `prep-proc` keeps `:args` verbatim in the per-proc
   description: `(assoc ret pid {:pid pid :proc proc :ins inopts :outs outopts
   :args args :signal-select signal-select})` (`impl.clj:48`).
3. **Delivery at start.** `start-proc` calls
   `(spi/start proc {:pid pid :args (assoc args ::flow/pid pid) :resolver
   resolver :cast cast :ins ... :outs ...})` (`impl.clj:155-162`). The only
   addition flow makes to the caller's args map is `::flow/pid`.
4. **The SPI contract.** `spi/ProcLauncher`'s `start` receives exactly
   `{:keys [pid args ins outs resolver]}` — `:args` is "a map of param->val, as
   supplied in the graph def" (`spi.clj:72-86`). Note what the SPI docstring
   does NOT mention anywhere: bindings, thread-locals, or ambient state. It
   also explicitly says "A process should not transmit channel objects (use
   [pid io-id] data coordinates instead)" (`spi.clj:53-54`) — the whole
   protocol is written in terms of values crossing.
5. **Args become the initial state.** In `impl/proc`'s `start`:
   `state (step args)` (`impl.clj:263`) — the args map is passed to the
   step-fn's 1-arity 'init', and its return IS the proc's state. The public
   docstring says the same: "The init arity will be called once by the process
   to establish any initial state. The arg-map will be a map of param->val, as
   supplied in the flow def" (`flow.clj:211-217`).
6. **State is threaded through every step, forever.** The run loop is
   `#(loop [status :paused, state state, count 0, read-ins read-ins] ...)`
   (`impl.clj:271`); every transform call is `(transform state cid msg)`
   (`impl.clj:304-305`) destructured back as `[nstate outputs]`
   (`impl.clj:302`) and recurred (`impl.clj:322`); every transition is
   `(transition state ...)` returning `state'`
   (`impl.clj:209-217`, `flow.clj:234-243`).
7. **Args are a declared requirement, not a convention.**
   `(assert (or (not params) args) "must provide :args if :params")`
   (`impl.clj:257`), where `params` comes from the step-fn's 0-arity describe
   (`impl.clj:246`, `flow.clj:184-197`).

**Verdict on the question asked:** yes — "each proc's state carries its
environment, delivered at graph construction" is precisely the flow-native
pattern, and it is the ONLY one flow offers. Nothing in flow reads a
thread-local, a dynamic Var, or ambient global state on behalf of a step-fn.

`::flow/in-ports` / `::flow/out-ports` are the same idea for channels: a
returned init state may carry `{::flow/in-ports {cid chan}}` and flow merges
them into the read/write sets — `ins (into (or ins {}) (::flow/in-ports
state))` (`impl.clj:264-265`, documented `flow.clj:219-226`). So even the
proc's private channels arrive through *state*, not through a side register.

Seon already follows this shape in the one place it matters most. The work
launcher's entire world — parallelism, the `active-work` atom, both admission
buffers, the task executor, `io-submissions`, the `proc-stopped` promise —
travels as `:args` in the graph definition (`src/seon/flow.clj:508-518`),
lands in state via the init arity (`src/seon/flow.clj:405-411`), and is
destructured out of state on every transform
(`src/seon/flow.clj:416-418`, `424-427`). `var-process` merges its own args
into the start options rather than closing over them
(`src/seon/flow.clj:123-126`) — deliberately, per its docstring, so
"`create-flow` definitions stay pure data" (`src/seon/flow.clj:90-92`).

## 2. Dynamic bindings and thread pools

### Flow itself conveys nothing

Everything flow runs off-thread goes through one function:

```clojure
(defn futurize [f {:keys [exec]}]
  (fn [& args]
    (let [^Executor e (if (instance? Executor exec) exec (disp/executor-for exec))
          fut (FutureTask. #(apply f args))]
      (.execute e fut)
      fut)))
```

(`impl.clj:29-36`)

`#(apply f args)` — a plain closure. No `bound-fn*`, no
`Var/getThreadBindingFrame`, no `with-bindings`. A repository-wide grep of
`reference-code/core.async/src/main/clojure` for
`bound-fn|binding-conveyor|getThreadBindingFrame|resetThreadBindingFrame|push-thread-bindings|with-bindings`
returns hits in exactly two files — `clojure/core/async.clj:528` and
`clojure/core/async/impl/go.clj` (lines 895, 897, 917, 918, 1048) — **and none
anywhere under `flow/`**.

Every off-thread hop in flow runs through that binding-free `futurize`:

- the proc's entire run loop: `((futurize run {:exec exs}))` (`impl.clj:323`),
  where `exs` is `(spi/get-exec resolver (if (= workload :mixed) :mixed :io))`
  (`impl.clj:262`);
- each `:compute` transform: `(futurize step {:exec (spi/get-exec resolver
  :compute)})` awaited with `.get ... compute-timeout-ms` (`impl.clj:258-260`);
- **`inject` itself**: `((futurize do-io {:exec :io}))` (`impl.clj:197`). Even
  putting a message into a running flow hops to an `:io` thread. Only the
  message *value* survives that hop.

### core.async proper DOES convey — for `thread` and `go` only

- `thread-call` wraps: `(-> f bound-fn* returning-to-chan (dispatch/exec
  workload))` (`clojure/core/async.clj:528`). `thread` and `io-thread` are both
  thin macros over it (`async.clj:531-546`).
- `go` captures the frame at creation, `captured-bindings# (Var/getThreadBindingFrame)`
  (`go.clj:1048`), stashes it in the state array at `rt/BINDINGS-IDX`
  (`go.clj:1055-1057`), and the generated state machine installs/restores it
  around every resumption — `old-frame# (Var/getThreadBindingFrame)` then
  `(Var/resetThreadBindingFrame (rt/aget-object state BINDINGS-IDX))`
  (`go.clj:895-897`), re-saved and restored in the `finally`
  (`go.clj:917-918`).

**The teaching.** The same library, by the same author, conveys bindings where
its abstraction is "run *this lexical body* somewhere else" (`thread`, `go`),
and conveys nothing where its abstraction is "a process with declared params
and explicit state" (flow). That is not an oversight in flow — it is the
consequence of the design flow.clj's own preamble states: step-fns are
"communication-free functions … that might include no communication or
core.async code", achieving "a strict separation of your application logic
from its execution" (`flow.clj:14-16`, `50-53`). A step-fn that reads an
ambient dynamic Var is not communication-free and is not separated from its
execution context; flow gives it no way to be.

### The pools make the loss total, not intermittent

`dispatch/executor-for` memoizes one executor per workload tag
(`dispatch.clj:98-111`). The `:io` executor, when virtual threads exist,
starts a **brand-new virtual thread per `.execute`**:

```clojure
(reify Executor (execute [_ r] (.invoke svt nil (object-array [r]))))
```

(`dispatch.clj:82-89`, guarded by `virtual-threads-available?` at
`dispatch.clj:75-80`).

A fresh thread has the *root* binding frame. So an `:io` submission does not
"sometimes" lose the caller's bindings — it never has them. `:compute` and
`:mixed` are cached thread pools (`dispatch.clj:71-73`, `91-96`), which is
strictly worse for reasoning: a pooled thread carries whatever frame the
*previous* task happened to leave behind, so a missing binding can read as
`nil` on one run and as another cluster's stale value on the next.

The one thread-local core.async does keep is diagnostic only — the
`in-go-dispatch` `ThreadLocal` used to detect blocking in a dispatch thread
(`dispatch.clj:40-61`).

### Confirming the 2026-08-07 audit finding at source

`seon.flow/submit!!` (compute) wraps the work function:
`work-fn (bound-fn* work-fn)` (`src/seon/flow.clj:673`), and the compute
runnable invokes that wrapped fn at `src/seon/flow.clj:281`.

`seon.flow/submit!` (io) wraps only the **callback**:
`completion (bound-fn* complete!)` (`src/seon/flow.clj:618`), stored as
`::complete!` (`src/seon/flow.clj:621`). The `::work-fn` is stored raw
(`src/seon/flow.clj:620`, `assoc submission`) and is invoked raw on the
virtual thread: `::value (work-fn {::started! (fn [])})`
(`src/seon/flow.clj:366`, inside the runnable at `:356-369` handed to
`(.execute ^Executor task-executor future-task)` at `:375`, where
`task-executor` is the root `:io` executor, `src/seon/flow.clj:557-559`).

So the audit's claim is exactly right, and the asymmetry — callback conveyed,
work not — reads as an omission rather than a decision. The live consequence:
`src/seon/effect.clj:505-519` submits background capability work whose handler
runs with `seon.effect/*request-context*` (`src/seon/effect.clj:26-28`) and
`seon.db/*conn*` (`src/seon/db.clj:65-67`) at their root `nil` values. Note
that the *lexically closed-over* values in that same closure — `handler`,
`projected-request`, `effective` (`src/seon/effect.clj:511-515`) — cross
perfectly. Data crosses; thread-locals do not. That contrast is the whole
design lesson in one call site.

It is also worth saying plainly that the `:compute` path at
`src/seon/flow.clj:673` is not "correct", merely "currently lucky":
`bound-fn*` captures the frame of whichever thread calls `submit!!`, so the
guarantee is "the caller happened to have the right bindings installed", which
is unverifiable at the call site and silently degrades the moment a caller is
itself moved onto a pool. `src/seon/flow.clj:917` (`join-error-fanout!`) has a
third instance of the same reflex, a `bound-fn` around a loop submitted to the
root `:io` executor.

## 3. The soundest way for a submission or proc to carry an environment value

The constraints in the question map one-to-one onto mechanisms flow already
has:

**(a) step-fns and work-fns receive it as an ordinary argument.** For procs,
that is `:args` → init arity → state → every transform
(`impl.clj:48` → `:155` → `:263` → `:271` → `:304`). For submissions, the
submission map is *already* the argument: `execute-work!` and
`execute-io-work!` destructure it (`src/seon/flow.clj:250-252`, `345-347`) and
call `(work-fn {::started! …})` with a map (`src/seon/flow.clj:281`, `:366`).
That map is the natural carrier — one more key on an argument map that already
exists, in a system whose own rule is that maps are open and accrete
(AGENTS.md, ruling #48).

**(b) nothing needs thread-local state.** Deleting all three `bound-fn*` /
`bound-fn` sites (`src/seon/flow.clj:618`, `:673`, `:917`) is the proof
obligation: once the environment is an argument, a conveyance wrapper is not
"belt and braces", it is a second mechanism whose presence hides whether the
first one works. Keeping it means the day someone forgets the argument, the
failure is invisible in dev and appears under a different thread pool.

**(c) graph rebuild naturally re-delivers it.** This falls out for free
because delivery happens at `spi/start`, not at definition time.
`impl/create-flow`'s `start` runs `start-proc` over every proc description on
every start (`impl.clj:166-167`), and `stop` merely sends `::flow/stop` and
nils the chans atom (`impl.clj:174-183`) — the `pdescs` (and therefore the
`:args`) are re-read on the next `start`. And the SPI mandates this explicitly:
"The launcher should acquire no resources, nor retain any connection to the
started process. A launcher may be called upon to start a process more than
once, and should start a new process each time start is called"
(`spi.clj:19-22`). So a stop → `create-flow` → start rebuild re-runs
`(step args)` and the proc gets the current environment value with no extra
code. A thread-local, by contrast, has nothing to re-deliver at rebuild — the
new proc thread simply starts blank again.

The environment must be an ordinary immutable value (cluster name, database
value or connection holder, schema projection, request identity, the work
launcher itself). It should be built once per cluster and passed down, never
re-derived inside a step-fn from a global.

## 4. Flow features that fit and that Seon is not using

1. **`:params` in the describe arity.** `flow.clj:184-197` and `:211-217`
   document `:params` as "the initial arguments to setup the state for the
   function", and `impl.clj:257` turns it into a start-time assertion:
   `(assert (or (not params) args) "must provide :args if :params")`. Seon's
   step-fns declare `:ins`, `:workload`, `:ping-map-fn` but **no `:params`**
   (`src/seon/flow.clj:160-165`, `396-404`, `742-744`), so that assertion can
   never fire. Declaring `:params {::environment "…"}` makes the environment a
   *declared, checked* requirement of the proc instead of a convention — and
   it becomes visible in `describe` and in `datafy`
   (`impl.clj:250-253`, `flow.clj:62-64`).
2. **`::flow/in-ports` / `::flow/out-ports` returned from init**
   (`impl.clj:264-265`, `flow.clj:219-226`). Seon uses `in-ports`
   (`src/seon/flow.clj:410`, `:747`) but not `out-ports`; it is the sanctioned
   way to let data exit a flow to an external channel without a side register.
3. **The transition arity as the environment's lifecycle hook**
   (`impl.clj:209-217`, `flow.clj:234-243`) — "state' will be the state
   supplied to subsequent calls", so `::flow/resume` is the natural place to
   *refresh* a captured environment on rebuild-free resume. Seon's transitions
   currently only signal stop (`src/seon/flow.clj:412-415`, `:751-757`).
4. **`:ping-map-fn`** (`flow.clj:191-193`, `impl.clj:246`, `:279`) — a proc can
   publish the identity portion of its environment into `ping`/`datafy` output,
   which makes "does this proc have a cluster identity?" a live observation
   rather than an inference. Seon uses it for capacity only
   (`src/seon/flow.clj:163-165`, `402-404`).
5. **`:io-exec` / `:mixed-exec` on `create-flow`** (`flow.clj:101-103`,
   `impl.clj:52-57`, `:148`). Seon's work-launcher graph passes only
   `:compute-exec` (`src/seon/flow.clj:528`), so the launcher proc's own run
   loop is placed on core.async's *global memoized* `:io` executor
   (`impl.clj:262` → `dispatch.clj:98-111`) rather than the process root's.
   Passing `:io-exec` too would make the root executors the whole truth for
   that graph.

### One defect found while reading

`clojure.core.async.flow.impl.graph/Graph` declares `command-proc`
(`graph.clj:24-25`), but the reify returned by `impl/create-flow` implements
`start stop pause resume ping pause-proc resume-proc ping-proc inject` and
**not** `command-proc` (`impl.clj:87-197`). `seon.flow/monitor-graph`
delegates it unconditionally (`src/seon/flow.clj:835-836`), so any caller
would hit an `AbstractMethodError`. No first-party caller exists today
(`rg command-proc src/ test/ script/` → only those two lines), so this is
latent, not live. It is upstream's gap, not Seon's; the local fix is either to
drop the delegation or to have it return a flat error value.

## 5. Verdict — the recommended contract

**The environment is one namespaced key carrying one immutable map, present on
every submission and in every proc's `:args`. No `bound-fn*` survives.**

1. **Declare the environment shape once** as a registered schema (a cluster
   name, the database connection holder, the schema projection basis, the
   request identity, the work launcher). It is data with object-valued leaves,
   not a thread-local.

2. **`submit!!` (compute):** require `::flow/environment` on the submission
   map. `execute-work!` already receives the whole submission
   (`src/seon/flow.clj:250-252`); merge the environment into the map handed to
   the work-fn at `src/seon/flow.clj:281`, so the work-fn's single argument
   becomes `{::flow/started! … ::flow/environment env}`. Delete
   `(bound-fn* work-fn)` at `src/seon/flow.clj:673`.

3. **`submit!` (io):** identical — require `::flow/environment`, pass it in the
   work-fn's argument map at `src/seon/flow.clj:366`, and pass it to
   `complete!` as well so the settlement callback
   (`src/seon/effect.clj:517-519`, which needs the connection) stops depending
   on the submitter's frame. Delete `(bound-fn* complete!)` at
   `src/seon/flow.clj:618`. This is the line that closes the audited hole.

4. **Make absence a construction-time refusal, not a nil read.** `submit!` /
   `submit!!` should throw on a missing `::flow/environment` exactly as
   `var-process` throws on a non-var step or a `:mixed` workload
   (`src/seon/flow.clj:100-110`). A capability request with no cluster identity
   is not a degraded request, it is an unconstructable one.

5. **Procs:** every Seon step-fn's describe arity declares
   `:params {::flow/environment "…"}`, and `var-process` requires an
   `::flow/environment` entry in its `args` map. `impl.clj:257` then enforces
   presence at `spi/start` for free, and `flow.clj:123-126`'s existing merge
   already delivers it on every rebuild.

6. **Call sites become explicit.** `src/seon/cluster/loop.clj:329-342` and
   `src/seon/effect.clj:505-519` both already hold the cluster/request values
   they need lexically at submission time; they pass them as
   `::flow/environment` instead of relying on `*request-context*`
   (`src/seon/effect.clj:26`) and `seon.db/*conn*` (`src/seon/db.clj:65`)
   surviving a thread hop that, per `dispatch.clj:82-89`, they cannot survive.

7. **The regression that kills the class** (not the instance): submit both an
   `:io` and a `:compute` unit of work whose fn asserts it received a complete
   environment argument **and** that the ambient dynamic Vars are at their root
   values. Asserting the second half is what prevents a future `bound-fn*` from
   quietly re-becoming the real mechanism.

Why this and not "wrap io work in `bound-fn*` too" — the one-line patch that
would make today's symptom disappear: it would make Seon's capability request handler
depend on a mechanism flow's own author declined to use anywhere in flow, and
it would keep the environment invisible in `describe`, in `datafy`, in `ping`,
and in the submission value itself. `impl.clj` shows the alternative is not a
new mechanism to build but the mechanism flow already is: `:args` in,
state through, values across every thread boundary.
