---
type: research
status: completed
tags: [research, orchestrator, mcp, cljs, pod]
---

# Dependency, Shadow, MCP, and ACME operator audit — 2026-07-14

## Decision summary

The repository's dependency split is sound. `deps.edn` should continue
to own JVM and ClojureScript dependency bases, classpaths, JVM options, and
tool entry points. `shadow-cljs.edn` should own build graphs and compiler
options. `bb.edn` plus `script/seon/dev/` should own the fast operator and MCP
adapter dependencies. `bin/seon` should remain a tiny launcher. Process
lifecycle, artifact sequencing, target selection, dynamic ports, and cluster
ownership do not belong in aliases.

The MCP half of the initial audit is now implemented and verified:

- `script/seon/dev/mcp.clj` is the one Babashka server and its dependencies are
  declared in `bb.edn`;
- `eval_clj` uses the writer's stateful `io-prepl` boundary and `eval_cljs`
  uses Shadow nREPL;
- `.mcp.json` and `.codex/config.toml` call the same checkout-relative
  `bin/mcp-server-cljs` launcher while preserving Claude's `seon_cljs` name;
- Shadow and writer REPLs bind port zero and publish their actual ports; and
- focused framing, routing, session, deadline, restart, and live CLJ/CLJS
  proofs are recorded in the roadmap and the archived MCP issue.

ACME and Inspect remain unready for the current operator. Preserved ACME
processes are live from `seon-stable` and `seon-display-v3`, `bin/acme` still
calls removed named-process commands, and Inspect's live cluster callers still
call removed operator verbs or hard-code ports. Those are the remaining
consumer-migration boundary, not evidence that lifecycle belongs in
`deps.edn`.

## Scope and evidence

This audit read the active roadmap and runtime authorities, the current
dependency and build configuration, the operator implementation, MCP scripts,
writer server, ACME wrapper and manifest, Inspect callers, retained lane
research, and the relevant vendored sources under `reference-code/`.

The source-grounded checks included:

- Shadow's nREPL startup, port-file publication, cache-root selection, build
  selection, and runtime-selection implementation;
- nREPL's bencode transport, message IDs, sessions, and `done` status;
- Babashka process's structured command API;
- exact `clojure -Spath` probes for the base, `:writer`, `:writer-test`, and
  `:cljs` bases;
- the installed Clojure 1.12 `clojure.core.server` source for `repl` and
  `io-prepl`.

There is no vendored Clojure CLI or tools.deps source checkout in
`reference-code/`. Alias behavior was therefore verified with the repository's
exact installed Clojure CLI and effective classpaths rather than inferred from
memory. No runtime, process, worktree, or configuration was changed during the
audit.

## Observed live baseline

The initial observation found the default cluster ready under the new
operator. A post-MCP re-audit on the same date additionally confirmed:

- the current `.mcp.json` and `.codex/config.toml` use a portable relative
  launcher and no longer name the deleted `bin/mcp-server`;
- `shadow-cljs.edn` declares `:nrepl {:port 0}` and the writer configuration
  defaults its development REPL to zero;
- stable ACME remains live at HTTP 7980/writer 7981 from
  `/Users/sean/src/seon-stable` (PIDs 31038/30873); and
- display-v3 ACME remains live at HTTP 7982/writer 7983 from
  `/Users/sean/src/seon-display-v3` (PIDs 52189/45003).

All four preserved processes are orphaned under PID 1. Their command lines use
the retired server boot, old dependency aliases and Datahike revisions, Java
25, and `data/clusters/acme/store`. Current source uses the writer uberjar,
`seon.db.server`, Java 26, and a cluster database child named `db`.

This is not merely stale documentation. Starting a new ACME cluster blindly
can collide on ports or create a new empty database beside the old one. The
old processes must be drained or adopted through an explicit ownership and
data decision before `seon-stable` can be retired.

## Dependency target audit

