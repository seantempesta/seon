---
title: Morning report 2026-10-09 (built overnight)
type: report
status: active
created: 2026-10-08
---

# Morning report

This page is built up through the night; the final form is written before the owner wakes.

## Decisions root took on the owner's behalf (review these)

- **Agent entity (Q-AE1 to Q-AE4, `cards/README-agent-entity.md`):** all four recommended options were taken, in line with the owner's ruling "an agent has their own entity and the task should be ref'ed there".
  - Assignments become components of the agent and ref their task.
  - The call cap is anchored on a per-assignment grant.
  - Messages are written at the task home.
  - The "branch and merge back" design lives as the ref `design/agent-entity` on the platform branch; no worktree.

- **Bench gate (C0):** E0 (baseline noise) and E1 (task wording: prose vs typed references, T1-T4 x 5) run before the
  'model pass costs an ordinary pass' gate, as live runs 1-4 did; the full matrix still waits on C0. Root default.

## Decisions needed from the owner

1. **Host-bound tasks (RF-3).** A branch never changes compiled platform code, so a task whose proof reaches the changed
   function only through compiled callers can't go green on a branch (fan-out agent fan-doc: its proof calls the
   compiled `seon.render.ns/render-ai`). The options are the `hostbound-opus` / `hostbound-astra` notes' routes: root runs the
   isolated compiled check and returns the result to the agent's task; or such tasks go only to lanes, not to model agents. Until
   decided, root picks model-agent tasks whose proofs call the changed function directly.

2. **Commit cost (research `dad68a4ce`).** A small durable append should cost 4-6 ms; ours is 21-80 ms. Recommended
   order: remove redundant commits, shrink the branch-head and root records (88 KB), group flushes across agents, then
   (longer) a datom write-ahead log for one flush per commit, durability before an answer kept throughout. Root will card
   steps 1-3 in our Datahike/konserve forks; **the write-ahead log is an architecture decision for you.**

3. **Test admission in the recording transaction (CC-2, `cards/README-commit-cost.md` Q1).** Your earlier ruling (OD-Q5) keeps
   writer admission of a test request. CC-2 keeps it writer-checked but in the eval request's one recording transaction
   instead of its own commit (saves about 20 ms and two flush barriers per request whose tests run). Recommendation: yes,
   that still satisfies the ruling.
4. **When to open the datom write-ahead log design** (commit-cost step 4).

Root defaults taken: the automatic test step narrows to identities changed since its previous completed run (new optional
request key; `my.test/check` and proofs keep the broad rule; namespace-derived seeds stay broad); a branch revision's reach maps are
derived and dropped rather than cached (advancing from the recording transaction's report is the follow-up). A REPL `require` updates the stored ns form (files are the write-back of the program), and a function's
digest depends only on the aliases it resolves through, so an unused alias changes no other digest (task `ad78cf13e`; lane ns-form). Tests reaching a change are answered by a reverse walk from the changed names (reach-cost option 1;
the whole-program reach graph per change was the real P1 owner: 280 ms / 671 MB cold). SCI functions carry their defining context so a host thread reads the current definition (option
A1: an SCI fn binds its defining context on entry when none is active; +4 ns (+14%) per call through a Var, correct for
reads, calls, host ops and writes; option A2 was free but left host ops and writes wrong). The reply's `:seon.cost/transactions` counts every durable commit the request caused, the test
world's throwaway-branch commit included, counted by distinct commit ids (CC-1 follow-up). `:chat` is the default wire format (E2: success tied 36/40 vs 35/40; chat had fewer repeated forms
31 vs 86 and 16% fewer prompt tokens; a weak preference, a dial per agent). Proofs and reaching tests run as one test request (CC Q2: one admission and one completion instead
of two each).

5. **Should "tests reaching a change" decide green reuse (reach-reverse option 3)?** Today a recorded green is reused when
   the test's forward-closure digest is unchanged (`reach-digests`, which costs 178 ms / 322 MB right after a defn). The
   alternative: a recorded member is current when no name changed since its recorded commit reaches it (a reverse walk), so
   digests are computed only for tests that run. Faster, but it changes what green reuse means across branches. Root is
   doing the safe options first (cheap datom checks, then advancing cached reach by the change).
