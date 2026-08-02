---
type: issue
status: open
severity: cleanup
tags: [issue, flow, schema, config, architecture]
---

# Give Flow configuration dials one registration owner

## Problem

The fresh `seon.flow` namespace and old `seon.config.resolve` both register the
same two Flow configuration attributes. The old writer aliases put both source
trees in one JVM, and `schema/register!` silently associates the last form for
a key.

The shapes happen to match today, but load order becomes the authority as soon
as either copy changes.

## Evidence

- `src/seon/flow.clj:408-425` registers and enumerates
  `:seon.config.flow.compute/queue-depth` and
  `:seon.config.flow.compute/concurrency`.
- `src-old/seon/config/resolve.cljc:341-365` registers and enumerates the same
  attributes.
- `src/seon/schema.cljc:739-813` ends registration with an `assoc`; it does not
  reject a conflicting existing form.
- `clojure -Spath -M:writer:host:writer-test` includes both `src` and
  `src-old`.
- Requiring `seon.flow` and then `seon.config.resolve` in that alias succeeded.
  The before/after forms were equal, proving there is no current shape
  mismatch, but also proving both owners execute in one registry.

This violates the one-mechanism rule and makes a fresh owner vulnerable to
silent quarry overwrite.

## Owner

`seon.flow` owns the surviving dial schemas. The old config resolver should
consume those keys without registering copies, or old configuration should be
kept in a process/classpath that cannot load the fresh owner twice.

## Acceptance

- One source location registers each Flow dial on every alias combination.
- The old writer/host test alias loads without duplicate registration.
- A conflicting duplicate declaration fails loudly at the global schema
  population gate rather than winning by load order.
- The acquired values and descriptions remain identical to the current
  queue-depth and hardware-core defaults.

## Triage 2026-07-27

- **OPEN-DEFERRED.** Fresh ownership has moved to
  the FLOW section of `resources/seon/schema.edn`, but the duplicate declarations remain only in
  quarry configuration at `src-old/seon/config/resolve.cljc:343-365`; the
  explicit old-system alias at `deps.edn:75-84` is the remaining collision
  surface and dies with that machinery.

## Triage 2026-07-29

**REAL-BUT-QUEUED — config-aero quarry cleanup.** The collision remains only
when an explicit quarry alias loads both generations. The mining report names
the two visible config generations; patching is frozen and this dies with the
quarry rather than receiving an independent fix plan.

## Schedule 2026-07-29

**FUTURE — `config follow-up`.** Commit `68a5de51f` compiled one effective
manifest but did not remove the duplicate quarry declarations:
`src-old/seon/config/resolve.cljc:343-365` still declares both Flow dials, and
the explicit `:writer` alias in `deps.edn` still loads `src` plus `src-old`.

## Backlog triage 2026-08-02

**Still real only inside the old writer reader closure.** Fresh ownership is
now the schema resource, while the root writer/build aliases still make
`src-old/seon.config.resolve` executable beside it. The destination is the rot
deletion wave that removes those live quarry readers; this must not receive an
independent fresh config mechanism or a compatibility fix.
