---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

GENERATED FILE — do not hand-edit. Regenerate with `bin/issues-index`.
Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention.

## Blocker (6)

| Issue | Severity | Lane |
|-------|----------|------|
| [Cold resume loses the defs and aliases the plan prefix established](cold-resume-loses-the-defs-and-aliases-the-plan-prefix-established.md) | blocker | general |
| [Give every fresh public function a complete Malli contract](fresh-public-functions-lack-complete-malli-contracts.md) | blocker | Core |
| [Make program graph render declarations resolvable](program-graph-render-declarations-name-absent-functions.md) | blocker | Core |
| [Mixed-union Datahike declaration lacks the fresh EDN codec](mixed-union-datahike-declaration-lacks-fresh-edn-codec.md) | blocker | Core |
| [Replace bare flow callback predicates with honest contracts](flow-callback-schemas-are-not-generatively-constructible.md) | blocker | Core |
| [Terminalize a receipt when its terminal transaction is refused](refused-terminal-transaction-leaves-a-running-receipt-hot-loop.md) | blocker | agent |

## Friction (17)

| Issue | Severity | Lane |
|-------|----------|------|
| [A failed ephemeral bind NPEs instead of saying what happened](web-start-npe-when-an-ephemeral-bind-fails.md) | friction | UI |
| [A slow-tab proof can count a late initial derivation](a-slow-tab-proof-can-count-a-late-initial-derivation.md) | friction | general |
| [Align vendored Malli source with the pinned dependency](malli-vendor-is-ahead-of-pinned-dependency.md) | friction | general |
| [An agent can be assigned its own red form, and the loop delivers it](an-agent-can-be-assigned-its-own-red-form.md) | friction | general |
| [Flow monitor test preselects an unreserved port](flow-monitor-test-preselects-an-unreserved-port.md) | friction | Core |
| [Keep the old source tree off Babashka's default classpath](babashka-default-classpath-exposes-src-old.md) | friction | general |
| [Lane discipline: unverified stops and cross-lane steering](lane-discipline-stop-verification-and-cross-lane-steering.md) | friction | general |
| [Name database-value and transaction-data contracts](database-and-transaction-boundaries-use-anonymous-any-contracts.md) | friction | Core |
| [Prevent a detached load drive from restarting after success](detached-load-launch-restarts-after-success.md) | friction | general |
| [Publish graph transitions instead of polling them in tests](observable-graph-transitions-are-polled-in-tests.md) | friction | Core |
| [Re-ground schema skills in the fresh EDN and instrumentation system](schema-skills-teach-the-retired-registration-model.md) | friction | Core |
| [Refresh instrumentation before the fresh operator calls start](fresh-operator-start-enters-stale-instrumentation-before-refresh.md) | friction | general |
| [Route ordinary stderr presentations through log renders](stderr-presentations-bypass-the-log-render-kind.md) | friction | general |
| [Stop fallback kills innocent shared-JVM clusters](stop-fallback-kills-innocent-shared-jvm-clusters.md) | friction | general |
| [The design language's font is redistributed without its license, and only at one weight](the-bundled-font-has-no-license-and-only-one-weight.md) | friction | UI |
| [The review hook's rubric lags the omission ruling](review-rubric-lags-the-omission-ruling.md) | friction | general |
| [Three smaller defects in the vendored Datahike, found beside the card-many scan bug](datahike-planner-and-caches-carry-three-smaller-defects.md) | friction | Core |

## Cleanup (5)

| Issue | Severity | Lane |
|-------|----------|------|
| [Block attribute vocabulary splits across architecture docs](block-attribute-vocabulary-splits-across-architecture-docs.md) | cleanup | Core |
| [Derive repeated state vocabularies from their owning schemas](derived-state-contracts-repeat-hand-maintained-enums.md) | cleanup | Core |
| [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | cleanup | Core |
| [Remove the stale program-graph owner rename](architecture-program-graph-owner-rename-is-stale.md) | cleanup | Core |
| [Unify the nested-data walk shared by admission and rendering](value-admission-render-walk-overlap.md) | cleanup | general |
