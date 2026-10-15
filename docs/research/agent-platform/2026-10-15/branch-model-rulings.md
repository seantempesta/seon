---
title: "Owner rulings 2026-10-15 morning: the branch model (own writes over main's HEAD), merge by reach closure, the writer follows the branch, config, stage exits — and the design work they start"
type: research
status: active (owner rulings; the design round and slices below are the plan §0 order)
created: 2026-10-15
---

# Branch model and merge: owner rulings, 2026-10-15

Context: the night of 10-14 (docs/research/agent-platform/2026-10-14/root-working-state.md, "Night of 10-14" and
"Morning"). Most stalls of S5 runs 5-8 were one problem: a long-lived chain branch held its merge base's rows for code
main had since changed, while the one JVM runs main's HEAD compiled. Root patched it reader by reader with a three-way
rule (`seon.cluster.registry/seen-database`, `moved-past`, `merge-sides`, and their uses in the overlay, `doc`,
`source`, references, reach digests and the recorder). The owner rejected that as per-declaration work and gave the
model below.

## The model (owner)

- A branch advances by its own writes. It is temporary; the merge to main keeps drift small. Main is itself a branch
  that advances as agents merge into it. Root knows the HEAD of main and of every branch; an agent resumes from its
  branch's HEAD (the world's connection). That is the point of the world/context.
- No rebase. Drift is reconciled once, three-way, at the merge.
- Nothing is copied: a branch shares main's immutable indexes as of its fork and adds its own datoms.

## Rulings

1. **A branch's program is its own writes.** `own-delta` = the declarations in the branch's own transactions since its
   base (a `since` read of its own history; cost proportional to the agent's work). Interpreted in SCI = own-delta ∪
   its callers; everything else binds the loaded program's compiled Vars (main's HEAD as loaded), and every reader of a
   declaration the branch did not write (source, doc, references, must-reads, reach digests, the recorder's stale check)
   reads the loaded program's row, not the branch's base copy. The three-way readers and their caches are deleted when
   own-delta lands (`seen-database`, `moved-past`, `merge-sides`, `commit-digests`, `main-changed`, the moved map in
   `seon.test.runner/reach-entries`, `seen-digests` in `executed-roots`).
2. **Which declarations are compiled vs interpreted is a lookup:** the loaded program's definition digests vs the
   branch's own writes, plus the stored call graph for callers. No per-declaration comparison against main's history.
3. **The writer follows the branch.** Platform code is only what SCI cannot interpret (the compiled evaluator, writer,
   runner); it changes globally after merge + reload. A predicate is program: the checked writer validates a branch's
   writes with that branch's predicates (context-bound projection), so a predicate the agent defines on its branch
   (a "branch-born predicate") can be used by its contracts before the merge
   (`seon.schema/a-contract-naming-a-branch-predicate-is-refused-at-the-writer`).
4. **Config** (the word; never "cluster dials"): config the branch wrote itself (e.g. a task's `:seon.task/model`) wins;
   everything else reads main's HEAD, so `bin/seon config apply` reaches running agents. (S5 run 8's attempt read run
   4's frozen cap because it read its own branch's fork-time config.)
5. **Merge by reach closure.** Seeds: the chain's proofs plus the tests the branch defines that are green and reach
   something it changed. The merge carries their closure through all hops — called functions transitively, the schemas
   their contracts name, the schemas those reference, predicates named inside, the defs they read, their namespaces —
   and only indexed program entities (functions, tests, schemas, defs, namespaces). Agent-generated data (tasks,
   attempts, evaluations, messages, test-world facts) never merges. Branch additions nothing reaches (probes, scratch,
   experimental schemas such as run 8's `:tmp/probe`) stay on the branch and are listed in the merge report.
6. **Merge reuses the branch's results.** Every eval request's test step already records results keyed by reach digest;
   with ruling 1 a branch result's identity equals its identity on the merge candidate. The merge looks results up by
   digest and runs only what is missing; no candidate world boot in the normal case.
7. **Stage exits (per-task deftests) are the least mature part and get real work:** each stage's exit must tell its
   agent concretely what done looks like (e.g. Design: every example in the Problem maps to a named function in the
   Approach; run 4's Design passed `seon.step.design/handoff` with an Approach that could not produce the Problem's own
   example). Revise from `:outside` dogfood runs (no provider cost), stage by stage, after rulings 1 and 5.
8. **`my.program/ns-unmap!` takes clojure.core's shape.** `:my.program/context` becomes a world-derived input (assembled
   from the world's connection and contexts), never a positional argument; `ns-unmap!` declares `[subject]` and
   `[ns sym]` only. Same principle as "a database value is never bound".
9. **Agent branches of MCP lanes are scratch.** Retire every non-chain agent branch with no running graph (about 100
   from 2026-10-14, each with a `base/` copy); standing rule: an outside agent's branch is retired like an abandoned
   chain once it has no running graph and no unmerged change. Nothing in them is mined.
10. **The edit hook's babashka-loadable namespaces** (`seon.edit`, `seon.error`, …): root handles it — a declared
    namespace property checked by a bb-load test, so a merge that breaks the hook fails a test (the first automatic
    merge, becee4d99, broke it; reverted e5dd2abd5).
11. **Rejected for now:** binding main's compiled JVM Vars per context so compiled callers follow a branch (the
    predicates hybrid's "larger option", 8926d8d61).

## The work, in order (plan §0)

1. Design round (dig of the three-way commits → Opus + Astra independent plans → Fable hybrid → narrow check): own-delta
   (ruling 1-2), config reads (4), deleting the three-way readers; together with the writer slice (3).
2. Merge by reach closure and result lookup (5, 6): after 1.
3. `ns-unmap!` shape (8), agent-branch retirement (9), bb-load property (10): small slices beside 1.
4. Stage exits (7): after 1 and 2, with `:outside` runs.
5. Open defects carried (root-working-state.md "Morning"): a closed working attempt keeps its graph; the re-run NPE in
   `seon.sci.eval` lazy-member (not reproduced on HEAD); D4b (a prove pass that changes nothing is proposed again);
   prompt caching fell to 23% in S5 run 6 (something in the system text varies per turn); the cold block 4.7 s;
   reasoning-without-answer / token-starvation replies delivered like unparseable ones.
