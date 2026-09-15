---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, test, class/n4, wave/operator-launch-concurrency]
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

## The catch is for the wrong exception (namespace-steward lane, 2026-09-07)

Root cause located, and today it is a total block on `bin/test`, not
friction. `dev_cache.clj:353-366` (`admit!`) moves the staging directory
onto the destination and catches exactly `FileAlreadyExistsException`:

```clojure
(catch FileAlreadyExistsException _
  nil)
```

On APFS the JVM raises `DirectoryNotEmptyException` for a non-empty
destination instead, which that catch does not cover, so the intended
"destination already exists = success" path never runs and the launch dies
with the `Directory not empty` message quoted above.

Observed 2026-09-07 12:0x–12:4x: five consecutive `bin/test
seon.render.web-test` invocations failed identically on
`dev-dependency-classes/325a05bec52908404bd81b3e32284826e9bbeac99cac9206a5b52a8236da1158`.
That destination is pinned by three live cluster JVMs (`ps aux | grep java`
matches pids 93646/93653/93654), so deleting it by hand is not available
either — the fix has to be the catch. Three `bin/test` runs earlier in the
same session succeeded against a different cache digest, so the wedge
begins the moment a live JVM holds the digest directory that the next
prepare recomputes.

Why a rebuild recomputes a digest that already exists: the cache digest
is `[cache-version dependency-digest project-source-digest]`
(`dev_cache.clj:286-288`), so a colliding destination is BY CONSTRUCTION
the cache this run wants. The freshness check does not scan for it — it
reads the single selection/result file, which `b/delete` removes at the
start of every prepare (`dev_cache.clj:398`), so a concurrent run's prepare
makes the next one report "inputs changed" for a cache that is on disk and
valid.

Suggested fix, in the shape this note already asks for: catch
`DirectoryNotEmptyException` (a `FileSystemException`) alongside
`FileAlreadyExistsException` in `admit!` and fall through. Nothing stale is
served by doing so — `prepare!` already validates the destination with
`valid-cache` against the current project digest immediately after
`admit!`, and raises when it does not match.

## 2026-09-07 recurrence: a git worktree alone reproduces it, no concurrency

A short-lived `git worktree` used for a baseline `bin/test` run resolved
`reference-code/` through a symlink, so the dependency closure hashed to a
different digest. Every `bin/test` in that worktree wrote
`target/dev-dependency-cache-current.edn` in the SHARED tree (the worktree's
`target/` is not private). Back in the main checkout the closure hashed to its
own digest again, whose directory already existed and was complete — and the
prepare failed exactly as this note describes:

```
Execution error (FileSystemException)
target/dev-dependency-classes.next/<uuid> ->
target/dev-dependency-classes/325a05be…: Directory not empty
bin/test: dependency cache freshness check failed
```

Two `bin/test` invocations in a row died there with no live JVM holding
anything. Manual repair: delete the staging directory and rewrite
`dev-dependency-cache-current.edn` to the existing complete digest. So the
failure needs neither concurrent launches nor a pinned digest — an existing
valid destination is enough, which is the catch this note already asks for.

Second, smaller finding: `dev-dependency-cache-current.edn` is a single
shared file that any checkout sharing this `target/` overwrites, so one
worktree's dependency layout silently invalidates the main tree's cache.

## Resolution (2026-09-15 triage)

Basis: `7e35df2131c71f476a85c6a38bfc8eb292cb36f5` (committed source; concurrent working-tree edits excluded).

Commit `bbeb6f651` accepts a populated cache destination; HEAD `dev_cache.clj:369` (`admit!`) catches both `FileAlreadyExistsException` and `DirectoryNotEmptyException`. `current-cache` scans valid digest directories instead of trusting only the selection file; `with-cache-lock` serializes builds; `refresh!` deletes only its own UUID staging directory in `finally`. Commit `9a8189cbc` removes source-URL locations from dependency hashes and `f2e3bcb34` keys classes only by dependency configuration, pins and JDK. Verified with `git show HEAD:dev_cache.clj` and `git log --oneline -- dev_cache.clj`; no concurrent rebuild was induced.

surface: runner-gate
