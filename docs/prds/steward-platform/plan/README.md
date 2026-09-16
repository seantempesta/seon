---
type: plan
status: active (the roadmap; the owner marks it up; unsettled.md is the working edge)
created: 2026-09-16
tags: [plan, steward, roadmap]
---

# Self-building Seon — the index of real tasks

Owner (2026-09-16 01:05Z): "no fake tasks … index all the real tasks we need
to achieve this level of self building and repair." This page is that index.
It is the ONE ordered list for the steward platform; `unsettled.md` records
what moved and when. Every row names the mechanism it builds on (file), what
is genuinely missing, the proof that closes it, and its dependencies. Status:
**landed** (commit), **in flight** (lane), **planned**, **decision**.

## The target loop, in one paragraph

A task is a row: instructions, namespace, subject, a set of deftests that
define done, a budget. Starting it creates a worker agent entity with its
first turn open, in the same transaction. The worker iterates until every
test in the set is verified on the current reach digest; it may add tests,
never remove them. Batches run on forked clusters, one per namespace; a
finished batch merges its changed program rows into the shared branch, the
merge gate runs exactly the tests whose reach changed, and approved rows are
written back to their files by exact span. Signals derived from facts open
the tasks: red tests, untested and uncontracted functions, recurring faults,
missing render pairs, ugly output, lint findings, indexed issues, and a
user's unanswered messages. A namespace's steward is the engineer
responsible for all of it. Triggers and scheduling plug in as callers of
`start!` later (owner ruling 2026-09-16).

## A. Task definition and instance

| # | Task | Builds on | Missing | Proof | Depends on | Status |
|---|---|---|---|---|---|---|
| A1 | `my.task` schema: id, title, instructions, namespace, subject, tests `[:set {:min 1} ref]`, budget, agent, from; entity map with units and pair | `resources/seon/schemas/*` population; `:seon.render/units` on `seon.agent.edn`; walk `declared-concerns` (`src/seon/render/walk.clj:88`) | the resource, the writer's empty-set refusal | fixture: transact the two rows in [task-prototype](task-prototype-2026-09-16.md) §2; pull and render | — | planned (slice 1) |
| A2 | `my.task/start!`: creation-tx + agent ref + budget overlay + plan step + `generated-run-tx`, one transaction function | `seon.cluster/ensure-entity!` (`src/seon/cluster.clj:2259`), `bootstrap/seed-tx`, `turn/generated-run-tx` (`src/seon/turn.clj:728`) | the generalisation; the bootstrap's hard-coded task becomes a row | live: `start!` on default → armed worker, opening rendered with the task block | A1 | planned (slice 1) |
| A3 | `my.task/status` and the AI/HTML pair: instructions, subject, each test red/green/unrun, exact completing calls | plan/message pairs as the pattern (`src/seon/plan.clj:1229`, `src/seon/cluster/message.clj:379`) | the pair, the `:seon.test` entity pair (audit A: absent) | the opening bytes in §4 of the prototype, recorded | A1 | planned (slice 1) |
| A4 | Open test set: workers add test refs, retraction refused except by the creator; minimum one | `[:db.fn/call …]` writer decision (datahike skill) | the writer | regression: add succeeds, worker retract refused, creator retract allowed, empty refused | A1 | planned (slice 1) |
| A5 | Settlement runs the task's tests in-process before `plan/settle-call`; done-query = every test verified on the current reach digest, digest supplied as a query input | `seon.plan/settle-call` (`src/seon/plan.clj:562`), `seon.test/run`, turn settlement seams (`src/seon/turn.clj:2192`, `:3412`, `:3451`, `:4623`) | the call and the third query input | live: the worker never calls complete; the step closes on the run row | A1, B1 | planned (slice 2) |
| A6 | `my.task/copy!`: new row from a template row with a new subject; id via `seon.id/id`; `:my.task/from` | `seon.id` | the function | regression | A1 | planned (slice 4) |
| A7 | Interim done-query for slice 1: latest result green with run basis after item creation | audit A `green-after?` lower bound | nothing new; replaced by A5 | — | A1 | planned (slice 1) |

## B. Test evidence: know what is tested and what must re-run

