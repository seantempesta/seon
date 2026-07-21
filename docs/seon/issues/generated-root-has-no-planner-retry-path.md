---
type: issue
status: open
severity: friction
tags: [issue, agent, flow]
---

# A failed planner run leaves a generated root open with no re-drive

## Problem

`seon.ai/generate-code!` commits the generated root with its one planning
assignment message and claim. `my.plan/publish-generated-program!` keys the
DAG publication off `run → :seon.agent.run/cause → the root's message`. When
the planner's first run closes `:error` before any program publishes (live
case: a Kimi K3 timeout at the 300 s planning variant cap), nothing re-wakes
the planner: the root-scoped scheduler observes an empty frontier forever,
and a NEW wake message to the planner would open a run whose cause is not
the root message, so its reply would evaluate but never publish the DAG.
The root stays `:open` with its claim set and the caller never receives a
terminal result.

## Evidence

Live gencode-cluster drive 2026-07-21 02:01:59Z
(`logs/clusters/gencode/pod/…log`): root `tn6d6i8ywnek`, planner
`red-pugs-spend`, `OpenAI-compat request timed out / aborted` after 300 s,
`halt turn :error → close run :error`; the root remained `:open`/claimed and
was restored by every later boot (`restored-roots [… "tn6d6i8ywnek"]`)
without any re-drive. An earlier identical strand is root `lg145imfv2ms`
(planner turn killed by the parse-forms instrumentation regression, since
fixed and archived).

## Acceptance

The root-scoped scheduler (or terminal owner) must observe a planner run
that closed `:error` with no published namespace DAG and either re-issue
the planning assignment through the existing message/claim mechanism
(bounded retries) or commit the `:blocked` terminal with the run's error
evidence so the caller receives an honest result. No new registry or second
scheduler; the existing root observer and `commit-generated-terminal!` are
the owners.
