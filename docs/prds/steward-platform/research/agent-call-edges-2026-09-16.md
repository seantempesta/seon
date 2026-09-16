---
type: research
status: active
tags: [research, test, database, class/n7]
---

# Agent declaration call edges — 2026-09-16

## Scope and current evidence

This lane owns the fresh declaration-call residual of N7, not the other
classification owners. The class-mining rule is **record the missing fact,
then query it**. The required guarantee is that analyzer-observed declaration
calls survive admission order, so changing a reached function changes the
test's reach digest.

Read end to end: AGENTS.md, issues README and localized instructions, the N7
class and its three current open members, the agent-call issue, reach-digest
landing, N7 evaluation-edge landing, and data audit A. Read the class-mining
N7 row and structural-kill column, roadmap entry, and turn PRD §§13–15.
Applied data-oriented-clojure, repl, datahike, data-modeling,
clojure-testing, and seon-flow-architecture skills.

The other current N7 members remain outside this bounded assignment:
[config discovery](../../../seon/issues/config-dial-discovery-has-three-authorities.md)
and [namespace projection](../../../seon/issues/cluster-toolkit-stores-a-prefix-derived-projection.md).
No class-wide closure is claimed.

## Dependency ledger and verified cause

- `reference-code/clj-kondo/analysis/README.md:119`: var usages report
  from/from-var/to/name and call arity. The dependency's
  `src/clj_kondo/impl/analysis.clj:19` emits these facts; its linters resolve
  usages against the analyzed namespace context.
- `src/seon/fn/analyzer.clj:318`: admission lint already constructs a
  program prelude from supplied declarations. Runtime graph analysis must
  use that same mechanism with the submitted namespaces' actual program rows.
- `src/seon/fn.clj:518`: runtime batch previously included namespace forms
  and submitted source, but no earlier admitted declarations. Its target
  filter consequently discarded unknown-namespace usages.
- `src/seon/turn.clj:1189`: the declaration writer already resolves pending
  subjects. Calls need the same writer-time resolution, including when a
  target arrives later in the same batch.
- `src/seon/program.cljc:40`: declaration ownership controls replacement;
  pending call evidence must belong to both function and test declarations.
- `src/seon/test.clj`: existing reach-digest is reused unchanged.

On default PID **7595**, a raw kondo probe of a test calling an unqualified
`observed` reported `:to :clj-kondo/unknown-namespace`, `:from
my.agents.edge-probe`, `:from-var observed-test`, `:name observed`, `:arity 1`.
Adding the function definition to the analyzed input resolved the exact usage
to `my.agents.edge-probe`, both before and after the test source. Repeated
namespace forms preserved that resolution. Thus missing namespace context is
verified; a missing `ns` form is falsified. Target filtering is a second loss
point, not a substitute explanation for the unknown namespace.

## In-process evidence

All runs use MCP JVM mode and `(seon.operator/connection "default")`, with
`seon.test/run` and a 120000 ms declared run option. No test JVM was launched.

| Run | Regression | Pass / fail / error | Evidence |
|---|---|---|---|
| 61942, basis 536871445 | `seon.test-reaching-test/agent-admitted-tests-reach-their-tested-function` | 5 / 2 / 0 | HEAD reproduces both missing target and unchanged digest. |
| 61958, basis 536871460 | same | 7 / 0 / 0 | Candidate analyzer, evaluated before its source edit. |
| 62215, basis 536871531 | `seon.agent-call-edges-test/admitted-declarations-retain-call-dependencies-across-turn-order` | 0 / 0 / 1 | Virtual turn did not close within the fixture event bound; not an edge assertion result. |
| 65249, basis 536871549 | same | 0 / 0 / 1 | Fixture had not armed its agent; corrected the fixture before assessing call edges. |
| 65267, basis 536871569 | same | 5 / 2 / 0 | Earlier-call and digest checks pass; pending-call resolution fails with original writer loaded. |
| 66097, basis 536871609 | same | 2 / 5 / 0 | Candidate run during reload; empty call sets. This is not a passing proof. |
| 66113, basis 536871623 | same | **7 / 0 / 0** | Final source definitions, complete canonical fixture, real SCI virtual turns. |