| Owner | Current assessment | Required adjustment |
|---|---|---|
| `deps.edn` base | Correctly small: `src`, `resources`, Clojure, and Malli. | Keep it small. Do not add operator, MCP, or Python harness dependencies. |
| `:writer` | `:replace-paths` and `:replace-deps` create an isolated writer graph with the selected Datahike/Konserve source. Effective classpath excludes Shadow and CLJS. | Keep the isolated basis. Add a writer resource path only if the writer gains a proven resource dependency. |
| `:writer-test` | Correctly extends the writer basis with `test` and `script`. | Keep as the writer gate. |
| `:build` | Correct tools.build boundary; the uberjar basis selects `:writer`. | Keep build entry here, but keep build sequencing in the operator. |
| `:cljs` | Correctly owns compiler/runtime dependencies and its bounded JVM options. Effective classpath includes `src`, `resources`, `test`, and `script`. | Remove Shadow's inert duplicate `:source-paths` only after a clean build proves the deps-mode classpath. Do not remove direct `core.async` without an effective dependency-tree and compile proof. |
| `:lint` | Appropriate tool alias. | No change identified. |
| `bb.edn` | Correct owner for the Babashka operator and unified MCP adapter. It now declares Cheshire, Malli, and nREPL bencode. | Keep these tooling dependencies out of the product bases. |
| `acme/deps.edn` | A downstream local/root dependency with its own `src` is sound. The operator's `-Sdeps` injection correctly contributes ACME source to the classpath. | Keep downstream dependency declaration downstream. Model ACME as an operator artifact flavor, not a Seon alias. |
| `package.json` | Runtime dependencies are appropriate. The Shadow npm package and `client:*` scripts appear redundant because every supported active command enters Shadow through `clj -M:cljs` or `bin/seon`. | Remove the redundant package and stale scripts only after lockfile and clean-build proof. |
| `src-inspect-ai/pyproject.toml` | Correct owner for the Python evaluation harness. | Keep Inspect dependencies out of `deps.edn`; migrate only its operator API usage. |

`shadow-cljs.edn` currently contains `:source-paths`, but Shadow's deps mode
uses the Clojure classpath. The effective classpath probe confirmed the actual
source roots. Retaining a second, inert list invites false confidence that the
two lists are synchronized.

Exact post-change classpath probes showed:

- base: `src` and `resources`, with no Shadow, CLJS, or Datahike;
- `:writer`: `src`, `reference-code/datahike/src-secondary`, and the exact
  maintained Datahike/Konserve revisions, with no test/script roots;
- `:writer:writer-test`: the writer basis plus `test` and `script`;
- `:cljs`: `src`, `resources`, `test`, and `script`, CLJS 1.12.145,
  Shadow 3.4.10, and maintained Datahike/Konserve revisions; and
- ACME `-Sdeps` injection: the same CLJS basis plus only
  `/Users/sean/src/seon/acme/src` from the downstream project.

This confirms that ACME's empty dependency map is valid today. Its own future
Clojure dependencies belong in `acme/deps.edn`; its npm dependencies belong in
a downstream `package.json`/`node_modules` reached through `SEON_EXTRA_NPM`,
not in `deps.edn`.

The `:writer` basis omits the repository `resources` directory. That is
currently consistent with the writer's source and artifact build. It should
not inherit base resources accidentally; a future writer-owned resource should
be added deliberately to the isolated basis.

## Configuration ownership

| Concern | Authority |
|---|---|
| JVM/CLJS libraries, source roots, JVM flags, main/tool entry points | `deps.edn` |
| Shadow build IDs, targets, outputs, preloads, compiler options, build hooks | `shadow-cljs.edn` |
| Operator and MCP adapter libraries | `bb.edn` |
| Desired target/flavor, sequencing, artifact hashes, process identities, port files, logs, sockets | `script/seon/dev/` |
| Executable compatibility entry points | thin `bin/*` launchers |
| JavaScript runtime/build libraries | `package.json` |
| Inspect harness libraries | `src-inspect-ai/pyproject.toml` |

Aliases should describe a reproducible basis, not encode `prep`, warm-up,
compile, CSS, process restart, or cluster selection. Those are ordered artifact
and lifecycle transitions, so the operator remains their sole owner.

## Shadow build and artifact audit

The default development path has several sound properties that should be
preserved:

- one Shadow server watches `client` and `test`, avoiding overlapping CLJS
  suites inside the live pod;
- the node-script client loader is treated with its referenced runtime chunks
  as one closure rather than hashing only `main.js`;
- the warm complete test artifact is published by the watcher, while focused
  one-shot compiles do not publish it;
