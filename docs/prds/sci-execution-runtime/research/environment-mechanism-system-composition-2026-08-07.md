---
type: research
status: complete
tags: [research, runtime, boot]
---

# Environment Mechanism — What Integrant, Aero, and Hyperlith Teach — 2026-08-07

## Scope and reading discipline

Read end to end before writing: `reference-code/integrant/src/integrant/core.cljc`
(702 lines), `reference-code/integrant/README.md`,
`reference-code/integrant-repl/src/integrant/repl.clj` (141 lines) and
`repl/state.clj` (5 lines), `reference-code/aero/src/aero/core.cljc` (435 lines)
and `aero/README.md`, `reference-code/hyperlith/src/hyperlith/core.clj` (154
lines) and every namespace under `hyperlith/src/hyperlith/impl/`, plus the four
example apps under `hyperlith/examples/*/src/app/main.clj`. Also read
[parallel-isolation-audit-2026-08-07.md](parallel-isolation-audit-2026-08-07.md),
which is the defect this question responds to.

Every claim below carries a `file:line`. Paths are relative to
`/Users/sean/src/seon`.

## Verdict up front

**Adopt the value; reject the registry.**

The one transferable idea across all three libraries is that *the environment is
a plain map produced by an ordinary function at boot and then passed as an
argument*. All three do this, and none of them puts the environment in a dynamic
var. Integrant's `init` is a `reduce` returning a map
(`reference-code/integrant/src/integrant/core.cljc:453-455`); Hyperlith's
`start-app` calls a caller-supplied `ctx-start` thunk once and merges the
resulting map into every request
(`reference-code/hyperlith/src/hyperlith/core.clj:128-135`); Aero's `read-config`
is a pure read-and-walk returning data
(`reference-code/aero/src/aero/core.cljc:424-429`).

The thing to reject is equally consistent: **every one of these libraries pays
for extensibility with a process-global mutable registry**, and each such
registry is exactly the "per-cluster environment silently degrades to the
process-wide default" failure the
[parallel-isolation audit](parallel-isolation-audit-2026-08-07.md) already
proved defective in Seon. Integrant's openness is `defmulti` dispatch plus the
global `derive` hierarchy plus an annotation atom
(`core.cljc:10,457-535,286-292`). Hyperlith's is four `defonce` atoms and one
compile-time env map (`impl/router.clj:3`, `core.clj:86`, `impl/error.clj:4`,
`impl/cpu_pool.clj:4`, `impl/env.clj:5`). Aero's is `defmulti reader`
(`aero/core.cljc:30`) and `defmulti eval-tagged-literal`
(`aero/alpha/core.cljc:50`). Integrant-repl is *entirely* three global vars
mutated by `alter-var-root` (`integrant/repl/state.clj:3-5`,
`integrant/repl.clj:13,23,62-64`).

For a system where the environment is **per-cluster and several clusters share
one JVM**, that is not a stylistic objection — a global multimethod table cannot
express "this cluster's web server", so a second cluster either collides or is
invisible.

## 1. Integrant: the system as data

### 1.1 The config map

A config is an ordinary map whose top-level keys are qualified keywords or
vectors of them (`core.cljc:50-53`), each value a plain value. References are
records, not magic: `Ref` and `RefSet` are `defrecord`s satisfying a two-method
`RefLike` protocol (`core.cljc:25-27,77-89`), constructed by `ig/ref` /
`ig/refset` (`core.cljc:91-103`). EDN configs get them via four reader tags
(`core.cljc:217-230`) — `#ig/ref`, `#ig/refset`, `#ig/profile`, `#ig/var`. So
the *declaration* half is pure data with no evaluation: the same property Seon
already has by reconciling config into database facts.

### 1.2 Dependency order is derived, not declared

