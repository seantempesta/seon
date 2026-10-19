# SCI context reuse across tasks on one branch (experiment, 2026-10-14)

Probe on default (HEAD fdc34a9ba), disposable branches `:probe-sci-reuse-a` (via `my.agent/create-branch!`) and
`:agent-1bd049e9b17c` (the branch `eval_clj` minted for agent `probe-sci-reuse-a1`); both retired with
`my.agent/retire-branch!`. No source edited, nothing merged. Timings are warm, one JVM, single samples unless noted;
(inferred) marks items read from source, not measured.

## 1. How a context is acquired today

`seon.cluster.agent/acquire-context!` `[handle agent-id {:seon.agent/branch b}]` (JVM handle =
`(:seon.turn.loop/cluster (val (first @seon.operator.runtime/running-instances)))`):

1. `acquire-once!` per `[branch agent-id]` in the registry. `previous` = that key's earlier handle.
2. Connection: previous's, else the live parent's, else `branch-owner`'s (any handle already holding that branch),
   else `registry/active-branch-connection`, else `store/open-branch!`.
3. Base program context `held-base`: `previous`'s `:seon.sci.eval/base-ctx`, else `branch-owner`'s, else
   `sci.eval/branch-ctx` (one `fork-cluster-ctx` = `sci/fork` of the source ctx with this connection's custody).
4. `sci.eval/acquire!` of `selected` (the branch db) onto the base: the branch's program entities are
   (re)interpreted from the db into the base (incremental, program-key keyed).
5. `sci.eval/fork-for-turn` (when agent-id): the agent's own ctx. No carried agent ctx: `forked-program` = `sci/fork`
   of the base. Carried ctx with an unchanged program key: returned as is (no fork, no walk). Moved key:
   `regenerate-agent-context!` forks the new program and carries the agent's private layer (own Vars, roots, bindings)
   as live objects.

Measured (`acquire-context!` on branch `:agent-1bd049e9b17c`, then `:probe-sci-reuse-a`):

| case | ms | alloc MB |
|---|---|---|
| agent a1 reacquire (its own previous, program changed since) | 15.3 | 10.8 |
| NEW agent a2, branch already held by a1 (branch-owner base) | 3.9 | 3.4 |
| a2 again (previous, key unchanged) | 0.75 | 0.63 |
| first acquisition of a branch nobody holds (a3) | 22.7 | 10.7 |
| second new agent on that branch (a4) | 3.3 | 3.4 |
| raw `sci.core/fork` of the base ctx | 0.006 | 0.04 |

`sci/fork` is ~5 microseconds because the env is an immutable value in an atom; everything else in acquisition is the
db connection, `acquire!` and the agent layer. The 23 ms cold branch acquisition is itself a fork of the source ctx
(`branch-ctx`), not a boot. The whole acquisition is ~4 ms for a second agent. SCI is not where task-start time goes.

## 2. Does a second agent on the same branch see the first agent's work?

Setup: as `probe-sci-reuse-a1` through `eval_clj` (SCI): `(in-ns 'probe.scratch) (defn hello ...) (def private-atom (atom 41)) (swap! private-atom inc)`,
later `(defn hello2 ...)`. Then in JVM mode `acquire-context!` for agents a2, a5 on the same branch and `sci/eval-string*` in each ctx:

| form | a1 (author) | a2 / a5 (new agents, same branch) | base ctx |
|---|---|---|---|
| `(probe.scratch/hello 7)` | "hi 7" | "hi 7" | "hi 7" |
| `@probe.scratch/private-atom` | 42 | unresolved symbol | unresolved symbol |
| namespace `probe.scratch` exists | true | true | true |

- `defn` is a program entity on the branch, so `acquire!` interprets it into the shared base from the db; every agent on
  that branch sees it, no re-evaluation by the author. Contexts are distinct objects (`identical?` ctx false, base true).
- A bare `def` of a live object (the atom) is the author's private layer and is NOT visible to another agent: not in the
  db, not in the base.
- A fresh context on a branch nobody holds (a3, branch forked before the defn) sees nothing from the scratch work, as
  expected; on the very branch that holds the defn it would be reinterpreted from the db (a2/a5 case).
- Private atom survived a later program change for the same agent (`defn hello2` then `@private-atom` = 42):
  `regenerate-agent-context!` carries it. `*ns*` stays `user` in a bare ctx; the agent's namespace lives in the db
  (`seon.sci.eval/agent-namespace`, and the reply's `:ns probe.scratch`) (inferred for how a new agent's start-ns is chosen).

## 3. What leaks if a context is reused for the next task

Reuse = same agent id keeps its ctx (`acquire-context!` returns `previous`; fork-for-turn returns the carried ctx).
Carried across tasks, by source (inferred unless noted):
- private defs and atoms (confirmed above): task A's scratch state stays callable and can shadow or mislead task B;
- result-handle bindings `result/e…` bound in the ctx (`::result-objects`, `bind-settled-results!`) and the print session
  options (`::print-session`): task A's handles stay live;
- own bindings of inherited Vars (`own-bindings`);
- the entering namespace: db fact, not ctx, so not a leak.
Not carried: any db state, because the db is the branch's, shared by both designs anyway.

Reuse buys at most the ~3-4 ms of a new-agent acquisition (see table) and keeps nothing the next task is entitled to
except program entities, which a new agent on the same branch gets anyway through `acquire!` of the shared base.

## 4. `seon.context/current-state` regeneration

`(seon.context/current-state db agent-id)`, held task `seon.ref/define-a-pathless-reference-section-is-a-missing-file-error`
(agent `attempt/341f9a9016cf`, read on main's db, read-only), 3 runs: 46.7 / 38.5 / 41.3 ms, ~128 MB alloc and ~1650 reads
for the first eval request; ~80 k calls. Task-less agent: ~25 ms, 88 chars. Sizes of the raw block (chars, pr-str):
task 2300, chain 1134, proofs 2218, step 103, must-reads 31485, turns 88, i.e. about 38 k chars (~9.5 k tokens, inferred
chars/4; the AI view `current-state-ai` renders must-reads through their declared renders, size not measured).
Findings on the way: `(seon.context/current-state-ai block)` called from a plain eval refuses at
`seon.render/render-ai` for the missing `:seon.sci.admit/caps` (the doc says the render argument carries the world; a
bare call is not a supported entrance; recorded as a contract error, 1 occurrence, on my retired branch). A first
defn on a fresh agent costs ~1.0 s (custody 131, derive 208, settle 223, test-step 270 ms); later defn 14 ms eval, 428 ms
request (derive 43, test-step 126, settle 155).

## Recommendation

Do not reuse the previous task's SCI context. Per task step: keep the branch and its connection, take a NEW agent id's
context via `acquire-context!` on the same `{:seon.agent/branch b}` (3-4 ms, 3.4 MB; the shared base already has every
program entity the earlier steps defined through `acquire!`), and regenerate `current-state` for that agent (~40 ms
plus the must-read renders). That is "keep the existing SCI base and db env" in the sense that matters: nothing is
re-evaluated and the branch is not re-forked. Reusing the same agent id would only add the 3 ms and carry task A's
private defs, result handles and print options into task B. If the owner wants the same agent id across steps for other
reasons, the carry is already automatic and safe for program entities; just treat private state as task-local.
Do not fork from the branch head to a new branch: that is the 23 ms path plus a branch to retire and loses nothing worth keeping.
Where task start time actually goes is the first-eval request stages (custody/derive/settle/test-step, 0.4 to 1.0 s) and
`current-state` (40 ms), not SCI acquisition.
