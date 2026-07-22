---
type: research
status: complete
tags: [research, agent, database]
---

# Live turn frame defect audit

## Finding

Turn `va1uj4ncpg9u` failed before its first eval because the execution child
requested the complete authored program and configuration in one unbounded
`execute-many` response while the coordinated checkpoint writer enforced a
64 KiB frame ceiling.

At the recorded database value, the same successful seven-member response is
about 422,059 characters by `pr-str`; its member sizes are
`[2270 4343 974 986 298935 102421 11851]`. The writer therefore replaced it
with the bounded correlated `frame-too-large` failure during the checkpoint.
`seon.db/execute-many` returned that failure as a top-level error map without
`:seon.db/results`. `prepare-eval-program!` read the absent results and called
`subvec` on nil. ClojureScript's exact error for that operation is
`v must satisfy IVector`.

The reply parser is not involved. A read-only MCP replay of the exact reply
returned a vector containing the three expected forms: `(ns my.demo)`, the
top-level `require`, and `my.plan/active!`. No eval receipt exists because
child program preparation precedes `eval-batch!`.

## Source evidence

- `src/seon/execution.cljs:337-402` permits complete program query members to
  return up to 3 MiB each and up to 16,384 rows.
- `src/seon/execution.cljs:709-726` groups six complete program members and
  the full configuration pull into one response, extracts absent
  `:seon.db/results`, then calls `(subvec results 0 6)` without checking the
  top-level error.
- `src/seon/db.cljs:1130-1157` returns a top-level error map for a failed
  `execute-many` response; only success carries `:seon.db/results`.
- `src/seon/db/transport/uds.cljc:895-917` replaces an encoded response over
  the negotiated ceiling with `frame-too-large`; its protocol value is defined
  at `src/seon/db/protocol.cljc:1352-1363`.
- `logs/operator/writer/0c83a7a9-2186-4f58-b4ce-cf7bdf88ade6.log:2-7`
  records the active writer's 65,536-byte ceiling. The pod log records the
  failed turn at 04:06:52, before the 4 MiB writer returned at 04:07:33.
- The persisted error at basis transaction 536870952 carries the CLJS stack
  and exact `v must satisfy IVector` message. Its outer frame is
  `ask-and-eval-reply!`, where the child error is rethrown; the originating
  child error frame had already reduced the cause to its ordinary message.

## History

This predates tonight. `git blame` attributes the program queries and 3 MiB
allowance to the July 16 execution-child series. The unsafe
`prepare-eval-program!` acquisition and `subvec` are from `97654066`, also
July 16. The `seon.ns.source` extraction did not modify this owner. W1.5b's
honest frame enforcement exposed the latent assumption.

## Correct fix and proof

The diagnostic minimum is to preserve a top-level acquisition error before
touching `:seon.db/results`, but that still leaves every eval impossible at
64 KiB. The functional fix is a bounded, complete, cursor-based acquisition
over the same immutable database value, using `index-page` and bounded
`pull-many` like `src/seon/runtime/admission.cljs:195-267`, while preserving
the canonical program shape and configuration read.

This overlaps q22's recorded convergence boundary: program acquisition,
namespaces context, and warnings context need one frame-safe paging recipe.
It is not a safe one-line limit adjustment and should not be implemented as
three new duplicate mechanisms.

Focused proof must cover `seon.execution-test`, the execution host/runtime
tests, top-level frame-error preservation, multi-page equality, and a fresh
agent drive under the supported 64 KiB ceiling with evals greater than zero.
