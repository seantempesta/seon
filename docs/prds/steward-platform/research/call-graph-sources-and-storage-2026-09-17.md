---
type: research
status: read-only findings; options for review
created: 2026-09-17
tags: [testing, selection, call-graph, sci, clj-kondo, orchard, datahike, steward]
---

# Other sources of call edges, and the storage shape that can say how it knows

Dated 2026-09-17. Read-only research lane on `steward-platform`. Owner
instruction: *"if our call graph storage and querying isn't good enough yet
then we need to improve them. keep having agents dig into source code to find
the right methods."* Ruling E2 says a test exercises a function when it reaches
it transitively through stored `:seon.fn/calls` edges, with no annotations.
The sibling note
[call-graph-fidelity-2026-09-17.md](call-graph-fidelity-2026-09-17.md) (read
end to end) measured the STATIC side and named four missing classes. This note
covers the OTHER sources of the same edges — the JVM constant pool, SCI's
analyzer, and the clj-kondo outputs we still do not consume — and the storage
shape that lets a query say **how** it knows an edge.

**Verdict in one line: the runtime constant pool is exact for JVM-loaded
first-party code and closes the `#'f` / var-quote class outright, but it is
blind in the one direction selection needs (test → function), and blind
through Malli instrumentation unless unwrapped; SCI's analyzer is the only
seam that can be made exact for agent-authored code, and our fork already has
two precedents for the accretive hook it needs.**

## Measurement boundary

Three read-only `mcp__seon__eval_clj` calls in `jvm` mode against cluster
`default`, custody `(seon.operator/connection "default")`, 2026-09-16 in the
owner's long-lived JVM under ordinary load. No transaction, no test JVM, no
`bin/seon` state change. Single observations on a warm JVM, not distributions.

Two facts bound every "stored" number below:

- The stored side is `default`'s **published `:current-src` at measurement
  time**. Commit `15a35c2a7` *"Attribute protocol and multimethod body calls to
  dispatch identities"* (2026-09-16 13:23) had landed in HEAD, and its effect is
  NOT visible in the measured rows (`seon.print/emit` still has zero out-edges
  on `default`). Numbers here describe the cluster, not HEAD source.
- Costs quoted from
  [selection-efficiency-2026-09-17.md](selection-efficiency-2026-09-17.md) §1b
  are taken, not re-measured: `seon.fn/gate-set`'s frontier walk over AVET is
  **14.181 ms** for the worst seed (1,009 tests) against **6,753 ms** for the
  recursive rule, `datahike.query/solve-rule` does not memoise
  (`reference-code/datahike/src/datahike/query.cljc:1321`), Datahike has **no
  VAET** — every `:db.type/ref` is implicitly `:db/index`, so AVET *is* the
  reverse index (`reference-code/datahike/src/datahike/db/utils.cljc:312`) —
  and `db/pull` caps a cardinality-many attribute at 1000
  (`reference-code/datahike/src/datahike/pull_api.cljc:16`).

---

## 1. Runtime-exact edges for JVM-loaded code (the constant pool)

### 1.1 What orchard actually does

`reference-code/orchard/src/orchard/xref.clj` is 122 lines and the whole
mechanism is three of them.

- `fn-deps-class` (`xref.clj:27-41`) reflects over `(.getDeclaredFields c)` of a
  compiled function class and keeps every field that is `public static`, of type
  `clojure.lang.Var`, and named `const__*`. Those are the constant pool entries
  the Clojure compiler emits for **every Var a compiled body mentions, in any
  position**: a direct call, a higher-order argument, an `apply`/`partial`/`comp`
  operand, and a `#'f` var-quote all compile to a read of the same `const__N`
  field. The compiler does not record the call shape, so the constant pool
  answers "which Vars does this body mention", not "which does it call".
