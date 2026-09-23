# Lane shrink-citations (2026-09-23)

The skill citation checker (3a8f2e6d7, 72d968b7c) was 1,048 script lines, a
169-line test and 58 lines of hook wiring. It now follows the design in
`docs/research/agent-platform/deletion-hunt-2026-09-23.md` (row 3a8f2e6d7).

## What changed

- `script/seon/dev/citations.clj`: 1,048 lines down to 140. Parsing a
  Markdown block takes one `str/index-of` scan per block (no regex). A citation
  is a `path:N`, `path:A-B` or a following `:N` span. Its name is the joined
  code span, and a list like `a`, `b` (`p:1`, `:2`) pairs the i-th name with
  the i-th citation. A name holds when it, or its unqualified part, is on the
  cited lines. It also holds when a clj-kondo `:var-definitions` Var with that
  name overlaps the lines. Only the Clojure files of text failures are linted,
  one `clj-kondo` process per file, run concurrently: a single process over
  many paths ran serially (0.95 s for 7 files). A missing path fails. A bare
  filename resolves below the directories its document cites. A name found
  nowhere in the file is a paraphrase: it is unverified and holds.
- Deleted: the baselines and `git cat-file --batch` reader, `--fix`, the
  content-keyed analysis cache (`tmp/citations/analysis`, removed), the
  shell-word `git commit` parser, and the status taxonomy.
- `bin/seon-hook`: the PreToolUse citation wiring is gone (−57, ae4dc420f).
  It parsed every shell command containing "git" and "commit", and it threw an
  NPE on every commit while this rewrite was in progress. The orchestrator
  reported that NPE and it was fixed at once.
- `script/seon/dev/citations_test.clj`: 169 lines down to 32, one test. It
  checks that current anchors pass and a missing file fails. It then shifts
  the source and note by one line and asserts that each named anchor fails by
  name with its new row.

## Wiring the orchestrator must install (outside this lane's paths)

`.git/hooks/pre-commit` is untracked, so this lane did not edit it. Append:

    git diff --cached --name-only -- .agents/skills | grep -q '\.md$' &&
      { bb script/seon/dev/citations.clj || exit 1; }

A commit carrying no skill Markdown never starts bb. Under `git commit --only`,
git points `GIT_INDEX_FILE` at the commit's own index, so the staged names are
exactly that commit's paths.

## Proof (bb and git only, no JVM, background QoS)

The HEAD snapshot came from `git archive HEAD`, with `reference-code` symlinked
in. It has since been deleted.

- `bb -cp script -e "(require 'clojure.test 'seon.dev.citations-test) (clojure.test/run-tests 'seon.dev.citations-test)"`:
  1 test, 2 assertions, 0 failures, 0 errors, 0.23 s.
- Drift caught: `llm-providers/SKILL.md` passes at HEAD (0 failures, exit 0,
  443 ms). Prepending one line to `src/seon/ai.clj` gives 7 failures and exit
  1 (452 ms), for example ``:443` `targets` is at 444-494``.
- Staged mode: with only `AGENTS.md` staged there were 0 documents, exit 0, in
  92 ms. With `codex-lanes/SKILL.md` staged there was 1 failure, exit 1, in
  146 ms.
- Current skills: at HEAD the new checker finds 23 failures across the 22
  documents. The old checker at HEAD, in its default mode, found 39 drifted;
  in `--worktree` mode on the snapshot it found 22. I spot-checked every
  non-clojure-testing failure against the source and each is a real anchor
  shift (`LANE_EFFORT` 54→55, `(recur old)` 203→206, `notify-listeners!`
  379→382, `projection-from-database` 3412→3447, and so on). The 11
  clojure-testing failures are the same ones the old checker reported. So
  there are no false positives, but the skills themselves have drifted. That
  is an out-of-scope defect, below.

## Timings (under `nice -n 20 taskpolicy -b`)

| operation | wall ms | note |
|---|---:|---|
| test namespace | 230 | |
| one skill document, worst (fork-maintenance.md) | 876 | lints its text-failure files |
| one skill document, typical | 430–490 | |
| no skill staged | 92 | bb + git; the hook line skips bb entirely |
| all 22 skill documents | 1,280–1,495 | over 1 s: proportional to the ~78 cited files read and the text-failure files linted; happens only when one commit carries every skill |

## Out of scope

- The skills under `.agents/skills/**` carry 23 drifted anchors at HEAD and 32
  in the working tree (other lanes' uncommitted src). AGENTS.md treats a stale
  skill as a high-priority defect. `.agents/skills/**` is not this lane's
  path. The list is `bb script/seon/dev/citations.clj $(git ls-files '.agents/skills/*.md')`.
- Size: 172 lines in total (checker 140, test 32), against a target of about
  120. The remainder is the list pairing (`a`, `b` (`p:1`, `:2`)). Without it
  the current skills give false positives (`render-ai`/`render-html`,
  `bind-handlers`/`handler`, `retry-strategy`/`delays`).

RESET NEEDED: no.
