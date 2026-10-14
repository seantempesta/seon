---
type: research
status: dig (facts only, no recommendation); pinned to HEAD f21bdcbca at the time of reading
created: 2026-10-14
question: when root's merge writes accepted entities to files, what does bin/lane commit check today, what does the merge path already do, and which file kinds are outside program entities
---

# Dig: what the merge path writes to files, and what bin/lane checks

Facts with symbols and paths. Each row marked VERIFIED (read in source or observed by rg this session) or UNVERIFIED (inferred, or not run).

## 1. `bin/lane commit` today (bin/lane)

| Step | Where (symbol) | What it protects against |
|---|---|---|
| Lane must hold a claim file; each path must be claimed by this lane (`owner_of`, `covers`) | `commit`, `claim` | one file, one owner; a lane committing another lane's work |
| Paths literal, no `..`, globs, whitespace | `valid_path`, `no_whitespace` | pathspec magic sweeping others' files |
| Message: not another live lane's name, not a subject among last 200 commits | `message_owned` | a shared scratch message landing under the wrong paths |
| `deps.edn` or `reference-code/` gitlink refused without `--restart-batch` | `commit` (restart-batch block) | dependency or fork bumps outside root's restart batch |
| Candidate tree = HEAD + named paths via temp index | `candidate_tree` | judging bytes that will not be committed |
| clj-kondo: narrow lint vs cached HEAD analysis, or full `src script test --cache false` | `lints_unchanged`, `lint_candidate`, `lint_cache_sync`, `lint_namespaces`, `cache_shape`, `lint_callers` | a lint error landing; callers failing after a shape change |
| Branch evidence (`--branch-evidence AGENT`): live root's `seon.cluster.branch-evidence/branch-evidence` says the branch declares exactly these definitions and every reaching test is green on its head | `branch_evidence`, `evidence_paths` (src/ and test/ .clj/.cljc only) | an unproven or red change; trailers record commit, tests, runs |
| Throwaway check `bin/seon check PATHS --test NS/VAR...` (publication, reload in a throwaway JVM, named tests) | `commit` → `bin/seon check` (`script/seon/operator.clj` `check!`) | a candidate that does not boot or whose named tests fail |
| No `--test` and no gate input: check skipped | `gate_inputs_absent` (bb, `seon.test.cache/gate-input?`) | nothing to publish for docs, skill Markdown, experiments |
| Lint summary must show `errors: 0` | end of `commit` | clj-kondo error on the candidate |
| Staged-index check: nothing staged beyond this commit | `staged_only`, `commit_locked` | a commit carrying others' staged paths |
| Candidate bytes unchanged since the proof | `commit_locked` (`git diff --quiet $proven $now`) | editing a file during its check |
| Commit under `.git/lane-commit.lock` with `git commit --only` | `commit`, `commit_locked` | interleaved commits in the shared index and `COMMIT_EDITMSG` |
| Landed commit = requested parent, paths, message (else exit 3) | `commit_locked` (`LANE COMMIT MISMATCH`) | history drift; never rewritten |

Restart rule (AGENTS "Restart in batches"): enforced only by the `--restart-batch` refusal above. VERIFIED.

## 2. Root's merge end to end (verified in source)

| Stage | Symbol (file) | Writes | Runs |
|---|---|---|---|
| Root tool | `my.root/merge!` (src/my/root.clj) → `seon.merge/merge!` with `checkout` from `seon.merge/checkout` (`:seon.config.fs/working-root`, default `"."` in config/default.edn:237) | nothing on disk | calls merge-onto |
| Facts merge | `seon.merge/merge-onto`, `merge-tx` | main's datoms (one transaction, base moved) | none |
| Gate | `seon.cluster.source/prepare-merge!` | a fresh merge branch S (scratch) | `proven-files` over merged value when `root` given; `lint-errors` (clj-kondo analyzer, `seon.fn.analyzer/analyze`) on proven files; `candidate-gate!` runs proofs + reaching tests on S; `fork-point-red` for reds |
| Accept | `seon.cluster.source/accept-merge!`, `merge-verdict` | main's commit (merge commit with parents candidate + head) | verdict over prepared results |
| Publish (separate, due work) | `seon.merge/publish!` (called from `seon.cluster.agent` `:publish` action, src/seon/cluster/agent.clj ~2276) | files, then one git commit | see below |
| Write-back | `seon.cluster.source/write-back!` → `write-back-commits` → `proven-files` / `proven-schema-file` / `proven-task-file` → `install!` (atomic rename) | src/, test/ .clj/.cljc (declarations by span splice), `resources/seon/schemas/*.edn`, `resources/seon/tasks/<ns>.edn` | proven-files: reindex by `file-rows` and compares digests (`:not-equivalent`), `:conflict` on base digest; no clj-kondo in write-back itself; no reload |
| Index | `seon.merge/indexed!` → `seon.fn/index!` | main's record of written files only | analyzer |
| Commit | `seon.cluster.source/commit!` → `call-with-commit-lock` (perl flock on `.git/lane-commit.lock`) → `commit-locked` | one git commit, `git commit --only` of exactly written paths | staged-index check, carried-paths check |
| Claim | `seon.merge/publish!` → `source/waiting-paths`, `claim-paths!`, `release-claim!` (writes/deletes `tmp/orchestrator/lanes/merge-<key>.paths`) | ledger file in bin/lane's own directory | reads other lanes' `*.paths` and `git status --porcelain` |