| # | Task | Builds on | Missing | Proof | Depends on | Status |
|---|---|---|---|---|---|---|
| B1 | Per-test reach digest recorded on every result; `verified?` (db, test) and `stale`; `check` selects by staleness; in-JVM runs as ordinary REPL commands | reach rules (`src/seon/fn.clj:790`), `record-tx` (`src/seon/test/runner.clj:1420`), `seon.test/run` | the digest, the arity, the selector | regressions named in the lane spec; live: second `check` with no edit executes zero tests | call-edge completeness (B3) | in flight: lane `reach-digest`, beat 1 probes |
| B2 | Full run once per HEAD by the gate-owning session; later gates derive outstanding coverage and print existing reds | [suite-efficiency plan](../../context-generation/research/suite-efficiency-plan-2026-09-15.md) (`d64e304e8`) | admission-before-execution in `bin/test`; recorded reds surfaced per lane | a bare gate after a recorded full run executes only stale tests | B1 | planned; plan reviewed |
| B3 | Call-edge completeness for agent-admitted definitions (Juniper's test lacked its edge) | n7 landing (`src/seon/fn.clj` runtime analysis returns core + `my.*` edges) | the regression on the Juniper shape inside B1 | B1's fourth regression | — | in flight within B1 |
| B4 | Fixture-observation declarations published as program-row facts; the post-adoption check runs cheap reaching tests first and defers declared observations with their exact command | hook lane member 2, option 1 | the row facts, the deferral | hook feedback after an edit reaching the docstring-examples test arrives within the bound | — | in flight: lane `hook-publication-race` (resumed) |
| B5 | In-process runner keeps the drift guarantee: a test mutating worker-global state is detected | runner drift detector | the same check on the in-process path | regression with the two batch-12b mutating tests | B1 | planned |
| B6 | Program-digest provenance discrepancy on run rows | `runner/program-digest` (`:1358`) | one stable derivation | [issue](../../../seon/issues/recomputed-program-digest-disagrees-with-the-stored-run-digest.md) | — | open, friction after B1 |

## C. Signals as facts: how a namespace knows it has problems

| # | Signal | Facts today | Missing | Task it opens (required tests) | Status |
|---|---|---|---|---|---|
| C1 | red test | `:seon.test/fail-count`, `error-count`, runs | none | fix `T` (T itself) | derivable now |
| C2 | public function without a reaching test | `seon.fn/functions-without-tests` (288) | none | test `F` (a reaching test the worker writes, then required) | derivable now |
| C3 | incomplete contract (`:any`, `:some`, bare value, unguarded variadic) | `:seon.fn/spec`, schema-audit checker | expose the checker as a query function (`seon.fn/contract-findings`, audit A chain 4) | contract `F` (contract-complete predicate as a test) | planned |
| C4 | recurring fault in my functions | `seon.error` rows, `:seon.instrument/fn` → ns → steward (`src/seon/error.clj:1095`) | none for detection; `:seon.test/error-signatures` for the regression link (audit A chain 2) | regression for signature `S` | planned |
| C5 | unresolved or missing call | `:seon.fn/calls` to identity stubs | `:seon.fn/unresolved-calls` at the analyzer seam (audit A chain 5) | resolve `F`'s calls (caller's reaching tests) | planned |
| C6 | entity schema without a render pair | schema rows, `:seon.render/ai` props | none | declare the pair (a render test on the fixture) | derivable now |
| C7 | ugly or elided output | `:seon.eval/shown` text; elision maps in memory | `:seon.eval/elisions` observation facts (audit B §4) | bound the render (render bound test) | planned |
| C8 | lint findings | kondo in the hook; `:seon.fn.file/findings` as a value | store findings per row at index time | fix the finding (reaching tests green + finding gone) | planned |
| C9 | issues | 236 markdown notes, `bin/issues-index` | index frontmatter, namespace tags, status as facts at publication | the issue's own regression | planned (small side lane) |
| C10 | duplicate behaviour, parallel code paths | nothing stored | candidate derivation: same output refs and overlapping calls, same keyword footprint; plus the recorded human judgments from `docs/seon/issues/` n11 class | dissolve `F` into `G` (both sets of reaching tests green, one identity retired) | decision: start from recorded judgments |
| C11 | cross-namespace red-test attribution and steward alerts | [attribution plan](../research/test-attribution-plan-2026-09-15.md) option 1 | `:seon.test/stewards` on the result/adoption transaction | both stewards woken with T, F, R | decision (owner): later, as a caller of `start!` |
| C12 | a user's unanswered messages | `seon.message` from/to/about/inbox | `seon.cluster.message/unanswered` and the relative predicate (D1) | reply (D1's test) | planned (slice 3) |

## D. The chatting task and the steward as engineer

| # | Task | Builds on | Missing | Proof | Depends on | Status |
|---|---|---|---|---|---|---|
| D1 | Chatting task: one persistent entity, repeated sessions; test = no inbound message from the user newer than my newest reply to them | inbox wake (exists), message facts | the predicate as a contracted function and its deftest; test custody when settlement runs a test reading cluster facts | live: two user messages across two sessions on one entity; the test flips red and green each time | A1–A5 | planned (slice 3) |
| D2 | The steward starts workers from a conversation: `start!` called by an agent | A2, `my.*` protocol | `my.task/start!` as an agent-callable function with call preparation | live: root spins up a worker on a request from the user | A2 | planned (slice 3) |
| D3 | Steward standing task per namespace: required tests derived from C1–C9 for that namespace; the namespace picture pair for `:seon.ns` renders them | `:seon.ns/steward`, `seon.render.ns` pair (`src/seon/render/ns.clj:846`) | the derivation function; the picture's extension | live on one messy namespace: the steward's opening lists its real problems as tests | A, C | planned (slice 4) |
| D4 | Budget as settings overlay at start; `turns-left` unchanged | `max-episode-runs` overlay | nothing beyond A2 | — | A2 | planned (slice 1) |

