# Audit: v1 Seon → next, capability by capability (2026-10-22)

Read-only audit of `~/src/seon` (v1, JVM + SCI, 107,546 lines of `src`, 18,799 commits, HEAD `825d0530b`) against `~/src/seon-jolt/next` (Jolt, HEAD `2bcfdfa`, 16,770 lines of `src`, 390 commits). No code in either repository was changed. v1 was started live (below) and stopped when done.

**How to use this file.** Every item names its evidence (`path:line`, a commit, or a live REPL excerpt) and its recommendation on next's pillars. §4 turns the recommendations into task entries in the `resources/seon/tasks/<ns>.edn` shape (`:seon.task/plan`, `:seon.task/proofs`, `:seon.task/needs`), ready to paste after the owner settles §5.

**Path prefixes:** `v1:` = `~/src/seon/`, `nx:` = `~/src/seon-jolt/next/`, `notes:` = `~/src/seon-jolt/notes/`, `AP:` = `v1:docs/research/agent-platform/`, `MEM:` = `~/.claude/projects/-Users-sean-src-seon/memory/`.

**What was verified and what wasn't.** Five read-only sweeps covered v1 and next by topic. I re-checked these load-bearing claims in next's code myself:
- `nx:src/seon/reconcile.clj:75` (unrun proof = red);
- `reconcile.clj:36-39,334` (failure → `:attach`);
- `nx:src/seon/context.clj:57-67,217` (var references ignore depth);
- `nx:src/seon/db.clj:70` (`:keep-history? false`);
- `nx:src/seon/fx.clj:33-50` (`:file` writes any path);
- `nx:src/seon/agent_proc.clj:198-206` (kind is the only check);
- `nx:src/seon/web.clj` (no Origin check);
- `nx:src/seon/context.clj:13-50` (system prompt);
- the unmerged branches' ahead/behind counts.

Other `file:line` citations come from the sweeps and were not each re-opened. No test suites were run in either repository.

---

## 0. v1, observed live (2026-10-22, 14:48–15:00)

Started with `env -u DEEPSEEK_API_KEY -u SERPER_API_KEY … bin/seon start` (v1's default operator root, writing only under `v1:data/`). It booted `825d0530b` in 14.7 s (JVM pid 36592, embed server pid 36939 on 8701) and ran a store collection at boot (1 object swept). I drove it through prepl with `seon.mcp/call`, the same function its MCP bridge uses. The default cluster runs `:seon.config.ai/no-provider true` (it shows on the attempt's record page), so no model call was possible. Stopped with `bin/seon stop` (`:stopped? true :process-exit? true`, embed server stopped); `git status` in v1 is clean.

What it showed, in the order an agent meets it:

1. **`(help)` is eight sections of data** ("Who you are", "How to speak", "Your task", "How work proceeds", "Write what you learn", "When you cannot", "Each reply is data", "Data"), 312 ms and 124.5 MB to build. It teaches concrete forms:
   - `(my.program/source 'seon.db/q)`, `(my.program/callers 'seon.db/q)` "never a text search";
   - `in-ns` once, never repeated;
   - probe in a `let`, never `def`;
   - `my.task/update!` with `my.edit/text` for passage edits;
   - `send-back!` as the last form;
   - "Never weaken a proof or a contract to go green".
