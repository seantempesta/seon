# Wiki audit, 2026-10-23 (main b39cef5)

Question (owner): how in sync is the wiki, and can we pull those docs into the code? Read-only audit: 19 pages, 1,370 lines, checked against src, the task files and the standing root's main.

## Verdict

- **Mechanism: the wiki is loaded and served, but nothing checks it.** Boot writes `docs/` onto main (`seon.root/wiki-tx`, root.clj:618; the standing root holds 151 sections). `seon_context` shows the sections a plan cites (`seon.context/current-state` `:sections`). The help page is `docs/agent.md`, read from main (`seon.context/help-page`, `my.agent/help`). The only red the wiki can raise is a link to a missing section (`:seon.docs/refused`, recorded as a `:platform` error). A dangling var, keyword, task or file citation raises nothing. `bin/check docs` does not exist, and task `seon.docs/the-wiki-stays-true-and-healthy` is unbuilt.
- **Truth: about 70% current.** The mechanism descriptions are mostly true. The rot is in four things:
  - ~20 dangling citations (below);
  - dependency pins and statuses that have moved;
  - claims overtaken tonight (put!/take! threads; durability; boot loading the wiki);
  - a large layer of history: 72 dated stamps, ~25 owner quotes kept as narrative, ~30 session tags (THREADS, RC1, D2, F2, L1…), and "Where this is going" or "known gaps" sections.
- **What the parser itself sees.** I ran it over every page on the standing root's db (`seon.ref/parse` plus `names-something?`). About 1,000 distinct backticked names; ~130 resolve to nothing. They break down as:
  - ~20 genuine dead vars;
  - ~25 task names (the parser does not resolve task names; see R4);
  - ~45 git branch names such as `jolt/thread-bytes`, written with one backtick so they parse as symbols;
  - the rest dependency vars and v1 names.
- **Already live, and the models to copy:**
  - `seon.repl-test/the-help-page-runs`: every form on agent.md runs.
  - `render_test.clj:557-575`: `docs/render.md#one-printer` must name exactly the printing vars.
  - `bin/laws`, which ties code sites to clocks.md, resolution.md and failure-points.md, but **one way only**: it goes red on a site the page doesn't name, never on a name whose site is gone.

## Dangling citations (the parser's view plus a grep of src)

| Page:line | Cites | Truth | Fix |
|---|---|---|---|
| references.md:40 | ```seon.docs/backlinks```, ```seon.docs/homes``` | deleted 2026-10-21 (stated in the task plan) | describe it as the query, or write the function in `seon.docs` |
| references.md:40 | "boot does not load the wiki yet" | false: `seon.root/wiki-tx` loads it at boot | say boot loads it (`seon.docs-test/a-booted-root-holds-the-wiki`) |
| failure-points.md:87 | `seon.config/GC-SWEEPERS` | no such var | `:seon.config.root/gc-sweepers` |
| merging.md:32 | ```seon.proofs/shards``` | no such var | name `seon.proofs/run-tests!`, or drop it |
| source.md:87 | ```seon.fx/kinds``` | a parameter of `seon.fx/procs` | `seon.fx/procs`; the kinds live in `resources/seon/system.edn` `:fx` |
| source.md:43 | `test-runner/inputs`, `config-input`, `declared-refs` | moved | `seon.fx/inputs`, `seon.fx/config-input`, `seon.fx/declared-refs` |
| source.md:55 | `seon.schema/register!` | v1's name | write it with two backticks |
| vocabulary.md:12 | `seon.step/step-schema` | defined only in a test | `seon.step` |
| vocabulary.md:27 | `seon.root/start-reader!` | gone | name the current edge reader, or drop the row |
| vocabulary.md:39 | `seon.agent/print-value` | lives in `seon.print/print-value` | re-cite |
| vocabulary.md:61 | `seon.db/observation-attrs` | gone | cite where `seon.merge` classifies observation |
| vocabulary.md:106 | `seon.wire` | no such namespace | drop |
| render.md:53 (and render_test.clj:564) | `seon.mcp` | no such namespace | drop it from both |
| parity.md:24 | `seon.entrance/…` task | file still named `seon.entrance.edn` | rename the task file to `seon.server` (§7a) |
| parity.md:11-43 | `bin/seon`, `bin/lane`, `my.program/*`, `my.test/run` … | v1 names in the v1 column | two backticks |
| clocks.md:34-39, storage.md:30-93, merging.md:31, … | task names in one backtick | tasks, not vars | resolve task names in `seon.ref` (R4) |
| forks.md, cost.md, machine.md, … (~45 sites) | `jolt/<branch>` in one backtick | git branches | two backticks, or teach the parser refs |
| flow.md:5,19,71,80; forks.md:29,55 | `flow/impl.clj`, `impl/dispatch.clj:41-75`, `c/*.c`, `flowlab/*` | dependency-relative paths and line refs; `flowlab/` is outside next/ | `reference-code/jolt/...` paths with fn names, not line numbers |
| forks.md:30; parity.md:37,92; tasks.md:12,22 | `SCAFFOLD.md`, `notes/HANDOFF.md`, DECISIONS, DESIGN-tasks §11 | missing, or outside next/ (`../notes/`) | link `../notes/...`, or move the ruling into BAGGAGE or a task |

