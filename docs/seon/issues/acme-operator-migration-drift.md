---
type: issue
status: open
severity: friction
tags: [issue, component, agent, database, web]
---

# ACME cannot migrate safely through the current operator

## Problem

The ACME downstream harness still composes the removed shell-supervisor
contract, old bundle topology, legacy database directory, and retired UI
surface vocabulary. It cannot be started or migrated through the current
`bin/seon` operator without either failing or silently opening a different
empty database.

This blocks ACME migration and its downstream acceptance work. It does not
block the already-working default cluster.

## Evidence

The lifecycle audit explicitly listed `bin/acme` among the direct callers that
had to move from named process operations to the semantic operator only after
the default cluster was proven. The current cutover is complete in `bin/seon`:
its command dispatch accepts `up`, `down`, `restart`, `status`, `logs`, and the
other current operations, but no `start`, `stop`, `tail`, or `wire-server`
process command.

`bin/acme` still:

- implements `up` as `build`, `start wire-server`, `start pod`, and implements
  `down`/`stop` with removed named-process commands;
- one-off compiles `:acme-client` to `out-acme/client/main.js`, exports that as
  `SEON_CLIENT_OUT`, and warns against a second watcher, while the current
  operator owns one managed Shadow watcher and derives its process/artifact
  graph as a unit;
- documents and runs the writer against `data/clusters/acme/store`, while the
  current operator derives `data/clusters/<name>/db`; the present checkout has
  the legacy `store` and no ACME `db`, so a naive `up` would not reopen the
  existing evidence database;
- retains `tile`, `live tile`, `agent-tile`, `error-tile`, and
  `steps-tile-html` names across `bin/acme`, `config/acme.edn`, and `acme/`.
  `config/acme.edn` still points at
  `seon.agent.ctx.typeahead-steps/steps-tile-html`, which has no current source
  definition.

Read-only live inspection on 2026-07-14 found two preserved downstream
clusters still running the pre-cutover mechanism:

- the stable checkout's writer/pod run from `/Users/sean/src/seon-stable` as
  `seon.server.boot --path data/clusters/acme/store` plus
  `node out-acme/client/main.js`;
- the display-v3 checkout has the same legacy writer/bundle shape from
  `/Users/sean/src/seon-display-v3` on its separate ports/database.

Both writers use the old Java 25 source launch rather than the current Java 26
database-server artifact. Their running processes and legacy databases are
evidence to preserve, not state that the default operator may adopt, reset, or
overwrite.

The 2026-07-14 post-MCP re-audit pinned the live owners precisely:

- `/Users/sean/src/seon-stable`: writer PID 30873 on 7981 and pod PID 31038
  on 7980, with about 44 MB under `data/clusters/acme`;
- `/Users/sean/src/seon-display-v3`: writer PID 45003 on 7983 and pod PID
  52189 on 7982, with about 4.2 GB under `data/clusters/acme`.

There is also an artifact-integrity trap in a naive wrapper-only migration.
The current artifact builder always compiles/fingerprints build id `client`,
but `SEON_CLIENT_OUT=out-acme/client/main.js` changes the required entry-file
path. Because a legacy `out-acme` file exists, simply delegating to current
`bin/seon up` could publish a hybrid manifest containing the current default
client closure and a stale ACME entry bundle. Artifact flavor/build id must be
one piece of operator data; output substitution is not sufficient.

This is distinct from `acme-no-sci-eval-seam.md`: that issue concerns direct
SCI access after a cluster exists; this issue owns lifecycle, artifact,
database, and active consumer-surface migration.

## Owner

The ACME downstream boundary: `bin/acme`, `config/acme.edn`, `acme/`, and the
current operator's one target/artifact/process graph. Core operator semantics
remain owned by `script/seon/dev/*`; ACME adapts to them without restoring named
process commands or a parallel supervisor.

## Acceptance

- Coordinate and drain the stable and display-v3 ACME process groups through
  their actual owning checkouts before any migration. Never reset, delete,
  rename in place under a live writer, or let the current operator create a
  fresh `db` and present it as the migrated legacy `store`.
- Preserve both legacy databases and blob trees byte-for-byte before changing
  paths. Reopen or explicitly migrate each through one documented,
  non-destructive Datahike/database-server transition, then prove representative
  agent, plan, eval, and program facts plus blob reads before retiring the old
  copy.
- `bin/acme up`, `down`, `restart`, `status --edn`, `logs`, and cluster reset
  compose the current semantic operator and its ownership fences. They use one
  isolated ACME process namespace, ports, sockets, artifact manifest, watcher,
  Java 26 database server, and CLJS pod; no removed named-process command or
  second Shadow lifecycle remains.
- If selected ACME ports or database paths are held by preserved worktree
  processes, current-operator status reports the foreign ownership conflict;
  it must not report merely `down`, overwrite discovery files, or start a
  competing cluster.
- The managed artifact includes the downstream source/preload and is invalidated
  by core, overlay, dependency, and build-config changes. The pod executes that
  exact published artifact rather than a presence-only `out-acme` bundle.
- Active ACME config/source uses block, render, surface, canvas, card, slot,
  page, web UI, and database/db vocabulary, with every renderer symbol resolving
  through the current public boundary.
- Behavioral proof starts ACME beside the ready default cluster, verifies the
  two process/database namespaces do not cross-talk, exercises its overlay and
  routes in a browser/feed, restarts it without data loss, and confirms a scoped
  reset cannot touch the default or preserved pre-migration databases.
