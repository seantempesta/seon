---
title: The plan
type: plan
status: active
created: 2026-10-10
---

# The plan

One plan, revised in place. First written by lane fable-docs on 2026-10-10; rewritten by lane fable-replan-2 on
2026-10-11 from the owner's answers in [fable-replan](../research/agent-platform/2026-10-11/fable-replan.md) §8
(questions Q1–Q16), the [task verbs catalogue](../research/agent-platform/2026-10-11/task-verbs-catalogue.md), the
[owner decisions](../research/agent-platform/2026-10-11/owner-decisions.md) of the same morning and the TASK-CHAIN card's
two reviews (`schedule/cards/TASK-CHAIN.md` §8). Live facts were checked on default (archive c1230efc3, then
f51240f34) in JVM mode, read-only. Dated evidence lives in the research pages; this file holds direction and decisions.

## 0. Where we are, and the order (revised in place; last 2026-10-12, verified at the code of HEAD 015673f47 and corrected for 786915ca3)

**Where we are.** A DeepSeek-flash agent turns a small ordinary task red → green from the context Seon generates, and
root has merged three such changes by hand (d3de85d72, 3c4f94227, a4b7e6261). The first chain experiment ran on
2026-10-12 ([chain-experiment-1](../research/agent-platform/2026-10-12/chain-experiment-1.md)). The automatic merge
path (`seon.cluster.source/land!` from a green end, dial `:seon.config.root/land?` false in `config/default.edn`) has merged
nothing; merge requests are root's tasks (`my.root/merge-requests`, `my.root/merge!`, `my.root/close!`). The task
database holds **783 tasks** in 94 files (`resources/seon/tasks/`). **155** of them have at least one deftest tagged
`:seon/tasks` naming them; 180 distinct task symbols are tagged in `test/`, and **25 of those tags name no task** (stale
proofs). **628 tasks have no tagged proof.** Agents have `my.task/current` and `my.task/send-back!`; `my.task/refine!`
and `my.task/create!` do not exist yet. The fence revert, verification and database reset are done (§4).

Evidence for this section: [state-vs-docs](../research/agent-platform/2026-10-12/restart-audit/state-vs-docs.md) (phase
state by symbol, attribute and `git log`), [cache-hits](../research/agent-platform/2026-10-12/restart-audit/cache-hits.md)
and [request-cost](../research/agent-platform/2026-10-12/restart-audit/request-cost.md) (measured on the running default).

### Phase status (every later commit that moves a phase updates this table)

Three documents number their phases. To end the collision, `hybrid-agent-world` is cited with the prefix **AW**
(AW0–AW7), `hybrid-step-semantics` keeps **P1–P10**, `hybrid-push` keeps **U0–U7**, `hybrid-immutable-env` keeps
**E1–E7**, `hybrid-start-a-flow` keeps **H1–H10**. A task intent names its document.

