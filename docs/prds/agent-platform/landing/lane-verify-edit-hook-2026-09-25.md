---
type: landing
status: complete
lane: verify-edit-hook-opus
created: 2026-09-25
---

# Lane verify-edit-hook-opus (2026-09-25): edit-hook verification and fixes

The owner asked us to make sure the edit hook works: automatic code fixing,
linting and error feedback. I drove `bin/seon-hook` with hook JSON on stdin in
the shapes Claude Code and Codex send. I also drove it live through this
session's own Edit and Write tools. All test edits went under
`tmp/verify-edit-hook/`. Default's JVM was not touched: the tmp paths are
outside `src/` and `test/`, so the contract check never ran for them.

## Broken, first

1. **The Gemini review had been dead since 2026-09-22T21:47Z.** Commit
   `72fa85fc3` removed the `try` around `process-alive?`. After that,
   `(long nil)` threw on the missing worker record every time a PostToolUse
   tried to open a window. The log shows 11 empty `REVIEW_PENDING_LOCK_ERROR`
   lines in its 95-line window, and `tmp/reviews/` has nothing newer than
   `20260922T214711`. The regression
   `post-edit-coalesces-one-optional-review-worker-and-drops-failure` has been
   red the whole time. It fakes `agy`, and the same scenario replayed by hand
   calls the fake twice. **Fixed (`6199c2349`).** The review is now **disabled**
   in the config. Fixing the crash alone would have restarted `agy` calls,
   which breaks the owner's 2026-09-24 no-paid-call rule. Turning it back on
   is the owner's decision.
2. **Every Markdown edit took about 2 s and buried its own findings.** The
   hook walked every doc for dependency pins (1554 ms). It then led its
   feedback with 66 stale pins from 46 other files, so the 300-token budget
   elided the edited file's own errors. **Fixed (`af3246bde`).**
3. **MultiEdit was never linted.** It was missing from the hook's edit set.
   PreToolUse passed it with exit 0 and PostToolUse left its named path out of
   the derived scan. **Fixed (`a264a5f67`).**
4. **A shell-write refusal was handed to the wrong agent.** The digest
   baseline was the session's last PostToolUse, and subagents share their
   parent's session id. The Codex lane rollout `01a0d908…`, at ordinals
   139 and 153 (14:48:50Z and 14:49:04Z), had its read-only
   `nl -ba reference-code/… | sed` results replaced by
   `Bash left unreadable Clojure … test/seon/fn_test.clj:506`, a file a
   Claude subagent had broken. The orchestrator (`agent=nil`) received the
   same refusal. **Fixed (`4fa391ff8`).**
5. **An internal hook failure was invisible to the agent.** The top-level
   catch printed `HOOK ERROR` to stderr with exit 0. Neither harness shows
   that to the model. **Fixed (`cde266d6f`).**
6. **The config comments were false.** They claimed "no running Seon" and "no
   numeric fallback". **Corrected (`bf19a700c`).**

## Behaviour table

Unless the row says otherwise, "driven" means a JSON event through
`tmp/verify-edit-hook/drive.sh` with config `tmp/verify-edit-hook/hook.edn`,
which is the real config with review off and a tmp scan root.

