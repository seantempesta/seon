---
title: "Card TX-1: No empty commits; one registration per agent"
type: reference
status: active
created: 2026-10-08
---

# TX-1 No empty commits; one registration per agent

Ledger row TX-1 (group E/M). Census §0.3: 56 of 1,218 commits on default's `:cluster-default` carry no data datom:
39 `seon.cluster.host/home-call` (re-hosting an agent that already exists) and 17 anonymous `seon.db/transact!`; a new
agent is registered by two commits (census §2 throwaway: `home-call` 39 datoms on the cluster, `home-call` 40 again on
the new branch). Each commit costs ~41 KB retained + 29 KB head on default (census §1). Lane: Sonnet. Cited at HEAD
`22910a03a`. Split: **TX-1a** (re-hosting and new agents, this card's functions) and **TX-1b** (the anonymous empty
commits, measure-then-fix, below).

## TX-1a

### What changes

- `seon.cluster.host/start!` (`src/seon/cluster/host.clj:73`) transacts `[:db.fn/call #'home-call …]` on the cluster
  and then on the branch (`:85-90`). `home-call` (`:44-71`) answers `[]` when the matching row exists, and the
  transaction still commits its stamp. `start!` now pre-reads each target's current value and sends no transaction
  when the row already matches (same `:seon.agent/branch`, no `:seon.agent/admission-closed-tx`). Every other case
  (absent, closed, homed elsewhere) still goes to the writer, which decides races and refuses by name as today. The
  pre-read only skips a write whose answer is known to be `[]`; admission is still fenced by the run writer.
