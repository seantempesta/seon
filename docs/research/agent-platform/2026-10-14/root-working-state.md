---
title: "Root working state 2026-10-14 afternoon: the queue, the lane rules and the lanes in flight (input to the audit and to compaction)"
type: research
status: active
---

# Root working state, 2026-10-14 ~15:50 local

## Lanes in flight

| Lane | Model | Work | Files held (approx.) |
|---|---|---|---|
| writeback-fix | Opus | write-branch! NPE, refusals, dropped comments, partial install (5771d8049 landed: new-ns write) | src/my/program.clj, src/seon/cluster/source.clj |
| verdict-honest | Sonnet | task proofs always run; reply says what ran (n nearest of m reaching) | src/seon/turn.clj |
| request-view | Opus | later forms in a request see its own definitions (d/with), one recording transaction | src/seon/sci/eval.clj (+patches) |
| b1-durable | Opus | cross-branch test reuse read from main and live branches; red retained; overlay seon.test patches | src/seon/test.clj, runner.clj |
| projections | Opus | one projection per content (no :tx/e in keys), speculative per operation | src/seon/db.clj |
| reconcile-writer | Opus | reconciliation normalizes only changed entities (89% of write time) | seon.fn (after boot-dup) |
| boot-dup-analysis | Sonnet | boot double analysis patch, gated on row equality | src/seon/fn.clj, src/seon/cluster.clj |
| s3d-2 | Sonnet | documents: lead rule, structural fallback, :no-lead/:no-summary (ready to commit) | src/seon/ref.clj |
| s3a-1 | Opus | root reads chains and starts their first task | src/seon/cluster/agent.clj |
| s4a | Sonnet | three stage templates, then step deletions | resources/seon/tasks/seon.step.*.edn |
| live-chain-rough | Opus | rough Prove -> Implement live run (paid DeepSeek, provider lifted for it) | none (root REPL) |

## Queue (verbatim)

# Lanes queued until S1a releases its 166 paths (root, 2026-10-14)

From reflevels-opus.md (c81161289) "Broken things" and default's open fault:

1. Opus: root's lifecycle pass throws on a deleted agent branch. Open fault: `seon.cluster.agent/taskless-agents` → `seon.cluster.source/unmerged-count` → `seon.cluster.registry/branch-commit-id` "store holds no branch :agent-a062e0c7f9fc" (05:03:09Z, inside run-pass! lifecycle). Survives TASK-CHAIN (not in §6). Fix at owner: the pass must read agents and branch existence from one basis (no guard, no catch). Red proof: a lifecycle pass after an agent's branch is deleted answers without an error.
2. Opus: a recorded error repeats on unrelated replies: `seon.sci.eval/install-row!` re-runs `user/L`'s init against `user/db` (a map) on every reply of the probe agent, 25 occurrences. Why does an installed row's init re-run per reply?
3. Sonnet: `my.program/ns-unmap!` refuses: `:my.program/context` not filled by schema name (CLAUDE.md "Fill defaults by declared schema name").
4. Sonnet: `(doc <ns>)` lists ~106 clojure.core Vars qualified under the namespace (e.g. `seon.sci.reader/*agent*`), ~800 tokens of junk; `dir` is right. Producer `seon.sci.lookup/namespace-documentation`.
5. Sonnet: `my.fs/section` on `resources/seon/help.md` returns no text; `#your-task` section-missing.
6. Sonnet: file task `seon.sci.reader/name-the-read-event-schema` (anonymous `[:vector :map]` output hides `:seon.sci.reader/error`; only 56% of contracted arities name an output schema).
7. Astra (perf list): a probe request 1,048 ms / 3.6 GB step allocation; arity-ref queries 0.26–1.3 GB.
8. Root: vocabulary row "summary, doc, code (reference levels)" saved at scratchpad/vocab-row.txt → replace the "light reference, deep reference" row in docs/seon/architecture/vocabulary.md.
9. Root: file task `seon.sci.eval/a-program-change-reruns-every-branch-value-rows-init` (entry text in docs/research/agent-platform/2026-10-14/install-row-reruns.md, 8ad2a751b) once S1a releases resources/seon/tasks/seon.sci.eval.edn. Design round in flight: dig-overlay-rebuild.md (Haiku) → Opus + Astra independent → Fable hybrid. Option 2 (seon.db/db throws) belongs to the errors cut S-reads; option 3 (deliver in the defining reply) to the hybrid.
10. Root: retire probe branch :agent-a3357c1a9c97 (namespace probe.ir1) and Opus reflevels probe :agent-a7a39120a3ca.
11. Root: file task `my.program/ns-unmap-takes-clojure-cores-shape`: agents write `(my.program/ns-unmap! 'user 'L)` (clojure.core/ns-unmap's [ns sym]) and it matches the declared [context subject] arity, so 'user is taken as the context; AGENTS.md "Agents change the program in REPL terms ... as in Clojure": accept [ns sym] with the context filled from the evaluation's bindings (no new name). Evidence: lane-repl-small report, c5a8cd8eb session. Also file the task entry for c5a8cd8eb's `seon.sci.namespace-publics-test/namespace-doc-lists-only-its-own-publics` (seon.sci.lookup.edn) and tag the test, or leave it untagged as a retired task (it is green: retire = no entry).
12. After perf-analyzer (Lane A) commits: Opus lane on `seon.fn/reconcile-tx-in` normalizing the whole entity population on unchanged digests (190 reconciliation/adoption transactions at 1-4 s = 89% of write time; 2.54M `seon.db/carried-projection` calls). gemini/perf-transact.md, handoff.md §4 (f21bdcbca). Then the rest of handoff §4: whole-program rehearsal (~13 s per change), contract recompiles, per-datom attribute checks, input-path? classification (after lane-lifecycle-branch).
13. Boot seon.source/current 30.7 s (Argon lane hit quota 2026-10-14): give to Astra after overlay-astra, or Argon after reset; brief = the boot-source-current spec in this session.
14. File task `seon.cluster.source/publish-names-one-meaning`: `seon.merge/publish!` (write files + git commit) and `seon.cluster.source/publish!` (read the checkout into a cluster) share a word; rename one per vocabulary (dig-merge-writes-files.md ce3617af2). Also file `seon.cluster.source/merge-commit-names-its-author` (hard-coded Co-Authored-By: Claude Opus 5.5).
15. After 17:25 local (Gemini quota reset): Argon-low third catalogue on scratchpad/content-keys-brief.md as truths-argon (bin/agy-agent run truths-argon); then Fable hybrid of truths-opus/astra/argon.
16. After lane proj-content-key releases src/seon/db.clj: Opus lane on speculative projections: `:seon.db/value-projection` holds 267.8 MB in 25 entries with 2 hits / 30 misses (cache-after-boot.md 42cdf2d68; projection-duplicates.md 953e0e933: 6% hit rate), pushing out other kinds (analyzer parse cache 538 evictions / 582 misses). Design: a speculative projection lives for the operation that needs it (held as a value, passed explicitly through the transaction), the commit's content entry serves afterwards; keep db.clj `speculative-projection` docstring's ruling B case fast (a write after a 22-registration batch: 30 ms vs 642 ms rebuild): measure it parent vs change with seon.profile/measure.
17. Filing batch: `seon.cache/status-is-snapshotted-at-boot-ready` (no per-kind snapshot at :seon.boot/ready, so "what boot fills" cannot be answered; cache-after-boot.md).
18. S1d-2 lane (my.task/update!) also teaches the installed my.edit/text in resources/seon/help.md (help has 0 mentions; installed since 776a04249) and replaces refine! there in the same commit that installs update!; help must teach only installed forms (gemini/handoff.md §8).
18. S1d-2 lane (my.task/update!) also teaches the installed my.edit/text in resources/seon/help.md (help has 0 mentions; installed since 776a04249) and replaces refine! there in the same commit that installs update!; help teaches only installed forms (gemini/handoff.md §8).
19. After repl-trial reports: Opus lane on the REPL commit path's reliability, data loss first: (a) write-back drops ;; comments a redefined defn omits (chain-experiment-9 P17, no task), (b) write-back-commits installs files one by one with no rollback on :install-failed, then the frictions: write-digest-error refusing valid defns on agent branches, empty diff after write-back, second write-back refused :conflict (base does not advance), writes to unclaimed files; also the reset vs CLAUDE.md conflict (does reset delete agent branches?). Sources: dig-repl-commit-path.md (9e8d8251f), repl-trial.md. Also bin/lane still advertises --accept-red-before (R9 says remove).
20. Filing batch: `bin/lane`: a failed `bin/lane commit` keeps no log of its output (a red run id is lost; s1c-1 report). Flake hunt: `seon.task-test/ready-answers-in-under-10-ms-for-fifty-leaves` 18.4 ms vs 10 ms under concurrent check JVMs (run 7e6a5f41cf40). Load-flaked bounds seen today: `seon.test.redefined-callee-test/a-branch-acquired-from-a-writers-base-runs-the-program-its-head-holds` (17-19 s loaded vs 7 s alone, 15 s bound), `seon.context-test/a-steps-block-names-its-chain` (5.17 s vs 5 s). Check queueing: 193 s of a 259 s check.
21. S3d-3 patch docs/research/agent-platform/2026-10-14/s3d-3-schema-key-levels.patch (10 files, proven: red 1dbbb7db7a03, green 5db5945eb27c, reaching 62751346220f with 5 HEAD reds): apply + commit with --test on its four proofs once s3d-2 (seon.ref), projections (seon.db) and writeback-fix (my.program) release. Root accepted the 1.1% publication allocation rise (feature, not perf). Then update TASK-CHAIN S3d-3 status. Load flake: seon.publication-cost-test/publication-cost-follows-changed-inputs ~1% under bound when loaded.
22. Red sweep additions (HEAD reds seen 2026-10-14 afternoon): nine seon.sci.eval-test / acquisition-cost-test / supplied-database-test vars listed in 4ed6d57d9's message; instrument-test/a-sovereign-sci-fork-refuses-by-throwing-under-both-dials; five from S3d-3's reaching run (2777254c86a5). Timing edges: eval-test/no-agent-work-writes-a-program-context `(< 0.5 0.5)`, eval-test/equal-programs-on-two-branches-share-one-program-context (cache pressure). Retire probe branches: overlay-impl :agent-390ddc075683, s3d1b-before, repl-trial's, lane-install-row :agent-a3357c1a9c97.
23. Filing batch (seon.operator.edn): keep `a-booting-jvms-class-directory-is-pruned-under-it` open, rewritten as the airtight design: each launch hard-links its digest dir into a private per-pid dir before loading (inodes survive a prune of the shared original; no lock, no window); 16840a911's rename-aside narrows the window to microseconds but rests on JVM start latency. Retire `a-restart-prunes-the-program-classes-a-starting-check-uses` as covered by 16840a911 + that task. Keep its `:rollback :unknown` message ask in the kept task.
24. After b1-durable releases src/seon/test.clj: Astra on the test step's cost (teststep-verdict 70933f422): a request that first defines tests spends 3.8-4.8 s / 5.7-7.1 GB in the step vs 345 ms / 232 MB for an unchanged check; top: seon.test/changed-definition-symbols 381-390 ms (scans branch history since the last run's basis), seon.test.runner/commit-results! 187 ms + seon.test/clean-request-handle 157 ms, seon.test/member-result 126 ms; allocation unattributed (profiler records time only). Leftovers to retire: agent teststep-verdict's tsv.* namespaces; default's probe var user/tsv-before.


