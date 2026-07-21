---
type: research
status: active
tags: [research, config, database, runtime, acme]
---

# Live config apply boundary — 2026-07-14

## Dependency ledger

- Aero at pinned submodule SHA `c47a10fa3830d3bcf6e4545a75cdbb1fe8d38d5d`:
  `reference-code/aero/src/aero/core.cljc` owns manifest reading, tag dispatch,
  and relative include resolution. `seon.config/read-config-file` is Seon's one
  configured reader seam.
- Datahike through the maintained root dependency and `seon.db`: database
  writes use the serialized writer protocol; `seon.state/reconcile!` owns the
  exact managed-subset compiler and full-head fence.
- Reitit through the maintained CLJS dependency:
  `src/seon/web/router.cljs` owns the one pod route vector, same-origin
  middleware, and Node-to-Ring adapter. The operator door is one static
  supplement until operator routes become database route facts.
- Babashka process/fs plus system `curl`:
  `script/seon/dev/cli.clj` already owns target selection, readiness, locking,
  and user-facing command results.
- Existing call sites and proofs:
  `seon.client/boot-seed!`, `seon.state-test`, `seon.web.router-test`, and
  `seon.dev.cli-test` demonstrate declarative config reconciliation, basis
  stable no-op behavior, injected static handlers, and operator locking.

## Observed failure

Two consecutive `bin/acme config apply config/acme.edn` operations on the
isolated `acme-agentic-tool-refinement` cluster ran the full artifact builder
and replaced the writer and pod. The database desired state was already
converged. The public config command called `reconcile-development!`, so it had
no operation-scoped live boundary.

The first focused convergence proof exposed a second defect. The desired
config singleton included `:seon.agent.web/allowed-domains []`. Datahike
correctly represented that empty cardinality-many value as attribute absence,
but the exact compiler repeatedly emitted a map addition and committed only
transaction metadata. Desired maps now canonicalize impossible empty
cardinality-many presences to database absence before retaining strict
presence-sensitive comparison for every stored attribute.

Focused router selection then exposed recovery response schemas coupled to
accidental namespace load order. The response collections now use their shared
value shapes, while agent/run/turn namespaces remain the sole owners of stored
identity attributes.

## Result

The live pod owns one `apply-config!` operation used by both boot and the
operator endpoint. It resolves an explicitly selected manifest once through
the same Aero reader, computes routes, skills, and singleton facts, and calls
the existing provenance-scoped `seon.state/reconcile!`. The operator requires
an already-ready compatible target and never widens failure into `up`.

Focused evidence:

- `seon.state-test`: 7 tests, 35 assertions;
- `seon.runtime.recovery-test`: 2 tests, 29 assertions;
- live-config convergence: 1 test, 4 assertions;
- operator config route: 1 test, 2 assertions; and
- `seon.dev.cli-test`: 7 tests, 20 assertions.

After one intentional ACME rebuild, two unchanged applies returned
`changed: false`, zero operations, and basis `536870999`. A temporary manifest
changed the batch limit from 100 to 101 and returned `changed: true`, two
operations, and basis `536871000`; restoring `config/acme.edn` returned two
operations at basis `536871001`, and the next apply was again a zero-operation
no-op. Watcher PID `81044`, writer PID `81308`, and pod PID `81335` remained
unchanged throughout all four live operations.
