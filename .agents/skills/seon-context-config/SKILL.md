---
name: seon-context-config
description: "Change or diagnose Seon's fresh database-backed cluster configuration. Load this when editing config/default.edn, adding a config dial to resources/seon/schema.edn, applying a sparse cluster overlay, tracing a runtime config read, or deciding whether a change is live versus arm-time. Also load it when old instructions mention config/system.edn, SEON_CONFIG, context-block manifests, routes, or skill corpus so you do not restore the deleted pod model."
---

# Seon cluster configuration

Fresh configuration is one compiled and reconciled database row per cluster.
It is not the deleted pod's Aero-style `config/system.edn` manifest.

Read these current owners:

- the config section of `resources/seon/schema.edn` — registered config attributes;
- `config/default.edn` — one shipped decision for every dial;
- `src/seon/config.cljc` — read, validate, compile, reconcile, and query;
- `src/seon/cluster.clj` — boot ordering and consumers; and
- `script/seon/fresh_operator.clj` — operator apply/start commands.

Treat `config/system.edn`, `SEON_CONFIG`, and `src-old/seon/config.cljs` as
historical quarry only.

## Author a dial

Configuration design and database schema design are one act:

1. Declare the namespaced attribute and value schema in the config section of
   `resources/seon/schema.edn`.
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

The current `config apply` grammar and live-cluster prepl operation are
`script/seon/fresh_operator.clj:1690-1722`; `bin/seon:4-7` routes the command
to that operator. Process records, advertisements, discovery, and lifecycle
are scoped by the explicit operator root; use `bin/seon --root PATH ...` for a
separate deployment (`bin/seon:4-18`;
`script/seon/fresh_operator.clj:44-76,604-619,1690-1722`).

`seon.config/apply!` exact-reconciles the desired row
(`src/seon/config.cljc:237-252`). Runtime code reads the effective ordinary
map from a database value with `seon.config/effective`
(`src/seon/config.cljc:254-275`).

Verify the resulting datoms and consumer behavior. File contents are desired
input; the database row is runtime truth.

## Trace the acquisition boundary

Applying a row does not magically rebuild a proc, executor, web server, or
other process-local structure.

`test/seon/config_application_test.clj:17-150` owns the recurring census
mechanism: it compares the exact default-manifest key set with an application
ledger and fails on any missing or extra consumer row. Treat that equality
check as the gate; never copy its momentary count into this skill. Read the
source boundary too; the acquisition matrix is:

| acquisition | dials | source and update truth |
|---|---|---|
| boot-time process structure | flow queue depth/concurrency; web port | the work launcher and web server capture them while the cluster starts; applying facts does not rebuild either (`src/seon/cluster.clj:940-971,1357-1370`) |
| operator start/add instrumentation | core-error mode and result caps | the operator reads one running cluster's effective row when it applies process-global host-Var instrumentation; `config apply` itself only reconciles the database row (`script/seon/fresh_operator.clj:1335-1415,1690-1722`) |
| graph-arm-time loop handle | result caps, eval time limit, message-chain limit; one copy of recurrence/escalation and core-error mode | `loop-handle` reads one effective map and carries these values into agent graphs (`src/seon/cluster.clj:1043-1077,1118-1125`) |
| per-episode pass | maximum runs per episode | the work derivation queries the current database value, so the next pass sees the change (`src/seon/cluster/work.cljc:424-441`) |
| per-turn | every registered AI setting plus the agent overlay | the `:call` branch resolves both from one immutable database value once per turn; a config apply or override changes the next turn, never the attempts already derived for this turn (`src/seon/cluster/loop.cljc:975-989`) |
| per-terminal evaluation | session-value blob threshold | `store-session-values!` reads the threshold from the current database immediately before terminal transaction data is committed; the next terminal evaluation sees an applied change (`src/seon/cluster/loop.cljc:432-458,1411-1424`) |
| per-render pass/request | render coalescing; data-drill collection page size | the render proc queries coalescing before each pass; `/data` reads page size from the request's database value (`src/seon/render/web.clj:473-480,636-662,1126-1145`) |
| explicit walk fallback | result caps | an agent walk normally receives the arm-time caps in its ambient context; only a call lacking those caps re-reads the current cluster config (`src/seon/render.clj:169-211`) |
| program-row installation | core-error mode and result caps | installing a contracted interpreted function reads the committed row's database value before wrapping it, both on cold acquisition and after a successful terminal transaction (`src/seon/sci/eval.clj:762-786,789-895`) |
| per-fault | recurrence/escalation facts and the fan-out's core-error decision | fault commit reads recurrence/escalation from the fault's database value, while the fan-out callback reads `on-core-error` again for each fault (`src/seon/cluster.clj:1000-1034,1151-1164`) |

`:seon.config/on-core-error` is deliberately split: the loop handle carries
the arm-time value into eval requests (`src/seon/cluster.clj:1051-1067`;
`src/seon/cluster/loop.cljc:1287-1302`), while flow-fault fan-out re-reads it
per fault (`src/seon/cluster.clj:1151-1160`). Never give that dial one blanket
“live” label.

Applying AI settings is next-turn live, including per-agent overrides. The
real-provider proof changed the same running worker's next request without a
PID or graph change
(`docs/prds/sci-execution-runtime/research/ai-settings-live-proof-2026-08-01.md`).
For any new dial, update the registered application ledger, cite the exact read
site, and test that boundary. Do not add polling or a generic config-change
dispatcher.

## Context and UI are not config manifests

The deleted manifest seeded context blocks, namespace policy, skill corpus,
routes, and render caps. Fresh Seon does not expose that model.

Current context is one visible walk, namespace pages are canonical Reitit
routes, and agent/namespace debug variants render the AI and HTML projections
of that walk (`src/seon/render/route.clj:1-34`;
`src/seon/render/web.clj:1041-1087,1198-1220`). These are derived runtime
behavior, not config manifests. Generalized canvas controls and a `/call`
action boundary remain absent from the current route table
(`src/seon/render/route.clj:1-34`).

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