`dependency-graph` walks the whole config with `tree-seq`
(`core.cljc:156-157,177-195`), collects every `Ref`/`RefSet` at any depth,
resolves each ref key through `find-derived` (which honors the global `isa?`
hierarchy, `core.cljc:61-75`), and builds a `weavejester.dependency` graph.
`key-comparator` turns that graph into a **deterministic** topological
comparator, tie-broken by `(compare (str %1) (str %2))` (`core.cljc:197-202`).
Ordering is therefore a pure function of the config value — a real lesson: *the
boot order is not a hand-maintained list, it is derived from the declared
references.*

Two related niceties: `dependent-keys` (`core.cljc:211-212`) lets you init a
*subset* of the config and automatically pull in its transitive dependencies,
and refsets are excluded from the subsetting graph but included in the ordering
graph (`core.cljc:190,205`).

### 1.3 Validation happens before any effect

`build` refuses the whole config up front — invalid composite keys, ambiguous
refs, missing refs, unbound vars — *before* the reduce starts
(`core.cljc:444-452`). Nothing is constructed until the declaration is proven
resolvable. That is the same discipline as Seon's admission gate, applied to
boot.

### 1.4 The resulting runtime artifact

`init` is `build` with `init-key` (`core.cljc:650-658`), and `build` is a
`reduce` over dependency-ordered keys starting from `(with-meta {} {::origin
config})` (`core.cljc:453-455`). Each step:

1. `resolve-refs` postwalks the raw config value, replacing every `RefLike` with
   the *already-built* value pulled out of the accumulating system map
   (`core.cljc:351-354,80-82`);
2. asserts (`core.cljc:426,545-548`);
3. calls `init-key` and `assoc`s the result under the same key
   (`core.cljc:423-428`).