## Per page

### README.md (map)
- **In sync:** the links and the five rules.
- **Stale:** the map has no entry for the server (MCP and ops), the root graph and reconciler, or the dashboard (see Missing).
- **History:** the two owner quotes (:7-8). Keep the rules, drop the quotes.

### references.md
- **False:**
  - :40 "boot does not load the wiki yet".
  - :40 `seon.docs/backlinks` and `homes` are cited at code depth but deleted.
- **Stale:**
  - :42 says "the unresolved ones are the docs check's to report". No docs check exists; say so, or build it (R1).
  - :44 "docs/ at 8 pages, 60 KB, 60 sections": it is now 19 pages and 151 sections.
- **History:**
  - :28 "Before (2026-10-22) … 82-88%";
  - the dated Cost numbers (:32, :44);
  - "(printer estimate, 2026-10-21)" (:62).
- **Move to code:** :30's per-depth rule for vars belongs in the `seon.docs/resolve-ref` docstring. The Cost paragraphs belong in `seon.ref-test/one-parse-costs-one-pass` and `seon.docs-cost-test`, which already hold them.

### agent.md (the help page): the healthiest page
- **In sync:** every key it lists exists:
  - `:refused` kinds and `:will-merge` (seon.context);
  - `:proof-unknown` (seon.reconcile);
  - STEEPER/LOOSE and slack 1.25/1.0/3.0 (`seon.cost/slack`);
  - the my.* functions.
  Its forms are run by a test.
- **Stale or missing:**
  - :30 lists `:refused` kinds. `seon.reconcile/done-refusals` also has `:no-design` and `:proof-red`, and `:unmerged` is a context key rather than a done refusal. Generate the list (R3).
  - :62's slack numbers are copied from `seon.cost/slack`, a literal rather than a config key, which breaks §5. Make it a config key and render the number.
  - `(my.agent/prove)` and `my.agent/pst` are absent from the page.

### tasks.md
- **In sync:** every cited var resolves.
- **History:**
  - owner quotes as narrative (:7, :30, :36);
  - "DESIGN-tasks D3(a)" (:12);
  - §22 "What is not settled", an open question: make it a task on `seon.template`.
- **Missing:**
  - the attempt lifecycle (outcomes, `:no-end`, stuck/blocked);
  - branch holders and `:spawn` refusals (root.clj:263-270).

### merging.md
- **Stale:** :32 `seon.proofs/shards`.
- **History:**
  - :34, an 18-line "Measured 2026-10-22 … Before" paragraph. Put it in the commit.
  - owner quotes (:38, :42);
  - "Where this is going" (:52-54), a plan: move it to RESUME or a task.
