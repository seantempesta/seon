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

## Partial implementation — 2026-07-14

The shared operator now derives an explicit artifact flavor before it builds.
The default flavor retains `client`, `.shadow-cljs`, `out/client/main.js`, and
`artifact.edn`. The ACME flavor selects `acme-client`, `tmp/shadow/acme`,
`out-acme/client/main.js`, and `artifact-acme.edn` as one data record. Artifact
manifest version 2 publishes those coordinates and includes them in the
application digest; a mismatched `SEON_CLIENT_OUT` is rejected instead of
forming a hybrid manifest. Legacy version 1 manifests are accepted only as the
default flavor. `bin/acme` now declares the ACME flavor explicitly.

Focused operator tests prove distinct cache/build/output/manifest identities,
the unchanged default Shadow command, the isolated ACME Shadow config merge,
hybrid-output rejection, and default-only legacy-manifest upgrade. The complete
operator checkpoint passes 89 tests and 562 assertions. Read-only
`bin/seon status --edn` still reports the default cluster ready through the
legacy-manifest read path.

This does not make ACME safe to start. Its wrapper still composes retired named
process commands, and the process graph still derives the default watcher.
Isolated one-off build sequencing, foreign-owner detection, legacy database
disposition, lifecycle migration, and live proof remain acceptance work below.

## Operator migration slice — 2026-07-14

The wrapper now contributes only ACME target data and delegates `up`, `down`,
`restart`, `status`, `logs`, and scoped `cluster reset acme` to the shared
semantic operator. Retired `build`, `compile`, `start`, `stop`, and `tail`
commands fail with migration guidance instead of reconstructing named process
operations. The shared process graph derives its watcher build and readiness
from the artifact flavor: ACME watches `acme-client` plus `test` in
`tmp/shadow/acme`, with its downstream source and preload, while the default
watcher command remains unchanged.

The operator now refuses both `up` and reset when a preserved legacy `store/`
exists without the current `db/`; it cannot create an empty sibling database
and call that migration. Structured status reports the configured cluster and
database path, canonical artifact flavor/digests, owned process identities,
and dynamically discovered web, CLJ, and CLJS endpoints. A listener without a
matching live PID/start record is `:seon.dev.process.status/foreign`, making the
target an ownership conflict rather than down. Read-only probes against both
preserved port pairs (7980/7981 and 7982/7983) report the writer and pod as
foreign while naming the legacy database conflict. The default target remains
ready and publishes its actual dynamic ports.

Focused proof passes 15 operator tests/51 assertions. The complete operator
checkpoint passes 92 tests/574 assertions. No preserved process or database was
mutated.

ACME is still not safe to start. The stable and display-v3 process groups and
databases need the preservation manifest's coordinated archive, drain, reopen,
and read-back sequence. The active downstream source/config cleanup has removed
the stale `steps-tile-html`, canvas `error-tile`, and `.seon-tile` references in
favor of `steps-surface-html`, the error card, and `.seon-card`; the exact
`acme-client` compile exposed the undeclared renderer before that repair.
The repaired isolated compile completes with zero warnings. Its generated
`out-acme/client/main.js` is no longer tracked: the flavor manifest and build
graph are the artifact authority, while `.gitignore` already classifies the
output tree as reproducible build state.
Browser/feed proof still remains. The operator intentionally does not claim an
Inspect-style per-sample token lease: concurrent target-coordinate allocation,
pinned frozen artifacts, and idempotent token-fenced release remain owned by
the Inspect issue.

## Default broken surface — 2026-07-14

Server-side gzip SSE proof found that ACME's root route returned a valid
Datastar frame containing only the deliberate `acme.widget/broken-surface`
failure. `acme.context/install-into!` installed that fixture both as the focal
canvas and as an unconditional supporting block for every live agent. That
made the downstream product's normal page an error demonstration rather than
a healthy customization example.

Normal ACME startup now pins `acme.widget/dash` and installs only healthy
supporting surfaces. The throwing renderer and downstream `error-response`
override remain available for explicit failure tests; they are no longer
installed as default database state. A source rebuild restarted ACME, removed
the already persisted `:acme-broken` component through the canonical
`seon.agent.ctx/remove!` surface, and reached ready beside the default target.
Server-side gzip SSE proof for both `/agent/root/feed` and `/data/feed`
returned Datastar frames with no broken-surface or render-error text; the root
canvas rendered `Acme dashboard` with the downstream supporting surfaces.

## Owner

The ACME downstream boundary: `bin/acme`, `config/acme.edn`, `acme/`, and the
current operator's one target/artifact/process graph. Core operator semantics
remain owned by `script/seon/dev/*`; ACME adapts to them without restoring named
process commands or a parallel supervisor.

## Historical canvas value blocks current admission — 2026-07-15

The first agentic-suite restart against ACME's retained development database
booted the writer and pod, replayed all seven agents, and opened port 7994.
Before readiness could stabilize, the root surface derived an explicit
`:seon.render.canvas/content` pin as the string `"acme.widget/dash"`. The
current contract accepts a qualified symbol or literal hiccup, so
`seon.render.surface` recorded a core fault and the configured crash policy
terminated the pod. The operator consequently reported a live watcher and
writer with a dead pod rather than accepting the stale database.

Current `acme.context/install-into!` transacts the qualified symbol
`'acme.widget/dash`; the mixed-`:or` database bridge stores its `pr-str` and
decodes it on read. The malformed retained value therefore predates the
current write contract or was double-encoded by an earlier implementation. It
must not be coerced at render time: the strict crash is evidence that the
database is incompatible with current source.

Acceptance for the disposable harness is a scoped `bin/acme` reset followed by
a fresh boot, exact raw/decoded pin read-back, and a ready dashboard. If the
fresh database reproduces the string, the active EDN write path is the owner
and must be fixed before evaluation. A retained production database needs an
explicit, provenance-bounded reconciliation transaction; absence or an invalid
value may not be silently rewritten during ordinary boot.

The scoped reset then rebuilt the current writer, ACME client, bootstrap, and
database and reached ready at port 7994. The fresh root pin reads as raw storage
string `acme.widget/dash`, decodes to the qualified symbol
`acme.widget/dash`, and carries distinct string/symbol runtime types at database
basis 536870934. Both downstream context installs completed. This falsifies a
current bridge regression and localizes the crash to retained incompatible
data; the strict admission behavior and explicit reconciliation requirement
remain correct.

## Duplicate development-cluster identity — 2026-07-14

The agentic-tool-refinement lane started a current ACME target from a dedicated
worktree on port 8094 while an older current-layout ACME target remained live
from the main checkout. Both advertised cluster basename `acme`. An
`eval_cljs` request for `acme/root` selected the main-checkout pod at PID 84892
and cwd `/Users/sean/src/seon`, not the lane pod. Port, process, database, and
Shadow isolation were therefore insufficient for deterministic development
addressing when the wrapper forced `SEON_CLUSTER_DIR=data/clusters/acme`.

`bin/acme` now retains that path as its default but honors an explicit
`SEON_CLUSTER_DIR`. Starting the lane with
`data/clusters/acme-agentic-tool-refinement` published the distinct cluster
identity. The repository MCP server then resolved
`acme-agentic-tool-refinement/root` to PID 74241 and cwd
`/Users/sean/src/seon-acme-agentic-tool-refinement`. This closes the local
single-lane addressing need; general concurrent sample allocation and token-
fenced ownership remain with `inspect-live-cluster-caller-drift.md`.

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