| Phase | Document | Status | Evidence | Next |
|---|---|---|---|---|
| P1 | [step-semantics](../research/agent-platform/2026-10-12/hybrid-step-semantics.md) | done | b09c02b29: `seon.test.runner/latest-results` has no branch filter; `first-proof-run-t` is keyed by the attempt | |
| P2 | step-semantics | done | 9d25711e7: `:seon.task/step`, the `:error` end value, `:seon.task.attempt/relaunch`, `:seon.error/unimplemented`, `seon.task/obligations` | |
| P3 | step-semantics | partial | 7ddbb2c1e: `seon.context/step-status`, `seon.turn/record-broken!` judged at `fork-commit`. `seon.test/change-reds` still has a caller in the merge gate (`seon.cluster.source`); `test-step` still folds a throw into its pass values | convert the merge gate; delete `change-reds` |
| P4 | step-semantics | done | 031125438, be32e7501: `seon.context-test/broken-tests-show-with-their-failures` | |
| P5 | step-semantics | done | 015673f47: validity through `seon.task/obligations` at the fork point | |
| P6 | step-semantics | done | 015673f47: `seon.turn/end-task!` records a merge request only when `(nil? needed?)` | |
| P7 | step-semantics | not started | `seon.cluster.agent/apply-plan!` still has its per-action `(catch Exception failure`; no test `a-failed-start-ends-its-attempt-and-the-next-plans` | |
| P8 | step-semantics | not started | `follow!` still writes the `:cancelled` end on main; no derived lifecycle | |
| P9 | step-semantics | not started | no `my.root/relaunch!`; the filed task is `seon.cluster.agent/a-capped-attempt-has-no-relaunch` | |
| P10 | step-semantics | not started | three starters remain: `plan`, `agents-to-start`, `seon.cluster.host/start!`; no ROOT/AGENT declaration in `seon.mcp` | |
| P-final | step-semantics | not started | depends on P7–P10 and the merge-request redesign | |
| U0–U7 | [push](../research/agent-platform/2026-10-12/hybrid-push.md) | not started; being redesigned | The owner's redesign: done is derived from green proofs and root merges. U0/U1 as written delete `:seon.squash/*`, `copy!`, `squash-merge!` and `conflict-task!` before U2/U3 convert their callers (`land-ends!`, `record-error-end!`, `unlanded-ends`, `settle-kept-branches!`, `seon.cluster.registry-test`), which breaks HEAD; each deletion is committed with its last caller's conversion | redesign first |
| E1 | [immutable-env](../research/agent-platform/2026-10-12/hybrid-immutable-env.md) | done | f424382cd: `reference-code/sci` bump, `sci.core/freeze` | |
| E2 | immutable-env | done | f424382cd: `seon.sci.eval/program-context`, `seon.sci.eval-test/no-agent-work-writes-a-program-context` | |
| E3–E5 | immutable-env | in progress (lane program-context) | `fork-for-agent` absent; `copy-base-ctx`, `fork-cluster-ctx`, `branch-ctx`, `fork-for-turn`, `base-bindings`, `transfer-evaluated-roots!` and `acquire!` remain | see the cost findings below |
| E6 | immutable-env | partial | b7cc27520 forks the predecessor's end commit as `:seon.task.attempt/fork`; `write-attempt!` still takes `(first (sort fork-point))`, so the octopus fork is absent | red proof `a-successor-starts-from-its-predecessors-program-context` |
| E7 | immutable-env | partial | 35e906c38: a branch's definitions weigh only what they add; `script/seon/program_context_weight.clj` does not exist | measure one base, replace the 12,288 B per function constant |
| H1–H10 | [start-a-flow](../research/agent-platform/2026-10-11/hybrid-start-a-flow.md) | see the document | H4's claim that branches without program changes add no entry is false on default (corrected there) | |
| AW0–AW7 | `docs/research/agent-platform/2026-10-11/hybrid-agent-world.md` | see the document | b69403e7b (AW7: the recipient's mailbox writes a received message on its own branch), 4718638bd and 9e7958e30 (AW4 slices: attempt and task writes in the branch's own database; `seon.task/main` is gone) | |

### Cost findings, ranked (measured on default at HEAD 015673f47)

One `(+ 1 2)` through the MCP entrance costs 82–113 ms, 7,035 instrumented calls and about 65 MB allocated JVM-wide. The
work it must do costs about 12 ms (a 28-datom recording transaction at the platform's fsync floor of about 9 ms, one
read-and-evaluate, one print). It is 7–9 times over. By milliseconds a fix at the owner removes
([request-cost](../research/agent-platform/2026-10-12/restart-audit/request-cost.md)):

| # | Cost | ms now | after the fix | Owner |
|---|---|---|---|---|
| 1 | two context regenerations per request, each over all 11,085 Vars | 18.3 (+1.0 acquire) | about 0.5 | `seon.sci.eval/regenerate-agent-context!`, `base-bindings` (phase E4) |
| 2 | the recording transaction: 37.5 ms for 28 datoms and 125 KB written; 50% of commit allocation is a 64 KB LZ4 frame buffer per value | 37.5 | about 15–20 (fsync floor 9 stays) | `seon.db/transact-call`, konserve `Lz4Compressor`, fressian handlers, tempid upsert retries |
| 3 | the test step on a request that changed nothing | 11.1 | 0 | `seon.turn/step-due?`, `seon.turn/test-step` |
| 4 | 103 reads per request (40 `seon.db/q`, 63 `seon.db/pull`) at about 10 times Datahike's cost | 16.2 | 3–5 | `seon.db/q`, `seon.db/pull` and their callers |
| 5 | effective configuration computed 12 times, dials 6 times | 5.6 | about 0.5 | `seon.config/effective`, `seon.turn/agent-dial` |
| 6 | cost accounting over every executed definition, twice | about 4.5 | about 0.5 | `seon.profile/live-costs`, `seon.profile/selected`, `seon.db/cost-upserts` |
| 7 | contract predicate volume (2,382 `database-value?` calls) | about 0.5 | | a symptom of 4 |

