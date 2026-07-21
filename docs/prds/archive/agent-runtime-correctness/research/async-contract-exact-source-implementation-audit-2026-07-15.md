---
type: research
status: complete
tags: [research, agent, cljs, schema]
---

# Async contract exact-source implementation audit

## Decision

The selected compiler boundary is now source-grounded. Fetch the official
ClojureScript `r1.12.145` tag into `reference-code/clojurescript` and read it
by revision; do not move that checkout. Its commit is
`bd23d9a2475d822ea8dfd65deaa6732428b9ed25`. The tag name is authoritative for
the Maven `1.12.145` release even though the tagged commit message and top
changelog entry say `1.12.144`. Shadow `3.4.10` is commit
`d3c04691952aa9ea33f7287ffe9a2b3109c1e510`. This audit was made against Seon
HEAD `ffdad065fc3c199624c0c99391f90049f652a362`.

Do not add an eval-level Promise validator. Extend the one existing
`seon.instrument/injecting-fschema` construction so every supported async
function arity validates its resolved value through the same function contract,
then let Malli retain ownership of live-var replacement and restoration.

## Dependency ledger

| Dependency or mechanism | Exact identity | Source read | Constraint |
|---|---|---|---|
| ClojureScript | Maven `1.12.145`; official tag `r1.12.145`; commit `bd23d9a2475d822ea8dfd65deaa6732428b9ed25` | `reference-code/clojurescript` at `r1.12.145`: `src/main/clojure/cljs/analyzer.cljc`, `compiler.cljc`, `core.cljc`; `src/main/cljs/cljs/js.cljs`; `src/test/cljs/cljs/async_await_test.cljs` | `^:async` becomes native JavaScript async functions for fixed, multi-arity, and variadic shapes. `await` is legal only when analyzer env carries `:async`. |
| Shadow CLJS | `3.4.10`; commit `d3c04691952aa9ea33f7287ffe9a2b3109c1e510` | `reference-code/shadow-cljs` at that commit: `project.clj`, `src/main/shadow/build/cljs_hacks.cljc`, `cljs_bridge.clj`, `targets/bootstrap.clj` | Shadow patches invoke/type and other compiler seams, but does not replace `parse 'fn*` or the async function emitters. Bootstrap output serializes the analyzer namespace map rather than inventing a second async model. |
| Malli | `0.20.0`; tag commit `4c054bd7d042e70d60b83b9f07fb765bc103037f` | `reference-code/malli` at `0.20.0`: `src/malli/core.cljc`, `src/malli/instrument.cljs` | Stock output validation sees the immediately returned Promise. CLJS var surgery distinguishes simple, multi-arity, and pure-variadic functions by the exact properties the compiler emits. |
| Seon instrumentation owner | current HEAD | `src/seon/instrument.cljc`; `test/seon/instrument_inject_test.cljs`; `test/seon/instrument_smoke_test.cljs` | `injecting-fschema` already owns injection, input validation, resolved-output validation, rejection recording, and fault attribution for a simple `:=>`. Strengthen this owner in place. |
| Seon analyzer/eval | current HEAD | `src/seon/analyzer_info.cljs`; `src/seon/eval.cljs` | Analyzer facts retain source metadata and function contracts. Eval awaits agent form results, but must not become a second public-function contract authority. |

Exact source acquisition is non-destructive:

```bash
git -C reference-code/clojurescript fetch --depth=1 origin tag r1.12.145
git -C reference-code/clojurescript rev-parse r1.12.145^{commit}

```

The command returned `bd23d9a2475d822ea8dfd65deaa6732428b9ed25` and left
the reference checkout detached at its prior source snapshot. No dependency
version, Seon checkout, or worktree changed.

## Exact compiler behavior

`cljs.analyzer/parse 'fn*` reads `:async` from the function name or `fn*`
operator metadata and associates it into the method environment. The `await`
macro asserts that this environment flag is present and emits native JavaScript
`await` syntax. `cljs.compiler` then emits:

- `async function` for a fixed method;
- an async outer dispatch plus async fixed-arity accessors for multi-arity
  functions; and
- an async outer function plus async variadic delegate for variadic functions.

Those functions retain normal ClojureScript callable-shape properties:
`cljs$lang$maxFixedArity`, `cljs$core$IFn$_invoke$arity$N`, and optionally
`cljs$core$IFn$_invoke$arity$variadic`. ClojureScript's own exact-release
tests exercise fixed, variadic, multi-arity, and multi-arity-variadic async
definitions through both direct invocation and `apply`.

The self-host `cljs.js/eval-str` path analyzes with the same analyzer, emits
with the same compiler, and then evaluates the emitted JavaScript. Seon's
runtime compile state therefore does not need a separate async-shape registry.

