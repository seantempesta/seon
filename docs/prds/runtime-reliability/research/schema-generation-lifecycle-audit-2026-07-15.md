---
type: research
status: complete
tags: [research, schema, cljs, flow]
---

# Schema generation lifecycle audit

## Question

The first contained restart exposed `:malli.core/invalid-schema` after the pod
detached its schema projection and then validated its final database coordinate.
This audit asks whether validating the coordinate before detach closes the
lifecycle defect or merely hides a broader race with already-executing Malli
wrappers. It also relates that failure to the open atomic hot-reload issue.

## Dependency ledger

- Malli `0.20.0`, tag commit
  `4c054bd7d042e70d60b83b9f07fb765bc103037f`:
  `reference-code/malli/src/malli/core.cljc` and
  `reference-code/malli/src/malli/instrument.cljs`.
- ClojureScript `1.12.145`, tag `r1.12.145`, commit
  `bd23d9a2475d822ea8dfd65deaa6732428b9ed25`:
  `reference-code/clojurescript/src/main/clojure/cljs/compiler.cljc`.
- Maintained Shadow CLJS commit
  `4e72595f57618f5c43388ad13d5136cd3bede566`:
  `reference-code/shadow-cljs/src/main/shadow/cljs/devtools/client/node.cljs`
  and `client/env.cljs`.
- First-party owners: `seon.schema` owns the immutable active projection and
  stable registry facade; `seon.instrument/reconcile-projection!` owns exact
  wrapper replacement; `seon.runtime.admission` owns publication and detach;
  `seon.client/drain-runtime-owners!` owns shutdown inverse order; and
  `seon.client/shadow-build-notify!` owns watched-build admission.
- Existing proof: the planned-quiesce client runtime test asserts
  coordinate-before-projection order; the admission detach test proves
  old-to-empty reconciliation; and the Shadow publication test covers an
  explicit Shadow `:build-failure`, but not an import failure inside a nominal
  `:build-complete`.

## Finding 1: the shutdown reorder fixes the observed defect

Malli does not resolve a symbolic schema through the global registry on every
instrumented invocation. `m/-instrument` first compiles the function schema.
The `:=>` implementation then derives `validate-input`, `validate-output`, and
`validate-guard` once and closes over those validator functions in the wrapper.
The ClojureScript instrumentation layer installs that wrapper as the live var,
or installs compiled per-arity wrappers on a multi-arity function.

Consequently, an invocation that already entered a wrapper retains the old
compiled validators even if `admission/detach!` later unstruments the live var
and activates an empty projection. Multi-arity unstrumentation changes the
function object's accessor for future dispatch; it does not replace the local
function already executing on the JavaScript stack. There is no dynamic
registry lookup at output validation that can produce the observed
`:malli.core/invalid-schema`.

The failing lookup was instead a new instrumented call to
`db/head-coordinate` after detach. Its symbolic coordinate schema no longer
existed in the active registry facade. Moving that call before
`admission/detach!` restores the inverse dependency order:

1. close admission and drain owned work;
2. validate and retain the final immutable coordinate while the committed
   projection is active;
3. reconcile wrappers to the empty projection;
4. release the database attachment.

The source-level falsifier is the existing ordered lifecycle test. The live
falsifier remains two consecutive fully contained clean restarts because the
operator must also prove generation-bound quiesce evidence and attachment
reuse. No second registry, wrapper exception, or delayed-detach path is needed.

## Finding 2: hot reload is a separate incomplete-generation hazard

ClojureScript emits a `def` with an initializer as a direct assignment to the
namespace property. During reload, newly imported namespace bodies therefore
replace live function vars before Seon's publication callback runs.

Shadow's Node client then exposes a crucial distinction:

- `handle-build-complete` synchronously imports every selected output file
  inside `env/do-js-reload`;
- `do-js-reload*` catches an import exception, logs `JS reload failed`, and
  stops the remaining reload tasks; but
- the outer `:cljs-build-complete` handler still invokes the custom
  `:build-complete` notification after `handle-build-complete` returns.

Seon therefore cannot treat the callback's `:type :build-complete` as proof
that all selected JavaScript namespaces loaded. On that callback,
`admission/publish-committed!` rebuilds schemas and function contracts from the
unchanged committed database and reconciles those contracts against whatever
live vars survived the partial import. Complete wrapper coverage can be green
while the JavaScript implementation population is a mixture of the previous
and attempted generations. Reopening admission in that state violates the one
accepted program-generation contract.

This is not caused by projection detach, and retaining the old schema
projection alone cannot repair it. The old projection can validate old
contracts but cannot roll back direct JavaScript var assignments or namespace
load side effects.

## Smallest hot-reload falsifier

Add one focused Node reload proof at the Shadow/Seon boundary:

1. start from an available generation with two database-indexed functions in
   two ordered reload namespaces;
2. make the first namespace replace its live function and make the second
   throw during import;
3. deliver the same nominal `:build-complete` notification Shadow delivers
   after the caught import error;
4. assert that Seon does not reopen admission, does not rehost agents or the
   ticker, and reports the rejected attempted generation plus the first failed
   resource; and
5. repair the import and prove the next watched build publishes one complete
   generation without restarting the process.

A unit seam that only stubs `admission/publish-committed!` is insufficient: it
cannot distinguish an actual completed import population from Shadow's nominal
build event. The implementation owner is the one reload/publication transition.
It must obtain explicit reload success/failure evidence from the Shadow loading
boundary, or make the attempted live program population disposable and
rollback-capable. It must not add an exclusion list, a second function roster,
or reinterpret wrapper coverage as implementation-generation proof.

## Scheduling consequence

The shutdown issue remains on the ordered restart spine only until two clean
restart proofs graduate it. The hot-reload issue is an independent successor
chunk with its own dependency-grounded implementation and live failed-import /
recovery proof. A hot-reload redesign must not delay the coordinate-order
checkpoint, and a green restart must not close the hot-reload issue.
