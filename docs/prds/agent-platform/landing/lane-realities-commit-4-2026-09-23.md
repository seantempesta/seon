---
type: landing
status: stopped before implementation at the explicitly protected acquisition boundary
created: 2026-09-23
---

# Realities commit 4 — execution value is not supplied to agent callers

No implementation has landed. The assignment explicitly says: “if the entrance
needs a member for tests, STOP and name it … not a test-side copy.” The missing
value is the acquisition source carried into the executing context: in particular
the held `:seon.store/store` and `:seon.agent/context-state`. The returned execution
handle has these members; the agent-facing supplied context does not.

## Exact producer boundary

Observed HEAD: `295c03f6a`; acquisition implementation: `1ada78050`.
The following source files had no working-tree diff when inspected:
`src/seon/cluster/agent.clj`, `src/seon/sci/eval.clj`, `src/my/program.clj`,
`src/seon/env.clj`, `resources/seon/schemas/my.program.edn`, and
`resources/seon/schemas/seon.env.edn`.

* `src/seon/cluster/agent.clj:749` reads context-state from its handle.
  At line 761 it obtains the held store only from that handle or the explicit
  acquisition options; isolated acquisition without it refuses at line 778.
  The acquired map at lines 838–855 carries both values, but neither is installed
  into the executing ctx or its environment by this entrance.
* `src/my/program.clj:414–426`, the existing `supplied-context` producer, returns
  exactly connection, ctx and base-ctx. `config/default.edn:530–533` declares it
  as the supplier for `:my.program/context`.
* `src/seon/call_preparation.clj:1365` scopes the executing ctx into the existing
  environment. It does not supply an execution handle.
* `resources/seon/schemas/my.program.edn:1–4` declares the three-member context;
  `resources/seon/schemas/seon.env.edn` declares neither held store nor context-state.
* `src/my/test.clj:20` and `src/seon/test.clj:588` consume that supplied context.
  Converting these consumers alone cannot supply a held acquisition source.
* `src/seon/db.clj:388–391` binds only the connection and resets the read basis.
  Zero-argument host `deftest` bodies therefore do not receive the rest of the
  handle through this custody scope either.

An explicit-handle JVM request can call the entrance today. This finding concerns
the required automatic agent caller conversion and fixture delivery; it is not a
claim that commit 2 cannot create or release branches. A test-owned dynamic
handle, registry lookup, reconstructed context-state, or metadata transport would
needlessly introduce another transport instead of completing the existing owner.

The commit-2 owner needs to carry an acquisition source through the existing
executing-context/supplied-context path, preserving the selected branch and held
store. The producer and its schema/caller conversion must land together. These
are outside this assignment's owned paths. No protected source was edited.

## Runtime observation, not adoption proof

`bin/seon status` and MCP `runtime_status` both answered for root
`/Users/sean/src/seon`, cluster `default`, PID 51528, start
`2026-09-22T18:46:42.624Z`. No missing readiness layers; 14 errored receipts.
No missing tool was worked around. Default was not reset, reloaded or written.

MCP JVM/read-only, explicit root and cluster, timeout 5000 ms:

```clojure
(let [instance (get @seon.operator.runtime/running-instances "default")
      handle (:seon.turn.loop/cluster instance)
      ctx (:seon.sci.eval/ctx instance)
      environment (seon.env/of ctx)]
  {:acquisition-arglists (:arglists (meta #'seon.cluster.agent/acquire-context!))
   :cluster-handle-members (set (keys handle))
   :context-has-store (boolean (:seon.store/store ctx))
   :context-has-context-state (boolean (:seon.agent/context-state ctx))
   :environment-has-store (boolean (:seon.store/store environment))
   :environment-has-context-state (boolean (:seon.agent/context-state environment))
   :supplied-context-schema
   (get-in (seon.db/carried-projection
            (seon.db/db (seon.cluster.boot/connection "default")))
           [:seon.schema.projection/forms :my.program/context])})
```

Return in **4 ms**: acquisition arglists `([handle agent-id])`; all four
context/environment booleans false; supplied schema is connection + ctx +
base-ctx. The cluster handle contains context-state but no held store.
**This JVM predates commit 2's adoption.** Its answer establishes the live
starting boundary only; the commit-2 finding above is source evidence, not a
claim that default executed commit-2 source. No `seon.test/run` was invoked.

## Dependency evidence

HEAD gitlinks: Datahike `cc2b2bc7dbe774ea1bcc7487e8225a0d420d0e17`, SCI
`fcbd8862800e638dc0f8f5521111f999279cbcd2`. Datahike working tree is foreign-dirty.
Inspected `reference-code/datahike/src/datahike/versioning.cljc:212`:
branch takes connection, retained commit/branch and destination, acquiring the
roster permit; secondary indices use copy-on-write. SCI
`reference-code/sci/src/sci/core.cljc:345` forks the env atom with a new generation.
The first-party composition is `agent/acquire-context!`. These dependency
operations do not manufacture the missing caller's store/context-state.

## Proof ledger and size

| Proof | This attempt |
| --- | --- |
| (a) body enters through acquisition, roster then unlink | Not run |
| (b) independent members off one commit | Not run |
| (c) recorded green reuse, zero executions | Not run |
| (d) declared overrun and actual exit | Not run; existing `bounded-result` still uses Future completion, which is not thread-exit evidence |
| (e) destructive exclusion | Not run |
| (f) leaf/core request wall time | Not measured |
| (g) committed source load and platform tier | No implementation commit; no load or platform pass claimed; platform remains orchestrator-owned |
| (h) named request via MCP on adopted default | Not run; adoption prerequisite unsatisfied |

Current measured area, excluding schemas: test.clj 2181; runner.clj 4943;
cache.clj 609; selection.clj 215; fast.clj 123; bounds.clj 44; test_support.clj
1119; bin/test 1107; test-fast 48; _test-slot 165; test-check 53. Total **10607**.
Implementation line delta **0**; no size target met. Historical shelved branch
timings (41.64 ms first, 34.88 ms later p50) remain prior evidence, not rerun here.

No scratch root, worktree, background shell, test JVM or process was created.
No foreign session was contacted. Existing issue search found the historical
hot-adopted-handle-shape issue, not this supplier omission; this note records the
new integration boundary without changing the issue index.
