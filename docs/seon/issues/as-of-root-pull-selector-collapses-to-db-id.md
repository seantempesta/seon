---
type: issue
status: open
severity: blocker
tags: [issue, render, database, context]
---

# Derive the root pull selector through Datahike's database interface

## Problem

An agent prompt rendered from its run's opening database value collapses to a
generic `db/pull` form and `#:db{:id ...}`. The root walk reads the installed
schema as `(:schema database)`, but a Datahike `AsOfDB` exposes schema through
the `IDB` interface rather than as a record field. The resulting empty schema
map constructs the selector `[:db/id]`, making every other root attribute and
connection unrepresentable to the walk.

## Evidence

- The complete `bin/test --all` run retained at
  `tmp/test-runs/run.a3WRAZ` failed both
  `a-run-prompts-from-its-opening-database-value` and
  `refused-terminal-program-transactions-settle-and-do-not-refire` with the
  generic prompt `user=> (db/pull db '[*] 27772)` followed by only the entity
  id.
- Both failures reproduce by direct in-JVM invocation, so this is not the
  parallel isolation-sensitive class.
- A direct probe after opening the run observed
  `seon.render.walk/root-acquisition` return selector `[:db/id]`, root
  `#:db{:id 27772}`, and no connections. An ordinary wildcard pull from the
  same opening database value returned the agent id, namespace, and open-run
  connection.
- On that same sparse root value, schema matching correctly selects
  `:seon.cluster.agent/agent`; the attributes have already been lost before
  renderer selection.
- `src/seon/render/walk.clj:68-72` derives installed attributes from the map
  key, and `src/seon/render/walk.clj:326` keys the compiled selector by that
  same value. Datahike's maintained `AsOfDB` implements `dbi/IDB -schema` by
  delegating to its origin while exposing only `origin-db` and `time-point` as
  record fields (`reference-code/datahike/src/datahike/db.cljc:567-605`).
- `test/seon/schema_usage_guard_test.clj:453` already pins that an as-of
  database value's schema is read through `dbi/-schema`.

## Owner

`seon.render.walk/root-selector`, its acquisition helpers, and the compiled
pull-plan schema identity. The repair belongs to the protected render owner;
the lifecycle lane did not edit it.

## Acceptance

- Root acquisition over both current and as-of database values derives the
  same complete selector from Datahike's database interface.
- An opening-database agent root retains its id, namespace, open run, and
  reachable trigger/message connections.
- Both affected turn tests render the recorded opening message rather than the
  generic value floor.
- One regression proves the class at the root-selector boundary for current
  and as-of database values, not only the two observed prompts.
