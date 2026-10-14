---
title: "Design prototype, run 1: the Design stage as one ordinary task on default de1f48c23. A DeepSeek agent wrote a correct What exists and Approach for the seon.ref/section pathless-reference defect in 12 model calls and 41 s with no hand act, and its proof went green on its branch. The merge then refused :red-on-target, because the merge candidate carries program declarations only, not the task's refined intent, so the proof stayed red there. The proof also passes weak work. Nothing merged; the merge request waits for root"
type: research
status: complete (lane design-proto, 2026-10-14); attempt :done, merge refused :red-on-target, task set :held after the run
created: 2026-10-14
---

# Design prototype, run 1: `seon.ref/design-a-pathless-reference-section`

**Broken first.**

1. **The merge cannot accept a Design step's work (binding).** Attempt `a7004ec2ee33` ended `:done` at 03:32:50.632 UTC.
   At 03:32:50.463 the merge recorded `:seon.task/refusal :seon.source/red-on-target-error` on the assignment. The
   proof is green on the agent's branch: run `bdf9cdb492ed`, 3 passing, 0 failing, at 03:32:49.553. On main it is
   red: run `15ce6d7bee8b`, 2 passing, 1 failing. The cause is in `seon.cluster.source/prepare-merge!`. Its `delta`
   is the program three-way comparison of the branch against main (`compare-branch` → `three`). Tasks are not
   program, so the refined `:seon.task/intent` is not in the merge candidate. The proof reads the task on the
   candidate (`seon.test/task` → `seon.test.runner/task-input` on the request's database), finds only main's Problem
   section there, and fails. Main holds the same red, so the verdict is `:red-on-target`: "root decides". The
   consequence: every Design task, whose only product is task text, will end like this until the candidate carries
   the task's own refinements.
2. **The proof accepts weak work.** I checked the same three predicates in JVM mode over hand-written intents. Each
   of these passes all three:
   - Problem, then `## What exists` reading only "`seon.ref/section`.", then `## Approach` reading "Fix it.";
   - a rewritten Problem;
   - a Problem that already cites `:seon.ref/section-error`.

   The schema check was green before the agent started (run `15ce6d7bee8b`: pass 2, the schema assertion among
   them), because the Problem names the schema.
3. **Filing gaps (before launch, not the agent's).**
   - `bin/lane` cannot commit a proof that is red by design. With `--test` naming it, check refuses the red. With no
     `--test`, the lane refuses ("a check that runs no test proves nothing"). `--branch-evidence` lists it as
     `unproven`.
   - A deftest's `:seon/tasks` tag must name a filed task. Check refused the test alone with `:dangling-id`.
   - A committed test does not reach default's main without a restart, so I defined it on main as root by hand. The
     first try recorded a wrong namespace entity: the agent default requires (`my.message my.turn seon.db
     seon.program seon.bootstrap clojure.test`), none of the authored ones, so `str/` and `ref/` did not resolve. I
     removed it with `my.program/remove-ns!`. The same forms then worked. This is unexplained and was not
     reproduced on a fresh name on an agent branch.

**What went right.** The agent made no hand act and spun nowhere. The F69 live-lock of Run 25 did not recur: tagging
the task itself gave the runner one task input. The agent found the producer on its second call, the true callers
and the tests on its third. Its Approach matches the fix the owner verified at the REPL on 2026-10-10 (filed in
`resources/seon/tasks/seon.ref.edn` under `a-reference-with-no-path-is-a-missing-file`). It could not read that task
(`my.program/source` refuses a task name), so it reached the same fix on its own. It defined no program code: no stubs
and no `defn`.

## Setup

- **Default**: pid 10712, booted 02:58:28 UTC from `data/source/de1f48c23…`. Health `:degraded` (warnings). This
  lane did not stop or reset it.
- **Defect, re-verified in JVM mode**: `(seon.ref/section "#the-goal")` throws `:seon.instrument/contract-error`,
  "seon.ref/locate refused argument 0 `path` … Called from seon.ref/section (ref.clj:618)".
- **Task**: `seon.ref/design-a-pathless-reference-section`, handle `d8713b2acf86`, created on main by root with
  `seon.task/create!` and filed identically in `resources/seon/tasks/seon.ref.edn` (verified equal).
  - Intent: only `## Problem` (the defect and the form that shows it).
  - Instructions: TASK-CHAIN §4 Design's text, with `my.task/refine! {:seon.task/intent whole-new-intent}` in place
    of `update!` and "intent" in place of "plan".
- **Proof**: `seon.design-prototype-test/a-pathless-reference-section-is-designed` in
  `test/seon/design_prototype_test.clj`, tagged with the task itself. It reads `(seon.test/task)`'s effective intent
  and asserts:
  - the `problem`, `what-exists` and `approach` anchors (`seon.ref/document`) are present and not blank;
  - no `seon.ref/references` entry carries `:seon.ref/problem-kind`;
  - at least one resolved `:seon.schema/key` is among the references.
- **Red, confirmed**:
  - candidate check run `96362157c82b`: pass 2, fail 1, actual `["what-exists" "approach"]`;
  - main run `15ce6d7bee8b`, the same.
- **Commit**: `69dc3c429`, test plus task file. Check JVM green on
  `seon.ref-test/a-missing-heading-reply-names-the-nearest-heading`, run `9d9ba50789c6`; lint 0 errors.
- **Pre-launch gate** (agent `design-proto-gate`, then retired): `my.test/check` over the three tests reaching
  `seon.ref/section` and `seon.ref/section-error` that are not the defect's own red proofs, plus the proof. Run
  `d7fad629713e`, 976 ms, 744 MB:
  - `a-missing-anchor-names-the-closest-spelled-heading-first`: 2/0/0;
  - `a-missing-heading-reply-names-the-nearest-heading`: 1/0/0;
  - `a-referenced-doc-shows-its-heading-size-and-one-read`: 15/0/0;
  - the proof: 2/1/0, red as designed.
- **Config**: `apply!` `{no-provider false, max-episode-runs 40}` gave 4 operations, `converged? false` (C3).
  Effective values were confirmed, model `deepseek-flash`.
- **Launch**: `[:db/add [:seon.task/id "d8713b2acf86"] :seon.task/execution :running]` at 03:32:09.62 UTC. This was
  the only act after which the run was hands-off.

## Grades

| Measure | Value |
|---|---|
| Hand acts after launch, until the attempt ended | **0** |
| Hand acts after it ended | 1: `:held` at 03:38:18, so a later close or relaunch cannot spend unattended; the merge request is unaffected |
| Model calls | **12** (cap 40), all `deepseek-flash`, finish `tool_calls` |
| Tokens, first call | 7,438 prompt / 63 completion |
| Tokens, total | 217,439 prompt (130,816 cached, 86,623 uncached), 4,751 completion; prompt grew from 7.4k to 24.5k per call |
| Wall time | 41 s (launch 03:32:09.6 → first call 03:32:10.1 → last call 03:32:43 → `:done` 03:32:50.6) |
| Evaluations | 38 forms by the agent, each 0 to 90 ms; the two `refine!` calls 90 and 85 ms |
| Relaunches / send-back | 0 / 0 |
| Merge | refused `:seon.source/red-on-target-error`; `my.root/merge-requests` lists it for root |

## The agent's reads, in order

1. `(seon.context/current-state)`.
2. `(in-ns 'seon.ref)`, then `my.program/source` of `seon.ref/section`, `seon.ref/locate`, `seon.ref/section-error`
   and `seon.ref/nearest-files`.
3. `my.program/source` of `:seon.ref/path`, `:seon.ref/reference-text` and `:seon.ref/section-missing`;
   `(my.program/callers 'seon.ref/section)`; `(my.program/tests-reaching 'seon.ref/section)`.
4. `my.program/source` of the two red `seon.ref-test/a-reference-with-no-path-*` tests, `seon.ref/resolve-path`,
   `:seon.ref/section` and `:seon.ref/nearest`.
5. `(my.program/source 'seon.ref/a-reference-with-no-path-is-a-missing-file)`, a **task**. It was refused: "No
   program declaration, namespace or schema names that subject". Then `tests-reaching` `seon.ref/resolve-path` and
   `:seon.ref/candidate`.
6. It reproduced the throw; read `:seon.ref/section-error`, `:seon.ref/section-missing` and `my.fs/section`.
7. It read `:seon.ref/anchor`, `:seon.error/base`, `:seon.ref/location` and `:seon.ref/file`, then made its first
   search: `(my.program/search "section-error")`.
8. It read `seon.ref/based-file`, evaluated `(seon.ref/nearest-files "")` (the five root `.md` files) and
   `(seon.ref/based-file "")`, and read `seon.error.refusal/diagnostic`.
9. First `refine!`. It answered `:my.task/problems [:seon.error/offending :unresolved]`.
10. `(my.program/search "offending")`, `:seon.error/offending-projection`, `:seon.error/offending` (not found), and a
    Datalog check that it is no schema key.
11. Second `refine!`, with the reference dropped. Green.

It found the true producer (`seon.ref/section`), the value's real origin (`locate`'s `:seon.ref/path` contract), both
callers (`my.fs/section`, `seon.ref/resolve-path`, matching `my.program/callers`), the five tests and the schemas
(`:seon.ref/section-error`, `:seon.ref/path`, `:seon.ref/reference-text`, `:seon.ref/section-missing`,
`:seon.ref/anchor`, `:seon.ref/nearest`). It never read `docs/seon/architecture/vocabulary.md`.
`my.program/search` came late, twice, as a confirmation: the must-reads and `callers` had already led it to the
owner.

## What it wrote

On its branch, the intent's 19 references all resolve. One `:seon.ref/problem` resolves but is not involved.

> ## What exists
>
> The producer is `seon.ref/section`, `[:=> [:cat :seon.ref/reference-text] [:or :seon.ref/section :seon.ref/section-error]]`. It derives `path` by `(subs reference 0 hash-at)` and `anchor` by `(not-empty (subs reference (inc hash-at)))`, then `(locate path)`. The refusal comes from `seon.ref/locate`, `[:=> [:cat :seon.ref/path] [:maybe :seon.ref/location]]`, whose input is `:seon.ref/path`, `[:string {:min 1 …}]`. `seon.ref/section` passes `path` directly to `seon.ref/locate`, `seon.ref/nearest-files` (`[:=> [:cat :string] [:vector :string]]`) and `seon.ref/section-error` (`[:=> [:cat :keyword :seon.ref/path [:maybe :seon.ref/anchor] :seon.ref/nearest] :seon.ref/section-error]`); both … declare `:seon.ref/path` as a non-empty string even though an anchor-only reference carries no path.
>
> … `:seon.ref/section-error` … Its `:seon.ref/path` is required, but a pathless reference has no path to report, and the tests below require the key to be absent (absence is no key).
>
> Callers of `seon.ref/section` are `my.fs/section` …, which passes through, and `seon.ref/resolve-path` …, which reads `:seon.ref/section-missing` to build a `:seon.ref/problem` and so depends on the missing-file answer, not an exception.
>
> The tests that cover it are `seon.ref-test/a-reference-with-no-path-is-a-missing-file` and `seon.ref-test/a-reference-with-no-path-answers-an-error-that-holds-no-path`; both are gated to `seon.ref/a-reference-with-no-path-is-a-missing-file` and are red now. `seon.ref-test/a-referenced-doc-shows-its-heading-size-and-one-read` and `seon.ref-test/a-missing-heading-reply-names-the-nearest-heading` cover the pathful cases that must keep answering as they do.

> ## Approach
>
> Data first. Change `:seon.ref/section-error` so `:seon.ref/path` is `[:seon.ref/path]` optional (registered on this branch with `seon.schema/register!`), because a pathless reference has no path and absence is no key. `:seon.ref/section-missing` (`[:enum :file :heading]`), `:seon.ref/anchor` (`[:string {:min 1}]`) and `:seon.ref/nearest` stay as they are. No new schema is needed.
>
> Then change `seon.ref/section-error` to take a path that may be absent, so it omits `:seon.ref/path` from the error when there is none: keep its arity 4 and contract over named schemas, using a `[:maybe :seon.ref/path]` `path` argument and `cond->` to assoc the path only when it is non-nil.
>
> Keep `seon.ref/locate` … unchanged: it still receives only real paths. In `seon.ref/section` …, bind the derived path as `(not-empty (cond-> reference hash-at (subs 0 hash-at)))` so a reference with no path holds `nil`, and only call `seon.ref/locate` when the path is present; a nil path falls straight through to the `:file` branch, `(section-error :file path anchor (nearest-files (or path "")))`, with `seon.ref/nearest-files` receiving the checkout root's Markdown. No function is added or deleted.

**Judgement.** The Approach names the right change. It is the owner's verified two-part fix:

- the producer `section` stops calling `locate` with `""`;
- the error schema and `section-error` make `:seon.ref/path` optional, its absence being no key.

The facts I checked against what it read are correct:

- contracts and `[:enum :file :heading]`;
- callers equal `my.program/callers`;
- `nearest-files ""` gives the root's Markdown.

Two flaws:

- It **edited Problem**, against "Keep `## Problem` as it is", appending a paragraph of analysis.
- Its schema change is described in prose, not as the `seon.schema/register!` form the instructions ask for ("registered
  on this branch"). It registered nothing. This is harmless for a design, but the instruction and the behavior
  disagree, and the proof does not notice.

## Did the proof judge it right?

- **Green only on good work in this run**: yes. Red after the first `refine!` (one unresolved reference), green after
  the second.
- **Red on good work**: once, arguably. `:seon.error/offending` is a real member written into error maps (here by
  `seon.ref/section-error`), but it is no registered schema key, so the resolver calls it unresolved. The agent took
  two calls to find that and removed the citation. Better: the instruction says what to do with such a key.
- **Green on weak work**: yes, as shown under "Broken first", item 2. Checks 1 and 3 test presence, not content.
  Check 3 is vacuous whenever Problem names a schema, which a well-written defect report does.
- **On the merge candidate**: red on good work, every time (item 1). The judgement the proof made on the branch is
  not the one the merge makes.

## The context it saw

- **The first model call** (7,438 prompt tokens) did not show the block's contents: the agent's first form read it.
  `(seon.context/current-state)` printed 19,361 characters, an estimated 6,050 tokens (`seon.ai.tokens/estimate`).
  The prompt stored for that call is not kept (a model call stores its basis), so its contents are inferred from the
  second call's growth to 12,971.
- **What the block held**:
  - The task, with the intent and the instructions verbatim.
  - `:proofs`, with the proof's source and its four references (`seon.db/db`, `sections`, `seon.ref/references`,
    `seon.test/task`), plus the failure's data: actual `["what-exists" "approach"]`.
  - `:step`, `:seon.task/status :working`.
  - The must-reads:
    - `seon.ref/section`'s source, lines elided mid-form;
    - `:seon.ref/section-error`'s form;
    - **the whole sources of the tools the instructions cite**: `my.program/search` (cut mid-form),
      `my.program/source` and `my.task/refine!`, with their nested references;
    - `docs/seon/architecture/vocabulary.md` as a section reference.
- **What helped**: the proof's source and its failure data. The agent stated its job correctly from them on call 2
  ("rewrite the intent … with `## What exists` and `## Approach`"). The `seon.ref/section` must-read took it straight
  to the owner.
- **What misled, or was noise**:
  - The `:step` held `:seon.source/refusals`: a `:seon.source/red-on-target-error` from `accept-merge!`, timestamped
    03:32:12, before the agent had done anything, naming run `15ce6d7bee8b`. That is `seon.context/step-observation`
    previewing the merge verdict, not an actual merge. The agent ignored it. A first block that announces a refused
    merge before any work is wrong context.
  - About half the block's must-reads are tool sources (`search`, `source`, `refine!`) rather than the subject: §3a's
    doc views would cut this.
  - `my.program/source` on a task name is refused with a fix that never mentions how to read a task.

## The end

- The attempt's end, `:done`, triggered `:merge`. `seon.merge/merge!` refused it (`:red-on-target`) and wrote the
  refusal on the assignment. No relaunch followed: `ready #{}`, one attempt.
- **Nothing merged.** The branch holds no program change, only the task's refined intent and the agent's evaluations.
- Root's options, from `my.root/merge-requests`: `my.root/merge!` or `my.root/close!` for assignment `a7004ec2ee33`.
  This lane took neither, because merging is root's.
- Merging as-is would have an empty program delta. Whether the refined intent reaches main through that path is the
  open question of item 1.

## Proposed edits to TASK-CHAIN §4 Design, each tied to an observation

1. **The merge must judge the task as the branch holds it** (item 1). Add to §2/§4: the merge candidate carries the
   assignment's own task refinements (the members `my.task/refine!` wrote on the branch). Equivalently,
   `prepare-merge!`'s `delta` includes the tracked task facts beside the program three-way comparison. Without this,
   every Design ends `:red-on-target`.
   - Red proof: a merge test where a branch changes only its task's intent to satisfy a task-reading proof. Today it
     refuses `:red-on-target`; it must be accepted.
2. **Check 3 reads What exists and Approach, not the whole intent**. This replaces "no resolved schema key among the
   plan's references" with "no resolved schema key among the references of `what-exists` ∪ `approach`, and no
   changed `:seon.schema/key`". Observation: green from the start because Problem cited `:seon.ref/section-error`.
3. **Add `:problem-changed`**. Problem's text must equal the creator's (the task's declaration at the merge base).
   Observation: the agent appended to Problem against its instruction, and nothing refused it.
4. **Add `:producer-not-cited`**. What exists must cite at least one first-party function (`:seon.fn/sym`) and at
   least one deftest (`deftest?`) that `seon.fn/tests-reaching` that function, and Approach must cite at least one
   first-party function. This uses `cited-fns`, already in the shared helpers. Observation: "`seon.ref/section`." /
   "Fix it." passes today. This still cannot judge whether the Approach is right; that stays with Prove's red tests
   and Implement. It does refuse an empty design.
5. **Instructions, schema changes**: replace "any schema to add or change, registered on this branch" with "any
   schema to add or change, as the `seon.schema/register!` form you would evaluate (Implement evaluates it)". The
   agent wrote the change in prose and registered nothing, and for a design that is right: registering a changed
   `:seon.ref/section-error` on the branch would break `section-error`'s callers before Implement.
6. **Instructions, unregistered keys**: add "A key that is written into maps but is no registered schema (for example
   `:seon.error/offending`) is not a reference: write it without backticks." Observation: two calls spent finding
   this.
7. **Instructions, the stub sentence**: begin it with "When Approach adds a function". Observation: the agent
   correctly added no stub for a bug fix. Keep the stub only for new functions.
8. **Instructions, reading tasks**: name `my.task/current` for a task a test is tagged with. Observation: the agent
   tried `my.program/source` on the defect's task (which holds the verified fix) and was refused with a fix naming
   only functions, namespaces and schemas.
9. **Context** (§3a): render the instruction-cited tools as doc views, not sources. Do not put a previewed merge
   verdict in `:step` `:seon.source/refusals` until a merge has run. Observation: about half the 6,050-token block
   was tool source, and the first block showed a red-on-target refusal before any work.

## Filing findings (outside the Design stage)

| # | Observed | Where |
|---|---|---|
| D1 (binding) | Merge candidate omits task refinements; a task-reading proof is red there and the merge refuses `:red-on-target` | `seon.cluster.source/prepare-merge!` (`delta` from `compare-branch`) |
| D2 | No `bin/lane` path commits a red-by-design proof: `--test` refuses the red, no `--test` refuses, `--branch-evidence` says `unproven` | `bin/lane` |
| D3 | A test committed on HEAD is invisible to default's main until restart; a run lane must define it on main by hand | root restart batch / publication |
| D4 | Root's first SCI `ns` form for `seon.design-prototype-test` on main recorded the agent default requires instead of the authored ones; not reproduced after `remove-ns!` | `seon.ns` recording at the REPL (O1 class?) |
| D5 | `:seon.error/offending`, written into error maps, resolves as `:unresolved` | `seon.ref/references` vs unregistered map members |
| D6 | `my.program/source` refuses a task symbol and its fix does not mention `my.task/current` | `my.program/source`, `my.program/not-found` |
| D7 | `seon.context/step-observation` shows a merge refusal at the first block, before any work | `seon.context/step-observation`, `seon.context/step-verdict` |
| C3 | `apply!` `converged? false` (4, then 3, operations) | as before |
| own | My unbounded JVM datom scan over config attributes cost 2.3 s and 5.4 GB; it was my form, not a system defect | — |

## Residue on default (disposable)

- Task `d8713b2acf86` is `:held` (verified), with one attempt `:done` and the merge refused. Its branch
  `:attempt-a7004ec2ee33` is kept for root's merge request and as evidence for D1.
- `seon.design-prototype-test` was defined on main by root, matching `69dc3c429`.
- Probe agents: `design-proto-probe` (`:agent-e509239bfdfb`) and `design-proto-gate` (`:agent-0c5d472b5f61`) are
  retiring.
- Config restored: `no-provider true`, `max-episode-runs 100` (verified).
