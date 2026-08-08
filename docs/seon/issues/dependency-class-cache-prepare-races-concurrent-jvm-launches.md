---
type: issue
status: open
severity: friction
tags: [issue, runtime, testing]
---

# The dependency class-cache prepare races concurrent JVM launches

## Problem

`bin/seon start` (and any launch preparing `target/dev-dependency-classes`)
stages into a shared `target/dev-dependency-classes.next/<uuid>` and renames
into place. Under concurrent JVM launches (many lanes, the overnight
stress-test load), the prepare fails two ways, both observed 2026-08-08:

1. the rename collides — `Directory not empty` on
   `dev-dependency-classes.next/<uuid> -> dev-dependency-classes/<digest>`
   — and the launch dies with "The dependency class cache could not be
   prepared" (`#:seon.fresh-operator{:exit 1}`);
2. an external `rm -rf` of the stale staging dir races a concurrent
   writer and cannot complete, so recovery-by-hand also fails.

Eight consecutive shared-root start attempts failed on this while sibling
lanes launched their own JVMs. The cache content itself is fine — the
digest target already existed (that is WHY the rename found it non-empty).

## Expected shape

A digest-addressed cache prepare must be idempotent and concurrent-safe:
if the destination digest directory already exists, the prepare is DONE —
discard the staging dir and proceed (first-writer-wins, content-addressed,
konserve-style). Staging dirs are per-process (they already carry a uuid)
and reaped on exit or at the next launch when their owner is dead. No lock
needed; the digest is the coordination.

## Owner

The launcher's cache-prepare step (`bin/seon` → `script/seon/fresh_operator.clj`
dependency-class preparation).

## Acceptance criteria

- N concurrent launches over one warm cache all succeed; exactly one pays
  the build, the rest observe the digest and proceed.
- A pre-existing destination digest directory is success, never
  `Directory not empty`.
- Stale staging dirs from dead owners are reaped at the next launch.
- One class regression simulating the collision (destination exists,
  staging populated) asserts the launch proceeds.

## Second failure mode of the same race (tool-exercise lane, 2026-08-07 late)

The collision is not only `Directory not empty` at the rename. When a
concurrent builder clears the staging area mid-compile, the losing build
fails INSIDE compilation with an IOException that names a vendored source
file and nothing about the cache:

```text
Syntax error (IOException) compiling fn* at
(clojure/core/async/impl/protocols.clj:9:1).
No such file or directory
```

with `:seon.dev-cache/rejected
"…/target/dev-dependency-classes.next/395b31a9-9a9c-45c0-b201-00f35cab175d"`.
The vendored file was present and readable the whole time and the disk had
875 GiB free — the missing directory is the staging output, deleted under
the compiler.

Reproduced twice while a sibling lane's `bin/test` held the same shared
cache. What the operator prints is only `✗ The dependency class cache could
not be prepared.` plus a `/var/folders/**` temp path, so the real cause
takes two extra hops to reach. Whatever fix lands should also surface the
child's stderr on the operator's own output — a launch blocked by another
lane's build should say so in one line.
