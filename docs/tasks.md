# Tasks and templates

All work is task entities on main, linked by `:needs`; a template turns what someone asked into the linked set that works it, and root writes the whole set in one transaction before any agent of it forks.

## A request is one transaction

A request becomes its whole linked task set in one transaction on main, released, and the reconciler forks the set's first agent only from a main that holds all of it (owner, 2026-10-22: "it's just one transaction with several maps … only when everything is set should it fork and run it so they have the same data until that point").

`seon.template/request` makes the set from one declaration (the request's ns, name, plan, and any proofs or model):
- the request itself as the **design** step (kind `:design`, its plan the request's words);
- `<name>-implement` (kind `:implement`), needing design;
- `<name>-merge` (kind `:merge`), needing implement: the template adds it, explicit in the graph from the start (DESIGN-tasks D3(a)).

All three check out design's branch, so implement holds design's schemas, stubs and red proofs, and merge holds both. The request's proofs are design's and reach the later steps along `:needs` (`seon.task/accumulate`).

`seon.template/request-tx` is the one transaction: `seon.task/create-tx` of the set, then `seon.task/execution-tx` releasing it. `my.root/request!` writes it with one `seon.root/transact-main!`. Since the reconciler reads only committed main, no agent of the set sees part of it. Not this: create held, then `my.root/run!` (two transactions), or adding `:needs` afterwards.

The two other templates make linked sets the same way:
- `seon.template/with-merge` follows each declared work task (the task files, `my.root/load-tasks!`) with its merge task;
- `seon.template/attach-tx` attaches design → implement → test → merge to a failed attempt, in the one transaction that cancels the failed chain.

What is not settled: proofs accumulate along `:needs`, so a design step's own check would block implement (DESIGN-tasks §11 #14), and the proofs design writes reach main only as its plan's text (§11 #11, #16).

## The agent knows its task

An agent holds the task root forked it for from its first form: `my.task/current` reads it through `seon.turn/current-task`. The task rides `seon.root/spawn!`'s opts into the writer's `:spawn` request and the fork server's `:fork` message, and `seon.agent/init!` keeps it in the agent's state. A turn loop, a context read and an attached outside agent read it the same way.

## The done rule

`seon.reconcile/done-refusals` says why an attempt's measured facts do not make its task done; empty means done. A proof with no result never ran: it refuses as `:proof-unknown`, not `:proof-red` (v1's reading), so the refusal names what was not measured.

Related: [landing](landing.md) for how a merge task's branch reaches main, [references](references.md) for how a plan's citations become the task's references, [parity](parity.md) for v1's chain and templates.
