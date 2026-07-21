---
type: research
status: completed
tags: [research, orchestrator, agent]
---

# AGENTS.md and CLAUDE.md instruction unification audit

## TL;DR

The repository can safely make `AGENTS.md` the maintained authority and replace
each same-directory `CLAUDE.md` with the relative symlink `AGENTS.md` on macOS,
Linux, and WSL's Linux filesystem. Claude Code documents this exact arrangement,
and the installed Claude Code 2.1.208 loaded both a root and a nested
`CLAUDE.md -> AGENTS.md` probe. Current Codex source explicitly permits
symlinks, and installed Codex 0.144.2 loaded a symlinked `AGENTS.md` probe.

Do **not** perform a blind rename-and-link. The tracked corpus is not equivalent:
the root pair has materially diverged, ten localized directories are
`CLAUDE.md`-only, and runtime-reliability is `AGENTS.md`-only. Several localized
files also contradict the live branch or themselves. Merge and refresh the
regular files first, especially after the outstanding autosuggest-branch audit
lands; only then replace every `CLAUDE.md` atomically with a symlink.

The two clients do not have identical native nesting semantics. Claude launched
at the repository root loads descendant `CLAUDE.md` files on demand when it
reads within those subtrees. Codex discovers `AGENTS.md` only from the Git root
down to the selected working directory. A root-launched Codex session did not
receive a nested marker after reading a nested file. Therefore the unified
storage system needs two Codex accommodations: raise `project_doc_max_bytes`
and put an early root rule requiring Codex to read the applicable nested
`AGENTS.md` before working below the root. For guaranteed native loading, launch
Codex with `--cd <subtree>`.

There is also a live truncation bug in Seon's current configuration. The root
`AGENTS.md` is about 12.8K estimated tokens, while Codex's default project-doc
budget is only about 8.2K estimated tokens. Installed Codex warned that it
truncated the root and then omitted the closer nested file entirely. Setting
`project_doc_max_bytes = 131072` gives about 32.8K estimated tokens of combined
root-to-working-directory capacity and made the nested probe load. This setting
belongs in the trusted project `.codex/config.toml` as part of the conversion.

Native Windows is conditional. Git with `core.symlinks=false` checks a symlink
out as a small ordinary file containing `AGENTS.md`; Claude would read that as
prose, not as an import. Require Developer Mode or administrator-created links,
`core.symlinks=true`, and the structural check in this report. If unrestricted
native-Windows checkout is a requirement, use one-line `@AGENTS.md` import stubs
instead of symlinks on all platforms.

## Scope and method

The audit read:

- every tracked `AGENTS.md` and `CLAUDE.md` outside `reference-code/`;
- every project `.claude/` and `.codex/` configuration and subagent definition;
- the ignored worktree, build-output, virtual-environment, and temporary copies
  found by a repository-wide filesystem inventory;
- the fresh OpenAI Codex manual and current `openai/codex` source;
- Anthropic's current Claude Code memory and permissions documentation;
- Git and Microsoft platform documentation; and
- the current branch, active PRD roadmaps, architecture documents, route seeds,
  and source paths needed to falsify instruction-file claims.

No external LLM was consulted. The client probes used distinctive inert markers
in temporary ignored fixtures, then removed the fixtures. No instruction file
was changed by this audit.

## What each client actually does

### Claude Code

Anthropic documents all three properties needed here:

- Claude reads `CLAUDE.md`, not `AGENTS.md` directly.
- It explicitly recommends `ln -s AGENTS.md CLAUDE.md` when no Claude-only
  addendum is needed.
- It walks ancestors at launch and discovers descendant `CLAUDE.md` files on
  demand when it reads files in those descendants.

