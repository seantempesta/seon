---
type: issue
status: active
tags: [issue, agent, cljs, health, pod]
---

# Orchestration wrapper dropped child recovery evidence

## Evidence

On 2026-07-19, agent `legal-mugs-accept` lost its execution child while an
authored canvas repair turn was active. The host returned
`:seon.execution/child-retired? true` with process evidence, but the pod logged
`run-turn! orchestration error`, closed the run `:error`, and created no
`:seon.runtime.recovery/id` fact. A direct query of the current default
database found zero recovery anchors despite the observed child exit.

`run-turn-body!` and `close-turn!` preserved the committed turn ID and child
evidence. A later Malli/orchestration exception reached `run-turn!`'s outer
catch, whose response retained only `error/->message`. The loop therefore
could not distinguish a retired child from an ordinary turn error and never
entered `seon.runtime.recovery/recover!`.

## Owner and acceptance

`seon.agent.turn` owns preservation of the turn result across every outer
exception boundary. One transformation must extract the committed turn ID,
`:seon.execution/child-retired?`, and the host evidence through nested
exception data. Both `run-turn-body!` and `run-turn!` use it.

Focused proof must cover a wrapper around a child-exit exception. Live proof
must then show one child exit producing an interrupted eval, a crashed run,
one recovery anchor with diagnostic evidence, one fresh-child recovery run,
and no pod or writer exit. A repeated identical crash without later inbound
contact must remain stopped and notify root through the existing message path.

## Implementation evidence

`seon.agent.turn/turn-failure` now performs that one transformation. It uses
the complete flattened exception data, unwraps the retained host evidence,
and preserves the committed turn ID and child-retired flag at both catch
sites. Focused `seon.agent.turn-test` passes 9 tests and 23 assertions,
including an explicit nested orchestration-wrapper regression. The required
live interrupted-eval and recovery-anchor proof remains before resolution.