- **Move to code:** the check-steps paragraph (:33) restates the `seon.proofs/check-steps`, `step-digests` and `plan` docstrings. Cite them at doc depth instead.

### vocabulary.md
- **False:**
  - :13 says "four procs". system.edn has seven: api, writer, fork-server, flusher, reconciler, render and web.
  - :19-20 has two "group commit" rows; keep one.
  - :92's my.agent list has `source`, but the function is `source-fn`, and the list lacks `prove` and `pst`.
  - :94 my.turn lacks `evals`.
  - :96 my.root lacks `request!`, `load-tasks!`, `load-docs!`, `problems`, `generation`, `build!` and `next-generation`.
  - `my.schema` has no row.
- **Stale:** the dead vars in the table above. :64's heading "worktree sessions R1 and M1" was mangled by the lane rename.
- **Missing words:** server (`seon.server`, the `seon_*` tools), holder, problem/error fact (`seon.problems`, `seon.error`), render, dashboard, generation boot, prove.
- **History:** "Renamed from the prototypes" (:98-111) is provenance; move it to BAGGAGE or delete it.
- **Move to code:** the my.* table should be rendered from `(ns-publics 'my.*)` (R3).

### storage.md
- **Stale:**
  - :13-16, a doubled table header;
  - :20 "LZ4 (the defaults)", but the default codec is `:zstd` (default.edn:124).
- **Missing:** branch holders and the live-agent cap (`seon.root` `:spawn`).
- **History:**
  - :26 session DUP-HELPERS;
  - :36 "the standing root's store was made without history";
  - :59 a deferral with an owner date;
  - :81, :85, :87 dated measurements;
  - :93, the whole compiled-units ruling. Move it to task `seon.program/compiled-units-in-the-store` and keep only `:base`, `branch-base` and misaligned.
- **Move to code:**
  - the layout-cost table goes to the `seon.db-test/a-commit-costs-what-it-writes` docstring;
  - the codec comparison goes to the `:seon.config.store/compressor` doc.

### failure-points.md
- **Stale:**
  - :46 "an effect worktree session" (mangled by the rename): should be "an effect worker", `seon.fx/worker-proc`. The same mangling is at agent.clj:121.
  - :56: a refused test fork now answers `:seon.test/fork-refused` (agent.clj:394).
  - :87 GC-SWEEPERS.
  - :62 cites a merged task.
  - :135 `mdb.c:193`: cite `MDB_USE_ROBUST`.
- **History:**
  - the owner quote in the lead (:3-5);
  - session tags D2, RC1, THREADS (:59, :78, :84, :89-90);
  - :100-103 "Before … step E dry run";
  - :138, :148 measurements.
- **Move to code:** each row's mechanism into its edge function's docstring. The row keeps the variant, the edge and the error.

### clocks.md
- **False:**
  - :36: the durability task is done (`seon.writer/flusher-proc` syncs at once; `seon.agent/transact!` commits at idle). Move `writer-proc`'s `:since` to "measured".
  - :16 `seon.agent/transact!` is not an unseen bound any more.
- **Stale:**
  - :27 `seon.check/lifecycle` and :35 `seon.root/back-op` and `seon.agent-proc/agent-proc` are no longer sites.
  - :43-51 lists `seon.web/http-in`, `render-proc`, `seon.cost/once`, `measure` and `seon.profile/cpu-ns`. `bin/laws` sees none of them; re-mark them or note that they are unmarked.

### resolution.md
- **Stale:** :22 `seon.agent/run-tests-forked` is no longer a site.
- **In sync:** everything else.

### machine.md
- **Stale:**
  - :21 "Full checks run one at a time", but AGENTS §8 now says each runs in its own worktree;
  - :23 "`: settled`" is a typo.
