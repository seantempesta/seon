---
type: evidence
status: landed
created: 2026-09-23
tags: [agent-platform, skills]
---

# Skill citation checker — schedule row 61a

## Problem

The skills-verify lane (`ce8783a3b` and three re-anchor commits) found skill
anchors stale 3–10 minutes after verification: lines shift under active
lanes. This lane built the check that makes a stale citation fail by name.

## Mechanism

`script/seon/dev/citations.clj` (Babashka) reads every inline code span
`path:N`, `path:A-B` and continuation `:N` in `.agents/skills/**/*.md`. It
checks each citation two ways:

1. **Symbol.** The citation's name comes from a span joined to it
   (`` `sym` (`p:N`) ``, `` `sym`, `p:N` ``, `` `p:N` (`sym`) ``), from the
   paired name of a list (`` `a`, `b` (`p:1`, `:2`) ``), or from an earlier
   name in the same block. Only spans that read as one EDN symbol or keyword
   count. For a Clojure target, clj-kondo's `:var-definitions` supplies the
   row and end row, with `declare` excluded. Any other target is searched
   for the literal text.
2. **Baseline.** The checker reads the target as it was at the commit that
   last wrote the citing line unchanged (`git log -1` on the document, then
   `git cat-file --batch`, following gitlinks into submodules). If the cited
   lines are unchanged, the citation passes. If they moved, the checker finds
   them again by exact content. This check needs no symbol, so it also
   covers citations into function bodies.

The citation formats already in the skills needed no conversion. The only
format edit made four bare filenames (`gc.cljc`, `core.cljc`, `flow.clj`)
into full paths.

Two statuses fail the check: `drifted` (reported as "now at N") and
`missing-file`. `--fix` rewrites line numbers in place. The report also
counts `current`, `contained` (inside the named form but not at its head),
`changed` (cited lines edited since the baseline) and `unverifiable`.

When a target has another writer's uncommitted changes, the checker reads
it at HEAD, unless the commit being checked carries that target. This keeps
another lane's uncommitted hunks from blocking a commit.

The checker caches clj-kondo analysis per content digest under
`tmp/citations/analysis/`. clj-kondo's own `.clj-kondo/.cache` is keyed by
namespace, not content, and stores no end rows, so it cannot answer this.
A disposable root links the checkout's cache through `::cache-directory`.

`bin/seon-hook` runs on PreToolUse for a shell command containing
`git commit`. If the commit carries a skill Markdown file, the hook calls
`commit-request` and `commit-refusal` and blocks on drift. The pathspec is
tokenized as shell words, stopping at newlines and redirections, so a
heredoc message is not read as a pathspec. If a pathspec only the shell can
expand (`$paths`, a glob) is present, the hook checks every changed or
staged skill file.

## Proof

- **Deliberately shifted anchor.** Changed `.agents/skills/datahike/SKILL.md:296`
  from `src/seon/db.clj:4590` to `:4580`. The hook exited 2 with
  `SKILL.md:296 \`src/seon/db.clj:4580\` (seon.db/transact!) drifted: now at 4590`.
  `--fix` rewrote it to `:4590` (0.60 s wall), and the hook then answered
  `{"continue":true}`.
- **Live drift during the lane.** Three other lanes committed `db.clj` (+37),
  `cluster.clj` (+33, then +42), `test_support.clj`, `fn.clj` and
  `AGENTS.md` (+6, `85602530d`) while this lane ran. Each shift appeared in
  the next run as named drift, and `--fix` repaired it:
  - `7f9ef5865`: 50 citations re-anchored.
  - The AGENTS.md shift: 20 more.
  Before landing, all rewrites were compared against HEAD by definition row
  or content, for example `git show HEAD:src/seon/cluster.clj | sed -n 2770p`
  prints `(defn recover-runs!`.
- **Regressions.** `script/seon/dev/citations_test.clj` has 4 tests and 25
  assertions, all green. They cover parsing, drift detection and fix,
  relocation of a symbol-less body citation, reading at HEAD, the commit
  gate, the heredoc message and the `$paths` pathspec.
  Run: `bb -cp script -e "(require 'clojure.test 'seon.dev.citations-test) (clojure.test/run-tests 'seon.dev.citations-test)"`.
- **Full run, all 587 citations at the landing tree:** 567 current, 20
  drifted, all fixed. The two held skill files (`clojure-testing`,
  `codex-lanes`) were left for realities.

## Timings

The machine load average was 10–14 during these runs, with other lanes
active.

| Operation | Wall | Proportional to / justification |
|---|---|---|
| Full check, warm (78 cache hits) | 0.86–0.98 s | 587 citations, 78 Clojure targets, ~240 (commit, target) blobs read through two `cat-file` batches |
| Full check, cold (78 misses) | 2.07 s | One clj-kondo lint per cited Clojure file (~150 ms each, 12 concurrent). Runs only when the content digest or the kondo version changes. |
| One skill (datahike, 141 citations), warm | 0.26–0.36 s | — |
| Hook, commit carrying the datahike skill | 0.49 s, against 0.19 s for a non-skill commit | +0.30 s. The criterion was under 1 s. |
| Hook, commit with no skill file | 0.19 s, against 0.14 s for `git status` | +0.05 s: one `git diff --name-only` |
| `--fix` over 20 files | 1.6–2.1 s | A check, the rewrite, then the verifying re-check. Proportional to 2× the full check. |
| Test namespace | 1.4–1.8 s | 4 fixture git repositories, about 60 git subprocesses. Each test is under 1 s. |

## Limits

- **Uncommitted edit window.** A citing line edited since the document's last
  commit has no baseline, so only its symbol verifies it. `--fix` output is
  also uncommitted until the skill commit lands. Commit fixes promptly: the
  baseline is then the new commit.
- **Weak symbols.** A symbol-less citation still has no symbol (17 across the
  checked files at landing). It is verified only through its baseline once
  committed. A literal-text match on a cited line counts as current even if
  the association was accidental.
- **Commits outside the hook.** The hook sees the shell command, not git's
  index. A commit made outside the Claude/Codex tool hooks is not gated.
- **Cache growth.** `tmp/citations/analysis/` gains one small EDN file per
  analysed content. It is disposable, and nothing sweeps it yet.

## Paths

- `script/seon/dev/citations.clj` (new)
- `script/seon/dev/citations_test.clj` (new)
- `bin/seon-hook`
- `.agents/skills/**`: every file except `clojure-testing/SKILL.md` and
  `codex-lanes/SKILL.md`
- this note

## Commits

- `3a8f2e6d7`: checker and hook
- `7f9ef5865`: re-anchor pass
- the follow-up commits named in the lane report
