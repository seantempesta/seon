---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, agent, test, database]
---

# The live issue status read is refused as depending on turn-taking

## Problem

A system turn for the issue-family worker returns
`:seon.turn/generated-read-depends-on-turns` before reaching settlement.
The offending generated form is `(my.issue/status {:seon.issue/id
"agent-form-calls-to-core-namespaces-are-not-indexed"})`.

## Evidence

On default PID 7595, worker `12254041a057`, the issue-settlement lane called
`seon.turn/system-turn` with explicit cluster custody and its carried schema
projection. A repeated probe after P5/P6 returned the same refusal in 1767 ms.
Its complete artifact is
`9f3c6a64576580653eb6d52e2ff2b31059543a48221c48969e6adaee55c4ea11`.
The read evidence names 68 attributes, including `:my.agent/turns-left`,
`:seon.cluster.eval/id` and provider attempt attributes. The refusal names
`:seon.wake/context-inert` as its expected dependency shape.

`src/seon/issue.clj:212` owns the status read, including test verification;
`src/seon/test.clj:483` owns `verified?` and its reach-digest read;
`src/seon/turn.clj:2049` owns generated-read dependency admission. The exact
operation introducing the turn dependencies has not been isolated. Do not
attribute it to a particular query from the outer form alone.

## Boundary and acceptance

This is outside issue-settlement's four turn settlement sites and issue-writer
guard. Its canonical system-turn regression passes; the live issue's direct
settlement owner records green run 70922 and joint completion/resolution
transaction 536871824. The residual is the live generated-read path.

Probe the nested read evidence at the existing owners, then verify that the
ordinary generated issue-status read and full system turn succeed without
suppressing genuine turn dependencies. Preserve verified-test semantics.
