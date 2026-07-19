---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, cljs, flow]
---

# Agent filesystem edit published malformed Clojure

## Problem

During the three-agent graduation journey, root attempted to repair
`my.ns/functions` after child calls exposed its stale invocation shape. The
filesystem edit duplicated the function head and left `src/my/ns.cljs`
unbalanced. The watcher rejected the build, the pod recorded a core fault, and
the configured fail-loud policy drained it.

The fail-loud publication boundary worked, but the mutation boundary had
already replaced a valid tracked Clojure file. A malformed agent-authored edit
must not become the checkout's current source and must not prevent the fresh
pod needed to evaluate a repair.

## Owner

`seon.agent.fs` owns filesystem mutation. Its deterministic match and SHA fence
prevent ambiguous or stale edits, but its write/edit/replace boundaries do not
currently validate Clojure syntax before replacing a Clojure-family file.

## Acceptance

- Identify the exact root eval and mutation function from database evidence.
- A malformed Clojure-family edit returns an ordinary error and leaves the
  prior bytes unchanged.
- A valid edit still uses the existing match/SHA fence and publishes normally.
- Focused tests and a live malformed-edit attempt prove the pod stays ready.

## Resolution

Database eval `thxtlvt9qykz` identifies the exact mutation as
`seon.agent.fs/edit-file` over lines 57–91 of `src/my/ns.cljs`. Every
Clojure-family mutation owner—write, line/match edit, anchored replace, and
insert—now parses the proposed complete file with the maintained rewrite-clj
reader before replacing bytes. Match selection, SHA fencing, and write gates
remain the one existing mechanism.

Focused filesystem proof passes 35 tests/155 assertions. A live attempt to
replace line 39 of the tracked file with `(defn broken [` returned the ordinary
error `refused malformed Clojure source`, retained the exact prior SHA, restored
the pod grant to read-only, and left the pod ready.
