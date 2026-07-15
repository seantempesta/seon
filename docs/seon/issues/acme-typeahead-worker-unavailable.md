---
type: issue
status: open
severity: friction
tags: [issue, agent, milestone, component]
---

# ACME typeahead worker is unavailable during live Inspect runs

## Problem

ACME is the diffusion/typeahead testbed, but a native generated database
workflow reached the pod while its configured worker transport was unavailable.
The run closed `:error` before producing a form. This is an infrastructure
failure, not evidence about the selected model's database capability.

## Evidence

The native Inspect artifact under
`evals/runs/2026-07-15-p0-db-static-acme/inspect-logs/` records sample
`database_workflow-seed1-000`, agent `smart-planets-sell`, one turn, zero
evals, and final database transaction `536871061`. Its exact turn bundle carries
the bounded error `DiffusionGemma submit/poll connection failed: fetch failed`.
The response's resolved model projection says provider `typeahead`, model
`muse-spark-1.1`, thinking `minimal`; that projection does not identify the
worker endpoint, implementation, weights, or transport state that actually
failed.

The initial native log incorrectly carried an ordinary incorrect milestone
score. A scorable-state guard now rejects timeout and `:error` closes on the
task paths that invoke it, but the common static-pod solver does not yet apply
that guard. [[inspect-capability-solvers-score-infrastructure-closes]] owns the
remaining shared-boundary correction.

## Owner

The ACME database-backed `:seon.ai` selection and the typeahead worker launcher
and runtime identity returned by the provider adapter. Inspect records that
identity and failure; it does not infer or repair the worker.

## Acceptance

- ACME reports the non-secret worker endpoint origin, server implementation and
  version, model revision or weights digest, quantization, and response worker
  identity alongside the resolved behavioral configuration.
- A readiness probe proves the selected worker can accept the configured mode
  before a scored run; absence or transport failure invalidates the sample.
- Two servers with the same advertised model name but different artifact
  identities cannot enter the same comparison cell.
- One native static-ACME Inspect sample produces at least one real model reply
  and retains the worker identity and exact turn/eval evidence.