Items 1 and 3 overlap: one of the two regenerations is the test step's `acquire-context!`. Cache kinds ranked by waste
([cache-hits](../research/agent-platform/2026-10-12/restart-audit/cache-hits.md), budget 512 MB, 252 MB weighed):

1. `:seon.sci.eval/program-context`: 160 MB charged, two bases at an estimated 80 MB each (12,288 B per function, never
   measured) plus branch definitions of 25–37 KB each. Two key defects (entity ids in the plan digest; per-branch commit revisions in
   the key) made equal programs on two branches derive two sets of branch definitions; **fixed in 786915ca3**
   (`seon.sci.eval-test/equal-programs-on-two-branches-share-one-program-context`). Open: the base the loaded commit
   moved away from stays charged until eviction, and the weight constant needs a measurement (E7).
2. Weights that charge a key: `:seon.task/file-grammar` 9.36 MB, `:seon.cluster/derivation-world` 10.30 MB,
   `:seon.task/task-basis` 10.39 MB, `:seon.task/tasks-tx` 11.53 MB, about 41 MB for small values. Weigh the value.
   `file-grammar` never hits and is deleted.
3. `:seon.sci.eval/loaded-digests`: whole-program digests (6,500 functions) per branch commit, 10 MB, 2 hits and 6
   misses. Digest only the authored and added functions.
4. `:seon.cluster/file-schema-keys`: 1,080 counted misses are a probe, not work. Add a non-counting read to `seon.cache`;
   the same idiom inflates `:seon.health/reads` and `:seon.health/derived`.
5. Kept: once-per-publication kinds (`file-derivation-key`, `file-declarations`) and low-traffic content-keyed kinds
   (`:seon.ref/document`).

Findings outside any phase, to be filed as tasks with a red proof: `seon.db/branch-connection` on `:current-src` throws
a NullPointerException instead of an error map; a fresh agent's first request spends 165–178 ms acquiring its checked-out branch (the `:custody` stage); the value
printer renders plain integers as entity references when a map is keyed by attribute keywords
(`seon.render.value`'s `reference-value`).

**Build sequence (owner, 2026-10-11 evening; revised by root 2026-10-12 from the restart audit).** One focus, at most two build lanes on disjoint files plus design lanes, the
start-a-flow benchmark rerun after each commit that moves it. Designs:
[hybrid-start-a-flow](../research/agent-platform/2026-10-11/hybrid-start-a-flow.md),
`docs/research/agent-platform/2026-10-11/hybrid-agent-world.md`,
[r1-db-flow-design](../research/agent-platform/2026-10-11/r1-db-flow-design.md),
[hybrid-step-semantics](../research/agent-platform/2026-10-12/hybrid-step-semantics.md),
[hybrid-immutable-env](../research/agent-platform/2026-10-12/hybrid-immutable-env.md),
[hybrid-push](../research/agent-platform/2026-10-12/hybrid-push.md).
Implementers: Sol 6.1 (medium) for specified phases, Opus for the one REPL path's semantics, Astra for hard
performance, Haiku for digs. A design round is a thorough Haiku dig, Opus and Astra independent plans, a Fable hybrid, a
narrow check (live claims reproduce on default; every deletion is committed with its last caller's conversion; every
phase names a red proof that resolves) and then tasks. Conflicts on copy-back keep main's value and become root's
problem task unless the entity schema declares a rule.

1. **Immutable program context, E3–E7** (lane program-context, Opus; running): the request cost findings 1 and 3
   (about 29 ms and 22 MB per request), then the weight measurement. Proof:
   `seon.turn-test/an-eval-request-on-an-unchanged-program-forks-nothing` as a measured-cost assertion through
   `seon.profile/measure`.
2. **Done and merge, one design round** (running: dig `docs/research/agent-platform/2026-10-12/dig-done-and-merge.md`;
   Opus and Astra plans `plan-done-and-merge-{opus,astra}.md`; then the Fable hybrid and the narrow check). The owner,
   2026-10-12: done is derived from green proofs with no verb, root merges from main, every edge case modeled, no
   guards. It replaces U0–U7 and **absorbs P3's merge-gate conversion and P7–P10** (starters, per-action catches, stop
   versus cancel, relaunch, held successors): those are the round's edge cases, so building them first would be
   rebuilt after the hybrid. P-final (the real chain with zero nudges) follows its phases.
