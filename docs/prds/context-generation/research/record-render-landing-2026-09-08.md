---
type: research
status: in-progress
date: 2026-09-08
tags: [render, agent, test]
---

# Record render — landing evidence

The binding authority is
[the agent record PRD](../plan/agent-record-and-turn-loop-prd-2026-09-07.md),
including the owner's subsequent sections 13–15. AGENTS.md was read end to
end, including its lane paragraph and the default development cluster rule;
the named PRD was read end to end, and each appended ruling was read when
received. This is an integration record, not a completed acceptance report.

## Current slices

- Agent entity render pair: `seon.render.ns/render-agent-ai` and
  `render-agent-html`. One identity read includes id, namespace, and its
  steward. HTML uses the value renderer. The profile lookup derives the
  cluster name from the database, with no agent-to-cluster join.
- Compact plan reads: `my.plan/current`, `ready`, `blocked`, and `steps`.
  `start!` selects an owned step at the writer; `add!` and `complete!` return
  compact step maps. The plan pair is on `:my.plan/component-view`; its AI
  source chooses empty/populated forms from the data.

## Required final blocks

Identity, plan, unanswered wakes, steward-routed faults, history. Empty wake
and fault concerns disappear. History is last, includes all evaluations in
chronological order, and walks their entity render pairs. Generated forms
are for the ordinary system turn that turn-cut stores; page previews write
nothing. The five-block page assembly is not yet implemented.

## Exact identity source observed for Juniper

MCP JVM evaluation on main-root `default` returned these source bytes from
the hot-reloaded Var; this was not a browser observation or proof of source
adoption convergence:

```clojure
; This is my identity and namespace; its steward is responsible for it.
(seon.db/pull (quote [:seon.cluster.agent/id #:seon.cluster.agent{:namespace [:seon.ns/name #:seon.ns{:steward [:seon.cluster.agent/id]}]}]) [:seon.cluster.agent/id "juniper"])
```

The direct database read returned Juniper, namespace `my.agents.juniper`,
and Juniper as that namespace's steward. The evaluated page response bytes
have not yet been captured.

The populated plan source authored for section 13 is:

```clojure
; Your plan. (dir my.plan) is its API; (doc my.plan/complete!) explains one form.
(my.plan/current)
(my.plan/ready)
(my.plan/blocked)
```

The empty plan source authored for section 13 is:

```clojure
; You have no plan yet.
(dir my.plan)
(doc my.plan/add!)
```

These plan sources are authored bytes, not a completed live proof.

## Evidence and limits

- `bin/test --platform`: 73 tests, 398 assertions, zero failures and errors.
  This was an intermediate snapshot, before the later plan changes.
- The subject invocation started but has no completed tally. Bare `bin/test`
  and the final focused gates remain outstanding.
- The identity regression uses the canonical database fixture and checks a
  distinct namespace steward without an agent cluster attribute.
- Juniper was initially absent from a read; later present. The prescribed
  fixture call refused with `Conflicting upsert: "step-render-plan" resolves
  both to 34744 and 34813`. Existing fixture facts were preserved.
- Repeated development adoption attempts refused concurrent source changes.
  A later refusal specifically named
  `my.agents.turn-cut-a-eb5b8a72-24a8-41a4-ac8b-be90edc02d5c/shared-inc`
  at `seon.sci.eval/install-row!`, invalid input. Its diagnostic listed
  required program-row keys for namespace and schema branches. This is not
  evidence that the function lacks a contract.
- A subsequent MCP call with `(+ 1 1)` returned missing effective config
  `[:seon.config.blob/max-bytes]`. The owner then explicitly instructed this
  lane to continue independent work under sections 15 and the history ruling.

## Integration boundaries still outstanding

- `dir` and `doc` are installed by the protected
  `src/seon/sci/eval.clj` functions `program-documentation`, `program-doc-var`,
  `program-dir-var`, and `install-program-doc!`. They still print prose and
  return their old values. The requested data-only injected macros need a
  turn-cut hunk at that seam.
- The existing history owner's `agent-config` still reads the agent's
  cluster attribute. Its replacement must derive the cluster from the same
  database value, and the old history assembly must be deleted under the
  newest ruling.
- Stored shown text, the live result-object carrier, evaluation inspection,
  the history namespace replacement, read-only page previews, and all final
  acceptance proofs remain unfinished.

## Dependency ledger

Datahike pull selectors and read dependencies:
`reference-code/datahike/src/datahike/pull_api.cljc`; first-party idioms are
`seon.render.ns/namespace-row` and `seon.db/pull`. Schema-declared pairs use
`seon.render/schema-producers` and `seon.render/render-call`. The preview
boundary is `seon.render.web/render-source-call`, calling the protected
`seon.cluster.loop/preview-sources`; the evaluation pair belongs to
`seon.repl`. Plan ownership and mutations use the existing `my.plan/rules`
and `transact-plan!` writer path.
