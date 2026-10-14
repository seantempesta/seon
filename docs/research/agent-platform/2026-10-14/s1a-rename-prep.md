---
title: "S1a prep: the intent to plan rename, inventory, sweep script and dry run"
type: research
status: phase 1 done at HEAD 5e0774bd6; nothing applied to the working tree
created: 2026-10-14
---

# S1a prep: `:seon.task/intent` to `:seon.task/plan`

Authority: `docs/seon/schedule/cards/TASK-CHAIN.md` section 7 entry S1a, `prebrief-s1.md`. Phase 2 is one atomic commit in a root-opened restart batch, then a reset (every stored `:seon.task/intent` datom is renamed by re-ingestion, so the live store needs the reset).

## Sweep script

`script/seon/rename_task_intent.clj` (`seon.rename-task-intent`, rewrite-clj, by syntax, no regex over Clojure).

```
clojure -Sdeps '{:paths ["script" "src" "resources"]}' -M -m seon.rename-task-intent report  ROOT
clojure -Sdeps '{:paths ["script" "src" "resources"]}' -M -m seon.rename-task-intent write   ROOT
clojure -Sdeps '{:paths ["script" "src" "resources"]}' -M -m seon.rename-task-intent compare OLD NEW
```

It rewrites, in `resources/seon/tasks`, `resources/seon/schemas`, `src`, `test`, `script`, `bench`, `config` (`.clj .cljc .edn`):

1. keyword nodes `:seon.task/intent` to `:seon.task/plan`, and the file-admission refusal variant `:no-intent` to `:no-plan` (it names the attribute: `seon.task/file-declarations`, the `:seon.task/task-file-error` enum, `task_ingestion_test`);
2. the keys `:intent` of `#:seon.task{...}` namespaced maps (whitespace between qualifier and map handled; the schema file is written that way) to `:plan`;
3. the symbol `intent` inside the vector value of a `:seon.task/keys` destructuring key to `plan`;
4. the same three rules inside string values that read as Clojure (fixtures, task text citing the key), re-escaped, and proven to read back to the new value;
5. in `resources/seon/schemas/seon.task.edn` only: the `:plan` description replaced by the corrected text below (the old text, "the goal every step of a chain shares", is the retired chain meaning).

Corrected description: "The plan: Markdown that starts as the user's request and that every task improves, in blocks such as ## Problem, ## What exists and ## Approach, with the references it cites (seon.ref/names). Declared on the subject (required on a task with no parent), inherited down :seon.task/children, a child's own value overwriting (seon.task/effective)." The last two claims are still true at S1a and are deleted by their owning slices (S1c, S3a).

Round trip is checked: every file must re-serialise unchanged before it is rewritten.

## Inventory (HEAD 5e0774bd6, `report` on a `git archive` extract)

| Category | Files | Occurrences |
|---|---|---|
| task files `resources/seon/tasks` | 101 | 855 `#:seon.task{... :intent ...}` keys; 4 qualified `:seon.task/intent` keys (all in `seon.sci.branch-execution-test.edn`); 859 of 871 entries carry an intent |
| schemas `resources/seon/schemas` | 4 (`seon.task`, `my.task`, `seon.context`, `seon.flow.plan`) | 21 keywords, 1 `:intent` key (the `#:seon.task` schema map), 1 `:no-intent` enum member |
| `src` | 10 | 27 keywords, 3 `:seon.task/keys [intent ...]` destructurings (`seon.context`, `seon.render.ns`, `seon.task`) |
| `test` | 42 | 217 keywords (many inside fixture strings), 19 `:intent` keys of `#:seon.task` maps in strings |
| `bench/seon/contracts_corpus.edn` | 1 | 2 keywords (a measurement corpus; swept for consistency, root may exclude it) |
| `script`, `config`, `bin`, `.agents` | 0 | none |
| Markdown laws, skills, specs | by hand | listed below |

Not touched on purpose: `:seon.message/intent` and the `intent` locals of `seon.cluster.agent` (they mean `:seon.task/execution`, vocabulary entry "intent").

## Dry run (extract in `tmp/s1a/old`, rewritten copy in `tmp/s1a/new`)

