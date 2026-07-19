---
type: issue
status: open
tags: [agent, pod, issue]
---

# Execution result diagnostic retained invalid map key

## Problem

An execution function that returns a map with a non-ordinary key should receive
an ordinary agent error. The IPC diagnostic instead embedded that invalid key
inside `:seon.execution/value-path`, so the diagnostic itself could not cross
IPC and the execution child exited with a core fault.

## Evidence

The first live lifecycle run for agent `plain-chefs-do` closed as `:crashed` at
turn `twu48whmx73j`. Its child exited from `seon.execution/send!` with “The
child attempted to send an invalid IPC message” before returning any evals.
The diagnostic path construction was the only branch that copied a rejected
host value into the bounded error value.

## Owner

`seon.execution/bounded-result` owns conversion of arbitrary function results
into either an ordinary bounded result or an ordinary agent error.

## Acceptance

- A non-ordinary map key produces an ordinary error with a structural
  `:map-key` path rather than retaining the key.
- Focused execution tests pass.
- Retrying the same live agent reaches its model reply without an IPC crash.
