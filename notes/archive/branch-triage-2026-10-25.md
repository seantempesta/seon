# Triage of the old unmerged branches, 2026-10-25

Owner, 2026-10-25: "triage and move forward the good ideas from the unmerged branches prioritize stability performance and the agent getting shit done well". Twenty-one jolt/* branches with commits not on main (as of main fe07905c0), read by four subagents against main's tree (`git log main..B`, `git cherry`, `git diff main...B`, a grep of main for each idea); root's session checked the land merges' parents and agent-conversation-base's proofs by hand. Branches with commits today that a live session owns (agent-loop, ctx-atom, ctx-lab, fork-guard, form-keywords, grep-ranks-definitions-first, stability, ui-values-design) are not triaged here. No branch is deleted; **Delete?** says which the owner may delete.

## The table

| branch | commits | verdict | what is still worth carrying | value | delete? |
|---|---|---|---|---|---|
| git-is-read-through-fx | 4 | CARRY | one git edge (`git-try`), a typed error for a branch with no commit (run-tests runs at an empty sha today), a law that keeps git behind it | stability | after carry |
| a-process-root-opens-the-shared-store-under-load | 2 | CARRY | `:root-did-not-listen` with the dead root's log, the 4-roots-at-once proof; the LMDB `env_open: Invalid argument` cause is still unfound (stability lead's) | stability | after carry |
| durable-read-runs-alone | 1 | CARRY | `bin/check durable` runnable alone, its clear message, the `attrs-that-differ` report | stability | after carry |
| unitflake | 5 | CARRY | `:db/pull-attr` measured on one db (cost_test builds two), tolerances from measured unit ranges instead of constants (redo for ticks) | stability (no flaky cost proofs) | after carry |
| agent-conversation-base | 1 | CARRY | five red proofs with no fixes: a proof with no result reads `:unknown` (not `:no-assertions`), a ref summary says there is more, a long name cuts in chars and names its form, a map shows its first entry when it fits, the transport-failure docstring | agents | after carry |
| evaluations | 15 | CARRY one | the `:cache-miss` problem (a turn records the prompt tokens it shares with the last request; short of that, a problem). New attribute + problem class: root's gate | performance (cost) | after carry |
| one-content-keyed-derivation | 14 | CARRY, later | `seon.derive` memo key with its refusals and eviction, `seon.error/sha256`, design rev 3; consumes main's reach digests, touches no program.clj. Sequence behind the ctx/render work | performance | after carry |
| examples (= eval-record-keeps-a-failing-form-whole) | 8 | CARRY, low | the second-move judge as measurement, plan runs, a generated `:repl-calls` piece | agents | after carry |
| acts-by-second-move | 18 | SUPERSEDED mostly | the same `:repl-calls` and second-move probe as examples (one carry for both) | agents | with examples |
| wiki | 13 | SUPERSEDED mostly | docs/system.md (a section per root proc) and the missing docs lints (two homes, a long lead, file:line in a lead, code in a heading, an isolated page); both already main tasks (`seon.docs/every-concept-has-its-page`, `seon.docs/the-wiki-stays-true-and-healthy`) | agents (context) | yes: its ideas live in those tasks |
| flow-lab | 2 | SUPERSEDED as code | its notes (six hazards, lifecycle and capacity numbers, resume cost table) go to the agent-loop work | performance | after the notes move |
| reach-scc | 4 | SUPERSEDED | components, per-component digests and reach-update are on main (program.clj); only a transient `closure` micro-optimization is left (optional, stability lead's) | — | yes |
| nested-tick-budget | 1 | SUPERSEDED | on main (agent_test, docs/forks.md); merging it would move the Jolt pin backwards | — | yes |
| agent-view-prototype | 4 | SUPERSEDED | its two leftovers (agent thread count, /ns and /data pages) are main tasks | — | yes |
| dry-run-merges | 2 | SUPERSEDED | only `seon.context/ns-map`, worth it only if runs show designs reading sources one at a time | — | yes |
| self-contained | 8 | DROP | a one-time cutover, already run; main holds the as-run script | — | yes |
| agent-conversation-debug-wip | 1 | DROP | on main as `/agent/{id}/debug` (seon.render.proc/debug-value) | — | yes |
| land2 | 1 | SUPERSEDED | a merge whose parents are both on main | — | yes |
| land11 | 1 | SUPERSEDED | a merge whose parents are both on main | — | yes |
| land13 | 2 | SUPERSEDED | merges of work on main (held-for-root, agent-ready) | — | yes |

## The order they are carried in

Stability first, then performance, then agents finishing work; each on a fresh branch from main, reimplemented against today's code with its proofs red → green, reported to root one by one:
1. **git-is-read-through-fx**: small; merges cleanly in a merge-tree, so carried by reapplying its commits on a fresh branch and proving.
2. **durable-read-runs-alone**: small.
3. **agent-conversation-base**: five small fixes, each with its red proof already written; the unknown proof first.
4. **unitflake**: the one-db measurement, then the measured tolerances.
5. **a-process-root-opens-the-shared-store-under-load**: with the stability lead, whose LMDB cause it diagnoses.
6. **evaluations' :cache-miss**: after root's gate (a new attribute and problem class).
7. **one-content-keyed-derivation**: after the ctx/render work merges, coordinated with the stability lead's reach work.
8. **examples' :repl-calls and second move**: lowest; the owner's path is dogfooding with live agents.

Also found: `seon.template/instructions`' docstring points at seon.judge stage stories that are not on main (a dead pointer); main's task `eval-record-keeps-a-failing-form-whole` has no proof test.

## Carried so far

| carry | branch | state |
|---|---|---|
| git-is-read-through-fx | jolt/git-one-edge | merged (root, 2026-10-25) |
| agent-conversation-base's five fixes | jolt/fit-fixes | ready, PROVE OK (532 tests); its merge held by root until live-agents' benchmark rounds end: those five tasks are the benchmark Seon agents' turns to green are measured on |
| durable-read-runs-alone | jolt/durable-alone | committed, self-reviewed; its two check runs wait for load |
| unitflake, piece 1 | jolt/unit-one-db | committed: the one-db :db/pull-attr row (10,377 B, 2,847 ticks); today's two-db measurement was already deterministic, so this is correctness, not a flake fix |
| evaluations' :cache-miss | jolt/cache-miss | root's gate passed; committed with its proof; measured on live-agents' 100 turns: 75.6% of prompt tokens cached, every hit 0.87-1.12 of the shared prefix, DeepSeek's block 128 |

When live-agents' rounds end: the comparison of a Claude fix (jolt/fit-fixes) with each Seon agent's diff on the same five tasks goes here.

## Per branch, as read



Note on diffs: `git diff main...BRANCH` warns "multiple merge bases" for evaluations and agent-conversation-base, so their three-dot stats are inflated by main's own later work. Judgements below use the branch's own commits and a grep of main.

## jolt/evaluations (15 commits, last 2026-10-23, cdf12058d)
- **Ideas:**
  1. Context = evaluations, each rendered from its record (`:seon.render/ai seon.render.ai/evaluation` on `:seon.cluster.eval/record`; turn 0's (help)/(walk) and each turn's (now) evaluated and recorded, basis names their ids). ON MAIN in another form: jolt/agent-conversation (1ea5bc781) records a turn's `:seon.turn/sent` refs and builds a function-calling conversation of evals (`seon.context/eval-messages` src/seon/context.clj:349, `request` :533, `records` :490); task seon.context "context is a sequence of real evaluations" (resources/seon/tasks/seon.context.edn:83). The (help)/(walk)/(now) basis shape is NOT on main and is deliberately gone (main has `seon.context/help-text` :18, `system-page` :24, `render/walk` src/seon/render.clj:263).
  2. seon_context takes a task (abf3a38fa). ON MAIN: `:context` op doc "Name the agent, or a task (ns/name)" src/seon/server.clj:133.
  3. Walk framed (task, what to change, what to do now, done, cited sources first) so the printer budget never cuts the point (446a088f8). ON MAIN in other form: stage instructions in `seon.template/instructions` (src/seon/template.clj:34), `docs/prompt.md`, task story = plan + write set + how to run each proof (918ff641a).
  4. `:cache-miss` problem: a turn records `:seon.turn/prefix-tokens` (prompt tokens shared with the turn before, `seon.turn/shared-prefix-tokens`, printer estimate taken twice so an estimate error never reads as a miss); `seon.problems/cache-misses` raises a problem when cache-hit falls short of it by more than `:seon.config.ai/cache-block-tokens`; open only while it is the run's newest turn; proof `seon.turn-test/a-turn-whose-cache-misses-its-prefix-is-a-problem`; wiki `docs/render.md#a-short-cache-hit-is-a-problem`. NOT ON MAIN: grep for `prefix-tokens`, `cache-misses`, `cache-block-tokens`, `shared-prefix` over main src/test/docs/resources finds nothing. Main only records hit/miss tokens (src/seon/turn.clj:123-133) and has a structural test (`seon.context-test/the-request-is-a-conversation-of-evals-then-the-screen`, cited docs/render.md:89), no runtime detector.
  5. First-move judge sends a real first request (`seon.turn/first-request`, `render {:costs false}`, keyed by request less measured costs). NOT ON MAIN (`first-request` greps empty); main's `seon.judge/request` (src/seon/judge.clj:13) still renders with a faked `:observed {:results {} :reds []}`. Depends on the old context shape, so a reimplementation is a different change.
  6. Context cost proof, context tests (839-line rewrite of context_test), version 1-4 Haiku-reader scores. SUPERSEDED: tests were for the old (help)/(walk)/(now) shape; main has its own `context_test`, `render_cost_test` (288).
  7. Judge results recorded in plan/wiki: every context version through v4 scored 0 of 10 acted, each first reply a read (docs/render.md on the branch, line ~102). Measurement worth keeping (see below); main's docs/decisions.md:95 holds the later standard-format result.
- **Worth carrying:**
  1. [agents][stability] `:cache-miss` problem (idea 4). It is a runtime check that the append-only prefix really hits the provider's cache, the thing the conversation design depends on (65-79% cached prefix claimed in docs/render.md:89; one measured run cost anywhere from $0.01 to $0.58 depending on cache, seon.model task plan). Keep: the proof name, the "estimate taken twice never reads as a miss" rule, the "open only while newest turn of its run" rule. Better reimplementation: record the prefix from the actual previous request's message list (the conversation is now literally a prefix: compare `seon.context/request` messages of turn n-1 with turn n by count and digest) instead of the printer estimate, which removes the 2x slack.
  2. [agents] Haiku-reader noise-share scores (v1 40/50/20-25%, v2 33/35-40/35%, v3 50/40-45/30%, old block 45/40/28%) and "0 of 10 acted" for every first-move version: evidence that data-heavy contexts did not change first moves. Already reflected in decisions.md:95; no code to carry.
- **Verdict:** CARRY one piece: the `:cache-miss` problem. Everything else SUPERSEDED by jolt/agent-conversation on main (context, task-keyed seon_context, framing) or by clean-path (short prompt, stage stories).
- **Effort:** small-medium for cache-miss: src/seon/turn.clj (record prefix or digest), src/seon/problems.clj (`cache-misses`, report/health class), resources/seon/schemas/seon.facts.edn + seon.problems.edn (1 attribute, 1 class), config key `:seon.config.ai/cache-block-tokens` in default.edn + schema, docs/render.md section, test in turn_test. Adds an attribute and a class, so it needs root's four-question gate (what replaces: nothing; why the existing hit/miss tokens cannot do it: they are recorded but nothing raises a problem on them).

## jolt/examples (8 commits, last 2026-10-23, 01ea57433) = same commit as jolt/eval-record-keeps-a-failing-form-whole
Triaged once. The branch name `eval-record-keeps-a-failing-form-whole` is only a label: its commits are the judge's second move, standard format, design runs and `:repl-calls` piece, not that task.
- **Ideas:**
  1. Second move: `my.root/first-moves!` `:moves 2`, `seon.turn/prepare-second`/`ask-second`/`second-move` (turn 1 made as the loop makes it in a disposable agent on a fresh branch from the held first reply, keyed by digest of the request it would send). NOT ON MAIN (greps for `prepare-second`, `second-move`, `ask-second` empty; only `docs/decisions.md:93` mentions "acts-by-second-move" as owed). Main's `first-moves!` (src/my/root.clj:307) scores the first reply only.
  2. Score per stage (`:on-task :plan :stage`, `:design` green = on-task with no waste or plan written) and `my.root/rescore!` (re-score held replies with no model call). NOT ON MAIN (`rescore!` empty).
  3. Standard format (`seon.judge/standard-request`, `standard-turn`, `standard-messages`, stage `stories`): short system prompt, task as user message, one eval tool answered by the REPL. ON MAIN in another form, as the real agent format not a judge-only variant: docs/prompt.md ("You are a Seon agent", 405 tokens), `seon.template/instructions` (src/seon/template.clj:34, docstring still says "the standard format's stage stories (seon.judge's)" which is now a dead pointer), clean-path merge 76d9e6421 (3,329 -> 405 tokens a request; a 7-turn run 45.6k -> 25.1k prompt tokens). Owner ruling docs/decisions.md:95.
  4. `my.root/plan-runs!` + `seon.judge/run-to-plan` + `plan-end`: run each task's design stage to its plan in a disposable agent on a fresh branch, at most N calls, score "plan rewritten, references resolve, a proof named" as `:seon.judge/run-end`. NOT ON MAIN (greps empty). Owner's ruling (decisions.md:97) that design is the first stage and is judged by its plan is on main as prose only.
  5. `repl-shape` (replies as forms, REPL answers as user messages closing on the proofs' status). SUPERSEDED: main's agent-conversation is a function-calling conversation; the judge measured it and the standard (tool) format won (decisions.md:95).
  6. `:repl-calls` data piece (six calls with arglists and doc's first sentence read from the var so it never goes stale) and `pieces` hook. NOT ON MAIN as generated text. Main's agent.md:28/60 and context.clj:255 list `my.agent/callers`, `similar`, `related`, `grep` by hand, so they can go stale.
  7. judge_test.clj additions (second move linked to its first, design run call limit, request keyed by what it sends, pass stops at bound, plan-run recorded once). NOT ON MAIN; they test items 1,2,4.
  8. Measurements in commit messages: review of second move "10 findings: 8 fixed, 2 refuted"; "turn 1 is made ready first and keyed by digest of the request it would send so a changed context is never answered from an old move". Standard format result (decisions.md:95): same moves as today's 17 KB format at a third less cost and a fifth of the waste.
- **Worth carrying:**
  1. [agents] Plan-run (design to plan) scoring (4): the only measurement of "turns to green" ingredients that is model-run but cheap (N calls, no merge), and the owner's stated first-stage measure. Goal text "agent getting shit done well". Needs the server-side judge tooling to be kept honest on today's conversation format (it builds its own messages, so rewrite it to call `seon.context/request` for the run and score `plan-end`).
  2. [agents] Second move (1): first-move scoring alone says 0 of 10 acted, a read, so it cannot rank formats; the second move is what separates them. Keep the key rule (digest of the request actually sent) and the fresh-branch-per-move rule (a reused branch holds an earlier pass's plan).
  3. [agents][stability] Generated `:repl-calls` text (6): small, removes hand-written call lists that go stale; fits system-page/help.
  4. [stability] Fix the dead pointer in template.clj docstring ("seon.judge's") that points to stories which do not exist on main.
  Drop: repl-shape, pieces hook, rescore! (judge-internal, tied to a format main no longer uses).
- **Verdict:** CARRY (partly): plan-runs and the second move as measurement tooling; `:repl-calls`. The standard-format idea itself is SUPERSEDED (on main as docs/prompt.md and template instructions).
- **Effort:** plan-runs/second move: medium-large (src/seon/judge.clj, src/my/root.clj ~300 lines, src/seon/turn.clj ~100, seon.facts.edn run-end attribute, judge_test). On today's main it should reuse `seon.turn/work!`/`act!` and the conversation request rather than a parallel `standard-messages`, so it will be smaller (`first-moves!` already reuses held facts/digest). `:repl-calls`: small (src/seon/context.clj help/system page).

## jolt/agent-conversation-base (1 commit, 2026-10-25, f0ab10b39)
It is a merge commit of jolt/fit-tasks into the base. Content: five tasks with their precise plans and write sets, each with its proof written and RED on this branch (kept off main on purpose, 6cff75578). On main the same five task names exist (resources/seon/tasks/*.edn) with shorter plans and proof names that have no test.
- **Ideas (all five NOT ON MAIN as fixes):**
  1. `an-unrun-proof-is-unknown-in-the-context`: a proof with no result reads `:unknown`, not `:no-assertions`. `git grep no-assertions main` still hits `src/seon/context.clj:309` (`:else :no-assertions`) and docs/agent.md:34; test `a-proof-with-no-result-reads-unknown` is absent from main tests. Audit evidence: 263 of 265 contexts showed every proof `:no-assertions` while none had run; agents then loosened a contract.
  2. `a-summary-says-there-is-more`: summary view carries `:seon.ref/left` and `:seon.ref/read-at`. `:seon.ref/read-at` greps empty on main.
  3. `a-long-name-is-cut-like-any-text`: a keyword/symbol cut says `…+N chars` and names the form that reads the rest. Test `a-long-name-is-cut-in-chars-and-names-its-form` absent on main.
  4. `a-first-entry-that-fits-is-shown`: `seon.print.select/fill-map` refuses (insufficient-floor-budget error) where a vector shows its first member. Test `a-map-shows-its-first-entry-whenever-its-least-form-fits` absent on main (only a fixtures.clj:230 task stub).
  5. `a-transport-failure-says-what-happens`: `seon.model/transport-error` docstring says "kept and retried after a pause" but the owner answers the agent once and does not retry. `src/seon/model.clj:34` still says it; so does a schema comment (resources/seon/schemas/seon.problems.edn:74) and docs/failure-points.md:22 text. Test `a-transport-failure-is-answered-to-the-agent` absent on main.
- **Worth carrying (all five, ranked):**
  1. [agents] Unknown proof status (1): a false statement in the model's own screen ("ran and asserted nothing") is the highest-leverage lie in the context; one-line `cond` default plus docs/agent.md sentence. Red tests ready: `a-proof-with-no-result-reads-unknown`, and `a-failure-reads-as-assert-and-got` edited from :no-assertions to :unknown.
  2. [stability] Map first entry (4): `print-value` returning an error map where a vector prints is a defect in the one printer, which every render and context uses. Red test ready, repro `(print-value (zipmap (range 30) (range 30)) {:budget 10 :handle 'result/h1})`.
  3. [agents] Summary says there is more (2) and long-name cut (3): AGENTS section 9 ("every cut says what it left and how to read the rest"); printer-eval blind scoring ranked bare `…+N` last most often (notes/printer-eval/README.md).
  4. [stability] Transport docstring (5): docstring-only truth fix; near-zero cost.
  Proofs and repros to keep are in the branch's test/seon/{context,model,print,ref}_test.clj additions (59 lines) and the rewritten plans (current var names and write sets), which are better than main's task entries.
- **Verdict:** CARRY all five (small, independent, proofs already written and red). Land the red tests with each fix.
- **Effort:** small each; about 6-30 lines of src in src/seon/context.clj, src/seon/ref.clj + :seon.ref/view schema, src/seon/print/select.clj, src/seon/model.clj + docs. Cherry-pick the branch's test and task-file hunks (git show f0ab10b39 shows the final state; the content is on its first-parent history in jolt/fit-tasks).

## jolt/agent-conversation-debug-wip (1 commit, 2026-10-24, f71d23307)
- **Ideas:** debug page rebuilt each request from the turn basis (`seon.context/remade`) in the render proc, shown with `seon.render.ui/request` html render of `:seon.model/request` (messages whole, system folded, screen set apart, token and cache %).
- **Status:** ON MAIN in the intended form: `/agent/{id}/debug` from recorded refs (`seon.render.proc/debug-value`, docs/parity.md:83, docs/render.md:143, link at src/seon/render/ui.clj:399, web.clj:220, schema comment resources/seon/schemas/seon.render.edn:95), token split read back from what was sent (`seon.context/sent-split` :629; merges 76d9e6421, 502558397 "a context read records what it sent"). `seon.context/remade` is gone from main by design (design r3 step 3: record what was sent, do not rebuild it); the commit message itself says the approach was discarded.
- **Worth carrying:** nothing: superseded by the merged debug page. One idea to check once against main's page, not carry: the request render's "screen set apart" box and the "prompt tokens cached (N%)" line (cheap, from `:seon.turn/cache-hit-tokens`/`miss-tokens`); main's row shows a token split already.
- **Verdict:** DROP (SUPERSEDED).
- **Effort:** none.



## jolt/wiki (12 commits unique by patch, 13 listed; last commit 2026-10-21)
- **Ideas:**
  - Wiki style rules (plain headings, standalone lead <=40 words, one home per concept, current truth, map groups): ON MAIN in docs/README.md "How the wiki is kept" (main's own task seon.docs/every-concept-has-its-page says "main took its rules (README)").
  - Patterns embedded in topic sections, not a catch-all patterns.md; backlinks as a pattern's instances: ON MAIN (README rule 1; test/seon/docs_test.clj `backlinks-are-a-sections-instances`; the `:pattern-of` experiment was reverted inside the branch itself).
  - Every section has a lead: ON MAIN (test/seon/docs_test.clj:111 `every-section-of-the-wiki-has-a-lead`, `:leadless-section` problem in src/seon/problems.clj).
  - Orphan pages flagged: ON MAIN (`orphan-pages`, src/seon/problems.clj:295). Stale section when cited code changed: ON MAIN (problems.clj ~238-285, seon.source/defining-meanings).
  - docs/vocabulary.md links each word to its section: PARTLY (vocabulary.md exists with 15 links; branch version is 173 lines rewritten, uses old words).
  - Tasks link the wiki (`:seon.task/docs`, `:seon.task/refs` as data on 78 tasks): NOT ON MAIN by ruling (main's task text: "Not applied: Describes lines, :one/:owner/:migrate, :seon.task/docs, :seon.task/refs, :pattern-of"); main derives the same links from citations (seon.ref/parse, seon.context :sections). Do not carry.
  - `Describes:` lines per section naming vars/files: NOT ON MAIN, deliberately rejected (replaced by code-depth citations).
  - docs/system.md, one section per root proc from resources/seon/system.edn: NOT ON MAIN (`git show main:docs/system.md` fails; docs/root.md has only 5 sections "The root graph/Boot/Runs and judging/..."). Open task seon.docs/every-concept-has-its-page names it. Branch text is from 10-21 and "partly untrue on main" (main's own words).
  - docs/check.md (bin/check as it runs, the gate for catches): NOT ON MAIN as a page (docs/checks.md exists, different); also open in the same task.
  - Pages agents/merge/store/program/printer/context/door/worktrees/config/cost: ON MAIN under other names (agent.md, merging.md, storage.md, source.md, render.md, server.md, machine.md, config.md, cost.md). Branch versions are stale and use old words (door, lane, dragon).
  - docs/DRAGONS.md with Describes edges: SUPERSEDED by docs/failure-points.md (same table, renamed per AGENTS 7a).
  - Docs lints: two-homes (two sections citing one var at code depth), lead-echoes-heading, lead-too-long (>40 words), lead-cites (file:line in lead), heading-has-code, isolated-page, changelog (dated heading): NOT ON MAIN (grep of src/test for each finds nothing). Task seon.docs/the-wiki-stays-true-and-healthy lists them with the baseline counts (two-homes 20, no-lead 32, lead-cites 18, lead-too-long 5, heading-has-code 14, orphan 13, isolated-page 3) and a 40-word test `every-section-leads-with-a-sentence-of-at-most-40-words` as a proof, not yet written (docs_test.clj has no such deftest).
  - `bin/check docs` (red on every unresolved citation): NOT ON MAIN (grep "docs" in src/seon/check.clj finds no step; notes/root/wiki-audit-2026-10-23.md R1 describes it; parity.md:110 says "no check fails on a dangling citation"). Not from this branch, but the same family.
- **Worth carrying:**
  1. [agents] docs/system.md (one section per proc in resources/seon/system.edn, each with a standalone lead): an agent working on root has only 5 coarse sections in root.md to pull. Rewrite from main's code, not the branch's text (stale). Keep branch's section skeleton: "The declaration", "Boot and stop", effects/owners/lanes.
  2. [agents][stability] Wiki lints the branch measured: two-homes, lead-too-long, lead-cites, heading-has-code, isolated-page, changelog. Keep the baseline counts above as expected-red starting points and the three fixture deftests names (a-var-with-two-homes-is-duplication-and-names-the-home, a-lead-that-is-missing-echoes-its-heading-runs-long-or-cites-is-flagged, a-heading-with-code-an-orphan-an-isolated-page-and-a-changelog-are-flagged, from jolt/patterns 2da2255). Value: keeps context pulls clean (one home per concept means an agent pulling a var gets one authoritative section).
  3. [stability] `bin/check docs` for unresolved citations (shared task, not from this branch's diff).
  4. [agents] docs/check.md page on bin/check: low priority, docs/checks.md already covers it.
  Measurements: the branch has none worth keeping beyond the lint baselines.
- **Verdict:** mostly SUPERSEDED; CARRY only docs/system.md and the missing lints, and both are already filed as main tasks (seon.docs/every-concept-has-its-page, seon.docs/the-wiki-stays-true-and-healthy). Branch prose itself: DROP (old words, 4 days stale, main explicitly rejected its declared keys).
- **Effort:** system.md: small-medium (one page, ~8 proc sections, verify against src/seon/root.clj and system.edn; touches docs/system.md, docs/README.md, docs/root.md links). Lints: medium (src/seon/problems.clj, test/seon/docs_test.clj, docs/health.md; each needs a cost proof). Wiki-branch text can be read via `git show jolt/wiki:docs/system.md` as a skeleton only.

## jolt/land2 (1 commit, 2026-10-22)
- **Ideas:** The only unique commit is a merge of jolt/dup-views into jolt/land2 (074fbbd30). Both parents (c87084ab8, b3afb265d) are ancestors of main. Its one resolution: problems.clj keeps `:task-conflict`/`:failed` rows plus the schema-declared death set named `death-statuses`: ON MAIN (src/seon/problems.clj:42 `(def ^:private death-statuses task/failed)`, used at :539, :541). A subsequent merge land3 (42886c25a) reproduced the same resolution on main's history.
- **Worth carrying:** nothing: superseded by main's problems.clj and land3.
- **Verdict:** SUPERSEDED.
- **Effort:** none; branch can be deleted.

## jolt/land11 (1 commit, 2026-10-23)
- **Ideas:** One merge commit (321eea812, "Merge commit 'd683692'"). Both parents (52572742f, d6836920b) are ancestors of main. The big 2-dot diff against main (624 files) is only main having moved on. No unique content.
- **Worth carrying:** nothing: superseded (its content, the reconcile/root/turn/writer refactor of that day, is in main's history via parents).
- **Verdict:** SUPERSEDED.
- **Effort:** none.

## jolt/land13 (2 commits, 2026-10-23)
- **Ideas:** Two merge commits: d46e20d72 (merge jolt/agent-ready) and 7bb30a181 (merge jolt/hold-for-root). Every parent except 7bb30a181 itself is on main (bb012f894, b39cef589, d300b8d60 yes). 7bb30a181 is just the intermediate merge of two main-reachable parents; its diff vs each parent is the other side's changes, already on main. Ideas in them: "a refused/failed run is held for root": ON MAIN (docs/agent.md:70, docs/tasks.md#a-refused-run-is-held-for-root, docs/decisions.md:138 "No automatic retries", docs/checks.md `held` scenario); agent-ready: ON MAIN via later jolt/agent-ready2 (visible in main log "agent-ready2 review: a spawn answered once...").
- **Worth carrying:** nothing: superseded.
- **Verdict:** SUPERSEDED.
- **Effort:** none.



## jolt/acts-by-second-move (18 commits, last 2026-10-24)
Diff vs main: judge.clj +294, root.clj +308, turn.clj +103, judge_test +195, branch.clj/task.clj/proofs.clj small. 887 insertions.
- **Ideas:**
  - Second-move judge (`my.root/first-moves!` with `{:moves 2}`; `seon.turn/prepare-second`, `ask-second`, `second-move`; keyed by request digest + move): NOT ON MAIN (grep `second-move`, `prepare-second` finds nothing in src/test). Main has only the one-call first-move judge (`my.root/first-moves!` src/my/root.clj:307, `seon.judge/first-move` src/seon/judge.clj:94).
  - Standard chat+tool-call format beside ours (`seon.judge/standard-request`, `standard-turn`, `repl-shape`): ON MAIN in another form. Main's context is the standard format now: `seon.model/eval-tool` ("One tool, eval", docs/model.md), short standard system prompt (src/seon/context.clj:26, src/seon/template.clj:35, commit 775d31610 "the clean path's prep: the standard short system prompt, short prose-first stage instructions"). The A/B harness itself is not on main.
  - Live state ends each turn (`live-state` = `(my.branch/changes)` + `(my.task/status)`): ON MAIN as `seon.screen` (src/seon/screen.clj:22: "(seon.screen/show)" "(my.task/status)" "(my.branch/changes)" "(my.task/related)"), evaluated each turn (`seon.turn/screen!`, src/seon/turn.clj:183).
  - REPL calls piece (`:repl-calls`, the half-dozen calls with arglists from vars): PARTLY. Main has docs/agent.md + the screen; no generated "calls you must know" piece (`grep repl-calls` empty).
  - Design/implement stage runs (`plan-runs!`, `stage-run`, `run-until`, `plan-end`, `implement-end`, one proof status rule `seon.proofs/status-of`): ON MAIN differently. `:design`/`:implement` kinds with `seon.turn/work!` run by the reconciler (src/seon/reconcile.clj:40,284, src/seon/problems.clj:447, template.clj:37,47); end judged by the judge/`done-explain` (docs/tasks.md:44). The disposable-agent, budgeted, scored-per-stage harness is not on main.
  - Fork the agent for the task (`spawn! :task`) so `my.task/current` works in design runs: ON MAIN (agents are born per task by the reconciler). The finding (0/10 design runs could write a plan because the REPL held no task) is a lesson, not code.
  - Unknown cost never 0 when no turn found: ON MAIN in spirit (`:seon.judge/cost-unknown`, src/my/root.clj:379).
  - Runner-sources in the run key (`seon.judge/runner`) so old runs are never answered for new runners: NOT ON MAIN (judge `scorer` digest only covers scoring).
- **Worth carrying:**
  1. [agents] The measurement: "a first move is a read for both DeepSeek models, 0 of 10 acted in each of 7 variants; measure whether it acts by move 2". Main's live-agent rounds (plan.md: "look first, dogfood, then 5 paid runs") could reuse a cheap held-first-reply second-move probe (digest-keyed, no re-spend) as a prompt/context A/B before paying for full runs. Keep: digest-keyed held/rescored/paid single function; bounded by `judge-pass-usd`; tests `judge_test` (second move's link to its first, call limit).
  2. [agents] `:repl-calls` piece generated from var arglists + first doc sentence (never stale). Small, fits the screen/help page.
  3. [stability] "Score per stage" idea: score a design as on-task/plan/stage, with no model call (`rescore!`), so scoring changes do not cost money. Main's `scorer` digest already rescored held replies; only per-stage scoring missing.
  Not worth carrying: the repl-shape/standard-format dual harness (main chose the standard format; the A/B is decided).
- **Verdict:** SUPERSEDED mostly (format, live state, stages, forking per task on main); CARRY small: second-move probe as an optional harness and `:repl-calls`. Low priority since owner's path is now dogfood with live agents.
- **Effort:** small for `:repl-calls` (src/seon/screen.clj or context.clj). Medium for the second-move probe (src/seon/turn.clj, src/seon/judge.clj, src/my/root.clj, test/seon/judge_test.clj); the branch's 18 commits predate the render/screen rewrites and would conflict badly in turn.clj/root.clj, so reimplement from its tests, do not merge.

## jolt/agent-view-prototype (4 commits, last 2026-10-23)
Diff vs main: render/ui.clj +271, render/proc.clj +142, render_test +187, context.clj, writer, agent, tasks, css. 737 insertions.
- **Ideas:**
  - Agent page as debug view: a row per turn with the context remade from its basis, why-the-agent-is-as-it-is head, cost, branch, problems, ended agent read from the store: ON MAIN, far beyond the prototype. `/agent/{id}` is the agent's conversation (commit 981733b5a), debug page grows with its rows (e0313f6e1), failed call keeps sent + typed error (15a42e985), token split (c59787bf4), `a context read records what it sent` (502558397), ui.clj:470 turn row from `:seon.turn/basis`, ended agent from store (ui.clj:885-891).
  - Message the agent and release a held task from the page: ON MAIN (src/seon/render/ui.clj:393-406 input box: `> message`, interrupt, release via `seon.root/message!`/`release!`).
  - Agent reports its OS thread count; page head shows start stamp instead of ticking uptime: NOT ON MAIN. Task `an-agent-reports-its-threads` is on main (resources/seon/tasks/seon.agent.edn:54) with proof `seon.agent-test/an-agent-reports-its-threads` unwritten; grep for a thread-count finds nothing in src.
  - `/ns/{ns}` namespace page and `/data` value browser: NOT ON MAIN (docs/parity.md:85 "none"; task `seon.render/namespace-agent-debug-and-data-pages` at seon.render.edn:83).
  - v1 debug-response parity for cost: `seon.model/cost` pricing: ON MAIN.
- **Worth carrying:**
  1. [agents] `/data` entity browser and `/ns/{ns}` page: lets the owner and agents inspect a pulled entity or namespace through the render cache. Only if done as a render of existing values (AGENTS.md §3 views rule). Medium value; the prototype's /ns design needs a namespace value from `seon.ref/namespace-view`.
  2. [stability] Thread count on the agent head: tiny; useful for the "never flood the machine" rule. Better done by the agent-loop (parked agents) work, where thread/memory per parked agent is the binding number (flow-lab measured).
  Prototype's `render_test` cases (`a-turn-row-shows-the-context-remade-from-its-basis`, `the-agent-head-says-why`, `an-ended-agent-page-reads-the-store`) are all superseded by main's own render tests.
- **Verdict:** SUPERSEDED (debug view, messaging, release, ended-agent page are all on main). Remaining two items are already filed as tasks on main; DROP the branch code.
- **Effort:** small (thread count: agent.clj `:stats` + view entry + head), medium (/ns and /data: src/seon/render/ui.clj, proc.clj, web.clj, render_test.clj). Write fresh against the print/render-cache of main.

## jolt/dry-run-merges (2 commits, last 2026-10-23)
These are two Seon-agent merge commits (real agent output of a dry run), not hand work. Diff: context.clj +176/-, context_test +161, e2e.clj, e2e_test, lib_test -24.
- **Ideas:**
  - `seon.context/ns-map`: aider-style ranked map of each cited namespace's functions (arglists + first doc sentence, ordered by `:form/refs` use count, within `:seon.config.render/map-tokens` share, plus `(clojure.repl/dir ns)` for the rest); `:map` in attempt-keys: NOT ON MAIN (grep `ns-map`, `map-tokens` empty). Main reaches the same lever (naming helpers: v1 R4 7/32 -> 22/24 green, turns 3 -> 1) by other means: `:write-set` (src/seon/context.clj:277), `:similar` (context.clj:291, docs/vector.md:41 embeddings+call graph fused), `my.agent/callees/callers/grep`, docs/agent.md:28 lists `(clojure.repl/dir ...)`.
  - `callees` from `:form/refs` instead of re-reading source: ON MAIN (task a-red-proofs-calls-are-its-recorded-refs, seon.context.edn:70; `seon.context/callees` listed in docs/resolution.md:26 so possibly still a read-and-resolve; check).
  - A cited task (ns/name) shown as a section at its depth: ON MAIN (`seon.ref/task-view` src/seon/ref.clj:566, src/seon/docs.clj:402).
  - Reasoning goes back with the assistant message: ON MAIN (src/seon/context.clj:352-374).
  - `:proof-unknown` reason with a `:do` form writing the proof: ON MAIN (src/seon/context.clj:87, src/seon/reconcile.clj:56, task.clj:371).
  - Error shows its contract problems/fix: ON MAIN (render of `seon.error/*`, ui.clj:249-298).
  - `:tests` in the turn state (what this turn ran / judge runs / root runs): ON MAIN in other form (docs/agent.md `my.agent/prove`, `deferred`).
  - `e2e/agents-reused` ignores refused attempts with no agent: NOT ON MAIN as written; likely moot (e2e now different; "attempt" renamed to run).
- **Worth carrying:**
  1. [agents] `ns-map` only if the live-agent rounds show designs still spend many turns reading sources one pair at a time (step E runs 1-5: 15-25 turns). Measure first; `:similar` and `:write-set` may already cover it. Keep the proof `the-map-names-each-cited-namespaces-functions` and the R4 numbers.
  Everything else is on main.
- **Verdict:** SUPERSEDED (only `ns-map` missing, and it is conditional on a measured need). DROP the code; keep the lesson in the note.
- **Effort:** small (one function and a section in src/seon/context.clj, one config key, test). Context code has been rewritten since, so reimplement.

## jolt/flow-lab (2 commits, last 2026-10-25)
Lab notes + a ~35-line park/wake prototype, explicitly "not a design".
- **Ideas:**
  - Park a live agent proc and wake it on a message (`:park` op, `:wake` request routed through the api, `:waking`/`:pending` counters, work! clears stale `[:turn :stop]` and continues turn numbering): NOT ON MAIN as code; its decision IS on main: docs/decisions.md:40-46 ("Every agent parks", "Agents stay running, parked, between messages", numbers quoted: 1 thread, 0% CPU, wakes in 7-35 ms, 47-300 MB each). Real implementation is in flight on `jolt/agent-loop` (plan.md:19,50: "part 3 park and wake").
  - Resume from any commit: `branch!` at a commit 1.4-3.7 ms, fork on it ~340 ms, `d/commit-as-db` + `context/render` rebuilds the exact request in 26-37 ms; effect classification by symbols (heuristic; compiler facts should replace it): decision on main (decisions.md:40-43 "auto-replay pure, ask for effects"); `commit-as-db` already used in main (src/seon/context.clj:682, src/seon/fork_server.clj:79).
  - Today's lifecycle numbers (warm fork 27-60 ms, cold first fork 0.9-1.5 s because the fork server builds the reach graph on the first fork, resumed branch +390 ms for load-diff, 75 ms/turn scripted, idle RSS 48-60 MB, grows to 116/300 MB after turns and Chez does not shrink it; ~40-50 ms from tests to ended): these numbers are in no wiki page on main (notes/flow-lab absent).
  - Hazards list (stale `:stop`, turn numbering restart, lost wake, two wakes in one single-threaded process, reconciler judges at end and kills, message to a dead parked proc): NOT ON MAIN; they are exactly the agent-loop's test cases.
  - A new run does not see the old conversation because `model-turns` reads only the same `:seon.run/id`: lesson, tied to the decision "a session continues across turns" (decisions.md:42).
  - Capacity: memory per parked agent after a run binds, not threads/CPU: ~50 fresh / ~18 resumed / ~8 after real work under 4096 MB.
- **Worth carrying:**
  1. [stability] The six hazards and the numbers, as test cases and a capacity bound for `jolt/agent-loop` (is the memory of parked agents bounded by `:seon.config.machine/memory-mb`?). Send flow-lab.md to the agent-loop session, then file it in docs/agent.md or processes.md.
  2. [performance] Cold first fork 0.9-1.5 s (fork server builds main's reach graph on first fork, fork-server.clj:87): prebuild at boot. Check against current fork_server.clj; may already be fixed by `jolt/fork-guard`/reach changes.
  3. [agents] Resume cost table (0.38-0.40 s regardless of length; fork dominates) supports the "resume anything" decision.
- **Verdict:** SUPERSEDED as code (agent-loop owns park/wake and resume); CARRY the notes as input. Do not merge branch src/test changes (marked LAB).
- **Effort:** none for the code; small to move notes/flow-lab/flow-lab.md and resume.clj into notes/ or docs after agent-loop merges. Files: notes/flow-lab/*, docs/agent.md or docs/processes.md.

## jolt/git-is-read-through-fx (4 commits, last 2026-10-24)
Diff: fx.clj +77/-, root.clj -14, main_test +29, failure-points.md, task file. 91 insertions, 39 deletions. `git merge-tree` against main reports no conflict (it wrote one unreachable tree object into .git; no refs changed).
- **Ideas:**
  - `seon.fx/git-try` (nil on git's exit 1, throws on any other exit), `checked-out?`, `seon.fx/git-refs` via `git rev-parse --git-common-dir`; replaces `my.root/git-refs` hand-parsing .git, gitdir: and commondir: NOT ON MAIN (grep `git-try`, `checked-out?` in main finds only the task file; `my.root/git-refs` still hand-parses at src/my/root.clj:180, used at :228).
  - Remaining `sh/sh "git"` in seon.fx: NOT ON MAIN as fixed (src/seon/fx.clj:69, 79, 142, 160, 161, 165, 234, 403).
  - `run-tests` answers a branch with no commit as a typed `:seon.fx/io-error` instead of running at an empty sha (`rev-parse branch` unchecked at src/seon/fx.clj:403): NOT ON MAIN. This is a real bug fix (stability).
  - `checkout-at` via `git archive -o tar` through `git!` instead of a zsh pipe: NOT ON MAIN; also removes the zsh pipefail line.
  - Law `seon.main-test/git-is-read-through-fx`: no src form outside the git readers names `"git"`/`".git"`, also sees a git word in a shell line (with a red case for each): NOT ON MAIN.
- **Worth carrying:**
  1. [stability] The run-tests empty-sha fix and `git-try` exit-1-vs-other-exit semantics (swallowed git failures were reading as "no", e.g. `(zero? (:exit ...))` on a non-repo). Fits the "no swallowed error, unknown is not nil" rule (AGENTS §9).
  2. [stability] The law (a check that can fail), keeps the one-owner rule from regressing; test has both a red and a green case.
  3. [performance] none measured; removing the `.git` hand-parse is a cleanup. src net -ish: fx +77/-? root -14, net +52 lines against "resist growth"; the task's value case is the typed error and the single git entry.
- **Verdict:** CARRY (all of it; it applies cleanly by merge-tree, so a rebase/merge from main is probably the cheapest route; re-run `bin/prove`, check failure-points.md row for run-tests).
- **Effort:** small: files src/seon/fx.clj, src/my/root.clj, test/seon/main_test.clj, docs/failure-points.md, resources/seon/tasks/seon.fx.edn. Needs the §3 self-review and the "names what it replaces" line (my.root/git-refs, five `sh/sh "git"` sites).

## jolt/self-contained (8 commits, last 2026-10-23)
Diff: notes/cutover-step-R.sh +55/-19 and notes/cutover-step-R-2026-10-23.md. Eight fixes to the one-time cutover script.
- **Ideas:** cutover script ordering fixes (R8 alone behind GO_R8=1, freeze marker, v1 history as second parent of a `-s ours` merge, no submodule recursion on fetch, `add -u` not `add -A`, zsh `:refs` modifier, `--no-local` clone, in-repo `../../../` climbs): NOT ON MAIN in this form, but the cutover is DONE (commit f00f9d1ba "the cutover is done; the repo is ~/src/seon-next with v1's history"; 97043c9af "the cutover script as last run"). The script on main already carries the as-run version.
- **Worth carrying:** nothing as code. Lessons that may deserve a wiki line if a future repo move is planned: `git add -A` swept 81k untracked test-results files; zsh modifier letters after `$var:`; v1 history joins as second parent of an `-s ours` merge. [stability] only if another cutover happens.
- **Verdict:** DROP (one-time migration already executed; superseded by as-run script on main).
- **Effort:** none. If the lessons are wanted: one paragraph in docs/machine.md (notes only).


1. acts-by-second-move: SUPERSEDED (format, live state, stages on main); optional small CARRY of a second-move probe and `:repl-calls`; reimplement, do not merge.
2. agent-view-prototype: SUPERSEDED (main's debug page/conversation/messaging far ahead); leftover thread-count and /ns, /data are already tasks on main.
3. dry-run-merges: SUPERSEDED except `ns-map` (aider-style ranked map), carry only if live runs show designs still read sources serially.
4. flow-lab: SUPERSEDED as code by jolt/agent-loop; CARRY the notes (hazards, capacity and lifecycle numbers, resume costs) to the agent-loop work.
5. git-is-read-through-fx: CARRY (small, merges cleanly): git-try, typed error for a branch with no commit, one git entry, a law; self-contained: DROP (cutover already executed).



## jolt/one-content-keyed-derivation (14 commits incl. 3 main-merges; last 2026-10-23)
- **Ideas:**
  - seon.derive: framed canonical-EDN key + full SHA-256 + refusals (records/fns/refs/host objects/dbs/past byte bound/infinite seq) + result lookup (`found`) + deterministic eviction (`evict-tx`, no-history attrs): NOT ON MAIN (`src/seon/derive.clj` absent; grep `seon.derive` in main src finds nothing; only the task file `resources/seon/tasks/seon.derive.edn` (8.8 KB vs branch 13.2 KB), the owner rulings in docs/decisions.md:105-116, and notes/reuse/attack*.md are on main). Branch has `src/seon/derive.clj` (98 lines), `resources/seon/schemas/seon.derive.edn`, `test/seon/derive_test.clj` (78 lines: a-changed-input-changes-the-key, an-unread-input-leaves-the-key, what-is-not-edn-is-refused, a-result-is-found-by-its-key-and-evicted-by-its-scope).
  - `seon.error/sha256` (full 64-hex hash) with `digest` as its first 16: NOT ON MAIN (main `seon.error/digest` is still the 16-digit one; trivial 10 lines).
  - Design revisions (notes/reuse/design.md rev 1-3, code identity from content ids the process loaded: boot digest, Jolt loaded digest, Merkle over what a function reaches): NOT ON MAIN (main has only notes/reuse/attack*.md). Rev 3 is the freshest thinking. Note the owner later ruled (decisions.md "Reuse is transparent", "ctx render-first") so rev 3 should be re-read against those.
  - notes/reuse/cold-paths.md (plan/import/context measured cold): NOT ON MAIN as a note, but its three findings ARE on main as tasks: `jolt.analyzer/analysis-allocates-the-ir-not-its-lookups` (resources/seon/tasks/jolt.analyzer.edn:4), `seon.contract/an-armed-call-costs-its-checks` (seon.contract.edn:7), `clojure.core/tree-seq-costs-its-nodes` (clojure.core.edn:1) (the `-` cherry commit 57bd91f7d). The Jolt analyzer fix itself is on main as fork commit `4af66adf` (docs/forks.md:65).
  - Reach walk relation: derive does not touch seon.program at all (diff has no program.clj). It consumes reach digests ("the key is the reach digest + content ids"), so it depends on main's `seon.program/reach-index`/`graph-index` (program.clj:1086,1143) as is; today's names-only change only makes the digests less coarse (fewer tree-digest dependents), which helps a function-identity key.
  - Memo not yet written: the branch stops at the key + store; no `memo` fn, no boot/loaded digest, no walk integration, no cost proof of a hit (all in the task as "not yet").
- **Worth carrying:**
  1. [performance][agents] The memo for walk node renders / model context (owner-approved 2026-10-23 and re-affirmed "ctx render-first" today): a render of an unchanged value is never remade. Keep: key encoding + refusals + eviction + the 4 sensitivity/precision/refusal/eviction proofs. Also main already has a "render cache" mentioned in AGENTS and `seon.context` (context.clj:552 "until the render cache"), so check what the printer-everywhere work did before porting (notes/research-2026-10-25/printer-reuse.md, reuse.md on main already study this).
  2. [performance] Cold-path numbers worth keeping: whole-tree plan 2.88G ticks / 8.8 GB, import-tx 497M ticks / 3.2 GB (analyze 243M/1,150 MB, unit-account 75M/717 MB, account 95M/697 MB), d/db-with of the import 1.90G ticks, context render 3.3M ticks/11 MB/120 calls at turn 5 (1.2 KB per char of context), content-key cost table (current datoms printed+digested ~7k ticks / 30 KB, exact across branches; hasch of a pull 15.6k/88 KB; max-tx is inexact).
  3. [stability] Full SHA-256 over a 16-hex digest for memo keys (collision = wrong answer there, only a stale lookup for error signatures).
- **Verdict:** CARRY (the memo core and its measured design); but sequence it behind the render/ctx work on main. Do NOT merge the branch as is (stale base, owner rulings since changed "show reused/computed" away).
- **Effort:** medium for seon.derive + proofs (src/seon/derive.clj, schemas/seon.derive.edn, seon.error sha256, test/seon/derive_test.clj, then the walk/ctx integration which is the large part: seon.render walk, seon.context). Reimplementing the 98-line key/store layer alone: small.

## jolt/reach-scc (4 commits, last 2026-10-23)
- **Ideas:**
  - `seon.program/components` (iterative Tarjan SCC): ON MAIN, same code (src/seon/program.clj:610-635), with cost proof `seon.program-cost-test/the-strongly-connected-components-are-one-visit-per-node-and-edge` (test/seon/program_cost_test.clj:179).
  - Merkle digest per SCC, one pass, sinks first (`reach-digests` = `reach-graph` then digests): ON MAIN as `reach-graph`/`reach-step` (program.clj:968-1070) + `graph-index` (1086) + `reach-digests` (1105) + `reach-index` (1143); landed via jolt/reach-scc2 (commits cc6e2050d, 9b3fb1730, merge 571423af4: "604 ms / 734 MB a call against main's 1,446 ms / 2,771 MB"). Main's version is a superset: also folds `:reads`/`:proc`/`:bin` flags per component, `process-path`, data-refs holders.
  - `reach-update` (re-fold only changed forms' components + ancestors): ON MAIN (program.clj:1073) with cost proof `program-cost-test` (~line 217) and `program_test.clj:276`. Branch measured 118 nodes in 3.8 ms/6 MB, 1,643 nodes in 30 ms/57 MB vs 0.83 s/841 MB full.
  - Relation to today's change: main's `names-only` (program.clj:940, now incl. `seon.config/schema-forms`) is applied in `reach-step`'s `edges` fn (no edges from a names-only var's refs) and in `uses?`; the branch has no such concept and its `graph-rows`/`schema-row?`/`:schema-names` is a different, older shape. Merging the branch would conflict and would undo names-only.
  - `closure` with transients (queue vector + transient seen, tested as appended): NOT ON MAIN (main program.clj:595-606 is still the persistent `into`/`set` version). Only part of the branch not on main. Measured in 44d181039 as part of 2,480 ms/4,616 MB -> 946 ms/922 MB, but the bulk of that was the per-test neighbour rebuild, which scc2 eliminated; the transient closure's own share was not isolated. On main `closure` is used by reach-step's `affected` (rev walk) and other callers.
- **Worth carrying:** only [performance] a transient `closure` (small; measure first with `seon.profile`; reach-update's `(closure changed rev)` is its hot use). The measurements of reach-update are already encoded as main's cost proof.
- **Verdict:** SUPERSEDED (all but the closure micro-optimization, which is optional).
- **Effort:** small (src/seon/program.clj `closure` only, ~8 lines; re-measure with program_cost_test).

## jolt/unitflake (5 commits incl. 1 merge; last 2026-10-21)
- **Ideas:**
  - Derive the cost judge's tolerances from the unit table's measured `:range` per row (`unit-range`, `tolerances`, `widest-tolerances`, `tolerance-text`; slack = largest measured ratio, exponent tolerance = steepest log(hi/lo)/log(100); calls exact 1.0/0; verdict carries `:tolerances`; prompt and explain render them instead of hardcoded 1.25x/3x/0.3): NOT ON MAIN. grep `tolerances` / `unit-range` in main src finds nothing; main cost.clj still has constants `slack {:ticks 3.0 :ns 3.0 :bytes 1.25 :calls 1.0}` (cost.clj:40), `steeper-by 0.3` (:62), `ratchet 1.5` (:80). Main has since moved to ticks as the exact time dimension (`exact`, `tick-allowance`, `growth-share`), so the branch's `:ns`-centric rewrite conflicts structurally and must be redone on ticks.
  - `:db/pull-attr` measured as a difference on ONE db with the same entities (`notes-db`, measure-unit measures the subtrahend on the same args): NOT ON MAIN. Main test/seon/cost_test.clj:277-289 still builds two different dbs (a 1-entity db for pull-attr, an n-entity db for pull) with `:minus :db/pull`; main's measure-unit still subtracts per-unit values of two separately set up measurements.
  - Cause of the bimodality (e7aad9dae): one entity at the edge of a 512-datom EAVT leaf, pull costs 33,408 B while EAVT <= 509 datoms, 36,048 B from 529 (a second index level, ~2.6 KB/pull): finding NOT ON MAIN as a doc (cost.clj:391-397 comment on main mentions "one-level index before, the seed makes it two levels", the same cause found independently, but with the old two-db setup still in place).
  - docs/cost.md unit table + derived tolerances section: PARTLY (main's docs/cost.md:41 states the constants).
- **Worth carrying:**
  1. [stability] Same-db pull-attr measurement: fixes a real flake source in `the-unit-table-is-current` (row moved 7.6 -> 9.6 KB between setups; branch: 9,656/9,581/10,085 B at 1k/10k/100k on release, 5-6 green runs in a row). Cheap and independent; do first.
  2. [stability][agents] Tolerances derived from measured ranges (no guessed constants; verdict states what it was judged by, agent prompt text derived from the same source). Principled and matches "estimates are proofs", but must be redone for ticks/bytes/calls; ns is no longer red. Proofs to keep: cost-test/the-exponent-tolerance-follows-slack-and-span, slack-is-the-units-measured-spread, context-test/the-prompt-states-the-judges-own-tolerances.
  3. Measurements: bytes ranges per row from 18 measurements; widest bytes ratio 1.26x, print entry 1.02x, exponent +0.109 bytes; wall 3.15x/+0.256. The branch's printer entry 2,431 B vs 2,256 priced (1.08x) -> repriced by :map/literal.
- **Verdict:** CARRY (pieces 1 then 2; do not merge the branch).
- **Effort:** small for #1 (test/seon/cost_test.clj unit-benches + measure-unit, src/seon/cost.clj units row); medium for #2 (src/seon/cost.clj judge/tolerances/explain, resources/seon/schemas/seon.cost.edn, src/seon/context.clj prompt, src/seon/check.clj, test/seon/cost_test.clj, context_test.clj, docs/cost.md).

## jolt/a-process-root-opens-the-shared-store-under-load (2 commits, last 2026-10-25)
- **Ideas:**
  - A root that dies before listening is a typed test error with its log: `start-root!` keeps the dead root's log (`:log`), `ask` answers `{:error :root-did-not-listen :log ...}` instead of an ask of a nil port (schema `[:maybe :int]`): NOT ON MAIN (main test/seon/test_store.clj:147-200 `start-root!` returns `:port nil` only; grep `root-did-not-listen` finds only notes/research-2026-10-25/regressions-compiled.md describing the symptom: "seon.test-store/ask :port nil (root did not listen) ... unexplained, not reproduced").
  - Proof `seon.test-store-test/n-roots-open-the-process-store-at-once` (4 roots start on the process store at once; every one must listen; the typed error case): NOT ON MAIN. Does NOT fix the LMDB cause: the branch only surfaces the log; the root cause `lmdb env_open: Invalid argument` is still unfound. notes/archive/stability-2026-10-25.md:11 (row 5a) says "not found. Ruled out: differing flags or map size, LMDB_FLAGS, the semaphore unlink race (ENOENT on macOS ...)". The task entry `seon.test-store/a-process-root-opens-the-shared-store-under-load` is on main (resources/seon/tasks/seon.test-store.edn:2). The other commit (ea825e627) adds task `seon.proofs/an-agent-reads-its-own-recorded-results`: NOT ON MAIN (the task is missing from main's seon.proofs.edn).
  - Task text from the stability lead wants "an LMDB typed error": nothing in this branch gives LMDB a typed error at the edge (`seon.db`'s open); the branch only handles the symptom in the test harness.
- **Worth carrying:**
  1. [stability] Turn "ask of no port" into a named error carrying the log, so a flake names its own cause instead of 'unexplained, not reproduced'. Good diagnostics, 26 lines. Plus the 4-roots-at-once reproduction proof (it may be the repro the lead needs: run it under load with `nice` load to try to hit env_open EINVAL).
  2. [stability] The real item (typed LMDB open error at the seon.db edge, named in docs/failure-points.md with its declared error, retry/refuse rule) is NOT in this branch; needs the env_open cause first (agents with strace/dtruss; check `lock.mdb` size/ftruncate races with concurrent opens, EINVAL from `mdb_env_open` on macOS when the lock file is truncated by another opener).
  3. [agents] the filed task an-agent-reads-its-own-recorded-results (a red its own turn recorded is not on its branch, so its judge reruns it): wasted judge reruns.
- **Verdict:** CARRY (the harness error + proof now; the typed LMDB error as the follow-up it was meant to be).
- **Effort:** small (test/seon/test_store.clj, test/seon/test_store_test.clj, one task entry); the LMDB typed error itself is medium and unknown until the cause is found (src/seon/db.clj edge, docs/failure-points.md).

## jolt/durable-read-runs-alone (1 commit, 2026-10-24)
- **Ideas:**
  - `bin/check durable` as a single mode runs both processes (durable-write must exit 137, then durable-read), and `bin/check durable-read` alone says why it cannot instead of crashing with FileNotFoundException: NOT ON MAIN (main bin/check:94-103 single-mode path runs `run "$@"` only; u-durable at bin/check:226-229 is the only place both run; main test/seon/check.clj durable-read at 753-760 still does an unguarded `slurp` of durable.expect.edn). Task `seon.check/durable-read-runs-alone` is on main as an open task (seon.check.edn:95).
  - `main-data-datoms` leaves the agents' `:seon.agent/root` links out (a new boot retracts them by design): ON MAIN, already (test/seon/check.clj:712-719 includes `(= :seon.agent/root (:a %))` and durable-read at :764 reads `linked`; probably from a later fix of task seon.db/a-killed-root-recovers-main-in-the-durable-read-check, whose task entry is open at seon.db.edn:101 but now stale).
  - Report names differing attributes (`by-attr`, `:attrs-that-differ`, expect carries `:by-attr`): NOT ON MAIN (grep `by-attr` / `attrs-that-differ` in main test/seon/check.clj: none).
- **Worth carrying:** [stability] the single-mode fix and the clear message (dev-loop reliability for the durability check, the thing the stability lead cares most about), and `attrs-that-differ` (turns the next "4 datoms missing" into a named attribute). Both tiny. Also: the two task entries on main (seon.check durable-read-runs-alone, seon.db a-killed-root-recovers...) should be marked done/updated.
- **Verdict:** CARRY (small; a cherry-pick-sized change).
- **Effort:** small (bin/check ~8 lines, test/seon/check.clj ~20 lines, 2 task edns). It will conflict textually in check.clj (main already edited main-data-datoms); redo by hand.

## jolt/nested-tick-budget (1 commit, 2026-10-23)
- **Ideas:**
  - Jolt pin a5b36d13: a tick budget covers all work inside it, nested bounded runs included: ON MAIN in a later form: docs/forks.md:61 (`ed1e37a1` "one tick count, owned by the timer ... a tick budget covers all the work..."), and main pins Jolt e1b72c1a (git ls-tree main reference-code/jolt), which descends from it.
  - Test `seon.agent-test/a-proof-run-ends-at-its-budget-though-its-tests-run-bounded-evals`: ON MAIN (test/seon/agent_test.clj:625), cited in resources/seon/tasks/seon.proofs.edn:48.
  - (The branch would also REGRESS the Jolt pin from e1b72c1a back to a5b36d13.)
- **Worth carrying:** nothing: superseded by main's Jolt pin + test.
- **Verdict:** SUPERSEDED (and dangerous to merge: pin goes backwards).
- **Effort:** none.