- `write`: 156 files changed (101 task files, 4 schemas, 9 `src`, 41 test files, 1 bench). 2152 changed diff lines, 1076 per side.
- `compare` (leaf-token comparison of 892 swept files, 1,035,128 identical leaves): 255 keywords, 856 `#:seon.task` map keys, 3 destructured symbols, 32 strings rewritten, `:unexplained` is exactly one: the corrected schema description. No other difference.
- Independent EDN check (clojure.edn over every task file, both trees, `:seon.task/intent` renamed on the old side): 871 entries, 859 had `:seon.task/intent`, 859 now have `:seon.task/plan`, 0 left; key sets equal in all 871; value differences 0.
- `clj-kondo --lint src script test --cache false` old vs new: 1664 vs 1667 warnings, identical except 3 predicted hand fixes, each an unresolved body use of the renamed destructuring: `src/seon/context.clj:189`, `src/seon/render/ns.clj:131`, `src/seon/task.clj:278` (rename the body's `intent` to `plan`). No other new finding. (`src/my/task.clj` keeps a local named `intent` bound from `:seon.task/plan`: `refine!` is deleted by S1d-2.)
- Not run: tests (existing reds unchanged must be proved in phase 2 on the branch, same tests before and after).

## Hand list for phase 2 (what the script leaves)

Residual `seon.task/intent` after the sweep: 31 occurrences in 15 files, all text that does not read as Clojure (prose, docstrings, partial fixture fragments). Each is edited by hand; the task-file ones matter (backticked keywords are references, an unresolved one is an ingestion problem):

- task files: `seon.context.edn:24` (2), `seon.program.edn:17`, `seon.ref.edn:11`, `seon.step.edn:3` (retired by S4a).
- `src`: `seon/task.clj:702, 750, 1096, 1119` (docstring examples), `seon/bootstrap.clj:40, 46, 53, 64` (help text; the file and `seon.bootstrap.edn` are held, see below), `seon/render/schema.clj:34`, `my/task.clj:48, 59`.
- `test` (fixture fragments built by `str`): `cluster/db_flow_test.clj:1463, 1468, 1471`, `cluster/host_test.clj:426-433`, `cluster/merge_test.clj:491`, `cluster/evaluate_sources_test.clj:194` (comment), `context_test.clj:147, 295`, `ref_test.clj:240, 292`.
- three destructuring bodies named above; CSS class `seon-task-intent` in `seon.render.ns` (and `resources/public/css/input.css` if it styles it); the private fn `seon.contracts/intent` (its callers write the key; rename to `plan`).
- Prose "intent" meaning the task text (not `:seon.task/execution`): docstrings in `seon.task` (275, 696, 723), `seon.context` (184), `seon.ref` (2, 429), `seon.render.ns` (101, 121, 140, 153, 169), `seon.health` (277), `seon.contracts` (28-40, 124); schema descriptions that say "intent" in `my.task`, `seon.context`, `seon.flow.plan`, `seon.ref`, `seon.task` (the attribute descriptions other than `:plan`'s).
- Laws and documents: `AGENTS.md` (CLAUDE.md is its symlink) "Write tasks as data" lines 412, 419, 421, 424, 433 and the "Understand" lines naming the task text; `docs/seon/architecture/vocabulary.md` entries 76, 93, 94; `docs/seon/architecture/contracts-guide.md` 111-126; `docs/seon/specs/task-steps.md` 31, 43, 46, 128; `docs/seon/specs/agent-loop.md` 319, 332, 342; `docs/seon/plan.md` 74, 209, 224, 230. `.agents/skills/clojure-testing/SKILL.md` has no `intent` text (its tag text converts in S2c).
- Decision for root: the word `plan` is already a vocabulary entry (the pure decision of `seon.cluster.agent/plan`, schema namespace `seon.flow.plan`). Recommend recording both meanings in the entry ("plan: a task's Markdown text, `:seon.task/plan`; also the pure decision of db<->flow") rather than renaming the flow side in this commit.

## Files phase 2 will touch, and claims (`bin/lane status` at HEAD 5e0774bd6)

The 156 swept files are the ones `write` changes (reproduce the list with `write` on a `git archive` extract and `diff -rq`): (101 `resources/seon/tasks/*.edn`; `resources/seon/schemas/{my.task,seon.context,seon.flow.plan,seon.task}.edn`; `bench/seon/contracts_corpus.edn`; `src/my/task.clj`, `src/seon/{bootstrap,cluster/agent,context,contracts,merge,ref,render/ns,task}.clj`; 41 test files). Hand files add `src/seon/render/schema.clj`, `src/seon/health.clj`, `AGENTS.md`, the docs above, and the 5 task files above (already in the 101).

Held right now: lane `help-cache-key` holds 3 paths, dirty in the working tree: `resources/seon/tasks/seon.bootstrap.edn`, `src/seon/bootstrap.clj`, `test/seon/bootstrap_test.clj`. Collisions with S1a: `resources/seon/tasks/seon.bootstrap.edn` and `src/seon/bootstrap.clj` (help text lines 40-64). S1a must wait for that lane's commit or take those two as a patch. No other swept file is claimed or has uncommitted changes. Other lanes' claims change by the hour: re-run `bin/lane status` and `git status` immediately before the restart batch, and re-run `report` on `git archive HEAD` (counts above are at 5e0774bd6).

## Phase 2 order

1. Root opens the batch; claim all swept and hand files.
2. `write` on the working tree; hand-fix the 3 unresolved bodies, the 31 residual strings, the CSS class and `seon.contracts/intent`; edit the laws.
3. Lint `src script test` with `--cache false`, zero new findings against the table above; `compare` HEAD extract vs working tree.
4. Boot proof: publish and reload the changed schemas on a branch of the live store; run the tests reaching the changed files before and after; the red set must be equal.
5. Commit through `bin/lane commit` with `--test` for each; root resets the store.