- `fn-deps` (`xref.clj:54-83`) adds the anonymous functions: it reads Clojure's
  package-private `clojure.lang.DynamicClassLoader/classCache`
  (`xref.clj:43-52`, `.setAccessible true`) and unions the constant Vars of
  every cached class whose **name starts with the target function's class
  name** — the compiler's own naming convention for lambdas. If that union is
  empty it falls back to the class itself (the AOT case, `xref.clj:76-78`), then
  re-resolves every Var by symbol so a re-evaluated definition does not leave a
  stale Var object (`xref.clj:79-83`).
- `fn-transitive-deps` (`xref.clj:85-100`) is a plain worklist loop over
  `fn-deps`, no memoisation.
- `fn-refs` (`xref.clj:115-122`) is the REVERSE direction and is brute force:
  `orchard.query/vars` over every project namespace, then `fn-deps` on each,
  filtered by membership. It has no index.

Its own documented limits (`xref.clj:55-60`): `:inline` core functions (`+`,
`-`) are compiled away and never appear; a redefined function leaves old lambda
deps in the cache; macros are expanded before compilation, so a macro body's
calls are attributed to the **expansion site**, not to the macro Var.

### 1.2 Orchard is not on the classpath — I ran the algorithm, not the library

`grep orchard deps.edn` returns nothing, and `(require 'orchard.xref)` on
`default` answered `:absent` (query 1). I did not add it. I inlined
`fn-deps-class` and the class-cache scan into the probe forms, which is the
same algorithm read from `xref.clj:27-83`.

### 1.3 The first measured result: instrumentation blinds the constant pool

Query 1 returned **zero** first-party deps for `seon.fs.jvm/read`,
`seon.turn/step` and `seon.cluster.agent/graph-definition`. Query 2 explains
it exactly:

| Var | class of the Var's root | `const__` Var fields | class-cache entries under that name |
|---|---|---:|---:|
| `seon.fs.jvm/read` | `clojure.lang.AFunction$1` | **0** | **0** |
| `seon.turn/step` | `clojure.lang.AFunction$1` | **0** | **0** |
| `seon.cluster.agent/graph-definition` | `clojure.lang.AFunction$1` | **0** | **0** |

`seon.instrument/arm-var!` (`src/seon/instrument.clj:617-651`) replaces the Var
root with a variadic closure over `original` and `compiled-wrapper`
(`:635-644`). Closed-over locals are constructor fields, not `const__` static
fields, so **an armed Var's constant pool is empty and its class is not in the
class cache**. `orchard.xref/fn-deps` on any armed Seon function returns `∅`.
Query 3: **1,112 of 4,646 first-party function Vars on `default` are armed**, so
this is not an edge case.

The wrapper carries `:malli.instrument/original` in its metadata
(`src/seon/instrument.clj:646`). Unwrapping through that key and scanning the
ORIGINAL class restores the exact view:

| Var | original class | total const Vars | first-party |
|---|---|---:|---:|
| `seon.fs.jvm/read` | `seon.fs.jvm$read` | 21 | 14 |
| `seon.turn/step` | `seon.turn$step` | 20 | 9 |
| `seon.cluster.agent/graph-definition` | `seon.cluster.agent$graph_definition` | 14 | 7 |

**Any runtime-constant-pool source Seon builds must unwrap
`:malli.instrument/original` first.** A note asserting "orchard gives us exact
edges" without that step would measure the wrapper and silently report nothing
— the project's named failure class.

### 1.4 What the runtime view supplies, measured

Query 3 built the index once (one pass over the class cache, 56,247 entries;
**37,439** first-party classes indexed in **4,267.4 ms**), then derived
unwrapped deps for all **4,646** first-party function Vars in **64.8 ms** —
**9,480** first-party out-edges in total, with **1,166** Vars showing zero
(bodies that mention no first-party Var, plus classes evicted from the cache).

Against `default`'s stored `:seon.fn/calls` out-edges, first-party only:

| function | class from the fidelity note | runtime | stored (first-party) | supplied by runtime only | lost by runtime |
|---|---|---:|---:|---|---|
| `seon.cluster.agent/graph-definition` | `#'f` var-quote (shape 15/6) | 7 | 4 | **`seon.cluster.agent/mailbox-step`, `seon.schedule/schedule-step`, `seon.turn/step`** | none |
| `seon.fs.jvm/read` | capability handler | 14 | 1 | 13 helpers (`read-window`, `open-read-channel`, `whole-file-pass`, `window-pass`, `readable-file`, `refuse!`, …) | none |
| `seon.turn/step` | flow step-fn | 9 | 9 | none | none |

The `graph-definition` row is the clean proof for the var-quote class: the
three step-fns referenced as `#'mailbox-step`, `#'turn/step` and
`#'schedule-step` (`src/seon/cluster/agent.clj:454,460,467`) are exactly the
three edges the runtime supplies and static analysis drops. `seon.turn/step`
shows the honest opposite: where clj-kondo already attributes the body, the
runtime adds nothing.

### 1.5 defmethod and protocol-impl bodies — reachable, but NOT by name prefix

Query 1, reaching bodies through the runtime dispatch tables rather than the
class-name prefix:

- `(methods seon.print/emit)` — **25 methods**; the union of their constant
  Vars is **14** first-party and **contains `seon.print/emit-sequential`**, the
  function the fidelity note measured at zero reach. Stored out-edges for
  `seon.print/emit` on `default`: **`[]`**.
- `(methods seon.issue.opening/render-candidate)` — **8 methods**, union **10**
  first-party (`block`, `check-form`, `comment-lines`, `commented-form`,
  `default-candidate`, `function-pull-form`, `render-candidate`, `status-form`,
  `test-pull-form`, `seon.repl/source-text`). Stored out-edges: **`[]`**.
- `(Class/forName "seon.print.TextSink")` — the `deftype` at
  `src/seon/print.cljc:213` compiles its protocol-method bodies INTO the class,
  so its own constant pool yields **3** first-party Vars
  (`append-chunk!`, `literal`, `soft-separator`) with no dispatch table needed.

But query 3, which used orchard's **class-name prefix** rule, found **zero**
runtime callers of `seon.print/emit-sequential` across all 4,646 Vars. The
reason is structural: a `defmethod` body compiles to `seon.print$fn__NNNN`,
whose name is not prefixed by any Var's class name, so orchard's rule assigns
it to **no** function. **`orchard.xref/fn-deps` cannot see defmethod bodies.**
Reaching them requires enumerating the runtime dispatch tables explicitly —
`clojure.core/methods` for a `MultiFn`, `(:impls @#'Protocol)` plus
`Class/forName` for `deftype`/`defrecord` — which orchard does not do and which
query 1 shows works.

### 1.6 What the runtime view CANNOT supply

1. **The direction selection needs.** Reach is a REVERSE query: which tests
   reach `f`. The constant pool is a forward view per class; the reverse is
   `orchard.xref/fn-refs` (`xref.clj:115-122`), a brute-force scan with no
   index. More decisively: **test namespaces are not loaded in the cluster
   JVM.** Query 3 found **zero** runtime callers of `seon.fs.jvm/read` — the
   callers are in `test/seon/fs/jvm_test.clj`, which `default` never loads. A
   runtime source recorded at publication or adoption can only ever supply
   **src → src** edges. Test → function edges remain the static analyzer's job,
   or a test-JVM recording.
2. **Runtime resolution.** `test/seon/fs/jvm_test.clj:22` reaches the handlers
   through `(deref (ns-resolve 'seon.fs.jvm operation))` where `operation` is a
   runtime value. No `const__` field exists for a Var that was never named
   literally. Shape 13 stays open under the constant pool too.
3. **Macro attribution.** Macros are expanded before compilation
   (`xref.clj:58-60`), so a macro body's calls land in every expansion site's
   constant pool and never on the macro Var. That over-approximates in the safe
   direction but destroys the macro's own out-edges.
4. **`:inline` core functions** never appear (`xref.clj:58-60`). Irrelevant for
   first-party selection.