**So the runtime artifact is a flat map: same keys as the config, values
replaced by live components.** Nothing is nested; nothing is a record; there is
no `System` type. Components receive dependencies **as arguments** — the ref in
their config value has already become the live thing by the time `init-key` sees
it (README.md:100-104: "When `:adapter/jetty` references `:handler/greet`, it
will receive the initialized handler function, rather than the raw
configuration").

Two pieces of metadata ride along: `::origin` (the config that produced it) and
`::build` (the *resolved* value per key, `core.cljc:428`). `halt!`/`resume` need
them; `run!` and `reverse-run!` even `:pre`-assert `::origin` is present
(`core.cljc:388,396`). Note the shape choice: **the provenance is metadata, so it
never collides with a component key.**

### 1.5 Teardown mirrors construction

`halt!` is `reverse-run!` with `halt-key!` (`core.cljc:660-666`), walking the
same derived graph backwards (`core.cljc:214-215`). `halt-key!` defaults to a
no-op (`core.cljc:504`) and is documented as required-idempotent
(`core.cljc:496-502`). `resume`/`suspend!` add a middle state that reuses live
resources across a reload (`core.cljc:677-702`), and notably `resume` first
halts keys that vanished from the new config (`core.cljc:671-675,686`).

### 1.6 What Integrant deliberately does NOT do

- **No ambient system.** `integrant.core` holds no system anywhere. `init`
  returns it; the caller owns it. The *only* atom in the whole namespace is the
  annotation registry (`core.cljc:10-23`), which is documentation metadata, not
  runtime state. This is the single most important observation for Seon: a
  mature, widely used system-composition library carries **zero** process-global
  runtime state.
- **No component protocol / no records.** README.md:31-36 states the design
  reaction to Component explicitly: "anything can be dependent on anything
  else". Components are whatever `init-key` returns — a function, an atom, a
  connection.
- **No injection into the running component.** Dependencies are resolved once,
  before construction, by value substitution. There is no later lookup.
- **No lifecycle beyond init/halt/(suspend/resume).** No start ordering
  overrides, no restart-on-failure, no supervision.
- **No global var stashing** — that is deliberately quarantined to the *separate*
  `integrant-repl` artifact (below).

### 1.7 Integrant-repl is the anti-pattern, and it knows it

`integrant.repl.state` is three unadorned `def`s (`repl/state.clj:3-5`) in a
namespace marked `^:clj-reload/no-reload` (`repl/state.clj:1`), mutated by
`alter-var-root` from `set-prep!`, `prep`, `init`, `clear`, `halt`, `resume`
(`repl.clj:13,23,62-64,78-80,86,100-104`). It is a **dev-time convenience layer,
shipped as a different library**, precisely so the core stays pure. The lesson
transfers directly: if Seon wants a REPL convenience for "the current cluster",
it belongs in a dev namespace that is obviously not the mechanism, never in the
runtime owner.

### 1.8 Lessons for Seon's boot tower

1. **The environment is the return value of a reduce over declared units in
   derived dependency order** (`core.cljc:453-455`). Seon's boot tower —
   process → store → facts → flow — is already a dependency order; making it a
   *derived* order over declared units means nobody hand-maintains it.
2. **Resolve dependencies by substitution before construction, not by lookup
   during operation** (`core.cljc:423-428`). A subsystem that receives its
   database connection as an argument cannot reach around it; a subsystem that
   *looks it up* can, and will.
3. **Provenance rides as metadata** (`core.cljc:428,380-381`), so the value stays
   a clean flat map of live things.
4. **Refuse the whole declaration up front** (`core.cljc:444-452`).
5. **Teardown is the same derived order reversed** (`core.cljc:660-666`) — Seon
   gets cluster shutdown for free from the same declaration.
6. **Subset boot is free** once dependencies are derived (`core.cljc:211-212`) —
   directly useful for a test that wants store + facts but no web server.

## 2. Integrant's weaknesses for Seon's case

### 2.1 `defmulti` is exactly the parallel-provisioning surface we are closing

`init-key`, `halt-key!`, `resume-key`, `suspend-key!`, `resolve-key`,
`expand-key`, and `assert-key` are all `defmulti` (`core.cljc:457-535`). A
multimethod table is a **process-global mutable map that any loaded namespace
can `assoc` into**, at any time, with no declaration and no record. That is:

- **unqueryable** — the set of legal components is `(methods ig/init-key)`, a
  runtime artifact of whatever happened to be loaded, not a fact;
- **process-scoped, not cluster-scoped** — there is one `init-key` table for the
  whole JVM, so two clusters cannot have different component sets, and a
  component registered for one is visible to all;
- **load-order dependent** — `ig/load-namespaces` exists (`core.cljc:249-264`)
  purely to `require` the namespaces a config's keys imply, i.e. to force the
  side effects that populate the table. A mechanism that needs a helper to
  provoke its own registration is confessing the registration is ambient.

It gets worse. The default `init-key` implementation resolves the key as a
**var name** and calls it: `:foo.bar/baz` → `(find-var 'foo.bar/baz)` → call it
on the value (`core.cljc:478-494`). So *any qualified keyword naming any var
anywhere on the classpath is a valid component key*. For a system whose stated
goal is that agent code "cannot reach around it or create parallel provisioning
designs", this is the opposite of the target.

Compounding it, `find-derived` dispatches through the **global `derive`
hierarchy** (`core.cljc:61-75`), which `load-hierarchy` populates by scanning
every `integrant/hierarchy.edn` on the classpath (`core.cljc:271-292`), and
`composite-keyword` mutates that hierarchy at runtime via `derive` inside a
memoized function (`core.cljc:29-40`). Three separate global mutable surfaces,
all process-scoped.

### 2.2 How you would bound the component set

The honest answer: **you do not bound a multimethod — you replace it.** Two
constructions, both available to Seon today:

**(a) The unit set is a database fact, and the constructor is looked up by
declared identity.** Seon already indexes every function into the program graph
with `:seon.fn` facts and already records arbitrary namespaced metadata
(`:seon.fn/workload`, `:seon.fn/external-sink`). A boot unit is then a *declared
fact* — "this function constructs the environment key `:seon.web/server`, and
depends on `:seon.db/connection`" — which makes the component set a Datalog
query rather than `(methods ig/init-key)`. Adding a component is adding a
declaration, which is reviewable, queryable, and per-branch (therefore
per-cluster, since a cluster is a branch). This is the CLAUDE.md
"everything is declared, recorded, and queryable" principle applied to boot.

**(b) The construction functions are a closed vector in one namespace.** Not a
"hand-maintained list" in the banned sense — a hand list is banned when it
*substitutes for a derivable fact*; here the fact is not derivable from anything
else, and the whole point is that the set is closed. The dependency *order*
must still be derived from declared refs, never written down.

Either way: `init-key` on a closed set is just `(f value)` where `f` came from
the declaration. The multimethod buys nothing once the set is closed.

### 2.3 Is the config-as-data half the transferable part?

Partly, and less than it looks — because **Seon already has a better version of
it.** Integrant's config-as-data exists to get declarations out of code and into
an inspectable, diffable value (README.md:26-32). Seon's config already
reconciles into database facts, which are inspectable, diffable, *and*
queryable, versioned by branch, and per-cluster. Reading an EDN file into a map
at boot would be a step backwards.

What genuinely transfers from the data half is narrower and sharper:

- the **`Ref` as a first-class value** (`core.cljc:77-96`) — a dependency edge
  is a datum you can walk, not an implicit `require`. Seon's equivalent is a ref
  attribute between declared boot units;
- **derived topological order with deterministic tie-break**
  (`core.cljc:197-202`) — boot order must be reproducible, not
  hash-order-dependent;
- **whole-config validation before any effect** (`core.cljc:444-452`).

`#ig/profile` / `#ig/var` / `bind` / `expand` / `converge`
(`core.cljc:120-154,562-648`) are all machinery for reusing one config file
across environments. Seon has separate clusters as first-class values; none of
that is needed and all of it is complexity.

## 3. Aero

### 3.1 What it is

`read-config` reads EDN through a reader that turns **every** tag — known or
unknown — into a `tagged-literal` rather than evaluating it
(`aero/core.cljc:177-189`), then resolves those literals by walking
(`core.cljc:360-412,414-430`). The evaluation step is a multimethod
`eval-tagged-literal` (`aero/alpha/core.cljc:50`) with `reader`
(`aero/core.cljc:30`) as the default leaf. Tags include `#env`, `#envf`,
`#prop`, `#long`, `#double`, `#keyword`, `#boolean`, `#include`, `#join`,
`#read-edn`, `#merge` (`core.cljc:44-102`), plus the structural `#ref`,
`#profile`, `#hostname`, `#user`, `#or` (`core.cljc:228-286`).

`#ref` is resolved by a fixed-point loop: `resolve-tagged-literals` re-expands
until nothing is incomplete, bounded by an attempt counter that throws "Max
attempts exhausted" (`core.cljc:365-412`). That is Integrant's dependency
ordering problem solved *worse* — by iteration to a fixed point with a warning
path that silently `nil`s unresolvable refs (`core.cljc:378-398`).

### 3.2 The transferable idea, and it is one sentence

**Config must be data, never a program.** README.md:78-80, verbatim in spirit:
"While it can be very flexible to have 'clever' configuration 'programs', it can
be unsafe... Always use data for configuration and avoid turing-complete
languages!" The tagged-literal design is the mechanism: the reader *never*
`eval`s; it produces a datum, and a separate pure walk interprets it. That is
the same shape as Seon's admission gate.

Two smaller points worth keeping:

- **Environment variables sparingly** (README.md:84-88, citing the arguments
  against 12-factor). Seon already agrees: runtime reads the database, and the
  bootstrap config is deliberately tiny.
- **`Deferred`** (`core.cljc:25,158-160,433-435`) — wrap an expensive or
  privileged read in a delay so it is only forced if the selected profile
  actually needs it. If a Seon boot unit is expensive and conditionally needed,
  this is the cheap shape.

### 3.3 What NOT to adopt from Aero

Essentially the whole surface. Seon does not need a config *file format* — it
has facts. `#ref`'s fixed-point loop is strictly weaker than a derived
dependency graph and has a documented silent-degradation path
(`core.cljc:378-398`: prints a warning to `*err*` and substitutes `nil`) — that
is exactly the "silently degrades to a default" failure mode the isolation audit
condemned. `#profile`/`#hostname`/`#user` (`core.cljc:243-256`) make config
depend on ambient host state, which is the ambient-environment defect wearing a
config hat. And `reader`/`eval-tagged-literal` are, again, process-global
multimethod tables.

## 4. Hyperlith: the closest live example

This is the most instructive artifact of the three, because it is a *complete*
web + db + render system in ~700 lines of `impl/`, and it makes the right call
on the main question and the wrong call on three secondary ones.

### 4.1 How context reaches handlers — the right call

`start-app` takes `:ctx-start` and `:ctx-stop` **functions supplied by the
application** (`core.clj:120-123`). It calls `(ctx-start)` exactly once
(`core.clj:127`), getting back a plain map. Then:

```clojure
wrap-ctx (fn [handler]
           (fn [req]
             (handler
               (-> (assoc req :hyperlith.core/refresh-mult refresh-mult)
                   (u/merge ctx)))))
```

(`core.clj:130-135`). **The environment is merged into the request map.** Every
handler is a one-argument function of a request that already contains its
dependencies, destructured at the head:

- `(defaction handler-send-message [{:keys [_sid db] {:keys [message]} :body}] ...)`
  — `examples/chat_atom/src/app/main.clj:35`;
- `(defview handler-home {...} [{:keys [db] :as _req}] ...)` —
  `chat_atom/main.clj:46-47`;
- `(defaction handler-save-cell [{:keys [sid tabid tx-batch!] ...}] ...)` —
  `examples/billion_cells/src/app/main.clj:281-283`.

There is **no dynamic var, no thread-local, and no global db** in the handler
path. `render-handler` closes over `req` and calls `(render-fn req)` inside the
SSE loop (`impl/datastar.clj:164`) — the environment survives the hop onto a
virtual thread (`impl/util.clj:27-32`, itself using `bound-fn*`) and onto the
CPU pool (`impl/cpu_pool.clj:8-9`) **because it is a value in a closed-over map,
not a binding**. That is precisely the property the
[parallel-isolation audit](parallel-isolation-audit-2026-08-07.md) found Seon
lacking.

`start-app` returns `{:wrapped-router ... :ctx ctx :stop (fn [& [opts]] ...)}`
(`core.clj:149-154`), so the environment stays reachable to the caller and to
the REPL — the examples' comment blocks query it as `(-> app :ctx :db)`
(`chat_atom/main.clj:79`, `billion_cells/main.clj:581`). No global needed for
REPL access either.

`ctx` shape in practice is small and flat: `{:db db_}`
(`chat_atom/main.clj:57-60`), or `{:db reader :db-read reader :db-write writer
:tx-batch! (batch/async-batcher-init! ...)}` (`billion_cells/main.clj:552-557`).
Teardown is the mirror: `(ctx-stop ctx)` with the application closing what it
opened (`core.clj:153`, `billion_cells/main.clj:559-561`).

### 4.2 Where Hyperlith uses globals anyway — the wrong calls

Four, and each is worth naming because Seon must not copy them:

1. **`router/routes_`** is a `defonce` atom mutated by `add-route!`
   (`impl/router.clj:3-6`), and routes are registered as a **macro-expansion
   side effect**: `defview`/`defaction` call `ds/shim-handler` /
   `ds/render-handler` / `ds/action-handler` at load time
   (`core.clj:88-104` → `impl/datastar.clj:96,105,138`). `static-asset` and
   `static-css` do the same (`impl/assets.clj:19`, `impl/css.clj`). So the route
   table is process-global and populated by `require`. One process can host
   exactly one app. Seon's route table is already a **value** in
   `src/seon/render/route.clj` — keep it that way.
2. **`refresh-ch_`** (`core.clj:86`), reset by `start-app` (`core.clj:126`) and
   read by `refresh-all!` (`core.clj:106-108`) — one refresh channel per JVM.
   Note the tension in the same file: the refresh **mult** is threaded properly
   through the request (`core.clj:132`, read at `impl/datastar.clj:144`), while
   the refresh **channel** is a global. The correct half is right there next to
   the wrong half.
3. **`er/on-error_`** (`impl/error.clj:4`), reset by `start-app`
   (`core.clj:129`) and deref'd inside the `try-on-error` macro
   (`impl/error.clj:10`) — one error policy per JVM.
4. **`env`** is a *compile-time macro* over an `.env.edn` resource read at load
   (`impl/env.clj:5-17`) — config baked into the namespace.

Also: the ctx keys are **unqualified** (`:db`, `:tx-batch!`) and `u/merge`d
straight over the Ring request map (`core.clj:134`, `impl/util.clj:22-25`), so a
ctx key can shadow a request key with no diagnostic. Hyperlith even ships
`qualify-keys` (`impl/util.clj:58-61`) and uses a namespaced key for its own
injection (`:hyperlith.core/refresh-mult`, `core.clj:134`) — it knows the risk
and does not apply it to the user ctx.

### 4.3 The single sentence to take from Hyperlith

*An environment map built once by an application-supplied function, merged into
the value each unit of work already receives, survives every thread hop for
free and needs no carrier.* Its globals are all things that were **not** put in
that map, and each one is a place where the framework can host only one app per
process. Seon needs many clusters per process, so Seon can afford none of them.

## 5. Verdict for Seon

### (a) Constructing the environment at boot, in dependency order

Take Integrant's *shape* wholesale and its *extension mechanism* not at all:

- boot is a **reduce over declared units in derived topological order**, the
  accumulator being the environment map being built
  (`integrant/core.cljc:453-455`) — which is also the shape CLAUDE.md already
  names for plan execution ("executing a plan is a reduce over its forms");
- the order is **derived from declared dependency refs**, never written down,
  with a deterministic tie-break (`core.cljc:197-202`);
- **validate the entire declaration before constructing anything**
  (`core.cljc:444-452`);
- each unit's constructor receives the **partially built environment** (or the
  precise subset it declared), so it gets its dependencies as arguments and has
  nothing to look up;
