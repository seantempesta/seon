---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

Owner-requested triage is in progress in sub-five-minute committed slices.
Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention. The severity projection below remains the
complete open inventory while each row receives current-tree verification.

## Verified triage — slice 1

| Rank | Classification | Issue | Why now | Owner / rung |
|------|----------------|-------|---------|--------------|
| 1 | PRESSING | [Route agent evals through the bounded compute owner](agent-turns-bypass-the-bounded-compute-door.md) | Confirmed spine blocker: production turns still evaluate SCI inline on an `:io` proc. | Agent-flow bounded-compute fix wave |
| 2 | PRESSING | [Bound submission startup by the declared time limit](flow-submit-waits-forever-before-time-limit.md) | `submit!!` still waits on `@started` before its timed result wait; this must fold into rank 1. | Same bounded-compute fix wave |
| 3 | PRESSING | [`seon.flow/submit!!` awaits `started` with no bound](submit-awaits-started-with-no-bound.md) | Duplicate evidence for rank 2's startup wait; preserve until the shared fix closes both. | Same bounded-compute fix wave |
| 4 | PRESSING | ["A dial exists" has no single authority](a-dial-exists-has-no-single-authority.md) | Config admission, database installation, and defaults still require separate registration edits; a fix lane is relaunching. | Config/schema reconciliation rung |

Thirty-five open notes remain to be verified against current source. Their
severity grouping below is inventory, not a completed triage classification.

## Closed by current-tree verification — slice 1

| Classification | Issue | What dissolved it |
|----------------|-------|-------------------|
| DISSOLVED | [An ordinary agent's block set has no production caller](archive/an-ordinary-agents-block-set-has-no-production-caller.md) | `df160158f` made `creation-tx` install the block set with the agent. |
| DISSOLVED | [Fresh-corpus render retains a removed my.store reference](archive/fresh-corpus-render-retains-my-store-reference.md) | The reset-only cluster ruling discards stale program rows; current source has no `my.store` row. |

## Blocker (14)

| Issue | Severity | Lane |
|-------|----------|------|
| [A lost stream clear paints a stale reply forever](a-lost-stream-clear-paints-a-stale-reply-forever.md) | blocker | Core |
| [A refused transaction needs value-based classification at the transact wrapper](transaction-refusal-loses-its-ex-data.md) | blocker | Core |
| [An owner can never fix a red form into settlement](an-owner-can-never-fix-a-red-form-into-settlement.md) | blocker | general |
| [Bound http-kit streaming writes for slow SSE consumers](http-kit-streaming-writes-have-an-unbounded-socket-queue.md) | blocker | UI |
| [Bound submission startup by the declared time limit](flow-submit-waits-forever-before-time-limit.md) | blocker | Core |
| [Cold resume loses the defs and aliases the plan prefix established](cold-resume-loses-the-defs-and-aliases-the-plan-prefix-established.md) | blocker | general |
| [Keep a failed cluster stop addressable](cluster-stop-release-failure-becomes-unaddressable.md) | blocker | Core |
| [Keep evaluation failures inside total admission](sci-eval-failure-bypasses-total-admission.md) | blocker | general |
| [Make program graph render declarations resolvable](program-graph-render-declarations-name-absent-functions.md) | blocker | Core |
| [Mixed-union Datahike declaration lacks the fresh EDN codec](mixed-union-datahike-declaration-lacks-fresh-edn-codec.md) | blocker | Core |
| [Replace bare flow callback predicates with honest contracts](flow-callback-schemas-are-not-generatively-constructible.md) | blocker | Core |
| [Root blocks carry two key vocabularies, so the page 500s](root-blocks-carry-two-key-vocabularies-and-500-the-page.md) | blocker | general |
| [Route agent evals through the bounded compute owner](agent-turns-bypass-the-bounded-compute-door.md) | blocker | agent |
| [`bin/seon up` exits 0 after a readiness timeout](operator-up-exits-zero-on-readiness-timeout.md) | blocker | general |

## Friction (20)

| Issue | Severity | Lane |
|-------|----------|------|
| ["A dial exists" has no single authority](a-dial-exists-has-no-single-authority.md) | friction | general |
| [A failed ephemeral bind NPEs instead of saying what happened](web-start-npe-when-an-ephemeral-bind-fails.md) | friction | UI |
| [A frozen disposition can close a run against facts newer than the basis it was written at](a-frozen-disposition-can-close-against-newer-facts.md) | friction | general |
| [A nil query input matches anything, so prompt cannot refuse a nil trigger](a-nil-query-input-matches-anything-so-prompt-cannot-refuse.md) | friction | Core |
| [A self-referential registered schema overflows the stack instead of refusing](a-self-referential-schema-overflows-the-stack.md) | friction | Core |
| [A slow-tab proof can count a late initial derivation](a-slow-tab-proof-can-count-a-late-initial-derivation.md) | friction | general |
| [Align vendored Malli source with the pinned dependency](malli-vendor-is-ahead-of-pinned-dependency.md) | friction | general |
| [An agent can be assigned its own red form, and the loop delivers it](an-agent-can-be-assigned-its-own-red-form.md) | friction | general |
| [Boot cannot select a config manifest, so a cluster cannot choose its provider](boot-cannot-select-a-config-manifest.md) | friction | general |
| [Contracts that require a LIVE connection are called with a released one](instrumentation-surfaces-released-connection-contracts.md) | friction | Core |
| [Flow monitor test preselects an unreserved port](flow-monitor-test-preselects-an-unreserved-port.md) | friction | Core |
| [JDK Integers refuse at every database boundary](jdk-integers-refuse-at-every-database-boundary.md) | friction | Core |
| [Keep the old source tree off Babashka's default classpath](babashka-default-classpath-exposes-src-old.md) | friction | general |
| [Prevent a detached load drive from restarting after success](detached-load-launch-restarts-after-success.md) | friction | general |
| [Route ordinary stderr presentations through log renders](stderr-presentations-bypass-the-log-render-kind.md) | friction | general |
| [Stop fallback kills innocent shared-JVM clusters](stop-fallback-kills-innocent-shared-jvm-clusters.md) | friction | general |
| [The design language's font is redistributed without its license, and only at one weight](the-bundled-font-has-no-license-and-only-one-weight.md) | friction | UI |
| [Three smaller defects in the vendored Datahike, found beside the card-many scan bug](datahike-planner-and-caches-carry-three-smaller-defects.md) | friction | Core |
| [`seon.flow/submit!!` awaits `started` with no bound](submit-awaits-started-with-no-bound.md) | friction | Core |
| [stop! may leave the prepl server name registered](stop-may-leave-the-prepl-server-name-registered.md) | friction | general |

## Cleanup (5)

| Issue | Severity | Lane |
|-------|----------|------|
| [Block attribute vocabulary splits across architecture docs](block-attribute-vocabulary-splits-across-architecture-docs.md) | cleanup | Core |
| [Give (pid, start-instant) liveness one owner](process-liveness-check-has-no-single-owner.md) | cleanup | general |
| [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | cleanup | Core |
| [Remove the stale program-graph owner rename](architecture-program-graph-owner-rename-is-stale.md) | cleanup | Core |
| [Replace contract-scaffold prose with current namespace contracts](implemented-namespaces-still-instruct-a-stub-filling-lane.md) | cleanup | general |