5. **Cost and freshness.** 4.27 s to build the index over 37,439 classes, and
   the cache is a map of `SoftReference`s — evicted classes silently yield
   nothing. A source of edges that returns `∅` under memory pressure is the
   absence-as-health defect unless it reports the eviction. Once built,
   deriving all 4,646 rows is 64.8 ms, so the cost is entirely the one pass.
6. **Symbols in config facts** (shape 14) are data, never compiled, and the
   constant pool sees nothing.

---

## 2. Exact edges for agent-authored code, from SCI

### 2.1 SCI resolves every symbol to a Var at analysis time

`sci.impl.analyzer/analyze` (`reference-code/sci/src/sci/impl/analyzer.cljc:2359`)
is the single entry, and every symbol passes through
`sci.impl.resolve/resolve-symbol`:

- **Value position** — `analyzer.cljc:2366`:
  `(resolve/resolve-symbol ctx expr false m)`, `call? = false`. This is the
  shape the static analyzer cannot see at all: a HOF argument, an `apply` /
  `partial` / `comp` operand, a bare symbol. The analyzer knows the resolved
  Var and emits `[:vderef v]` (`:2381-2384`).
- **Call position** — `analyze-call` at `analyzer.cljc:1972`, resolving at
  `:1985` with `call? = true`; the argument count is `(count (rest expr))`, so
  the **call shape and the arity are both available here**, which is exactly
  what `:seon.fn/call-arities` stores.
- **Var-quote** — `analyze-var` (`analyzer.cljc:1509-1512`) resolves `#'f`
  through `resolve/lookup` with `only-var? true`.
- The common floor for all three is `sci.impl.resolve/lookup*`
  (`reference-code/sci/src/sci/impl/resolve.cljc:40-171`) returning `[sym v]`,
  wrapped by `lookup` (`:205-253`) and `resolve-symbol*` (`:255-269`).
  Both are public in a `{:no-doc true}` namespace, but **the analyzer captures
  `resolve/resolve-symbol` directly**, so intercepting from outside would mean
  `alter-var-root` on a dependency — a process-global mutation, which §2.1
  ("values carry their world") forbids. The observer must ride the ctx.

Crucially, **SCI expands macros during analysis** (`analyze-call`), so a macro
body's calls in agent code are analysed and would be observed — closing shape
12 for agent-authored source.

### 2.2 The accretive addition, and its two precedents in our fork

`reference-code/sci` is our submodule (`git remote` → `seantempesta/sci`), and
two of its local commits are exactly this pattern:

- `47f6c8b5` *"Observe host interop during SCI analysis"* —
  `observe-host-interop!` (`analyzer.cljc:57-60`) reads
  `:host-interop-observer` from the ctx and calls it at
  `analyzer.cljc:1157` and `:1304`. Documented at
  `reference-code/sci/src/sci/core.cljc:300-301`.
- `af8a5fb9` / `a27e2c0e` *"executed built-in call observation"* —
  `:built-in-call-observer`, a one-arg fn called with a fully-qualified symbol,
  read from the RUNTIME ctx at `analyzer.cljc:1806-1811`, documented at
  `core.cljc:303-308`, threaded through `sci.impl.opts` at
  `reference-code/sci/src/sci/impl/opts.cljc:213,238,266,365-375`.

So the shape is settled: **a `:var-reference-observer` ctx key, a two-or-three
arg observation fn, called from the three analysis sites above with the
resolved Var and the call shape.** It is an option key, refused if unknown by
`f934044d` *"Refuse unknown option keys"*, and it must be added to the three
`opts.cljc` sites and `core.cljc`'s docstring in the same commit. Seon already
installs both existing observers — `src/seon/sci/kernel.clj:87,94,106` and
`src/seon/sci/eval.clj:207,213` — so the wiring at our end is one more key.