3. **The recording transaction, the reads and configuration** (cost findings 2, 4, 5, 6; Astra, beside 1 on disjoint
   files: `seon.db`, `seon.config`, `seon.profile`, the konserve and Datahike forks, never `seon.sci.eval` or
   `seon.turn` while lane program-context holds them), each at its owner with a before and after number from the same
   probe.
4. **Library words in src** (ledger §3.18 CN entries, after lane program-context releases its files): CN-27 frontier →
   ready, CN-15 custody → `with-branch`, CN-18 roster, CN-5 world → bindings, CN-6 receipt, CN-9 shown → printed value,
   one commit each with every caller; code a plan deletes is not renamed
   (`docs/research/agent-platform/2026-10-12/restart-audit/vocab-src-inventory.md`). Living prose is done (fede4d3d4,
   5c339d69a, 976e3888a, 02cf34720, 39cdc0f7f, 27fb60c0d and the specs commits).
5. **Findings filed with red proofs** (Sonnet): `seon.db/branch-connection` throwing on `:current-src` and `:base/*`
   branches; the 165–178 ms first acquisition; integers printed as entity references; `seon.turn/planned-sources`
   contradicting `seon.sci.forgive/forgive` on repair; `file:line` citations in `seon.render/call-reach-key` and
   `seon.flow/fault-port` docstrings.
6. **Phase numbering and task intents**: every filed phase task names its document (`AW`, `P`, `U`, `E`, `H`); 25 stale
   tags are retired or retargeted.

