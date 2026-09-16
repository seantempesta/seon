---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [adoption, instrumentation, contracts, class]
---

# Adoption can leave a changed contract armed with its previous shape

## Problem

`seon.turn/write-refusal-bound`'s declared contract was changed from

```clojure
[:=> [:cat :seon.db/database-value :seon.cluster/name :seon.agent/id] ...]
```

to

```clojure
[:=> [:cat :seon.db/database-value :seon.turn.loop/cluster :seon.agent/id] ...]
```

in the same commit (`f0cb3f692`) as the caller that passes the handle map.
`bin/seon init --dev default --changed src/seon/turn.clj` reported
`development cluster converged` (`:current-src` commit
`6aaa99ca-0cf3-53d6-a204-6e2ff481f7c5`), and afterwards an in-process
`seon.test/run` still failed at the wrapper with

```
seon.turn/write-refusal-bound refused cluster at []: expected a string, got a map.
  at [seon.turn$step invokeStatic turn.clj 5284]
```

— the caller was the NEW `step` (line 5284 is the new call site), so the
namespace was reloaded, while the wrapper kept the PREVIOUS contract. A direct
MCP call to the same Var earlier in the same JVM had reported the NEW
contract's refusal, so the armed shape is not even stable across one session.

AGENTS §1 states publication "re-arms wrappers when their contract or a
transitively referenced declaration changes". Here the contract changed and
the wrapper was not re-armed, or was re-armed from a program row that still
carried the previous contract.

## Why it matters

This is the absence-of-signal shape again, one level up: adoption REPORTS
convergence, so a lane reasonably treats the live cluster as current. Every
in-process proof of a changed contract is then run against a shape that is not
in the source, and the failure looks like the lane's own defect. Three
adoption cycles were spent on this before the source was checked against HEAD
and found correct.

## Repro

1. Change a public function's `:malli/schema` argument shape and its caller in
   one commit.
2. `bin/seon init --dev default --changed <that file>`; wait for
   `development cluster converged`.
3. Call the function through the caller in-process; the wrapper refuses with
   the PREVIOUS contract.

## Fix shape

Adoption's re-arm decision should be derived from the contract it just
published, at the authority, rather than from a comparison that can answer
"unchanged" for a contract that did change. When a wrapper is not re-armed,
that is a decision worth naming in the adoption report, so a lane can see
which wrappers kept their identity.

Found by the write-storm lane, 2026-09-17, while proving
`seon.turn-test/a-refused-turn-write-is-bounded-and-commits-exactly-one-fault`.
