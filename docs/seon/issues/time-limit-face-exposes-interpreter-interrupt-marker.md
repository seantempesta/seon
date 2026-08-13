---
type: issue
status: open
severity: friction
tags: [issue, sci, class/n1, wave/sci-failure-face]
---

# Keep interpreter-private markers out of the time-limit face

## Problem

The time-limit value correctly exposes the diagnostic evaluation record, but
also exposes SCI's private interrupt marker as an opaque host object. The
marker is neither actionable nor stable agent-facing data.

## Evidence

On 2026-08-04, isolated cluster `edgefaces0804` evaluated this through the
real SCI door and reached the configured 30-second limit:

```clojure
(loop [n 0] (recur (inc n)))
```

The useful part of the face was present:

```clojure
{:seon.error/kind :seon.sci.eval/time-limit
 :seon.error/message "Ran out of time after 30004ms."
 :seon.sci.admit/record
 {:seon.eval/fn-entries 613144508
  :seon.eval/host-interop-count 0
  :seon.eval/duration-ms 30004
  :seon.eval/allocated-bytes 14760632016
  :seon.eval/outcome :time}}
```

The same `:seon.error/data` also showed:

```clojure
{:seon.sci.eval/throwable "clojure.lang.ExceptionInfo"
 :seon.sci.eval/data
 #:sci.impl{:interrupt #object[java.lang.Object ...]}}
```

`src/seon/sci/kernel.clj:277-289` merges the deepest refusal into the flat
failure value, preserving the interpreter marker alongside the useful
diagnostic record.

## Owner

`seon.sci.kernel/failure-value` owns the agent-facing failure value at both
guarded entrances.

## Acceptance

A timed-out eval returns kind, message, and the complete
`:seon.sci.admit/record`, including `:seon.eval/fn-entries`, without exposing
`:sci.impl/interrupt` or an opaque host object. Internal triage may retain the
original throwable independently of the agent face.

## N1 disposition — 2026-08-12

Still open outside this lane. `seon.sci.kernel/failure-value` must recognize
the time-limit result structurally, construct the declared flat time-limit
value with the complete admission record, and retain the internal throwable
only in fault evidence; `:sci.impl/interrupt` must never enter the outward
projection.

## Post-refusal-render verification — 2026-08-13

**STILL OPEN after `c5f9e7701`.** The commit removed nested wrapping when a
throwable already carries a flat refusal, but the ordinary time-limit branch
still constructs a new diagnostic and, at `src/seon/sci/kernel.clj:523-525`,
copies the throwable's complete `ex-data` into
`:seon.sci.eval/data`. The only selective extraction is the separate
`:sci.impl/symbol` key at lines 527-528. There is still no structural removal
of `:sci.impl/interrupt`, so a SCI time-limit throwable continues to expose
the private marker through the outward value.