- **False:** :39 says root prunes `target/check/` to three runs and generations to two. No code does this; make it a task, or say it is a manual act.
- **History:** owner quotes and anecdotes at :11, :15, :19 (the 128 GB / build-lock story), :23 and :35 (261 GB).
- **Move to code:** :23's harness waits belong in the `seon.check/run-check` docstring.

### flow.md
- **In sync:** all the API defaults checked.
- **False:** :53 "a full consumer costs an OS thread". Jolt `c508734d` made a pending put! a fiber callback waiter (async.ss:896-951).
- **Stale:**
  - :79 "today a throw loses its message" contradicts :78 (`seon.root/answer-taken`).
  - :3, :71, :80 cite `flowlab/` files outside the checkout.
  - :19 and the other `:NNN` line refs.
- **History:** :8 and :17 are Jolt docstring defects (file `jolt/<topic>` tasks); :9 commit hashes; :71 "Tasks are filed".
- **Move to code:** :78's 25 lines belong in the `seon.step/ask` and `seon.root/answer-taken` docstrings.

### core-async.md
- **False:**
  - :40 put!/take! "an OS daemon thread each… THREADS is changing it";
  - :51 "a full tap costs an OS thread";
  - :62 "Until THREADS' fix merges": it merged. Delete the idiom.
- **Stale:** :3 Jolt `9e42a0d8`, but the pin is `4342b73c`; the line refs have moved.
- **History:** :44 "since `93af2568`"; :16 is a Jolt docstring defect, not wiki text.

### config.md
- **In sync:** all the `seon.config/*` vars, the ports, the env order and the layers.
- **Stale:**
  - :3, an owner-quote lead;
  - every section says its content twice, as prose and again as a citation paragraph (:18, :36, :49, :62, :73);
  - :26 should name `bin/worktree --remove`. check.clj:1000 tells the user `bin/worktree --ports`, which does not exist.
- **History:** :38 "the merge of CONFIG, 2026-10-21 …"; :58 "read 2026-10-21".
- **Move to code:** :53-60, the manifest fields, belong in the `seon.config.ai.edn` `:description`s, which already exist for 19 keys.

### cost.md
- **In sync:** yes.
- **History:** :8-9, the dated 3.9 vs 5.2 ms figures and the `(statistics)` story.
- **Move to code:** the definitions of what time and bytes measure belong in the `seon.profile/cpu-ns` and `alloc-bytes` docstrings.

### render.md
- **In sync:** the vars resolve, and §one-printer is test-checked.
- **False:** :53 `seon.mcp`.
- **Stale:**
  - :34 "16,000" should cite `:seon.config.render/context-budget`;
  - keywords `:seon.render/html`, `/ai`, `/schema` and `:seon.print/full` resolve to no schema key. Check that they are enum values and write them with two backticks if they are.
- **History:** :46 "Before (2026-10-22)…", about 15 numbers; :44 "owner, D4"; quotes at :3, :29, :51.
- **Move to code:** :24 is a 400-word paragraph listing about 30 vars. The vars' docstrings carry it; the page keeps the rule that a lifecycle is declared once on its enum.

### forks.md: the most stale page
- **False pins:**

| Repo | forks.md says | Actual pin |
|---|---|---|
| jolt | `a9aef8ea` | `4342b73c` |
| chez | `359f151` | `80e4b9f` |
| datahike | `fe50764` | `fd0c065` |
| konserve | `39ce147` | `3dbd4d5` |

  Chez :30 says "Not applied: 0001–0005", but they were applied, and gc-sweepers and thread-bytes came after.
- **Missing:** the lmdb and zstd submodules.
- **Dangling:** SCAFFOLD.md, notes/HANDOFF.md.
- **History:** nearly all of it: session tags A1/L1/J1/F2/E14/S1, "for root to merge", "Pending", "will move", test counts.
- **Fix:** generate it (R2).

