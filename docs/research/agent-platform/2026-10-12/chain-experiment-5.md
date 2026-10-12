---
title: "Chain experiment 5: a contract task caps in 40 s at 15 calls without a defn; nothing reaches the merge pass"
type: research
status: complete (lane chain5, 2026-10-12); no merge, no publication; the subject task is left :held on default
created: 2026-10-12
---

# Chain experiment 5

**Result.** One single-Implement step ran on default (archive **a221ad569**, restarted and reset, DeepSeek flash) on
`seon.render.transcript/contract-runtime-duration`, and **did not merge**. The attempt hit `:capped` after 15 model
calls in 40 s (12:53:07 to 12:53:47 UTC), 143k prompt tokens (106k cached, 74 %), 2.5k completion tokens. It never
wrote a `defn` of the subject. **Zero hand acts after assignment**: root touched nothing until the attempt was over.
db<->flow behaved as designed. Its pass copied the `:capped` end to main 41 ms after the branch end (chain 4's G8 is
gone) and wrote root's problem task. Then it stopped, because `:capped` is a terminal end with no relaunch (M12). The M8a
path (`:merge` → `:publish` → `:delete-branch`) was **never reached**, so this run neither proves nor refutes it.

Against chain 4's 17 hand acts: **0 performed**. A merge would have needed **at least 2**: a decision on the contract's
nil input, written into the intent, and a relaunch (the `:capped` end has no reader). Chain 4 had the same two needs.

Authorities: AGENTS.md "The goal"; [chain experiment 4](chain-experiment-4.md) (method, J/G frictions);
[M8a notes](m8a-notes.md) and commit 7f580e895 (what is automatic); [chain queue](chain-queue.md).

## Subject selection (step 1)

Each candidate was checked with `my.test/check {:seon.test/identities #{…}}` on probe agent `chain5-probe`'s own branch,
in SCI mode.