- the focused test runner checks the exact selection fingerprint;
- `up` builds the selected runtime closure before reconciling processes, which
  matches the active roadmap contract.

The artifact code is nevertheless hard-wired to the default `client` build
and its cache closure. `bin/acme` sets `SEON_CLIENT_OUT` to the ACME output, but
the artifact builder still compiles `client` and hashes the default client
cache. Process planning also always starts a watcher for `client test`. A thin
wrapper change alone would therefore start another watcher from the same
checkout/cache and would not produce a trustworthy ACME artifact.

The operator needs data-driven artifact flavors. A practical split is:

- `default`: build `client`, watch `client` plus `test`, use the default
  isolated cache root and client closure;
- `acme`: build `acme-client` once, do not watch, use a separate cache root and
  `out-acme` closure, and retain distinct artifact/process/log/socket/database
  namespaces.

Shadow's source establishes that nREPL defaults to port zero, publishes the
actual port in `<cache-root>/nrepl.port`, and derives all build state beneath a
configurable cache root. Separate cache roots prevent different worktrees or
flavors from sharing the server port and mutable analyzer/build artifacts.
Build IDs already separate their build directories, but they do not make a
shared Shadow server safe for independently owned worktrees.

## MCP transport audit

The initial finding has been resolved in place. The handwritten executable is
now a thin launcher over `script/seon/dev/mcp.clj`; `bb.edn` declares its
non-bundled dependencies; one JSON-RPC server exposes `eval_cljs` and
`eval_clj`; all diagnostics stay on stderr; and the two client configurations
launch the same checkout-relative command.

The CLJS side retains the justified Shadow nREPL mechanism: bencode messages,
unique IDs, persistent sessions, collection through `done`, explicit runtime
selection, and cluster-qualified agent addressing. The CLJ side uses the
writer's development-only `clojure.core.server/io-prepl`, whose EDN event
framing supports stateful `*1`/`*2`/`*3` sessions without adding nREPL to the
product writer basis. Both transports read current port files on demand and
report process-local session loss honestly after restart.

This solves the default workflow and any cluster/runtime that is actually
owned and advertised by the current operator/Shadow server. It does not by
itself adopt legacy ACME processes compiled from another worktree, and it does
not replace Inspect's need for a structured cluster lease.

Arbitrary eval remains loopback, development-only tooling. It must not become
the writer's typed production administration surface.

## Dynamic ports and ownership

Shadow and the writer now bind their development REPLs to port zero and publish
the actual values. The unified MCP adapter discovers both on each call. The web
UI remains a configurable stable human endpoint.

The stable web UI port 7890 is different: it is a human-facing endpoint and is
reasonable as a configurable default. ACME's 7980 may remain an explicit
downstream override if the operator checks ownership before binding it.

Port-file rules should be:

- namespace every port file by owned cluster and artifact flavor;
- publish only after bind succeeds;
- discover the actual port rather than repeat defaults in clients;
- clear a stale file only after proving the recorded process is not the owned
  live process;
- reject a live foreign owner rather than kill or overwrite it;
- include checkout/artifact identity where multiple worktrees may operate.

The current checkout's MCP registrations are relative. Preserved worktrees may
still contain old absolute registrations; they are historical state to
inventory before removal, not configuration to merge back.

## ACME integration findings

The current `bin/acme` is incompatible with the current `bin/seon`. It calls
removed commands such as `start wire-server`, `start pod`, `stop`,
`restart pod`, and `tail`. The replacement operator supports `up`, `down`,
`restart`, `status`, `logs`, `doctor`, `test`, `skills`, `config`, and the
selected cluster reset operation.

ACME also needs a deliberate artifact and data transition, not merely a syntax
update:

- old live data is under `store`; new cluster layout derives `db`;
- old processes use old code, Java, writer boot, and dependencies;
- a current ACME artifact must compile `acme-client`, not substitute an output
  filename after compiling `client`;
- the ACME build must not create a second watcher in the default Shadow cache;
- the old `seon-stable` processes must be stopped through coordinated ownership
  after deciding whether their data is reset, archived, or migrated.