**Exactness and limits.** Analysis-time observation is exact for every
lexically present symbol including value positions and macro expansions, with
the call shape and arity attached. It cannot see `(requiring-resolve 'ns/f)` or
a symbol pulled from a config value, because those resolve at run time, not
analysis time. For those the existing `:call-preparation-hook`
(`core.cljc:310-321`, `analyzer.cljc:1777-1805`) fires at CALL time on the
calling thread for every direct call whose callee is a Var — but its own
docstring is explicit that "calls through a computed callee, a self-reference,
or a non-Var value are not hooked", so `apply`, `partial` and `comp` are out.
An analysis-time observer is the complete answer for what is written; nothing
in SCI closes what is computed.

**What this buys.** `seon.fn/runtime-analysis-batch`
(`src/seon/fn.clj:687`, called at `:876` and `:1029`) currently runs agent
source back through the SAME clj-kondo analyzer, so agent-authored code inherits
every static hole (fidelity note §3, row 17). An observer at the evaluation
seam replaces inference with the resolution the evaluator already performed.

---

## 3. The static side we still do not consume

`src/seon/fn/analyzer.clj:19-33` now requests
`{:arglists true :var-usages true :protocol-impls true :keywords true
:var-definitions {...} :namespace-definitions {...}}` — `:protocol-impls` was
turned on by `15a35c2a7`, so the fidelity note's "we request none of them" is
stale as of HEAD. Still unrequested, with what each would close:

| clj-kondo output | emitted at | closes |
|---|---|---|
| **`:symbols`** | `reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:60-85`, documented `reference-code/clj-kondo/analysis/README.md:191-199` | **shapes 13 and 14.** "A list of (namespaced) symbols that occur in quoted forms or EDN, i.e., they do not refer to vars or interop", carrying `:from`, `:name`, `:to` and a full span. `'seon.fs.jvm/read` inside a config map and `(requiring-resolve 'probe/target)` both become facts. This is the single highest-value unused output and it needs no hook. |
| `:defmethod` / `:dispatch-val-str` on var-usages | `impl/analysis.clj:33,41` | distinguishes WHICH `defmethod` a body belongs to, so the dispatch-val can ride the edge instead of collapsing into the `defmulti`. `15a35c2a7` attributes to the dispatch identity; these two keys would let a later refinement name the method. |
| `:protocol-impls` (now on) | `impl/analysis.clj:192-214`, README `:181-189` | shape 8. Gives `:protocol-ns/:protocol-name/:method-name/:impl-ns` plus the method span for the span-containment join. |
| `:keywords` `:reg` | `impl/analysis.clj:176-190`, README `:178-180` | nothing here: `:reg` is set by a hook calling `clj-kondo.hook-api/reg-keyword!`, i.e. it only reports what a hook we would have to write already knows. |
| `:instance-invocations`, `:java-class-usages` | `impl/analysis.clj:216-233`, README `:214-224` | Java interop reach only. Not an E2 hole; diagnostics, not correctness. |
| `:analyze-macros` / hooks for our own macros | `reference-code/clj-kondo/doc/hooks.md` | shape 12 (`(mac x)` reaching the macro's body). **We have no hook configs of our own**: `.clj-kondo/` holds `config.edn`, `.cache/`, `imports/` (vendored), `inline-configs/` (extracted from dependency jars — `datahike.api`, `hyperlith.*`, `malli`, test corpora) and `metosin/`. Writing per-macro hooks is one config file per macro, maintained by hand — a hand-maintained mirror of what SCI's analyzer resolves for free. |

**Open by construction, whatever we enable:** a callee computed at run time.
`(apply f xs)` where `f` is a parameter, `(requiring-resolve (symbol s))` where
`s` is data, and a multimethod dispatch value derived from a database read. No
static analysis and no constant pool answers these; only an honest typed
unknown does.

---

## 4. Storage: provenance on the edge, and no stored closure

### 4.1 Do not materialise the closure

- `:seon.fn/calls` is `[:set :seon.db/ref]`
  (`resources/seon/schemas/seon.fn.edn:21`). Datahike gives every
  `:db.type/ref` `:db/index` implicitly
  (`reference-code/datahike/src/datahike/db/utils.cljc:312`) and has **no
  VAET**, so AVET is the reverse index and the frontier walk in
  `seon.fn/gate-set` (`src/seon/fn.clj:1157-1211`) is an indexed seek per node:
  **14.181 ms** for the worst seed, against **6,753 ms** for the equivalent
  recursive rule (`datahike.query/solve-rule`,
  `reference-code/datahike/src/datahike/query.cljc:1321`, no memoisation).
- Index nodes are cheap either way: Datahike's persistent sorted set uses
  `DEFAULT_BRANCHING_FACTOR 512`
  (`reference-code/datahike/src/datahike/index/persistent_set.cljc:471`), so
  68,930 `:seon.fn/calls` datoms are ~135 leaves under one root — a two-level
  tree. Depth is not the cost; the datom count is.
- The stored closure already exists and already shows the cost:
  `:seon.test/reach` is `[:vector {:seon.db/cardinality :many} :seon.db/ref]`
  (`resources/seon/schemas/seon.test.edn:2`), and **229,262 reach datoms are
  held by only 281 tests** (mean 816). Extrapolated to all 1,829 declared
  tests that is ~1.5M datoms to maintain — 21× the edge set it is derived from.
- And it is **unsafe to read back through `pull`**: `db/pull` caps a
  cardinality-many attribute at `+default-limit+ 1000`
  (`reference-code/datahike/src/datahike/pull_api.cljc:16`), silently. Any
  materialised closure read through pull is truncated by construction, which is
  precisely the defect `selection-efficiency-2026-09-17.md` §1a already filed
  against `seon.test/changed-since-green:63` and
  `seon.test.runner/held-members:2229`.

**Recommendation: no stored transitive closure for selection.** Close per
query over AVET. `:seon.test/reach` stays what its schema says it is — result
provenance for a run that happened — and every reader of it uses
`db/datoms`, never `pull`.

### 4.2 Provenance as one attribute, not a second edge family

Today an edge is a bare ref: `:seon.fn/calls` says *that* A reaches B and
nothing about how we know. With three or four sources feeding the same set, a
query must be able to answer "how do you know" — and a selection that widens on
weak evidence needs to see the weakness. The §2.5 rule (one mechanism) and the
"no second registry" rule both say: keep ONE edge family.

A cardinality-many ref cannot carry an attribute on the edge, so the
accretive shape is a **component entity per (caller, callee) pair**, exactly
like `:seon.fn/arities` (`resources/seon/schemas/seon.fn.edn:19`), with
`:seon.fn/calls` retained unchanged as the reach index that `gate-set` walks.
The pair rows are the provenance; the ref set is the index.

```clojure
;; resources/seon/schemas/seon.fn.edn — accretion, nothing narrowed
:call-edges
[:set {:description
       "One component row per distinct callee this declaration reaches,
  carrying the evidence for that edge. `:seon.fn/calls` remains the ref
  set the reach walk seeks on; this set only explains it, so a query can
  report HOW an edge is known and a selector can widen on weak evidence.
  Population is exactly the `:seon.fn/calls` set."}
 :seon.db/ref]

;; new namespace: seon.fn.edge
#:seon.fn.edge
{:callee [:and {:description "The reached declaration."} :seon.db/ref]
 :source [:set {:description
                "Every source that observed this edge, one keyword each.
  `:analysis` — clj-kondo `:var-usages` with an arity and a caller
  attribution. `:declared` — a symbol named in a stored fact the system
  already keeps (`:seon.fn/capability-fn`, a flow `:step`, a render pair);
  the edge is minted where that fact is minted, never re-derived.
  `:constant-pool` — a `const__` Var field of the caller's compiled class
  or one of its lambdas, read after unwrapping
  `:malli.instrument/original`; forward-only and only for code loaded in
  the publishing JVM. `:sci-resolution` — the Var SCI's analyzer resolved
  while analysing this form, exact for agent-authored source.
  A set, not one value: two sources observing the same edge is
  corroboration, and the set is how the query says so."}
          [:enum :analysis :declared :constant-pool :sci-resolution]]
 :shape [:enum {:description
                "How the callee was named at the observation site: a
  direct call, a value reference (a higher-order argument, an `apply` or
  `partial` operand, a `#'f` var-quote), or a symbol in stored data. A
  value reference is an edge — the callee is reachable — but it carries
  no arity, which is why `:seon.fn/call-arities` stays narrower than the
  edge set."}
         :call :value-reference :declared-symbol]}
