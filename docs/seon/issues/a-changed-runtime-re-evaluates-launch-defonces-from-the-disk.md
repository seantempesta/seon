---
type: issue
status: open
severity: defect
tags: [issue, sci, runtime, values]
---

# A changed runtime evaluates launch records again, so a branch reads today's disk as what the JVM loaded

`seon.sci.eval/bind-analysed!` evaluates every open value row from its source on the first runtime whose program
differs (`(not (contains? roots sym))`), `defonce` as `def` (`seon.sci.eval/evaluated-values`). 72 of 476 value rows on
a branch with one changed leaf (lane runtime-rereview census, 2026-10-19). Among them are launch records whose
meaning is the moment the JVM loaded: `seon.cluster.source/launched-dependencies` ("read once when the namespace first
loads at launch") and `seon.cluster/launched-source-digests`.

Reproduced 2026-10-19 ([runtime-rereview](../../research/agent-platform/2026-10-19/runtime-rereview.md) R5): after
launch the probe changes the throwaway archive's `src/seon/profile.clj` and `deps.edn`; compiled code answers the
launch digests (`f9f55c7e…`, `d075ebfe…`), the branch runtime the new ones (`f4d22e58…`, `789c1516…`). It also re-reads
every loaded source file per changed runtime.

Owner: `seon.sci.eval/bind-analysed!`'s due rule for values. The branch-runtime design said values and state are never
re-evaluated; a value the runtime must re-derive is one whose reach a change holds, not one that is merely open.
