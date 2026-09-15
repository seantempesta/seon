---
type: issue
status: open
severity: friction
tags: [issue, operator, render, class/n1, wave/operator-status-face]
---

# A failed `bin/seon init` dumps the entire prepl event history instead of the cause

## Problem

When init's publication fails, the terminal face prints the complete prepl
event vector — now including every progress event since `b465b4613` — plus a
stack trace, burying the one line that names the cause. Reported by the
gate-fix-operator lane (2026-08-06, standing ugly-output order) while
reproducing the `canonical-definition` publication failure.

## Expected

The failure face leads with the flat error's kind/message (the actual cause),
then at most a bounded tail of recent events; the complete event history
belongs behind a verbose flag or a retained file, not on the default face.
Owner: `script/seon/fresh_operator.clj` init failure rendering
(`prepl-eval!` fail! sites and the init command's error printer).

## Acceptance

A deliberately failing publication prints a face whose first lines name the
cause; full event history remains reachable explicitly.

## N1 disposition — 2026-08-12

Still open in the operator publication leaf. Retain the complete prepl event
history by report identity and make `bin/seon init` print a declared bounded
failure face whose first fields are phase, cause, and source path.

## Edit-hook correction — 2026-09-06

The edit hook previously clipped the start of the entire event vector, removing
the cause and retaining only progress chatter. `publication-failure-message` in
`bin/seon-hook` now extracts the terminal exception's cause and data using EDN,
and names `logs/current-source-failure.log` before that summary. The log retains
the latest failed publication only, bounded by the existing hook log cap, with
an explicit omitted-character count if needed. It does not accumulate a file
per failure.

Root replayed the actual failed publication captured in
`tmp/identity-publication.log`. The resulting short advisory names
`:malli.core/invalid-schema` and
`:seon.cluster.loop/evaluate-sources-request`; both were previously hidden by
the clipped progress events. The operator's direct terminal failure face is
still outstanding, so this issue remains open.

The hook also searches structured exception events independently in stdout and
stderr. Trailing cleanup events or stderr warnings no longer hide the cause.
`publication-diagnostics-survive-trailing-output` exercises those cases and an
output with no structured exception, which must remain unknown.

## Verified at HEAD (2026-09-16, N1 verification)

**CONFIRMED — the edit-hook half stays fixed; the operator's own face is
unchanged.**

The publication result protocol improved: the init JVM now returns a
structured result rather than raw output. `script/seon/fresh_operator.clj:2376-2382`
emits `SEON-INIT-RESULT` carrying
`{:seon.fresh-operator/message (ex-message failure) :seon.fresh-operator/data (ex-data failure)}`
— message first, no trace, no event vector.

But the failing path around it still dumps everything. When the init JVM
exits non-zero or returns no result,
`script/seon/fresh_operator.clj:2446-2452` fails with the COMPLETE captured
output as data:

```clojure
(when-not (zero? exit)
  (fail! "The initialization JVM exited unsuccessfully."
         {:seon.fresh-operator/exit exit
          :seon.fresh-operator/output output}))
(or @result
    (fail! "The initialization JVM returned no result."
           {:seon.fresh-operator/output output})))
```

and `-main`'s catch prints that data with one unbounded `prn`
(`script/seon/fresh_operator.clj:3151-3156`):

```clojure
(println (str "✗ " (ex-message error)))
(when-let [data (not-empty (ex-data error))]
  (prn data))
```

So the cause line is present and first — an improvement over the filed
state — but it is still followed by the entire init JVM output on a single
line. The note's own "the operator's direct terminal failure face is still
outstanding" remains the accurate summary.

A publication was deliberately NOT failed for this check: inducing one
would publish to the live `default` cluster, which this verification is not
permitted to do. Source-exact verdict.

surface: operator

Fix sketch: retain `output` to `logs/current-source-failure.log` (the hook
already names that file) and let the failure data carry the log path plus a
bounded tail, so `prn` of the data is small by construction rather than by
the printer's discretion.