6. **Two reach relations disagree.** Test selection (`seon.fn/gate-sets`) stops at direct callers (60 tests for
   `seon.id/hex`); reach digests use the full transitive closure (2,327). Deliberate today; should selection and reuse share
   one relation?

## Pending ledger edits

- From lane agent-entity: R-8 is answered by AE-5 and R-9 by AE-1 to AE-3. §6 question 2 is answered. New §3.31 holds AE-1 to AE-7. In §5, "R-9's row" becomes AE-1 to AE-3. Applied in `08a2aa871`.

## Scoreboard

- **Live run 4** (`324f19e3b`): green in 3 turns and 6.2 s. 4 calls, 5.2K tokens, 1 error (help's placeholder write-back), 2 wasted passes, no platform refusal.
- Bench: grade function landed (`bb89f96c8`): success, calls, calls to green, errors by type, wasted calls, tokens, wall
  time from the database; 8 strategies designed (`0a6aefa16`).
- **Fan-out 1** (3 live agents on real tasks in parallel, about 22 s of wall time; no platform refusals): 2 of 3 green and
  committed by root after review: `64b270b02` (`my.program/source` repeats the docstring: 2 turns, 5 calls, 14.5 s)
  and `96ec6feb7` (`settings!` answers the pre-write overrides: 3 turns, 5 calls, 18.4 s; root stripped a
  `:db/id` leak). Not landed: fan-doc (a host-bound proof, see decision 1; it also hit a committed nilable contract it
  could not redefine). 7 frictions filed (`7e86786ef`).
- **Context scorecard, round 1** (`docs/research/agent-platform/2026-10-08/context-refine/scorecard.md`; 18 real agent
  forms, 8 rules each): 56 rule failures and 9,565 tokens before, 38 failures and 9,046 tokens after. Fixes: help with no
  placeholders (`a9e2bcf6d`), handled messages leave the block (`15ef7b9b4`), no printed help map as the system message
  (`edfa957c1`), error replies name their type and say each thing once (`32bc482b0`). Round 2 is running (breaks
  render, help as data, error next steps); the per-reply tests block goes to feedback-2.
- **Fan-out 2** (3 live agents, real tasks, proofs `0717d5a5f`): 0 of 3 green, nothing landed. Mostly platform:
  - an unrelated timed-out test (18 s against its 5 s bound) restarted in every eval request, costing 10-26 s of every
    agent pass;
  - must-read `source` forms answering nil cost the agents most of their turns;
  - re-evaluating a test namespace's `ns` form at root's REPL left a proof with no executable Var;
  - heap at 3.5 of 4 GB and `install-row!` threw 1,588 times.

  Root's response (platform first): reset; fan-outs paused until the automatic test step is fixed (unchanged reds
  answered from their record, nothing timed out restarts per request, only tests this change reached); the `source`
  fix goes to context round 2; `install-row!` gets a diagnosis lane. Frictions filed in `3a2a97701`.
- **Per-request cost on every reply** (feedback-2): `bdb747cc5` total and per-stage ms; `fbd5e0fa9` calls, reads and
  transactions per request; `ae3916720` exact allocation and CPU time (evaluation on a platform worker, 27 µs hop; a
  100 MB allocation reads 100-101 MB beside a concurrent allocator). First honest `:ping`: 147 ms, of which MCP custody
  67, settle 26, test step 27. Slice 4 (top owners when slow) went red and was shelved, its patches kept.
- **Live per-request cost after restart (56f176efc):** a warm `:ping` costs 143 ms, 57,845 armed calls, 46 reads and 1
  transaction. Stages: settle 80, test step 37, fork 13, evaluate 2, print 1.6 ms. A new agent's first request costs
  1.2 s (custody 1 s, agent creation). Next cost target after the test step: settle (one transaction should be a few
  ms; patterns P1/P2: PB-10 writer validates three ways, CO-7/CO-8 schema predicates armed).
