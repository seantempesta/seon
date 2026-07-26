---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime]
---

# Pod execution admitted duplicate local fibers

## Evidence

The reset-boundary U12 rerun produced two ordinary running turns for one run
under the same process and epoch. Each turn reached `:reply-ready`
with its own successful ordinal-zero attempt, while the run had exactly one
consumed-input edge. The database claim correctly excluded other processes holding runs,
but the wake leaf and scan leaf could both enter `drive-run!`; the `:held`
renewal transition intentionally accepts the same process identity and
therefore cannot arbitrate fibers inside that process.

## Resolution

The pod run-holding process now retains one process-local Promise handle per run, matching
the cluster JVM's retained virtual-thread handle. Every pod wake and scan
dispatches through that addressable handle. The database claim remains the
durable authority; the handle only enforces the concurrency recipe's one-fiber
rule inside a cluster JVM.

A focused CLJS regression invokes two dispatch leaves for one run and proves
one shared Promise, one driver invocation, and one shared result.

## Acceptance

- Concurrent pod wake and scan leaves execute one driver fiber per run.
- Two distinct processes still arbitrate exclusively through database CAS.
- Killing the pod workload loses only the local handle; takeover resumes from
  the database cursor under a higher epoch.
