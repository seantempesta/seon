---
type: issue
status: open
severity: friction
tags: [issue, sci, wave/sci-eval-context-owner]
---

# Bind first-party namespaces so value-position reads deref

## Problem

`install-loaded-first-party-namespaces!` binds each loaded first-party
namespace as `(ns-interns host-namespace)` — raw `clojure.lang.Var` objects.
SCI's analyzer derefs a symbol in value position only when the resolved thing
is a SCI Var; a raw JVM Var fails that test and falls through to `:else v`, so
interpreted code that reads a first-party CONSTANT receives the Var object
instead of its value. Call position is unaffected (a Var is `IFn`), which is
why the live path has not tripped it yet — but any agent (or any interpreted
first-party function, which ruling #21 now makes the norm) that reads
`some.ns/some-constant` gets a `clojure.lang.Var` where a value is expected.

The obvious fix, `sci/copy-var*`, derefs at copy time and therefore SNAPSHOTS
the value, which contradicts the function's own docstring claim that
"a re-evaluated `defn` changes the next host call without reacquisition". Both
behaviours cannot come from one naive binding; the owner must choose and say
which, in the docstring.

## Evidence

- `src/seon/sci/eval.clj:636-660` — the raw `ns-interns` binding and the
  hot-reload liveness claim in its docstring.
- `reference-code/sci/src/sci/impl/analyzer.cljc:2276-2298` — value-position
  symbols deref only under `(utils/var? v)`; everything else returns `v`.
- `reference-code/sci/src/sci/core.cljc:111-136` — `copy-var*` calls
  `@clojure-var` once, at copy time.
- Reproduced 2026-07-31 in `tmp/sci-precompute/p2_interpreted.clj`: with raw
  `ns-interns` binding, calling an interpreted `seon.render.walk/neighborhood`
  fails with `class clojure.lang.Var cannot be cast to class java.lang.Number`
  at `seon/render/walk.clj:592`, which reads `tokens/chars-per-token`. Switching
  the same probe to `sci/copy-var*` makes the interpreted result `=` to the
  compiled one.
- `docs/prds/sci-execution-runtime/research/sci-precomputed-analysis-2026-07-31.md`
  §5.3 records the measurement context.

## Owner

`seon.sci.eval` (`src/seon/sci/eval.clj`).

## Acceptance

- Interpreted code reading a first-party constant through the host binding
  receives the VALUE, proven by a recurring test (not a lane probe).
- The chosen liveness semantics is stated truthfully in the docstring: either
  re-evaluated Vars are observed without reacquisition, or acquisition is a
  snapshot at a basis and reacquisition is the update mechanism.
- If the interpreted-corpus direction (ruling #21) lands first, this note is
  resolved by first-party namespaces ceasing to be host-bound at all; the
  remaining third-party binding must still satisfy the first bullet.
