---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

GENERATED FILE — do not hand-edit. Regenerate with `bin/issues-index`.
Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention.

## Blocker (12)

| Issue | Severity | Lane |
|-------|----------|------|
| [A lost stream clear paints a stale reply forever](a-lost-stream-clear-paints-a-stale-reply-forever.md) | blocker | Core |
| [A refused transaction needs value-based classification at the transact wrapper](transaction-refusal-loses-its-ex-data.md) | blocker | Core |
| [Bound http-kit streaming writes for slow SSE consumers](http-kit-streaming-writes-have-an-unbounded-socket-queue.md) | blocker | UI |
| [Bound submission startup by the declared time limit](flow-submit-waits-forever-before-time-limit.md) | blocker | Core |
| [Derive prompts through render units](prompt-assembly-bypasses-the-render-router.md) | blocker | agent |
| [Keep a failed cluster stop addressable](cluster-stop-release-failure-becomes-unaddressable.md) | blocker | Core |
| [Keep evaluation failures inside total admission](sci-eval-failure-bypasses-total-admission.md) | blocker | general |
| [Make program graph render declarations resolvable](program-graph-render-declarations-name-absent-functions.md) | blocker | Core |
| [Mixed-union Datahike declaration lacks the fresh EDN codec](mixed-union-datahike-declaration-lacks-fresh-edn-codec.md) | blocker | Core |
| [Replace bare flow callback predicates with honest contracts](flow-callback-schemas-are-not-generatively-constructible.md) | blocker | Core |
| [Root blocks carry two key vocabularies, so the page 500s](root-blocks-carry-two-key-vocabularies-and-500-the-page.md) | blocker | general |
| [`bin/seon up` exits 0 after a readiness timeout](operator-up-exits-zero-on-readiness-timeout.md) | blocker | general |

## Friction (17)

| Issue | Severity | Lane |
|-------|----------|------|
| [A failed ephemeral bind NPEs instead of saying what happened](web-start-npe-when-an-ephemeral-bind-fails.md) | friction | UI |
| [A failed form does not stop the fold, so a run can complete with a lie](a-failed-form-does-not-stop-the-fold.md) | friction | general |
| [A nil query input matches anything, so prompt cannot refuse a nil trigger](a-nil-query-input-matches-anything-so-prompt-cannot-refuse.md) | friction | Core |
| [A self-referential registered schema overflows the stack instead of refusing](a-self-referential-schema-overflows-the-stack.md) | friction | Core |
| [Align vendored Malli source with the pinned dependency](malli-vendor-is-ahead-of-pinned-dependency.md) | friction | general |
| [An ordinary agent's block set has no production caller](an-ordinary-agents-block-set-has-no-production-caller.md) | friction | agent |
| [Contracts that require a LIVE connection are called with a released one](instrumentation-surfaces-released-connection-contracts.md) | friction | Core |
| [Five namespaces claim they await implementation](five-namespaces-claim-they-await-implementation.md) | friction | general |
| [Flow monitor test preselects an unreserved port](flow-monitor-test-preselects-an-unreserved-port.md) | friction | Core |
| [Keep the old source tree off Babashka's default classpath](babashka-default-classpath-exposes-src-old.md) | friction | general |
| [Route ordinary stderr presentations through log renders](stderr-presentations-bypass-the-log-render-kind.md) | friction | general |
| [Stop fallback kills innocent shared-JVM clusters](stop-fallback-kills-innocent-shared-jvm-clusters.md) | friction | general |
| [The design language's font is redistributed without its license, and only at one weight](the-bundled-font-has-no-license-and-only-one-weight.md) | friction | UI |
| [The root agent facts link drills a value that has no entities](the-root-agent-facts-link-drills-a-value-that-has-no-entities.md) | friction | UI |
| [Three smaller defects in the vendored Datahike, found beside the card-many scan bug](datahike-planner-and-caches-carry-three-smaller-defects.md) | friction | Core |
| [`seon.flow/submit!!` awaits `started` with no bound](submit-awaits-started-with-no-bound.md) | friction | Core |
| [stop! may leave the prepl server name registered](stop-may-leave-the-prepl-server-name-registered.md) | friction | general |

## Cleanup (4)

| Issue | Severity | Lane |
|-------|----------|------|
| [Block attribute vocabulary splits across architecture docs](block-attribute-vocabulary-splits-across-architecture-docs.md) | cleanup | Core |
| [Give (pid, start-instant) liveness one owner](process-liveness-check-has-no-single-owner.md) | cleanup | general |
| [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | cleanup | Core |
| [Remove the stale program-graph owner rename](architecture-program-graph-owner-rename-is-stale.md) | cleanup | Core |
