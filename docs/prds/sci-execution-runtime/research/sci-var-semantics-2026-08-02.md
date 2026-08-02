---
type: research
status: active
tags: [sci, database, runtime]
---

# SCI Var semantics and the `seon.db` install surface

## Direct verdict — the per-cluster SCI Var does not feed compiled code

**No: the proposed design does not work as described.** A compiled host
function resolves and dereferences its compiled `clojure.lang.Var`; it does not
look up a same-named `sci.lang.Var` in whichever SCI context invoked it.
`sci/copy-var`, `sci/copy-var*`, `sci/new-var`, and `sci/intern` all produce
SCI Vars, not alternate bindings of the compiled Var. `sci/binding` therefore
changes what interpreted code sees through that SCI Var but does not change
what a compiled callee sees.

The nearest design that preserves an SCI-side agent-facing name is explicit
custody transfer at the evaluator boundary: obtain the cluster connection from
the evaluation request or cluster context, then either pass it to compiled code
as an argument or establish a Clojure thread binding on the real compiled
`seon.db/*conn*` for the complete call and result-realization interval. An
SCI-side Var may separately provide the interpreted name, but it cannot be the
mechanism by which the compiled function receives custody. The current
`evaluate` implementation already uses the latter compiled-Var binding
(`src/seon/sci/eval.clj:1421-1446`).

**Confidence: high.** This verdict is supported by the maintained SCI source
and a JVM probe against the repository dependency graph.

## Dependency ledger and method

- Maintained SCI revision: `6de15683b7520cc973bc9c136aec7ad3f9b3788c`.
- Var construction and binding:
  `reference-code/sci/src/sci/core.cljc:41-57,76-136,139-145,259-270`;
  `reference-code/sci/src/sci/impl/copy_vars.cljc:139-165`;
  `reference-code/sci/src/sci/impl/vars.cljc:99-164`;
  `reference-code/sci/src/sci/lang.cljc:71-156`.
- Analysis and evaluation:
  `reference-code/sci/src/sci/impl/analyzer.cljc:1909-1926,2085-2127,2296-2321`;
  `reference-code/sci/src/sci/impl/interpreter.cljc:29-83`.
- Seon's compiled install and custody seams:
  `src/seon/sci/eval.clj:908-932,979-1004,1393-1446` and
  `src/seon/db.clj:15-17,45-56`.
- Probe surface: raw JVM via `clojure -M:dev`; no cluster was started, stopped,
  reset, or written, and no test runner was invoked.

## Question 1 — exact semantics

### `copy-var`, `copy-var*`, and `new-var`

`sci/new-var` constructs a new `sci.lang.Var` with its own root and metadata
(`sci/core.cljc:41-48`). `sci/copy-var` macroexpands to another new
`sci.lang.Var` whose initial root is the host value at construction
(`sci/core.cljc:76-109`; `sci/impl/copy_vars.cljc:139-165`). Its runtime
counterpart `copy-var*` reads `@clojure-var` once and passes that value to
`new-var` (`sci/core.cljc:111-136`). Copying preserves selected metadata,
including `:dynamic` and `:private`, but does not preserve Var identity or link
later roots.

The probe observed:

```clojure
{:classes {:host-x clojure.lang.Var
           :copied-x sci.lang.Var
           :new-var sci.lang.Var}
 :copy-independent
 {:before [:host-root :host-root]
  :after-host-root-change [:host-root-2 :host-root]}}
```

Changing the compiled Var root after `copy-var*` did not change the SCI Var
root.

### `sci/binding` versus a compiled callee

SCI's thread-binding frames are keyed by the SCI Var object
(`sci/impl/vars.cljc:35-45,115-164`). `sci.lang.Var/deref` checks that frame
before its own root (`sci/lang.cljc:148-156`). The compiled function in the
probe was created in a JVM namespace as `(defn read-x [] *x*)`; its body names
the compiled Clojure Var.

With a copied SCI `*x*` and a copied root containing the compiled `read-x`
function, the probe returned:

