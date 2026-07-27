---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

GENERATED FILE — do not hand-edit. Regenerate with `bin/issues-index`.
Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention.

## Blocker (13)

| Issue | Severity | Lane |
|-------|----------|------|
| [A refused transaction needs value-based classification at the transact wrapper](transaction-refusal-loses-its-ex-data.md) | blocker | Core |
| [Bound http-kit streaming writes for slow SSE consumers](http-kit-streaming-writes-have-an-unbounded-socket-queue.md) | blocker | UI |
| [Datahike's `:branches` roster loses branches under concurrent `branch!`](datahike-branch-roster-read-modify-write-race.md) | blocker | Core |
| [Derive core schema admission without an identity allowlist](schema-core-admission-uses-a-process-identity-allowlist.md) | blocker | Core |
| [Fence cluster stop against a replacement instance](cluster-stop-can-kill-a-replacement-instance.md) | blocker | general |
| [Fence held-run transitions by the live lease](run-held-transitions-ignore-lease-expiry.md) | blocker | Core |
| [Fence terminal receipt state and observe it in the run model](run-acceptance-properties-miss-takeover-and-terminal-preservation.md) | blocker | Core |
| [Fence the agent pointer when opening and closing a run](run-close-does-not-fence-the-agent-pointer.md) | blocker | agent |
| [Mixed-union Datahike declaration lacks the fresh EDN codec](mixed-union-datahike-declaration-lacks-fresh-edn-codec.md) | blocker | Core |
| [Put run-claim eligibility in one fenced transition](run-claim-eligibility-is-not-in-the-cas.md) | blocker | agent |
| [Recover a run opened before its plan commits](run-is-unrecoverable-before-its-plan-commits.md) | blocker | agent |
| [Retain the flock when Datahike release fails](store-release-failure-drops-the-flock.md) | blocker | Core |
| [`bin/seon up` exits 0 after a readiness timeout](operator-up-exits-zero-on-readiness-timeout.md) | blocker | general |

## Friction (3)

| Issue | Severity | Lane |
|-------|----------|------|
| [And-wrapped secondary Datahike attribute is rejected](and-wrapped-secondary-datahike-attribute-is-rejected.md) | friction | Core |
| [Keep the old source tree off Babashka's default classpath](babashka-default-classpath-exposes-src-old.md) | friction | general |
| [Preserve concurrent edits in the Gemini review backlog](gemini-review-pending-state-loses-concurrent-edits.md) | friction | agent |

## Cleanup (1)

| Issue | Severity | Lane |
|-------|----------|------|
| [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | cleanup | Core |
