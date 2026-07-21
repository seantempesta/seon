# W8a — PRD archival sweep

You are working in /Users/sean/src/seon on branch codex/runtime-reliability-refactor.
This is a SHARED working tree: touch ONLY the paths named below. Commit
path-limited only: `git commit --only ... -- <paths>`. Never `git add -A`.

GROUNDING (mandatory, before any edit):
- Read docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md
  (the program ledger; your unit is W8) and
  docs/prds/sci-execution-runtime/research/audit-doc-drift-2026-07-21.md
  (the per-directory verdicts).
- Vocabulary: use each document's existing terms; do not rename anything
  else while moving files.

TASK: archive every docs/prds/ directory EXCEPT these four (which stay):
sci-execution-runtime, source-cleanup, generate-code, package-capabilities.

For each other directory:
1. `git mv docs/prds/<name>` to `docs/prds/archive/<name>` (create
   docs/prds/archive/ first).
2. In its roadmap.md (or index md if no roadmap), set frontmatter
   `status: archived` and add one line at the top of the body:
   `Archived 2026-07-21; superseded by
   [[../../sci-execution-runtime/program-synthesis-2026-07-21]].`
   For runtime-reliability specifically, the pointer line must also say
   its high-level ledger role moved to the synthesis.
   Do NOT otherwise edit archived content.
3. gym-v2 is an EMPTY directory: `git rm -r` nothing needed — just
   remove the empty dir (it has no tracked files; verify with
   `git ls-files docs/prds/gym-v2` — if empty, `rmdir` it).

INBOUND LINKS (the part that breaks if skipped):
4. rg for references to each moved directory FROM the four kept PRD
   trees, docs/seon/, AGENTS.md files, and README/readme files:
   `rg -l 'prds/<name>|\[\[\.\./<name>' docs AGENTS.md src/seon/AGENTS.md src/my/AGENTS.md`
   Update every hit in KEPT documents to the new archive path (wikilinks
   become `../archive/<name>/...` etc.). Links inside other ARCHIVED
   docs may stay stale (they moved together, relative links between
   sibling archived dirs still resolve).
5. Root AGENTS.md: in "Documentation authority" and "Key entry points",
   replace the two runtime-reliability references with
   `docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md`
   (active program ledger) and
   `docs/prds/sci-execution-runtime/AGENTS.md` (current chunk runbook).
   Touch nothing else in AGENTS.md. NEVER edit any CLAUDE.md (they are
   symlinks to AGENTS.md).
6. docs/prds/readme.md: update its directory listing/description to the
   four active dirs + archive/.

GATE:
- `rg -n 'prds/(agent-runtime|agent-fsm|refinement|unified-flow|diffusion|repl-autosuggest|runtime-reliability|database-|frozen-turn|independent-downstream|agentic-tool|bun-native|embeddings|namespace-ui|inspect-autocomplete|local-performance|reactive-render|root-workspace|agent-canvas|agent-ctx|sci-execution)' docs/prds/sci-execution-runtime docs/prds/source-cleanup docs/prds/generate-code docs/prds/package-capabilities docs/seon AGENTS.md | grep -v archive` —
  must return ZERO hits pointing at moved dirs (sci-execution-runtime
  hits are fine, it didn't move).
- Every kept + moved md still has valid frontmatter (type/status/tags).
- `git status --short` shows only your moves/edits.

COMMIT in two path-limited commits:
(a) the archive moves + frontmatter flips + readme:
    `git commit --only -m "Archive superseded PRD directories" -- docs/prds`
(b) the AGENTS.md pointer update:
    `git commit --only -m "Point instruction authority at the active program ledger" -- AGENTS.md`

FINAL SUMMARY must list: dirs moved, dirs kept, inbound links updated
(file:line count), gate results, anything you could not resolve.
