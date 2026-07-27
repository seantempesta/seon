---
type: issue
status: superseded
severity: friction
tags: [issue, web, agent]
---

# AI and HTML render twins may run one derivation twice

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — web slice 2.** The shared render-unit engine must derive
both twins once per frozen database value as part of the web transition.

## Problem

The AI context render and HTML surface render can independently invoke the same
database-derived function for one frozen database value.

## Evidence

The archived dual-path audit's C25 row records duplicate invocation. This is a
candidate cause of repeated SCI cost and memory sawtoothing on large feeds.

## Owner

The general render-unit engine that derives both twins from one renderable
context block.

## Acceptance

Profiling shows one derivation per matching function/input/database basis, both
twins compose from that result, bounded reuse invalidates only changed units,
and arbitrary agent-authored canvas functions use the same mechanism.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
