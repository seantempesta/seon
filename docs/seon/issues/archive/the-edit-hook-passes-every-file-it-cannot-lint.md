---
type: issue
status: resolved
severity: blocker
tags: [issue, hook, lint, wave/dev-tooling-face-hygiene]
---

# The edit hook passed every file it could not lint

## Problem

`bin/seon-hook` read "I built nothing to check" as "there is nothing wrong".
Three seams shared the one disease:

1. `reconstruct-file-content` had branches for `Write` and `Edit` and a bare
   `:else nil` for everything else. A Codex `apply_patch` therefore produced
   NO prospective content, `validate-clojure-edit`'s `when-let` saw `nil`,
   and the PreToolUse check returned `{:continue true}`. **No apply_patch
   edit has ever been syntax-checked before its bytes landed.**
2. A resolved path that does not exist was not a refusal. On 2026-09-17 a
   lane's patch header named `/Users/sean/src/seon/db.clj` — a path this
   repository does not have — and the hook logged the event and passed it
   (`logs/hook-debug.log`, `2026-09-17T00:05:01.477906Z`).
3. The PostToolUse clj-kondo pass filtered event paths through `.isFile` and
   reported error-level findings as ADVISORY text. A file that vanished was
   silently dropped, and an unmatched delimiter left on disk was one line in
   an advisory tail.

The visible cost: at 00:05:18 a lane wrote an unmatched delimiter into
`src/seon/db.clj:3085`, which then broke `bin/seon start` and a
`bin/seon reset --force`.

## Evidence

Codex session `01a0ac64-54ae-71f3-8150-d7b6f06569e9`, rollout
`~/.codex/sessions/2026/09/16/rollout-2026-09-16T16-44-11-01a0ac64-….jsonl`:

- ordinal 934 — `tools.apply_patch("*** Begin Patch\n*** Update File:
  /Users/sean/src/seon/db.clj…")`. The hook fired (log line 912) and passed;
  apply_patch itself then failed on the nonexistent path. **The lost
  `src/seon/` prefix was the model's own header text, not a hook path bug:
  extraction was exact.**
- ordinal 947 — `tools.exec_command({cmd:"apply_patch <<'PATCH'\n*** Update
  File: src/seon/db.clj…"})`. Tool name `exec`, not `apply_patch`, so
  `.codex/hooks.json`'s matcher `^(apply_patch|Edit|Write)$` never matched.
  **This is the edit that landed the broken bytes, and no hook ran at all.**

## Fix

`bin/seon-hook` now parses the apply_patch header grammar by exact prefix
(`*** Add File:`, `*** Update File:`, `*** Delete File:`, `*** Move to:`),
applies the patch's hunks to build the prospective file, and refuses — with
the offending path named — whenever it cannot: an unnamed path, an absent
update target, an Add over an existing file, an unapplicable hunk, an edit
payload naming no path at all, or any tool it has no reconstruction for.
PostToolUse now returns `:decision "block"` for syntax errors left on disk
and for a named Clojure path that is absent afterwards without the patch
having deleted it.

## The shell route, closed

Decided by the coordinator under the "don't wait" ruling; the owner may
veto. The matcher is now `.*` in both `.claude/settings.json` and
`.codex/hooks.json`, and a tool whose payload names no path has its changed
Clojure files DERIVED — declared roots walked, content digests compared with
this session's previously recorded digests, never a modification time. A
syntax error blocks immediately, naming the path and the tool.

A shell write is caught POST-write (the bytes land, the block fires at
once); a tool-payload write is caught BEFORE the write. Measured cost:
167-197 ms per non-edit tool event for the complete scan of 553 files, where
scoping by `git status --porcelain` would have cost 2.7 s warm and 9.6 s
cold in this tree. Evidence and the full table:
[the landing note](../../prds/steward-platform/research/hook-passes-unlinted-paths-2026-09-17.md).

Still open by design: a shell write is checked for readability, not
published — `bin/seon init --dev default --changed PATH` remains the rule.

## Does it reach a codex lane?

Partly, and the gap was measured rather than assumed. Codex fires the hook
for `apply_patch` and — since the matcher widened — for shell commands too
(`tool_name: "Bash"`). It surfaces a PreToolUse block verbatim, and
surfaces NOTHING from a PostToolUse `decision: block`: a scratch probe lane
wrote an unmatched delimiter through a shell heredoc, the hook blocked at
2026-09-17T02:26:41.262071Z, and the lane reported "NO FEEDBACK STEP 3 …
I was not prevented from continuing". Every refusal now also exits 2 with
its reason on stderr, which blocks unconditionally and cannot be silently
dropped, and the derived scan runs on every PostToolUse so a codex lane is
told at its next `apply_patch` even when the shell event's block is
dropped. A codex shell write still cannot be refused BEFORE it lands:
codex has no event for it. Full evidence and a loophole inventory in
[the landing note](../../prds/steward-platform/research/hook-passes-unlinted-paths-2026-09-17.md).

## Regressions

`test/seon/dev/edit_feedback_test.clj`:
`every-apply-patch-header-form-resolves-against-the-repository-root`,
`pre-edit-refuses-a-patch-path-that-does-not-exist`,
`pre-edit-blocks-a-patch-that-writes-unreadable-clojure`,
`pre-edit-refuses-an-edit-payload-that-names-no-file`,
`post-edit-refuses-unreadable-clojure-and-still-reports-siblings`,
`a-shell-write-the-hook-was-never-handed-is-derived-and-refused`.
