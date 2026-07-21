---
type: research
status: completed
tags: [research, prd, flow, agent]
---

# Automatic test feedback infrastructure audit

## TL;DR

Seon already has most of the right pieces, but they are not connected end to
end:

- `bin/seon-hook` is the one edit-feedback adapter. It already returns
  `PostToolUse` `additionalContext`, which Codex records into the active turn.
  That is the only existing mechanism that automatically puts a diagnostic in
  the editing agent's model context. A log file, browser panel, REPL, or
  background process does not wake or notify the model by itself.
- The Codex app discovers both project hooks, but the live `hooks/list` result
  reports both as `untrusted`, so neither runs. Trust is deliberately a
  per-handler hash stored in user configuration. It needs a one-time user
  review and must be reviewed again when a hook definition changes.
- Codex already maps `apply_patch` to the existing `Edit|Write` matcher aliases.
  The matcher is not the bug. `bin/seon-hook` rejects the canonical
  `tool_name: "apply_patch"` payload and only understands Claude's one-file
  `Edit` and `Write` shapes. It therefore needs one normalization function that
  extracts the complete path set and prospective contents from all three
  supported payloads.
- Shadow should remain the compiler/dependency authority. Extend the one managed
  watcher to watch `client` and `test`; add a host-side Shadow build hook that
  publishes an immutable successful test artifact plus an exact manifest. Do
  not use Shadow's `:autorun`: its vendored implementation runs the whole test
  bundle synchronously, has no affected-test selection, and has an explicit
  unresolved child-termination problem.
- The edit hook should call the same public operation a human calls:
  `bin/seon test changed --path ...`. That operation waits for a successful
  manifest matching the current source fingerprint, derives affected test
  namespaces from Shadow's graph, launches a fresh bounded Node process, and
  returns a short decision/result. The hook merely adapts that result into
  `additionalContext`; it must not implement a second selector or runner.

This gives direct, deterministic feedback after an edit without creating a
second event bus, registry, test daemon, or database projection. Full-suite
tests remain checkpoint gates, not the inner loop.

## What exists now

### Direct edit hook

Both [`.codex/hooks.json`](../../../../.codex/hooks.json) and
`.claude/settings.json` register the same `bin/seon-hook` command for
`PreToolUse` and `PostToolUse`, matched by `^(Edit|Write)$`. The hook is a direct
Babashka script and has no running Seon dependency
(`bin/seon-hook:1-21`). Its current responsibilities are:

- reconstruct and parse a prospective Clojure file with Edamame before an edit
  (`bin/seon-hook:114-170`);
- auto-fix and report Markdown violations after an edit
  (`bin/seon-hook:198-225`);
- report docstring findings (`bin/seon-hook:227-245`);
- gate newly observed live pod core faults (`bin/seon-hook:249-314`); and
- return concise feedback as `hookSpecificOutput.additionalContext`
  (`bin/seon-hook:316-323`).

This supersedes the paused-JVM/nREPL repair design documented in
`docs/prds/agent-fsm/research/edit-hook-parsing-clojure-mcp-2026-06-25.md`.
The direct Babashka path is the current mechanism; the old JVM repair path
should not return.

Two gaps prevent it from working for current Codex edits:

1. `bin/seon-hook:336-338` accepts only literal tool names `Edit` and `Write`.
2. `get-file-path` and `reconstruct-file-content` expect a one-file Claude
   payload. Codex supplies `tool_name: "apply_patch"` with the entire patch in
   `tool_input.command`, including potentially several add, update, delete, or
   move operations (`codex-rs/core/src/tools/handlers/apply_patch.rs:503-539`).

The matcher does not require changing. Codex's hook identity keeps the
canonical name `apply_patch` while supplying `Write` and `Edit` as matcher
aliases (`codex-rs/core/src/tools/hook_names.rs:28-38`). The hook script must
normalize the selected payload after dispatch.

### Hook trust and the one-time user action

On 2026-07-14, the installed Codex app reported `hooks` as a stable enabled
feature. A live app-server `hooks/list` request for this checkout found both
configured hooks, enabled and free of discovery errors, but returned
`trustStatus: "untrusted"` for both. A valid `apply_patch` probe consequently
produced no `logs/hook-debug.log` entry.

This is expected Codex behavior, not a Seon process problem:

- unmanaged hook definitions are hashed per event/group/handler;
- only `trusted` or managed definitions enter the runnable handler set; and
- a missing trusted hash is `untrusted`, while a changed definition is
  `modified` (`codex-rs/hooks/src/engine/discovery.rs:495-545,589-612`).

The supported user experience is a hook review prompt with `Review hooks`,
`Trust all and continue`, and `Continue without trusting`. Approval writes each
current hash under `hooks.state.<hook-key>.trusted_hash` in the user's Codex
configuration and reloads user config (`codex-rs/tui/src/hooks_rpc.rs:51-89`).
Project trust and hook trust are separate. Do not commit trusted hashes, silently
modify a user's Codex configuration, or use the dangerous trust-bypass flag as
the normal runbook. After implementation, ask the user to review the two hooks
once in Codex. A later definition change correctly requests review again.

### What actually notifies the active agent

For a successful tool call, Codex runs matching `PostToolUse` hooks and records
their `additionalContext` on the active turn
(`codex-rs/core/src/tools/registry.rs:594-624`). The next model sampling boundary
therefore includes the diagnostic without the agent polling a file or terminal.
This is the direct notification mechanism to reuse.

The other current mechanisms are useful observability, not model notification:

- `logs/hook-debug.log` is a bounded forensic log; it must be polled.
- `bin/seon logs ... --follow` tails one managed process for a human or an
  already-running tool call (`script/seon/dev/cli.clj:218-242`).
- browser diagnostics update the human-visible UI, not the active Codex turn.
- MCP and REPL calls are request/response operations; neither injects an
  unsolicited future result into a model turn.
- a code-mode host call can keep one tool invocation alive, yield, and later
  emit `notify(...)`, but repository code and Shadow cannot invoke that host
  primitive. It is an optional orchestration adapter, not system authority.

Therefore the robust default is a bounded synchronous `PostToolUse` hook. If
the test takes a few seconds, the editing call remains in progress and the
result lands in that same turn. A background-only test would need a client-owned
monitor and would lose this delivery guarantee on task restart.

### Managed Shadow and test processes

The operator already manages a detached Shadow watcher with bounded lifetime
logs and process identity (`script/seon/dev/process.clj:344-384`), but its argv
is only `clj -M:cljs watch client` (`script/seon/dev/process.clj:123-136`). Its
readiness parser also recognizes only `[:client]` build markers
(`script/seon/dev/process.clj:263-269`).

The `:client` build has a runtime `:build-notify` callback
(`shadow-cljs.edn:57-65`). That callback deliberately computes the exact Node
namespaces reloaded and re-instruments them (`src/seon/client.cljs:314-350`). It
runs inside the reloaded application runtime and should remain dedicated to
that responsibility. It is not the authority for a headless test artifact.

The `:test` build is currently a one-shot `:node-test` output at
`out/test/test.js` (`shadow-cljs.edn:222-240`). `bin/test-cljs` serializes the
mutable compile/run path. The completed impact-selection audit establishes the
next design: a warm complete test artifact, immutable/versioned publication,
an exact manifest, and a fresh Node process for each selected run. See
`test-impact-selection-and-runner-audit-2026-07-14.md` in this folder.

Shadow's vendored `node-test` `:autorun` is unsuitable. It launches the complete
output with `node`, waits synchronously, offers no impacted-test selector, and
contains `FIXME: what if this doesn't terminate?`
(`reference-code/shadow-cljs/src/main/shadow/build/targets/node_test.clj:65-102`).
Host build hooks are synchronous stage hooks
(`reference-code/shadow-cljs/src/main/shadow/build.clj:133-151,233-279`), so a
small `:flush` hook is appropriate for publishing a successful artifact
manifest. Compile failures occur before that successful flush and must be
reported from watcher state/logs rather than misrepresented as a manifest.

## One mechanism end to end

```text
apply_patch / Edit / Write
        |
        v
bin/seon-hook: normalize edit -> changed path set
        |
        v
bin/seon test changed --path ...       (one public decision operation)
        |
        +--> wait for exact Shadow manifest/source fingerprint
        |       |
        |       +--> compiler failure: return failure, never use stale output
        |       +--> watcher absent/timeout: bounded one-shot fallback
        |
        +--> Shadow graph -> conservative affected test namespaces
        |       +--> explicit widening reason when graph proof is insufficient
        |
        +--> fresh bounded Node run against immutable artifact
        |
        v
short namespaced decision/result
        |
        v
PostToolUse additionalContext -> active agent turn
```

