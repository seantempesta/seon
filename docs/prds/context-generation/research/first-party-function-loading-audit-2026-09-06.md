---
type: research
status: complete
tags: [research, context, sci, runtime]
---

# First-party function loading audit — 2026-09-06

Status: proposal, not a ruling. Transparent on-demand loading remains open.

## Observed failure

A newly published cluster program contained
`seon.cluster.agent/render-identity-ai`, while the long-lived JVM had already
loaded `seon.cluster.agent` before that Var existed. The namespace was present,
the program function row was present, and SCI could not resolve the function.
Calling `require` could not change this state because Clojure does not reload an
already-loaded namespace. Selectively evaluating the five new host definitions
and then creating a fresh cluster fork made SCI resolution succeed. This
falsifies the premise that an indexed first-party row plus an already-loaded
host namespace is sufficient for acquisition.

The live database also exposed the incomplete source closure. It held exact
`:seon.fn/source` rows for public `render-identity-ai` and private
`identity-data`, but no function row for the ordinary private
`identity-selector` definition on which the helper depends. After the manual
host evaluation, a read-only probe reported the namespace and all three host
Vars present, plus indexed rows for the two functions and no function row for
the selector. That later state confirms the fact shape; it does not reproduce
the original absence because the JVM had already been repaired manually.

The separate observation that a root candidate had no entry source persisted
after SCI could resolve the identity renderer. This audit therefore does not
attribute that symptom to the missing host Var.

## Existing owners and the gap

`host-namespace!` returns `find-ns` first and calls plain `require` only when
the namespace is absent. It can load a namespace that the process has never
seen, but it cannot add a new Var to one already loaded
([`src/seon/sci/eval.clj:1030-1091`](../../../../src/seon/sci/eval.clj)).

`install-first-party-namespaces!` deliberately binds indexed first-party
functions as forwarding SCI Vars over compiled JVM Vars. It reads
`ns-interns`, selects public and indexed function names, and marks only host
bindings it actually found as installed. This preserves compiled host
semantics and observes later changes to an existing host Var
([`src/seon/sci/eval.clj:1093-1160`](../../../../src/seon/sci/eval.clj)).

Acquisition caches every program function's source, source transaction,
namespace, privacy, and core-versus-agent provenance
([`src/seon/sci/eval.clj:1452-1569`](../../../../src/seon/sci/eval.clj)). It
then passes core rows through `install-row!` with contract installation skipped
([`src/seon/sci/eval.clj:1638-1670`](../../../../src/seon/sci/eval.clj)). The
function branch of `install-row!` nevertheless marks an evaluated/skipped row
installed without proving that an SCI Var exists
([`src/seon/sci/eval.clj:850-875`](../../../../src/seon/sci/eval.clj)). Since
`kernel/ensure-function!` trusts that set, later invocation does not attempt an
install and fails at resolution
([`src/seon/sci/kernel.clj:162-178`](../../../../src/seon/sci/kernel.clj)).

The existing database installer is not a general first-party source loader. It
requires a fact-backed `seon.def` root and installs agent-authored functions
from that descriptor
([`src/seon/sci/eval.clj:738-768`](../../../../src/seon/sci/eval.clj)).
Indexed function facts include exact function source, calls/AST, macro and
privacy facts, and namespace requires/imports/refers. They do not represent
every ordinary top-level `def`, `defmacro`, `deftype`, or their complete
evaluation order, and there is no declared fact separating functions safe to
interpret in SCI from definitions requiring compiled host semantics. Evaluating
one indexed function source therefore cannot promise its dependency closure or
the semantics of arbitrary first-party Clojure.

## Options

These are proposals for an owner decision.

1. **Bounded correctness first (recommended with current facts).** Refuse
   acquisition loudly when a core program function has no corresponding host
   Var, and require the operator to establish a matching compiled process
   generation before it permits a cluster to fork that program commit. This
   guarantees that the callable surface never advertises an unavailable
   function and that program facts agree with compiled behavior. It costs an
   operator-managed process generation and cluster refork for structural
   first-party additions. It does not provide transparent on-demand loading or
   mixed old/new compiled program generations in one JVM.

2. **Declare an SCI-installable source closure.** Extend indexing to record all
   required top-level definitions, dependency order, and an explicit
   SCI-interpretable classification. The existing SCI installer could then
   evaluate the exact program-version closure lazily inside the cluster ctx.
   This would give version-sovereign loading for definitions admitted to that
   class. It requires coordinated schema, indexer, and evaluator work; compiled
   host-only functions would still need option 1 or 3.

3. **Build versioned compiled first-party artifacts.** Publish an exact
   compiled artifact per program commit and resolve each cluster's forwarding
   Vars from its isolated version. This could preserve Java interop, macros,
   types, and simultaneous cluster program versions. It is the largest change:
   artifact construction, namespace/classloader isolation, lifecycle, and GC
   all need an owner design. It replaces the current simple process-global Var
   forwarding model.

The first option closes the silent correctness hole without pretending the
on-demand goal is solved. Choosing between transparent SCI loading and
versioned compiled loading is an architecture decision because the present
program facts cannot state either guarantee.
