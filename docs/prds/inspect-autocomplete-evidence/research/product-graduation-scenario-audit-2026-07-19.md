---
type: research
status: complete
tags: [research, agent]
---

# Product graduation scenario audit

## Verdict

The current Inspect corpus already has reusable, database-derived evidence for
namespace movement, namespace-targeted messaging, database recall across
turns, durable planning across restart, and execution-child recovery. The
remaining work is not six new scenarios:

- migrate the existing planning and pod-restart drivers from the retired
  cluster functions to the implemented branch lease;
- compose the namespace milestone with the namespace-resident scenario when
  both movement and addressed delivery are acceptance facts; and
- add one public generate-code scenario after that public contract lands. The
  current reuse/repair scenario deliberately drives raw `defn`, qualified call,
  repair, `deftest`, and test-run forms, so it cannot prove the public
  two-namespace operation.

No evidence in this audit requires a new lifecycle mechanism, transcript
scorer, database reader, or benchmark-only product function.

## Dependency and source ledger

This audit is against:

- Seon `7af9767afe5ce064ae9cc7f9b7cd3c61119f7ba9`;
- maintained Inspect AI
  `05322696a0f784ec399ef6abbafd3d2a250ea9cc`;
- maintained Inspect Evals
  `97c99f5f6507fc5d1449fe3247f267d591f64350`;
- `src-inspect-ai/src/seon_inspect/milestone.py` and
  `tasks/milestone_lift.py` for namespace movement and database recall;
- `src-inspect-ai/src/seon_inspect/product_scenarios.py` and
  `tasks/product_scenarios.py` for namespace delivery, reuse/repair,
  execution-child recovery, and pod-restart evidence;
- `src-inspect-ai/src/seon_inspect/planning.py` and
  `tasks/long_term_planning.py` for durable planning;
- `src-inspect-ai/src/seon_inspect/cluster.py` for the current
  `acquire_branch_lease`, `restart_branch_lease`, and
  `release_branch_lease` boundary; and
- `src/seon/ai/generate_code.cljs` plus `src/my/plan.cljs` for the current
  generated-code scheduler and durable namespace-plan implementation.

## Coverage map

| Graduation behavior | Existing task and scorer | Current strength | Exact remaining boundary |
|---|---|---|---|
| Namespace movement | `milestone_lift(milestone="namespaces")`; `check_ns_movement` | Structured eval evidence covers schema namespace, function namespace, home use, dependency loading, and in-place redefinition without a parallel namespace or function. | Keep this scorer. Pair it with the live namespace scenario when addressed delivery is also required; do not merge their distinct evidence projections. |
| Database memory across turns | `milestone_lift(milestone="db")`; `check_store_recall` | A write is recalled by a later query and report in the same run. | Add restart only by enabling the existing pod-restart scenario through the branch lease. The milestone itself should remain the across-turn oracle. |
| Database memory across restart | `product_scenario("pod_restart")`; `check_pod_restart` | The frozen scorer already requires a changed pod, same database and agent, and an equal post-restart read. | The generic driver expects an owned restart operation, but the native live task currently rejects this scenario. Route it through `restart_branch_lease`; no product seam is missing. |
| Durable planning | `long_term_planning`; planning trajectory scorer | The scorer requires a pre-restart durable plan, post-restart completion of a pre-existing step, no replacement root plan, all original leaves complete, decompose-first behavior, close adjacency, and a final answer. | `pod_planning_driver` still calls retired `create_cluster`/restart/destroy functions. Migrate this driver to the existing branch lease; retain its task and scorer. |
| Namespace-targeted multi-agent messaging | `product_scenario("namespace")`; `check_namespace` | Live branch-lease path proves two root-addressed messages select one resident and its first eval runs in the target namespace. | If the graduation claim includes useful work rather than routing alone, extend the database projection with the two addressed terminal results. Do not score transcript narration. |
| Execution-child crash and recovery | `product_scenario("child_recovery")`; `check_child_recovery` | Live evidence joins the interrupted eval/turn, recovery entity, PID and digest, diagnostic blob, later successful replacement eval, completed sibling, and current parent host. | No product or harness seam is presently missing. Retain the warm replacement-host assertion within the configured reclamation window. |
| In-place two-namespace repair | `product_scenario("reuse_repair")`; `check_reuse_repair` | Datahike history proves peer authorship, qualified reuse, same-symbol repair, absence of a parallel function, and a fresh passing test. | This scenario bypasses public generate-code. It remains a lower-level reuse/repair oracle, not public API acceptance. |
| Public generate-code two-namespace repair | None | The product now has durable namespace-plan scheduling internals, but no Inspect task invokes and scores the settled public operation end to end. | After the public contract lands, add one thin task adapter that invokes it for implementation namespace A and test namespace B, then reuse the history and fresh-test evidence below. |

