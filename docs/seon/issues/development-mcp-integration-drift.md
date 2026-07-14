---
type: issue
status: open
severity: friction
tags: [issue, mcp, cljs, database, component]
---

# Development MCP integration is split and stale

## Problem

Repository MCP configuration does not provide a portable, working development
evaluation boundary for both Seon runtimes. Claude and Codex launch different
or nonexistent executables, the current task can expose no Seon eval tools at
all, and there is no CLJ evaluation path into the writer.

This blocks reliable MCP-assisted CLJ/CLJS probing and restart verification. It
does not block the default cluster's normal operator or web UI.

## Evidence

The dependency/Shadow/MCP audit verified:

- `.mcp.json` registers `seon` at the nonexistent `bin/mcp-server` and a
  separate `seon_cljs` at `bin/mcp-server-cljs`;
- `.codex/config.toml` registers only the CLJS executable under the ambiguous
  name `seon`; the active Codex task exposed neither CLJ nor CLJS Seon tools;
- both client files use `/Users/sean/src/seon` absolute paths, so a task in a
  worktree can silently evaluate the main checkout;
- `bin/mcp-server-cljs` is an 875-line Babashka executable mixing MCP framing,
  bencode/nREPL transport, sessions, runtime selection, port discovery, and
  lifecycle behavior without a repository-declared adapter namespace or full
  dependency boundary;
- Shadow and writer developer ports are repeated as fixed defaults even though
  both runtimes publish the actual bound port through owned port files.

The existing CLJS adapter's essential transport is valid: unique nREPL request
ids, persistent sessions, response collection through `done`, stderr-only
diagnostics, and explicit Shadow runtime selection. The drift is its split and
fixed deployment, not a reason to invent another CLJS transport.

Installed Clojure 1.12 source confirms that `clojure.core.server/io-prepl`
emits framed EDN events with exactly one `:ret` per form and supports stateful
REPL values. The writer currently exposes the human-oriented `repl`, so a
machine CLJ tool is absent; adding nREPL to the production writer would create
an unnecessary second protocol and enlarge its isolated basis.

The 2026-07-14 implementation verifier found six additional owning defects in
the first integrated version:

- a multi-form CLJ string consumed only the first per-form `:ret`, leaving
  later returns queued and shifting subsequent stateful calls;
- Shadow reports runtime and print failures as the values
  `:repl/exception!` and `:repl/print-error!` with a terminal `done` status, so
  status-only classification incorrectly returned tool success;
- writer discovery ignored the operator-supported
  `SEON_WRITER_REPL_PORT_FILE` override;
- temporary and replaced Shadow nREPL sessions were not always closed;
- CLJ deadlines were per-read inactivity timeouts and both CLJ and CLJS
  transports accumulated output before the final display clip; and
- `docker/seon-entrypoint` passed a default writer REPL port into the canonical
  production container, violating the typed-production/development-eval
  boundary.

The current fix enforces one CLJ form before socket write (`(do ...)` is the
explicit multi-expression form), classifies both Shadow sentinels as errors,
honors the config-owned writer port-file override for the selected cluster,
closes transient/replaced nREPL sessions, uses an overall CLJ deadline with
bounded event retention and bounded nREPL response retention, and removes all
writer REPL arguments from the production container.

Focused proof passes `seon.dev.mcp-test` at 9 tests/36 assertions and
`seon.db.server-test` at 1 test/4 assertions. A live newline-JSON-RPC proof
against the ready default cluster observed CLJ state `9`, rejected `10 11` as
`:multiple-forms`, then observed `10` from `(inc framing-proof)` with no queued
return; a thrown CLJS `js/Error` returned `isError` with `:evaluation`; and a
64-token bounded CLJ call returned valid JSON with `:bounded`. The earlier
restart proof also observed explicit named-session loss plus successful default
CLJ/CLJS recovery. This note remains open only until the owning commit exists;
archive it with that commit and these behavioral proofs.

## Owner

One repository-owned development MCP adapter under `script/seon/dev/` with a
thin `bin/` launcher, plus the operator-owned cluster/port identity and the
writer's development-only `io-prepl` accept boundary. `.mcp.json` and
`.codex/config.toml` are client registrations of that same owner.

## Acceptance

- One development-only stdio MCP server exposes explicit `eval_clj` and
  `eval_cljs` tools. `eval_cljs` uses the existing Shadow nREPL mechanism;
  `eval_clj` uses the writer's `clojure.core.server/io-prepl`, without adding
  nREPL or arbitrary eval to the typed production database protocol.
- The adapter lives in a tested Babashka namespace with all non-bundled
  dependencies declared in `bb.edn`; the executable only discovers the
  repository and launches it.
- Every request selects or resolves an explicit cluster/checkout, discovers
  current ports from operator-owned namespaced files, rejects stale/foreign
  ownership, and never silently routes a worktree task to the main checkout.
- Claude and Codex launch the same portable command. Preserve the existing
  Claude-facing server name as a compatibility alias only if needed, with both
  names resolving to this one implementation rather than the removed
  two-server topology.
- Stateful CLJ and CLJS sessions preserve their respective REPL semantics.
  Malformed forms, unavailable runtimes, timeouts, stale files, bad framing,
  transport loss, and runtime restart return structured, token-bounded tool
  errors; JSON-RPC alone is written to stdout and diagnostics stay on stderr.
- After restarting the writer, Shadow watcher, pod, Claude, and Codex, both
  clients evaluate one stateful CLJ sequence and one stateful CLJS sequence
  against the intended checkout/cluster. Old sessions fail or reconnect
  explicitly; no request hangs or crosses into another cluster.