### source.md
- **In sync:** every code-depth var, bin/root next, bin/update, and the config keys.
- **Stale:**
  - :87 `seon.fx/kinds`; :43 the test-runner names; :55 v1's name;
  - :25 "21 resources", but there are 24;
  - :9 `jolt/merges`, but the default is `jolt/seon-merges`.
- **Missing:** a home for boot (`seon.root/import-source!`, the boot fact, `wiki-tx`, `previous-end`).
- **History:** 13 dated stamps; session tags INPLACE/TESTS/J2, BUILD P3/P4, PKG plan (:3-91); "the next step" (:19, :51); "Not yet" (:81).
- **Move to code:**
  - the measurements go to cost proofs;
  - the Known gaps (:19, :51, :81) become tasks on seon.source and seon.program;
  - §Shared build caches goes to the `bin/env` comments and forks.md.

### parity.md
- **False:** :41 says "no agent function" for prove, but `my.agent/prove` exists.
- **Stale:**
  - :3 "Inventory of 2026-10-22 against `29c3a91`";
  - :28 "230+ tasks";
  - :93 "9 topic pages, bin/check docs" (there are 18 pages and no docs check; `jolt/wiki` and `jolt/patterns` are unmerged or gone);
  - :99 `archive/jolt/door` should be the tag `archive/door`;
  - :59 "waste" should be "overrun" (§7a).
- **History:** :97 "kept since 2026-10-22 (A.2)".
- **Move to code:**
  - §"The gaps, in order" (:95-107) is a plan: its gaps become tasks, and THE PLAN is RESUME's.
  - The status column should be derived from tasks (R5).
  - :26 restates the `seon.server/ops` docstring.

## Concepts in src with no home

No section cites any var of these namespaces at code depth:
- **The server and MCP:** `seon.server` (233 lines; ops, respond, defect), `bin/mcp` and the `seon_*` tools. No page exists, though AGENTS §6 points at it.
- **Root and the reconciler as a whole:** `seon.root` (1,051 lines) and `seon.reconcile` (768) have homes only for fragments. Missing:
  - the root graph's seven procs;
  - attempt lifecycle and judging;
  - holders and `:spawn` refusals;
  - stuck/blocked;
  - boot.
- **The agent process and turn loop:** `seon.agent` internals, `seon.agent-proc`, `seon.turn`, `seon.model`, `seon.repl`, `seon.sock`, `seon.intr`, `seon.stage`. agent.md is the agent's view, not the mechanism's.
- **Problems and errors:** `seon.problems`, `seon.error`, `seon.learn`.
- **The web and dashboard:** `seon.web`, `seon.web.html`, `seon.render.ui` (986 lines), `seon.render.proc`.
- **The printer:** `seon.print.select` (731 lines) and `seon.print.emit` (424). render.md names them only through §one-printer.
- **Checks:** `seon.check` (1,329 lines, the biggest namespace; its modes are listed nowhere), `seon.e2e`, `seon.fixtures`.
- **Templates:** `seon.template` and `seon.merge` are explained in tasks.md and source.md but never cited at code depth.

## Pull docs into the code: what moves, what stays

**Principle.** The code says what is. The wiki says why, how pieces relate, and what to do. A sentence that would have to change when a docstring, config key, enum or pin changes is not wiki text. It is either generated from that fact or replaced by a citation of it, which the context already renders at the asked depth.