**Older direction, still the plan unless a phase above replaces it** (statuses are the audit's where stated):

- **Vocabulary cut, partly done.** `my.root` has `instructions`, `merge-requests`, `merge!`, `close!`, `ended`;
  `seon.task/close!` closes a merge request (`:merged`, or `:closed` with a reason) and deletes its branches. Missing:
  `my.task/refine!` and `my.task/create!` (steps with needs and instructions). Delete the queue and the old step
  components. Stored attributes follow their functions: home → main, refuted → red-first, outcome → end,
  proposal → merge request.
- **The runtime follows the database**
  ([runtime-follows-db](../research/agent-platform/2026-10-11/runtime-follows-db.md), owner rounds 1–4, 2026-10-11; its
  §10 is the build order, 22 cards of about fifteen minutes each with a red proof). An agent wakes and runs a task; a
  subject task owns step tasks (`:seon.task/children`, in the schema), siblings run in parallel unless one
  `:seon.task/needs` another, a parent never runs; each step carries its `:seon.task/instructions`. What should be
  running is two reads (`seon.task/frontier` today, `seon.task/ready` after U6), under the dial
  `:seon.config.agent/max-running` (default 4, in `config/default.edn`); the attempt is the launch's write; one clock proc
  fires cron tasks; problems are tasks assigned to root; the plan is rendered from needs, never stored; `start --paused`
  for debugging.
- **The chain experiment** (root; TASK-CHAIN §5). Run once on 2026-10-12; the six-step tree runs unattended after the
  measured per-step costs (calls, tokens, errors by signature, send-backs, whether the successor needed root) justify it.
- **The merge request is a task assigned to root** (Q8, round 3): no review mechanism; the data it carries (the diff since
  the merge base, every reached test with its result, the proofs, the intent and decisions, the merge instructions) is
  rendered in root's context; root's work ends in `my.root/merge!` or `my.root/close!`. The dial stays off until
  criteria for non-controversial merges are written from what root's merges find.
- **Guessed names are answered at the failure** (`seon.error` owner; Q7, TASK-CHAIN §6b): the refusal names the nearest
  real symbols across public declarations and `my.*` with their calling forms; the agent's next successful form records
  guessed → resolved and links it to the task; a name guessed by two agents becomes a mined task.
- **The concise performance line** (`seon.batch` owner; Q1): one line of about 40 tokens, only when a definition the
  agent changed got costlier than main; request cost as `ms / MB`; `seon.batch/cost-diff`'s 500 tokens go.
- **The interpreted census as a bug list** (Opus, beside everything; Q3): every interpreted-only red of a declaration that
  is not host-bound is a bug at its owner; the roughly 50 law-breaking tests (`with-redefs`, `alter-var-root`, `intern`)
  are rewritten under `seon.test-census-test/no-test-redefines-a-var`; platform changes are checked by
  `my.test/check-platform` (32–45 s measured).
- **Cost that follows changed inputs** (owner-file lane): acquisition reuses the program context by identity; hold a
  basis, never a value (tasks `seon.sci.eval/a-context-holds-the-basis-it-acquired-not-the-database-value`,
  `seon.db/a-failed-projection-derivation-stays-cached-under-its-commit`).
- **Chores beside, never ahead**: the two reds on clean HEAD (`seon.config-test/the-default-document-has-one-canonical-complete-location`,
  `seon.db-test/equal-declaration-content-shares-one-projection-across-values`); FEEDBACK-LOOP stages 14, 15, 17; the lag
  workarounds; the `tmp/` dispositions.

**Standing rules** (deduped; each names its reason):

- **No guards or fences** (CLAUDE.md, c722d8d22). A change that adds a check, lock, refusal class, retry, verdict tier
  or recovery path beside a mechanism is refused in review; the design that makes the failure impossible replaces it.
  A fence found in code is a defect with a root-cause task.
- **Design is interactive.** The owner decides designs in conversation; lanes implement agreed designs; bug fixes and
  work agreed in this section are pre-approved when the owner is unreachable; a new design question waits for the owner.
- **Merge requests are root's tasks** until criteria for automatic merges are written (Q8).
- **One probe always in flight.** One lane always serves the loop (a merge, a chain run); the head of the list is work
  with a named owner. A run is graded against the previous one before the next.
- **A round's tasks are small ordinary owner functions picked by reach** until the chain runs; the instrument's own
  defects are lane work, never round tasks.
- **Progress is credited at completed guarantees**: a merged primitive is "available"; a path-only commit is not branch
  evidence; proof-only green is not done; an interpreted green is feedback, not the gate's verdict. A phase is "done"
  in the table above only when its last deletion is committed.
- **A finding is filed the same day with a red tagged proof** (an entry in an existing table test where one fits) or it is
  invisible; a friction met twice changes an entrance rule or an existing proof in the same beat.
- **Errors are judged by their producer**: the same signature across agents is a Seon defect; every checkable CLAUDE.md
  law has a test.
- **Evidence outlives chat**: one dated evidence page per day under `docs/research/agent-platform/<date>/`; cards and
  this plan are revised in place; `tmp/` holds nothing that matters and is never swept by age or name.
- **Lanes**: claim before editing; delete with `rm`, never `git rm`; leave nothing staged; a dependency bump is committed
  last in a batch, then one restart; a closing lane leaves one committed evidence link and a disposition per scratch root.

**Merges, host-bound tests and the first task (owner, 2026-10-11, round 3):**

- **The merge request is a task assigned to root; no new mechanism.** `:seon.config.root/land?` is false. A green end
  records the merge request; everything root needs to judge it is attached to that task as data and root's context renders
  it through render functions and the value printer. Root works it as any agent works its task; `my.root/merge!` or
  `my.root/close!` ends it; there is no review function, flag or `:reviewed` fact. Missing data in that rendering is a
  defect of the render or the task, fixed at its producer.
- **Host-bound tests are derived.** Whether a test can run interpreted is decided by the same analysis that decides it
  for functions (what the body references, from SCI's and clj-kondo's reading of it), never authored.
- **The experiment's first task** is a mined open task with a reproduction form and an existing owner symbol, picked by
  reach.

## 1. The goal

Software that grows by accretion: open data under named schemas, stable names, every call checked and measured, built
by many agents each in its own branch and SCI context. Work is tasks that improve as they move through a chain of agents
(design, prove, implement, merge after review, retrospect), each agent working from the task as the previous one left
it and editing it for the next. Tasks are mined from the database (slow functions, guessed names, uncontracted
functions, red proofs) and retire themselves when their proofs are green on main. A core of about 10,000 lines where
ordinary work is sub-second.

## 2. The essentials

1. **An honest, short number in every reply.** Committed (FEEDBACK-LOOP stages 1–13, 16; like-for-like cold and warm
   cost per [symbol scope], b37a3ff72). Being cut to one line (§0, the concise performance line): an agent learns it wrote slow software in
   forty tokens, not five hundred.
2. **Tasks worked end to end by Seon agents through the REPL.** A model agent turns a designed task green; root has
   merged three; the automatic path has merged nothing yet (§0, the merge request). The chain of separate agents is the next
   shape (§0, the runtime and the chain experiment).
3. **Red proofs before any assignment.** `seon.task/assign!` runs the proofs on the agent's branch and refuses all-green
   or unobserved proofs (7ce008211, c95848065). 155 of 783 tasks carry a tagged proof (§0). Proof quality is the weak point:
   nearly every failed round traced to the proof or the task, not the implementer, so the chain gives proofs their own
   stage and the catalogue (TASK-CHAIN §4).
4. **Default runs HEAD.** Routine: `bin/seon start --head` per batch of commits; readiness 5.1–5.3 s on a quiet machine (`start --head` with schema transactions measured 32 s on 2026-10-11).
   The lag workarounds are not yet deleted (§0, chores).
5. **Correctness and efficiency over size, for now** (owner, 2026-10-10). Temporary growth is acceptable; Seon agents
   trim it later using the cost data; every change is judged by correctness and measured cost. Growth that is a fence
   is not acceptable (§0 standing rules).

## 3. The method: the chain of agents

The method is the TASK-CHAIN card (`schedule/cards/TASK-CHAIN.md`) on the runtime of
[runtime-follows-db](../research/agent-platform/2026-10-11/runtime-follows-db.md): Mine (a query) → Design (survey,
schemas, contracted stubs) → Prove → Implement (with a send-back right) → Merge (root's merge-request task) →
Retrospective. Each stage is a step task of one subject, worked by a fresh agent the reconciler launches when the
step's needs are done; the handoff is the subject task at main, edited by `my.task/refine!`; a step ends when its proofs
are green, the same rule as any task; its instructions are data on the step; every stage records an explicit decision.
Variations of the pipeline are trees and instructions, compared on the same subject family. The first experiment is two
steps, Design+Prove → Implement (§0, the chain experiment).

Every new mechanism is run by hand once before it is automated: the chain runs of 2026-10-10 found two to five
frictions each at minutes of cost, the cheapest learning of the weekend.

## 4. Done

The honest-measurement task (FEEDBACK-LOOP stages 1–13 and 16); the merge base by `t` (d9eb15c05, aa7ffb17f); the
byte-bounded node cache (12473ca94); the first links of the agent lifecycle (DYNAMIC-CONTEXT stages 1–3: the block
names the namespace, an agent closes at its end, one end message to root); non-root agents never load the shared JVM
(e361536a9); a function and its schema merge as one unit (ff61d0b29); the collection fix (f51240f34). History is in
git and the dated research pages.

## 5. Superseded

The 2026-10-10 step list and the 2026-10-10 evening dependency list (38fce69e3) are replaced by §0. Of that list,
items 1 (schema merge), 2 (target fence), 3 (exact merge), 4 (admission), 5 (idempotent end) were committed on 2026-10-10;
items 2, 3 and 5 are fences and are reverted (§4, Q15); item 6 (one interpreted proof path) becomes §0's interpreted census with the owner's rule that every mismatch is a bug; items 7 (data-first task) and 9–11 (rounds, ROOT-AGENT stages,
triage) wait behind the chain. Research corrections to pass 1 of the replan: `turn.clj` grew 5,622 → 5,888 overnight
and `source.clj` 1,488 → 2,136 (not from 441 and 1,156); the compiled check gate exists (`my.test/check-platform`,
32–45 s); the hand accept path already retires branches.

## 6. Deliberately deferred

- ROOT-AGENT stages 4–13 (grants, root tasks, `decline-merge!`, tick, triage, spend report): `my.root` holds root's
  tools first (§0, the vocabulary cut); the tick waits for three chain runs; triage waits for a judgment duty to occur three times.
  The card's two refuted claims (write-back conflicts vanish with Claude lanes; "a failure leaves nothing written") are
  corrected when it is next touched.
- DYNAMIC-CONTEXT stages 4–7 (shown declarations, working set, history by reference, reflection on a snapshot): after
  guessed names are answered at the failure (§0, guessed names) and only if a chain run shows prompt growth caps agents.
- Publication (a compiled check is 32–45 s measured) and boot's whole-program derivation: platform commits pay the existing check; per-file
  derivation inputs when the chain table shows the cost dominating.
- Merging through git objects or a dedicated checkout (fable-replan §4.3 fix A): the revert removes the fences the
  shared tree motivated; the root fix is committed only if a real collision is observed.
- COST-REG slices 2–3; growth tests and guards; embeddings (after lexical search
  is used and found wanting); the Datahike fork rebase; a stateful lifecycle test suite; a Flow-hosted writer (refused).
- The positive-wording pass over AGENTS.md's remaining negatives: root, in small commits.

## 7. Decisions, by owning topic (every owner ruling, deduped; superseded ones marked)

**Laws and design**
- 2026-10-10 Seon grows by accretion; the four properties and the design order in AGENTS.md are the laws.
- 2026-10-11 NO GUARDS OR FENCES, no exceptions (AGENTS.md c722d8d22); supersedes "every restriction names the failure
  it prevents". The night's sixteen fences are reverted in one batch (Q15); the three root causes they patched are
  committing in the shared tree, two evaluators compared, and database values held across transactions.
- 2026-10-11 Focus: improving the tasks, not more platform machinery; embedding search later.
- 2026-10-10 Correctness and efficiency over size for now; temporary growth acceptable; agents trim later.
- 2026-10-10 Datahike's writer stays as it is (go-loops, group commit); Seon does not batch; Flow procs run on
  platform threads, one per task; accounting closes at `seon.flow/var-process`; the reply is published after
  accounting; `seon.batch` is a misleading name for the eval-request fold (rename belongs to its owner).
- 2026-10-10 Stored data is described in Datahike's terms (entities, attributes, values, datoms).
- 2026-10-10 Merge bases are found by the fork's `t` and `as-of`, never by walking commits (committed d9eb15c05).
- 2026-10-10 Full history is kept with the one-hour retention window; `as-of`, `since` and `history` are unaffected.

**Tasks and the chain**
- 2026-10-10 Work is a chain of separate agents; run by hand first; nail a single task before child tasks (the
  deferral of child tasks ended 2026-10-11 with the task tree below).
- 2026-10-11 The chain is TASK-CHAIN: build it generally, experiment with variations, keep the stages worth their
  calls; agents may edit any subject field; every stage records an explicit decision, `:unchanged` with a reason, never
  an absence. Superseded the same day (runtime-follows-db rounds 1–4): stages are step tasks, not stages of one task;
  "stage proofs" are the step's own proofs; the first experiment's handoff is the reconciler's, not by hand.
- 2026-10-10 Tasks are entities upserted as the chain refines them, with history; author little, derive the rest;
  authored = name, intent, must-read; derived = proofs, interface, schemas, status. Task names are fully namespaced
  symbols. The database is the source of truth; files are lossless write-back, proven by a round trip.
- 2026-10-11 Auto-retire: a task whose proofs are green on main retires itself, derived, never written.
- 2026-10-11 Vocabulary (Q16): agents have `my.task/current`, `refine!`, `send-back!`; success has no verb; root's
  namespace is `my.root`; merge words are git's (`request-merge!`, `merge!`, `close!`, `:merged`/`:closed`); the queue
  and steps are deleted; home → main, refuted → red-first, outcome → end, proposal → merge request, renamed now.
- 2026-10-08 A question task declares its answer's Malli schema; the answer is ordinary Datahike data.
- 2026-10-09 Budgets with four meters (calls, CPU, allocation, storage) estimated by the designer; completion is
  automatic when proofs are green.
- 2026-10-11 Guessed names: answer at the failure with the nearest real symbols and calling forms; learn guessed →
  resolved from the agent's next form; link it to the task; a name guessed twice becomes a mined task.

**Runtime, scheduling and the task tree** (runtime-follows-db, owner rounds 1–4, 2026-10-11)
- One path: an agent wakes and runs a task; everything uses it (steps, merge requests, problems, recurring attempts);
  the reconciler, the clock and the heartbeat are the procs that host it, not tasks. Agents never start agents.
- Progress is derived from facts written once (steps, needs, attempt, end); intent is stored on the task
  (`:held :running :paused :stopped :cancelled`, each one reconciler action in Flow's verbs) plus cluster
  `running/paused`; nothing writes "actual" to the database except the heartbeat's audit.
- Order is `needs` only, parallel by default, parents never run; no position; the plan is rendered from needs.
- Instructions are data on the task (steps and problem tasks alike); no templates, no stage types.
- Problems are tasks assigned to root by identity, created where their facts already pass; nominal ends wake nobody;
  error signatures and cost regressions go to fresh agents. Merge requests are one problem class.
- One scheduling system: a recurring thing is a task with a cron; one clock proc writes firings; a function firing runs
  in the clock (maintenance, heartbeat); an attempt firing runs on the path; a firing to a live agent is a message
  wake; refused delivery is a problem task. `seon.schedule`'s per-agent proc goes.
- Flow owns process and computation; channels carry only ephemeral data; everything of consequence is a fact, so a
  crash and a resume to any commit are derivations. `start` resumes; `start --paused` for debugging.
- `max-running` is a dial, default 4; agents are to be lightweight: context MB and ms to start are measured targets.
- The cache of a branch's interpreted definitions (`seon.sci.eval/interpret-overlay!` per branch) is the first performance lane; Astra's
  0c3916000 already caches the plan.

**Proofs and tests**
- 2026-10-09 Proof gates scheduling; the rest is an unproven backlog.
- 2026-10-10 Proofs as written: an `are` table with the reported input plus one `is` on a count for a cost task; a
  seeded property only where a one-line law exists; COST-REG slices 2–3, guards and growth tests cut.
- 2026-10-11 How to write good proofs is not yet clear: keep a proof catalogue of good and bad examples with why
  (TASK-CHAIN §4).
- 2026-10-11 Interpreted must agree with compiled for every declaration that is not host-bound; every mismatch is a
  bug, never a verdict class; the gate compares directly; platform changes use the existing compiled check. Supersedes
  the 2026-10-10 "evidence readers accept the agent's evaluator plus compiled" and its tiers (reverted, Q15).
- 2026-10-10 Fixtures are data on the namespace entity (`:each`, `:once`), written by `use-fixtures` and file indexing.
- 2026-10-10 Only reliable, like-for-like cost data is surfaced; one cost entity per [symbol, scope], cold and warm.

**Merge**
- 2026-10-10 Auto-merge on green is the target; 2026-10-11 (Q8, round 3): until criteria for non-controversial merges
  are written, a merge request is a task assigned to root, rendered from the data attached to it; no review mechanism.
- 2026-10-11 The first merge through the path is the round-8 task, rerun by a fresh agent after its two interpreter
  bugs are fixed (Q11; the original merge request went with the reset). Interpreted/compiled mismatches at the gate are bugs (Q3).
- 2026-10-11 One close owner `seon.task/close!` decides a merge request and deletes its branches; the stale merge
  requests and ended agents' branches are cleaned by hand today (Q6).
- 2026-10-10 Commit the Datahike fork's commit-cost change (done, 5acc29e23).

**Measurement and cost**
- 2026-10-11 Concise performance feedback: one line of about forty tokens only when a changed definition got costlier
  than main; request cost as `ms / MB`; the teaching goes in the instructions (Q1). Supersedes the 2026-10-10 "compact
  cost diff always on" as to its size, not its existence.
- 2026-10-10 Numbers are live: each reply shows the request's own values against main's stored values; cost entities
  per function with values replaced in place, no history; schema cost measured once per digest at contract time.
- 2026-10-09 A cost regression against the last green run on main is a red test, not a health note.

**Data, documents and search**
- 2026-10-10 Doc sections are entities identified by path and anchor, with parsed references as facts; files are the
  write-back.
- 2026-10-10 Search: the fork's Lucene secondary index (`:scriptum`) first; vectors (`:proximum`) only after a chain
  run shows a lexical miss.

**Process and roles**
- 2026-10-10 Roles: Claude designs; DeepSeek-flash implements non-platform; Opus implements platform; root reviews and
  merges. 2026-10-11: design is interactive with the owner; lanes implement agreed designs (Q9).
- 2026-10-10 When the owner is not replying, root decides with the recommended option, proceeds and reports; 2026-10-11
  narrows it: pre-approved are bug fixes and work agreed in §0; new design waits.
- 2026-10-10 The whole loop is run by Seon agents eventually: a root agent through `my.root`, agents triage, no spend
  cap with a daily report (deferred behind the chain, §6).
- 2026-10-09 Auto-restart default onto HEAD per batch of commits; then delete the lag workarounds.
- 2026-10-09 Freeze new platform machinery until the REPL-only acceptance passes (host-bound half still open).
- 2026-10-10 Reflection: root reads the record first, then asks the snapshot; by hand.
- 2026-10-11 Re-plan in two passes: analysis and questions, then the plan from the answers (done; this file).

## 8. Open questions

None open. Q1–Q16 of the replan and the three round-3 questions are answered in
[fable-replan](../research/agent-platform/2026-10-11/fable-replan.md) §8 and folded above.
