---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, architecture, toolkit]
---

# Confirm the background `:io` connection fix with a real `my.shell` drive

## Problem

`my.background/background` submissions reach their handler with
`seon.effect/*request-context*` bound to `nil`, so any handler that reads
the cluster connection from it gets `nil` and fails. The foreground path
works only because it conveys the binding by hand.

The asymmetry is one function apart in `src/seon/effect.clj`:

- **foreground** — `dispatch` wraps the handler in `(bound-fn [] (handler …))`
  before handing it to the `:io` executor, so the dynamic binding rides
  along.
- **background** — `flow/submit!` is given a plain `(fn [_] (handler …))`.
  Flow conveys NO bindings anywhere, by design (the environment research
  confirms zero `bound-fn` under `flow/`), so the worker thread sees the
  root value `nil`.

`seon.shell.jvm` reads exactly that dynamic var:

```clojure
;; src/seon/shell/jvm.clj:290
(let [connection (:seon.db/connection effect/*request-context*)
```

so every background `my.shell/run` fails, whatever it runs.

This is [Defect I of the parallel isolation audit](../../prds/sci-execution-runtime/research/parallel-isolation-audit-2026-08-07.md)
— "Every capability request crossing the bounded evaluator on `:io` runs with no
cluster identity today; it survives only because with one cluster the
fallback happens to be right" — except here there is no fallback that
happens to be right, so it does not survive at all. The audit predicted the
class; this is the first observation of it failing outright, on the one path
nobody had driven.

## Evidence

Tool-exercise lane, 2026-08-07, cluster `tools` in an isolated operator
root, driven through a real run. Complete result:
`docs/prds/sci-execution-runtime/research/probes/tool-exercise/background-ordinals.edn`.

Eight concurrent background submissions from one form:

```clojure
(def refs
  (mapv (fn [i]
          (my.background/background
           (my.shell/run {:my.shell/argv ["sh" "-c" (str "sleep 1; echo done-" i)]
                          :my.shell/cwd  "…/tool-exercise-scratch"})))
        (range 8)))
```

All eight settled, and all eight settled with the SAME failure:

```text
#:seon.error{:kind :seon.instrument/contract-violated,
  :message "seon.blob/stage-binary! violated its contract (invalid-input):
            [[{:value nil, :message \"must be a live unreleased Datahike
            connection from the calling cluster\"}]]", …}
```

The control is decisive: the IDENTICAL command run in the FOREGROUND
succeeds, including a 40,000,000-byte stdout correctly staged to the blob
tier with `:preview-complete? false` and a digest
(`…/probes/tool-exercise/shell-basics.edn`). Only the background path
fails, and it fails on a 7-byte stdout.

## What is NOT broken (worth recording)

The same exercise proves the identity half is sound: the eight concurrent
submissions produced eight DISTINCT ordered identities —
`["exercise:c6139a2e-…" 0 0]` through `["…" 0 7]` — all terminal, each
carrying its run reference. The effect-ordinal counter is correct under
concurrency; it is only the environment that does not travel.

## Expected

The submission carries the environment, and the handler receives it as an
argument rather than reading a dynamic var — the seon.env Phase 3 direction
exactly. `seon.effect/request!` already puts `:seon.env/environment` on the
background submission map; the handler side has not been converted, and
`src/seon/shell/jvm.clj:290` is one of the named readers on the PRD's
deletion list. The foreground `bound-fn*` in `dispatch` is deleted in the
same change, per the flow-carriage sequencing constraint (while conveyance
remains, a forgotten environment is invisible on `:compute` and fatal on
`:io` — which is precisely what happened here).

## Acceptance

A background `my.shell/run` with output large enough to require blob
staging settles with a real result, proven by a regression that drives the
background path through a real run — not by a direct handler call, which
cannot see this defect at all.

## Fixed at cause — 2026-08-08 (`f3b8eabda`); one confirmation outstanding

The background work-fn now rebuilds its request context from the value its
submission carried, instead of hoping to inherit a binding frame flow does
not convey (`with-request-context` in `src/seon/effect.clj`). The foreground
arm is unchanged, so the two arms are symmetric for the first time.

Regression: `seon.effect-test/background-work-outlives-the-deadline-of-the-turn-that-started-it`
drives a REAL background submission through `effect/request!` and the work
launcher, and asserts the settled receipt reports a live connection. That is
the class this note names, and it is asserted at the effect execution boundary where the class
lives rather than in one capability.

Still open because the acceptance criterion asks for more than the class
regression, and it has not been run: a background `my.shell/run` whose output
is large enough to require blob staging, driven through a real run on a live
cluster, settling with a real result. The tool-exercise lane's harness is
committed at
`docs/prds/sci-execution-runtime/research/probes/tool-exercise/` and is the
right instrument — re-running it against `f3b8eabda` closes this note.

Named remainder, and the reason the fix is a rebuild rather than an argument:
`src/seon/shell/jvm.clj:290` still READS the dynamic var. Converting it (and
its peers) to take the environment as an argument is the seon.env Phase 3
reader conversion, at which point `with-request-context` is deleted with
them. The rebuild is honest about being transitional and its docstring says
so.

## Settlement fixed at cause — 2026-08-12; focused gate blocked by foreign source

Background settlement no longer reads its connection or admission dials from
the settling thread. `request*` now constructs one immutable settlement
request on the requesting thread containing `:seon.db/connection`,
`:seon.sci.admit/caps`, `:seon.config/on-core-error`, the effect identity,
owner, opening instant, and blob threshold. The terminal callback consumes
only that value and its terminal argument. A thread hop therefore has no
binding frame from which it could drop settlement custody.

The one class regression replaces the earlier indirect connection assertion:
it runs the background work and terminal callback on a fresh raw `Thread`,
asserts both `seon.effect/*request-context*` and `seon.db/*conn*` are `nil`
before work and before settlement, then reads the committed result and
notification facts through the original connection.

Evidence obtained before the gate blocker:

- a real `seon.flow` IO-launcher probe observed a virtual settling thread with
  both dynamic roots `nil`, while `env/of` recovered the submission's carried
  environment and the work value arrived;
- `(require 'seon.effect :reload)` succeeded after the source change; and
- `git diff --check` passed for the source and regression.

The required focused `bin/test seon.effect-test` did not reach the namespace.
Its shared published-base preparation refused the protected foreign file
`test/seon/render/web_test.clj:505`: unresolved symbol `debug-feed-path`
(`tmp/test-runs/run.JtBA4Q/test-run.txt`, `runner-exit=1`). Per lane ownership,
this issue remains open and no foreign file was edited or rerun around.

## Verification — 2026-08-13

The deferred focused gate ran green after the foreign tree churn settled: `bin/test seon.effect-test` passed 10 tests / 125 assertions / 0 failures / 0 errors, including the fresh-thread no-bindings regression landed in `e4eaacdfe`. Settlement inputs travel as request data; terminal settlement reads no dynamic binding.