- **Context scorecard, round 2:** `breaks` 2,110 → 840 tokens with each caller once (`216fbba1d`); help reply is data
  (`c8c9671ce`); `doc` without empty sections (`c2ab1fd1a`); must-reads offer a working source form (`2a51d1edd`, the
  defect that cost fan-out 2's agents most of their turns). Not done: error types for unresolved symbol and uninstalled
  attribute (their producers sit in held files). New tasks: breaks of a hub function allocates 1.2 GB; a map argument
  missing its database is refused instead of supplied; a retire-branch error repeats in every reply.
- **Bench tasks** (`c13417919`): T1-T4 each in prose and typed wording with shared red proofs. The launcher and table
  (BENCH-3) work on the fixture provider (T1 × 2 wordings × 2 runs in parallel, 7.3 s, all green) but are uncommitted:
  - **Regression found by the bench:** the test-step lane's F11 commit `f5e4ec35d` makes proofs that evaluate agent code
    from strings keep their first red forever, so no such task can go green. The test-step lane is fixing it with the
    bench grade test as the regression.
- **Test step fixed** (lane test-step): reaching means this branch's change since its fork (`678597f32`); adding an
  alias reaches nothing (`100f08747`); an unchanged red is answered from its record while the program is unchanged
  (`f5e4ec35d`, narrowed by `d78664a85` after the bench caught the regression); the tests block shows only what ran,
  with no ids (`0d3f63da5`). Live after restart: a fresh agent's `(+ 1 2)` drops from 10.2 s (with reds on main) to
  1.05 s (custody 0.9 s for agent creation, test step 37 ms); a root `:ping` 145 ms (settle 50, test step 51).
- **Bench launcher** committed (`d384bb0c4`). E0/E1 running (paid, deepseek-flash). Cost lanes on settle (writer) and the
  test step's unchanged-program selection are running.
- **Settle measured, not changed** (lane settle-cost): about 93% of settle is Datahike/konserve's durable commit (empty commit
  12-13 ms, 17 datoms 19-25 ms, `d/with` 0.12 ms; 30-41 index-node files plus an 88 KB root and two drive flushes per
  commit, queued across agents). Seon's checks are about 1-2 ms, so CO-7/PB-10 can't move settle. The root cause is the
  cost of each commit. Levers: fewer commits per request (CO-2 drops the admission commit; one commit per eval request)
  and cheaper commits in our forks. Astra research `commit-cost-astra` is running.
- **CO-1** (`79a19e8d7`): an unchanged branch's change set is a cache lookup (0.1 ms, was 14.6-18.5 ms). CO-2 already
  held (a `:ping` answered from its record commits once). Remaining step owners: the test step re-forks the context the eval
  request already forked (6-7 ms, P1 repeated work, small `turn.clj` card) and selection per new commit (4-15 ms, owner
  not yet named).
- **Bench E0/E1** (`0e8b841f6`, `docs/research/agent-platform/2026-10-08/bench/e0-e1.md`): 65 deepseek-flash runs, US$0.099,
  20 parallel agents per group with no platform refusals (live heap 1.2 GB). E0 noise baseline: one cell varies by 2 of 5
  between batches. E1: typed wording beats prose on T2 (4/5 vs 1/5) and T3 (5/5 vs 2/5); no difference on T1 (10/10 vs
  8/10 at 10 runs); T4 leans typed (7/10 vs 4/10, ranges overlap). **Root decision: typed task wording is the default.**
  Run-polluting findings now being fixed: F1, 30% of calls came after green (no completion), and F2, a prose-only reply ends
  the run (9 of 65) (lane turn-end); F3 `dir` shows internal fns and supplied arguments, and F4 help teaches no schema
  registration (lane context-3); F5-F7 REPL bugs, one a crash (lane repl-bugs). E2 (wire format) follows F1/F2.
- **F1/F2 landed** (`062027664`): a task whose proofs are all green is `:done` (derived) and the loop stops calling the
  model; a prose-only reply gets one follow-up and a second one waits. E1b re-runs the typed cells to measure the effect.
- **More landings:** F7 refusal crash on a database argument (`7209cfd2a`); F5 quoted alias refused in the SCI fork
  (`7cb34638`, pointer `9050d76f5`); context round 3: `dir` shows only what an agent calls, help teaches schema registration
  (`c01abe244`); AGENTS.md task intents written typed (`c8a7a40a9`). CX-12 (chat wire format, for E2) is in progress.
