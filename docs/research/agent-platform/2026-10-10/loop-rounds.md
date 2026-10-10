---
title: "Loop rounds, 2026-10-10: chain runs 2–5 and rounds 1–4"
type: research
status: evidence
created: 2026-10-10
---

# Loop rounds, 2026-10-10

Evidence for [the plan](../../../seon/plan.md) §0. The dated chain-run and loop-round entries below were written in the
plan's §0 during 2026-10-10 and moved here verbatim, with their numbers, when §0 became a dependency list (Astra's
[day audit](astra-day-audit.md), finding 10). Round 4 is from root's report of the same evening. Nothing here is
current direction; the plan's §0 is.

## Summary, one line per run

| Run | Task | Agent | Result | Cost | Landed |
|---|---|---|---|---|---|
| Chain run 2 | `seon.sci.forgive/forgiving-forgiven-text-changes-nothing` | `chain2-forgive-1` | capped at 15 calls, proofs 2 red 1 green | 31.5 s, 156k prompt tokens, 7 agent errors | no; the task was wrong for a first run (Astra) |
| Chain run 3 | `seon.competency/slug-removes-edge-hyphens` (bench fixture) | `chain3-slug-1` | green, no root rescue | 1 call, 4.7 s, 1,337 prompt tokens, 0 errors | not merged (fixture) |
| Chain run 4 | `seon.program/edn-symbol-text-accepts-a-leading-slash` | `chain4-edn-1` | green; the first model fix to real Seon code | 7 calls, 26.5 s, 24k prompt tokens, 1 agent error | d3de85d72 (root wrote back and committed) |
| Chain run 5 | `my.program/a-search-snippet-keeps-the-whole-line` | `chain5-search-1` | green; the whole loop, root only reviewed and merged | 4 calls, 9.3 s, 13.7k prompt tokens, 0 errors | 3c4f94227 |
| Loop round 1 | path-form, search-limit, ref-section (aeaf6f1fc) | `r1-*`, three in parallel | 2 of 3 green; `r1-ref-section` stalled with a silent end | 5 and 2 calls; prepare 6.2 s / 3.2 GB for two merges | a4b7e6261 (two merged) |
| Loop round 2 | cost-diff, problems-prose, ref-section (9a5324489) | `r2-*` | 1 of 3 green, 0 merged: the gate refused `r2-cost-diff` (three reaching tests red, run 5649676309ec) | 13 calls / 143k tokens (cost-diff); two capped at 15 | none |
| Loop round 3 | the same three, refined (1745f48cf) | `r3-*` | 2 of 3 green (both earlier failures); 0 merged: a schema change cannot merge; a same-file conflict | 12 and 10 calls to green; cost-diff capped, 1 of 4 proofs | none |
| Loop round 4 | problems-prose (rerun on the new base), cost-diff (third attempt) | `r4-*` | 0 of 2: problems-prose ended `:stalled` at 5 calls; cost-diff capped with 12 errors | — | none |

Over rounds 1–4: eleven agent runs, five green, two merged. Chain runs 4 and 5 are the two model fixes that reached
HEAD through the whole path.

## The entries, verbatim from the plan's §0

**Chain run 2, defined.** Done means: a DeepSeek-flash agent, launched on `seon.sci.forgive/forgiving-forgiven-text-changes-nothing`
with only the context Seon generates, turns its proofs green with no root rescue, and root reviews and merges its
branch. Its three known defects ("a]a", "('", "(\\") are in designed-task-examples §2. Launch: root's JVM REPL drives
`seon.competency/start-run!` and `await-runs` (the E1 path, per-branch `no-provider false`, a call cap) but keeps the
branch for review instead of `launch!`'s retirement. Every friction met is filed; that list decides the next build.

**Chain run 2, first attempt (2026-10-10, agent `chain2-forgive-1`, branch `:agent-e84b11b8bd78` kept).** Capped at 15
calls in 31.5 s, not green (proofs 2 red, 1 green); 156k prompt tokens (108k cached), 3.2k completion; 0 `defn`s,
19 repeated forms, `in-ns` on every call, 7 agent errors, 0 platform errors. It found the right spot and nearly the
research's fix (a dropped closer becomes a space, but without recording `:seon.batch/inserted`). Frictions, in causal
order:
1. `(my.program/source 'seon.sci.forgive/scan)` (about 250 lines) is cut from 3,533 to 1,989 tokens with a
   `:large-value` finding telling it to select less, so it cannot see the function whole (lane `source-whole`).
