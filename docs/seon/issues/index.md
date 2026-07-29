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

## Verified triage

| Rank | Classification | Issue | Why now | Owner / rung |
|------|----------------|-------|---------|--------------|
| 1 | PRESSING | [Route agent evals through the bounded compute owner](agent-turns-bypass-the-bounded-compute-door.md) | Confirmed spine blocker: production turns still evaluate SCI inline on an `:io` proc. | Agent-flow bounded-compute fix wave |
| 2 | PRESSING | [Bound submission startup by the declared time limit](flow-submit-waits-forever-before-time-limit.md) | `submit!!` still waits on `@started` before its timed result wait; this must fold into rank 1. | Same bounded-compute fix wave |
| 3 | PRESSING | [`seon.flow/submit!!` awaits `started` with no bound](submit-awaits-started-with-no-bound.md) | Duplicate evidence for rank 2's startup wait; preserve until the shared fix closes both. | Same bounded-compute fix wave |
| 4 | PRESSING | ["A dial exists" has no single authority](a-dial-exists-has-no-single-authority.md) | The registration split remains, but config patching is frozen pending the `config-aero-quarry` mining return. | Config/schema reconciliation rung |
| 5 | PRESSING | [Make program graph render declarations resolvable](program-graph-render-declarations-name-absent-functions.md) | The catalog still advertises six absent projection functions, so every advertised family can return `:seon.render/unresolvable`. | N5 / program-graph render rung |
| 6 | PRESSING | [Keep a failed cluster stop addressable](cluster-stop-release-failure-becomes-unaddressable.md) | A release failure still removes the registry marker and strands the live generation. | Cluster lifecycle transition |
| 7 | PRESSING | [Bound http-kit streaming writes for slow SSE consumers](http-kit-streaming-writes-have-an-unbounded-socket-queue.md) | Fresh Datastar writes reach http-kit’s still-unbounded `toWrites` list. | Web transport boundary |
| 8 | PRESSING | [Cold resume loses the defs and aliases the plan prefix established](cold-resume-loses-the-defs-and-aliases-the-plan-prefix-established.md) | Resume still forks without the evaluated prefix and cannot replay effects. | Parser-merge wave |
| 9 | PRESSING | [Preserve original line endings in SCI reader source spans](sci-reader-normalizes-crlf-but-reply-uses-original-offsets.md) | CRLF source and original offsets still produce a bogus trailing form. | Parser-merge wave |
| 10 | REAL-BUT-QUEUED | [Mixed-union Datahike declaration lacks the fresh EDN codec](mixed-union-datahike-declaration-lacks-fresh-edn-codec.md) | The documented codec half is absent, but no live heterogeneous attribute consumer is evidenced. | Schema-EDN transaction boundary |
| 11 | REAL-BUT-QUEUED | [Replace bare flow callback predicates with honest contracts](flow-callback-schemas-are-not-generatively-constructible.md) | Ten process-local callback schemas remain bare `fn?`; honest but not spine-blocking work. | Flow/schema honesty rung |

Twenty-three open notes remain to be verified against current source. Their
severity grouping below is inventory, not a completed triage classification.

## Closed by current-tree verification

| Classification | Issue | What dissolved it |
|----------------|-------|-------------------|
| DISSOLVED | [An ordinary agent's block set has no production caller](archive/an-ordinary-agents-block-set-has-no-production-caller.md) | `df160158f` made `creation-tx` install the block set with the agent. |
| DISSOLVED | [Fresh-corpus render retains a removed my.store reference](archive/fresh-corpus-render-retains-my-store-reference.md) | The reset-only cluster ruling discards stale program rows; current source has no `my.store` row. |
| DISSOLVED | [A lost stream clear paints a stale reply forever](archive/a-lost-stream-clear-paints-a-stale-reply-forever.md) | `fb1ce96d8` deleted clear entries and made terminal facts supersede cached partials. |
| DISSOLVED | [A refused transaction needs value-based classification at the transact wrapper](archive/transaction-refusal-loses-its-ex-data.md) | `93aa9d6de` and `e1f7262c6` landed the transaction wrapper and shared cause-chain classifier. |
| DISSOLVED | [An owner can never fix a red form into settlement](archive/an-owner-can-never-fix-a-red-form-into-settlement.md) | The 2026-07-29 late-morning ruling replaced `:owner-fixed` with test-based goal completion. |
| DISSOLVED | [Root blocks carry two key vocabularies, so the page 500s](archive/root-blocks-carry-two-key-vocabularies-and-500-the-page.md) | Plan law L18 resets clusters to current pages; current source installs only `:seon.render.block/*`. |
| DISSOLVED | [Keep evaluation failures inside total admission](archive/sci-eval-failure-bypasses-total-admission.md) | `1c7abb6a7` routes failure values through total admission before persistence. |
| STALE/WRONG | [`bin/seon up` exits 0 after a readiness timeout](archive/operator-up-exits-zero-on-readiness-timeout.md) | Current `wait-ready!` throws and the CLI exits 1; `bin/seon` preserves that exit. |

## Blocker (9)

| Issue | Severity | Lane |
|-------|----------|------|
| [Bound http-kit streaming writes for slow SSE consumers](http-kit-streaming-writes-have-an-unbounded-socket-queue.md) | blocker | UI |
| [Bound submission startup by the declared time limit](flow-submit-waits-forever-before-time-limit.md) | blocker | Core |
| [Cold resume loses the defs and aliases the plan prefix established](cold-resume-loses-the-defs-and-aliases-the-plan-prefix-established.md) | blocker | general |
| [Keep a failed cluster stop addressable](cluster-stop-release-failure-becomes-unaddressable.md) | blocker | Core |
| [Make program graph render declarations resolvable](program-graph-render-declarations-name-absent-functions.md) | blocker | Core |
| [Mixed-union Datahike declaration lacks the fresh EDN codec](mixed-union-datahike-declaration-lacks-fresh-edn-codec.md) | blocker | Core |
| [Preserve original line endings in SCI reader source spans](sci-reader-normalizes-crlf-but-reply-uses-original-offsets.md) | blocker | general |
| [Replace bare flow callback predicates with honest contracts](flow-callback-schemas-are-not-generatively-constructible.md) | blocker | Core |
| [Route agent evals through the bounded compute owner](agent-turns-bypass-the-bounded-compute-door.md) | blocker | agent |

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
