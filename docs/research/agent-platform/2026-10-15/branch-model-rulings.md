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

1. **A branch runs its own HEAD, like a git checkout** (amended 10-15, below). Every reader (source, doc, references,
   must-reads, reach digests, the recorder) reads the branch's own database value; main's later changes are not seen
   until they are merged. The three-way readers and their caches are deleted (`seen-database`, `moved-past`,
   `merge-sides`, `commit-digests`, `main-changed`, the moved map in `seon.test.runner/reach-entries`, `seen-digests`).
2. **Which declarations SCI interprets is a lookup between two pointers:** those whose digest at the branch HEAD differs
   from the loaded commit's (`seon.sci.eval/loaded-identity`), plus their callers; a pure function of the two commit ids,
   cached by that pair and filled from the parent commit's entry. Host-bound platform code always runs as loaded.
   Drift is kept small explicitly: root merges main forward into chain branches (`git merge main`) at each restart
   batch, three-way, program entities only, conflicts listed in the agent's block (own-delta-hybrid.md P4).
3. **The writer follows the branch.** Platform code is only what SCI cannot interpret (the compiled evaluator, writer,
   runner); it changes globally after merge + reload. A predicate is program: the checked writer validates a branch's
   writes with that branch's predicates (context-bound projection), so a predicate the agent defines on its branch
   (a "branch-born predicate") can be used by its contracts before the merge
   (`seon.schema/a-contract-naming-a-branch-predicate-is-refused-at-the-writer`).
4. **Config** (the word; never "cluster dials") is branch data like everything else (amended 10-15, below): a branch
   reads its own config. Root changes a branch's config explicitly by applying it to that branch (`seon.config/apply!`
   takes the branch's connection; an entrance `bin/seon config apply --branch B` / `--chains`). Root acts
   cluster-wide and may write any branch; agents never write main. Config is not a program entity, so a merge never
   carries it. (S5 run 8's attempt read run 4's cap 40 because root applied 100 to main only.)
5. **Merge by reach closure.** Seeds: the chain's proofs plus the tests the branch defines that are green and reach
   something it changed. The merge carries their closure through all hops — called functions transitively, the schemas
   their contracts name, the schemas those reference, predicates named inside, the defs they read, their namespaces —
   and only indexed program entities (functions, tests, schemas, defs, namespaces). Agent-generated data (tasks,
   attempts, evaluations, messages, test-world facts) never merges. Branch additions nothing reaches (probes, scratch,
   experimental schemas such as run 8's `:tmp/probe`) stay on the branch and are listed in the merge report.
5a. **The merge list is curated by tests, and the agent sees it** (owner, 10-15). The agent's block shows its merge
   list live, derived by the same rule the merge uses: the entities the branch changed since its merge base (git's
   three-way: a change main made is never the branch's, so it is never carried back as a revert) that its seeds reach
   through all hops. Seeds are the task's proofs plus the branch's green tests that reach a change. Everything else the branch
   changed is listed beside it as "stays on the branch", each item with the form that would include it (a test that
   reaches it) or drop it (`ns-unmap!`). The agent curates the merge by writing tests, never by a hand list: to get
   something merged, test it. The block's change section (c94977458, "reached by no test") becomes this list; one
   function derives it for the block, the done rule's report and the merge, so what the agent sees is what merges.
5b. **Clean or not, as git decides it, then CI.** Structural: three-way per declaration (entity attribute) against the
   merge base (`seon.program/three-way`): changed on one side only, or identically on both, is clean; changed on both
   sides differently is a conflict. Semantic (git leaves this to CI): the merge candidate (main HEAD + the merge list)
   runs every seed whose reach digest differs from the branch's recorded result (main moved something it reaches);
   unchanged reach reuses the branch's green (ruling 6). Clean = no conflict and every seed green on the candidate.
   Not clean: nothing reaches main; the candidate stays as the merge branch with its conflicts and reds as data. Like
   `git merge main` into a feature branch, root first offers it back to the agent that has the context: main is merged
   into the chain branch, the conflicts and reds appear in the agent's block, and the agent resolves them by `defn` and
   tests. Root resolves by hand only when the agent cannot (ours = main, theirs = the branch, at the ordinary REPL on the
   merge branch), then `my.root/merge!`.
5c. **The help says all of this.** The agent's help (`resources/seon/help.md`, universal for every agent) states what
   merges (5a), how to see the merge list in the block, how to include (test it) or drop (`my.program/ns-unmap!`) a
   change, and what a returned conflict looks like. Root's help (`my.root/instructions` laws, and a root section) states
   how root applies config to a branch (ruling 4), how it reads a refused or conflicted merge, how it hands it back,
   and how it resolves by hand. Each help change lands in the commit that installs the behavior it describes, never
   ahead of it.
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
   from 2026-10-14, each with its `base/` merge-base ref, a pointer, not a copy); standing rule: an outside agent's branch is retired like an abandoned
   chain once it has no running graph and no unmerged change. Nothing in them is mined.
10. **The edit hook's babashka-loadable namespaces** (`seon.edit`, `seon.error`, …): root handles it — a declared
    namespace property checked by a bb-load test, so a merge that breaks the hook fails a test (the first automatic
    merge, becee4d99, broke it; reverted e5dd2abd5).
11. **Rejected for now:** binding main's compiled JVM Vars per context so compiled callers follow a branch (the
    predicates hybrid's "larger option", 8926d8d61).

## Amendment, 10-15 late morning: branches diverge like git (owner)

"I want main and branches to diverge just like in git and we explicitly merge things back. Make it as immutable and
pointer based as possible." And: config apply to a branch, done by root. This replaces ruling 1's "everything else
binds the loaded program and every reader reads the loaded program's row": a branch runs exactly its own HEAD; every
reader reads the branch's own database value; main's later changes are not seen until the merge, which reconciles
once. The only cross-pointer computation is which declarations SCI interprets: those whose digest at the branch HEAD
differs from the loaded commit's, plus their callers — a pure function of two commit ids, cached by that pair. The one
exception is host-bound platform code (evaluator, writer, runner), which always runs as loaded. Branch HEADs are
Datahike branch pointers held by the world's connection; nothing recomputes where a HEAD is. The hybrid
(own-delta-hybrid.md) settles own-delta vs this model; the owner's preference is this model.

## The work, in order (plan §0)

1. Design round (dig of the three-way commits → Opus + Astra independent plans → Fable hybrid → narrow check): own-delta
   (ruling 1-2), config reads (4), deleting the three-way readers; together with the writer slice (3).
2. Merge by reach closure and result lookup (5, 5a, 6): after 1; the merge list in the agent's block is the same derivation.
3. `ns-unmap!` shape (8), agent-branch retirement (9), bb-load property (10): small slices beside 1.
4. Stage exits (7): after 1 and 2, with `:outside` runs.
5. Open defects carried (root-working-state.md "Morning"): a closed working attempt keeps its graph; the re-run NPE in
   `seon.sci.eval` lazy-member (not reproduced on HEAD); D4b (a prove pass that changes nothing is proposed again);
   prompt caching fell to 23% in S5 run 6 (something in the system text varies per turn); the cold block 4.7 s;
   reasoning-without-answer / token-starvation replies delivered like unparseable ones.