The baseline's function change left digest
`8aae56c01668c6853a09877504aba1739ec48c1aa18eb25b0372984ec5c04b2a`
unchanged. The candidate's representative default-database call returned the
real `seon.db/q` and `clojure.test/is` refs in **141 ms**.

The pending-call schema was acquired through the complete canonical
`seon.test-support/create-base` mechanism, not an extra-schema roster.
The fresh base contains the declared schema and installed attribute entity
**384**. Its use is scoped around in-process tests; default is not reforked.

The new regression exercises seven virtual turns: function, test, function
source replacement, forward declaration, test-first declaration, later
function, and a lexically shadowed call. Its source is
`test/seon/agent_call_edges_test.clj`; the helper returns the actual call sets
and both reach digests. The negative shadowing assertion prevents a source
name from becoming a fabricated edge.

The analyzer candidate and the writer candidate were evaluated as Clojure
forms through MCP before their production edits. The existing analyzer
regression passed before editing the analyzer files. The writer candidate was
exercised through the new in-process regression before editing `turn.clj`;
subsequent readback found the old relation vector reloaded into the JVM.
Persisting the candidate and rerunning produced run 66113 above. This is
hot-loaded-definition evidence, not a claim that development adoption finished.

Exact single-test invocation (run under a scoped binding of the canonical
`database-base` to the fresh complete base described above):

```clojure
(let [c (seon.operator/connection "default")
      d (seon.db/db c)]
  (seon.test/run
   #'seon.agent-call-edges-test/admitted-declarations-retain-call-dependencies-across-turn-order
   c {:seon.db/db d
      :seon.test.run/provenance (seon.test.runner/provenance d)
      :seon.test/remaining-ms 120000}))
```

## The structural change

**Every analyzer-observed declaration call is either a resolved program ref
or an explicit pending target that the declaration writer resolves when its
function identity arrives.** Existing program rows supply kondo's namespace
context through its existing admission-lint prelude. Unknown unqualified
usages retain kondo's calling namespace; no test-name convention or declared
subject substitutes for analysis. Dependency grounding for that namespace
rule: `reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:3700` also uses
the calling namespace for the unknown-namespace case.

The existing pending-subject writer now resolves both relation families.
Writer-time existence checks cover earlier rows in the same transaction.
Pending evidence belongs to the declaration's existing exact-replacement
ownership, so replacing a body also replaces its pending calls. The consumer
and reach-digest code are unchanged.

Owned production paths: `src/seon/fn.clj`, `src/seon/fn/analyzer.clj`, the
declaration-relation region of `src/seon/turn.clj`, `src/seon/program.cljc`,
`resources/seon/schemas/seon.fn.edn`, and
`resources/seon/schemas/seon.test.edn`. The small schema and ownership changes
are necessary to retain the analyzer fact through the existing writer.
`turn.clj` had no foreign diff before editing; its resulting diff contains only
this lane's relation changes. No prohibited test-consumer or source-publication
owner was edited.

## Operational boundaries

Runtime status fails `seon.problems/problems`' occurrence-count return
contract; tracked by
[the existing status issue](../../../seon/issues/runtime-status-refuses-error-occurrence-count.md).
Named MCP sessions reported a restart while `bin/seon status` still showed
PID 7595 and the same generation; tracked by
[the existing session issue](../../../seon/issues/mcp-session-loss-claims-unobserved-restart.md).
Raw analyzer-map projection timed out at 30000 and 20000 ms. Returning its
complete `pr-str` value succeeded in 27 ms. These observations do not prove a
restart or an analyzer hang.

Hook publication `0925d5a4-05ec-4b58-9c66-a48641e32dec` reported operator
exit **124** and `Publication did not finish within its declared bound.`
No completed adoption is claimed at this checkpoint. Concurrent files,
including other turn-test edits, are preserved. No default lifecycle command
or foreign lane operation was performed.
