---
type: reference
status: active
tags: [clojure, sci, program-graph]
---

# Program graph, base context, turn fork, and the agent's defs

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
(`src/seon/cluster/loop.clj:1408-1417`). Agent-authored functions retain the
complete Malli-contract requirement (`src/seon/sci/eval.clj:286-301`).

## 3. Live base and per-turn forks

Boot builds one program-only acquired cluster context
(`src/seon/sci/eval.clj:1294-1385`). The loop makes one fresh `sci/fork` for
each turn and uses it for every form in that turn
(`src/seon/cluster/loop.clj:1245-1264`; `src/seon/sci/eval.clj:1418-1492`).

Cross-agent sharing is contracted definition → admission → program fact →
install in the live base → next-turn fork. An existing fork cannot see a newly
installed base binding, while an inherited Var remains shared until a
generation-owned write copies it (`reference-code/sci/src/sci/core.cljc:331-337`;
`reference-code/sci/src/sci/impl/utils.cljc:362-379`;
`reference-code/sci/src/sci/impl/evaluator.cljc:25-49`).

## 4. Agent-scoped def facts

Ordinary session definitions are agent-scoped `:seon.def` facts, not
contracted `:seon.fn` rows. Each row is identified by agent plus qualified
name and carries a pure source form, a faithful inline/blob value, an atom's
last settled value, or an explicit unrestorable reason
(`resources/seon/schemas/seon.def.edn:1-45`). The loop selects that restore
ladder and supplies the rows to the terminal receipt request
(`src/seon/cluster/loop.clj:195-240,426-436`).

Each fresh turn fork rehydrates only the selected agent's rows in deterministic
order, recreates atoms around their snapshots with an honest notice, and
states every loss (`src/seon/sci/eval.clj:1387-1492`). Exact replacement is
inside `receipt-settle-call`; clearing is a separate explicit, agent-local
transition (`src/seon/cluster/run.clj:1230-1307,1419-1440`). The recurring proof crosses
a force-destroyed writer JVM and fresh reader JVM, then clears the agent's defs
(`test/seon/sci/defs_test.clj:363-408`).

Do not collapse the agent's defs into program rows or replay. Their facts share the
terminal receipt transaction for atomicity, but every turn reads them directly
for the selected agent (`src/seon/cluster/run.clj:1405-1417`;
`src/seon/sci/eval.clj:1426-1492`).
