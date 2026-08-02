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

## Question 3 — pending integrated findings

The install-path and private-Var inventory audit is in progress.