Shadow's `install-hacks!` replaces analyzer resolution/invoke/type seams and a
compiler namespace lookup plus selected emit multimethods. It does not replace
the async `fn*` parser, `emit-fn-method`, `emit-variadic-fn-method`, or outer
multi-arity dispatch. Its bootstrap target writes the analyzer namespace map to
the analysis cache. The selected Shadow release therefore preserves the exact
ClojureScript async shape described above.

## Executable probes

An isolated `cljs.main` probe using Maven ClojureScript `1.12.145` defined one
multi-arity and one variadic `^:async` function. Runtime inspection returned:

```clojure
{:multi-ctor "AsyncFunction"
 :multi-max 2
 :arity1-ctor "AsyncFunction"
 :arity2-ctor "AsyncFunction"
 :variadic-ctor "AsyncFunction"
 :variadic-max 1
 :variadic-accessor-ctor "AsyncFunction"}

```

A second isolated probe added Malli `0.20.0`, applied its stock `:function`
instrumentation to an async multi-arity function, and called a valid arity. The
returned Promise rejected with `{:type :malli.core/invalid-output}` because the
arity wrapper validated the Promise object against `:int` before resolution.
This directly reproduces the dependency assumption without a live Seon pod.

## Current defect and invariant

Seon's simple-arrow wrapper correctly chains returned thenables and validates
their resolved value. `async-unwrappable?` excludes every other async shape,
and `coverage-gap` deliberately removes those exclusions from its result. The
2026-07-14 census consequently reported zero gaps while 95 of 747 contracts
were structurally unvalidated.

The intended invariant is stricter:

> Every live public function contract is either wrapped by the one exact-data
> instrumentation generation or appears as a measured, source-grounded gap.
> Async wrappers validate the resolved domain value exactly once and preserve
> the function's callable arities and Promise behavior.

## Implementation plan

### 1. Make async detection stable across Malli surgery

Strengthen `async-fn?` in place. Check the original function first, then the
originals behind its fixed and variadic accessors. This is grounded in the
compiler's emitted properties and survives Malli's multi-arity `meta-fn`, whose
constructor is an ordinary `Function` even though its copied accessors still
lead to the async originals. Do not persist an async flag or infer it from a
function name.

### 2. Generalize the existing schema construction

Keep `injecting-fschema` as the only resolved-output/injection wrapper.

- For any async `:=>` contract, supply its custom function-schema object to
  Malli, including variadic arrows.
- For an async `:function`, retain the raw outer vector Malli's
  `-arity->schema` requires, but replace each child arrow with an
  `injecting-fschema` object. Malli then performs its existing fixed/variadic
  accessor replacement and invokes Seon's one wrapper per declared arity.
- Preserve input, arity, output, rejection, injection, and fault behavior. If
  a function guard exists, validate it against `[args resolved-value]` at the
  same resolved boundary or reject that target honestly; never silently omit
  the guard.
- Preserve the exact schema/live arity profile check before any live mutation.

This is one mechanism: Seon constructs the function schemas; Malli owns live
var surgery; the ClojureScript compiler owns callable shape.

### 3. Remove the false opt-out success path

Delete `async-unwrappable?` once the supported shape matrix is green. A target
that cannot be safely constructed becomes an explicit rejection and a coverage
gap, not `::skipped`. Update boot/delta/reconcile statistics so exclusions are
never counted as successful coverage.

`coverage-gaps` must include every live canonical contract with no verified
wrapper. Keep it derived from database program facts plus live vars; add no
stored wrapper state or second registry.

### 4. Prove the transition matrix

Add focused tests beside `seon.instrument` for:

| Shape | Required proof |
|---|---|
| simple fixed async `:=>` | valid resolution passes unchanged; invalid resolved output rejects once; input remains synchronous validation |
| variadic async `:=>` | direct call and `apply` preserve rest arguments and validate the resolved value |
| fixed multi-arity async `:function` | every accessor validates; unsupported arity reports the declared arity set |
| multi-arity plus variadic async `:function` | fixed and variadic dispatch preserve ClojureScript semantics and resolved validation |
| injection | declared absent keys fill before validation for every applicable arity; explicit values win |
| rejection | original rejection reason propagates; one fault datom is recorded, never duplicated by nested wrappers |
| generation change | instrument, redefine/reconcile, and unstrument restore every accessor without wrapper stacking |
| census | the prior 95 exclusions become wrapped or explicit named gaps; zero means zero |

Run focused instrumentation tests first, then the complete CLJS checkpoint.
The live acceptance proof is a default-cluster REPL census plus one deliberately
invalid resolved output through simple, variadic, and multi-arity functions,
followed by a hot-reload/reconcile repeat. Do not use the live pod for initial
implementation experiments.

## Explicit non-goals

- No change to ClojureScript, Shadow, Malli, or dependency versions.
- No eval-loop, provider, timeout, or cancellation wrapper.
- No second instrumentation registry or persisted wrapper state.
- No implementation in this audit commit. Exact source identity and the shape
  matrix are now proved; code work is the next bounded unit.