| # | Claimed | Observed | Evidence |
|---|---|---|---|
| 1 | Pre-edit syntax check refuses unbalanced delimiters and names the path | **Yes**, for Edit, Write and Codex `apply_patch`. Exit 2; stderr and `decision/permissionDecision` carry `path:row:col [error/syntax]` | Driven e1/e2/e5 → exit 2. Live: this session's Edit dropping a `)` in `tmp/verify-edit-hook/veh/a.clj` returned `PreToolUse:Edit hook error: BLOCKED: … a.clj:3:1 [error/syntax] Found an opening ( with no matching )` |
| 1b | MultiEdit is covered | **No, before the fix**: exit 0 `{"continue":true}` on a paren-dropping MultiEdit (e4). **Yes, after**: exit 2 naming the path (m1); a valid MultiEdit passes (m2) | New regression `pre-edit-blocks-a-multi-edit-that-leaves-unreadable-clojure` |
| 1c | An Edit whose `old_string` is absent | exit 0: the hook leaves the refusal to the tool, which fails "string not found" | e7 |
| 2 | Post-edit clj-kondo feedback arrives as additional context | **Yes**: `hookSpecificOutput.additionalContext` with findings (`invalid-arity`, `unused-binding`, `unused-private-var`), followed by the docstring lint | Driven p2. Live: `PostToolUse:Edit hook additional context: clj-kondo findings: test/seon/dev/edit_feedback_test.clj:456:20 [warning/shadowed-var] …` arrived in this session |
| 2b | Within the configured feedback bound | **Per message only.** `:feedback :max-tokens 300` clips each message (kondo, docstring, Markdown) separately. One event can carry about 3 × 300 tokens. `:max-advisory-findings` (20) is a code fallback with no config key | `clip-feedback` and `findings-feedback` in `bin/seon-hook` |
| 3 | Markdown lint on docs/ and `.agents/skills/`; docstring lint on `.clj/.cljs/.cljc` | **Yes.** `AGENTS.md` and other Markdown outside those trees is not linted (`authority-markdown-file?`). `.bb` and `.edn` get kondo but no docstring lint | Driven p4 (`docs/n.md`) and p2 |
| 4 | Shell writes (`sed -i`, `cat >`, python) to a .clj are derived by digest walk and blocked on a syntax error | **Yes**: exit 2 naming the path and the tool, for sed (s1), python (s2) and `cat >` under a Codex session id (s3). Live in production: `DERIVED_BLOCK … fn_test.clj:506:79` at 14:48:48Z | Existing regression `a-shell-write-the-hook-was-never-handed-is-derived-and-refused` |
| 4b | The writer is told, not a bystander | **No, before the fix** (see Broken #4). **After**: the baseline is taken at PreToolUse per session plus `agent_id`. The writer gets the block and a later agent does not | Driven fA/fB. New regression `a-derived-write-reaches-the-agent-whose-tool-call-spanned-it` |
| 5 | "Automatic code fixing" | **Markdown formatting only**: trailing whitespace, repeated blank lines, blanks around headings and fences, final newline (`seon.dev.markdown/fix`), rewritten in place after the edit. **Clojure is never repaired**: no parinfer, parinferish, cljfmt or delimiter repair exists. A broken Clojure edit is refused (Pre) or reported (Post). Before the fix the Markdown rewrite was silent; now the agent is told | Live: `Markdown auto-fix rewrote tmp/verify-edit-hook/docs/live.md (2 formatting rule(s) …)`. Claude Code adds its own "PostToolUse hook modified … after your edit" notice |
| 6 | Gemini review: one coalesced async batch per 120 s | **It made no calls from 2026-09-22T21:47Z to this fix** (Broken #1). When it ran, it made one `agy -p <prompt>` call per 120 s window with edits, so at most 30 an hour. Each prompt carried the four SKILL.md rubrics (up to 60 k chars each) plus up to 200 k chars of files. The output went only to `tmp/reviews/*.md`, never into the agent's context. 31 reviews exist, all dated 2026-09-22. Per-call price: none is recorded in the repo; `agy` bills the Google account. **No paid call was made to find this out.** It is now disabled | `call-agy`, `run-review-worker!`, `ls tmp/reviews` |
| 7 | Latency well under a second | **Yes** (table below). Markdown is the slowest at about 0.5 s | 5 runs per row |
| 8 | Codex trust and matchers | `bin/codex-agent:432,445` pass `--dangerously-bypass-hook-trust` on run and resume, so the stale trust entry in `~/.codex/config.toml` does not matter. `.codex/hooks.json` uses `.*` on Pre and Post with `./bin/seon-hook`, and lanes start at the repo root. Hooks fire in live Codex lanes: rollout `01a0d908…` received the hook's refusal text. The snapshot at session start (reference doc §6) still means a `.codex/hooks.json` change needs a lane stop and resume; edits to `bin/seon-hook` take effect immediately | `docs/seon/reference/codex-cli-hooks-2026-09-17.md` §0/§6/§8; `grep -c 'unreadable Clojure'` on that rollout = 2 |
| 9 | Internal failure is loud | **No, before the fix** for edit events (stderr only). **After**: `additionalContext: "HOOK ERROR — this PostToolUse check of … did not run: …"`. A Bash event already blocked | Driven with a missing config. New regression `a-hook-failure-reaches-the-agent-context` |

## Timings

Median of 5 runs, wall clock including the `bb` start. Measured after all fixes.

| Event | Exit | Median ms | Range |
|---|---|---|---|
| Pre Edit .clj (valid) | 0 | 151 | 150–155 |
| Post Edit .clj | 0 | 172 | 171–174 |
| Pre Write .clj (broken) | 2 | 150 | 149–151 |
| Pre apply_patch .clj | 0 | 151 | 148–163 |
| Post Write .md (docs/) | 0 | 485 | 478–774 |
| Pre Bash / Pre Read | 0 | 129 / 128 | 127–134 |
| Post Bash (no write) / Post Read | 0 | 151 / 153 | 149–155 |

Before and after:

- **Markdown Post**: 1964 ms before, 485–734 ms after.
- **Non-edit PreToolUse**: 75 ms before, about 128–150 ms after. Fix #4 added a
  digest walk on every PreToolUse. The walk itself is 60 ms over 693 files.
- **Contract compile** against default (src/test edits only, from the log): 45–204 ms.

## Fixes (commits)

| Commit | What |
|---|---|
| `6199c2349` | `process-alive?` treats a missing worker record as idle; the lock error logs its class; `:review :enabled false` |
| `af3246bde` | Markdown: the edited file's own pins via `validate-file ::repository-root`; `:dependency-pin-current` joins the default rules (it never ran from `validate` before); the auto-fix is reported; linter exceptions become advisories. Test assertion in `script/seon/dev/markdown_test.clj` |
| `a264a5f67` | MultiEdit reconstruction and edit membership, plus a regression |
| `4fa391ff8` | Derived-scan baseline at PreToolUse per session plus `agent_id`, plus a regression |
| `cde266d6f` | HOOK ERROR goes into `additionalContext`, plus a regression |
| `bf19a700c` | The config comments are true |

Net lines: source (`bin/seon-hook`, config, `markdown.clj`) +128/−61, which is
+67 net and about half comments. Tests +94.

## Open items and verification limits

- **Root runs** (lanes run no tests):
  `bin/test-check default --ns seon.dev.edit-feedback-test`. This covers the
  three new regressions plus the review test, which is red before `6199c2349`.
  The Markdown tests are Babashka tests under `script/`; I ran
  `repository-pin-subjects-follow-working-tree-renames` and
  `dependency-pin-current-rule-test` there (0 failures).
- `repository-dependency-pins-are-current-test` is red: 66 stale pins in 46
  dated docs. Filed as
  `docs/seon/issues/repository-pin-check-reads-dated-landings-as-living-claims.md`.
- The Markdown event is still about 0.5 s. About 330 ms of that is the vault
  tag and type vocabulary walk in `validate-file` (`::vault-root`).
- The feedback bound is per message, not per event (row 2b). Applying it
  once per event would fit the "limits once" law; that needs an owner ruling.
- The derived scan still reports a write by another agent when it lands
  inside this agent's own tool call. A write from a background process that
  finishes after its tool call is now caught by no one.
- The review output never reaches an agent. If the owner turns it back on,
  the useful form is probably feedback on the next event, not files in
  `tmp/reviews/`. 50 stale entries sit in `tmp/.pending-review`.
- The Pre contract compile runs in default's live JVM for every src/test edit
  (45–204 ms). That is the one "no running Seon" exception. It fired for my
  own `test/seon/dev/edit_feedback_test.clj` edits; they were read-only
  compiles.
- MultiEdit is not a tool in this Claude Code 2.1.221 session. It is still
  documented as a hook tool name, so the fix is coverage for a payload that
  may not arrive.