- **teardown is the same order reversed** (`core.cljc:660-666`), and each
  teardown must be idempotent (`core.cljc:496-502`);
- **subset boot falls out for free** (`core.cljc:211-212`) — a test asking for
  store + facts and no web server gets exactly those, in order, without a second
  boot path. That is directly load-bearing for the 2026-08-07
  "platform IS the test infrastructure" ruling.

One divergence worth stating: Integrant's `resume`/`suspend!`
(`core.cljc:677-702`) exist to survive a namespace reload. Seon does not need
them — re-evaluating a `defn` already changes running proc behavior, because
graph definitions reference transforms as vars. Do not port the suspend/resume
lifecycle.

### (b) The shape of the value

**A flat map, fully namespaced keys, one entry per subsystem, provenance in
metadata.**

- *Flat, not nested*: Integrant's system map has exactly the config's key set
  (`core.cljc:427`); Hyperlith's ctx is one level
  (`billion_cells/main.clj:552-557`). Nesting invents paths that then need
  helpers.
- *Namespaced keys*: Integrant requires it (`core.cljc:50-53`); Hyperlith does
  not and is one silent collision away from a bug (`core.clj:134` merging
  `:db` over a request map). Seon's convention already mandates it — and here it
  is also what lets the environment be merged into, or carried beside, any other
  map safely.
