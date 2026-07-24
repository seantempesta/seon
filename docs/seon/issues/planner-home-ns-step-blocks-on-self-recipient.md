---
type: issue
status: open
severity: friction
tags: [issue, agent, flow]
---

# Planner home-ns step blocks the root on a self-recipient refusal

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — U9 deletion.** Generated namespace-DAG dispatch is outgoing
scheduler behavior; U9 retains the self-recipient falsifier in its cutover
acceptance.

## Problem

When the planning agent's reply declares its OWN home namespace
(`(ns my.agent.<planner> …)` — a natural first form for an exploring
model), the parse-once projection fences later forms under it and
`compile-namespace-dag` publishes `my.agent.<planner>` as an ordinary
namespace step. The scheduler then ensures the namespace's unique
resident — the planner itself — and the atomic assignment commit fails
with `message!: refused self-recipient — sender and recipient must
differ.` Dispatch failure blocks the whole root on the first frontier.

## Evidence

Fresh gencode cluster drive 2026-07-21 05:03Z: planner
`goofy-memes-taste` opened with
`(ns my.agent.goofy-memes-taste (:require …))` then explored with
`fs`/`search` calls instead of emitting the program. Root `jb86u5r7pb4c`
published exactly one namespace step `my.agent.goofy-memes-taste`,
dispatch refused the self-recipient, and the root delivered the
`:blocked` terminal envelope carrying that error (caller turn
`xaguzbcuh9fs` rendered it).

## Acceptance

A generated root's namespace DAG never makes the coordinator's own home
namespace a dispatchable work unit: either the DAG compiler excludes the
planner's home namespace (its forms are planning scratch work, already
evaluated by the planner's own turn), or dispatch treats a unit whose
unique resident is the coordinator as self-work that completes from the
planner's own eval evidence. Blocking the root over its planner's
ordinary setup form is never acceptable. Covered by one focused test on
the DAG/dispatch owner.

Triage 2026-07-23 — **DISSOLVES into P4 loop migration**; preserve the self-recipient falsifier in replacement run-state acceptance.