The artifact mismatch is sharper than a stale command name. With
`SEON_CLIENT_OUT=out-acme/client/main.js`, the current artifact builder still
compiles build id `client` and fingerprints
`.shadow-cljs/builds/client/dev/out/cljs-runtime`, while its required-output
check points at the old `out-acme` file. If that legacy file exists, a naive
wrapper that merely delegates to `bin/seon up` can publish a hybrid manifest:
current default-client closure plus stale ACME entry bundle. ACME therefore
needs a real artifact flavor/build-id in the one operator graph before any
start attempt.

`config/acme.edn` also reintroduces a manually mirrored tool/context set and a
DiffusionGemma/typeahead block. The preserved stable manifest inherited the
default system instead. Since the roadmap is the implementation-state
authority, the active ACME manifest should be reconciled against the actually
graduated autocomplete work rather than treating either aspirational docs or
the old mirror as truth. The current `steps-tile-html` spelling additionally
uses retired vocabulary and is evidence that the mirror has drifted.

The preserved worktree audit found additional state that must not be flattened
into this branch blindly:

- `seon-stable` has dirty ACME source/config, generated bundle, Inspect scoring
  research/scripts, and an untracked regular `acme/CLAUDE.md`; its live ACME
  database is about 44 MB;
- `seon-display-v3` has a live ACME cluster and about 4.2 GB under
  `data/clusters/acme`, plus dirty generated output/reference links;
- `seon-plan-pilot` retains about 373 MB of ACME database evidence; and
- several other worktrees retain generated ACME bundles, local `node_modules`,
  or audit fixtures.

The five selected stable-lane implementation commits for Inspect planning and
plan behavior are already present on the current branch under new commit IDs
(`6ca0aec4`, `71527299`, `1946850e`, `c8a8b23e`, `99c5046b`). Git's patch-id
comparison marks the corresponding stable commits as integrated. Remaining
stable-only commits are documentation/history or older autocomplete changes;
they must be classified against the active roadmap and evidence-preservation
issue rather than bulk-merged.

## Inspect integration findings

Inspect's source and offline test suite are present, but live evaluation is not
fully integrated with the new operator:

- `src-inspect-ai/src/seon_inspect/cluster.py` calls removed `cluster create`,
  `cluster destroy`, `bench-bundle`, and per-pod restart commands;
- `bench_common.py` connects directly to writer port 7891;
- `typeahead_corpus.py` calls old `bin/acme start pod` and `restart pod`
  commands and defaults to writer port 7981;
- corresponding README and runbook examples retain the same contracts.

Consequently, a green offline Python suite does not prove that pod-backed CLJ,
CLJS, typeahead, or multi-cluster evaluation works. Inspect should migrate to
the operator's structured output and typed lifecycle/lease API after that API
exists. It should discover ports through cluster state, never arbitrary
hard-coded values or Clojure string evaluation.

## Implementation order

1. Completed: record the live ACME ownership/data hazard and stale Inspect
   caller contracts as durable issues.
2. Completed for development REPLs: cluster-namespaced dynamic writer port
   discovery and Shadow port-file discovery preserve default behavior.
3. Completed: writer `io-prepl` on port zero with framing and publication
   tests.
4. Completed: one repository-declared MCP server with CLJ/CLJS tools, bounded
   framing, sessions, deadlines, failures, and restart behavior.
5. Completed: portable matching Claude/Codex registrations and live CLJ/CLJS
   proof against the default cluster.
6. Next: make artifact manifests flavor-aware, preserving the default closure
   and warm test publication exactly.
7. Implement the isolated one-off ACME build, then coordinate stopping the old
   stable/display processes and deciding each old `store` data disposition
   before starting current ACME.
8. Implement the roadmap's cluster lifecycle/lease boundary and migrate
   Inspect's live callers and documentation. Run offline tests plus a basic
   live CLJ/CLJS and typeahead smoke.
9. Remove inert Shadow source paths, redundant npm Shadow tooling, and stale
   lifecycle scripts only after clean artifact and lockfile proofs.
10. Retire preserved worktrees only after their commits and dirty changes are
    accounted for and their owned processes are stopped. Use Git worktree
    removal and pruning; do not delete directories manually.

## Required proof

The completed change should demonstrate all of the following:

- effective classpaths for base, writer, writer tests, CLJS, and ACME source
  injection;