## E. Batches, isolation, merge

| # | Task | Builds on | Missing | Proof | Depends on | Status |
|---|---|---|---|---|---|---|
| E1 | One forked cluster per namespace batch; workers admit definitions there only | `bin/seon init NAME` forks the published commit; cluster = branch + agents | operator support for N batch clusters from one command; cleanup of finished ones | two clusters running two namespaces' workers concurrently | A, D3 | planned (slice 5) |
| E2 | Changed program rows since the fork basis, as a pure projection | `seon.db/since`, `seon.program` row identities and exact source/spec | the projection | regression: edit three rows on a fork, projection returns exactly those | — | planned (slice 5) |
| E3 | Merge writer into the shared branch: exact replacement through `seon.program`; a row changed on both sides since the fork basis refuses (no three-way merge in v1) | `seon.program/exact-replacement-tx` (`src/seon/program.cljc:840`), `:db.fn/call` | the writer and its conflict rule | regression: clean merge lands; conflicting identity refuses with both sources named | E2 | decision (owner): refusal rule acceptable? |
| E4 | Merge gate: run exactly the tests whose reach digest changed on the shared branch | B1 | the invocation at merge | after a merge the gate executes only the affected tests | B1, E3 | planned (slice 5) |
| E5 | Re-fork on refusal: the task re-forks from the new shared head and re-runs to green | E1, A5 | the operator step | live: a refused merge is re-forked and lands | E3 | planned |

## F. To disk: the real code base

| # | Task | Builds on | Missing | Proof | Depends on | Status |
|---|---|---|---|---|---|---|
| F1 | File and form-span provenance on every indexed program row | `seon.fn/build-artifact` knows path, rows, identities at index time (`src/seon/fn.clj:1182`); audit A: not durable on rows | `:seon.fn/file` ref and span attributes written at index and adoption | pull any function → its file and span; regression on the fixture | — | planned (slice 6) |
| F2 | Exact write-back: replace the old form's bytes by the new source inside the owning file; append new definitions to the namespace's file; new namespaces as new files (`seon.program/source-files`, audits A/B) | `my.fs/write!` conditional writes (`src/my/fs.clj:59`), exact stored source | the pure assembly and the effect | the ordinary index run over the written files reproduces the merged rows byte for byte | F1, E3 | planned (slice 6) |
| F3 | Round-trip proof and commit: index the written files, gate the reaching tests, path-limited git commit by the operator | `bin/seon init`, B1 | the operator step | a worker-authored change lands in the repository with its tests | F2, E4 | planned (slice 6) |

## G. Platform defects in the way

| # | Defect | Status |
|---|---|---|
| G1 | First debug page after idle costs 2 s (35× warm); root page acquisitions erased by read-only MCP returns | in flight: lane `cold-page-kills` on the [approved plan](../../context-generation/research/cold-page-plan-2026-09-15.md) |
| G2 | Hook false refusal "requires a running operator JVM" after a converged batch | landed `c0006a125` |
| G3 | Post-adoption check times out on expensive tests | in flight: B4 |
| G4 | A value larger than the budget is elided to nothing | [issue](../../../seon/issues/a-value-larger-than-the-budget-is-elided-to-nothing.md); next n1 slice |
| G5 | Two orchestrator sessions writing one gate ledger | decision (owner): which session owns gates |
| G6 | Codex usage limit noted in the gate ledger; lanes launched tonight were accepted | watch |

## H. The live proof ladder (each slice ends in a live run the owner reads)

1. **Slice 1** — A1, A2, A3, A4, A7, D4: a real code task on `my.agents.root` (subject chosen by the owner, no fake tasks: candidates are C2's 288 untested functions, starting with `my.agent/done`, `my.background/await`, `my.edit/exact!`), live on DeepSeek; deliverables: opening bytes, ledger line, explain probe.
2. **Slice 2** — A5, B1 beat 2, B5: completion derived at settlement; the worker never calls complete.
3. **Slice 3** — D1, D2, C12: the chatting task across two sessions on one entity; root spins up a worker from the conversation.
4. **Slice 4** — D3, C3, C4, C9, A6: the steward's standing task on one messy namespace chosen by the owner; issues indexed as facts.
5. **Slice 5** — E1–E5: two namespaces in two forked clusters, merged, gated.
6. **Slice 6** — F1–F3: the first worker-authored change written to disk and committed.

## Decisions pending (owner)

1. The first real subject for slice 1 (an untested public function in `my.*`, or another).
2. Workers add tests, never remove them (A4).
3. Merge refusal rule for v1: same identity changed on both sides refuses, re-fork (E3).
4. The first messy namespace for slice 4.
5. Which session owns gates (G5).
