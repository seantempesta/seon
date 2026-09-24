# An :or contract refusal's message names an unrelated alternative's problem

Found 2026-09-24 by lane fix-status-readable-response.

`seon.instrument` builds `:seon.error/message` from `(first problems)`
(`src/seon/instrument.clj:363`, used at `:382`). For an `:or` contract such as
`:seon.operator/response` (`resources/seon/schemas/seon.operator.edn`), Malli's
explanation carries the problems of every alternative, so the first problem is
whichever alternative is listed first, not the one the value nearly satisfies.

Observed: `bin/seon status` refused with "readable-response refused return
value at [:seon.error/at] ... missing :seon.error/at" (problem-count 23), which
sent several lanes looking for an error value lacking `:at`. The real and only
mismatch, proven at the REPL, was `[:seon.operator/clusters 0
:seon.dev.mcp/health]` = `:faulted` against `[:enum :observed :unknown]`; all
problems are present in `:seon.error/problems`, only the message misleads.

Wanted: an `:or` refusal's message names the problem of the alternative the
value reached deepest (longest `:in` path), or says the value matched no
alternative and names each alternative's deepest problem.