- **E1b** (`655cc8849`, typed T1-T4 × 5 after F1/F2, US$0.02): calls after green 1.6-2.8 → 0 per run; calls/run down
  27-60%; prompt tokens/run roughly halved (T2 23.6k → 5.3k); runs hitting the cap 12/20 → 4/20; green 16/20 vs 18/20
  (within noise); platform errors 0. F2 gives a second chance, not a guarantee (one run ended on two prose replies). The
  launcher now ends `:done` runs early (lane launch-done).
- **Commit-cost cards** (`8f5f23dab`, CC-0 … CC-8): commits per eval request today are 1 for `:ping` or a `defn` with no reaching
  test, 3 when a reaching test runs (recording, admission, completion), up to 5 with proofs; the reply's commit count is
  wrong and is fixed first (CC-1).
- **E2 wire format** (`690045e35`, 80 runs, US$0.07): transcript vs chat tie on success (35/40 vs 36/40; no task clears
  the rule at 10 runs); chat favoured on repeated forms and tokens, so chat is the default (lane chat-default). CX-4
  removed prompt copying completely (`user=>` lines 44/322 → 0/244). Platform defects found: H1, a Markdown reply read as
  121 forms (lane h1-prose); H3, a deftest over an earlier contracted defn is refused (lane h3-contract).
- **CC-0** (`9df647b67`): a small commit on a fresh production-config store costs p50 13.4 ms, 2-6 files, ~10 KB, with a 3-4 KB
  head. Default's head is ~88 KB with 30-41 node files per commit, so commit cost grows with the store (CC-3/CC-4 target
  it). **CC-1** partial (`745f32362`): JVM requests now count their commit; counting by commit ids is the follow-up.
- **H3 fixed** (`4c7919e64`): a later-request redefinition of a contracted defn (as a deftest, or with a new arity) was refused
  because `derive-form` read the shared Var outside the agent's SCI context, so it saw the base's binding. This is a
  class introduced by SCI-1's per-context Vars; lane ctx-audit is sweeping every JVM-side SCI Var read and write. `:chat` is
  the shipped default (`937296267`).
- **H1 fixed** (`5fe677bd3`): the bench's 121-form Markdown reply now reads as its 13 real forms (code inside a
  clojure/clj/cljc/edn fence is code; elsewhere a line is code when it starts a form; a closing fence can't be swallowed by
  a half-finished form; indented definitions are code). Another data point for decision 1 (RF-3): branch test evidence
  proves nothing for compiled platform files like the reader.
- **SCI context sweep** (`324461975`): no wrong site left in Seon (62 SCI-Var sites, all in context or context-free); found
  the class instead: an SCI fn run on a raw thread or an executor sees each Var's first definition (red proof committed).
  Root chose option A (fns carry their defining context); lane sci-fn-ctx is working in the SCI fork, timing first.
- **Fan-out 3** launched on 324461975 (all of tonight's fixes live) to re-measure the end-to-end rate on real tasks.
- **Process note:** lane sci-fn-ctx ran `git stash` inside the SCI fork submodule (forbidden); it held only its own changes and
  was popped at once. No one else's work was touched.
- **Fan-out 3** (`8b0efd5d7` landed; frictions `a0216de96`): 1 of 3 green (batch 1: 2/3; batch 2: 0/3), and **0 platform
  errors**: both misses were model mistakes (a fix key in the wrong map; forms written as `;;` comments, then `sort-by` used
  as a transducer). Help's "write every sentence as a ;; comment" encourages commented forms (context task filed). Costs
  seen: reading one task's proofs allocated 887 MB; a two-test check 1.4 GB (pattern P1, cards CO-3..CO-5).
- **SCI-5** bumped (`ea6fa74ef`, fork f8a2542d A1). Seon's proof is still partly red (raw thread and executor read the first
  definition; future is right): the integration risk the card flagged. Lane sci5-seon is diagnosing which context the
  fn captures.