- *Metadata for provenance*: `::origin` and `::build`
  (`core.cljc:428,380-381`) keep the map itself pure live-components. Seon's
  analogue is the cluster name, the branch, and the basis transaction the
  environment was constructed at — metadata, not entries, so no subsystem can
  accidentally consume them as a dependency.
- *Passed as an argument*, so it survives virtual-thread hops, SCI evals, and
  flow procs with no carrier — this is the whole point, and Hyperlith
  demonstrates it working across three different thread transitions
  (`impl/util.clj:27-32`, `impl/cpu_pool.clj:8-9`, `impl/datastar.clj:151-177`).

Because the environment is per-cluster and several live in one JVM, the
environment value **must name its cluster**, and every subsystem entry in it
must be the cluster's own instance. Integrant has nothing to say about this
because it assumes one system per process; Hyperlith actively assumes it
(`impl/router.clj:3`). Seon must be explicit where they were silent.

### (c) Keeping the component set closed

**The set of subsystems is declared and closed; only the wiring is derived.**

Every "extension point" in this family is a global mutable table — `defmulti
init-key` (`integrant/core.cljc:472-476`) with a var-resolving default that
admits any classpath symbol (`core.cljc:478-494`), the global `derive` hierarchy
(`core.cljc:61-75,271-292`), the annotation atom (`core.cljc:10-17`), Aero's two
reader multimethods (`aero/core.cljc:30`, `aero/alpha/core.cljc:50`), and
Hyperlith's `routes_` (`impl/router.clj:3`). Each answers "what components
exist?" with "whatever got loaded", which is neither queryable nor per-cluster.