```clojure
{:sci-binding [:sci-thread :host-root]
 :host-var-under-sci-binding
 "Can't dynamically bind non-dynamic var #'probe.host3/*x*"}
```

The vector is `[probe.host3/*x* (probe.host3/read-x)]` under
`sci/with-bindings {copied-x :sci-thread}`. Interpreted dereference saw the SCI
thread binding; the compiled call still read the compiled root. SCI's binding
machinery does not treat a `clojure.lang.Var` as one of its dynamic Vars, so it
cannot bind the host Var directly.

### Clojure `binding` versus interpreted code

Seon currently installs actual host Vars, not copies:
`install-loaded-first-party-namespaces!` passes `ns-interns` directly to
`sci/add-namespace!` (`src/seon/sci/eval.clj:908-932`). With that exact shape,
a Clojure `with-bindings` on the host dynamic Var produced:

```clojure
{:compiled-callee-under-host-binding :host-thread
 :explicit-host-var-deref-in-sci :host-thread}
```

There is one important hygiene nuance. SCI's analyzer recognizes only
`sci.lang.Var` as a Var (`sci/impl/utils.cljc:356-357`). Therefore a bare host
Var installed through `add-namespace!` evaluates in value position to the
`clojure.lang.Var` object itself, while `(deref (var probe.host/*x*))` observes
the Clojure thread binding. In function position the host Var remains callable,
and a compiled callee observes that same binding. With a copied SCI Var instead,
the interpreted name dereferences normally but Clojure `binding` of the host
Var does not affect it.

### `sci/intern` of a plain value

`sci/intern` delegates to `sci-intern` (`sci/core.cljc:259-270`). Supplying a
plain value either binds the existing SCI Var root or creates a new SCI Var
with that value (`sci/impl/namespaces.cljc:610-634`). The probe returned:

```clojure
{:class sci.lang.Var
 :same-object-as-resolve true
 :value 7}
```

It does not create a Clojure Var and does not establish any link to a compiled
same-named Var.

### Explicit SCI-context access is possible but is not same-name resolution

A specially written compiled function can call `sci.ctx-store/get-ctx` while
SCI is evaluating it, then explicitly resolve or intern an SCI Var in that
context. `eval-form` establishes the current SCI context around evaluation
(`reference-code/sci/src/sci/impl/interpreter.cljc:80-83`). A probe using such
a host function returned the per-context value while called through SCI and
failed with `No context found` outside SCI. This is an explicit dependency on
SCI internals, not transparent resolution of the compiled function's
same-named Var. It would also make `seon.db` calls outside SCI require a second
custody path, so it is not the smallest design.

## Question 2 — symbols bind to Var objects during analysis

**Verdict:** ordinary namespace symbols are resolved during analysis to a
specific object. They are not looked up again by name in the context current at
each invocation.

`lookup*` reads the analyzing context's env and namespace map
(`reference-code/sci/src/sci/impl/resolve.cljc:40-53,71-72,137-153`), and
`resolve-symbol` either returns that object or raises an analysis-phase error
(`sci/impl/resolve.cljc:323-334`). In value position the analyzer embeds a node
that closes over the resolved SCI Var and dereferences it when evaluated
(`sci/impl/analyzer.cljc:2296-2321`). Call position also resolves during
analysis and embeds the resulting callee in the call node
(`sci/impl/analyzer.cljc:1909-1926,2085-2127`). Function construction retains
the analyzed body and creating context (`sci/impl/analyzer.cljc:356-386`;
`sci/impl/fns.cljc:39-53,63-78`).

Two explicit exceptions delimit that statement:

- `(resolve 'x)` deliberately performs a runtime lookup through the context
  SCI has made current. A function from A with a static `x` returned A's value
  when invoked by B, while an A-created function using `@(resolve 'x)` returned
  B's value.
- A `^:const` SCI Var is even earlier-bound: the analyzer embeds its current
  value rather than a Var dereference (`sci/impl/analyzer.cljc:2306-2309`). A
  probe defining const `x=1`, defining `f`, then redefining `x=2` returned
  `{:x 2 :old-f 1}`.

