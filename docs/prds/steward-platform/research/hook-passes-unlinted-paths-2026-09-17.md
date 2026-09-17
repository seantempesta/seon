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

## Does the hook work for both harnesses? Measured, not assumed

The owner asked. The answer is yes for Claude Code, and **partly** for codex,
with one hole that is now closed as far as a hook can close it.

### What codex actually fires

Codex's hook event enum, read from the CLI binary
(`@openai/codex-darwin-arm64/.../bin/codex`, v0.153.4): `pre_tool_use`,
`permission_request`, `post_tool_use`, `pre_compact`, `post_compact`,
`session_start`, `session_end`, `user_prompt_submit`, `subagent_start`,
`subagent_stop`, `interrupt`. Hook entries are trusted per
`~/.codex/config.toml` `[hooks.state]`, keyed
`<file>:<event>:<index>:<index>` with a `trusted_hash`; changing the
matcher in `.codex/hooks.json` did NOT invalidate that trust — codex
sessions kept firing afterwards (`logs/hook-debug.log` 02:04:03Z, 02:16:47Z,
session `01a0acbc-…`, after the 01:40:15Z change).

- **apply_patch**: fires `PreToolUse` and `PostToolUse` with
  `tool_name: "apply_patch"`. Always did.
- **shell commands**: fire `PostToolUse` with `tool_name: "Bash"` — codex
  normalises its shell tool to the Claude-compatible name. This only began
  reaching the hook when the matcher was widened to `.*`; under
  `^(apply_patch|Edit|Write)$` it matched nothing, which is why rollout
  ordinal 947 left no trace at all.
  Evidence: `logs/hook-debug.log` 2026-09-17T02:44:54.892464Z
  `DERIVED_WRITE | tool=Bash | session=01a0acbc-ebbb-7391-950a-f8559fc01d42`
  — a codex session id, firing a shell event.

### What codex does and does not surface to the model

- **A PreToolUse block IS surfaced, verbatim.** Rollout
  `01a0acbe-94f4-7ca3-b945-f8fbfab61175` ordinal 1262,
  2026-09-17T01:39:12.587Z: `"Command blocked by PreToolUse hook: BLOCKED:
  edit reconstruction failed: a hunk's context is not present in the file:
  …"`. The lane read it, re-inspected the file and re-issued. That block was
  this lane's own new refusal, and it is also the one measured cost of the
  applier: one out-of-order-hunk veto the tool itself would not have issued,
  now fixed (`sublist-index` retries from the top of the file, with a
  regression).
- **A PostToolUse `decision: block` is NOT surfaced.** Live proof, scratch
  lane `hook-probe-codex` (session `01a0ad2f-ce73-7342-8427-b4f874bb81c4`,
  `LANE_EFFORT=low`): it wrote an unmatched delimiter into
  `bin/hook_probe.clj` through a shell heredoc. The hook saw it and blocked —
  `logs/hook-debug.log` 2026-09-17T02:26:41.258132Z `DERIVED_WRITE |
  tool=Bash | changed=["bin/hook_probe.clj"]` and 02:26:41.262071Z
  `DERIVED_BLOCK`. The lane's verbatim report: **"NO FEEDBACK STEP 1."**,
  **"NO FEEDBACK STEP 3."**, **"The write succeeded; I was not prevented
  from continuing."**, and "Every shell command used `exec_command` through
  `functions.exec`." The lane deleted the file itself.

That is exactly how `src/my/program.clj:530` carried "Nested #()s are not
allowed" while its own lane worked on: the hook caught it, and codex told
the lane nothing.

### The fix, and what remains impossible