Seon's replacement is the principle it already holds: **the component set is a
declared, queryable fact.** A boot unit declares the environment key it
produces and the keys it requires; the order is derived from those declarations;
adding one is adding a declaration reviewed like any other, not a `defmethod`
appearing at load time. Agent code cannot register a subsystem because
registration is not a load-time side effect it can perform — and cannot reach
one it was not given, because there is nothing ambient to reach.

### What NOT to adopt — the explicit list

1. **`defmulti`-based component registration** (`integrant/core.cljc:457-535`) —
   process-global, unqueryable, load-order dependent, not per-cluster.
2. **The var-resolving `init-key` default** (`core.cljc:478-494`) — makes every
   qualified symbol on the classpath a component key.
3. **The global `derive` hierarchy for key dispatch**
   (`core.cljc:61-75,271-292`) and **runtime `derive` from a memoized function**
   (`core.cljc:29-40`).
4. **The annotation registry atom** (`core.cljc:10-23`) — Seon's program graph
   already holds metadata as facts.
5. **`integrant-repl`'s global `config`/`system`/`preparer` vars**
   (`repl/state.clj:3-5`, `repl.clj:13,23,62-64`) — the exact
   ambient-environment defect. If a REPL convenience is wanted, it lives in a
   dev namespace and is never read by runtime code.