2. No REPL way to change one branch of a large definition: `defn` of the whole function is the only path, so the
   agent `slurp`ed and string-edited the file instead (decision for the owner: an edit tool over a definition's source).
3. A relative `spit` from SCI rewrote the running archive `data/source/<sha>/src/seon/sci/forgive.clj` (root
   restored it from git; the compiled Var was unaffected); lane `archive-readonly` makes extracted archives read-only.
4. Model tool markup `</invoke>` in a reply is read as code (3 errors; `seon.sci.forgive` reply-mode prose rules).
5. A guessed `seon.print/inspect`; `my.program/callers` passed a function (refused with a fix; agent errors).

**Chain run 3 (decided 2026-10-10 after Astra's review, `astra-chain-friction.md`, 1fc49251c).** `forgive` was the wrong
first task: a 178-line platform reader, three defects and an unsettled repair policy, so it tested everything at once.
Chain run 3 is the small ordinary `comp.text/slug-removes-edge-hyphens` (policy fixed before launch; lane `slug-task`);
then a small real owner; `scan` later as a separate platform acceptance. Landed for it and live on default 18919b663:
sealed archives and pins (9cd874d94, 4027882d4), whole source (e25afdf6e), tool markup as prose (ececefad5), an agent
runs its proofs with `clojure.test` at its REPL (9a78f8f9d), errors grouped by signature across agents in health
(763693afc), retirement closes admission and a message hosts a stopped agent (d98ab13ed; the agent does not yet run:
lane `message-wakes`), six red census proofs for violated laws (18919b663). In flight: `message-wakes`, `merge-gate`,
`slug-task`, and the DYNAMIC-CONTEXT card's revision (owner: reflection reads the record first, then asks a snapshot
of the agent forked at the decision's t; one happy path for task running, every end message goes to root).
Boot (Astra, 246a571ed, `docs/research/agent-platform/2026-10-10-boot-source.md`): readiness 5.3 s unchanged, 7.5 s one
file, 6.9 s across a dependency change on a quiet machine; default's 15–18 s boots were contention with lanes' check
JVMs. Every changed boot still re-derives the whole program (`stored-derivation-schema-keys` 1.55–1.78 GB). Owner
2026-10-10: deferred; the slots go to the self-improvement loop. Per-file derivation inputs (3–5 h) is the chosen fix
when it is picked up. The same cost shows in every `bin/seon check`: publication about 26 s (lane publication-robust,
runs 8c15c8a47f70, 9dbc88e3b762), which also makes each platform proof 30–90 s.
Open: two reds on clean HEAD (`seon.config-test/the-default-document-has-one-canonical-complete-location`,
`seon.db-test/equal-declaration-content-shares-one-projection-across-values`) to classify; boot readiness 15.6 s on
18919b663 against 5–9 s earlier.

**Chain run 3 result (2026-10-10, agent `chain3-slug-1`, branch `:agent-6c8a0df7e53b`): GREEN.** DeepSeek flash took
`seon.competency/slug-removes-edge-hyphens` red → green in 1 model call, 4.7 s, 1,337 prompt tokens, 0 errors, no root
rescue: read `comp.text/slug`, redefined it, proof green. One reply repeated its `in-ns`/`defn` pair (4 repeated forms).
Not merged: `comp.text` is a bench fixture. Next: chain run 4 on a small real owner with a known bad input, through
review, write-back and root's merge (lane `real-task-pick` proposes candidates).

**Chain run 4 (2026-10-10, agent `chain4-edn-1`, branch `:agent-e2a7a50d8c07`): GREEN AND LANDED, the first model
fix to real Seon code.** Task `seon.program/edn-symbol-text-accepts-a-leading-slash` (lane `real-task-pick`, 550b599b4):
7 calls, 26.5 s, 24k prompt tokens, 1 agent error (a body fragment). Root reviewed the one added condition, wrote the
branch back as the agent (`my.program/write-branch!`, 367 ms) and committed d3de85d72; proof and reaching test green on
HEAD (run 1b79dec1c743, 32 pass). Gaps it exposed, both on the happy path:
1. The agent never proposes the merge: the loop stops calling the model once the proofs are green, so
   `my.program/propose-merge!` never runs and `prepare-merge!` has nothing to take. The end message to root
   (DYNAMIC-CONTEXT stage 3) is the merge request.
2. `bin/lane commit --branch-evidence` treats a `.cljc` file as ineligible and falls back to `bin/seon check` with no
   tests named, so the commit was "proven" by boot and lint alone. The fallback must run the tests reaching the change.

**The loop's first links landed (2026-10-10, lane dc-lifecycle; DYNAMIC-CONTEXT stages 1–3).** The block names the
agent's namespace and teaches that locals need their bindings (34bbd83cb); an agent closes when its task ends, so a
wake never reopens a finished task (f8b2a26b6); a task that ends sends root one message of references (`:seon.task/end`,
`:seon.task/merge` when green, the task as `:seon.message/about`), the green end records the agent's proposal, and root's
block derives `:seon.task/ended` (proofs, errors by signature, calls, tokens, next operations) at render (a9feb9ba4).
With lane sci-tests (e361536a9): non-root agents' tests run interpreted, never loaded into the shared JVM; jvm mode is
root's. Next: chain run 5, the whole loop with no root rescue (task from lane `real-task-pick-2`); then sci-tests slice 2
(`seon.task/assign!` runs the proofs in the agent's own environment).

**Chain run 5 (2026-10-10, agent `chain5-search-1`): THE WHOLE LOOP, NO ROOT RESCUE.** Task
`my.program/a-search-snippet-keeps-the-whole-line` (lane real-task-pick-2, 340462543): DeepSeek flash green in 4 calls,
9.3 s, 13.7k prompt tokens, 0 errors, 0 repeated forms (the namespace line removed the `in-ns` repeats). Its one end
message reached root (`:seon.task/end :done`, merge task 57f4bf2e4fac); root's block derived `:seon.task/ended`; root
ran `prepare-merge!` (gate run 74e4621e2005 green, 4.0 s, 1.1 GB), `accept-merge!` (main 6aca2052, 1.0 s, 1.4 GB) and
`write-back!` (213 ms), reviewed the one-line diff and committed 3c4f94227 by branch evidence. Root's only acts were
review and the merge decisions. Findings (lane chain5-findings files them with proofs): the cost diff lists unchanged
interpreted definitions; `jvm-evaluate`'s self time compares different forms; a test wrote into the live root inbox;
merge cost. Next: run the loop on several tasks, measured round over round (the competency table), and automate root's
review step.

**Interpreted tests are not yet faithful (2026-10-10, lane sci-tests, bff46841f, `interpreted-tier.md`).** Of 846 tests
executed interpreted as a non-root agent (partial tier, 600 s cap), 205 are red only when interpreted: 50 redefine Vars
(the tests break the law; census task), 25 need a `dev/` namespace, 20 use a `def` that failed to interpret as a value,
17 find SCI `*out*` unbound, 14 `await-event!`, 10 `&env` macros, 7 `proxy`, 46 unclassified. Since e361536a9 agents'
reaching tests run interpreted, so these are false reds in agents' replies: lane `interp-fidelity` fixes the
interpreter classes largest first. Lane `assign-proofs` builds `assign!` running the proofs in the agent's own
environment.

**Loop round 1 (2026-10-10, three DeepSeek-flash agents in parallel, tasks aeaf6f1fc): 2 of 3 green and merged
(a4b7e6261).** `r1-path-form` (5 calls, 9.2 s, 0 errors) and `r1-search-limit` (2 calls, 6.8 s) ended green; each end
message was root's merge request; root reviewed, prepared (gate runs f895519d7509, 04fe54058fd5), accepted and wrote
back; proofs green on HEAD (run d9089653c740). `r1-ref-section` diagnosed its bug correctly, then the turn loop stopped
calling it (`:waiting`, admission open) and root got no message: a stalled working agent and a silent end (lane
assign-proofs, queued). The commit gate's `.cljc` fallback ran no tests again (lane `lane-gate-cljc`). Round cost:
prepare 6.2 s / 3.2 GB for two one-function merges (task filed).

**Loop round 2 (2026-10-10, tasks from the loop's own findings, 9a5324489): 1 of 3 green, 0 merged.** `r2-cost-diff`
fixed the cost diff's false entries (13 calls, 143k tokens) but the merge gate refused it (run 5649676309ec, three reaching
`seon.request-cost-test` tests red): the gate worked, the agent ended `:done` too early (proofs green, reached tests
red; lane assign-proofs fixes `seon.turn/task-end`). `r2-problems-prose` was unprovable: its census proof reads files,
which a branch cannot change. `r2-ref-section` capped (the task's second failure). Both capped ends reached root as end
messages. Task-design rules learned: a proof reads through the program, never files; a task carries the updates to
existing tests that assert the old behavior. Lane round2-refine diagnoses and refines all three for round 3.

**Loop round 3 (2026-10-10, refined tasks 1745f48cf, proofs run at assignment 7ce008211): 2 of 3 green, both tasks
that had failed before** (refinement works). `r3-ref-section` green on the third attempt (12 calls) once the intent named
both changes; `r3-problems-prose` green (10 calls) once the proof read the program instead of files; `r3-cost-diff`
capped with 1 of 4 proofs (a larger refined task). Neither green merged: a schema change cannot merge
(`prepare-merge!` "Only function, test and namespace declarations may change here", lane root-land), and problems-prose
conflicted with a concurrent landing in the same file (0b50e737d; rerun on the new base). Recurring tool misuse across
agents: `my.program/callers` given a symbol, `my.program/source` given a keyword; the tools should take what agents pass.

**How root keeps the goal moving (2026-10-10 stall review).** The day after the instrument landed, the goal test was
"next" for two hours and never started: it had no owner and no first step, each owner observation about the
instrument became a new instrument lane, root chores were prepended to the next list, and root idled after landings.
Standing rules:
- One lane slot always serves essential 2 or 3 (the goal), never the instrument; the head of "next" is always work in
  flight with a named lane, never a label.
- An instrument finding is filed and sequenced unless it surfaces false data (that blocks the goal, so it is fixed).
- Root chores (branch cleanup, measurements) run beside lanes, never ahead of goal work; after a landing root
  launches the next step in the same turn.
- Every lane names the essential it serves; at most two or three implementation lanes, on disjoint files.
- A finding is filed the same day with a red tagged proof, or as a row in an existing proof. A task without one is
  invisible to the schedule: E1's F9 (proof tests unresolvable in an agent's world) and H1 (prose read as code) were
  seen on 2026-10-08, left unfiled or filed proofless, and met again in chain run 2.
- Every chain run keeps its branch and ends with reflection: root wakes the agent with questions about why it acted
  as it did and records the answers. The first reflection found two bugs (an agent created before a restart could not
  be woken; a root question was treated as more task work).
- Errors are judged by their producer, not their submitter: the same signature across distinct agents is a Seon
  defect (lane `agent-error-signatures`); every checkable AGENTS.md law needs a test (audit `laws-without-proofs`).
- The REPL-only acceptance's host-bound half (31b442840 fell back to `--test`; `proven-file` unreached) still gates
  new platform machinery.
- Lanes delete with plain `rm`, never `git rm`, and leave nothing staged; a whole-namespace `--test` on a namespace
  with declared-long tests is refused (name the vars); a dependency bump lands last in a batch, then a restart.

**Loop round 4 (2026-10-10 evening, root's report after the audit): 0 of 2.** `r4-problems-prose` (the round-3 green rerun
on the new base after the same-file conflict) ended `:stalled` at 5 calls (the loop stopped calling the agent before its
proofs passed; the `:stalled` end of 56d2efa61 reached root). `r4-cost-diff` was capped with 12 errors, the task's third
failure. Root's decision: no new loop rounds until the landing is exact (plan §0).
