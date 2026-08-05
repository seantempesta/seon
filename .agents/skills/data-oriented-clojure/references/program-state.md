---
type: reference
status: active
tags: [clojure, sci, program-graph]
---

# Program graph, base context, run fork, and session image

Read this before describing whether an evaluated definition is static,
process-live, run-private, or durable. Keep these boundaries separate.

## 1. Static build indexing

Build indexing analyzes first-party `src/` and `test/` without evaluating
application forms (`src/seon/fn/analyzer.clj:117-145`). The resulting canonical
rows come from exact analyzed source artifacts (`src/seon/fn.clj:292-353`).

## 2. Contracted runtime publication

An evaluated declaration becomes durable program data only through the
terminal transaction. After that transaction succeeds, the loop installs the
exact committed row from `db-after` into the supplied current context
(`src/seon/cluster/loop.clj:1641-1653`; `src/seon/sci/eval.clj:1271-1361`).
Agent-authored functions retain the complete Malli-contract requirement
(`src/seon/sci/eval.clj:1666-1685`).

## 3. Current cluster context; [TARGET] per-run forks

**[CURRENT]** Boot builds one acquired cluster context
(`src/seon/cluster.clj:1880-1885`; `src/seon/sci/eval.clj:1334-1361`), and
current evaluations mutate the supplied context.

**[TARGET — ruled, unbuilt]** Each run evaluates in a fresh generation-aware
`sci/fork` of the acquired base. Cross-agent sharing is contracted definition
→ admission → program fact → acquisition at a run boundary, never mutable
context sharing (`docs/prds/sci-execution-runtime/plan/README.md:381-395`;
`reference-code/sci/src/sci/core.cljc:331-337`).

## 4. Durable session-image facts

Ordinary session definitions are `:seon.code.def` facts, not contracted
`:seon.fn` rows. Each row carries a faithful inline value, blob-backed value,
proven source form, or explicit unrestorable reason
(`resources/seon/schemas/seon.code.def.edn:1-38`). The loop exact-reconciles
those rows beside the terminal receipt
(`src/seon/cluster/loop.clj:389-491,1641-1653`).

Cold acquisition pre-interns image names, binds faithful values, evaluates
only proven source rows in deterministic order, and leaves unrestorable names
unbound with durable reasons (`src/seon/sci/eval.clj:1271-1361`). The recurring
proof covers blob-backed values, nested functions, metadata, faithful data,
proven source, and unrestorable closures
(`test/seon/sci/session_image_test.clj:99-217,239-323`).

Do not collapse the session image into receipts, program rows, or replay.
Receipts share the terminal transaction boundary, but cold restore reads
`:seon.code.def` rows directly (`src/seon/cluster/loop.clj:1641-1653`;
`src/seon/sci/eval.clj:1282-1294`).