6. **`suspend`/`resume` lifecycle** (`core.cljc:677-702`) — solved differently by
   var-referencing flow procs.
7. **`#ig/profile` / `#ig/var` / `bind` / `expand` / `converge`**
   (`core.cljc:120-154,562-648`) — per-environment config-file reuse; clusters
   already solve this.
8. **Aero's whole config-file layer** — Seon's config is facts.
   Specifically avoid **`#ref`'s fixed-point loop with silent `nil` substitution**
   (`aero/core.cljc:365-412`, warning at `378-398`) and **`#hostname`/`#user`**
   (`core.cljc:247-256`), which make config depend on ambient host state.
9. **Hyperlith's route registry as a load-time side effect**
   (`impl/router.clj:3-6`, `core.clj:88-104`) — Seon's route table is a value;
   keep it a value.
10. **Global refresh channel and global error policy** (`core.clj:86`,
    `impl/error.clj:4`) — one-per-JVM slots for things that must be
    one-per-cluster.
11. **Unqualified environment keys merged into another map**
    (`core.clj:130-135`) — silent shadowing.
12. **Compile-time env reading** (`impl/env.clj:5-17`).

## Follow-ups

Two observations that belong to other owners rather than this report:

- The [parallel-isolation audit](parallel-isolation-audit-2026-08-07.md)'s
  Defect II (derived state in a process-wide slot) is *not* addressed by any of
  these libraries — none of them caches derived values at all. The environment
  value is the natural home: a compiled validator derived from a projection
  belongs on the environment that holds the projection, which makes it
  per-cluster by construction rather than by discipline.
- Integrant's `run!`/`reverse-run!`/`fold` (`core.cljc:383-407`) are a small,
  general "do something to every component in dependency order" surface. If Seon
  wants per-cluster health checks or footprint inspection over subsystems, that
  is the shape — a fold over the environment in derived order — not a second
  registry of inspectable things.
