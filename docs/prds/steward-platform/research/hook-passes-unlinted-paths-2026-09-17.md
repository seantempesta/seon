---
type: research
status: active
tags: [research, hook, lint, wave/dev-tooling-face-hygiene]
---

# The edit hook passed every file it could not lint — 2026-09-17

Landing note for the bounded lane repairing `bin/seon-hook` after a lane
wrote an unmatched delimiter into `src/seon/db.clj` and broke
`bin/seon start` and `bin/seon reset --force`.

## What the evidence actually said

The prompt's hypothesis was a path bug: the hook resolved
`/Users/sean/src/seon/db.clj` from a header beginning `src/seon/`, so look
for a prefix strip or a `str/replace` on the root's basename. **That is not
what happened, and there is no such code.** Path extraction was exact then
and is exact now.

Codex session `01a0ac64-54ae-71f3-8150-d7b6f06569e9`, rollout
`~/.codex/sessions/2026/09/16/rollout-2026-09-16T16-44-11-01a0ac64-….jsonl`:

| ordinal | 00:05:01.395 | `text(await tools.apply_patch("*** Begin Patch\n*** Update File: /Users/sean/src/seon/db.clj…"))` |
| --- | --- | --- |
| 934 | the model wrote that absolute path itself | hook fired (`logs/hook-debug.log` 00:05:01.477906Z), **passed**, then apply_patch failed: "Script failed" at 00:05:01.505 |
| 947 | 00:05:18.534 | `text(await tools.exec_command({cmd:"apply_patch <<'PATCH'\n*** Update File: src/seon/db.clj…"}))` |

Ordinal 947 is the edit that landed the broken bytes. Its tool name is
`exec`, not `apply_patch`, so `.codex/hooks.json`'s matcher
`^(apply_patch|Edit|Write)$` never matched and **no hook ran at all** —
neither before nor after. There is no `PostToolUse` or `SOURCE_EDIT` line
for it because there was no event.

## The three seams, one disease

Reading the hook end to end found the recurring class — a check that reads
absence of signal as health — in three places:

1. `reconstruct-file-content` handled `Write` and `Edit` and ended in a bare
   `:else nil`. `validate-clojure-edit` wrapped it in `when-let`, so an
   `apply_patch` event produced `nil` and returned `{:continue true}`.
   **No Codex patch has ever been syntax-checked before its bytes landed**,
   for as long as both tools have existed. Ordinal 934's pass is not a path
   bug — the hook would have passed a correct path just the same.
2. Nothing refused a resolved path that does not exist. The check simply
   found nothing to say.
3. `clj-kondo-feedback` filtered the event's paths through `.isFile` and
   emitted error-level findings as ADVISORY prose. A vanished path was
   dropped silently; an unmatched delimiter on disk was one line in a tail.

## What changed

All in `bin/seon-hook`:

- The apply_patch header grammar is parsed by exact prefix match over the
  four forms the tool supports — `*** Add File:`, `*** Update File:`,
  `*** Delete File:`, `*** Move to:` — replacing two multiline patterns.
  `*** Move to:` renames the operation above it; paths resolve against the
  repository root through the existing `absolute-file-path`.
- `reconstruct-patched-file` builds the prospective file: an Add carries its
  `+` lines whole, an Update applies its `@@` hunks to the file on disk
  (under the OLD name when a Move renames it), and a Delete or a moved-away
  source reconstructs nothing because there is nothing left to lint.
- Every failure to build is `:unavailable`, which blocks and names the path:
  a path the patch does not mention, an absent update target, an Add over an
  existing file, an unapplicable hunk, a payload with no patch text, and any
  edit tool the hook has no reconstruction for.
- An edit event whose payload names no path at all now blocks instead of
  falling through `local-edit?` to `{:continue true}`.
- PostToolUse returns `:decision "block"` with a `REFUSED:` reason for
  syntax errors left on disk, and for a named Clojure path that is absent
  afterwards without the patch having deleted it. Advisory findings still
  ride `additionalContext` unchanged.

## Pre-write versus post-write — what is now guaranteed

- **apply_patch:** a PreToolUse refusal before the write is possible and now
  happens. The harness offers PreToolUse for this tool and honours
  `{"decision":"block"}` ("Tool call blocked by PreToolUse hook" is in the
  codex binary). The hook builds the prospective file from the patch and
  lints it, so an unmatched delimiter never reaches disk.
- **Edit / Write:** unchanged and already correct. `Write` carries its whole
  content; `Edit` reconstructs through `seon.edit/exact` and a missing file
  raises, which is `:unavailable` and blocks. The same defect could not hit
  them, because both always had a reconstruction branch. An Edit whose
  `old_string` is ambiguous still passes deliberately: the tool itself
  refuses it, and a pre-read the authority re-decides is not the hook's
  decision to make.
- **The hook cannot guarantee anything about a shell write.** It is bound to
  tool names. `apply_patch` from a heredoc, `python`, `sed`, `cat >` fire no
  event, so there is nothing to block and nothing to lint — and the bytes
  are on disk with no publication queued. AGENTS.md §5 states this; ordinal
  947 is what it cost. Closing it is an owner decision, because it means
  matching every tool and deriving changed paths from the working tree
  rather than from the payload.
- Even where the hook does fire, PostToolUse is post-write by construction:
  a lane killed between the write and its correction leaves the bytes on
  disk. The refusal is now loud, which is the most a post-write check can
  honestly offer.

## Verification boundary

`bin/test-fast seon.dev.edit-feedback-test seon.dev.hook-test` — iteration,
not the isolated gate. Those namespaces drive the real `bin/seon-hook`
subprocess with real JSON events and a real `clj-kondo`, which is the
harness the hook runs on. No `bin/test`, no prepl, no cluster was used, per
the assignment. `bin/test --paths … -- …` and `--platform` were explicitly
out of scope for this lane and have NOT been run.