2. **A real task's context** (`v1:` filed task `my.edit/exact-has-no-digest-for-a-file-over-eight-kilobytes`, chained with `seon.task/chain!` of `seon.task/from-filed`, `:seon.chain/merge? false`, then `(my.root/run! {:seon.task/assigned "3b7b14217c69"})`). The debug page's "Next call" (`/agent/attempt%2Faae259011cd1/debug?sent=true`) showed:
   - **System: "(help) and the attempt sections ≈8550 tokens"**, then "Exchanges 0", then **"Turn sections ≈754 tokens"**. The stable, cacheable part is 92% of the prompt.
   - Attempt sections: the task's `## Problem` plan and its Design instructions, **`:chain :after` with the next two steps' full instructions**, `:attempts` (attempt 1 `:stalled`, 0 calls), and **`:must-reads`**. Each cited function comes at `:seon.ref/level :code` with file, line, doc, source and contract; each keyword at summary or doc with its schema form; a file path at `:summary` with `:seon.ref/tokens 21303` and the form that reads it (`(my.fs/section "src/seon/sci/kernel.clj")`). `my.program/search` comes at doc depth with arglists, in/out schemas and examples.
   - Turn sections: `:step` (status `:working`, the done rule's refusal `:seon.source/red-test-error` with the runnable `(my.test/check {...})`, and `:seon.context/start [(in-ns 'my.edit)]`); `:proofs` (the design hand-off proof red, failure `:only-in-second [[:sections ["what-exists" "approach"]] [:producer-not-cited …] [:data-not-modeled :seon.schema/key]]`, plus the form that reads its source); `:cost`.
3. **The chain was three tasks, not four:** design, `-2` (prove instructions) and `-3` (implement instructions). The merge step from `from-filed` is not written "until T3b" (`v1:src/seon/task.clj:1076-1090`).
4. **Verbs** (as a fresh outside agent `audit1`, which got its own branch `:agent-e8d9c042ce8f` on first use):
   - `(my.program/search :seon.task/plan)`: 8 ranked matches, schema-key producer first, each with `:my.program/candidate-tokens`, and `:more (my.program/search {:text … :limit 24})`. 139 ms.
   - `(my.program/similar db "merge a branch into main and record conflicts" 5)`: `[[my.root/merge-main! 0.866] [seon.merge/merge-main! 0.856] [seon.merge/merge! 0.83] [my.program/resolve! 0.821] [seon.merge/merge-tx 0.813]]`. 67 ms.
   - `my.program/history {:seon.program/subject 'seon.merge/merge!}`: each source version with `:seon.db/tx`, `:added?`, `:process`, `:source`.
   - `(my.program/callers 'seon.merge/merge!)`: call sites with form spans and call arity, plus `:seon.program/unknown [{:gap :outside-graph …}]`. An honest "unknown" member.
   - `(my.program/sizes 'seon.merge)`: `{:count 26 :lines 608 :largest [[merge-main! 88] …]}`.
   - `(my.fs/stat …)`: path, kind flags, `:byte-size`, `:modified-at`.
   - `(my.shell/run! {:my.shell/argv ["git" "log" "--oneline" "-3"] :my.shell/cwd "."})`: `:exit 0`; stdout and stderr each with `:bytes`, `:digest`, `:preview-complete?`, `:text`.
   - `(my.test/check)` after a `defn` on the branch: `:seon.test/executed-count 0`, `:reused-count 0`, **`:seon.test/unreached [[:seon.fn/sym seon.merge/audit-probe]]`**. Coverage is reported.
   - `(my.root/turns-report)`: `#:seon.grade{:attempts 2 :green 0 :quantiles {} :steps [{:step :design …}]}`.
5. **Refusals are good data.** A wrong call answers the expected shape plus a generated `:example` and a `Fix:`. Examples:
   - `my.program/history` given a symbol: "expected :my.program/history-request (a map)… Fix: Supply a map", with `:expected [:map …]` and `:example {…}`.
   - `my.shell/run!` without cwd: "expected the required key :my.shell/cwd…".
   - A Datalog read of an uninstalled attribute: "cannot read uninstalled attribute :seon.task.attempt/key" with `:seon.db/registered-candidates [:seon.task.attempt/end …]`.
   - An ended agent: `run transition refused: admission-closed` with the fix "This agent's attempt has ended…".
6. **Health mistakes who must fix an error.** After those four agent-side call mistakes, `runtime_status` reported `:seon.health/state :faulted`. Each agent arity or contract mistake was an `:open-fault` with score 160, its `:subject` attributed to `seon.profile/bracket` (the instrumentation wrapper, not the caller), linked to filed tasks. So an agent's own wrong call made the cluster "faulted" and was blamed on the wrong producer. Next's law already says a typed error goes to the requester (`nx:AGENTS.md` §2); keep it that way (§2.10).

---

## 1. Executive summary

### Where next stands
next is not "redoing everything from scratch." Most of v1's 20 census ideas (`AP:2026-10-20/census/ideas.md`) are present in next in a far smaller form:
- the one printer (`nx:src/seon/print/select.clj`, ported from v1 with Rules 1–10 unchanged);
- the reference parser (`nx:src/seon/ref.clj`);
- the forgiving reader (`nx:src/seon/reader.clj:188`);
- contracts armed at definition, cost proofs with stated estimates (`nx:src/seon/cost.clj`);
- tasks plus one running reconciler (v1's pure reconciler `v1:src/seon/cluster/reconcile.clj` was never wired in; next's runs);
- the three-way program merge;
- write-back through git plumbing;
- agents as forked processes.

The eval path is about 2,000 lines against v1's about 18,000. The `my.*` surface is 436 lines against 2,736.

The gaps are concentrated where agents do **real work** and where **context quality** is decided. Those are the two levers v1's live runs and next's R4 experiment measured as decisive.

### The biggest gaps
1. **Storage on main is still pre-plan:** no history, no compression, inline values, no GC. The fix is finished and unmerged on `jolt/blobs` (11 ahead, 0 behind). It is missing from `docs/parity.md` and RESUME's "State" section.
2. **The agent does not know its task** (`my.task/current` is nil in released agents, DESIGN-tasks §11 #12). This undermines every context and verb that reads the task.
3. **Context generation dropped v1's and R4's best-measured ideas:**
   - no `:found` helpers (R4: 7/32 → 22/24 green, turns p50 3 → 1, tokens −69%, `notes:option3-R4-learning.md` §0);
   - var references ignore their citation depth and have no size cap (`nx:src/seon/context.clj:57-67,217` against v1's `reference-tokens 3000`);
   - sections are sent twice per turn (`context.clj:299,302`), and the overflow note breaks the cached prefix (`:300`);
   - no `(help)`, and the system prompt is unchecked prose (`context.clj:13-50`). It names neither `my.agent/callers`, `callees`, `grep`, `source` nor `my.fx`, and teaches `my.task/update!` without a task id. v1's checked help was its most-proven turn saver (census idea #3).
4. **No verbs for a user's real work:** no `my.fs`, `my.edit`, `my.shell` or `my.web`. The owner ruled them wanted (DECISIONS, 2026-10-22; `nx:AGENTS.md` §1). next's effect owner exists (`nx:src/seon/fx.clj`) but has no permission model: any agent may ask `:git`, `:build` or `:file`, and `:file` writes any path with no digest precondition (`fx.clj:33-50`, `agent_proc.clj:200`).
5. **The done rule and failure flow diverge from v1's lessons:**
   - an unrun proof is red (`reconcile.clj:75`; v1 `b54d25f68`: unknown);
   - `send-back!` triggers an automatic refinement chain (`reconcile.clj:334`; v1 held the task for root);
   - no coverage refusal (v1's `:seon.test/unreached`, seen live).
6. **Templates are strings applied only after a failure** (`nx:src/seon/template.clj:122-171`). A request never gets a design step, although the 2026-10-22 ruling says a request becomes design → implement → merge.
7. **Merge:**
   - main does not win a conflict, and `unmerged` judges only against ours (DESIGN §11 #9);
   - `seon.merge/program` takes 440 ms per db against M1's 34 ms at 10k vars;
   - nothing computes what changed (v1 built an address-skipping `datoms-diff` in its Datahike fork, `771a0c4b`, never bumped).
8. **The owner's view and the door:**
   - no `runtime_status` equivalent through MCP;
   - `seon_agents` dumps 230+ tasks;
   - no message bar;
   - messages are not facts;
   - `/call` has **no same-origin check**: a local web page can POST evaluations into every live agent (`nx:src/seon/web.clj:84-104`; v1 had `::same-origin`).

### The best v1 ideas not yet carried over
- Context = stable attempt sections in the system message (≈8.5k tokens, cached) plus small turn sections (≈0.75k). Observed live; 85–91% cache hits in runs 9–13.
- `(help)` as sectioned data whose every reference resolves, tested (`v1:test/seon/bootstrap_test.clj:10`), with lessons from live runs pinned as rows (`519c745fd`: "bind task in a let, never def" ended 6 of 9 attempts' cross-pollution).
- `:must-reads` with a per-function token cap and the form that reads the rest.
- Refusals that carry a generated `:example`, the expected schema and a `Fix:`, and unknown-attribute reads that name candidates.
- Coverage (`:seon.test/unreached`) as part of the done judgement.
- Durable effect receipts: opened before the effect runs, settled once, never re-dispatched (`v1:src/seon/effect.clj:1-9`).
- Effect policy in the handler: fs roots, no symlink following, digest preconditions on writes, argv-only shell with an environment allowlist, manual redirects (`v1:src/seon/fs/jvm.clj:108-127`, `shell/jvm.clj:73`, `web/jvm.clj:42,297-334`).
- Messages as facts with received-tx custody, and a message wakes a turn.
- `turns-report` (median, p90, per step, per model) and the cache-hit share as the run's first numbers.

### Top 10 moves, in order
1. **Land `jolt/blobs`** (storage A.1–A.6: history on, LZ4/zstd nodes, values over 1 KiB out of line inside Datahike, GC at quiescence, tx-meta in the replica), then erase and re-seed the standing store with the owner's confirmation (DECISIONS: "The standing store is erased"). Size: a merge plus `bin/check`; zero new design.
2. **The agent entity carries its task** (DECISIONS "One agent entity"; DESIGN §6.2): root writes `:seon.agent/*` with its task ref on the branch before the fork, and `my.task/current` is one entity read (v1 run 13 measured 1.4–4.3 s and 1.8–3.7 GB for a mid-attempt read; this must cost under 1 ms). About 60 lines changed.
3. **Context v2 inside `seon.context`, no new namespace:**
   - (a) attempt sections in the system message, rendered at the attempt's start commit, and turn sections last; delete the double send and move the overflow note;
   - (b) var references honour depth (summary / doc / code), with `:seon.config.context/reference-tokens` capping code depth;
   - (c) a `:found` section: helpers the plan doesn't name, from the call graph of the red proofs and the plan's references first, embeddings later (R4's lever);
   - (d) report cache-hit share.
   Proof: R4's A/B through `seon.learn/verdict`.
4. **`(help)` from the wiki:** move `system-prompt` into `nx:docs/agent.md` (sections loaded by `seon.docs`), add `(my.agent/help [anchor])`, and run every backticked form in it through the existing `the-template-instructions-run` harness (`nx:test/seon/repl_test.clj:53`). That makes next's check stronger than v1's.
5. **Done rule and failure flow:** unrun proof → `:proof-unknown`; `send-back!` → held for root with `:target` and counterexample as data (D2 option a); add the coverage refusal "a changed function no green test reaches" at the last task.
6. **Templates as task data** (decided 2026-10-22): templates as held tasks in `resources/seon/tasks/seon.templates.edn`; `:seon.task/owned` on member schemas drives what carries; one `from-request` creates design → implement → merge at creation; implement on design's branch; proofs never accumulate; design's done is a proof (`design-done`), not `:no-design`.
7. **Merge correctness and speed:** `:seon.merge/kept` (main wins on a merge branch built from main's head); `seon.merge/program` as one `:aevt` scan per attribute (M1); changed-since as a fork function diffing two index trees by node address (port v1's `771a0c4b`), not a `:seon.tx/changed` stamp unless the owner overrides "no stamps".
8. **Effects for real work, with policy and receipts:** `:fs`, `:edit`, `:shell` and `:web` kinds in `system.edn :fx`, with `my.fs`, `my.edit`, `my.shell` and `my.web` as thin wrappers over `my.fx/call`. Port v1's handler policies as data (dials under `seon.config.fs/shell/web`). Mark `:git`, `:build`, `:deps` and `:source` root-only. Open a receipt on the branch before a non-idempotent effect runs. Add the `/call` same-origin check in the same lane.
9. **The door and the owner's view:**
   - `seon_status` (root's AI render, already at `/context.txt`);
   - `seon_agents` filtered to live work;
   - JSON-RPC −32601 for unknown methods;
   - land `jolt/door`'s per-request defects (rebased; 299 behind);
   - messages as facts on the recipient's branch plus the `/agent/{id}/message` bar;
   - `bin/root logs|open`.
10. **Close the learning loop, then run E:**
    - `turns-to-green` (median, p90 per template, per commit, per model) plus cache-hit share in `seon.learn`, on the dashboard;
    - fix the two model-path defects (a 429 with `rate 0` retries immediately; transport errors are not retried despite the docstring, `nx:src/seon/model.clj:36-41`, `fx.clj:268-282`);
    - then the scripted design → implement → merge dry run, which v1 never completed live (run 13: Implement 0/2).

---

## 2. Capability-by-capability audit

Status words: **present**, **partial**, **missing**, **better** (next exceeds v1), **divergent** (different in a way that loses a v1 lesson).

### 2.1 Agent REPL verbs and tools

**What v1 did.** 12 `my.*` namespaces, 2,736 lines (`v1:src/my/*.clj`):

| Namespace | Lines | Verbs |
|---|---|---|
| `my.agent` | 236 | `identity`, `settings`/`settings!`, `branch`, `branches`, `create-branch!`, `retire-branch!` |
| `my.background` | 91 | `background`, `poll`, `await` |
| `my.edit` | 135 | `form!`, `exact!`, `text`, `lines!` |
| `my.fs` | 133 | `read`, `write!`, `glob`, `stat`, `section` |
| `my.message` | 77 | `inbox`, `read`, `received!`, `send` |
| `my.program` | 1,314 | `callers`, `tests-reaching`, `reads-key`, `breaks`, `history`, removal verbs, `source`, `search`, `similar`, `sizes`, `diff`, `write-branch!` |
| `my.root` | 307 | `instructions`, `merge-requests`, `merge!`, `close!`, `run!`, `abandon!`, `merge-steps`, `merge-main!`, `turns-report` |
| `my.shell` | 41 | `run!` |
| `my.task` | 135 | `current`, `update!`, `send-back!` |
| `my.test` | 195 | `check`, `check-platform`, `run` |
| `my.turn` | 25 | `wait` |
| `my.web` | 47 | `fetch`, `search` |

Live, all of the read verbs answered in 5–140 ms (§0).

**What next has.** 8 namespaces, 436 lines (`nx:src/my/*.clj`):
- `my.agent`: `$`, `whoami`, `messages`, `send-message`, `callers` (Datalog over `:form/refs`), `callees`, `grep`, `source` (`my/agent.clj:17-85`);
- `my.db`: `db`, `q`, `pull`, `transact!`;
- `my.fx`: `call`, `send`;
- `my.branch`: `merge-main!`, `unmerged`, `resolve!`;
- `my.schema`: `register!`, `forms`, `registration`;
- `my.task`: `current`, `update!` (own plan or instructions only), `send-back!`;
- `my.turn`: `evals`, `wait`;
- `my.root`: root's verbs, each taking `sys`.

**Status.**
- Navigation is **partial**: callers, callees, grep and source are present; ranked search, sizes, breaks, history and diff are missing.
- Removal with a blocking-referrer check is **missing**. Plain `ns-unmap` works but checks nothing.
- `my.db` is **better**: v1 hid Datalog behind `seon.db/q`'s elided-db convention, which help had to explain.

**What v1 learned.**
- Help taught `ns-unmap!` on a keyword (`0b2b0082c`, run 10) and `def` of task symbols (`519c745fd`, run 13). Every verb help names must be executed by a test.
- `my.program/search` ranked owners before tests and showed each candidate's token cost. That lets an agent choose what to expand, which grep cannot.

**Recommendation.** Extend `my.agent` with pure reads of the agent's own branch, about 120 lines total, no root round trip. No new namespace: `my.program`'s v1 name can be an alias later if help wants it.
- `search` (Datalog over `:form/src` and var names, ranked name match > caller count > owner over test, with `seon.print/more` for the rest and a token estimate per match);
- `sizes`;
- `breaks` (`callers` + `seon.program/reaching-tests`);
- `diff` (the three-way program against the merge base, which `my.branch/unmerged` already computes).

Add `my.agent/unmap!` that checks `breaks` and answers `:seon.program/blocked` as data, then `harvest!`s like `my.branch/resolve!` (`nx:src/my/branch.clj:43-44`). Each verb is one row in `docs/agent.md` (§2.3) and is executed by the help test.

### 2.2 Tasks and templates

**What v1 did.**
- About 90 `:seon.task/*` attributes (`v1:resources/seon/schemas/seon.task.edn`). Notable ones: `:step`, `:plan` (flows), `:instructions`/`:proofs`/`:model` (`:seon.task/owned true`), `:needs` (cycle refused at the writer, `v1:src/seon/task.clj:257`), `:next` (the chain edge actually used), `:execution` (`:held :running :paused :stopped :cancelled`).
- Templates were held task files `v1:resources/seon/tasks/seon.step.{design,prove,implement,merge}.edn`.
- `from-filed` (`task.clj:1076-1117`) made four linked tasks; `chain!` (`:1298-1368`) forked `chain-<handle>` and wrote everything in one transaction; `advance-tx` copied flowing members.
- TASK-CHAIN §1 planned to make `:needs` the only edge; never reached (`7fa9ec012`).
- Live: the chain wrote three tasks (merge step deferred "until T3b"), and later steps saw their own instructions plus the plan (§0.2–0.3).

**What next has.**
- `nx:src/seon/task.clj`: create-tx writes held tasks and refuses a missing need or a cycle as data (`:68-94`); reviewable task files (`:280-376`); `accumulate` hard-codes plan joining and proof union (`:180-195`).
- `nx:src/seon/template.clj`: the templates are strings; `attach-tx` (`:122-171`) builds design → implement → test → merge **only after a failure**.
- `:seon.task/kind`; each task gets its own default branch (§11 #13).

**Status: divergent.** v1 had task-data templates and a design-first chain from the start. next has neither, although both were decided on 2026-10-22: "Templates are held tasks in the task EDN, never released"; "A request becomes three tasks linked by `:needs`"; `:seon.task/kind` goes; `:seon.task/owned` drives carrying.

**What v1 learned.**
- Hand-off checks can't see a reversed Approach or a proof that is red for the wrong reason (runs 4 and 13).
- Only Design updated the plan although every step was told to (`MEM:feedback_use_the_designed_chain_2026_10_19.md`).
- Runs 10–12 skipped the designed chain and did worse.
- No full design → implement → merge ever merged live (run 13: Design 3/4, Prove 2/3, Implement 0/2; `AP:2026-10-19/live-run-13.md`).

**Recommendation.** One lane, write set `seon.task`, `seon.template`, `resources/seon/tasks/seon.templates.edn`, `resources/seon/schemas/seon.task.edn`:
- move the template text into held template tasks;
- add `{:seon.task/owned true}` to the `:instructions`, `:proofs` and `:model` member schemas and derive `accumulate` from it (deleting the hard-coded join);
- `seon.template/from-request` (replacing `with-merge` and `attach-tx`'s chain building) creates design → implement → merge at creation, with implement on design's branch;
- proofs are never carried (§11 #14);
- `:seon.task/kind` goes: the attributes present decide;
- `my.task/edit!` on any task id replaces `update!` (DESIGN §5.2, about 5 lines).

Estimated net src: −80 (strings to data, two builders to one).

### 2.3 Context generation and help

**What v1 did.**
- `seon.context/model-context` (`v1:src/seon/context.clj:1166-1202`) builds the system message as `(help)` plus attempt sections rendered on the attempt's starting db (`block-views` `:1007-1019`, so the bytes repeat each turn and cache), then exchanges, then turn sections.
- Must-reads go through `seon.ref`'s depth rule (`v1:src/seon/ref.clj:290,300,697,721`): a bare word is summary, a backtick is doc, three backticks are code. Program functions the plan cites are given as code only within `:seon.config.context/reference-tokens 3000` (`ref.clj:844-857`, `v1:config/default.edn:237`). Library vars and attributes stay at summary.
- `(help)` is `resources/seon/help.md` (59 lines) parsed into sections with resolved references, cached by digest plus revisions read (`v1:src/seon/bootstrap.clj:20-69`), and tested (`v1:test/seon/bootstrap_test.clj:10,39,50`, `help_test.clj:50,95`).
- Live: help plus attempt sections ≈8,550 tokens and turn sections ≈754 (§0.2). Cache share 85–91% from run 9 on; run 8 fell to 22% when the prefix moved (`AP:2026-10-18/live-run-8.md:93-95`).

**What next has.**
- `nx:src/seon/context.clj:279-302`: the system prompt literal (`:13-50`), the first turn's context re-rendered from its recorded basis, history turns newest-first within `history-budget 32000`, then the current block under `context-budget 16000`.
- Sections are task, turn, proofs (with one level of callees when red), references, sections, refused, test-run, your-last-reply, evidence, unmerged, last-evals (`:207-244`).
- The wiki is mirrored per heading (`nx:src/seon/docs.clj:101`).
- The context is rebuilt from the basis and never stored (`:355-363`): **better** than v1, which stored rendered text.

**Status: partial.**
- Var references ignore depth: `resolve-ref` (`context.clj:57-67`) returns the whole source for any citation, with no cap. A real task's context was 17 KB, 8.9 KB of it whole source of cited functions (`notes:LEARNING-2026-10-22.md`).
- Sections and references go out twice per turn (`:299` and `:302`).
- Overflow edits the first user message (`:300`), so the cache breaks every turn after overflow.
- No `(help)`.
- The system prompt names neither `my.agent/*` navigation nor `my.fx`, and teaches `(my.task/update! :seon.task/plan f & args)`, against the decided `edit!` on any task.
- **The R4 `:found` section never landed** (no `:found` in `src/`, `notes:DECISIONS.md:217` says decided, not built). R4 was run **on next**: 22/24 green vs 7/32, z = 5.17, cost about $0.20 (`notes:option3-R4-learning.md` §0, §5).

**What v1 learned.**
- The quality of the generated context is the strongest lever measured.
- Help must be checked against the program (idea #3: 6 of the 12 double-red spec tests were on ideas #1 and #3, `AP:2026-10-20/census/README.md:33-38`).
- One missing var (`clojure.repl/source`) caused 33 of 36 eval errors in R4. Next fixed it (`nx:src/seon/repl.clj:69`).

**Recommendation.** All inside `seon.context`, `seon.ref` and `seon.docs`; no new namespace:
1. **Split** `current-state` into attempt sections (task, plan, references, sections, evidence, the task graph) and turn sections (proofs, refused, test-run, last reply, last evals, turn n of m). Render attempt sections at the attempt's start commit (the basis already records it) into the system message, and delete them from the current block. Put the overflow note in the current block.
2. **Depth for vars:** pass `:seon.ref/depth` through `context.clj:217` to `resolve-ref`. Summary is arglists plus the docstring's first sentence; doc is the docstring plus contract; code is source. Code depth is capped by a new dial `:seon.config.context/reference-tokens` (v1's 3000 is the provenance); over the cap, give doc depth and the runnable `(my.agent/source 'x)`.
3. **`:found`:** for red proofs, the program functions their bodies and the plan's references reach (one hop through `:form/refs`) that the plan doesn't name, top k by a dial, at summary depth. Embeddings join through the `:embed` effect later (§2.12), fused by RRF as decided (`notes:DECISIONS.md:217`; EM1 R@10 0.441 fused vs 0.386 graph).
4. **`(help)`:** `nx:docs/agent.md` holds the agent's orientation as wiki sections; `seon.docs` loads it; the system message is its sections. `my.agent/help` answers `docs/view` for one anchor. The template-instructions harness also runs `agent.md`'s backticked forms.
5. **Measure:** add a `cache-share` column to the learn report (§2.15); A/B context v2 against today's with `seon.learn/verdict` on R4's eight tasks (paid; owner's go).

### 2.4 Tests, proofs and done rules

**What v1 did.**
- Proofs were recorded results keyed by reach digest (`v1:src/seon/test/runner.clj:1460`); `seon.test/select` (`v1:src/seon/test.clj:938`) reused an unchanged result, and `seon.test.cache` digested file inputs.
- The done rule, `seon.cluster.source/done-refusals` (`v1:src/seon/cluster/source.clj:1458-1514`), refuses: a member with no current result ("unknown, not green", `b54d25f68`), red proofs, other red members, contract gaps, file and clj-kondo refusals, functions no green test covers (last task), and unresolved unmerged entries.
- The merge used the same list (`127fe54f6`), after run 4 showed done and merge disagreeing.
- Live: `my.test/check` reports `:seon.test/unreached`.

**What next has.**
- Proofs run in a fork with a tick budget (`nx:src/seon/agent.clj:373`): proofs ∪ changed vars ∪ reaching tests (`:299-309`).
- `reach-digests` (`nx:src/seon/program.clj:733-759`); reuse by digest only in `bin/test --reach` (`nx:test/test_runner.clj:87-108`).
- `done-refusals` (`nx:src/seon/reconcile.clj:58-81`).
- Cost proofs with stated estimates (`nx:src/seon/cost.clj`).
- A profiler at 0.9 ns per call (`nx:src/seon/profile.clj:15`; v1's was about 0.9 µs). Its counts are not stored.

**Status.** Proofs and cost proofs are **better**. The done rule is **partial**:
- `:proof-red` for an unrun proof (`reconcile.clj:75`);
- no coverage refusal;
- agents' proof runs never reuse results (`nx:docs/parity.md:54`);
- 76 of 154 task proofs have no deftest (LEARNING, "Recurring").

**What v1 learned.**
- A step-verdict over 12 kept chains read 12 s and allocated 29 GB (`b54d25f68`): judge from recorded results, never by re-running.
- 3,428 of 3,471 v1 tests had no stored result (`ideas.md:443-445`).
- A hub change ran about 167 tests per request (`db683577f`).

**Recommendation.**
- `:proof-unknown` when the result is absent or the run errored, with the context saying "not yet run" rather than "fix it".
- `:uncovered` at the last task: a changed function that no green test reaches. `reaching-tests` already exists; invert it.
- Agents' `run-tests-forked` reads and writes results keyed by `reach-digests` in the same store as `bin/test --reach` (`../test-results`), so an unchanged proof is never re-run inside an attempt.
- A `bin/check` mode fails when a task names a proof with no deftest.

### 2.5 Merges and conflicts

**What v1 did.**
- `prepare-merge!` (`v1:src/seon/cluster/source.clj:1246-1301`) forks main's head into a merge branch where "main wins every conflict: S holds main's version" (`:1269-1270`), runs reaching tests there, then `accept-merge!` writes main once under a basis fence.
- MERGE-STEP 5a (`e2bf7c579`): conflicts are unmerged data; ours is the target; any write resolves.
- "Whole chain merges" (`ac12586a5`): a reach-selected list once missed 4 chain-only helpers.
- 774 tx ids collided across branches (`AP:2026-10-15/one-merge-base.md:20-40`); fixed by commit-graph merge base (fork `593a8276`) and real merge commits (`331eec6ec`, `aa0bf23d3`, `4ca233c04`).
- An address-skipping `datahike.api/datoms-diff` (fork `771a0c4b`) was built and never bumped.
- Ended branches kept (`KEEP-BRANCHES.md:71-73`, about 140 KB per commit).

**What next has.**
- `seon.merge/program` keys forms, vars, methods, impls, namespaces, schemas and datoms (`nx:src/seon/merge.clj:77-95`).
- `three-way` (`:117-133`); `mutations` catches intern, alter-var-root, eval and inline defs (`:137-151`).
- Unmerged entries (`:287-303`); forward merge with `d/merge-db` and main as parent (`nx:src/seon/agent.clj:195-219`).
- The writer's `merge-refusal` (behind, unmerged, mutation, uncontracted; `nx:src/seon/writer.clj:38-74`) and an octopus `merge-train` of disjoint members (`:76-108`), which v1 never had.
- Write-back through git plumbing on a ref (`nx:src/seon/fx.clj:83-111`).

**Status.** The merge train and plumbing write-back are **better**. Conflict semantics are **divergent**: ours is kept, and `unmerged` judges only against ours, so a merge branch built from main reads an open conflict as settled (DESIGN §11 #9). Speed is **worse**: 440 ms per program map, three maps per merge, against M1's 34 ms at 10k vars (`notes:option3-M1-merge.md:270-279`). Tx ids still collide (§11 #4).

**Recommendation.**
- **Speed:** rewrite `live-forms` and `form-row` (`nx:src/seon/program.clj:411-441`) as one `:aevt` scan per attribute joined in memory: M1's fix, no format. Cost proof: linear in forms at 3 µs per form.
- **Changed-since:** port `771a0c4b`'s address-skipping diff onto the Jolt Datahike fork (persistent-sorted-set already walks shared nodes, `persistent_set.cljc` near line 290), O(Δ log V). Use `:seon.tx/changed` only if the owner overrides "no stamps".
- **Kept side:** `:seon.merge/kept` on each entry; the merge task's branch is built from main's head. Whether main wins or the request carries an explicit list is an open decision (§5, Q3).
- **Ids:** audit every `t` compared across branches (`:seon.attempt/merged-t`, `basis-t` at `nx:src/seon/reconcile.clj:298`) and replace each with a commit id.

### 2.6 Program model and write-back

**What v1 did.** `seon.fn` (4,455 lines over clj-kondo) indexed definitions without evaluating them, with definition and behaviour digests (`v1:src/seon/program.cljc:601,645`). `write-back!` replaced recorded byte spans and proved the file (`v1:src/seon/cluster/source.clj:2380-2430`). Agents could `write-branch!` without merging.

**What next has.** Forms recorded through the REPL path with the compiler's facts (`nx:src/seon/program.clj:276-403`). Files are rendered from live forms plus their leading text (`nx:src/seon/source.clj:189-199,309-334`), committed through the `:git` effect. `my.root/build!` and `bin/root next` run the next generation (`nx:src/my/root.clj:107-130`, `nx:bin/root:67-74`).

**Status: better.** It is smaller, uses the compiler's facts instead of a second analyzer, and closes the loop to a running binary.

**Recommendation.**
- A round-trip proof that `file-text` reproduces every source file byte for byte.
- A2 (store as the source of truth, compiled units by content): measure only after `jolt/blobs` lands, as RESUME orders.

### 2.7 Storage, blobs, history and GC

**What v1 did.**
- konserve file store with LZ4 nodes, `:keep-history? true` (owner ruling, `818fb3baf`), branching factor 128 with a fused-index-roots fork option (974 KB → 184 KB per small commit, `v1:src/seon/cluster/store.clj:245-255`).
- Blobs over 4,096 bytes out of line with LZ4 (`v1:src/seon/blob.clj:234-252`).
- Nightly cron collect, log rotation and footprint check (`v1:src/seon/maintenance.clj`, `v1:src/seon/schedule.clj:47-101`).
- Live: boot collected 1 of 26,339 objects.

**What next has.** On main, LMDB, `:keep-history? false` (`nx:src/seon/db.clj:70`), no compression, no GC, and an unbounded `runs/root.log`. On `jolt/blobs` (11 ahead, 0 behind):

| Step | Commit | Change | Measured |
|---|---|---|---|
| A.1 | `d1ef143` | realistic proof text | |
| A.2 | `7c57dda` | history dial, default true | |
| A.3 | `4dc1233` | LZ4 nodes through liblz4 | 1,674 → 967 KB |
| A.4 | `31c1540` | values ≥ 1 KiB out of line inside Datahike's node storage | 1,485 → 237 KB at n = 20 |
| A.4b | `014e8ab` | `:db.type/data` replaces EDN text; zstd | 194 KB |
| A.5 | `a53f998`, `dcc0083` | GC at quiescence | 5,148 of 159,152 keys swept in 11 s on a copy |
| A.6 | `15e65d6` | replica keeps tx-meta | |

**Status:** missing on main, done on the branch.

**What v1 learned.**
- A refused write still flushed dirty leaves: 2.5 → 19 GB at about 1 GB a minute (`v1:config/default.edn:281-289`, fix `f86ec57ed`).
- A health probe committing per item added 17 GB in 10 minutes (`MEM:feedback_health_never_a_drain_2026_09_28.md`).
- "Don't cause the problem in the first place."

**Recommendation.**
- Land `jolt/blobs` now; then erase the standing store as decided, with the owner confirming the moment.
- Add one proof v1's incident demands: a refused agent commit writes no nodes (`seon.db-test/a-refused-commit-writes-nothing`).
- Bound `root.log` by a byte dial at write time, not by cron.
- Report `map-use` against `lmdb-map-bytes` in health.

### 2.8 Rendering and the owner's UI

**What v1 did.**
- `seon.render.value` Rules 1–10 (1,856 lines) and render selection per schema.
- http-kit + Datastar pages (`v1:src/seon/render/route.clj:5-34`): `/`, `/ns/{ns}`, `/ns/{ns}/debug`, `/agent/{id}`, `/agent/{id}/debug`, `/agent/{id}/message` (same-origin), `/agent/{id}/context`, `/health`, `/chains`, `/feed/{id}`, `/data`.
- Live: `/chains` showed the chain, its attempts and ends; the debug page showed "Next call: what it would be sent now" with token counts per part. That is how this audit read a real context.

**What next has.**
- The printer ported (`nx:src/seon/print/select.clj`, 747 lines; emit 436).
- Render selection in 123 lines.
- A reactive render proc with no beat (`nx:src/seon/render/proc.clj:1-25`).
- Tiles plus `agent-page` with `zoom-context` (`nx:src/seon/render/ui.clj:944-961`).
- Routes `/`, `/agent/{id}`, `/feed`, `/call`, `/context.txt` (`nx:src/seon/web.clj:145-180`).

**Status.** The printer and renders are **better**. Pages are **partial**: no `/ns`, `/data`, debug "next call", message bar, `/chains` or `/health`. Security is **missing**: `/call` evaluates forms with no Origin or Sec-Fetch check (`web.clj:84-104`). Several literal budgets and clocks remain:
- `nx:src/seon/render/ai.clj:150` (700);
- `render/proc.clj:296,392` (400/700);
- `context.clj:147` (32);
- `web.clj:87` (`min 100`);
- `web.clj:132` (`deref … 10000`).

**Recommendation.**
- Same-origin check first (a few lines, plus a DRAGONS row).
- Then one `/agent/{id}/next` view rendering `seon.context/render` from a fresh basis (v1's most useful debugging page; it costs nothing new).
- `/ns/{ns}` over `ref/namespace-view` and `docs/view`.
- The message bar as a `:seon/call` posting a message fact (§2.9).
- Literal budgets become `:seon.config.render/*` keys, and `ask!` parks on its answer.

### 2.9 MCP door, messages and operator commands

**What v1 did.**
- Two tools (`v1:src/seon/mcp.clj:82-97`): `eval_clj` (sci or jvm, any agent, recorded as that agent's run) and `runtime_status` (health ranked by severity × impact × age, `v1:src/seon/health.clj:552,646`).
- Messages as facts with received-tx custody that wake a turn (`v1:src/my/message.clj`, `v1:src/seon/cluster/message.clj`, 959 lines).
- `bin/seon` start/status/open/init/stop/down/reset/nuke/logs/config apply/check (`v1:script/seon/operator.clj:2333-2342`).

**What next has.**
- Five tools (`nx:src/seon/mcp.clj:11-25`): `seon_agents`, `seon_eval`, `seon_context`, `seon_attach`, `seon_done`.
- Unknown methods are answered `{}` (`:57-68`).
- `bin/root start|stop|status|next`, `bin/worktree`, `bin/check`, `bin/test`, `bin/ports`.
- Messages in process memory only (`nx:src/seon/agent.clj:66-74,470-472`; `root.clj:301-302`).
- `jolt/door` (11 commits, 299 behind) has per-request defects, `seon_turn`, restore and replay, `{:op :stop}`, and 9 deftests.

**Status.**
- `seon_attach`/`seon_done` are **better** (an outside agent works a task through the same path).
- Health through the door is **missing**.
- Messages are **partial**.
- Operator `logs`/`open`/`reset` are **missing**.

**Recommendation.**
- `seon_status` (entrance `{:op :status}` → `render.ai/system`).
- `seon_agents` lists live agents and running or stuck tasks, with `seon.print/more`.
- −32601 for unknown methods.
- Rebase `jolt/door`: keep main's `bin/root` lifecycle or the door's `{:op :stop}`, one of them.
- Messages: `:seon.message/{id,from,to,body,at}` written on the recipient's branch by the api's `:message` handler; `my.agent/received!`; an unread message on an idle agent is a wake the reconciler sees.
- `bin/root logs` and `open` (two lines each). `init`/`nuke` go in BAGGAGE with a reason.

### 2.10 Errors and problems

**What v1 did.** One error schema with identity (layer, operation, class, frame, location; `v1:src/seon/error.clj:1-13`, 2,840 lines), problems as queries (`v1:src/seon/problems.clj`), health findings as schemas with severity. Live, it mis-ranked agent caller mistakes as cluster faults attributed to the instrumentation wrapper (§0.6).

**What next has.**
- `seon.contract/error` (`nx:src/seon/contract.clj:223-235`).
- Fold by cause in the observer (`nx:src/seon/error.clj:39-49,101`; "Nothing here files tasks or resolves errors", `:13`).
- `seon.problems/report` by class (`nx:src/seon/problems.clj:142-167`).
- `docs/DRAGONS.md` plus `bin/dragons` (**better**: named catch sites).
- Two left-overs: `error.clj:54` hashes `pr-str` of the cause, against v1's ruling "Hashing never goes through printed text" (`MEM:feedback_never_print_huge_text_stop_and_fix_2026_10_07.md:15`); and `root.clj:696,711,718` `(deref … 5000 {})` answers unknown as `{}`.

**Status: partial.** No resolution or reopen, no error → task path (R4 §6 designed one), no severity ranking.

**Recommendation.**
- Classify each error by **who can fix it** at the producer:
  - an `:seon.instrument`-style contract refusal of an agent's call is the agent's (it goes to its reply and its waste row, never a cluster problem);
  - a defect in a program function is a problem of that function's owner;
  - an edge failure is a DRAGONS class.
- Then a signature crossing two or more agents files a prove → fix → merge task (R4 §6), resolved when its producer changes and a merge is green.
- Hash the cause value, not its printed text.
- Replace `{}` on timeout with a recorded `:stalled` fact.

### 2.11 Effects: files, shell, web

**What v1 did.** One effect owner (`v1:src/seon/effect.clj`, 978 lines): the request id comes from turn, form and effect ordinal; a receipt is committed before the handler runs; the result is settled once; recovery never re-dispatches. Handlers held the policy:
- **fs:** roots, no-follow, byte caps, digest preconditions, serialized writes (`v1:src/seon/fs/jvm.clj:21-127,467,570`);
- **edit:** pure rewrite-clj (`v1:src/seon/edit.clj`);
- **shell:** argv-only, time limit, stdin cap, environment allowlist (`v1:src/seon/shell/jvm.clj:73,158,255`);
- **web:** http(s) only, no userinfo, manual redirects with loop detection, body cap; search key by env-var name (`v1:src/seon/web/jvm.clj:42,103,156,297-408`).

Live: shell answered exit, digests and preview completeness (§0.4). v1 also filed a task to *retire* `my.edit` because "files are the write-back… not the working medium" (`v1:resources/seon/tasks/my.edit.edn`, first entry). The owner has since ruled that file and shell work for users is wanted, while Seon's own code changes at the REPL (DECISIONS 2026-10-22; `nx:AGENTS.md` §1).

**What next has.** `seon.fx` (`nx:src/seon/fx.clj`, 341 lines):
- owner and lane procs, priority queue, per-task budget, AIMD token bucket;
- kinds `:echo :file :source :git :deps :build :model` (`nx:resources/seon/system.edn:39-48`);
- outcomes recorded as `:seon.fx/*` folded into the eval's transaction (`nx:src/seon/agent.clj:139-153`, `repl.clj:411-417`);
- test forks stub effects (`*fx-stub*`).
- Not recorded: the request, the value, or a link to the eval; no receipt before running; no per-kind permission.

**Status: missing** for user work; the owner is **present and better shaped** (queues, budgets, back-pressure as flow).

**Recommendation.** One lane, write set `seon.fx`, `my.fs`/`my.edit`/`my.shell`/`my.web` (new, about 30 lines each), `system.edn`, `resources/seon/config/default.edn`, schemas:
- **Kinds:** `:fs` (read, glob, stat, write with digest precondition), `:edit` (exact-span text and form edits; port the pure parts of `v1:src/seon/edit.clj` only if rewrite-clj runs on Jolt, else exact text and line windows), `:shell` (argv, cwd, env allowlist, stdin cap, a tick or byte bound, not a clock), `:web-fetch`, `:web-search` (key held by root like the model key).
- **Policy is data:** `:seon.config.fs/roots`, `max-read-bytes`, and so on.
- **Permission:** each kind in `system.edn` declares `:agents? true|false`; `:git :build :deps :source` are root-only; a refusal is `:seon.fx/refused`.
- **Receipt:** for `:fs` writes, `:edit`, `:shell` and `:web` posts, the agent commits `:seon.fx/status :open` with request and form ordinal before sending. A dead agent's branch shows the open effect; the reconciler marks it `:interrupted` and never re-sends.
- **Record:** request and value (bounded by the printer, out of line after A.4) plus a ref to the eval entity.
- **Names:** these verbs are for any repository and any user task. Help says Seon's *own* code changes at the REPL.

### 2.12 Embeddings and search

**What v1 did.** `seon.embed` (630 lines) against a local MLX EmbeddingGemma server on 8701; packed konserve segments; brute-force cosine top-k (`v1:src/seon/embed.clj:361-384`); a background fill proc; `my.program/similar`. Also nearest names on "not found" refusals (`v1:src/seon/ref.clj:553-563`). Live: 67 ms, sensible top 5 (§0.4).

**What next has.** Nothing. Decided: RRF fusion of plan references, call graph and embeddings, through an effect, gated by A/B (`notes:DECISIONS.md:217,430`).

**Status: missing.**

**Recommendation.** Do it after context v2's graph-based `:found` (§2.3) has a measured baseline:
- an `:embed` fx kind calling the 8701 server (reuse v1's model);
- vectors as out-of-line Datahike values keyed by `H(model, dim, task, definition-digest)` (one store, collected by the same GC; no konserve segments);
- brute-force kNN;
- `my.agent/similar`;
- `:found` fuses by RRF.

The model download needs the owner's go (RESUME open decisions).

### 2.13 Scheduling and admission

**What v1 did.** cron-utils schedule proc per graph arming one `timeout` (`v1:src/seon/schedule.clj:710-743`); a root portfolio of footprint, rotate-logs and compact; fixed slots (`max-running 4`, check slots 4).

**What next has.** No cron proc; slots `max-running 4`, `max-in-flight 8`. Clocks LEARNING lists:
- `seon.root` stall check;
- `agent_proc.clj:42-70` stall and stop bound;
- `fx.clj:204-215` wake tick;
- `writer.clj:267-284` flusher `Thread/sleep`, which LEARNING misses.

**Status:** cron **missing** but not yet needed; admission by workload **missing** (`notes:DECISIONS.md:215`).

**Recommendation.**
- Delete the flusher clock: flush when `drain` comes back empty.
- Replace stop-bound with the fork server's `:exit` event, and stall with the graph's own report.
- Keep the fx tick as the allowed delayed insert.
- Add the cron proc only with its first job (daily `bin/test`).
- Admission: extend `seon.fx/admit` with per-kind expected cost from recorded `:seon.fx/us` and peak memory, and use it for spawns.

### 2.14 Config

**What v1 did.** 733-line `v1:config/default.edn` (about 123 keys) with unit and provenance; `seon.config` reconciled a db row per cluster and branch (`apply!`, `v1:src/seon/config.clj:894`); `bin/seon config apply --branch`.

**What next has.** A 356-line aero file (about 65 keys) under closed schemas; one resolver task → model → provider → config with `{:value :source}` (`nx:src/seon/config.clj:210-229`); config facts on main.

**Status: better** (resolution with provenance); live change is **partial**.

**Recommendation.** Record in BAGGAGE that `bin/root next` (restart) is how config changes, unless an owner need appears. Fix stale literals (§2.8).

### 2.15 Learning and metrics

**What v1 did.** `seon.mine` (situations, charged waste, turns-to-green median and p90 per step, model and commit window; `v1:src/seon/mine.clj:544-729`); `my.root/turns-report` (`b2e8d2ea5`). Live runs:

| Run | Result | Turns to green |
|---|---|---|
| 11 | 2/4 done | median 23 |
| 12 | 2/6 | p50/p90 21/32 |
| 13 | Design 3/4, Implement 0/2 | Design median 15 |

**What next has.** `seon.learn` waste only (88 lines; "R4's template rows and trials are not ported", `nx:src/seon/learn.clj:8`). `:seon.attempt/turns`, `tokens` and `waste` are recorded, and cache-hit tokens per call (`nx:src/seon/model.clj:158`).

**Status: partial.** The facts exist; the report doesn't.

**Recommendation.**
- `seon.learn/report`: median and p90 turns to green per template, per main commit and per model; tokens per green; cache share; top waste rules. One query, rendered on the dashboard and in root's AI context. The owner's key metric is "turns to a good solution, from all runs".
- Port R4's `verdict` only if it isn't already in `seon.learn`.

### 2.16 Model providers

**What v1 did.** One attempt per call, "no retry, no backoff, no failover" (`18602eedb`); cache tokens read from three wire spellings; thinking disabled (9.8 s vs 21.3 s).

**What next has.** Native function calling with one `eval` tool, streaming, `:reasoning-ate-reply` detection (`nx:src/seon/model.clj`); the key stays in root. Defects:
- with `:seon.config.ai/rate 0` a 429 is re-queued and re-sent immediately, and `retry-after-ms` is parsed but unread (`fx.clj:217-233,268-282`, `model.clj:197-219`);
- `transport-error`'s docstring promises a retry that doesn't exist (`model.clj:36-41`).

`max-tokens 65536`.

**Recommendation.**
- A 429 or transport failure goes to a delayed insert of `max(retry-after, dial)` on a `:retry` in-port: the one allowed delayed insert (`nx:docs/flow.md:77`).
- Proof: with `rate 0`, a 429 is not re-sent before its delay.
- `max-tokens` from measured completion p99.

### 2.17 Onboarding, docs and skills

**What v1 did.** An 813-line `CLAUDE.md`, an architecture wiki, 13 skills in `v1:.agents/skills`. The useful ones for next are `llm-providers` (prompt caching), `datastar-web-ui`, `datahike`, `clojure-testing` and `agy-lanes`.

**What next has.** `nx:AGENTS.md` (261 lines), a 9-page wiki plus map, `parity.md`. The wiki rewrite and `bin/check docs` are unmerged (`jolt/wiki` 272 behind, `jolt/patterns-wiki-proof` 267 behind).

**Status: partial.**

**Recommendation.**
- New wiki pages, each with its one home: `agent.md` (the help, §2.3), `effects.md` (§2.11), `storage.md` (from `jolt/blobs`), `context.md` (the context's sections, order and cache rules, plus R4's evidence), `llm.md` (prompt caching and wire facts, from v1's `llm-providers` skill).
- Land or retire the wiki branches. Their gate (`bin/check docs`) is what keeps help and context honest.

---

## 3. Lessons from v1's history

### Do not repeat

| Lesson | Evidence | Where next is today |
|---|---|---|
| Help or orientation that is not executed by a test teaches wrong forms | `519c745fd` (def of task, 6/9 attempts), `393b1e74a` (require in user), `0b2b0082c` (ns-unmap! on keyword); census idea #3 | System prompt unchecked (`context.clj:13-50`); instructions are checked (`repl_test.clj:53`) |
| Done and merge must judge the same members, and unknown is not red or green | run 4 (`AP:2026-10-16/live-run-4.md`), `b54d25f68`, `127fe54f6` | Unrun counts red (`reconcile.clj:75`) |
| A merge list chosen by reach drops helpers | `ac12586a5`; DESIGN §3.2 | `:carry` proposed; needs the missing-callee rule |
| Tx ids are per branch | 774 ids (`AP:2026-10-15/one-merge-base.md`) | Recurred (§11 #4) |
| Relaunch at boot fills memory; "No automatic re-runs" | `e7fb578ff`, `63aac4169`; `MEM:project_metric_first_2026_10_16.md:27` | Next auto-attaches refinement chains on failure (`reconcile.clj:36-39,334`); owner decision D2 |
| A refused write can still cost storage | 2.5 → 19 GB (`v1:config/default.edn:281-289`) | No proof yet |
| Reading the task mid-attempt cost 1.4–4.3 s / 1.8–3.7 GB | `AP:2026-10-19/live-run-13.md` | `my.task/current` is nil; make it one entity read |
| Prompt-prefix churn collapses cache share (87% → 22%) | run 8 | Overflow edits the first message (`context.clj:300`) |
| src growth without deletions (89k lines, 8.9× budget) | `c78274fe1`; 569 commits "moved nothing" | 13.2k → 16.8k in 3 days |
| Deleting branches destroyed the corpus | `MEM:project_keep_branches_2026_10_16.md:10` | Next keeps branches; keep it so |
| Health that blames the wrong producer and counts an agent's own call mistakes as cluster faults | Live §0.6 | Next's problems fold; classify by fixer (§2.10) |
| Paid runs without a plan | "$50 in 3 days" (`MEM:feedback_deliberate_paid_llm_runs.md`) | Paid runs need the owner's go (AGENTS §7) |

### Keep (v1 got these right)
- Green at the start ends a task with no model call: run 3 merged with 0 calls (`AP:2026-10-16/live-run-3.md:23`). next has it (`nx:src/seon/turn.clj:1-17`).
- Reactive start end to end: run 1 merged in 27 turns, no hand act.
- Refusals carrying the form or example to act on: run 11 vs 10, 2 calls vs 15; live §0.5.
- Stable-prefix context: 85–91% cached.
- Context naming helpers: R4 (in next) 7/32 → 22/24.
- Cheap Datahike branches: about 2 MB per branch; `d/branch!` 382 B.
- Estimates written first, an overrun is red: COST-REG after `nearest` passed every test at 6.3 s / 15.4 GB (`v1:docs/seon/schedule/cards/COST-REG.md:10-13`). next has `seon.cost/proof`.
- One error value; one printer with runnable continuations; one reference parser. All carried.

---

## 4. Sequenced plan back to near-parity in goals

Each item is a task entry. The **file** is `nx:resources/seon/tasks/<owning-ns>.edn`; proofs are deftests the lane writes first and watches fail. Sizes are net `src` line estimates and lane-hours. Phases follow AGENTS' order: delete first, then foundations, then fixes. Items in one phase with disjoint write sets run in parallel.

### Phase 0: land what is done, delete what is decided (day 1)

```clojure
;; seon.db.edn
#:seon.task{:name "land-storage-plan-a"
 :plan "Merge `jolt/blobs` (11 ahead of main, 0 behind: d1ef143..15e65d6; RESUME plan A.1-A.6): history dial `:seon.config.store/keep-history?` default true, LZ4/zstd node compression through liblz4 built by bin/build, values >= 1 KiB out of line inside Datahike's node storage (fork jolt/values-out-of-line 4ae0593), `seon.db/collect!` at quiescence, report->tx-meta. Pin bumps in root's landing only. Then update docs/parity.md rows History/blobs/GC and RESUME State. Erasing the standing store waits for the owner's confirmation (DECISIONS: storage)."
 :proofs #{seon.db-test/a-commit-costs-what-it-writes seon.db-test/history-is-kept-by-default
           seon.db-test/a-large-value-is-written-once seon.db-test/collect-sweeps-only-unreachable-keys
           seon.db-test/a-refused-commit-writes-nothing}
 :needs #{}}
;; size: merge + full check; +0 design; new proof a-refused-commit-writes-nothing ~20 test lines

;; seon.writer.edn
#:seon.task{:name "flusher-has-no-clock"
 :plan "`seon.writer/flusher-proc` sleeps the rest of flush-ms (writer.clj:267-284), a clock AGENTS §2 forbids. Flush when `seon.writer/drain` returns empty (the queue drained), and delete flusher-proc and :seon.config.store/flush-ms."
 :proofs #{seon.writer-test/a-drained-queue-flushes-once seon.writer-test/an-idle-writer-does-nothing}
 :needs #{"seon.db/land-storage-plan-a"}}
;; size: -30 lines, 2h
```

Also in phase 0, as root's acts rather than tasks: read and land or retire `jolt/door`, `jolt/wiki`, `jolt/patterns`, `jolt/patterns-wiki-proof`, `jolt/runner`, `jolt/unitflake`, `jolt/isolation`, `jolt/laws` and `jolt/r5-port` (ahead/behind counts in §2.9 and §2.17).

### Phase 1: foundations (contracts other tasks need)

```clojure
;; seon.agent.edn
#:seon.task{:name "the-agent-knows-its-task"
 :plan "`my.task/current` is nil in released agents: root keeps the task in its view and never sends it (DESIGN-tasks §6.2, §11 #12). Fold the attempt into one agent entity (DECISIONS 'One agent entity'): root writes `:seon.agent/*` (task ref, branch, model, config, status) on the branch before the fork; `seon.turn/current-task` and `my.task/current` read it as one entity. Replaces :seon.attempt/* start fields and the fork message's missing :task. v1 measured 1.4-4.3 s per mid-attempt read (live-run-13); this must be one entity read."
 :proofs #{seon.agent-test/a-released-agent-reads-its-task
           seon.agent-test/current-task-is-one-entity-read-cost}   ; cost proof: O(1), < 1 ms
 :needs #{}}
;; size: ~+40/-60, 4h

;; seon.task.edn
#:seon.task{:name "templates-are-task-data"
 :plan "Templates move from `seon.template` strings to held tasks in resources/seon/tasks/seon.templates.edn (DECISIONS 2026-10-22). `{:seon.task/owned true}` on the :instructions, :proofs, :model member schemas drives `seon.task/accumulate` (replacing its hard-coded join, task.clj:180-195). `seon.template/from-request` makes design -> implement -> merge at creation (replacing with-merge and attach-tx's chain building); implement on design's branch; proofs never carried (§11 #13, #14). :seon.task/kind goes; attributes present decide (a merge carries :seon.task/target). v1: v1:src/seon/task.clj:1076-1117 from-filed."
 :proofs #{seon.template-test/a-request-becomes-design-implement-merge
           seon.template-test/implement-runs-on-designs-branch
           seon.task-test/owned-members-carry-by-schema-property
           seon.task-test/proofs-never-accumulate}
 :needs #{"seon.agent/the-agent-knows-its-task"}}
;; size: net -80, 1 day

;; seon.task.edn
#:seon.task{:name "edit-any-task"
 :plan "`my.task/update!` edits only the agent's own plan or instructions (§11 #16). `my.task/edit!` takes a task id and a member (DESIGN §5.2): any task's plan, instructions, proofs (add only; a proof's form digest is recorded when added; only root removes). Replaces update!."
 :proofs #{seon.task-test/design-writes-implements-proofs seon.task-test/an-agent-cannot-remove-a-proof}
 :needs #{"seon.task/templates-are-task-data"}}
;; size: ~+20/-15, 2h
```

### Phase 2: the done rule and failure flow

```clojure
;; seon.reconcile.edn
#:seon.task{:name "an-unrun-proof-is-unknown"
 :plan "`seon.reconcile/done-refusals` line 75 counts a proof with no result as :proof-red. v1 b54d25f68: 'a member with no current result is unknown, not green'. Emit {:refusal :proof-unknown} when the result is absent or proofs/run-errors is non-empty; the context says 'not yet run'."
 :proofs #{seon.reconcile-test/an-unrun-proof-refuses-as-unknown seon.reconcile-test/done-and-merge-judge-the-same-members}
 :needs #{}}
;; size: +10, 1h

#:seon.task{:name "send-back-holds-for-root"
 :plan "`send-back!` flows into `failed` -> :attach (reconcile.clj:334), an automatic retry. v1 held the task for root (merge-rulings.md:65; 'No automatic re-runs', MEM project_metric_first). Per owner decision D2 (§5 Q2): a :sent-back end bails (stuck-tx) and records :seon.end/target and :seon.end/counterexample as data; refinement chains only from a rule the owner enables."
 :proofs #{seon.reconcile-test/a-send-back-is-held-with-its-reason}
 :needs #{}}
;; size: +15/-10, 2h

#:seon.task{:name "uncovered-changes-refuse"
 :plan "v1's done rule refused 'functions no green test covers' at the last task; live, my.test/check answers :seon.test/unreached. next has no such refusal. At the last task, every changed function (seon.program/changed-vars) must be reached by a green test (seon.program/reaching-tests); else {:refusal :uncovered :symbol s}."
 :proofs #{seon.reconcile-test/an-unreached-change-refuses-at-the-last-task}
 :needs #{"seon.task/templates-are-task-data"}}
;; size: +20, 3h

#:seon.task{:name "agents-reuse-proof-results"
 :plan "Agents re-run unchanged proofs every eval (parity.md:54). Key run-tests-forked results by seon.program/reach-digests in ../test-results, the store bin/test --reach uses."
 :proofs #{seon.agent-test/an-unchanged-proof-is-not-rerun}
 :needs #{}}
;; size: +30, 4h
```

### Phase 3: context v2 (the measured lever)

```clojure
;; seon.context.edn
#:seon.task{:name "attempt-sections-are-the-cached-prefix"
 :plan "v1 sent (help) + attempt sections in the system message, the same bytes every turn (v1 context.clj:1002-1019; observed live ≈8,550 tokens vs ≈754 turn tokens; cache 85-91%). next sends sections and references twice (context.clj:299,302) and edits the first message on overflow (:300). Split current-state into attempt sections rendered at the attempt's start commit (in the system message) and turn sections (last); move the overflow note into the current block."
 :proofs #{seon.context-test/the-prefix-is-byte-identical-across-turns
           seon.context-test/no-section-is-sent-twice
           seon.context-test/overflow-keeps-the-prefix}
 :needs #{"seon.agent/the-agent-knows-its-task"}}
;; size: net ~0, 4h

#:seon.task{:name "references-honour-their-depth"
 :plan "`seon.context/resolve-ref` (context.clj:57-67) returns whole source for any citation; current-state keeps only names (:217). Pass :seon.ref/depth: summary = arglists + first sentence, doc = doc + contract, code = source capped by new dial :seon.config.context/reference-tokens (v1: 3000, v1 config/default.edn:237); over the cap, doc depth plus (my.agent/source 'x). Follow a cited var's docstring citations at summary."
 :proofs #{seon.context-test/a-bare-name-is-a-summary seon.context-test/code-depth-is-capped-by-the-dial}
 :needs #{}}
;; size: +40, 4h

#:seon.task{:name "found-names-the-helpers"
 :plan "R4 (notes/option3-R4-learning.md §0): a :found section of helpers the plan does not name took 7/32 green to 22/24, turns p50 3->1, tokens -69%. Not on main. First form: for each red proof and the plan's references, the program functions reached one hop through :form/refs that the plan does not cite, top :seon.config.render/found-count, at summary depth. Embeddings fuse in later by RRF (DECISIONS:217)."
 :proofs #{seon.context-test/found-lists-reached-helpers-the-plan-omits}
 :needs #{"seon.context/references-honour-their-depth"}}
;; size: +40, 4h; then the A/B (paid, owner's go)

;; seon.docs.edn
#:seon.task{:name "help-is-a-wiki-page"
 :plan "No (help); system-prompt is unchecked prose (context.clj:13-50) that omits my.agent/callers, callees, grep, source and my.fx and teaches update! without a task id. v1's help was checked (v1 test/seon/bootstrap_test.clj:10) and its lessons pinned as rows (519c745fd). Move the orientation into docs/agent.md sections loaded by seon.docs; the system message is its sections; (my.agent/help [anchor]) answers docs/view; the-template-instructions-run (repl_test.clj:53) also executes agent.md's backticked forms. Port v1 help's lessons as sections: probe in let not def; in-ns once; callers is one read; never weaken a proof."
 :proofs #{seon.repl-test/every-form-in-agent-md-runs seon.docs-test/every-reference-in-agent-md-resolves}
 :needs #{"seon.task/edit-any-task"}}
;; size: -38 code, +1 page, 4h
```

### Phase 4: merge correctness and speed

```clojure
;; seon.merge.edn
#:seon.task{:name "program-in-one-scan-per-attribute"
 :plan "seon.merge/program takes 440 ms per db (live-forms 243 ms) because live?/form-row call d/entity per form (program.clj:411-441). M1 (notes/option3-M1-merge.md §5) measured 34 ms at 10k vars with one :aevt scan per attribute joined in memory. Do that; no new format."
 :proofs #{seon.merge-test/program-cost-is-linear-in-forms}   ; cost proof, 3 us/form
 :needs #{}}
;; size: net ~-20, 4h

#:seon.task{:name "changed-since-by-tree-diff"
 :plan "Nothing records what a transaction changed (§11 #8). v1's Datahike fork built an address-skipping datoms-diff (fork 771a0c4b, never bumped); persistent-sorted-set already walks shared nodes (persistent_set.cljc ~290). Port it as a fork function `datahike.api/datoms-diff` on the Jolt line, O(Δ log V); merges and the divergence map use it. Needs root's go (dependency change)."
 :proofs #{datahike.api-test/diff-skips-shared-nodes seon.merge-test/changed-since-costs-what-changed}
 :needs #{"seon.db/land-storage-plan-a"}}
;; size: fork ~+150, seon -60 (three full maps -> one diff), 1 day

#:seon.task{:name "the-kept-side-is-recorded"
 :plan "unmerged judges only against ours (merge.clj:298-303; §11 #9), so a merge branch from main reads an open conflict as settled. Record :seon.merge/kept on each entry; build the merge task's branch from main's head; v1 MERGE-STEP 5a (e2bf7c579): main wins on a merge branch. Depends on owner Q3."
 :proofs #{seon.merge-test/an-open-conflict-stays-open-on-a-branch-from-main}
 :needs #{"seon.merge/program-in-one-scan-per-attribute"}}
;; size: +30, 4h

#:seon.task{:name "no-t-compared-across-branches"
 :plan "Tx ids are per branch (§11 #4; v1: 774 collisions). Replace every cross-branch t comparison (:seon.attempt/merged-t, basis-t at reconcile.clj:298) with commit ids."
 :proofs #{seon.reconcile-test/staleness-is-judged-by-commit-id}
 :needs #{}}
;; size: ~+10/-10, 3h
```

### Phase 5: real work through effects (parallel with phases 3–4; disjoint write set)

```clojure
;; seon.fx.edn
#:seon.task{:name "effect-kinds-declare-who-may-ask"
 :plan "The stand-in admits any declared kind (agent_proc.clj:198-206): an agent can ask :git, :build, :file. Each kind in system.edn :fx declares :agents?; :git :build :deps :source are root-only; refusal :seon.fx/refused as data, counted by root's problem fold."
 :proofs #{seon.fx-test/an-agent-cannot-ask-a-root-only-kind}
 :needs #{}}
;; size: +15, 2h

#:seon.task{:name "an-effect-opens-a-receipt"
 :plan "v1 committed a receipt before the handler ran and never re-dispatched (v1 src/seon/effect.clj:1-9). next records only the outcome (agent.clj:139-153). For non-idempotent kinds the agent commits :seon.fx/status :open with the request, kind and form ordinal before sending; settle writes value (printer-bounded) and a ref to the eval. The reconciler marks an open receipt of a dead agent :interrupted."
 :proofs #{seon.fx-test/a-dead-agents-open-effect-is-interrupted-not-resent
           seon.fx-test/an-effect-links-its-eval}
 :needs #{"seon.fx/effect-kinds-declare-who-may-ask"}}
;; size: +50, 6h

#:seon.task{:name "files-and-shell-for-any-work"
 :plan "Seon does any work its user wants (AGENTS §1; DECISIONS 2026-10-22). Add fx kinds :fs (read, glob, stat, write with digest precondition), :edit (exact text and line windows; form edits if rewrite-clj runs on Jolt), :shell (argv, cwd, env allowlist, stdin cap, bounded by bytes/ticks not a clock), with my.fs, my.edit, my.shell as wrappers over my.fx/call. Policy from v1 handlers as dials: :seon.config.fs/roots, no symlink following, max-read-bytes, max-write-bytes (v1 src/seon/fs/jvm.clj:21-127), :seon.config.shell/env-allowlist (v1 shell/jvm.clj:73). Each verb gets a section in docs/agent.md; help says Seon's own code still changes at the REPL. Delete fx/write-file's unconfined path (fx.clj:33-50) into :fs write."
 :proofs #{seon.fx-test/a-write-needs-the-prior-digest seon.fx-test/a-path-outside-the-roots-refuses
           seon.fx-test/shell-answers-exit-and-digested-output seon.fx-test/shell-sees-only-allowed-env}
 :needs #{"seon.fx/an-effect-opens-a-receipt"}}
;; size: +250 (vs v1 ~2,900 for the same), 1.5 days

#:seon.task{:name "web-fetch-and-search"
 :plan "Add :web-fetch (http/https only, no userinfo, manual redirects with loop detection, body cap; v1 web/jvm.clj:42,156,297-334) and :web-search (key held by root like the model key, v1 :seon.config.web/search-api-key-variable) kinds with my.web."
 :proofs #{seon.fx-test/redirects-are-bounded seon.fx-test/search-key-stays-in-root}
 :needs #{"seon.fx/effect-kinds-declare-who-may-ask"}}
;; size: +120, 1 day

;; seon.web.edn
#:seon.task{:name "call-checks-the-origin"
 :plan "/call evaluates forms in agents with no Origin or Sec-Fetch-Site check (web.clj:84-104); any page in the owner's browser can POST it. v1 refused cross-origin (::same-origin). Refuse when Origin/Sec-Fetch-Site is not the dashboard's own origin; a DRAGONS row names it."
 :proofs #{seon.web-test/a-cross-origin-call-is-refused}
 :needs #{}}
;; size: +10, 1h
```

### Phase 6: the owner's view, the door, messages

```clojure
;; seon.entrance.edn
#:seon.task{:name "health-through-the-door"
 :plan "No runtime_status equivalent (parity.md:22); seon_agents dumps 230+ tasks (parity.md:26); unknown JSON-RPC methods answer {} (mcp.clj:57-68). Add seon_status = {:op :status} -> seon.render.ai/system; seon_agents lists live agents and running or stuck tasks with seon.print/more; -32601 for unknown methods. Then rebase jolt/door's per-request defects and 9 deftests."
 :proofs #{seon.entrance-test/status-is-roots-ai-render seon.entrance-test/agents-lists-live-work
           seon.mcp-test/an-unknown-method-is-an-error}
 :needs #{}}
;; size: +40, 4h (+ door rebase 1 day)

;; seon.agent.edn
#:seon.task{:name "messages-are-facts"
 :plan "Messages live in process memory and vanish with the agent (agent.clj:66-74). v1: facts with received-tx custody, a message wakes a turn (v1 src/my/message.clj). The api's :message handler writes :seon.message/{id,from,to,body,at} on the recipient's branch; my.agent/messages reads unreceived; my.agent/received! writes received-tx; an unread message on an idle agent is a wake the reconciler sees. The owner's bar /agent/{id}/message posts one."
 :proofs #{seon.agent-test/a-message-survives-the-agents-death seon.web-test/the-bar-posts-a-message-fact}
 :needs #{"seon.web/call-checks-the-origin"}}
;; size: +60/-15, 6h

;; seon.web.edn
#:seon.task{:name "the-next-call-page"
 :plan "v1's /agent/{id}/debug 'Next call: what it would be sent now' (the page this audit used to read a real context). Render seon.context/render from a fresh basis at /agent/{id}/next with per-part token counts; add /ns/{ns} over ref/namespace-view and docs/view."
 :proofs #{seon.web-test/next-call-shows-the-context-the-model-would-get}
 :needs #{"seon.context/attempt-sections-are-the-cached-prefix"}}
;; size: +60, 4h
```

### Phase 7: errors, learning and the model path

```clojure
;; seon.error.edn
#:seon.task{:name "errors-go-to-who-can-fix-them"
 :plan "v1 live: an agent's own contract mistakes became cluster open faults attributed to seon.profile/bracket (audit §0.6). Classify at the producer: an agent's refused call is the agent's (reply + waste row), a program defect is its function owner's problem, an edge failure is its DRAGONS class. Hash the cause value, not pr-str (error.clj:54). root.clj:696,711,718 answer {} on timeout: record :stalled instead."
 :proofs #{seon.error-test/an-agents-call-mistake-is-not-a-problem seon.error-test/signature-hashes-the-value}
 :needs #{}}
;; size: ~0 net, 4h

;; seon.learn.edn
#:seon.task{:name "turns-to-green-report"
 :plan "The owner's key metric (turns to a good solution, from all runs). Facts exist (:seon.attempt/turns, tokens, waste; cache-hit tokens model.clj:158); no report. seon.learn/report: median/p90 turns to green per template, per main commit, per model; tokens per green; cache share; top waste rules; on the dashboard and root's AI context. v1: src/seon/mine.clj:544-729."
 :proofs #{seon.learn-test/report-quantiles-match-recorded-attempts}
 :needs #{"seon.agent/the-agent-knows-its-task"}}
;; size: +50, 4h

;; seon.model.edn
#:seon.task{:name "a-rate-limit-waits"
 :plan "With :seon.config.ai/rate 0 a 429 is re-queued and re-sent at once (fx.clj:217-233,268-282); retry-after-ms is parsed and unread (model.clj:197-219); transport-error's docstring promises a retry that does not exist (:36-41). Re-queue through one delayed insert of max(retry-after, dial) on a :retry in-port (docs/flow.md:77) for :rate-limited and :transport."
 :proofs #{seon.fx-test/a-429-is-not-resent-before-its-delay seon.fx-test/a-transport-failure-is-retried-once-delayed}
 :needs #{}}
;; size: +25, 3h
```

### Phase 8: proof of the whole loop (RESUME E)

```clojure
;; seon.e2e.edn
#:seon.task{:name "scripted-design-implement-merge"
 :plan "v1 never merged a full design -> implement -> merge live (run 13: Implement 0/2). Script one small request end to end through the door (seon_attach/seon_eval/seon_done as each step's agent, no model): design writes plan, stubs and red proofs into implement's task (edit!), implement turns them green, the merge lands and writes back, bin/root next runs the result. Then DeepSeek on R4's eight tasks, measured by turns-to-green-report (owner's go for paid runs)."
 :proofs #{seon.e2e-test/a-request-merges-through-design-and-implement}
 :needs #{"seon.task/templates-are-task-data" "seon.task/edit-any-task" "seon.reconcile/an-unrun-proof-is-unknown"
          "seon.context/attempt-sections-are-the-cached-prefix" "seon.docs/help-is-a-wiki-page"
          "seon.merge/the-kept-side-is-recorded" "seon.learn/turns-to-green-report"}}
;; size: +100 test, 1 day
```

**Later**, once the above are measured: embeddings (`:embed` kind plus RRF into `:found`, §2.12); workload admission (§2.13); cron proc with its first job; `my.agent/search`, `sizes`, `breaks`, `diff`, `unmap!` (§2.1, +120); error → task filing (§2.10); A2 (store as the source of truth).

**Totals.** About 30 tasks. Net `src`: about +900 added against about −400 deleted. That holds `src` near 17.3k while adding user work, which is still above the ~10k aim. The deletions already identified (templates to data, three program maps to one diff, flusher, `attach-tx` chain building, `system-prompt` literal, `write-file`) are the start; each lane names its own.

---

## 5. Open questions for the owner (one decision each)

1. **Erase the standing store after `jolt/blobs` lands?** History is fixed when a store is created, so the kept store can never gain history.
   - (a) erase and re-seed, as DECISIONS already says;
   - (b) keep it read-only as evidence beside a fresh store.

   *Recommend (a), confirmed at the moment of deletion.*
2. **Retry policy (D2): what happens after a failure or a `send-back!`?**
   - (a) held for root with the reason and counterexample as data;
   - (b) an automatic refinement chain up to a budget (today's code).

   *Recommend (a)* (v1's "No automatic re-runs"; next's own LEARNING), then (b) as an opt-in rule after the dry run shows real failures.
3. **What a merge carries, and who wins a conflict.**
   - (a) the whole branch, with main winning on a merge branch built from main's head (v1 `ac12586a5` plus MERGE-STEP 5a);
   - (b) an explicit `:seon.task/carry` list with branch-only callees reported missing (DESIGN-tasks §3, "no winner rules").

   *Recommend (a) for the dry run* (it is v1's proven rule and needs only `:seon.merge/kept`); revisit (b) when partial merges are needed.
4. **Changed-since.**
   - (a) a Datahike fork function diffing two trees by node address (port v1's fork `771a0c4b`; no stamp);
   - (b) a `:seon.tx/changed` stamp in tx-meta.

   *Recommend (a)* ("No stamps — Datahike already does it", 2026-09-23); prototype first, as RESUME says.
5. **Where the agent's orientation lives.**
   - (a) `docs/agent.md` in the wiki, loaded by `seon.docs`, with every form executed by a test;
   - (b) a resource file like v1's `help.md`.

   *Recommend (a)*: one home, one parser, the existing check.
6. **`:found` source for the first context v2.**
   - (a) call graph plus plan references only, now;
   - (b) wait for embeddings and land RRF fusion at once.

   *Recommend (a)*, then A/B (b) against it (EM1: fusion beats graph by 0.055 R@10).
7. **File and shell confinement.**
   - (a) policy roots as a dial (default: the task's repository plus a scratch directory), as v1;
   - (b) no roots (the owner's "not trying to do true isolation anywhere").

   *Recommend (a) with a permissive default*: roots record what a task may touch and catch mistakes, not attackers.
8. **Paid A/B of context v2 on R4's eight tasks** (about $0.20, measured R4 cost): go or no go? *Recommend go* after phases 1–3 land, since it is the strongest lever measured.
9. **Embedding model download** (EmbeddingGemma on 8701, v1's): allow it on this machine? *Recommend yes, after decision 8's result.*
10. **`jolt/door` (299 behind):** rebase and land its per-request defects and `seon_turn`, or re-derive the parts §4 phase 6 needs and retire it? *Recommend re-derive*: the branch is WIP stopped by root, and main has since built its own `bin/root` lifecycle.

---

*Live v1 session: started 14:48, stopped 15:00 (2026-10-22); PIDs 36592 (JVM) and 36939 (embed) confirmed gone; `git -C ~/src/seon status` clean. v1's writes went to its own `data/` (the default store: chain `:chain-3b7b14217c69`, outside agent branch `:agent-e8d9c042ce8f`, eval records, error occurrences). Nothing under `~/src/seon-jolt/next` was touched.*