## Smallest reusable additions

### Branch-lease restart adapter

Use the existing branch lease for both restart-dependent tasks. Acquire once,
retain the agent and database identities, restart that lease, reacquire its
dynamic endpoint, and release it in `finally`. This replaces only retired
harness calls; it does not add an operator abstraction.

The pod-restart evidence should be the smallest restart-memory falsifier:

1. transact a uniquely generated identity and value through the ordinary
   agent surface;
2. retain the exact agent ID, database identity, pre-restart pod identity, and
   immutable database value coordinates;
3. restart the acquired branch lease;
4. address the same agent and query the unique identity; and
5. accept only a changed pod identity, unchanged database/agent identity, and
   equal recalled value from database-derived evidence.

The durable-planning driver should reuse the same adapter while leaving its
existing trajectory scorer unchanged.

### Namespace composition

Do not replace `check_ns_movement` with `check_namespace`. Run the generated
namespace-workflow row to prove program movement and in-place repair, then run
the namespace scenario to prove addressed resident reuse. If useful child
results are part of graduation, add their database message/eval facts to the
existing namespace snapshot; the current two-message/one-resident facts alone
prove routing, not task success.

### Public generate-code scenario

Wait for the settled public function schema and use it directly; do not reach
through `seon.ai.generate-code`'s `^:no-doc` scheduler functions. The smallest
scenario supplies one request that owns implementation namespace A and test
namespace B, including an initially failing behavior that requires an in-place
repair. Its scorer should reuse the existing reuse/repair history projection
and add only evidence of the public invocation and its durable root plan.

Acceptance requires:

- the public request creates one durable generated-code root with namespace
  steps for A and B;
- namespace residents claim and complete the corresponding durable steps;
- B calls A through the same qualified symbol;
- repair changes that symbol's source history in place, with no `-v2`, second
  namespace, or parallel function;
- a fresh execution child runs the selected B test green after repair;
- the terminal generated-code result and exact eval/message/database handles
  remain queryable after completion; and
- every observer, execution child, branch lease, and temporary fact owned by
  the scenario is released or explicitly retained by its documented policy.

The missing product seam is the public generate-code function contract being
completed by its owning lane. Inspect should not guess its name or reproduce
its scheduling protocol.

## Ordered live acceptance matrix

1. **Admit exact sources.** Require the existing source lock, clean admitted
   paths, native `.eval` destination, model/provider identity, artifact/config
   identity, and current database coordinates before model work.
2. **Namespace movement.** Run one generated `namespaces` milestone and require
   every `check_ns_movement` fact, including in-place refinement and home use.
3. **Namespace messaging.** On an acquired branch lease, require two addressed
   messages, one namespace resident, correct first-eval namespace, terminal
   addressed results if added, and fenced release.
4. **Across-turn and restart memory.** Run the `db` milestone, then the
   branch-lease-backed pod-restart scenario against a unique fact. Require same
   database and agent, changed pod, and equal post-restart query result.
5. **Durable planning.** Run the existing long-term-planning task through the
   same lease/restart adapter. Require the original pre-restart plan and leaves
   to finish without a replacement root plan.
6. **Execution-child recovery.** Run the existing child-recovery scenario and
   require joined crash, recovery, sibling, later-success, and host evidence.
7. **Public two-namespace generate-code repair.** After its public contract is
   committed, run the new adapter and require durable root/step evidence,
   qualified reuse, in-place history, and a fresh green test.
8. **Final cleanup and provenance.** Require terminal native Inspect logs,
   exact source/task/scorer/model/operator/database identities, no outstanding
   lease, and no scenario-owned execution child or observer. Repeat the frozen
   matrix for the selected model arms without changing tasks or scorers.

Steps 2 and 3, and steps 5 and 6, may execute concurrently on distinct branch
leases after source admission. Step 7 depends on the public generate-code
contract. Final graduation depends on all rows being replayable from native
Inspect evidence rather than prose or operator logs alone.

## Product seams versus harness gaps

- **Product seam:** the settled public generate-code request/response contract
  for a two-namespace repair is not yet available to Inspect.
- **Harness gaps:** live pod restart and durable planning still call or exclude
  retired lifecycle entry points even though the branch lease exists.
- **Evidence strengthening:** namespace routing should include terminal
  addressed results if the claim is useful task completion.
- **Already sufficient:** the structured namespace-movement oracle and the
  execution-child recovery scenario require no new product mechanism.
