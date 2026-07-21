---
type: issue
status: open
tags: [agent, cljs, issue]
severity: blocker
---

# Toolkit current-ns wedges the agent since the cljc packaging window

## Evidence

Live default cluster, 2026-07-21. Agent real-hats-wave moved its REPL
namespace into the toolkit ns `my.kb` during a run (ordinary `in-ns`
movement; its successful evals from 03:59Z onward all carry
`:seon.eval/ns my.kb`). Turns kept working — L3 completed at 03:59Z with
three successful evals inside `my.kb`.

After the next execution-artifact rebuild (window includes `5ac8f0ef`
"Package portable toolkit owners as cljc" — my.kb.cljs → my.kb.cljc —
and `bc0a3b11`, committed 04:05:54Z), EVERY subsequent run for this
agent fails before the LLM call:

    SEON-CORE-FAULT setup-agent-ns! failed — the home-ns require/refer
    did not analyze cleanly for my.kb. ... {:agent-ns my.kb}

Runs 9390 and 9503 both closed `:error` with one `:error` turn each
(04:06:52Z, 04:07:55Z). The agent is permanently wedged: setup throws
before any eval, so no eval can ever move current-ns back, and every
future wake fails identically. Root (home-ns current-ns) kept working
through the same window, so the breakage is specific to a toolkit
starting-ns.

## Two defects

1. `seon.agent.turn`/`seon.execution.runtime/eval-batch!` pass the
   agent's derived CURRENT ns to `seon.eval/setup-agent-ns!`, which
   evaluates `(ns <current> (:require <home requires>))`. When current
   is a toolkit ns this re-declares a namespace the agent does not own
   and self-requires it (`(ns my.kb (:require [my.kb :as kb] …))`).
   Analyzing that leniently ever worked is the accident; the wedge is
   the design gap.
2. Whatever changed in the rebuild window (cljc rename dropping my.kb
   from the bootstrap analyzer entries, and/or `bc0a3b11`) turned that
   accident into a hard failure. The underlying analyzer error is
   swallowed — the thrown ex-info's `:result r` is dropped by the fault
   path, leaving only the generic message; that loss of evidence is its
   own smell.

## Expected owner

`seon.eval/setup-agent-ns!` + its `seon.execution.runtime/eval-batch!`
call site (the execution/eval lane owns both). Setup must either target
the agent's OWN namespace only, or skip re-declaration when starting-ns
is a host-bundled namespace — and "nothing wedges": a setup failure must
not permanently disable an agent.

## Acceptance

- An agent sitting in `my.kb` (or any toolkit ns) receives a message and
  its turn reaches the LLM; forms evaluate in that ns or the run reports
  an honest recoverable error that does not recur forever.
- The underlying analyzer failure is preserved in the fault data.

## Workaround used (battery lane, 2026-07-21)

Re-assert the agent's `:seon.agent/namespace` assignment (retract +
add of the same home-ns ref) so the newer assignment transaction
outranks the last `my.kb` eval and the next turn seeds from home.