**Confidence: high (0.99)** for ordinary-symbol timing and the two stated
exceptions.

### A closure from context A invoked by context B

The probe created independent contexts A and B, defined `x` and `f` in A,
installed the root function value of A's `f` under the name `from-a` in B, and
called `(from-a)` through B:

```clojure
{:cross-context
 {:before :a1
  :after-b-redef :a1
  :after-a-redef :a2}
 :redefinition
 {:same-var-object? true
  :a-root :a2}}
```

B's own `x` and its redefinition were irrelevant. Redefining A's `x` changed
what the old closure returned because `init-var!` creates a Var only when the
name is absent (`sci/impl/analyzer.cljc:775-806`) and `eval-def` reuses the
existing Var and calls `bindRoot` (`sci/impl/evaluator.cljc:25-47`). Thus normal
same-context `def` and `defn` redefinition preserves Var identity, and already
analyzed closures observe the new root.

Replacing an env entry by unmap plus reintern is different: old closures keep
the old object even when the namespace name later maps to a new Var.

### Session-image restore creates a fresh object graph

`install-session-image!` has three ordered passes:

1. create namespaces and `sci/intern` every name unbound;
2. bind faithful inline or blob-backed values; and
3. evaluate proven source rows in the cold context.

The implementation is `src/seon/sci/eval.clj:1148-1201`; unrestorable names
remain pre-interned but unbound at lines 1202-1209. Re-interning binds the
existing Var root rather than replacing the Var
(`sci/impl/namespaces.cljc:610-634`).

Consequently, restore does not preserve the hot process's Var identity or an
old closure. It creates fresh cold Vars, then source evaluation is reanalyzed
against those fresh objects. Future ordinary redefinitions in the restored
context mutate those same fresh objects. A phase-equivalent raw SCI probe
returned:

```clojure
{:old-and-restored-var-same? false
 :before :restored-x
 :after-redef :restored-x2}
```

The repository's existing cold-restore proof exercises the complete database
path, including a closure over a later-redefined `limit` and a function nested
in a map (`test/seon/sci/session_image_test.clj:239-317`). This research did
not invoke the database-backed restore because the task prohibited cluster
writes and test execution; the new runnable probe directly verifies the SCI
object semantics, while the cited recurring test is existing evidence.

**Confidence: high (0.97).** The SCI semantics and Seon pass ordering are
verified; a new end-to-end database-backed invocation was intentionally not
run.

### `sci/fork` is structural isolation, not Var-root isolation

`sci/fork` is exactly:

```clojure
(update ctx :env (fn [env] (atom @env)))
```

(`sci/core.cljc:326-331`). The env atoms differ, but the persistent map's
existing Var values are the same objects. The probe returned:

```clojure
{:env-atoms-same? false
 :existing-var-same? true
 :base-sees-root :fork-root
 :fork-only-in-fork 9
 :fork-only-in-base nil}
```

Defining a new name in the fork changes only its env map. Redefining an existing
name calls `bindRoot` on the shared Var and is immediately visible in both
contexts. Any code relying on `fork` as a transactional or general isolation
boundary is therefore incorrect.

Seon forks only the namespace-unmap evaluation path
(`src/seon/sci/eval.clj:1451-1454`), captures the fork's namespace state, and
installs that state into the live context only after the terminal transaction
commits (`src/seon/sci/eval.clj:891-900`). A simple `ns-unmap` is a namespace-map
`dissoc`, so that structural removal stays isolated until install. However:

- any root mutation performed while evaluating the unmap form or its arguments
  leaks immediately through a preexisting shared Var and cannot be rolled back
  by discarding the fork; and
- a previously analyzed closure retains its old Var object after the name is
  unmapped and re-interned. Installing the fork's namespace state changes
  future name resolution, not pointers embedded in old bodies.

Whether Seon's declaration dependency validation permits that second dangling
reference to become durable is **unverified** here. The fork's root-sharing
property and the first leak are directly verified; they are sufficient to rule
out treating `fork` as full separation.

