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

## Implementation state

`9f826df1` targets setup at the agent's home namespace, preserves the batch's
derived current namespace for evaluation, returns setup failures as values,
records their underlying data, and still runs the batch. Focused plan, eval,
and runtime proof passes 87 tests / 382 assertions.

## Live proof (default cluster, 2026-07-21)

- Pre-fix reproduction on then-current artifacts: fresh agent
  yummy-wolves-dress, `set-namespace!` → `my.kb`, user message → run 14677
  closed `:error` at 04:45:39Z, turn `vcld3havi54s` `:error` with the generic
  swallowed message, zero `llm-attempts`, fault data only `{:agent-ns my.kb}`.
- Post-fix (same agent, still resident in `my.kb`): run 16649 opened at
  05:03:06Z and closed `:waited`; turn `crned0osxzic` completed with eval
  datom `["crned0osxzic" my.kb true "(+ 20 22)"]` — the LLM ran, the form
  evaluated IN `my.kb`, and follow-on turns kept completing. The wedge class
  is gone.
- Forced setup failure (transacted
  `:seon.eval/home-requires [[seon.agent.lifecycle :refer
  [not-a-real-lifecycle-var]]]`, then a wake): the fault now PRESERVES the
  complete underlying analyzer chain — "Could not parse ns form
  my.agent.yummy-wolves-dress" ← "Invalid :refer, var
  seon.agent.lifecycle/not-a-real-lifecycle-var does not exist" (fault at
  basis-t 536875852). Acceptance line 2 satisfied.
- That forced run also exposed a residual: recording the setup failure as
  `:core` under the cluster's `on-core-error :crash` dial exits the child →
  run `:crashed` (16714) — a second wedge shape for a broken home
  declaration. Since `:seon.eval/home-requires` is agent-writable re-arm
  data, the follow-up commit reclassifies the record as fault `:agent`
  (recorded, never escalates, batch proceeds); unit-proven in
  `seon.execution.runtime-test/ns-setup-failure-records-a-fault-and-still-runs-the-batch`
  (17 tests / 88 assertions green). Its live artifact publish was pending on
  an unrelated concurrent `my.plan` refactor holding the watcher not-ready;
  re-verify live after the next clean publish, then close.

Root cause note: the manifest's default `:seon.eval/home-requires` carries
`[my.kb :as kb]` (and the other toolkit nses), so re-declaring a toolkit
current-ns self-required it; the durable fix removes the re-declaration class
entirely rather than special-casing the analyzer behavior change in the cljc
window.

## Workaround used (battery lane, 2026-07-21)

Re-assert the agent's `:seon.agent/namespace` assignment (retract +
add of the same home-ns ref) so the newer assignment transaction
outranks the last `my.kb` eval and the next turn seeds from home.

## Triage — 2026-07-23

DISSOLVES into the reconciled cutover deletion of the `eval.cljs` self-host;
the described current-namespace wedge is in that self-host namespace state.
