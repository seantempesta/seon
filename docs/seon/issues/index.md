---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

GENERATED FILE — do not hand-edit. Regenerate with `bin/issues-index`.
Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention.

## Blocker (11)

| Issue | Severity | Lane |
|-------|----------|------|
| [A refused transaction needs value-based classification at the transact wrapper](transaction-refusal-loses-its-ex-data.md) | blocker | Core |
| [Bound http-kit streaming writes for slow SSE consumers](http-kit-streaming-writes-have-an-unbounded-socket-queue.md) | blocker | UI |
| [Bound submission startup by the declared time limit](flow-submit-waits-forever-before-time-limit.md) | blocker | Core |
| [Keep a failed cluster stop addressable](cluster-stop-release-failure-becomes-unaddressable.md) | blocker | Core |
| [Keep evaluation failures inside total admission](sci-eval-failure-bypasses-total-admission.md) | blocker | general |
| [Make the loop's open transaction satisfy the transact contract](loop-open-transaction-violates-transact-schema.md) | blocker | Core |
| [Mixed-union Datahike declaration lacks the fresh EDN codec](mixed-union-datahike-declaration-lacks-fresh-edn-codec.md) | blocker | Core |
| [Preserve transaction refusal reasons across every run-loop branch](run-loop-discards-transaction-refusal-reasons.md) | blocker | Core |
| [Replace bare flow callback predicates with honest contracts](flow-callback-schemas-are-not-generatively-constructible.md) | blocker | Core |
| [Wire Flow errors and wake faults during cluster start](cluster-start-leaves-flow-errors-unconsumed.md) | blocker | Core |
| [`bin/seon up` exits 0 after a readiness timeout](operator-up-exits-zero-on-readiness-timeout.md) | blocker | general |

## Friction (2)

| Issue | Severity | Lane |
|-------|----------|------|
| [Align vendored Malli source with the pinned dependency](malli-vendor-is-ahead-of-pinned-dependency.md) | friction | general |
| [Keep the old source tree off Babashka's default classpath](babashka-default-classpath-exposes-src-old.md) | friction | general |

## Cleanup (3)

| Issue | Severity | Lane |
|-------|----------|------|
| [Give (pid, start-instant) liveness one owner](process-liveness-check-has-no-single-owner.md) | cleanup | general |
| [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | cleanup | Core |
| [Remove the stale program-graph owner rename](architecture-program-graph-owner-rename-is-stale.md) | cleanup | Core |
