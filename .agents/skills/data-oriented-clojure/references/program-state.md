# Program graph, live context, and session image

Read this before describing whether an evaluated definition is static,
process-live, or durable. Keep these four boundaries separate.

## 1. Static build indexing

Build indexing analyzes the JVM projection without evaluating application
forms (`src/seon/fn/analyzer.clj:117-145`). Its admitted roots are first-party
`src/` and `test/` (`src/seon/fn.clj:19-21`), and the resulting canonical rows
come from exact analyzed source artifacts (`src/seon/fn.clj:404-426,566-577`).

## 2. Contracted runtime publication

An evaluated declaration becomes durable program data only through the
terminal transaction. After that transaction succeeds, the loop resolves the
exact committed row from `db-after` and installs it into the supplied live
context (`src/seon/cluster/loop.cljc:1411-1447`;
`src/seon/sci/eval.clj:789-895`). Agent-authored functions retain the complete
Malli-contract requirement; contract facts are derived before publication
(`src/seon/sci/eval.clj:1323-1379`).

## 3. One process-live SCI context per cluster

Boot constructs one cluster context after config and root-agent facts exist
and before work launching or agent arming (`src/seon/cluster.clj:1337-1363`).
Every ordinary form uses that supplied context as given, so definitions
accumulate and become immediately visible within the cluster
(`src/seon/sci/eval.clj:1230-1275`). Only a namespace-unmap evaluation runs in
an isolated fork; its exact namespace state is installed after the terminal
transaction commits (`src/seon/sci/eval.clj:1280-1299,885-895`).

## 4. Durable session-image facts

Ordinary session definitions are a separate `:seon.code.def` fact family, not
contracted `:seon.fn` rows. The schema permits exactly identified namespace
definitions carrying a faithful inline value, a blob-backed value, a proven
source form, or an explicit unrestorable reason
(`resources/seon/schema.edn:2151`). The loop exact-reconciles those
rows beside the terminal receipt (`src/seon/cluster/loop.cljc:325-430,1411-1424`).

Prefer a faithful value whenever `store-faithful-edn` proves class, metadata,
and value round-trip; values over the configured threshold use the existing
content-addressed blob path (`src/seon/cluster/loop.cljc:432-458`). Retain a
source form only when the defining evaluation completed successfully and the
derived host-interop, effect, capability-reachability, and determinism checks
prove it safe to evaluate again; otherwise persist the explicit unrestorable
row (`src/seon/cluster/loop.cljc:340-417`).

Cold acquisition pre-interns every image name, binds inline and blob-backed
values, evaluates the proven source rows in deterministic ordinal/id order,
and leaves unrestorable names unbound while returning their durable reasons
(`src/seon/sci/eval.clj:1142-1203`). `cluster-ctx` performs program acquisition
and this session-image installation once for the cold cluster context
(`src/seon/sci/eval.clj:1205-1228`). The recurring cold-restore proof covers a
200,000-element blob-backed value, a function nested in a map, metadata,
effectful-but-faithful data, a proven source form, and an unrestorable closure
(`test/seon/sci/session_image_test.clj:239-323`).

Do not collapse the session image into receipts, program rows, or replay.
Receipts share the terminal transaction boundary, but cold restore reads
`:seon.code.def` rows directly (`src/seon/cluster/loop.cljc:1411-1424`;
`src/seon/sci/eval.clj:1153-1165`).