Git invocations in the merge path: one helper `seon.cluster.source/git` (src/seon/cluster/source.clj ~2049), used by `commit!` only. VERIFIED by rg over src/seon and script/seon; the other `"git"` hits are in operator, dependency digest, analyzer (reads `reference-code`, `rev-parse`), and sentinel.

Caller facts: `my.program/write-branch!` (src/my/program.clj ~1019) calls `seon.cluster.source/write-back!` with no merge, writing the branch's changed declarations to disk. VERIFIED.

Two `publish!` names exist and must not be conflated: `seon.merge/publish!` (checkout files + git commit, §2 above) and `seon.cluster.source/publish!` (source.clj:564, called from src/seon/cluster.clj ~1885, reads the checkout into a cluster branch and analyzes it). VERIFIED.

## 3. Each bin/lane check against the merge path

| bin/lane check | Merge path equivalent | Status |
|---|---|---|
| Claim / one owner | `waiting-paths` + `claim-paths!` on the same ledger dir; waits instead of refusing; dirty-by-others check via `git status` | PARTLY (waits rather than refuses; no `bin/lane claim` refusal of an unclaimed dirty file at claim time) |
| Literal paths | paths come from declaration identities, never user text | HAS, by construction (UNVERIFIED that no other path source exists) |
| message_owned | no equivalent; `commit-message` is generated, carries `Merge-commit:` trailer and a hard-coded `Co-Authored-By: Claude Opus 5.5` line | NOT (different rule) |
| Restart-batch / deps.edn guard | none needed: write-back only touches program identities (`change-path`: task, schema, namespace file); deps.edn is not reachable | HAS BY SHAPE (VERIFIED for change-path; UNVERIFIED for every producer) |
| clj-kondo errors on candidate | `lint-errors` in `prepare-merge!` over `proofs` (candidate bytes), only when a checkout root is given; refuses `:seon.source/lint-error` | PARTLY: analyzer's analysis, not the clj-kondo binary CLI run with the checkout `.clj-kondo` config (analyzer uses clj-kondo.core/run! at src/seon/fn/analyzer.clj:307; UNVERIFIED that the config matches `bin/lane`'s) |
| Narrow vs full lint scope | none | NOT |
| Branch evidence (tests reaching the change green on the agent's head) | `candidate-gate!` runs proofs + reaching tests on a fresh merge branch S of head, `merge-verdict` refuses reds, `fork-point-red` allows reds already red on main | HAS, stronger (on a merge branch, not the agent's head); difference: S is re-run, not reused from branch evidence |
| `bin/seon check` (publication, reload, throwaway JVM boot, named tests) | no throwaway JVM; no reload; the gate ran in the live root. Publication into main is a separate pass (`seon.cluster.source/publish!`) | NOT in the publish step (UNVERIFIED whether root's live reload follows the commit) |
| Gate-input classification (skip check for docs) | none; publish is only reached for merged declarations | NOT NEEDED by shape |
| Staged-index check | `commit-locked`: refuses other staged paths | HAS (same rule, Clojure port) |
| Candidate bytes equal to proven bytes | `write-back-commits`: `disk-digest` vs `base-digest` (`:file-changed`), `install!` writes the candidate bytes; `commit-locked` checks paths unchanged on HEAD via `diff --quiet HEAD` | HAS, file-level digest (not `git diff` of the candidate tree) |
| Lock `.git/lane-commit.lock` | `call-with-commit-lock` (same file, same perl flock) | HAS, shared with bin/lane |
| Landed commit = requested paths and message | `commit-locked` checks the carried paths set and `committed-merge` for idempotence; no message comparison | PARTLY |
| `bin/lane release` | `release-claim!` deletes the merge-<key> ledger file | HAS |

## 4. File kinds outside program entities (how they change today)

| Kind | Example path | REPL write tool today | Write-back covers it? |
|---|---|---|---|
| Program source | src/**/*.clj(c), test/**/*.clj(c) | `my.program` defn + `my.program/write-branch!` or merge publish | YES (declaration spans) |
| Schema resources | resources/seon/schemas/*.edn | schema entries → `proven-schema-file` | YES |
| Task files | resources/seon/tasks/*.edn | task entries → `proven-task-file` | YES |
| Docs (Markdown) | docs/**/*.md, AGENTS.md, CLAUDE.md | `my.fs/write!` (precondition digest) or `my.edit/exact!`, `lines!`, `text` (src/my/fs.clj:61, src/my/edit.clj) | NO |
| Skills | .agents/skills/**/SKILL.md | same as docs | NO |
| Help | resources/seon/help.md | same | NO |
| Config | config/default.edn, development.edn, test.edn | same (no program entity; `seon.config` reconciles DB config) | NO |
| Build | deps.edn | same; `bin/lane` refuses unless `--restart-batch` | NO |
| bin/, script/ | bin/lane, script/seon/*.clj | same | NO (script/ is a lint input for `bin/lane`) |

Tool capability facts: `my.fs/write!` writes any path under `:seon.config.fs/roots` (default `["."]`, config/default.edn:238), guarded by `:my.fs/precondition` (expected absence or prior digest); capability `seon.fs.jvm/write` (src/seon/fs/jvm.clj). `my.edit/form!` edits one named top-level Clojure form by digest (capability `seon.edit.jvm/edit`). No write of these tools goes through git, lint, reload or tests. VERIFIED by reading the tool docstrings and schema declarations; `seon.cluster.source/install!` (src/seon/cluster/source.clj:1382) writes a sibling temp file and renames it with ATOMIC_MOVE (VERIFIED); it does not use `seon.fs/write-edn-atomically!`. UNVERIFIED: whether `my.fs/write!` is permitted in a non-root agent's capability set.

## 5. Prior notes and rulings (one line each)

- docs/seon/specs/merge.md §4 step 5-6: write-back supplies the file; publication is due work for the next pass, committed with the merge's other paths. Target spec, matches code.
- docs/seon/specs/merge.md:36-37: "Our own lanes write a branch back to files without a merge (`my.program/write-branch!`) and commit by branch evidence (`bin/lane commit --branch-evidence`)" (current state).
- AGENTS.md "Merge is a root task" (~line 304), "Write-back" (~316), "Prove each slice before it is committed" (~579): agents transact facts; root merges and writes; lanes commit by bin/lane.
- docs/research/agent-platform/merge-and-write-back-data-pack-2026-09-22.md: data pack for the write-back design at HEAD 252e6e5bd (read-only research).
- docs/prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md: lane D1 plan revised 2026-09-24 (owner option 3) for isolation and write-back.
- docs/research/agent-platform/landing/merge-ns-rows-2026-09-26.md: a landing that left root patches pending and "RESET NEEDED"; shows the publish path had open defects.
- resources/seon/tasks/seon.merge.edn (`publication` task plan): the merge stops writing back; `seon.cluster.agent/next-actions` derives `:publish`; `publish!` waits on lane claims.
- resources/seon/tasks/seon.cluster.source.edn:51: a task to replace a runtime `:root-refused` check with a configuration schema (no-fences law).
- docs/research/agent-platform/2026-10-14/gemini/worktree-pitch-argon.md: recommends no per-agent git worktrees; one shared working tree written only at `seon.merge/publish!`.
- docs/research/agent-platform/2026-10-14/gemini/worktree-pitch-flash.md: rejects per-agent worktrees; accrete on Datahike branches with single-root write-back.
- docs/research/agent-platform/2026-10-14/gemini/worktree-pitch-brief.md: the owner's dream ("DB is the source of truth and we write to the files once we've vetted it ... get rid of the lanes"); the brief itself.

## 6. Observations to carry forward (facts, not recommendations)

- The merge path already re-implements the lock, staged-index check, claim ledger and commit (`source/commit!`, `source/waiting-paths`), reading the same `tmp/orchestrator/lanes/*.paths` and `.git/lane-commit.lock` that bin/lane uses. VERIFIED.
- The merge path's commit message hard-codes `Co-Authored-By: Claude Opus 5.5` (src/seon/cluster/source.clj `commit-message`). VERIFIED.
- The publish path has no clj-kondo binary run, no reload and no test run; its safety is the gate on the merge branch plus digest/equivalence proofs at write time. VERIFIED.
- Restart-batch and gate-input rules have no counterpart in the merge path. VERIFIED by absence in src/seon/merge.clj and src/seon/cluster/source.clj.
- Non-program files (docs, skills, config, deps.edn, bin, script) have no write-back path and no git commit path in the merge code. VERIFIED.