The boundaries matter:

- Shadow owns compilation and dependency facts.
- The changed-test operation owns freshness, selection, widening, locking,
  execution, and result formatting.
- The hook owns only client payload normalization and feedback delivery.
- The full suite owns checkpoint confidence.

No layer reparses dependencies, maintains a second test registry, or stores a
derived notification in the application database.

## Detailed behavior

### Edit normalization

Create one pure normalization function in the existing hook code. Its result is
a fully namespaced data map containing the operation, repository-relative path,
and prospective content when one exists. It must support:

- Claude `Write` and `Edit` exactly as today;
- Codex patch headers `*** Add File:`, `*** Update File:`, `*** Delete File:`,
  and `*** Move to:`;
- multiple files in one patch;
- repository containment after canonicalization; and
- deletion and move as conservative widening inputs when content cannot be
  validated directly.

Pre-edit syntax validation should apply a parsed patch in memory and parse every
resulting Clojure file. Do not implement a partial homegrown patch applier if
Codex's patch grammar cannot be reproduced safely. A first staged version may
retain pre-validation for Claude edits and make Codex post-edit parse failures
direct feedback; a later stage can reuse a verified parser. Post-edit changed
test selection only needs the normalized path set and actual files on disk.

### Freshness and stale builds

Never run a test artifact merely because it is the latest file on disk. A
successful manifest must contain the complete source/config fingerprint, build
identity, artifact path/digest, and compiler graph coordinate. The changed-test
operation compares that manifest to current inputs. While Shadow is compiling,
it waits within a bounded deadline. A compiler failure, watcher death, or
timeout is a first-class result. The existing one-shot compile is the fallback
when the managed watcher is unavailable, not a parallel normal path.

### Concurrency, coalescing, and cleanup

Keep single ownership in the changed-test operation:

- one cross-process execution lock protects selection and a test process;
- each request retains its own changed path set, so concurrent agents do not
  lose an edit merely because Shadow combines changes into one build;
- requests waiting on the same immutable build may share that build and may
  union selected namespaces, but correctness must not depend on coalescing;
- the launched Node child has a deadline and is killed as a process group on
  cancellation or timeout; and
- the result names the selected namespaces and widening reasons, so a caller
  can see exactly what was exercised.

Start with synchronous per-patch execution. It naturally prevents one agent
from issuing another edit before seeing the first result. Measure before adding
a debounce. If concurrent agents create avoidable duplicate runs, coalesce only
inside the existing operation/lock; do not add a daemon or event bus.

### Failure persistence

The authoritative delivery is hook output. A replace-only, bounded,
machine-readable latest decision/result file under operator runtime state is
useful for `bin/seon status`, crash diagnosis, and clients without hook support.
It is an ephemeral diagnostic artifact, not application state and not a context
block. Include the source/build fingerprint so stale failures cannot be
presented as current. Do not create a database notification entity or
acknowledgement workflow for a derived test result.

### Linting

Keep cheap structural feedback on the edit path. Edamame syntax, Markdown, and
docstring checks already work as direct focused checks. `clj-kondo` and Splint
remain useful manual/checkpoint lint tools, but they are not the CLJS compiler's
dependency authority and should not be run repo-wide after every patch. If
measurement supports incremental linting later, it should consume the same
normalized path set and return through the same hook response.

## Reuse, change, and delete

Reuse:

- `bin/seon-hook` as the sole edit adapter;
- the existing Codex and Claude hook declarations;
- `PostToolUse.additionalContext` for model-visible feedback;
- the managed Shadow process and process lifecycle code;
- Shadow's compiler resource/dependency graph;
- `bin/test-cljs` behavior while its internals are moved behind the public
  changed-test operation; and
- the existing operator CLI and bounded process/log conventions.

Change:

- normalize Claude and Codex edit payloads in `bin/seon-hook`;
- make the managed watcher build both `client` and `test` and report readiness
  independently;
- add a host-side test build publication hook;
- add `bin/seon test changed --path ...` to the existing operator; and
- have the hook call that operation and format its result as bounded
  `additionalContext`.

