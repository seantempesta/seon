---
name: seon-context-config
description: "Change or diagnose Seon's fresh database-backed cluster configuration. Load this when editing config/default.edn, adding a config dial under resources/seon/schemas/, applying a sparse cluster overlay, tracing a runtime config read, or deciding whether a change is live versus arm-time. Also load it when old instructions mention config/system.edn, SEON_CONFIG, context-block manifests, routes, or skill corpus so you do not restore the deleted pod model."
---

# Seon cluster configuration

Fresh configuration is one compiled and reconciled database row per cluster.
It is not the deleted pod's Aero-style `config/system.edn` manifest.

Read these current owners:

- config-family files under `resources/seon/schemas/` — registered config attributes;
- `config/default.edn` — one shipped decision for every dial;
- `src/seon/config.clj` — read, validate, compile, reconcile, and query;
- `src/seon/cluster.clj` — boot ordering and consumers; and
- `script/seon/fresh_operator.clj` — operator apply/start commands.

Treat `config/system.edn` and `SEON_CONFIG` as historical referents only. The
old source trees are available through `git show` and `git log`, not an in-tree
checkout (`AGENTS.md:247-254`).

## Author a dial

Configuration design and database schema design are one act:

1. Declare the namespaced attribute and value schema in the config section of
   the owning config-family file under `resources/seon/schemas/`; the loader
   merges that directory as one population (`src/seon/schema/edn.clj:1-15,49-51`).
2. Add its explicit shipped decision to `config/default.edn`.
3. Read the value from `seon.config/effective` at the owning runtime boundary.
4. Prove whether the consumer reads live database values or captures the value
   during arming/start.

`seon.config/default-decisions` refuses missing or invalid declared defaults
and ignores extra keys under ruling #48 (`src/seon/config.clj:268-305`). Do
not add an environment-variable read at the consumer or a second config
registry.

## Compile a sparse overlay

An overlay is one plain EDN map. Omitted keys inherit shipped defaults. Extra
keys are ignored; declared keys are rigorously validated
(`src/seon/config.clj:169-187,307-348`).

Precedence is:

```text
shipped defaults → selected sparse overlay → explicit typed environment map
```

The environment map is a caller-supplied typed bootstrap input, not an
invitation for runtime code to read ambient environment variables.

Use `:seon.config/absent` to retract an optional defaulted attribute. The
compiler refuses it for a required attribute and never stores the marker or
nil (`src/seon/config.clj:36-42,182-198,317-365`).

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
(`src/seon/config.clj:460-472`). Runtime code reads the effective ordinary
map from a database value with `seon.config/effective`
(`src/seon/config.clj:474-500`).

Verify the resulting datoms and consumer behavior. File contents are desired
input; the database row is runtime truth.

## Trace the acquisition boundary

Applying a row does not magically rebuild a proc, executor, web server, or
other process-local structure.

`test/seon/config_application_test.clj:1-13,27-109,206-222` owns the recurring census
mechanism: it compares the exact default-manifest key set with an application
ledger and fails on any missing or extra consumer row. Treat that equality
check as the gate; never copy its momentary count into this skill. Read the
source boundary too; the acquisition matrix is:

| acquisition | dials | source and update truth |
|---|---|---|
| boot/arm-time structure | flow queue depth/concurrency; web port | the work launcher and web server capture effective facts during cluster start; applying a row does not rebuild them (`src/seon/cluster.clj:1989-2005`) |
| graph-arm-time loop handle | result caps, eval time limit, message-chain limit, recurrence/escalation, core-error mode | `loop-handle` reads one effective map and carries these values into agent graphs (`src/seon/cluster.clj:1607-1646`) |
| per-episode pass | maximum runs per episode | the work derivation queries the current database value, so the next pass sees the change (`src/seon/cluster/work.clj:441-458`) |
| per-turn | every registered AI setting plus the agent overlay | provider settings derive from one immutable database value per turn; a config apply or override changes the next turn (`src/seon/cluster/loop.clj:920-934,1243-1259`) |
| per-terminal evaluation | desk-value blob threshold | `store-desk-values!` reads the threshold immediately before desk rows join the terminal receipt transaction (`src/seon/cluster/loop.clj:464-498,1643-1658`) |
| per-render pass | render coalescing | the render proc queries coalescing before each pass (`src/seon/render/web.clj:606-613,861-873`) |
| per-render request fallback | agent render profile | a request uses its supplied profile or derives the agent profile from current effective config (`src/seon/render.clj:37-74`) |
| program-row installation | schema projection and result caps | successful acquisition and installation advance the live context from the committed database value (`src/seon/sci/eval.clj:762-788`) |

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
- Ignore extra keys until a declaration gives them meaning.
- Store absence by omitting the datom, never by storing nil.
- Read runtime decisions from the database.
- Apply one reconcile mechanism through `seon.config/apply!`.
- Make structural acquisition explicit; do not claim an applied row rewired a
  live graph.
- Mine old manifests for requirements only, never for fresh structure.