| Candidate (task → proof) | Interpreted check | Verdict |
|---|---|---|
| `seon.context/a-message-to-the-agent-is-not-in-the-model-context` → `seon.context-test/unread-shows-each-undelivered-item-once-with-its-identity` (chain queue #3) | **green**, 5 pass, 3.8 s, 6.1 GB allocated | rejected: already green (the queue's "inferred" red is stale) |
| queue #1 `seon.render.web/…executor…` | not run | rejected: merged at fec3d7737 (chain 4) |
| queue #2 `my.program/a-function-called-through-its-callers…`, #4 `my.task/a-send-back-with-positional…` | not run | rejected: no task entry and no tag (unchanged since the queue) |
| `my.program/a-search-limit-is-a-positive-integer`, `…/a-search-snippet-keeps-the-whole-line` | green, green | rejected |
| `seon.program/edn-symbol-text-accepts-a-leading-slash` | green | rejected |
| `seon.print/a-path-form-reads-any-plain-key` | green | rejected; it is also the printer |
| `seon.ref/a-reference-with-no-path-is-a-missing-file` | **red**, 0.4 s | rejected: its intent requires `seon.schema/register!` of `:seon.ref/section-error` (chain 4: avoid J3/J4/J5), and two earlier rounds failed it |
| `seon.render.web/contract-query-params`, `seon.fs/contract-attributes` | red (fail, not error) | rejected: `query-params` is web-handler code and `attributes` is a host-interop file read; the pure one ranked first |
| **`seon.render.transcript/contract-runtime-duration`** → `seon.contract-coverage-test/runtime-duration-is-contracted` | **red, failing**: `seon.contracts/gaps` answers one `:seon.fn.contract.finding/missing-spec` row, 0.4 s | **picked**. The owner is a 13-line pure private function, `:seon.fn/host-bound? false`. Its file `src/seon/render/transcript.clj` exists, is unclaimed and is clean |

The candidates came from intersecting the `resources/seon/tasks/*.edn` task names with `:seon/tasks` tags in `test/`.
Platform namespaces were excluded.

## Setup

- Default: pid 81386, booted 12:48:38 UTC on a221ad569 and reset. Health `:degraded`, with one cache discipline warning
  (`seon.sci.eval/program-context`, hit 33 %). `:seon.config.fs/working-root` = `/Users/sean/src/seon`, the live checkout,
  so a publish would have committed into this repository.
- Config, as in chain 4 (`seon.config/apply!`): `:seon.config.ai/no-provider false`, `:seon.config.run/max-episode-runs
  15`. Restored to `true`/`100` at the end, verified with `seon.config/effective`.
- Launch: one root transaction, `[:db/add [:seon.task/id "cde64280edb8"] :seon.task/execution :running]`. No
  instructions were written, so the agent saw the shipped intent only. db<->flow's next pass did the assignment by
  itself (`seon.cluster.agent/next-actions` `:attempt` + `:start-flow`, which writes the attempt row as
  `seon.task/assign!` does): assignment `0a79a6fd21a1`, agent `attempt/0a79a6fd21a1`, branch `:attempt-0a79a6fd21a1`,
  merge base written. `seon.task/assign!` called by hand needs an existing agent and branch, which is the hand-assignment
  path, so the ordinary path for a model agent is `:running`. Before launch, `seon.cluster.agent/plan-facts` over `d/with`
  of that datom answered `:startable #{"cde64280edb8"}`.

## Timeline (UTC)

| Time | Event |
|---|---|
| 12:53:06.950 | `:running` written |
| 12:53:08.129 | first model call (prompt 1,703 tokens) |
| 12:53:08-31 | 11 calls reading: source, the contracts guide, `doc gaps`, callers, both callers' source, schema lookups, three probe calls of `runtime-duration` (nil nil → "Duration unavailable"; two Dates → a string) |
| 12:53:34-41 | three `seon.schema/register! :seon.render.transcript/instant-or-absent` tries: `[:or :inst [:= nil]]`, `[:or :inst [:enum nil]]` (both refused as "got a vector"), then `[:or :inst :nil]` (refused `:seon.schema/nilable-value-schema`) |
| 12:53:43 | `defn probe-maybe` with `[:maybe :inst]` in its scratch namespace `my.attempt-0a79a6fd21a1` (admitted), then a wrong-arity `seon.fn/contract-findings` call |
| 12:53:47.366 | db<->flow `:close`: root's problem task `attempt-ended-0a79a6fd21a1` |
| 12:53:47.405 | `:capped` end on main |
| after | nothing: no relaunch and no merge. The branch is kept (by design, for a non-merged end) |

**Per pass** (`seon.profile/summary` after the run, covering about 31 passes since boot): `plan-facts` 31 calls, total
510 ms, max 50 ms; `lifecycle-facts` 31 calls, max 16 ms; `next-actions` 0 ms. Every pass is far under a second.

## The attempt

| | |
|---|---|
| Calls | 15 (all `finish-reason "stop"`), about 2.6 s per call |
| Tokens | prompt 143,471 / cached 106,496 (74 %) / completion 2,506 |
| Evaluations | 20 forms over 15 replies, every one under 130 ms |
| End | `:capped` (`:seon.task.attempt/commit` `6accd85b-…`), copied to main by db<->flow |
| The code | **none**. `seon.render.transcript/runtime-duration` on the branch is byte-identical to HEAD |
| Stray on branch | `my.attempt-0a79a6fd21a1/probe-maybe` (a scratch-namespace defn) |

Errors by signature:

| Signature | Count |
|---|---|
| `:seon.cluster.reply/no-forms-error` (prose-only reply, turn 2) | 1 |
| `Unable to resolve symbol`: invented `render-runtime-html-runtime-duration-probe`, guessed `seon.schema/registered`, `db/db` (no alias in the agent's namespace), `seon.db/connection` | 4 |
| `seon.schema/register!` "expected :seon.schema/definition (a parseable, EDN-readable Malli form), got a vector" for `[:or :inst [:= nil]]` and `[:or :inst [:enum nil]]` | 2 |
| `seon.schema/register!` `:seon.schema/nilable-value-schema` | 1 |
| `seon.fn/contract-findings` refused argument 1 (a symbol where a map goes) | 1 |
| recorded faults | 0 |

What capped it: the model found the real question and never resolved it. `runtime-duration` really receives nil
(`render-runtime-html` passes `since`, a Date or nil, and the body answers "Duration unavailable" for any non-inst).
The contracts guide bans `[:maybe …]` in contracts, and registering a nilable name is refused. So the model spent 9 of
15 calls looking for a sanctioned way to say "an inst or nil" at a positional argument. The cheapest admitted
contract, an inline `[:=> [:cat [:or :inst :nil] [:or :inst :nil]] :string]` on the `defn`, was never tried.

## Hand acts

**After assignment: 0.** Cleanup after the run: task → `:held`, config restored, probe branch `:agent-b3512ebe69b5`
retired. Like chain 4's act 17, these are not counted.

What root would have needed for a merge (not performed):
1. Decide the contract of a positional argument that may be nil, and refine the intent with it. This is the decision a
   skipped Design step would make. Chain 4's act 5 was the same kind.
2. Relaunch the capped task: a send-back rewrite of the end and a pass, or a new attempt. `seon.task/ready` excludes a
   task whose attempt ended `:capped`, and nothing reads `:seon.task.attempt/relaunch` (M12).

## Where it stalled, by derived action

All read in JVM mode from `seon.cluster.agent/lifecycle-facts` and `next-actions` source over main:
- `:merge` did not fire: `merge?` requires the end in `merged-ends` (`#{:done :sent-back}`), and the end is `:capped`.
- `:close` fired as designed (end and problem task).
- `:delete-branch` will never fire for this assignment: it also needs `merge?`. `:attempt-0a79a6fd21a1` stays until
  root retires it.
- No new `:attempt`: `seon.task/ready`'s `not-join` drops a task whose attempt ended `:capped`/`:stalled`/`:given-back`/
  `:cancelled`/`:error`.

The stall is the designed behaviour of the terminal class. The open owner task is
`seon.cluster.agent/a-capped-attempt-has-no-relaunch` (M12), not a misfiring action.

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| K1 | `(seon.schema/malli-form? '[:or :inst [:= nil]])` → false, and `[:or :inst [:enum nil]]` → false, while `[:or :inst :nil]`, `[:maybe inst?]` and `:inst` → true. So `register!` answers "got a vector" (a type complaint) for valid Malli. The real refusal (nilable) shows only for the `:nil` spelling. The model read it as an EDN parse problem (turns 13-14). Same family as chain 4's J4. | `seon.schema/malli-form?` | **New**: `seon.schema-test/a-nil-literal-schema-is-a-malli-form`, `(is (seon.schema/malli-form? [:or :inst [:= nil]]))`. Red today (false, measured above) |
| K2 | The generated contract-task intent (`seon.contracts/task`) says "Never widen to :any, [:maybe ...]". The contracts guide bans `[:maybe …]` in contracts, and registration bans nilable names. None of them says what a function whose positional input is really nil declares. 9 of 15 calls went to this question. | `seon.contracts/task` (the intent it generates) and `docs/seon/architecture/contracts-guide.md` | Owner ruling first: is inline `[:or X :nil]` at a positional slot the sanctioned form? Then the proof is a row in the existing contract-findings table: `[:=> [:cat [:or :inst :nil]] :string]` answers no finding. The intent then names the form whenever the observed callers pass nil |
| K3 | `:capped` is terminal: no relaunch; the branch is kept forever. | `seon.task/ready`, `seon.cluster.agent/next-actions` | existing `seon.cluster.agent/a-capped-attempt-has-no-relaunch` (M12), met again |
| K4 | The `:close` problem task for a capped attempt that changed nothing says "Merge it with (my.root/merge! …)". There is nothing to merge, and a merge would carry only `probe-maybe` from the scratch namespace. | `seon.cluster.agent/ended-problem!` | **New**: the problem task of a `:capped` end whose branch holds no program change offers relaunch or retire, not merge. Proof in `seon.cluster.db-flow-test`'s lifecycle table: a capped, unchanged row's problem intent names no `my.root/merge!` |
| K5 | One call lost to a prose-only reply (`no-forms`). Turn 1's fenced Markdown blocks were evaluated. | DeepSeek reply shape; `seon.turn/continuing-reply?` | met once; F7's family |
| K6 | `db/db` is unresolved in the agent's namespace `my.attempt-0a79a6fd21a1`, but the shipped docstrings and test bodies the agent reads use `db/` and `program/` aliases. | `seon.context` (the start namespace the attempt is given) | met once, not filed |
| K7 | `(seon.cluster.agent/lifecycle-facts d)`, called by root with 1 of 3 arguments, was refused as `:malli.core/invalid-input` with the message "class datahike.datom.Datom cannot be cast to class java.util.Map$Entry". Its `:seon.error/offending` shows default filling had supplied argument 2 (the store) and **filled argument 3, `:seon.flow.plan/registry`, with a database value**. AGENTS.md: "structural resemblance is not identity". | the instrumented wrapper's default filling (`seon.instrument`), at the `:seon.flow.plan/registry` slot | **New**: a test that calls a contracted function missing a registry argument refuses by name (`:seon.flow.plan/registry` missing), never fills it with the bound database |
| K8 | Health: `seon.schema/call-with-projection-state` 96 calls, 48.9 s self, max 4.2 s since boot. The interpreted probe checks (6.1 GB for one 1-test check) ran in this window. | `seon.schema/call-with-projection-state` | recommended to the cost queue with the probe form `(my.test/check {:seon.test/identities #{'seon.context-test/unread-shows-each-undelivered-item-once-with-its-identity}})`: 3.8 s, 6.06 GB allocated, 1 test |

## Against chain 4

| | Chain 4 (46a1c4f06) | Chain 5 (a221ad569) |
|---|---|---|
| Subject | `seon.render.web/start!`/`stop!` + schema member | `seon.render.transcript/runtime-duration` contract |
| Attempts / calls | 5 / 69 | 1 / 15 |
| Tokens prompt / cached / completion | 884k / 661k / 18.0k | 143k / 106k / 2.5k |
| Hand acts after launch | 17 (config counted) | **0** |
| End copied to main without a hand pass | 0 of 4 | **1 of 1** (41 ms) |
| Code right on some branch | yes | no |
| Merge request / merge / publication / branch deleted | none / no / no / no | none / no / no / no |
| What would have unblocked | design decision + relaunches | design decision (K2) + relaunch (K3) |

## What the next run changes

- Pick a subject whose fix needs no contract design: a proof that pins an exact behaviour, so the Implement step has
  nothing to decide. Or refine a contract task's intent with its input shapes first (K2's ruling).
- Merge M12 (relaunch of capped attempts) before chain 6. Every chain so far has hit a cap. With no relaunch, a capped
  attempt reaches no merge pass.
- M8a's merge → publish → delete path is still unexercised on default. The first `:done` end will be its first live run.

## Residue on default (disposable)

Task `cde64280edb8` `:held` with one `:capped` attempt; problem task `attempt-ended-0a79a6fd21a1`; branches
`:attempt-0a79a6fd21a1` and `:base/attempt-0a79a6fd21a1`, holding only `probe-maybe`. Probe branch `chain5-probe`
retired. No git commit by Seon: nothing was published.