**Move into code:**
1. **Docstrings.** Mechanism paragraphs that restate one function: flow.md:78, render.md:24, references.md:30, merging.md:33, failure-points rows, cost.md definitions. Leave one sentence in the wiki and a doc-depth citation; the context renders the docstring.
2. **Config key docs.** Numbers copied from config (render.md:34, agent.md:62, the storage codec table, config.md manifest fields) become the key's comment or `:description` in `resources/seon/schemas/seon.config.*.edn`, and the page cites the key. `seon.cost/slack` becomes a config key (§5).
3. **Schema descriptions and enum values.** The `:refused` kinds, `:seon.task/state` values and attempt outcomes go in their enum's `:description`, rendered where needed.
4. **Task plans.** Every "known gap", "not yet", "where this is going", "what is not settled" and the parity "gaps in order" becomes a task in `resources/seon/tasks/*.edn`. They are plans, and plans are tasks (§3).
5. **Commits and notes.** Every "measured on date", "before (date)", session tag and owner quote kept as narrative. The rule stays; its provenance is `git log`. Cost numbers that matter are cost proofs.
6. **Generated.** forks.md pins and branches (`git submodule status`, `git log jolt/main ^upstream`); the my.* table (`ns-publics`); the root graph's procs (system.edn); `bin/check` modes (`seon.proofs/check-steps`); the parity status column (task states).

**Stay in the wiki:**
- the principles and patterns (each "Not this");
- cross-cutting explanations that no single var owns (one store and many branches, how a request becomes tasks, reach digests end to end, failure points as a set);
- the agent's help page;
- the map, and the rules for keeping it;
- v1 parity, as rulings with reasons.

## Recommendations, simplest first

- **R0. Fix the 20 dead citations and the 6 false claims above.** Done by hand, in one commit: the references.md boot claim, core-async/flow put!/take! threads, clocks durability, forks pins, vocabulary's proc count, parity's prove.
- **R1. `bin/check docs`: a red for every unresolved citation.** This is the core of task `seon.docs/the-wiki-stays-true-and-healthy`, cut to its first measure. It is `seon.ref/references` over every page on the check's db, red on each backticked name, key or path that names nothing, ending `docs OK|FAILED`. About 30 lines in seon.check, using `names-something?`, which exists; the query I ran for this audit is that check. Key it by the docs' and program's digest like any step, so it runs on every merge that touches docs or a cited var. It also makes the context honest: an agent stops reading `:unresolved` for names the wiki promised. Two prerequisites:
  - `seon.ref/names-something?` NPEs when `seon.config/checkout` is nil inside root's eval (seen at ref.clj:568 while running this audit);
  - write branch names and v1 names with two backticks.
- **R2. Generate forks.md's facts** (pin, branch, ahead-of-upstream count) in a check that diffs a table on the page against `git submodule status`, red on a mismatch. Its first run would have caught all four false pins.
- **R3. Turn the copies into checks, as `render_test` and `the-help-page-runs` already do:**
  - vocabulary's my.* table equals `ns-publics` of the my.* namespaces;
  - agent.md's `:refused` list equals `done-refusals`' kinds;
  - vocabulary's proc list equals system.edn.
  Each is one deftest, at the producer's owner.
- **R4. Make `bin/laws` two-way, and resolve task names in `seon.ref`.**
  - `bin/laws` goes red on a name a law page lists whose site is gone. That is three pages' worth of rot (clocks :27, :35, :43-51; resolution :22).
  - `seon.ref` resolves `ns/task-name` against `:seon.task/name` facts, so task citations (~25 today) resolve and show the task's plan at doc depth.
- **R5. Stale-by-change (the task's second measure).** A section citing a var at doc or code depth is flagged when that var's defining form changes on main after the section's last commit. The program facts hold both forms, and the rule "merging a change updates its section in the same commit" (AGENTS §7) then has a check.
- **R6. Delete the history.** One sweep per page under the README rule "nothing is a dated log". Optionally, the `:changelog` measure from the task (a dated line or session tag is red) keeps it out.
- **R7. Write the five missing homes:** server, root and reconciler (procs, attempts, holders, boot), agent process and turn, problems and errors, dashboard. Each leads with one sentence and cites its vars at code depth, so the context can serve them.

R1 + R2 + R4 make the wiki unable to go silently false about names, pins and law sites. R3 and R5 cover copied lists and changed functions. After that, the only unchecked prose is the "why" text, which is the part that should stay.
