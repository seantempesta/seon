---
type: issue
status: open
severity: friction
created: 2026-09-22
tags: [issue, effect, test, wave/contract-gate]
---

# Shell timeout refusal is not admitted by the run contract

## Problem

The shell timeout regression receives a handler-failure error whose declared
schema
the armed public return contract does not admit.

## Evidence

The slice 1 fast snapshot at `f08558cb5`, recorded run `0b0cd41bc68d`, ran
`seon.shell.jvm-test/time-limit-reaps-the-process-tree-and-marks-the-effect-interrupted`
under armed contracts. It stopped with `my.shell/run! returned undeclared
error schemas #{:seon.effect/handler-failed-error}`. That prevents the test
from checking its expected interrupted effect and process-tree cleanup.
The test body, shell implementation, effect owner, and their schemas were
unchanged by the operator slice.

## Owner

Inspect the source handler failure before changing the `my.shell/run!`
return declaration. The observed outer contract failure does not establish
why the handler failed.

## Acceptance

The existing timeout regression must reach and
verify the interrupted effect and the absence of its child process under
armed contracts. This is outside slice 1; no effect or shell implementation
was changed here.
