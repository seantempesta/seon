---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, eval, agent]
---

# Keep the interpreted program graph per cluster

## Problem

Before `ac9de46b9`, `seon.sci.eval/base-ctx` was a process-wide `defonce`.
Every cluster in one JVM therefore reached the same SCI context and the same
writable `sci.lang.Var` objects. A root rebind through one cluster could
change the program observed by another sovereign cluster.

The per-cluster context repair closed the main boundary, but an independent
fresh-JVM audit found 17 SCI stock Vars that remained identical across two
independent `sci/init` contexts and lacked `:sci/built-in`. Rebinding
`clojure.walk/macroexpand-all` through context A changed the value observed
through context B.

## Evidence

`plan/per-cluster-base-context-2026-08-01.md` §3.4 derives the exact residue:

- 11 `clojure.core` dynamic Vars;
- `clojure.core/unquote`;
- `clojure.walk/macroexpand-all`; and
- `clojure.lang/IFn`, `IDeref`, `IAtom`, and `IAtom2`.

An independent re-derivation against SCI `a27e2c0` selected Vars that were
identical across two `sci/init` contexts and lacked `:sci/built-in`. It found
exactly those 17 and no others. A fixed-string and mutation-shape search over
first-party `src/`, `test/`, `resources/`, `script/`, and `config/` found no
legitimate SCI root rebind of any member. Host Clojure bindings such as
`*read-eval*` and compiler directives such as
`(set! *warn-on-reflection* true)` do not mutate these SCI roots.

## Owner

`seon.sci.eval/build-base-ctx` constructs the live context that
`seon.cluster` holds once per cluster. SCI's maintained stock namespace maps
own whether their shared Vars are writable. Agent-authored Vars remain
writable; only the process-shared SCI stock Vars are built-ins.

## Acceptance

Two clusters in one JVM have distinct live SCI contexts. A definition made in
cluster A is immediately available within A and does not reach cluster B.
Across two freshly built cluster contexts, no identical SCI Var is writable.
Attempting to root-rebind a former residue Var either refuses or cannot change
the sibling context. A recurring regression covers both the structural class
and the behavior.

## Resolution

- `ac9de46b9` replaced the process `defonce` with one context built and held
  per cluster. `test/seon/cluster/armed_test.clj` exercises two clusters in one
  JVM, same-cluster visibility, and cross-cluster isolation.
- SCI fork commit `6de1568` marks the complete 17-Var closure as built-in at
  its stock construction/assembly seams. Its regression derives zero shared
  writable stock Vars across two independent `sci/init` contexts and proves
  `macroexpand-all` root rebinding is refused.
- Root commit `8d32828c9` pins SCI `6de1568` and adds the same structural and
  behavioral regression through two `seon.sci.eval/build-base-ctx` calls.

The SCI JVM suite passed on both supported matrices: Clojure 1.10.3 and
1.11.1 each ran 383 tests and 1,423 assertions with zero failures or errors.
Seon's focused SCI boundary ran 133 tests and 452 assertions with zero
failures or errors.

The patched Node suite ran 409 tests and 5,706 assertions with 2 failures and
1 error. An untouched checkout of parent `a27e2c0` produced the exact same
tally and the same three failures (`built-in-call-observer-test` and two
namespace-binding tests), proving this metadata repair added no Node failure.
Seon is CLJ-only; the two JVM matrices are the fork gate for this change.
