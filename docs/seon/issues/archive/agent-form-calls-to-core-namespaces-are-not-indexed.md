---
type: issue
status: superseded
severity: friction
tags: [issue, agent, sci, class/n7, wave/program-graph-indexing]
---

# Historical call-edge analyses need re-evaluation

## Closing verdict — 2026-09-16

Implementation residual resolved by `76774d044`: runtime declaration analysis
uses the existing program prelude, and the declaration writer retains and
resolves pending calls. The canonical real-SCI virtual-turn regression passed
**7/0/0**, run **66113**, basis **536871623**, in default JVM **7595**.
It covers function-first and test-first admission, function-to-function calls,
changed reach digest, and lexical shadowing.

Default's scratch test entity **67496** now records its call to
`my.agents.agent-call-edges-live-proof/target`. The function source change
from `(inc x)` to `(+ x 1)` changed its digest from
`822981feab880303787d1f30f196b7645a4c602945f86ad1bdd6a1db432fa13e` to
`b8b159b429b35a944b64bdff84ce9eaf6c6c322e3db88e5e8558fb37a895fc99`.
Both virtual turns closed; no edge was inserted manually.

This note is **superseded**, because historical reanalysis and regrading were
never performed by the constructor fixes. That residual is now named in
[historical call-edge analysis](../historical-call-edge-analyses-need-rederivation.md).
The batched gate and complete adoption boundary remain explicit in
[the landing](../../../prds/steward-platform/research/agent-call-edges-2026-09-16.md).

## Current verdict — 2026-09-15

New ordinary evaluation persistence is **resolved** by `f402c5d3d` and
`924fdbf3a`. Default's real SCI probe persisted both `seon.db/q` and
`my.turn/wait` edges through direct settlement and the ordinary turn fold.
After development adoption, the canonical in-process persistence and analysis
regressions passed **24 assertions, zero failures/errors** (run entities 67084
and 67086). `tests-reaching` found the installed test over its function and
through transitive `my.turn/wait` reach. Exact commands and complete values:
[the residual landing](../../../prds/context-generation/research/n7-eval-call-edges-2026-09-15.md).

**Remaining scope:** the original acceptance item to re-derive historical
ablation conclusions made from missing call edges. This change does not
backfill stored evaluations or re-run the archived ablation. The orchestrator's
batched namespace/platform gate is also pending; no new test JVM was launched.
The runtime namespace-dependent omission is no longer an open implementation
item. The dated sections below preserve the original root-cause evidence.

## Problem

A run form's `:seon.fn/calls` edges are recorded for functions in the agent's
own namespace and for `my.*` toolkit functions, but NOT for a fully qualified
core function the form plainly calls. `(seon.db/q '[...])` inside an agent form
produces no edge to the `seon.db/q` entity, even though that entity exists in
the same program graph.

Every "which forms used this function?" question is therefore silently wrong for
core functions — the exact class the "everything is queryable" principle exists
to prevent. It is not a missing fact (the entity is there); it is a missing EDGE,
which is worse, because the query returns a confident empty answer.

## Evidence

Measured on the minimum-context ablation FULL drive root
`tmp/ablation/drive-roots/full-02/clusters`, branch
`:cluster-minimum-context-full` (probe committed at
`tmp/ablation/grade_probe.clj` and `tmp/ablation/grade_probe2.clj`):

```text
seon.db/q entity: 12765
forms calling seon.db/q: nil
run forms with any call edge: 7
```

The agent's own form, ordinal 2 of run `b488ab0b-…`, is

```clojure
(let [c (cluster-agent-count)
      contract (seon.db/q '[:find ?spec . :in $ ?sym
                            :where [?f :seon.fn/sym ?sym]
                                   [?f :seon.fn/spec ?spec]]
                          "my.agents.w1-history-proof-5/cluster-agent-count")]
  (my.run/complete (pr-str {...})))
```

and its recorded facts are

```clojure
#:seon.fn{:keywords [:seon.fn/spec :seon.fn/sym]
          :calls [#:seon.fn{:sym my.run/complete}
                  #:seon.fn{:sym my.agents.w1-history-proof-5/cluster-agent-count}]}
```

`my.run/complete` and the agent's own function are edged; `seon.db/q` is not.
The same gap appears on the shipped bootstrap forms, which evaluate
`(seon.db/q '[:find (count ?f) . :where [?f :seon.fn/sym _]])` at ordinals 5, 6
and 12 and also carry no `seon.db/q` edge.