The same page recommends the `@AGENTS.md` import form on Windows because native
symlink creation may require Developer Mode or administrator privileges. See
[How Claude remembers your project](https://code.claude.com/docs/en/memory).
Claude's permissions layer checks both a symlink path and its resolved target;
a same-directory link to a committed authority file does not expand access. See
[Configure permissions](https://code.claude.com/docs/en/permissions).

Installed-client probe, Claude Code 2.1.208:

```text
CLAUDE.md -> AGENTS.md
nested/CLAUDE.md -> AGENTS.md

ROOT-AUTHORITY-MARKER-7f8e
NESTED-AUTHORITY-MARKER-93c1
```

The second marker appeared only after Claude's Read tool accessed
`nested/probe.txt`, proving both file-symlink resolution and lazy descendant
loading in the installed client.

### Codex

The fresh Codex manual says discovery is Git root to current working directory,
with at most one selected instruction file per directory and a default combined
budget of about 8.2K estimated tokens. It does not claim Claude-style lazy
descendant discovery. See
[Custom instructions with AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md.md).

Current `openai/codex` source is stronger evidence for symlinks than a shell
probe alone. `codex-rs/core/src/agents_md.rs` labels the discovery function
“Symlinks are allowed,” checks metadata for a file, and reads the candidate
through the executor filesystem. Its tests also cover a symlinked working
directory. See
[current agents_md.rs](https://github.com/openai/codex/blob/main/codex-rs/core/src/agents_md.rs)
and
[current agents_md_tests.rs](https://github.com/openai/codex/blob/main/codex-rs/core/src/agents_md_tests.rs).

Installed-client probes, Codex 0.144.2:

```text
# Default budget, root AGENTS.md larger than remaining capacity
WARN codex_core::agents_md: project doc exceeds remaining budget; truncating

# With project_doc_max_bytes=131072
CODEX-SYMLINK-MARKER-a61d

# Root-launched session after reading a nested ordinary file
NOT-INJECTED
```

The first run is especially important: once the root consumed the complete
budget, Codex never read the closer nested symlink. The second run proves direct
file-symlink handling when capacity exists. The third run proves that accessing
a descendant does not change the instruction chain in the installed client.

### Resulting compatibility contract

| Property | Claude Code | Codex |
|---|---|---|
| Maintained filename | `CLAUDE.md` loader sees the symlink target | `AGENTS.md` directly |
| Same-directory file symlink | Documented and locally proven | Source-explicit and locally proven |
| Ancestors | Filesystem root to launch directory | Git root to selected working directory |
| Descendants after launch | Loaded on access | Not automatically loaded |
| Relevant verification | `/memory` or `InstructionsLoaded` hook | launch with `--cd`, inspect startup warning/session sources |
| Capacity behavior | Loads full files; concise files recommended | Combined hard cap; later files disappear when exhausted |

The storage layout can therefore be unified. The discovery behavior cannot be
made literally identical by symlinks or a fallback-filename setting.

## Complete tracked inventory and merge decision

There are thirteen tracked instruction files across twelve directories. Only
the repository root currently has both names.

| Directory | Current tracked files | Semantic state | Merge decision before linking |
|---|---|---|---|
| `/` | `AGENTS.md`, `CLAUDE.md` | Materially divergent shared copies | Merge into one tool-neutral `AGENTS.md`; replace `CLAUDE.md` with `AGENTS.md` symlink |
| `docs/prds/agent-ctx/` | `CLAUDE.md` | Rich historical index but stale status | Move into `AGENTS.md`, mark the chunk completed, remove live-lane ownership claims, then link |
| `docs/prds/agent-fsm/` | `CLAUDE.md` | Its completed/historical framing is mostly right | Move into `AGENTS.md`, fix stale route and gym references, then link |
| `docs/prds/diffusion-dynamic-context/` | `CLAUDE.md` | Valuable depth plus contradictory chronological status | Rebuild a tight current index in `AGENTS.md` from current design/roadmap evidence, then link |
| `docs/prds/repl-autosuggest/` | `CLAUDE.md` | Pre-integration branch handoff | **Wait for the autosuggest integration agent**, merge its newer decisions and current branch reality into `AGENTS.md`, then link |
| `docs/prds/runtime-reliability/` | `AGENTS.md` | Current active authority | Preserve it, incorporate only genuinely newer autosuggest facts, add `CLAUDE.md -> AGENTS.md` |
| `src/my/` | `CLAUDE.md` | Timeless guidance with an incomplete live catalog | Move to `AGENTS.md`, add the active `my.plan` owner and recheck the complete live namespace set, then link |
| `src/seon/` | `CLAUDE.md` | Strong one-mechanism table with stale lane/current-PRD pointers | Refresh database-server paths and runtime-reliability pointers in `AGENTS.md`, then link |
| `src/seon/agent/` | `CLAUDE.md` | Useful invariants with removed filenames | Refresh the subsystem inventory in `AGENTS.md`, then link |
| `src/seon/ai/` | `CLAUDE.md` | Useful provider contract with missing current surfaces/skill | Refresh provider ownership in `AGENTS.md`, then link |
| `src/seon/render/` | `CLAUDE.md` | Mostly timeless; one stale render-key description | Correct `:seon.render/html` versus response `:seon.render/hiccup`, move, then link |
| `src/seon/web/` | `CLAUDE.md` | Useful SSE rules with stale route/debug truth | Refresh from route datoms and current debug/view-unit source, move, then link |

Every desired link target is exactly the same relative string:

```text
CLAUDE.md -> AGENTS.md
```

Using the same-directory relative target makes links relocatable across clones,
worktrees, macOS, Linux, and WSL. It also keeps link resolution inside the
already-authorized directory.

## Root-pair semantic merge

The root files share most runtime, data, instrumentation, error, process, and
testing material. The drift is not a simple tool-name substitution.

### Content unique to CLAUDE.md that must not be lost

- the stronger implementation-model ruling and its dated history;
- the nested instruction-file maintenance standard;
- the canonical vocabulary table;
- the owner “do not write hacks” root-cause directive;
- configuration-resolves-into-the-database ownership;
- the exactly-two-testing-surfaces rule;
- the disposable `cluster fork` lifecycle; and
- several functions-not-verbs corrections.

These should be merged by meaning, not copied verbatim. The model policy must be
tool-qualified: Claude model aliases such as Opus, Fable, and Haiku do not name
Codex models. The nested standard should name `AGENTS.md` as authority and
`CLAUDE.md` only as its compatibility symlink.

### Content unique to AGENTS.md that must not be lost

- Codex-specific shared-instance/subagent framing at the top; and
- the partially migrated `AGENTS.md` documentation wording.

The top should become cross-tool: every coding-agent instance reads the same
authority, Claude through the symlink. Tool-specific behavior belongs in short
explicit subsections rather than pretending one client's model names or loader
semantics apply to the other.

### Root content that must be corrected during the merge

- Do not retain the claim that Codex auto-loads a descendant whenever it edits a
  file. It does not in the installed client.
- The vocabulary table's GET `/agents` page is stale. Current route datoms define
  `/` as root's dashboard, `POST /agents` as the creation door, and
  `/agent/{id}` as an ordinary agent page.
- The web-UI implementation list must not omit `router.cljs`, `datastar.cljs`,
  and the current view-unit mechanism; the deepest `src/seon/web/AGENTS.md`
  should own the detailed list.
- Keep the two-surface testing ruling and remove all surviving claims that gym
  is a third harness.
- Put the Codex nested-file discovery rule and project-doc capacity warning near
  the beginning, before any possible truncation boundary.

The merged root should be shortened rather than becoming the union of both
files. Deep ownership facts belong in the localized authorities listed above;
the root should carry universal invariants and links.

## Localized-content freshness findings

### PRD indexes

- `agent-ctx/CLAUDE.md` says `status: active`, calls three lanes active, and
  points to `agent-ctx` as live status. Its roadmap says `status: completed` and
  begins with a 2026-07-12 closeout. The new `AGENTS.md` should be a compact
  completed/historical index.
- `agent-fsm/CLAUDE.md` correctly says the chunk merged and is history, while
  its roadmap still says `status: active`. Its route footnote claims a fleet
  roster at `/agents`, and its delivery list retains the retired gym wording.
  Preserve the historical index but correct those references; separately fix
  the roadmap frontmatter when that doc is next maintained.
- `diffusion-dynamic-context/CLAUDE.md` says escalation is built at the top and
  later says the same escalation is unbuilt. It says Fable agents are cleared
  for all work despite the newer root Opus ruling, and calls gym a third testing
  surface despite runtime-reliability retiring it. Its long status chronology
  violates the root rule that a PRD index gets one dated current-state
  paragraph. Re-derive one current paragraph from
  `planner-worker-design.md`, `typeahead-design.md`, the roadmap, and live
  source; retain hard-won findings and the runbook, not the diary.
- `repl-autosuggest/CLAUDE.md` is a valuable 2026-07-13 branch handoff, but the
  runtime-reliability authority now says only five reviewed autosuggest
  implementation commits were integrated and rejects compact-context,
  local-ACME/src-needle, and removed-markdown-path changes. The user's separate
  autosuggest audit is still in flight. Converting this file before that result
  lands risks blessing stale or deliberately excluded branch content.
- `runtime-reliability/AGENTS.md` is the only localized file already on the
  desired authority name and is the current branch's index. It explicitly
  records the partial autosuggest integration and must win over older PRD
  snapshots where they conflict.

Three other PRD folders advertise active roadmap frontmatter but have neither
instruction filename: `agent-fsm`, as noted above, does have a completed
`CLAUDE.md`; `namespace-ui` and `refinement` have no localized instruction file
at all. Decide whether those roadmaps are truly active. If yes, give each a
tight `AGENTS.md` plus symlink. If no, correct their status. The root claim that
every active PRD has localized context is currently false.

### Source indexes

- `src/seon/CLAUDE.md` still calls `docs/prds/agent-ctx/roadmap.md` the only
  we-are-here document. The active authority is runtime-reliability. Its lane
  table calls `server/*.clj` the JVM wire server although the retained writer
  now lives under `src/seon/db/*.clj`; `src/seon/server/` has no tracked source.
  Keep the one-mechanism table but refresh it against the runtime-reliability
  database namespace ruling.
- `src/seon/agent/CLAUDE.md` points to removed `inspect.cljs` and `todo.cljs`.
  The debug projection is `debug.cljs`; current agent source also includes
  `home`, `runtime`, `shell`, `web`, and other owners absent from its systems
  list. Rebuild the inventory from tracked source while retaining its loop,
  run, turn, frozen-database, and never-throw invariants.
- `src/my/CLAUDE.md` omits `my.plan` from its current catalog even though
  `src/my/plan.cljs`, config, architecture, and the active PRDs depend on it.
  Add it and verify the rest of the live `src/my/*.cljs` set mechanically.
- `src/seon/ai/CLAUDE.md` names a `claude-api` skill that is not present in the
  installed project skill corpus and omits the ownership role of
  `dispatch.cljs` and `typeahead.cljs`. Refresh the adapter list without
  turning optional providers on.
- `src/seon/render/CLAUDE.md` describes the human view as
  `:seon.render/hiccup -> surface`. Current architecture and source select the
  renderer with `:seon.render/html`; the returned html-response carries
  `:seon.render/hiccup`. Preserve the one-walker/two-view rule with the correct
  keys.
- `src/seon/web/CLAUDE.md` says historical debug turns are future work even
  though `seon.agent.debug/turn` and `turn-diff` exist. Its route truth omits the
  current view-unit and `/data` feed work and should be reconciled directly
  against `seon.route/core-routes-tx` plus `seon.web.router`.

## Project configuration and other duplicated guidance

Neither project config changes instruction discovery today:

- `.claude/settings.json` installs edit hooks; it has no `claudeMdExcludes`,
  additional-directory memory setting, or instruction remapping.
- `.codex/config.toml` configures only the Seon MCP server. It does not raise
  `project_doc_max_bytes`.
- `.claude/seon-hook.edn`, `.claude/settings.json`, and `.codex/hooks.json`
  share the current direct-hook mechanism. The instruction conversion should
  not create another hook or loader.

The Claude and Codex subagent definitions remain separate format adapters, not
instruction-file counterparts. They already drift materially:

- `.claude/agents/seon-agent.md` says the hook automatically reloads code,
  failing tests block progress, and REPL testing is preferred.
- `.codex/agents/seon-agent.toml` has the newer advisory-hook semantics,
  `bin/seon test changed`, and current focused test doors.

Update the Claude adapter from the Codex adapter's current semantics in the same
maintenance unit, and make both say `AGENTS.md` is the authority exposed to
Claude through `CLAUDE.md`. Do not try to symlink these files: their metadata
formats differ.

Maintained human entry points also still present `CLAUDE.md` as the authority,
not merely a compatibility name: `README.md`, `CONTRIBUTING.md`, and
`ORCHESTRATOR.md`. The symlink keeps those links functional, but their wording
should move to `AGENTS.md` so future contributors edit the right file.

## Generated, copied, and excluded files

These are not conversion targets:

- `.claude/worktrees/*/CLAUDE.md` is ignored worktree state. The two current
  copies differ from the active root and should disappear with their worktrees,
  not be merged.
- `target/database-server-classes/**/CLAUDE.md` and
  `target/wire-classes/**/CLAUDE.md` are ignored build outputs copied from
  `src/`. Some are current and some are stale. Rebuild after conversion; never
  edit or link them by hand. A resource copier may dereference the source
  symlink into an ordinary generated file, which is harmless because `target/`
  is not an instruction authority.
- `src-inspect-ai/.venv/` and `tmp/*venv*/` contain third-party package
  instruction files. They are ignored dependencies.
- `reference-code/` contains upstream submodule instruction files. The task
  explicitly excludes them, and changing them would mutate upstream grounding.

No repository generator currently creates the tracked root or localized
instruction files.

## Platform and Git risk analysis

### macOS and Linux

Git records each link as mode `120000` with link text `AGENTS.md`. Ordinary file
open and metadata calls resolve it. Both installed clients passed. This is the
recommended path.

### WSL

WSL's Linux filesystem follows normal Linux symlink semantics. Microsoft
recommends keeping Linux-tool projects inside the WSL filesystem rather than
`/mnt/c`; this also avoids DrvFS metadata and NTFS reparse-point differences.
See
[Working across file systems](https://learn.microsoft.com/windows/wsl/filesystems)
and
[WSL file permissions](https://learn.microsoft.com/windows/wsl/file-permissions).

### Native Windows

Git's documented `core.symlinks=false` behavior is to check links out as small
ordinary files containing the link text. See
[git-config core.symlinks](https://git-scm.com/docs/git-config#Documentation/git-config.txt-coresymlinks).
Windows can create file symlinks with Developer Mode or appropriate privileges;
see
[CreateSymbolicLink](https://learn.microsoft.com/windows/win32/api/winbase/nf-winbase-createsymboliclinka).

Consequences:

- a proper checkout with `core.symlinks=true` works;
- a degraded checkout leaves `CLAUDE.md` containing only `AGENTS.md`, which is
  **not** Claude's `@AGENTS.md` import syntax and silently loses instructions;
- CI and onboarding must fail fast on a non-link rather than accepting this
  degraded state; and
- teams that cannot require symlink-capable Windows checkouts should choose
  identical one-line `@AGENTS.md` stubs on every platform instead.

## Ordered atomic implementation plan

1. **Wait for the autosuggest integration audit.** Reconcile the newer branch
   fixes with the runtime-reliability refactor and update the runtime roadmap.
   Do not overwrite the currently untracked autosuggest research produced by
   another agent.
2. **Freeze and inventory.** Confirm the twelve expected directories, current
   branch, dirty files, and hashes. Treat unrelated changes as owned by other
   agents.
3. **Create regular AGENTS.md authorities first.** For each CLAUDE-only
   directory, copy its semantic content into a new regular `AGENTS.md`. Keep the
   existing runtime-reliability authority. At this stage do not remove any
   `CLAUDE.md`.
4. **Merge and refresh content.** Apply the per-row decisions above. Merge the
   root's unique directives, make tool-specific policy explicit, update active
   PRD and source ownership, and shorten repeated detail. Run the Markdown
   linter while every file is still regular and easy to review.
5. **Close loader gaps.** Add
   `project_doc_max_bytes = 131072` to trusted project `.codex/config.toml`.
   Near the top of root `AGENTS.md`, require Codex to discover and read the
   closest nested `AGENTS.md` before touching a subtree and after compaction.
   Document `codex --cd <subtree>` as the guaranteed native path.
6. **Update maintained pointers/adapters.** Correct `README.md`,
   `CONTRIBUTING.md`, `ORCHESTRATOR.md`, and both subagent adapters so they name
   `AGENTS.md` as authority. Preserve `CLAUDE.md` only as the Claude-compatible
   entrypoint.
7. **Review the semantic diff before linking.** Compare each new `AGENTS.md`
   with the old `CLAUDE.md`; account for every deletion as stale, duplicated, or
   relocated. This is the point to catch lost owner rulings.
8. **Replace all CLAUDE.md files in one filesystem unit.** For each of the
   twelve directories, remove the regular `CLAUDE.md` if present and run
   `ln -s AGENTS.md CLAUDE.md`. Add the missing runtime-reliability link. Do not
   leave an intermediate commit where Claude lacks its filename.
9. **Run the mechanical verification below.** It must pass before staging.
10. **Verify both clients.** In Claude, use `/memory`, then read a file under
    `src/seon/agent/` and the active PRD and confirm both localized sources
    appear. In Codex, launch once at the root to confirm no truncation warning,
    then with `--cd src/seon/agent` and
    `--cd docs/prds/runtime-reliability` to confirm root-to-leaf ordering. Also
    confirm a root-launched task follows the explicit manual-discovery rule.
11. **Rebuild ignored resources and run focused repository checks.** Generated
    target copies may change shape but remain ignored. Run Markdown validation,
    the direct hook checks, and any documentation-link check the current
    operator exposes.
12. **Commit atomically.** Stage only the exact authority files, symlinks,
    pointer/config updates, and the audit-derived documentation changes.

## Mechanical verification

Run from the repository root after conversion:

```bash
dirs=(
  .
  docs/prds/agent-ctx
  docs/prds/agent-fsm
  docs/prds/diffusion-dynamic-context
  docs/prds/repl-autosuggest
  docs/prds/runtime-reliability
  src/my
  src/seon
  src/seon/agent
  src/seon/ai
  src/seon/render
  src/seon/web
)

for d in "${dirs[@]}"; do
  test -f "$d/AGENTS.md" || { echo "missing authority: $d/AGENTS.md"; exit 1; }
  test -L "$d/CLAUDE.md" || { echo "not a symlink: $d/CLAUDE.md"; exit 1; }
  test "$(readlink "$d/CLAUDE.md")" = AGENTS.md || {
    echo "wrong target: $d/CLAUDE.md"; exit 1;
  }
  test -e "$d/CLAUDE.md" || { echo "broken link: $d/CLAUDE.md"; exit 1; }
  cmp -s "$d/AGENTS.md" "$d/CLAUDE.md" || {
    echo "content mismatch: $d"; exit 1;
  }
  git ls-files -s -- "$d/CLAUDE.md" | awk '$1 == "120000" { ok=1 } END { exit !ok }' || {
    echo "Git does not record a symlink: $d/CLAUDE.md"; exit 1;
  }
done

echo "instruction authority/link check passed"
```

Then falsify the two loader paths:

```bash
claude
# /memory, then read src/seon/agent/loop.cljs and the active PRD roadmap.

codex --cd src/seon/agent \
  -c project_doc_max_bytes=131072 \
  "List the AGENTS.md instruction sources in precedence order."

codex --cd docs/prds/runtime-reliability \
  -c project_doc_max_bytes=131072 \
  "List the AGENTS.md instruction sources in precedence order."
```

Absence of a Codex truncation warning is an acceptance condition, not merely a
clean-log preference.

## Acceptance criteria

- Every maintained fact exists in one regular `AGENTS.md`, never in a regular
  `CLAUDE.md`.
- All twelve `CLAUDE.md` paths are Git mode `120000`, target the literal
  same-directory `AGENTS.md`, resolve, and compare byte-equivalent through the
  link.
- Root unique owner directives survive with current, tool-qualified wording.
- Autosuggest findings are reconciled before its localized authority is frozen.
- The active runtime PRD and every live source subtree have correct current
  ownership, names, routes, and runbooks.
- Claude proves root plus lazy descendant loading.
- Codex proves root-to-selected-directory loading without truncation; the docs
  explicitly state it does not natively lazy-load descendants.
- A Windows checkout either preserves all symlinks or fails the mechanical
  check with an actionable instruction to enable symlinks or use import stubs.

## Primary-source evidence excerpts

The short excerpts below preserve the external evidence used; no external LLM
response exists to preserve.

> “A symlink also works if you don’t need to add Claude-specific content.”

Source: [Claude Code memory documentation](https://code.claude.com/docs/en/memory).

> “Discovers AGENTS.md files from the project root to the current working
> directory, inclusive. Symlinks are allowed.”

Source: [OpenAI Codex agents_md.rs](https://github.com/openai/codex/blob/main/codex-rs/core/src/agents_md.rs).

> “If false, symbolic links are checked out as small plain files that contain
> the link text.”

Source: [Git configuration documentation](https://git-scm.com/docs/git-config#Documentation/git-config.txt-coresymlinks).
