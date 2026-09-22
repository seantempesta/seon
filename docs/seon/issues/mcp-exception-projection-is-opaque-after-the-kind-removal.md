---
type: issue
status: open
severity: blocker
tags: [issue, dev, mcp, error, wave/dev-mcp, wave/error-class-contract, class/tools]
---

# MCP exception projection is opaque after the kind removal

## Observation — 2026-09-20 ~20:20 UTC, default pid 24777 (fresh reset)

Every JVM-mode evaluation that THROWS returns
`{:seon.error/kind :seon.dev.mcp/projection-failed, :seon.error/message "The MCP value projection failed.", :seon.error/data {:seon.error/diagnostic-offending "clojure.lang.PersistentArrayMap"}}`
with `exception true` and no message, class or frame. A scalar or string result
projects fine (`(+ 1 2)` → 3). Wrapping the same form in `try`/`catch` and
returning the message as a string works, which proves the failure is in the
exception projection, not the evaluation.

`src/seon/cluster.clj` ~`:330-360`: the exception projection builds its value
with `error/diagnostic` and a `:seon.error/kind` member (`{kind true
:seon.error/kind kind …}`) and the fallback `mcp-projection-error` also
stamps `:seon.error/kind`. After the error family cut (D3/D12: kinds deleted,
an arity's contract names its declared error schemas and the wrapper refuses
anything outside that union) the
constructor call itself fails, so the fallback runs and reports the map it
could not project. The orchestrator's tooling loses every exception's cause.

## Fix

Step-1 registry lane re-observation (2026-09-20): `runtime_status` for
`default`, pid 24777, returned the same `projection-failed` map instead of
health evidence. A read-only JVM evaluation returning a string succeeded:
the carried projection had 3,228 forms and registry lookup for
`:seon.agent/id` was not a compiled Malli Schema. This establishes that
the REPL remains reachable; it does not establish runtime health or the
cause of the status projection failure. No runtime mutation was performed.

Owned by the `kind-sweep-turn-cluster` sweep (PRD
`docs/prds/steward-platform/plan/error-conversion-prd-2026-09-20.md`): convert
the MCP projection producers in `src/seon/cluster.clj` to declared error
schemas via
`seon.error.refusal`, and one regression: a thrown `ex-info` in jvm mode
projects to a value carrying message, exception class and the first
first-party frame. The kind-free fallback must still name the offending class.

## Resolved — 2026-09-21 ~00:45 UTC

Fixed by the turn/cluster sweep in `510a9236d` (declared error schemas + two armed
regressions). In-place adoption of the change into default was refused
twice (a lifecycle lock held by a lane's short "source build", then the
30 s prepl silence bound in the eventless "source build" phase — the
publication-dissolution lane's item 2 owns that bound); the namespace was
hot-reloaded in default (`(require 'seon.cluster :reload)`), and a thrown
`ex-info` now projects message, exception class, frame, layer, operation
and `at`. Follow-up for the sweep: the reported frame is the projection
site (`seon.cluster/mcp-io-prepl` :511), not the throw's first first-party
frame; the legacy `:seon.error/diagnostic-*` member names remain until the
sweep's family conversion.

## Recurrence — 2026-09-23, `default` at `03bbf7eb0` (reopened)

A jvm-mode probe whose body threw a `NullPointerException` returned the
fallback again, now with the constructor's own refusal as the cause:

```
seon.error/operation seon.cluster/mcp-projection-error
seon.dev.mcp/projection-offending-class clojure.lang.PersistentArrayMap
seon.error/diagnostic-cause "seon.error.refusal/diagnostic refused argument 0 (0-based) at [:seon.error/message]: expected a string, got a string. Fix: Supply a string at [:seon.error/message]. Called from seon.cluster (cluster.clj:356)."
```

The same envelope came back when the form returned a `pr-str` string, so
the refused value is the projection's own error map. Two defects: the
diagnostic built at `cluster.clj:356` fails its own `:seon.error/message`
contract (an explanation rendered from the wrong member, or a schema at
that key that is not `:string`); and the evaluation fault is erased, which
is the absence-as-signal class. Wrapping the probe in `try` and returning
`(str t)` was the only way to see the exception. The regression named
above (a thrown `ex-info` in jvm mode projects as the declared error value
carrying class, message and the offending value as a `result/e<id>`
reference) is still owed; it must also construct this fallback diagnostic
and assert it validates.