**Confidence: high (0.99)** for fork sharing and structural behavior; the
application-level reachability of a durable dangling reference is unverified.

## Question 3 — `acquire!` installs all host interns, including private Vars

**Verdict:** the compiled first-party install path has no private filter. The
smallest API-hygiene correction is to replace `ns-interns` with `ns-publics` at
`src/seon/sci/eval.clj:932`. That preserves actual host Var identity and hot
reload while making Clojure's own public/private boundary the computed rule.

This is not a complete fix for the broader custody/isolation issue. In
particular, the task and filed issue describe
`seon.cluster.store/release-store!` as private, but it is public at
`src/seon/cluster/store.clj:340-357`; `ns-publics` will continue to install it.
The public-only change removes private implementation details from the
interpreted API. A separate design decision is required if public system-side
custody functions must also become unreachable without contradicting ruling
#20.

**Confidence: high** for the install-path result and public-only change; medium
for fleet-wide migration safety because dormant sovereign cluster branches were
not opened or inspected.

### Exact selection and installation path

Cluster boot calls `cluster-ctx` once at `src/seon/cluster.clj:1365-1370`.
`cluster-ctx` builds the base context, calls `acquire!`, then installs the
session image (`src/seon/sci/eval.clj:1211-1234`). `acquire!`:

1. queries every namespace assertion (`src/seon/sci/eval.clj:993-998`);
2. resolves each asserting transaction's admission source
   (`src/seon/sci/eval.clj:903-906`);
3. keeps core-provenanced namespace names
   (`src/seon/sci/eval.clj:921-927`);
4. intersects those names with the JVM's loaded namespaces
   (`src/seon/sci/eval.clj:928-931`); and
5. installs `(ns-interns host-namespace)` through `sci/add-namespace!`
   (`src/seon/sci/eval.clj:932`).

`sci/add-namespace!` merely merges the supplied map
(`reference-code/sci/src/sci/core.cljc:659-664`). There is no check of host
`:private` metadata or database `:seon.fn/private?` anywhere in this path.
Indexing records `:seon.fn/private?` (`src/seon/fn.clj:243-250`), but only the
program-documentation query filters it during acquisition
(`src/seon/sci/eval.clj:934-947`).

Vendored Clojure revision
`b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d` establishes the exact replacement:
`ns-publics` requires `.isPublic`, while `ns-interns` accepts every Var owned by
the namespace (`reference-code/clojure/src/clj/clojure/core.clj:4212-4221,
4230-4238`).

### Counted live inventory

A read-only JVM inspection of the live `default` cluster, PID 4717, counted
actual `clojure.lang.Var` values in the SCI env whose metadata carries
`:private`. Every counted value was identical to its host namespace's intern.
The result was **708 private host Vars across 42 namespaces**: 620 function
roots and 88 non-function roots.

| Namespace | Private Vars | Namespace | Private Vars |
|---|---:|---|---:|
| `my.message` | 1 | `seon.ai` | 17 |
| `seon.blob` | 2 | `seon.cluster` | 51 |
| `seon.cluster.agent` | 1 | `seon.cluster.loop` | 20 |
| `seon.cluster.message` | 7 | `seon.cluster.prompt` | 3 |
| `seon.cluster.registry` | 9 | `seon.cluster.reply` | 16 |
| `seon.cluster.run` | 19 | `seon.cluster.source` | 7 |
| `seon.cluster.store` | 14 | `seon.cluster.wake` | 1 |
| `seon.cluster.work` | 17 | `seon.config` | 10 |
| `seon.context` | 1 | `seon.db` | 10 |
| `seon.error` | 17 | `seon.flow` | 27 |
| `seon.fn` | 30 | `seon.fn.analyzer` | 27 |
| `seon.instrument` | 7 | `seon.print` | 26 |
| `seon.problems` | 8 | `seon.program` | 18 |
| `seon.reconcile` | 21 | `seon.render` | 9 |
| `seon.render.block` | 2 | `seon.render.hiccup` | 11 |
| `seon.render.transcript` | 37 | `seon.render.value` | 14 |
| `seon.render.walk` | 17 | `seon.render.web` | 47 |
| `seon.schema` | 64 | `seon.schema.datahike` | 12 |
| `seon.schema.edn` | 13 | `seon.schema.form` | 1 |
| `seon.schema.internal` | 7 | `seon.sci.admit` | 18 |
| `seon.sci.eval` | 36 | `seon.sci.reader` | 33 |