Every refusal now also **exits 2 with its reason on stderr**. Per
[the hooks reference](../../../seon/reference/claude-code-hooks-2026-09-17.md)
§2, exit 2 blocks unconditionally and takes precedence over any JSON, with
the reason read from stderr; it is the one channel a harness cannot silently
drop. The JSON keeps both shapes: a PreToolUse refusal now carries
`hookSpecificOutput.permissionDecision: "deny"` with
`permissionDecisionReason` (Claude Code's current form) AND the top-level
`decision`/`reason` pair (what codex honours today) — the SAME text in both,
because the reason is the thing the agent has to act on.

Second lever, in the same commit: **the derived scan now runs on every
PostToolUse, including a payload-named `apply_patch`**, skipping the paths
the payload already named. A codex lane that breaks a file with `sed` is
therefore told at its next patch, even if codex never surfaces the shell
event's own block. `record-edited-digests!` dissolved into this — one
mechanism now covers both.

Cannot be fixed from here: codex has no event for "the model is about to
run a shell command that writes", so a codex shell write can never be
refused before it lands. And whether codex surfaces exit 2 on
`post_tool_use` is not yet proven — the probe predates the change.

### Claude Code, for contrast

`.claude/settings.json` hook changes are re-read by the running session's
file watcher, so stale hook config is a codex-only question. A subagent's
tool calls fire the PARENT session's hooks with `agent_id` and `agent_type`
populated; the hook now records both in its per-session digest file
(`:seon.hook/agent-id`, `:seon.hook/agent-type`) and in every
`DERIVED_WRITE` and tool-event log line, so lane-ness is readable from the
hook's own record without a second registry.

## Loophole inventory, from tonight's evidence

| loophole | what it bypasses | evidence | can the harness refuse it? | state |
| --- | --- | --- | --- | --- |
| Shell write (`apply_patch` heredoc, `sed`, `python`, `cat >`) | the whole pre-write lint, and publication | rollout ordinal 947, 2026-09-17T00:05:18.534Z | Not before the write; yes immediately after | **CLOSED** post-write: derived digest scan + exit 2. `git checkout`, `git stash`, `git apply` need no special case — the scan reads CONTENT, never the payload, so any of them that restores or writes broken bytes is caught by the same event |
| A write with no tool event at all (owner's own terminal; a background process that writes after its tool returned) | everything | structural | No | **DETECTED ONLY** — and the detector is cross-agent: the next tool event of ANY session sees it. Proven: the codex lane's break reached the orchestrator's Claude session at 02:22:21.955331Z |
| `SEON_TEST_SILENCE_SECONDS=1800/3600` on a lane's own command line | the suite liveness watchdog — a tuned constant the agent can move is not a bound | 11 × `=1800` and 5 × `=3600` across `tmp/orchestrator/*.log`; read at `src/seon/test/runner.clj:530`, with a SECOND authority and a hand-written `"300"` literal at `src/seon/test/cache.clj:26` | Yes | **NOT LANDED (protected)**: both files are concurrently edited. The bound belongs in the declared config the runner already reads, with the env var honoured only to LOWER it; a raise is refused BY NAME using `SEON_CODEX_LANE`, which `bin/codex-agent:353` already exports |
| A lane running the full `bin/test` | the two-slot load cap and the orchestrator's ownership of the cold gate | two killed tonight; `bin/test --all` in lane logs | Yes — the runner already can know: `SEON_CODEX_LANE` | **IN FLIGHT, not mine**: the working copy of `bin/test:237` already refuses a gate from a Codex lane. Uncommitted, owned by the guardrails lane |
| `--paths` that omits a caller | nothing by design — it HIDES a break: HEAD's caller against your new arity is `:invalid-arity`, one of six blocking analysis types, so fixture base construction refuses and every test errors far from the cause | `docs/prds/steward-platform/research/no-default-cluster-fallback-2026-09-16.md:330-338` | Yes, and it is a derivation, not a list: after overlaying, ask the program graph for callers of every changed public arity and refuse when one is absent from `--paths` | **NOT LANDED (protected)**: `bin/test` |
| `datahike.api/with` bypassing admission | the writer's own admission | no current call site outside `seon.db` — I grepped `src/` and the skills and found none, so this is a rule without a live sighting | Yes, as a query, not a lint | **DETECTOR NAMED**: one regression asking `:seon.fn/calls` for `datahike.api/*` edges outside `seon.db`, the store/registry and the classified listeners AGENTS.md §3 already names |
| Ending a turn with a run in flight | nobody reads the result; the lane reports green it never saw | standing failure; tonight's exit=124 slot starvation is the same shape | Yes: Claude Code fires `Stop` and `SubagentStop` | **DETECTOR NAMED**: a `Stop` hook that exits 2 when a `tmp/test-runs/run.*` root is still held by a live pid this session started. Not landed — it adds a third hook event and wants its own proof |
| Committing with "not gated" | the gate | tonight's lane reports | No — refusing every `git commit` is wrong, and the hook cannot tell a gated commit from an ungated one | **CANNOT BE REFUSED, ONLY DETECTED**: every canonical gate records what it ran on `:current-src` (`seon.test.runner/commit-results!`, `src/seon/test/runner.clj:1435`), so "was this commit's reach ever run green" is a Datalog query, not a promise in a report |
| Writing a file another lane holds | the protected-path convention | tonight's protected-path stops | Not without a claim record — nothing records who holds what | **DETECTOR FALLS OUT OF THIS WORK**: the per-session digest files now record which session last changed each path. Two sessions changing one path is a query over `tmp/session-digests/*.edn` |

Two of these are the same disease as the hook's own: a bound an agent can
move is not a bound, and a report that says green is not evidence. Both are
fixed the same way — put the decision at the authority that can refuse, and
let the claim be derived rather than asserted.

## Verification boundary

`bin/test-fast seon.dev.edit-feedback-test seon.dev.hook-test` — iteration,
not the isolated gate. Those namespaces drive the real `bin/seon-hook`
subprocess with real JSON events and a real `clj-kondo`, which is the
harness the hook runs on. No `bin/test`, no prepl, no cluster was used, per
the assignment. `bin/test --paths … -- …` and `--platform` were explicitly
out of scope for this lane and have NOT been run.
