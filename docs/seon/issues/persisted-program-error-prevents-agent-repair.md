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
