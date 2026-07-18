---
type: issue
status: open
tags: [issue, agent, health, cljs]
---

# Persisted program error prevents agent repair

## Evidence

An agent evaluated a schema namespace followed by an action namespace whose
Malli function schema referenced the first namespace without requiring it. The
warm execution child accepted the forms because eval order had already loaded
the schema. After a clean pod restart, dependency-ordered program preparation
failed. Every later compiled render returned an execution error, and the same
program preparation boundary prevented the agent from evaluating the missing
require edge that would repair its program.

The execution IPC diagnostic added in `0a5a5589` located the rejected host
value at:

```clojure
[:seon.error/data :seon.error/data :seon.db.protocol/error :schema]

```

The immediate application error is an ordinary Clojure dependency bug. The
runtime defect is that a persisted program error removes the agent's normal
repair door after restart.

## Expected owner

`seon.execution` and `seon.eval` retain one execution path. Program preparation
must preserve exact ClojureScript dependency semantics while still allowing a
bounded repair form to replace or remove the source that fails preparation.
Do not add SCI, a pod-side authored eval path, or a second program registry.

## Acceptance criteria

- A persisted namespace with an invalid require/schema/function definition is
  reported as ordinary error data with its source namespace and form.
- The affected agent can evaluate a bounded corrective `ns`, `defn`, schema
  registration, or removal through the same supervised execution child.
- A clean restart then prepares the corrected program and invokes it normally.
- Regression proof starts from persisted invalid source; a warm compiler alone
  is insufficient evidence.

## 2026-07-18 implementation evidence

Commit `d34cbc2e` separates eval preparation from complete application loading
inside the existing execution child. The child still acquires and attempts the
exact current program. On failure it retains the trusted compiler, source map,
and configuration for `eval-batch!`; ordinary application invocation remains
refused.

The isolated `f33f49e6` runtime then proved the cold sequence against one real
database:

1. agent `fresh-kiwis-lay` committed `my.broken/run`; two eval forms passed;
2. a REPL-provenance transaction replaced `:my.broken`'s current
   `:seon.ns/source` with syntactically invalid source at basis transaction
   `536870924`;
3. a clean complete restart created new watcher, writer, and pod generations;
4. the fresh child accepted a corrective `(ns my.broken)` and `defn` through
   the normal supervised eval operation; two eval forms passed in turn
   `i5k4v6zy6j26`;
5. a second clean complete restart created another new process generation; and
6. the ordinary shared-function action path invoked `my.broken/run` in the
   fresh child and returned `{:seon.web.reactive.call/ok? true,
   :seon.web.reactive.call/value :repaired}`.

The repair-capability defect is closed by that proof. Keep this issue open only
for the first acceptance item: the initial program-load failure must expose its
source namespace and form directly instead of requiring an IPC value-path
diagnostic.