## Consequence observed

The minimum-context ablation's third success criterion is
"another clean receipt belongs to a form calling `seon.db/q` and carrying
literal keyword `:seon.fn/spec`"
([plan](../../../prds/sci-execution-runtime/research/minimum-context-ablation-plan-2026-08-11.md)).
Because the edge never exists, that criterion is UNSATISFIABLE, and all four
variants were graded `:minimum-context.grade/success? false` regardless of what
the agent actually did — FULL and HALF both completed the work correctly.

## Acceptance

- A run form that calls a resolvable program-graph function records a
  `:seon.fn/calls` edge to it, whatever namespace it lives in.
- One regression proves the class dead: evaluate a form calling a core function
  and a `my.*` function in one agent run and assert both edges, so a
  namespace-dependent edge gap cannot reappear.
- Re-derive any conclusion drawn from a `:seon.fn/calls` query over run forms
  since this gap existed.

## Owner

Program-graph indexing owner (`src/seon/fn.clj` reply/form analysis path).

## Re-verified at HEAD (2026-09-15)

OPEN, UNVERIFIABLE. Audited HEAD `7e35df213` still has `seon.fn/analyze-form` (`src/seon/fn.clj:642`) and relation persistence (`src/seon/turn.clj:1170-1178`); the removed form family does not establish removal of the edge defect. Exact live probe: `(let [db @(seon.operator/connection "default")] (seon.fn/analyze-form db "(seon.db/q '[:find ?e :where [?e :seon.agent/id]])" [:seon.ns/name "user"] nil))`. Observed: contract refusal in `analyze-forms` at `[0 :seon.program/row]`, expected map, got nil; the analysis never ran. Omitting the batch key and supplying `{}` also refused before analysis. This live contract boundary is not attributed to the historical edge omission or to a concurrent editor. Needed: a canonical armed evaluation with a valid declared program-row input, then inspect persisted edges for core and my.* calls. Downgraded to friction: the note demonstrates incorrect analysis/grading, not blocked execution or prompt generation at HEAD.

surface: other

Owner correction: UNVERIFIABLE-WITHOUT-GATE (seon.fn-test, seon.cluster.turn-test). No new JVM may be launched; the required canonical regression remains pending.

## N7 narrowed verification — 2026-09-15

Still open for **persisted edges on ordinary non-defining evaluation forms**.
The corrected read-only default JVM probe uses a valid program declaration row
and a symbol-valued namespace identity. `seon.fn/analyze-form` returned both
`[:seon.fn/sym "seon.db/q"]` and `[:seon.fn/sym "my.turn/wait"]` in the
definition row in 1,156 ms. That refutes a blanket namespace-based declaration
analysis defect; it does not prove ordinary evaluation persistence.

Current `analyzed-form` returns `[{} merged-row]`; nil program rows refuse in
`analyze-forms`. The remaining proof must run an ordinary non-defining form
through the real turn harness and inspect its persisted call edges. No nil-row
contract was weakened, and no duplicate edge family was added. Exact successful
probe is in the N7 landing note.

N7 implementation commit `5deb40e4e` changes namespace relevance indexing,
not this unproven evaluation edge. The class remains open at this boundary;
the green owner/platform gates do not prove ordinary evaluation persistence.
See [the landing note](../../../prds/context-generation/research/n7-query-classification-2026-09-15.md).

## N7 evaluation-edge slice — 2026-09-15

Commit `f402c5d3d` repairs the shared analyzer's ordinary-form contract and
projection. A missing declaration row is now an absent optional batch member;
the nullable positional API no longer inserts a forbidden nil. Ordinary forms
return their resolved `:seon.fn/calls` refs in the existing first tuple member.
Declaration edges remain solely on the declaration row.

Default's armed JVM probe returned both `seon.db/q` and `my.turn/wait` refs
in 18 ms with its ordinary database value. A mixed batch also preserved local
shadowing and source spans. On a canonical isolated fixture, persisting its
analyzed function and test rows made `tests-reaching` return the test and
include it in transitive `my.turn/wait` reach.

**Still open:** protected `src/seon/turn.clj` selects only declarations for
analysis, so ordinary evaluation persistence is not repaired by this commit.
The [residual landing](../../../prds/context-generation/research/n7-eval-call-edges-2026-09-15.md)
contains the exact proposed recorder diff and the full virtual-turn acceptance
proof still required. No second parser, edge family, or synthetic callable
identity was introduced.

