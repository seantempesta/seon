---
title: "M4 notes: the unmerged entity has no merge branch to live on until M6"
type: research
status: lane m4, 2026-10-12. Stopped before editing; nothing claimed, nothing changed. The hybrid's M4 row contradicts the code at HEAD 373ad0ac8, so the options below wait for root's ruling.
---

# M4 notes

## The contradiction

The hybrid puts `:seon.merge/unmerged` "on the merge branch only", written by `merge!`. At HEAD no merge branch exists:
`seon.cluster.squash/merge!` (`[connection a branch meta]`) transacts `[[:db.fn/call merge-tx a C projection]]` on main
and then writes `conflict-task!` on main through the same connection. The merge branch `merge/<assignment>` is M6
(fork from main's head B, three-way applied there, gate there). Two further gaps:

- `merge!` holds a `:seon.db/connection`, not a `:seon.store/store`. Forking and opening a branch is
  `seon.cluster.registry/branch!` / `fork!` and `seon.cluster.store/open-branch!`, which take the store. The only caller,
  `seon.cluster.agent/land-ends!`, has `(handle-store handle)`, but passing it means editing `src/seon/cluster/agent.clj`
  (not in M4's file list; M5 deletes `land-ends!`) and `merge!`'s signature, and `merge!` is not world-supplied for a store.
- `merge-tx` runs in the writer and reports its conflicts only by re-deriving them from `:db-before` after the transaction
  (`merged` called a second time). That is what M4 would store.

## Options

1. **Reorder: M4 after M6** (recommended). M6 creates the merge branch and applies the three-way there, so the conflicts are
   written in the transaction that produces them; `conflict-task!` is deleted then, with `db_flow_test` 680 and `squash_test`
   101 converted once instead of twice. Cost: M4's proof waits; HEAD keeps root's prose task meanwhile.
2. **Write `:seon.merge/unmerged` on main in M4** (simplest now). `merge!` transacts the entities (id `seon.id/id
   [assignment attribute entity]`) beside the merge, through the connection it already holds; no store, no agent.clj edit.
   Contradicts "merge branch only": main would hold the entity until M6 moves it, and a main-side reader (`my.root/merge-requests`)
   would see it. The proof `a-conflict-keeps-its-three-values-on-the-merge-branch` would read main until M6 renames its target.
3. **A throwaway merge branch in M4**: `merge!` takes a store (new argument), forks `merge/<assignment>` from main's head after
   the merge, opens it, transacts the entities, releases. True to the data design, but M6 rewrites it (fork before, not after),
   adds the `agent.clj` edit and a signature change that M6 changes again, and costs a branch create/open per conflict (unmeasured).

Schema (all options): `resources/seon/schemas/seon.merge.edn`, no `:seon.merge/` attributes exist in the registry
(`seon.squash/conflict` is the shape to replace; its `:base/:ours/:theirs` are `:seon.schema/value`, printed by the value
printer when not plain data).