## Lane rules given to every lane (verbatim)

# Lane rules (read fully, then your assignment)
Seon repo /Users/sean/src/seon. Read CLAUDE.md (AGENTS.md is the same file), .agents/skills/data-oriented-clojure/SKILL.md and .agents/skills/clojure-testing/SKILL.md; .agents/skills/datahike/SKILL.md if you touch transactions. Context: TASK-CHAIN (docs/seon/schedule/cards/TASK-CHAIN.md) is READY and being implemented slice by slice (§7); S1a has landed (task files use `:seon.task/plan`). Do NOT change code its §6 deletes unless your slice is the one deleting it; if your fix needs to, stop and report.
- ONE behavior. Write the red proof first: a deftest asserting data, tagged with its task `^{:seon/tasks #{'ns/task-name}}` (tags stay the installed proof mechanism until TASK-CHAIN S2). The task entry exists in resources/seon/tasks/<ns>.edn (named in your assignment). Confirm it red on HEAD with a real run BEFORE writing the body (`bin/seon check --at HEAD --test NS/VAR` with the test added, or the check's red run) and put that red run's id and its failing assertion in the commit message: a commit without a red run on HEAD does not count as proven. Then the simplest body that makes it green at the producer. Retire the task (delete its entry, keep the deftest untagged) in the commit that makes it green.
- TIME BOX ~20 minutes of implementation. If not green by then, leave the red proof and the work as a patch file under docs/research/agent-platform/2026-10-14/ and STOP with notes. If the code contradicts your assignment, or you need a design decision or a dependency change, STOP and report options. No workarounds.
- Library and git words only. NO GUARDS OR FENCES (no fallback that hides a nil, no catch-and-continue, no retry): fix the producer.
- NEVER touch the shared git index: no git add, git add -N, git rm, git reset, git restore --staged, git stash, git commit --amend, git rebase (HEAD is shared; a fix is a new commit). bin/lane commit stages exactly your paths through its own temporary index (deletions included). Claim BEFORE editing (an edit before the claim makes the claim refuse).
- Claim before editing: `bin/lane claim LANE PATHS` (check `bin/lane status`; never edit a file another lane holds: write a patch instead). Commit with `bin/lane commit LANE -m MSG --test NS/VAR ... PATHS` (one --test per test; name every test reaching the change; list pre-existing reds in the message). Pass the message with `-m`, or from a file named after your lane (`<lane>-msg.txt`), never a shared name such as `msg.txt` (two lanes once committed under one message). Message ends with a blank line and `Co-Authored-By: Claude <Model> 5.5 <noreply@anthropic.com>`. Release claims.
- Never stop/reset/restart default (report RESTART NEEDED). Never SCI as root on default; JVM-mode reads are fine. Use your own agent branch in SCI mode if you probe.
- Never catch an exception to turn a failure into a value unless you are the entrant boundary CLAUDE.md names; if a task seems to need it, stop and report.
- Report: commit, proof red→green (with the red run's id), timings, anything root must decide.

## Spec gaps (owner ruling 2026-10-13)
When your assignment leaves a gap, DECIDE, RECORD, CONTINUE: pick the simplest option consistent with the rules (no guards or fences, one owner per decision, data over code, delete what's replaced, reuse the existing function/library), write the decision into the commit message, and keep going. Stop only if the decision would change another lane's held files or contradict an owner ruling.
- A scripted sweep (rename, mechanical conversion) claims its files ONLY for its commit window: run the script over current HEAD, lint, check, commit, release. Never hold a wide claim while working on something else; re-run the script over HEAD when you start the window.

## REPL-first by default (owner 2026-10-14: "make this smooth as shit for Seon agents by dogfooding it")
For an ordinary function change (not the evaluator, writer, boot, schema resources, configuration, dependencies, bin/ or script/: those stay host-bound and commit with `--test` through `bin/seon check`): work as your own agent id through MCP `eval_clj` in SCI mode: read `(seon.context/current-state)`, `in-ns` the target, `defn` the change and the red deftest on your branch, read the reply's test step; write back with `my.program/write-branch!`; commit with `bin/lane commit LANE -m MSG --branch-evidence <agent-id> PATHS` (no throwaway JVM). If any step fails or confuses you (write-back refuses, evidence missing, context wrong or noisy, a tool missing), do NOT stop: apply the same edits to the files by hand, commit through `--test` as before, AND file each friction as a task in its producer's tasks file with the exact form and reply (or list it in your report if that file is held). The frictions are the deliverable as much as the fix.

## Queue items added after 9fa2ba933 (verbatim)

25. Write-back decisions (writeback-fix report): item 1 option (a) kondo fork keeps its own SCI ctx var → lane kondo-fork prepares; bump in next restart batch (note kondo-fork-bump.md) + red proof patch writeback-fix-hook-red-proof.patch; file `seon.fn/lint-entities-answers-a-finding-without-a-position` when seon.fn.edn frees (reconcile-writer). Item 3 option (a): accepted source is the declaration; the defn reply and write-branch! name the comment lines being removed (task seon.cluster.source/a-redefinition-without-its-comments-deletes-them-from-the-file → Sonnet after writeback-fix). Unfiled friction: bin/lane claim refuses a new file under a not-yet-existing directory. Fixed: 5771d8049 (new-ns write), f9bc67d84 (all-or-nothing install). E2E add path worked: defn 1.7 s, write-branch! 185 ms, --branch-evidence commit 3.3 s vs 5 min via check.
26. After S5 (deferred): reconcile-writer patches reconcile-writer-one-projection.patch + red proof (fcb3c55b8d9d; reads drop 1,768 → constant; est. 673 → ~40 ms), then convert seon.db/stored-collection to take the projection (6 callers). The db.clj key-digest part went to lane projections.

Lanes at ~16:20 local and the order: plan section 0 (175906558) and the resume memory; S3a-1 done d8332722c; s2b-2 launched.

27. request-view (986b6d79a; patches request-view.patch + a seon.test patch 2 to write): land after S3a-2, patch 1 and 2 together (patch 1 alone turns a false green into a loud provenance error). Root decisions: results computed on the in-request view are NOT recorded (the request's test step records the true ones after its one transaction); `ns-unmap!` option (a): judge on the view, unmap natively, the request records the removal, its own transaction deleted (convert test/my/program_mutation_test.clj's direct JVM callers to requests). Correction to patch 1: do not bind a database value (`seon.db/*working*` breaks AGENTS.md "a database value is never bound"): bind the request's applied facts (data) and compute the view from the connection's head plus those facts at the read, the working value only a cache keyed by head commit and facts digest; record the word in vocabulary.md.

28. Rough live chain 1 (live-chain-rough-1.md 4e2731bcb; tasks 810496199): Prove (0d0e604c3fd9) merged itself (installed auto-merge); Implement (90a6132f928c) green on its branch, merge request 51efd81cbc42 OPEN, refused because lane s3d-2's uncommitted seon.ref/section edits conflict: after s3d-2 commits, root repairs the merge on a merge branch and reviews (Implement's flaws: inline `[:string {:min 0}]` instead of `[:maybe :seon.ref/path]`; a redundant check; `:seon.error/offending` nil for "#" instead of absent: ask for those fixes before merging). Provider config restored by the run lane. The Prove unmap sentence fixed in TASK-CHAIN §4 (a8d44126f) and sent to lane s4a for seon.step.prove.edn. Four defects listed in the note (held task files): file them (my.program/search misuse with no result; my.program/source refuses task symbols; no `clojure.core/subs` suggestion for `str/subs`; redefining `locate` refused on output when only input changed). Hand acts map to TASK-CHAIN mechanisms (note §"what TASK-CHAIN must replace"); `my.root/close!` writes no attempt end (filed seon.task/a-closed-attempt-without-an-end-blocks-its-task).

## Evening, 16:00-16:50 (root)

Landed: S2b-2 e67cb2ba0, projections phase 1 c1dd12e6e, b1 cross-branch reuse 923eff09d (root applied the stopped
lane's patch), S3d-2 fc7d5994b, the tag fix e1db21f25, S3d-3 d8b6e1fce, clj-kondo bump 7882e7c4f, S4a templates
840d175a8, check-wait's measurements 19a8e2c1b, sentinel note bea2c4cae. Default restarted onto e1db21f25 at 16:26.

29. HEAD stopped booting at ad653ff14: rough run 1's Prove merge was published by the installed path once s3d-2 freed
    test/seon/ref_test.clj, and its write-back added the runtime-only task `seon.ref/implement-a-pathless-reference-section`
    to two deftest tags; boot ingests tasks from files, so it refused (dangling-id) and every lane's check failed at
    publication. `start --head` rolled back cleanly. Fixed by tags (e1db21f25); the producer fix (a merge never writes a
    runtime task identity into a file) is a row of S3b's red proof.
30. Sentinel on e1db21f25 (root ran `seon.sentinel/run`: its probes are SCI as root, so Haiku cannot): warm `(+ 1 2)`
    66.6 ms vs 38.6 baseline (1.7x; settle 54 ms), fresh 223-261 vs 214 ms, `defn` 670 ms (test-step 162, settle 204,
    custody 132). Boot ready 47,820 ms, 4.7x the 10-13 median, `seon.source/current` 88% of it; check-wait measured the
    same publication (about 20 s) inside every check's throwaway boot, plus a 23 s + 9 s rehearsal per check while
    default lags HEAD. Next perf lane: publication cost (after check-wait's proposal).
31. Dogfood gap (s3d-3, s2b-1): an outside lane's `(seon.context/current-state)` shows only `:my.agent/turns-left` and
    no task, because our lanes hold no task. S3f-2 (`:outside` per-task model) is what makes Claude Code lanes real
    Seon agents with a block, proofs and a done rule: pulled forward to right after S3a-2.
32. To file (held files at the time): a check's failure report drops the `actual:` line for a non-trivial value
    (kondo-bump had to write the value to a file to see a contract error); `:seon.config.test/step?` is read in
    `seon.turn` but declared in no schema; the test step costs 1,415 ms / 1,787 MB for one `defn` plus one 33 ms test
    (s2b-1) and 98 ms / 425 MB for a scratch `def`; `bin/lane claim` and `bin/seon check` refuse a quoted multi-path
    variable and blame `--test`; `bin/lane commit` cannot name a task's intended red proofs, so a tags-only fix names
    a green neighbour.
33. Lane rule added: no lane writes, restores or checks out a file another lane holds (kondo-bump reverted
    ref_test.clj with `git checkout`; s3d-3 re-applied its hunk from a saved diff).

## Night of 10-14 → 15 (root; owner asleep from ~20:45; orders: refine functions, renders, printer, instructions, context; sanity-check; no live runs while known things are broken)

Landed overnight (all live on default by 05:05 unless noted): S2a dc47cae3f, S2b-1 e3716cd30, S3a-2 674a8de72, S3a-2b
2dba90a5b, S3a-3 76c4bed09, S3b-1 e958b1700, S3b-2 0331e584a + 2b5829f40 (chain tasks never in task files) + 70fdd8ffb
(write-back reads git HEAD), S3c-1 bdc09786a, S3f-1a d024947fe, S3f-2 c2eab5ab5, S4a 840d175a8, S4b 38cca803d
14cc74ab4 65f4c292e; S5 defects D1-D8 (4dd6fab76 e1b547528 24d0d382e 1d8bbec16 4463dda3f 1c42de87c 108c2e077);
context: red values 373391e48, rerun block 14e881c6b + 9d57ee3b8, stable block in cached prefix ba2e0f299, old reply
meta 3ce843a85, history by reference a1c4970e6, stable must-reads da91498ce, changes render c94977458, taught forms
3b900522d, unresolved names offer search 5c78940d9, alias forms are code d17b7f67e; REPL: require refusals delivered
11f8c3263, request view 0f7db6347 + a239a2a73, host-copy private Vars 4121ddf4a, alias survives lazy install 10171f0cd,
send-back writes its end e54a9b587, nested time limits 8c0744b53; errors hold references e52ae96c5 221cb228f, db/q guard
deleted 712bb86e5; cost: test-step harness closure 160fbf821, Datahike as-of index-page (fork 62f94893, bump edd63b16e:
as-of digest 26 s → 1.9 s), publication analysis f9a315309; branch predicates phases 1-3 00c8b3d4d e79736317 50037b679;
per-action branch connection race 8b9ca750d; agy archive mode 86794e83a (an Argon lane's `git checkout` had wiped two
claimed files).

Morning questions for the owner:
1. `my.program/ns-unmap!` in clojure.core's `[ns sym]` shape: A drop the explicit-context arities → `[context ns sym]`
   only and convert 1-arg `'ns/sym` callers (root lean); B change "an exact declared arity is never prefixed"; C a new name.
2. Lifecycle of MCP lane agents' branches (~100 + base copies accumulate; `my.agent/branches` is noise): retire like an
   abandoned chain once the agent has no running graph and no unmerged change?
3. "This namespace loads in babashka for the edit hook" was prose only and an automatic merge broke it: a declared
   namespace property checked where requires are recorded and shown in the block, plus a bb-load test?
4. A contract naming a branch-born predicate is refused at the writer (`seon.schema/a-contract-naming-a-branch-predicate-is-refused-at-the-writer`).
5. The predicates hybrid's larger option (a branch defn rebinding main's Var) — recommended against for now (8926d8d61).

Open, in order: block build 6.0 s / 11.7 GB per model call (lane block-cost running); then DeepSeek S5 run 5 on the held
Implement task c12a57408f49 (chain :chain-f1f6dc8e6652) with a low cap; errors cut (block refusals still carry
`:seon.error/at`/`:layer`); order-independent declaration content key (ed2ffc32e); Argon request-fixed-cost-2 note
(quota-blocked, resume once); predicate held apart through interpret-affected! does not re-arm (red row);
`:seon.test/reach` empty on branch eval-request runs; outside-1's chain e439 to abandon; root scratch notes in the
session scratchpad root-pending.md.