- `seon.mcp/execution` (private, `src/seon/mcp.clj:171`): for an unknown agent it creates the branch
  (`my.agent/create-branch!`, `:197-199`) and then `host!` registers twice. It now registers on the cluster first,
  then forks, so the branch inherits the row and `start!`'s pre-read skips the branch write: one registration commit
  per new agent. If a registration exists whose branch is missing from the store's roster (a crash between the two),
  the branch is created then: `created (when-not (contains? (registry/roster store) branch) (my.agent/create-branch! …))`
  (`seon.cluster.registry/roster`, `src/seon/cluster/registry.clj:110`, the store's branch set).

### Files

- `src/seon/cluster/host.clj`
- `src/seon/mcp.clj`
- `test/seon/cluster/host_test.clj`

### Functions

- ADD `seon.cluster.host/registered?` (private)
  `{:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.agent/branch] :boolean]}`
  `[database agent-id branch]` = the row from `agent-entity` (`:22`) exists, is not a read refusal, has
  `(= branch (:seon.agent/branch row))` and no `:seon.agent/admission-closed-tx`.
- CHANGE `seon.cluster.host/start!` (`:73`), contract unchanged: inside the `some` over targets, skip
  (answer `nil`, "not refused") when `(registered? (db/db target) agent-id branch)`; otherwise transact as today.
- ADD `seon.cluster.host/register!` (public; the one place a registration is written)
  `{:malli/schema [:=> [:cat :seon.db/connection :seon.agent/id :seon.agent/branch :seon.ns/name :seon.cluster/name] [:or :nil :seon.db/transaction-refused-error]]}`
  `[connection agent-id branch namespace-name cluster-name]`: `nil` when `registered?`, else the `home-call`
  transaction under `(db/with-branch {} …)`, answering `nil` on commit or the refusal. `start!` calls it for
  each target (replacing its inline `db/transact!`).
- CHANGE `seon.mcp/execution` (`:171`), contract unchanged: in the `:else` branch, when `home` is nil, first
  `(host/register! connection agent-id branch (or namespace-name 'user) (:seon.cluster/name handle))` on the cluster
  connection (answer its refusal as today's `host!` refusal), then `create-branch!`, then `host!`. When `home` is
  present, create the branch only if it is absent from `(registry/roster store)`.

### Proof

`seon.cluster.host-test/an-existing-agent-rehosts-without-a-commit` and
`seon.cluster.host-test/a-new-agent-is-registered-by-one-commit` (new; `test/seon/cluster/host_test.clj`, its fixtures
as `one-identity-is-homed-on-one-branch-decided-by-the-writer` `:205`):

```clojure
;; rehost: host, stop! (admission stays open), host again
(let [before [(db/basis-t (db/db cluster-conn)) (db/basis-t (db/db branch-conn))]]
  (host/stop! hosted)
  (host/host! request)
  (is (= before [(db/basis-t (db/db cluster-conn)) (db/basis-t (db/db branch-conn))])))
;; new agent through MCP: exactly one home-call transaction, the same one on both branches
(let [home-calls (fn [db] (db/q '[:find [?tx ...] :where [?tx :seon.db.transaction/operation seon.cluster.host/home-call]] db))]
  (call! "(+ 1 2)" "tx1-new" running)
  (is (= 1 (count (set/difference (set (home-calls (db/db cluster-conn))) before-calls))))
  (is (contains? (set (home-calls (db/db new-branch-conn))) the-cluster-call)))
```

Datahike's `:seon.db.transaction/operation` is AVET-indexed (`resources/seon/schemas/seon.db.transaction.edn`), so the
query is a seek.

### Reaching tests

`seon.cluster.host-test`, `seon.mcp-entrance-test`, `seon.cluster.mcp-test`, `seon.cluster.agent-identity-test`.

### Cost

Expected: one EAVT pull per target per host start (microseconds) replaces a commit (41 KB + 29 KB, ~15 ms on default)
whenever the agent exists; a new agent saves one commit (63 KB on the throwaway, census §2) and its 254 ms first call
loses one of its three writes. Report the census window before/after: `home-call` commits per restart → 0.

### Commit

`bin/lane commit <lane> -m "TX-1a: an existing agent re-hosts without a commit; a new agent is registered once" --test seon.cluster.host-test --test seon.mcp-entrance-test --test seon.cluster.mcp-test src/seon/cluster/host.clj src/seon/mcp.clj test/seon/cluster/host_test.clj`

## TX-1b The anonymous empty commits

17 commits on default's history are `seon.db/transact!` with no data datom and no agent (census §2 "whole history").
Their producers are not named by the census. This slice names them, then removes each by the TX-1a pattern.

1. **Measure** (15 minutes, no source change): in a disposable JVM-mode probe on default, attach
   `(datahike.api/listen conn ::tx1b (fn [report] …))` to the cluster connection; for every report whose `:tx-data`
   holds only the transaction entity's own datoms (all `(= (:e d) tx)`), record `(:seon.db.transaction/operation …)`
   from the tx entity and a 20-frame stack from `(Thread/currentThread)` taken inside the listener (Datahike calls
   listeners on the writer thread, so take the stack in `seon.db/transact-call` instead: a temporary `d/listen` cannot
   see the caller; use the census probe's write-hook pattern,
   `docs/research/agent-platform/2026-10-08/tx-census/opus-evidence/tx-census-probe.clj`, and record the caller's
   stack from a JVM-mode `alter-meta!`-free wrapper: call `seon.db/transact!` sites found by
   `rg -n 'db/transact!' src | rg -v ':db.fn/call'` and check which can send an empty vector). Leave default as found;
   write the producers into this card's "Found" list in the same commit as their fix.
2. **Fix**: each producer skips its write when its own data is empty (`(when (seq tx-data) (db/transact! …))`) or when
   its pre-read shows the value already holds. Never a global rule in `seon.db/transact!`: an agent's explicit
   `(seon.db/transact! [])` commits as Datahike does (the REPL law).
- Proof: `seon.db-test/a-system-writer-with-nothing-to-write-commits-nothing` naming each producer found (one row
  per producer: run it with nothing to do, assert `db/basis-t` unchanged).
- Commit: `bin/lane commit <lane> -m "TX-1b: system writers with nothing to write commit nothing" --test seon.db-test <the producer files> test/seon/db_test.clj`

## Dependencies

None. `src/seon/mcp.clj` is also TX-0's file (serialize). GV-2 later renames `create-branch!` to `branch!`: whichever
lands second converts the call.

## Reset needs

None.