- a clean writer uberjar and default client/test/bootstrap/CSS artifact;
- default `bin/seon up`, structured ready status, web UI response, and clean
  down/up recovery;
- concurrent isolated default and ACME artifact builds without shared Shadow
  server/cache ownership;
- dynamic CLJ and CLJS port discovery after restarts;
- the same MCP command in Claude and Codex evaluating one stateful CLJ session
  and one stateful CLJS runtime session;
- unavailable-runtime, timeout, malformed-form, and transport-failure results
  returned as tool errors without corrupting stdio;
- current ACME code, dependencies, artifact, and database decision visible to
  the current operator;
- Inspect offline tests plus live operator-backed CLJ, CLJS, and autocomplete
  smoke evidence;
- no live process rooted in a worktree before that worktree is removed.

## Durable issue ownership

This audit found two operational problems that require issue-note ownership:

1. ACME has live, unowned-by-current-operator processes rooted in
   `seon-stable`, with incompatible artifact and database layouts. Acceptance
   requires ownership-safe drain/adoption, an explicit data disposition, and
   current-operator status that cannot report down while the selected ports are
   held by the old cluster.
2. Inspect's live harness calls retired operator commands and hard-coded writer
   ports. Acceptance requires the structured cluster lifecycle/lease contract,
   migrated callers and docs, and live CLJ/CLJS/typeahead proof.

The active notes are `acme-operator-migration-drift.md`,
`inspect-live-cluster-caller-drift.md`,
`autocomplete-worktree-evidence-preservation.md`, and
`acme-no-sci-eval-seam.md`. The initial MCP issue is archived after the
implemented proof.

## Source map

The most decision-relevant evidence is concentrated at these locations:

- `.mcp.json` and `.codex/config.toml` — matching portable MCP registrations;
- `bin/mcp-server-cljs` and `script/seon/dev/mcp.clj` — thin launcher plus the
  unified JSON-RPC, CLJ/CLJS transport, session, deadline, and discovery owner;
- `shadow-cljs.edn:4-8,31-40,75-101` — dynamic nREPL port, inert source list,
  and mirrored ACME build;
- `reference-code/shadow-cljs/src/main/shadow/cljs/devtools/server/nrepl.clj:117-140`
  — default port-zero nREPL startup;
- `reference-code/shadow-cljs/src/main/shadow/cljs/devtools/server.clj:139-151,349-359`
  — actual port-file publication under the cache root;
- `reference-code/shadow-cljs/src/main/shadow/cljs/devtools/server/worker/impl.clj:775-790`
  — connected-runtime selection;
- `reference-code/shadow-cljs/src/main/shadow/cljs/devtools/config.clj:93,183-188`
  — configurable cache-root ownership;
- `reference-code/nrepl/src/clojure/nrepl/core.clj` and
  `reference-code/nrepl/src/clojure/nrepl/transport.clj` — request/session and
  bencode transport contracts;
- `script/seon/dev/config.clj:145,167-169` — cluster port file plus dynamic
  writer REPL default;
- `script/seon/dev/artifact.clj:161,169-198` — default-client-only build and
  manifest closure;
- `script/seon/dev/process.clj:123-178,270-298` — unconditional client/test
  watcher and readiness;
- `bin/acme:106-189` — direct calls to removed lifecycle commands;
- `src-inspect-ai/src/seon_inspect/cluster.py:195-200,280-305,338-379` — removed
  bundle/create/restart/destroy operator contracts;
- `src-inspect-ai/src/seon_inspect/bench_common.py:34-37` — hard-coded default
  writer port;
- `src-inspect-ai/src/seon_inspect/typeahead_corpus.py:222-240,312,383,428` —
  old ACME commands and hard-coded ACME writer port.

## Conclusion

The central dependency architecture is not hacky. The isolated writer and CLJS
bases, thin operator launcher, dynamic development ports, unified MCP adapter,
and default Shadow test artifact now have clear ownership. The brittle parts
are duplicate/stale client declarations, default-client-only artifact logic,
preserved worktree process/data ownership, and ACME/Inspect callers left behind
by the operator rewrite.

Data-driven artifact flavors and a deliberate ACME/Inspect migration complete
the existing design without moving lifecycle into aliases or creating another
runtime path.
