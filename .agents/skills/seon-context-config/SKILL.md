---
name: seon-context-config
description: "Change or diagnose Seon's fresh database-backed cluster configuration. Load this when editing config/default.edn, adding a resources/seon/schema config dial, applying a sparse cluster overlay, tracing a runtime config read, or deciding whether a change is live versus arm-time. Also load it when old instructions mention config/system.edn, SEON_CONFIG, context-block manifests, routes, or skill corpus so you do not restore the deleted pod model."
---

# Seon cluster configuration

Fresh configuration is one compiled and reconciled database row per cluster.
It is not the deleted pod's Aero-style `config/system.edn` manifest.

Read these current owners:

- `resources/seon/schema/config.edn` — registered config attributes;
- `config/default.edn` — one shipped decision for every dial;
- `src/seon/config.cljc` — read, validate, compile, reconcile, and query;
- `src/seon/cluster.clj` — boot ordering and consumers; and
- `script/seon/fresh_operator.clj` — operator apply/start commands.

Treat `config/system.edn`, `SEON_CONFIG`, and `src-old/seon/config.cljs` as
historical quarry only.

## Author a dial

Configuration design and database schema design are one act:

1. Declare the namespaced attribute and value schema under
   `resources/seon/schema/*.edn`.
2. Add its explicit shipped decision to `config/default.edn`.
3. Read the value from `seon.config/effective` at the owning runtime boundary.
4. Prove whether the consumer reads live database values or captures the value
   during arming/start.

`seon.config/default-decisions` refuses missing and unknown defaults
(`src/seon/config.cljc:137-168`). Do not add an environment-variable read at
the consumer or a second config registry.

## Compile a sparse overlay

An overlay is one plain EDN map. Omitted keys inherit shipped defaults.
Unknown keys and invalid values are refused
(`src/seon/config.cljc:104-135,170-229`).

Precedence is:

```text
shipped defaults → selected sparse overlay → explicit typed environment map
```

The environment map is a caller-supplied typed bootstrap input, not an
invitation for runtime code to read ambient environment variables.

Use `:seon.config/absent` to retract an optional defaulted attribute. The
compiler refuses it for a required attribute and never stores the marker or
nil (`src/seon/config.cljc:31-37,183-229`).

## Apply and inspect

Start a cluster with an overlay:

```text
bin/seon start CLUSTER --config path/to/overlay.edn
```

Apply an overlay to a live cluster:

```text
bin/seon config apply CLUSTER path/to/overlay.edn
```

The current operator grammar is
`script/seon/fresh_operator.clj:705-730`; `bin/seon:4-7` routes `start` and
`config` to it.

`start` discovery is not currently scoped to the operator root. Before
accepting a start result, confirm that its pid and store path belong to the
intended root; otherwise stop at the boundary
(`docs/seon/issues/operator-start-discovers-jvms-from-other-roots.md`).

`seon.config/apply!` exact-reconciles the desired row
(`src/seon/config.cljc:237-252`). Runtime code reads the effective ordinary
map from a database value with `seon.config/effective`
(`src/seon/config.cljc:254-275`).

Verify the resulting datoms and consumer behavior. File contents are desired
input; the database row is runtime truth.

## Distinguish live and arm-time consumers

Applying a row does not magically rebuild a proc, executor, web server, or
other process-local structure.

The July 29 proof found:

- the maximum-runs-per-episode dial is read from current facts and applies on
  the next episode;
- render coalescing reads current facts and applies on the next pass; and
- structural values captured during boot/arming need their owning topology or
  process operation before they change.

Read the exact matrix and probes in
`docs/prds/sci-execution-runtime/research/config-application-proof-2026-07-29.md`.
For a new dial, document its acquisition boundary in the owning code and test
that boundary. Do not add polling or a generic config-change dispatcher.

## Context and UI are not config manifests

The deleted manifest seeded context blocks, namespace policy, skill corpus,
routes, and render caps. Fresh Seon does not expose that model.

Current context/render design is still being settled, and broader UI
restoration is tabled by ruling 12
(`docs/prds/sci-execution-runtime/plan/README.md:1087-1097`). Read
`docs/seon/architecture/context.md` and `docs/seon/architecture/ui.md` as
target documents before proposing new context or UI dials.

Do not place route trees, prose blocks, namespace allowlists, skill scans, or
rendered output into fresh configuration merely because the old manifest did.
First identify the surviving database facts and deriving owner.

## Durable rules

- Use fully namespaced config attributes.
- Keep defaults complete and overlays sparse.
- Refuse unknown keys.
- Store absence by omitting the datom, never by storing nil.
- Read runtime decisions from the database.
- Apply one reconcile mechanism through `seon.config/apply!`.
- Make structural acquisition explicit; do not claim an applied row rewired a
  live graph.
- Mine old manifests for requirements only, never for fresh structure.