Delete or avoid:

- no return of the old JVM/nREPL edit repair path;
- no Shadow `:autorun`;
- no second test daemon, watcher, event bus, dependency parser, or registry;
- no browser/debug panel as the only feedback path;
- no polling requirement for the editing agent; and
- no trusted hook hashes committed to the repository.

## Mechanical verification

The implementation is complete only when these behaviors are mechanically
proven:

1. Hook unit fixtures normalize Claude Write, Claude Edit, and multi-file Codex
   patches including add/update/delete/move and reject paths outside the repo.
2. A trusted Codex `apply_patch` adds a unique probe file and produces a new
   hook-log entry, proving the actual app dispatch path rather than a hand-fed
   stdin fixture.
3. A source edit selects its direct and reverse-transitive test namespaces from
   the compiler graph; a test-file edit selects that test directly.
4. CLJC, macros, config, dependency, runner, deletion, missing graph, and stale
   graph cases widen with an explicit reason.
5. The operation waits for a matching successful manifest and cannot run a
   previous artifact after a compiler failure.
6. A hanging Node test is terminated with its process group and a clear timeout
   reaches `additionalContext`.
7. Two simultaneous changed-test requests do not corrupt an artifact, lose a
   requested path set, or leave a child process behind.
8. A passing edit returns a concise selected-test summary; a failing edit puts
   the actionable failure directly into the active Codex turn.
9. With the watcher stopped, the bounded one-shot fallback still produces the
   same selector/result schema.
10. Markdown-only edits keep their focused Markdown feedback and do not trigger
    an unrelated CLJS test run.
11. A checkpoint `bin/seon test all` remains green after the inner-loop path is
    proven.

Do not write tests that assert exact agent/context prose. Test path selection,
freshness, process behavior, result shape, and delivery semantics.

## Staged implementation plan

### Stage 1 — make feedback real

- Add the pure edit normalization path to `bin/seon-hook`.
- Add hook fixtures for Claude and Codex payloads.
- Ask the user to review/trust the two discovered Codex hooks.
- Prove a real `apply_patch` reaches the hook and its
  `additionalContext` reaches the active turn.

This stage removes the current silent failure before test automation is added.

### Stage 2 — warm immutable test artifact

- Extend the existing managed watcher to `watch client test`.
- Make readiness and status distinguish both build ids.
- Add the host-side successful-build manifest publisher.
- Publish versioned/immutable test output and atomically replace the latest
  manifest only after a complete flush.
- Keep fresh Node execution and bounded cleanup.

### Stage 3 — one changed-test operation

- Implement namespace-level impact selection exactly as specified by
  `test-impact-selection-and-runner-audit-2026-07-14.md`.
- Add `bin/seon test changed --path ...` to the existing operator.
- Return a fully namespaced decision containing freshness, selected tests,
  widening reasons, artifact identity, status, and bounded failure detail.
- Retain one-shot compile only as the unavailable-watcher fallback.

### Stage 4 — automatic post-edit run

- Invoke the changed-test operation from `PostToolUse` for executable edits.
- Return its concise result through `additionalContext`.
- Add the single-flight/concurrency and timeout proofs.
- Measure edit-to-feedback latency and only then consider lock-level
  coalescing.

### Stage 5 — documentation and checkpoint

- Update `AGENTS.md`, `CLAUDE.md`, the testing architecture/component docs, and
  the runtime-reliability roadmap with the one command and hook-trust runbook.
- State clearly that edit feedback is an inner-loop aid and that checkpoint
  gates still require the broader suites.
- Run focused behavioral tests throughout and one full checkpoint at the end.

## Blockers and decisions

There is no architectural blocker. The only required user action is approving
the current hook definitions in Codex after the hook adapter is ready. The only
implementation sequencing dependency is that automatic test invocation should
wait for the warm immutable artifact and changed-test operation; wiring the hook
directly to today's mutable one-shot runner would make every edit slow and would
cement the path being replaced.

The source-grounded answer to “how do you get notified?” is therefore:
`PostToolUse.additionalContext`, delivered synchronously by the trusted edit
hook. Shadow produces build facts; the operator decides and runs tests; the hook
delivers the result. That is one composable mechanism with clear ownership.
