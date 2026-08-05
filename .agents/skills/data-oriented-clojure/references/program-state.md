---
type: reference
status: active
tags: [clojure, sci, program-graph]
---

# Program graph, base context, turn fork, and agent desk

Read this before describing whether an evaluated definition is static,
process-live, turn-private, or durable. Keep these boundaries separate.

## 1. Static build indexing

Build indexing selects the first-party `src/` and `test/` roots and analyzes
their files without evaluating application forms (`src/seon/fn.clj:20-22,
831-856`; `src/seon/fn/analyzer.clj:117-145`). Exact analyzed source becomes
canonical artifact rows at `src/seon/fn.clj:293-399`.

## 2. Contracted runtime publication

An evaluated declaration becomes durable program data only through the
terminal transaction. After that transaction succeeds, the loop installs the
exact committed row from `db-after` into the live base and current turn fork
(`src/seon/cluster/loop.clj:1640-1659`). Agent-authored functions retain the
complete Malli-contract requirement (`src/seon/sci/eval.clj:286-301`).

## 3. Live base and per-turn forks

Boot builds one program-only acquired cluster context
(`src/seon/sci/eval.clj:1369-1392`). The loop makes one fresh `sci/fork` for
each turn and uses it for every form in that turn
(`src/seon/cluster/loop.clj:1496-1502`; `src/seon/sci/eval.clj:1309-1318`).

Cross-agent sharing is contracted definition → admission → program fact →
install in the live base → next-turn fork. An existing fork cannot see a newly
installed base binding, while an inherited Var remains shared until a
generation-owned write copies it (`reference-code/sci/src/sci/core.cljc:331-337`;
`reference-code/sci/src/sci/impl/utils.cljc:362-379`;
`reference-code/sci/src/sci/impl/evaluator.cljc:25-49`).

## 4. Agent-scoped desk facts

Ordinary session definitions are agent-scoped `:seon.def` facts, not
contracted `:seon.fn` rows. Each row is identified by agent plus qualified
name and carries a pure source form, a faithful inline/blob value, an atom's
last settled value, or an explicit unrestorable reason
(`resources/seon/schemas/seon.def.edn:1-45`). The loop selects that restore
ladder and supplies the rows to the terminal receipt request
(`src/seon/cluster/loop.clj:376-490,1623-1645`).

Each fresh turn fork rehydrates only the selected agent's rows in deterministic
order, recreates atoms around their snapshots with an honest notice, and
states every loss (`src/seon/sci/eval.clj:1297-1367`). Exact replacement is
inside `receipt-settle-call`; clearing is a separate explicit, agent-local
transition (`src/seon/cluster/run.clj:990-1068`). The recurring proof crosses
a force-destroyed writer JVM and fresh reader JVM, then clears the desk
(`test/seon/sci/desk_test.clj:184-225`).

Do not collapse the desk into program rows or replay. Desk facts share the
terminal receipt transaction for atomicity, but every turn reads them directly
for the selected agent (`src/seon/cluster/run.clj:990-1051`;
`src/seon/sci/eval.clj:1317-1329`).
