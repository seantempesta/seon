---
type: issue
status: superseded
severity: friction
tags: [issue, runtime, schema, wave/instrumentation-error-data]
---

# Let message custody accept its declared absent run

## Problem

`seon.context/message-custody` declares an optional positional run ID and its
body explicitly classifies an absent run as history. In an instrumented fresh
cluster, the wrapper instead rejects `nil` as “should be a string.” Any history
walk containing a message therefore fails when the agent has no current run.

This currently keeps `GET /agent/root/debug` from rendering a prospective
prompt after the generated opening run has settled. The debug page now exposes
the failure as diagnostic data, but the context remains unavailable.

## Evidence

- `src/seon/context.clj:59-75` declares
  `[:maybe :seon.cluster.run/id]` and immediately handles an absent value.
- `src/seon/render/walk.clj:763-776` calls that function for every message in
  the history walk, handing the request's run ID, which is legitimately absent
  between runs.
- On 2026-09-03, an isolated cluster freshly forked from current source at
  `/Users/sean/src/seon/tmp/lane-debug-page-3-root` returned the diagnostic
  `seon.context/message-custody violated its contract (invalid-input): should
  be a string`; its expected schema still printed
  `[:cat :seon.db/database-value [:maybe :seon.cluster.run/id]
  :seon.cluster.agent/id :int]`, and its offending argument was `[nil]`.
- The same real walk with a complete effective-config row succeeds in an
  uninstrumented test JVM and produces 15 entries / 31,160 bytes. Supplying an
  actual current run ID also succeeds under the web regression, isolating the
  failure to the declared absent-run arm rather than acquisition or rendering.

## Owner

The instrumentation/schema boundary owns making the compiled input validator
agree with the declared contract. `seon.render.web` must not invent a run ID or
silently omit message history to evade it.

## Acceptance

- Under boot instrumentation, `(seon.context/message-custody db nil agent-id
  message-eid)` returns `:seon.context/history`.
- A focused regression exercises that exact absent positional input through
  the installed wrapper.
- A fresh cluster with no active run serves a non-empty prospective prompt at
  `GET /agent/root/debug` without writing render-cost facts.

## Resolution

Superseded 2026-09-03 after reproducing on a freshly initialized and reforked
`instrument-absent` cluster. Malli validated `[:cat [:maybe :string]]` against
`[nil]`, a generic instrumented `[:maybe :string]` positional returned `nil`,
and the installed `seon.context/message-custody` wrapper returned
`:seon.context/history` for `(db nil "root" message-eid)`. Instrumentation does
not drop `:maybe` or select another arity here.

Tracing the real debug request showed the actual arguments were
`{:run-id nil :agent-id nil :message-eid 31068}`. The string refusal was for
the required agent ID, not the optional run ID. The remaining debug-page
failure is tracked separately in
`docs/seon/issues/prospective-debug-walk-omits-agent-id.md`; it belongs to the
protected `seon.render.web` owner rather than this instrumentation lane.

`bin/test seon.instrument-test seon.fs-test seon.context-test` ran 26 tests
containing 178 assertions with 0 failures and 0 errors, including the generic
optional-positional regression and the installed `message-custody` wrapper.
