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

## Question 2 — pending integrated findings

The source and cross-context/fork probes are complete; synthesis is pending in
the next incremental commit.

## Question 3 — pending integrated findings

The install-path and private-Var inventory audit is in progress.