The existing declaration regression passed 5 assertions in default. The new
canonical regression encounters an independently verified old contract in the
cached fixture; its result is 1 pass, 0 failures, 1 error before the ordinary-form
assertions. The fixture issue is linked from the landing. The isolated gate
exited 75 before launching tests under the orchestrator-only policy; the
namespace and platform gates are queued, not claimed green.

## Reach-digest historical-test probe — 2026-09-16

Default at basis **536872536**, source commit
`6aa9dfe0-ab4c-58fa-892d-7bab168f2c65`, still holds test entity 42768,
`my.agents.juniper/largest-customer-test`, with exactly three call refs:
`clojure.test/is`, `clojure.core/let`, `clojure.core/=`. Its stored source
calls `(largest-customer rows)`, but the tested function is absent from its
transitive closure; there is no subject or pending-subject fact.

MCP JVM read-only probes used
`(seon.db/db (seon.operator/connection "default"))`. The test's current
source/contract/schema closure digest equals its reconstructed digest at
recorded run basis **536871342**:
`3b0fdce40c7fd0aa752f1eff4363421b4202793dea68208bca2b4657549b19a5`.
Thus digest equality does not repair incomplete historical analysis. An
empty-or-single-edge census also misses this exact case: it has three edges.

This adds a historical declaration-test verification boundary; it does not
refute the newer ordinary-evaluation persistence proof above. No fresh
equivalent test was admitted and no edge was inserted by this probe. Before
trusting reach-based reuse, verify the n7 repaired analyzer on a freshly
admitted Juniper-shaped test, and separately decide how historical test rows
are reanalyzed. Exact source, measurements, and reproducible probe:
[reach-digest beat 1](../../../prds/steward-platform/research/reach-digest-2026-09-16.md).

## Program-provenance baseline — 2026-09-16

A second read-only MCP JVM pull confirmed the historical Juniper test still
has only `clojure.core/=`, `clojure.core/let`, and `clojure.test/is` refs; its
stored source calls `largest-customer` without an edge. The owner's subsequent
scope correction withdrew inferred subjects, so this lane neither backfills
that edge nor masks it with a subject. The direct-call namespace query returned
22 tests for `seon.plan`, 114 for `seon.turn`, and 35 for
`seon.cluster.message`. Exact query and scope:
[program provenance](../../../prds/steward-platform/research/program-provenance-2026-09-16.md).

## Fresh declaration-test analysis probe — 2026-09-16

The reach-digest lane verified a fresh equivalent through real SCI evaluation,
`seon.fn/analyze-forms`, and persistence of its analyzed program row in the
canonical database fixture. The in-process regression
`seon.test-reaching-test/agent-admitted-tests-reach-their-tested-function`
recorded **5 passes, 2 failures, 0 errors**, run **45227**, on default JVM
**7595**, outer basis **536871187**. This is a fresh declaration-analysis
residual; it does not contradict the ordinary-evaluation settlement proof.

Exact admitted bytes, namespace `my.agents.reach-digest`:

```clojure
(defn largest-customer {:malli/schema [:=> [:cat [:vector {:min 1} [:map [:seon.test/pass-count :seon.test/pass-count]]]] [:map [:seon.test/pass-count :seon.test/pass-count]]]} [rows] (apply max-key :seon.test/pass-count rows))
(clojure.test/deftest largest-customer-test (clojure.test/is (= {:seon.test/pass-count 9} (largest-customer [{:seon.test/pass-count 2} {:seon.test/pass-count 9}]))))
```

The persisted test had exactly `clojure.core/=` and `clojure.test/is` call
refs, with no subject or pending subject. Its tested function, entity **40879**,
was absent from the closure. Appending a newline to that function's source
left the reach digest equal on both sides:
`8aae56c01668c6853a09877504aba1739ec48c1aa18eb25b0372984ec5c04b2a`.
The regression asserts the wanted inclusion and changed digest; neither
expectation was weakened and no synthetic edge was inserted.

The first exploratory version omitted `analyze-forms` and is **not evidence**
about production admission. The corrected regression above includes the
same analysis phase used by `src/seon/turn.clj` before settlement. The
historical Juniper row disappeared when default changed JVMs during this
lane; absence of that fixture is not evidence of a repaired edge.