The named private examples `held-flocks`, `panic-on-core-error?`,
`jdk-integers->long`, `seon.cluster.run/refuse!`, `running-instances`, and
`root-store-holder` were present as actual host Vars. Again,
`release-store!` was also present but is public and therefore excluded from the
708.

The live JVM predates the current churn. A separate load-only probe at current
HEAD found 747 private host-Var candidates across 45 loaded `seon.*`/`my.*`
namespaces. That is deliberately not labeled an installed count because it
lacks `acquire!`'s database-provenance intersection. Likewise, 1,126 current
database function rows marked private are not an installed count: unloaded
test namespaces do not enter the live context.

### Consumer and breakage audit

No real dependency on interpreted resolution of a private first-party Var was
found in the inspected surfaces:

- The shipped bootstrap names only public `help`, `dir`, `doc`, `seon.db/q`,
  and an agent-local `largest` (`resources/seon/bootstrap.edn:1-70`). `dir`
  explicitly uses Clojure's public namespace semantics
  (`src/seon/bootstrap.clj:48-61`).
- Agent-facing discovery already filters private rows: program documentation
  at `src/seon/sci/eval.clj:934-947`, toolkit discovery at
  `src/seon/cluster/instruction.cljc:36-61`, and bootstrap grading at
  `src/seon/bootstrap_drive.clj:190-197`.
- A read-only live database scan compared all 708 qualified private names with
  agent-authored function and session-image source. The default branch had no
  such stored sources and therefore no matches. Dormant branches were not
  inspected.
- A static search of evaluated test source found qualified calls to public
  first-party Vars, including `seon.schema/register!`,
  `seon.cluster.store/transact!`, `seon.db/q`, and `seon.render/walk`; no
  evaluated test source called a private first-party Var. Tests that use host
  `#'private` or `ns-resolve` to exercise internals do so outside SCI and are
  unaffected.
- Render execution does not need private SCI entries: a public compiled render
  function calls its compiled private helpers directly. Namespace rendering
  does expose private function source at distance one
  (`src/seon/render/ns.clj:286-298`), so agents may have learned those names
  even though no checked-in execution dependency exists.

The load-only public-only probe installed `(ns-publics 'my.message)`. It
preserved identity for public `my.message/send`, made private
`my.message/send-value` unresolved, and `(my.message/send "a" "b")` still
returned the expected message map. This proves the important composition rule:
compiled public functions continue calling compiled private helpers without
those helpers being separately resolvable in SCI.

### Recommendation and migration risk

Make the one-line `ns-publics` substitution at the existing compiled namespace
install seam and add a recurring acquisition assertion that private host Vars
are absent while public host Var identity is retained. Do not add a name list
or second install path.

Record the ruling-#20 interpretation explicitly when implementing it: private
rows remain in the database program graph for source and call analysis, and
compiled functions may use private helpers internally, but `^:private` names
are not an externally resolvable agent API. If the owner instead means ruling
#20 to include qualified private names literally, that is a policy ruling to
make before implementation, not a technical dependency discovered by this
audit.

Migration risk is limited but real: an uninspected dormant cluster may contain
scratch or durable agent source that explicitly names a private first-party
Var. Such code will fail loudly after cold acquisition under the public-only
surface. The current default, checked-in bootstrap, tests, and render execution
show no such dependency. Private source remains discoverable in namespace
rendering, and public custody functions such as `release-store!` remain
callable; neither concern is solved by the private filter.
