---
type: issue
status: resolved
tags: [issue, component]
severity: friction
---

# Stray repo-root `locks/stack.lock` from operator test

## Observed

Every changed-test hook run of the operator suite recreated `locks/stack.lock`
at the repository root. Reproduced and root-caused in
[[../../prds/runtime-reliability/research/cleanup-audit-config-startup-2026-07-20]]:
`test/seon/dev/cli_test.clj` `branch-commands-call-only-the-retained-lifecycle-owner`
used a fixture config without `:seon.dev.config/process-dir` and, unlike its
sibling lifecycle tests, did not redef `state/with-lock`. `cli/branch!` then ran
the real `:stack` lock, and `state/with-lock` degraded `(fs/path nil "locks")`
to the bare relative `locks/` at the JVM cwd (repo root).

## Fix (2026-07-20)

- `script/seon/dev/state.clj` `with-lock` now throws a clear `ex-info` when
  `:seon.dev.config/process-dir` is absent, blank, or not absolute. A lifecycle
  lock outside the operator's absolute process directory is always a bug; this
  also guards any relative `SEON_PROC_DIR`.
- `test/seon/dev/cli_test.clj` branch-commands test now redefs
  `#'state/with-lock (fn [_ _ _ transition] (transition))`, matching every
  sibling lifecycle test.
- Stray repo-root `locks/` deleted.

## Proof

`bin/seon test operator` full run: 288 tests, 1613 assertions, 0 failures,
0 errors; no `locks/` directory at the repo root afterward.

## Post-fix "reappearance" report investigated (2026-07-20, R13)

A later report that `locks/` reappeared after this fix traced to a stale
git-status snapshot: the observing session's snapshot HEAD was `4f38818f`
(2026-07-19 20:41), before the hardening commit `3d4aee61` (10:13), which
itself deleted the stray directory. `rg` confirms exactly one code path
creates a `locks` directory (`state.clj` `with-lock`, now guarded to an
absolute process-dir); no live process runs pre-fix operator code (the MCP
server loads only `seon.dev.config`; changed-test workers are fresh `bb`
per generation; release packages do not ship the operator script). A full
post-fix operator run (289 tests, 1619 assertions, 0 failures) left no
repo-root `locks/`. No second creation path exists.