```

The remaining open shapes get the typed unknown the fidelity note's Option B
proposes — `:seon.fn/unresolved-reference` beside `:seon.fn/pending-calls`
(`resources/seon/schemas/seon.fn.edn:76`) — modelled on
`:seon.test/reach-unknown` (`resources/seon/schemas/seon.test.edn:4`), whose
docstring already states the law: absence of a membership is never proof of an
empty closure.

### 4.3 The Datalog that uses it

`seon.fn/gate-set`'s walk is unchanged — it still seeks
`(db/datoms database :avet :seon.fn/calls entity)` — because
`:seon.fn/calls` keeps its population. Provenance enters only where a caller
asks for it. "Tests reaching `f`, with how each hop is known":

```clojure
;; one hop, joined to its evidence — the shape gate-set's loop repeats
'[:find ?caller-sym ?source ?shape
  :in $ ?callee-sym
  :where
  [?callee :seon.fn/sym ?callee-sym]
  [?edge   :seon.fn.edge/callee ?callee]
  [?caller :seon.fn/call-edges ?edge]
  [?caller :seon.fn/sym ?caller-sym]
  [?edge   :seon.fn.edge/source ?source]
  [?edge   :seon.fn.edge/shape ?shape]]

;; "is this reach evidence weak?" — the widening predicate, one clause
'[:find [?caller-sym ...]
  :in $ ?callee-sym
  :where
  [?callee :seon.fn/sym ?callee-sym]
  [?edge   :seon.fn.edge/callee ?callee]
  [?edge   :seon.fn.edge/shape :value-reference]
  [?caller :seon.fn/call-edges ?edge]
  [?caller :seon.fn/sym ?caller-sym]]
```

Neither leaves AVET. The added cost is one component row per distinct
(caller, callee) pair — bounded by the existing edge count (68,930 on
`default`), not by the closure (229,262 for 15% of tests).

---

## 5. Verdict and options

Simplest first. Each is additive to the one already landed
(`15a35c2a7`, protocol and defmethod body attribution).

### Option A — `:symbols` plus declared-symbol edges. No new runtime, no fork change.

Turn on `:symbols` in `src/seon/fn/analyzer.clj:19-33`, project
`:symbol/:from/:to` with the span, and mint an edge with
`:seon.fn.edge/shape :declared-symbol` wherever the symbol names a first-party
declaration. Mint `:seon.fn.edge/source :declared` from the facts we already
store — `:seon.fn/capability-fn` (`src/seon/fn.clj:611`) IS an edge — and admit
the var-quote usage as a `:value-reference` edge of unknown arity.

- **Guarantee:** shapes 6, 13, 14, 15 close. All 8 capability handlers,
  `seon.turn/step` and `seon.bootstrap/doc` gain real reach. Every edge is
  derived from the same analysis pass the gate already runs.
- **Cost:** one analysis vector, one span join, one `cond->` clause. No new
  process, no dependency change, no JVM reflection.
- **Give up:** a symbol in a docstring or a comment becomes an edge — an
  over-approximation in the safe direction. `apply`/`partial`/`comp` on a
  computed callee stays open.
- **Regression:** a fixture namespace with one call of each shape — direct,
  HOF argument, `apply`, `partial`, `#'f`, a quoted symbol in a map, a
  `defmethod` body, a protocol impl body — asserting for each the
  `:seon.fn/calls` edge, its `:seon.fn.edge/source` and `:seon.fn.edge/shape`,
  and that `tests-reaching` on the target names the fixture test.

### Option B — A, plus the typed unknown

Everything in A, and `:seon.fn/unresolved-reference` for the shapes that stay
open, with `gate-set` returning the typed unknown and
`seon.test.selection/widening-path?` (`src/seon/test/selection.clj:108-116`)
widening on it.

