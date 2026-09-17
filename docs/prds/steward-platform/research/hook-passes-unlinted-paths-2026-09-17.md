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

## Closing the shell route (coordinator decision, same lane)

The owner question above was decided by the coordinator under the "don't
wait" ruling and is implemented here; the owner may veto it.

`.claude/settings.json` and `.codex/hooks.json` now match `.*` — the hook
fires on EVERY tool event. For a tool whose payload names no path, the
changed files are DERIVED: every declared root is walked and each Clojure
file's content digest compared with the digest recorded at this session's
previous event (`tmp/session-digests/<session>.edn`, pruned after a day).
Never a modification time — a write that restores an old mtime, or two
writes inside one clock tick, are exactly the cases a digest still sees.
The first event of a session seeds the map from the tree and reports
nothing. A syntax error in a changed file blocks immediately, naming the
path and the tool that wrote it. A payload-named edit folds its own paths
into the map so the next derived scan does not re-report what the edit hook
already checked.

**When each check fires, plainly:**

- A **tool-payload write** (`apply_patch`, `Edit`, `Write`) is caught
  **before the write**: the hook builds the prospective file and refuses at
  PreToolUse, so broken bytes never reach disk.
- A **shell write** cannot be caught before the write — at PreToolUse the
  bytes do not exist and no payload names the file. It is caught
  **PostToolUse**: the bytes land, the block fires the moment the tool
  returns, and the agent cannot proceed until it repairs them. That is the
  most a derived check can honestly offer, and it is strictly better than
  the silence that broke the tree.
- A derived scan that throws blocks too. A scan that checked nothing must
  not answer "fine" — that is the disease this lane exists to kill.

**The bound, measured in this checkout on 2026-09-17.** The scan is
complete, not scoped, because the complete scan is the cheap option here:

| operation | cost |
| --- | --- |
| walk five roots + SHA-256 of all 553 Clojure files (9 MB), in bb | 112 ms |
| `git ls-files` over the same roots | 21 ms |
| **whole hook process, real roots, per non-edit event** | **167–197 ms** |
| `git status --porcelain` (warm) | 2.7 s |
| `git status --porcelain` (cold) | 9.6 s |

Scoping by `git status` — the fallback the decision offered — is **25x more
expensive** than scanning everything, because this working tree carries
`tmp/` run roots and the `reference-code/` submodules. So the roots are
declared data in `.claude/seon-hook.edn` (`:shell-writes {:roots …}`), the
walk is a plain `file-seq` with no git subprocess and no ignore semantics to
get wrong, and an untracked new file is covered by construction. The scan
runs at PostToolUse only, not at both events: "the digest recorded at the
previous event" is the same baseline for half the cost. One session's digest
map is 57 KB.

**What this costs everyone:** every tool call — a Read, a grep, a Bash —
now pays ~170 ms of hook. That is the price of the guarantee, and it is
stated here rather than discovered later.

**Still not covered:** a shell write is checked for READABILITY, not
published. `bin/seon init --dev default --changed PATH` after a shell source
write remains AGENTS.md §5's rule; wiring derived writes into publication is
a separate decision, because the queue would then carry writes from any
command that touches the roots.

## Verification boundary

`bin/test-fast seon.dev.edit-feedback-test seon.dev.hook-test` — iteration,
not the isolated gate. Those namespaces drive the real `bin/seon-hook`
subprocess with real JSON events and a real `clj-kondo`, which is the
harness the hook runs on. No `bin/test`, no prepl, no cluster was used, per
the assignment. `bin/test --paths … -- …` and `--platform` were explicitly
out of scope for this lane and have NOT been run.
