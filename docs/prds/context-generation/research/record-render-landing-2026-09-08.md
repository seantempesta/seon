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

## Stop boundary and final lane tally

The lane stopped under the owner's concurrent-breakage rule. The completed
focused invocation was `bin/test my.plan-test seon.render.ns-test` at snapshot
`628025af69ae1d60c19e480b00cd02c50507c75c`. It exited **1 before running any
subject tests**, during shared published-base preparation. The exception
contains `{:schema :char, :form :char}` at `seon.schema/build-projection`.
The snapshot's `src/seon/id.clj:45`, outside record-render ownership, declares
that schema in `seon.id/symbol-in`. The same minimal Malli probe on default
returned that rejection. No foreign files or sessions were edited or resumed.

The runner was reaped: launcher 31932, runner 33036, exit 1 at
2026-09-08T18:24:03Z. The retained evidence root is
`tmp/test-runs/run.9bJmLJ`; the focused log is
`tmp/record-render-subject-final.log`. The failure happened before a test
counter existed: **zero executed subject tests**, not a passing zero-test
suite. Bare `bin/test` and a final `bin/test --platform` were not attempted
after this stop condition. The earlier platform tally remains 73 tests,
398 assertions, zero failures/errors, and does not validate the final slice.

Commits:

- `57264aeb9` — identity entity pair and database-derived request profile.
- `080628130` — compact plan API and data-dependent teaching source, with
  updated canonical-fixture tests; verification blocked as above.

Implementation paths touched by these slices:

- `src/seon/render.clj` (request-profile only)
- `src/seon/render/ns.clj`
- `resources/seon/schemas/seon.cluster.agent.edn` (render properties only)
- `test/seon/render/ns_test.clj`
- `src/my/plan.clj`
- `resources/seon/schemas/my.plan.edn`
- `test/my/plan_test.clj`

No completed browser proof is claimed. Ten-load no-write and reply-byte
identity proofs were not rerun. The page still needs entity/component/derived
block assembly, scalar and Cluster-section removal, wake/fault pairs, and
read-only stored-history plus would-be-system-turn rendering. The custom
history namespace has not been replaced. `my.turn`, data-only `dir`/`doc`,
and compacting the older `item`/`items` read surfaces remain unfinished.
The old `:my.plan/ready-items` collection render declaration remains and
must be removed with its callers. Existing format helpers also remain.
The plan source test currently checks exact source bytes, despite its older
shared-reader name; it does not prove execution through SCI. This must be
corrected and supplemented with the real SCI proof before acceptance.


## Resumed for sections 17 and 16

The owner resumed this lane and replaced the concurrency gate with
`bin/test --paths` over only lane-owned files. The earlier stop is historical.
The plan arity change and all four callers were already committed in
`080628130`; the working tree had no residual plan arity diff when rechecked.

Section 17 component slice:

- `:seon.agent/plan` owns the objective, root steps, and current-step ref.
  `my.plan` ownership queries follow that edge, and the existing writer
  operations target its component. Juniper's fixture now has an objective
  and four root steps rather than an objective-shaped root step.
- `:seon.agent/settings` owns the overlay. `my.agent/settings` delegates to
  the existing `seon.ai/agent-overlay` reader, which now pulls that component.
  The existing schema derivation still supplies all per-agent dial keys and
  now declares the settings render pair. Evaluation and agent completion
  time limits are per-agent dials.
- Optional-only entity maps enter schema discovery by their declared
  attributes; an unrelated map does not match them merely because they have
  no required attributes. Both full and incremental projection indexes use
  the same admission rule. This was necessary for a sparse settings entity.
- Malli requires `[:and {:seon.db/component true} :seon.db/ref]` for these
  alias-backed refs; the literal vector-headed alias in section 17 refused.
  The component metadata and storage semantics are unchanged.

The first isolated attempt caught a misplaced attribute declaration and the
second caught that alias syntax. Both were fixed in this lane. The third
attempt (`tmp/record-render-components-test3.log`) passed shared-base
preparation and entered the runner; its tally is pending at this checkpoint.
Main-root development adoption reached program reconciliation; no browser
or component fixture proof is claimed at this checkpoint.

Additional implementation paths in this slice: `src/my/agent.clj`,
`src/seon/ai.clj` (overlay reader only), `src/seon/schema.clj` (shape discovery),
`src/seon/schema/edn.clj` (derived overlay pair),
`resources/seon/schemas/seon.agent.edn`, the config-agent and config-eval
schema resources, `test/my/agent_test.clj`, and `test/seon/ai_test.clj`.
The Juniper fixture and section 17's PRD integration paragraph changed too.
The section 16 page draft is still uncommitted and not included in this slice.


### Component follow-up, 2026-09-08

The component commit is `74b5b4b05`. Its first completed isolated plan gate
(`tmp/record-render-plan-gate.log`, root `run.WAmrg9`, HEAD `dbb0cda83`)
ran 19 tests / 68 assertions: 5 failures and 4 errors. These are owned
failures, not attributed to another lane. The earlier three-namespace gate
was terminated by TERM before a tally; it is not a green proof.

The report exposed reads using a not-yet-created component tempid: sibling
counting assigned the first step an erroneous position, and reconciliation
attempted a retract against that tempid. The follow-up only reads existing
component ids. The start writer tests membership in the owned step identity
set. The HTML fixture now carries a root identity and a fixed complete
profile. The plan HTML function asks the shared renderer for structural data
and propagates a typed error instead of placing an error map inside Hiccup.
The replacement gate is `tmp/record-render-components-test4.log` (plan and
settings namespaces); its result is pending at this checkpoint.

Default MCP answered arithmetic and an explicit value-renderer call returned
`:seon.render.value/missing-root-identity`, confirming the fixture omission.
A call supplying the root subsequently timed out at 3000 ms. Development
adoption again refused because source changed during adoption; no schema
refusal or successful convergence is claimed. No refork, reseed, browser
paint, or fresh/system/virtual/compact lifecycle proof has completed.


Live read evidence after `3f42669e0`: the previously timed-out write did
commit. `record-render-components/one` exists under the plan component with
position 4, reproducing the pre-fix tempid-count bug. No write was repeated
on the assumption that timeout meant absence. A settings component with
1234 ms evaluation time and 5678 ms completion backstop was then written.
Under its database projection, `my.agent/settings` returned exactly
`{:seon.config.eval/time-limit-ms 1234,
:seon.config.agent/turn-completion-backstop-ms 5678}` and the matching schema
was `:seon.config/agent-overlay`. The read-only reproduction is
`record_render_components_proof_2026_09_08.clj` in this directory.

The direct JVM settings call originally lacked a handed projection. The
follow-up derives it from the supplied database at the existing overlay
reader; callers no longer need a hidden projection binding for this read.
The value renderer also demonstrated its namespace-map spelling
(`#:my.plan{` and `:steps`), so the HTML assertions now check its actual
structural output instead of demanding one unsplit qualified-key string.
The test4 gate was deliberately terminated and reaped before assertions
because it snapshotted those known stale assertions. Test5 contains the
updated plan/settings/AI paths and selects all three subject namespaces.