- **Guarantee:** E2 becomes sound. No function is silently unselected; weak
  evidence widens instead of vanishing. This is what kills the
  absence-as-health class.
- **Cost:** one attribute, one branch in `gate-set`, one widening branch.
- **Give up:** selection sharpness where `partial`/`comp`/our own macros are
  dense.
- **Regression:** a fixture whose only path to the target is through `apply`;
  assert `tests-reaching` returns the typed unknown, not `[]`, and that the
  runner widens.

### Option C — B, plus SCI resolution for agent-authored code

Add a `:var-reference-observer` ctx key to our sci fork, at
`analyzer.cljc:1985` (call), `:2366` (value) and `:1509` (var-quote), threaded
through `opts.cljc:213,238,266,365-375` and documented at `core.cljc`, exactly
as `:host-interop-observer` and `:built-in-call-observer` already are. Seon
installs it beside them at `src/seon/sci/eval.clj:207-213` and records
`:seon.fn.edge/source :sci-resolution` for admitted agent definitions,
replacing `runtime-analysis-batch`'s inference (`src/seon/fn.clj:687`) for
that population.

- **Guarantee:** agent-authored code gets an EXACT graph with call shapes and
  arities, including through macro expansion — better than the static side can
  ever be, and it removes a second analysis pass rather than adding one.
- **Cost:** a fork commit in `reference-code/sci` (four files), plus the
  installation. The observer runs once per form at analysis, not per call.
- **Give up:** nothing for first-party source, which SCI never analyses;
  runtime-computed callees stay unknown (Option B covers them).
- **Regression:** evaluate through the real SCI context one form of each shape
  (direct, HOF argument, `apply`, `#'f`, a macro call) and assert the recorded
  edge, its `:sci-resolution` provenance, its shape, and the reach.

### Option D — constant-pool edges at publication. **Recommended AGAINST for now.**

Recording unwrapped `const__` Var deps for every JVM-loaded first-party
function at publication or adoption.

- **What it genuinely buys:** measured above, `graph-definition` +3 edges,
  `seon.fs.jvm/read` +13 — but Option A closes both by other means, without
  reflection.
- **What it cannot buy:** the direction selection needs. Test namespaces are
  never loaded in the cluster JVM, so it supplies **zero** test → function
  edges (query 3: zero runtime callers for `seon.fs.jvm/read` and
  `seon.print/emit-sequential`).
- **What it costs:** a 4,267 ms single pass over 37,439 classes; mandatory
  `:malli.instrument/original` unwrapping or it silently returns `∅` for 1,112
  armed Vars; a defmethod-aware dispatch-table walk because orchard's
  class-name-prefix rule assigns those bodies to no function; and a
  `SoftReference` cache that can evict a class between passes. Four ways to
  return "nothing" that look like "no edges".
- **Where it IS the right tool:** as a **falsifier**, not a source. A periodic
  check that compares the stored graph against the constant-pool view on
  `default` and reports edges the static side is missing would have found every
  hole in the fidelity note by itself, in 4.3 s. That is a maintenance-portfolio
  task, and it reports rather than writes.

**Recommendation: B, then C.** B makes selection sound with no dependency
change; C makes the agent-authored half exact and deletes a duplicate analysis
pass. Option D's algorithm lands as a drift check, never as a writer.

---

### Issues to file (out of this lane's scope)

- `orchard.xref/fn-deps`-style constant-pool reads return `∅` for any armed
  Var; **1,112 of 4,646** first-party function Vars on `default` are armed.
  Any future runtime source must unwrap `:malli.instrument/original`
  (`src/seon/instrument.clj:646`). Severity: cleanup, but a blocker for
  anything that adopts the technique.
- `default`'s published graph still shows zero out-edges for
  `seon.print/emit` and `seon.issue.opening/render-candidate` although
  `15a35c2a7` landed in HEAD — the cluster's `:current-src` needs a
  publication before the fix can be verified live. Severity: friction.
