---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

GENERATED FILE — do not hand-edit. Regenerate with `bin/issues-index`.
Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention.

## Blocker (8)

| Issue | Severity | Lane |
|-------|----------|------|
| [A refused transaction needs value-based classification at the transact wrapper](transaction-refusal-loses-its-ex-data.md) | blocker | Core |
| [Bound http-kit streaming writes for slow SSE consumers](http-kit-streaming-writes-have-an-unbounded-socket-queue.md) | blocker | UI |
| [Bound submission startup by the declared time limit](flow-submit-waits-forever-before-time-limit.md) | blocker | Core |
| [Keep a failed cluster stop addressable](cluster-stop-release-failure-becomes-unaddressable.md) | blocker | Core |
| [Keep evaluation failures inside total admission](sci-eval-failure-bypasses-total-admission.md) | blocker | general |
| [Mixed-union Datahike declaration lacks the fresh EDN codec](mixed-union-datahike-declaration-lacks-fresh-edn-codec.md) | blocker | Core |
| [Replace bare flow callback predicates with honest contracts](flow-callback-schemas-are-not-generatively-constructible.md) | blocker | Core |
| [`bin/seon up` exits 0 after a readiness timeout](operator-up-exits-zero-on-readiness-timeout.md) | blocker | general |

## Friction (5)

| Issue | Severity | Lane |
|-------|----------|------|
| [A turn's model work can outlive its own run lease](a-turns-model-work-can-outlive-its-own-run-lease.md) | friction | general |
| [Align vendored Malli source with the pinned dependency](malli-vendor-is-ahead-of-pinned-dependency.md) | friction | general |
| [Contracts that require a LIVE connection are called with a released one](instrumentation-surfaces-released-connection-contracts.md) | friction | Core |
| [Keep the old source tree off Babashka's default classpath](babashka-default-classpath-exposes-src-old.md) | friction | general |
| [stop! may leave the prepl server name registered](stop-may-leave-the-prepl-server-name-registered.md) | friction | general |

## Cleanup (3)

| Issue | Severity | Lane |
|-------|----------|------|
| [Give (pid, start-instant) liveness one owner](process-liveness-check-has-no-single-owner.md) | cleanup | general |
| [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | cleanup | Core |
| [Remove the stale program-graph owner rename](architecture-program-graph-owner-rename-is-stale.md) | cleanup | Core |