- **SCI-5 complete** (`c22814cdb`, fork 41194667): the remaining red was clj-kondo storing its own SCI context as the global
  root of `*ctx*` on load, so "no context" never fired on raw threads. A stored root is now no thread's own context. Proven
  on a throwaway JVM with `check --at`: raw thread, executor and future all read the current definition, and SCI-1's
  inheritance proofs stay green. Help regression from `8b0efd5d7` (unresolvable example, over the token cap) is being
  fixed (lane help-cap); `081f7eab6` stops help encouraging commented-out code.
- **Reach cost measured** (lane reach-cost; CO-0 `667cc9a95`): CO-3 never reached today and its design was unsafe; CO-4 moot after
  W-24; CO-5's forward closure walk measured 4× worse. The real owner is `seon.test.runner/tests-reaching` rebuilding the whole
  program reach graph per change (task `d21ac7dd1`). Root chose the reverse walk from changed names (lane reach-reverse). The
  check's other large cost is arming every contract per test world (677 MB): a separate card.
- **Reverse walk proven equal but not adopted** (lane reach-reverse, no commit): it gives today's answer, but the cost the
  expensive cases pay is in `reach-digests` (178 ms / 322 MB after a defn, 115 ms of it the armed `seon.db/datoms` output contract
  checking every datom of 11 whole-attribute reads). Root reversed its own earlier pick and chose cheap datom checks first
  (lane datom-check, P2), then advancing cached reach by the change (P1).
- **Datom checks already cheap** (lane datom-check, no change): the output contract is ~41 ns per datom (12% of a whole-attribute
  read); warm `reach-digests` is 21-40 ms. The 178 ms came from cache eviction: seon.cache is at 506 of 512 MB, reach
  declarations weigh 195 MB with 46% hits and 13 evictions, and weights run ~3× real bytes (task filed by heap-live). That
  cache-weight error is a root cause of the variance that sent three lanes after the wrong owner; lane cache-weight is
  fixing the weighers first, then everything gets re-measured. Lesson for the plan: measure with a cache that tells the
  truth before ranking cost owners.
- **Fan-out 4** (`137ff2bff`, 176 tests, 0 fail; frictions `35a5cb284`): all 3 fixes landed after root review (one agent's
  `sort-by` recomputed distances on every comparison, 8 GB down to 0.5 GB; a docstring and a hidden refusal restored). Only 1 of 3
  went green on its own branch: 2 misses were root's (redefining existing file-derived proofs at root left them without an
  executable Var; rule recorded: never redefine an existing proof at root). Agents needed 21-24 calls with 16-31 repeated
  forms. Platform blockers found: `write-branch!` refused all three branches (unchanged functions "reindex differently", 0.8-1.5 GB
  per refusal; lane writeback fixing); the call cap counts per wake, not per run (agents reached 24 under a cap of 16; AE-5).
- **Cache weights fixed** (`dc79b069f`; measured `ef52aa7d1`): reach kinds were charged 358 MB for 74 MB real (4.3×), now 1.02×;
  reach-declarations no longer cached. Fresh occupancy 506 → 201 MB, 0 evictions over 11 requests, warm `reach-digests` 21-40 →
  0.2-0.4 ms. Two P1 growths exposed (lane step-growth): the test step grows with the tests already on a branch (483 → 946 ms
  over 7 trivial requests, +20 reads each), and reach-attribute is keyed per revision (+7 MB per request).
- **Write-back diagnosed** (lane writeback): the check was right; the REPL path stored namespace rows whose digests and ns form
  disagree with the file. Fixed: a rewritten namespace row's digest covers what it keeps (`abb2f40a7`). Decided by root and in
  progress: REPL requires update the stored ns form; function digests use only the aliases they resolve through. Cost seen:
  one file's write-back proof is 160-790 ms / 280-780 MB (one clj-kondo analysis of the file).
- **Step growth owner found** (lane step-growth): the automatic step passes everything the branch changed since its fork as
  "the change", so each request redoes gate walks, reach digests and covered-by writes for every earlier branch test (+20 reads,
  +3-4k calls per request) and re-runs unrelated reds. Root ruled: narrow the automatic step to changes since its last run.
  reach-attribute holds 197 MB (38% of cache) of per-revision copies: derive-and-drop for branch revisions.
