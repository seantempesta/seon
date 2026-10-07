---
type: review
status: findings
created: 2026-10-07
lane: plan-v2-review
---

# Plan v2 review — plan-v2-review, 2026-10-07

**Revise before implementation.** The deletion direction serves §1. The current text does not yet specify a
dependency-complete replacement for the shared REPL path. The largest gaps are analysis ordering, incoming-message
visibility, settlement/error ownership, and the cut's intermediate states. All proposed implementation below is
**TARGET**, not an installed API. I accept the owner's §1 rulings; these findings concern how to deliver them.

## Evidence and foreign boundary

Read lane-rules first, all five r-* notes, the full v2 plan, agent-loop spec, repl-lookup design and dh-fix note;
loaded the data-oriented-clojure, REPL, testing, Datahike and Flow skills. Reviewed source at HEAD `100f7a13c`.
Default's archive is `722f110df0d2e140dcbb177eb228de16c60443db`; `git diff 722f110df HEAD -- src script test
resources/seon/schemas` is empty. Dependency pins: Datahike `d9121bbbad`, SCI `ce0aa53b9b`, core.async
`dc35f3e0d7`, Malli `43c6dca8d2`. The dirty Datahike checkout is another lane's work, not the installed pin.

Initial `bin/seon status` and MCP `runtime_status`: PID 41301, start 07:48:14.692Z, default `:faulted`, basis
536872987, 18 open faults, 179 errored receipts, 13 over-bound warnings, live heap 5,142,718,104 bytes. Existing
tasks include `seon.sci.eval/acquisition-rebuilds-the-whole-program` and the batch/schema tasks in the health reply.

**The running database changed during this review.** Before the first eval, its basis fell to 536871064; at
08:55:21Z status was `:degraded`, basis 536871070, same PID/archive, one errored receipt, no open-fault count,
ready-ms 7280, live heap 5,199,452,864 bytes. The stale `user.sgprobe/fix` entity was absent in both the lane and
root JVM reads. This is evidence of a replacement database boundary, not proof of who performed it or how.
I never stopped, reset, restarted, adopted or loaded source into default. Historical root populations and timings
cannot be compared to this new population. P0's deletion is now conditional on a fresh read.

Other lanes own the dirty task EDNs (`persist-tasks`) and Datahike (`dh-audit`); http-kit and unrelated untracked
files also exist. None was included in the candidate or edited. No test runs, model calls, agents delegated,
pollers, extra JVMs, or worktrees were started.

## Ranked findings and concrete plan changes

### 1. High — §4.1 orders analysis before the evidence it needs

The installed reader is a cursor interleaved with evaluation, not READ-all followed by EVAL-all:
`src/seon/batch.clj:549`, `src/seon/sci/batch.clj:23`. Namespace changes, aliases and syntax quote in later forms
depend on earlier evaluation. The SCI probe below confirms that dependency. A structural pre-read plus a later
semantic read would violate the single-read objective.

More seriously, `src/seon/turn/batch.clj:224` passes **observed macro expansions** to analysis;
`src/seon/fn/analyzer.clj:60` builds exact-span expansion hooks; `src/seon/fn.clj:944` analyzes a batch with those
hooks. They do not exist before EVAL. The current fold also derives facts and applies pending changes before a
later form consumes them (`src/seon/batch.clj:480,505`). Moving all analysis to the end is therefore not just
batching a call: it must preserve admission, same-pass schema/definition visibility, contract arming, deletion,
and behavior after a partially failing definition. Cross-namespace writes can call `analyze-form` again
(`src/seon/turn/batch.clj:254`), another counterexample to merely changing the outer loop.

Replace the diagram's separate READ/ANALYZE/EVAL boxes with: “One cursor reads and evaluates each form in the live
namespace, retaining source spans, repairs and observed writes/expansions. One whole-reply analysis joins those
observations without re-reading or re-evaluating forms.” Then require an S4 design/probe for the remaining hard
part: when admission needs analysis before later forms, how does this composition still make exactly one kondo
call? Do not claim the existing test reader solves that question. Name forms defining and invoking a macro in one
reply, namespace/alias changes, nested definitions, registration followed by use, and a failed definition as proofs.

Preserve original-to-repaired offsets explicitly: forgiveness changes text, while §4 asks for exact spans and
“each form exactly as sent.” Specify which text kondo sees and how its spans join observed writes. Re-running
macro expansion to obtain evidence would execute authored behavior twice and is not an acceptable shortcut.

### 2. High — the new context has no path for new messages or proof results

§4.3 contains opening and model exchanges only. A new root message after the opening changes neither; outside
evaluations are intentionally excluded. The mailbox carries a payload-free wake (`src/seon/cluster/agent.clj:477`),
and delivery assumes the receiver will derive the durable message (`src/seon/cluster/wake.clj:358`). Removing the
refresh without naming that derivation can spend a model call on an identical prompt, then mark the unseen
message answered. The same question applies to asynchronous error delivery and S5's failed proof results:
`:test` exchanges are excluded too.

Add a paragraph to §4.3 specifying how each newly delivered message/error/test outcome becomes an ordinary
evaluation with saved shown text through the same pass, once, before the model sees it. It must neither be a
refresh of old history nor an unrecorded prompt block. Name its origin and whether it belongs to the model-call
pass or an earlier input pass; “opening once” alone does not answer this. If the intended protocol instead
requires the model to query its inbox/proofs, specify the observable cue and test that no unseen message is
acknowledged. This needs resolution before S2 deletes the current delivery path.

Also scope opening completion to the **assignment/task**, not “agent has any opening.” Define what happens on
reassignment and after a crash during opening. Task reads use the task home (`src/seon/task.clj:340,535`), whereas
history uses agent custody; a start value of only the agent branch is insufficient for root-assigned task updates.

### 3. High — one machinery transaction is not achieved for faults, effects, or test completion

The happy MCP SCI path is an excellent starting point, but `with-submission` is a composition helper, not a
complete universal recorder (`src/seon/turn.clj:3464`). Today:

- Batch faults record **after** settlement (`src/seon/batch.clj:699,725`). `seon.fault/record!` can write the
  origin and the cluster copy separately (`src/seon/fault.clj:138`). Repeats alone ride settlement
  (`src/seon/turn.clj:3568`). Changing the ordinary recorder leaves these extra machinery commits.
- `*open*` puts run/evaluation intent into the first authored write (`src/seon/db.clj:3609`), so reusing the
  submission mechanism unchanged still distributes machinery over an effect and the final transaction.
- Effects currently reference a run and resolve the evaluation via run+ordinal
  (`src/seon/effect.clj:210`; `resources/seon/schemas/seon.effect.edn:212`). Removing the early run/evaluation
  rows needs a new provenance representation for an effect that survives a crash before settlement. Do not
  create a required ref to an evaluation that may never be recorded. Remove the obsolete ref/identity pair with
  all callers, rather than silently writing placeholder evaluations.
- `seon.test.runner/record-tx` records proof completion/admitted member claims, not just evaluation transcript
  rows (`src/seon/test/runner.clj:1831`). Test bodies execute as a resolved host Var or SCI test function
  (`src/seon/test.clj:264`); they are not uniformly “given code.” Effect-reaching members open an attempt first
  (`src/seon/test.clj:179`). A request may involve several test branches and a separate recording connection.

Add an entrant/transaction table to §4.1: model, MCP SCI, MCP JVM, interpreted test, host test; supplied read/eval
stages; observation; settlement connection; which domain writes remain effects; fault delivery destination.
Reuse the one fold's stage values. JVM mode must retain host read/eval (`src/seon/turn/batch.clj:887`), and test
bodies must retain their resolved body stage; neither needs a second evaluator. Decide what “one kondo analysis”
means for a precompiled body with no newly authored source and for host mutations that SCI cannot observe.

Have the existing test recorder contribute its domain transaction data to the shared settlement where custody
allows it. Do not delete proof admission, reach evidence, late exit recording or the request-level recorder merely
because they are called “third writer.” Explicitly price cross-branch completion and fault delivery: one Datahike
transaction cannot atomically update two branch heads. The old spec §2.1/§4 names this issue; v2 drops it.

The single batched digest lookup must be performed or checked **at the writer's head**, not treated as an
unchanging start-value verdict. `program-facts-call` already guards changed definitions
(`src/seon/turn/batch.clj:548,600`); preserve that authority. `runtime-analysis-batch` also queries namespace
context and available functions (`src/seon/fn.clj:958`), and entity replacement pulls current rows
(`src/seon/turn/batch.clj:452`). One digest query alone does not remove these reads. State what held values replace
them; do not promise one total database read for authored queries, schema admission, and the writer itself.

### 4. High — RECORD before PRINT cannot save the exact shown text or all diagnostics

§4.1 says RECORD saves shown text, then PRINT generates it. The installed fold prints while observation is still
open (`src/seon/batch.clj:321,371`): realizing a lazy value can execute code, write definitions, or throw.
That work must be observed before the one settlement. Rendering errors also need to join that record.

Replace the ordering with: “Retain each evaluated value; render/print once under its world, collect its shown
text and diagnostics; prepare facts; publish staged blobs; commit the machinery record; deliver the held batch.”
The final delivery reuses shown strings, never reprints history. `blob/with-publication!`
(`src/seon/blob.clj:304`) protects publication plus roots from GC; one database transaction is not one store
write, and blob bytes belong in the measured budget. Preserve the same publication path for JVM and tests too.

Specify rejected versus unknown commit outcomes and post-commit installation failure. A fault caused by the
commit itself cannot be included in that already-failed transaction. The value still returns with a diagnostic;
do not report one successful commit or acknowledged wake when persistence is unknown. Preserve the old spec's
unknown-outcome ownership requirement without its obsolete run machinery. Count attempted transactions and
successful durable commits separately.

### 5. High — serialization and answeredness are omitted at exactly the destructive cut

SCI submissions and model work currently share the agent proc (`src/seon/cluster/agent.clj:511,531`), but MCP JVM
runs directly on its caller thread (`src/seon/mcp.clj:254`), acquiring the same agent context and binding result
handles before recording. Its recorder's open-run refusal (`src/seon/turn.clj:1337`) is too late to serialize
execution; deleting runs removes even that fence. Serializing just ordinal allocation is insufficient for private
Vars, namespace state, current-ns, result handles, and a model prompt constructed while outside work mutates them.

Add: “All entrants using one agent's context enter its existing proc/request port, including JVM mode. One pass
owns that context until actual exit; different agents remain concurrent. Evaluation ids are minted from pass id
and within-pass position before evaluation; agent-wide ordinals are assigned at the writer. Outside work never
advances model answeredness.” This is preservation of §1.2, not a new scheduler.

Retain `turn-step`'s thread/interrupt cleanup and terminal replies until replacement proves them. The backstop
dial also bounds MCP waiting (`src/seon/cluster/agent.clj:781`); deleting it requires specifying that wait/stop
contract. A host call ignoring interruption is not terminated by an SCI limit or an MCP timeout. Do not allow a
second pass while it still owns the context. Keep the old spec's terminal-answer/actual-exit obligations even
with exactly the three policy dials.

State answeredness as a value: only a qualifying **committed model reply** advances it to the basis actually
presented, never settlement-time head. Wakes arriving during the provider call remain due; outside/opening/test
passes and failed calls do not answer them. Empty/prose-only/truncated replies, context failures, failed calls,
and calls with no evaluations still need a counted call record and a terminal outcome. Existing ordering queries
at `src/seon/turn.clj:2650,2909` cannot merely be adjusted to use the new transaction's t. Root wakes must refill
the bound as ruled; a timeout/retry must not reset that anchor.

### 6. High — slice order has unresolved prerequisites and S4 is too broad as one review unit

| Slice | Feasibility correction |
|---|---|
| P0 | Dead-code deletion and reviewed dependency bump can precede the loop cut. Use r-delete's **410–444**, keeping `turn-request`; 444 is not an unconditional deletion total. Read the stale namespace first: it is now absent. `remove-ns!` is a namespace operation, not a targeted single-row repair; inspect every member/reference and verify writer acceptance if the defect recurs. |
| S0 | Installed HTTP fixture seam is supported by r-turn and `ai/resolved-target`; no need for a new sender path. Give every entrant the same behavior cases, including definitions, refusals, effects and blob publication. A plain arithmetic pass does not bound S4. Its numbers follow P0, so retain the historical pre-P0 baseline separately. |
| S1 | Lookup changes are independent. Rewrite help only to capabilities available in that commit; future completion/wait semantics cannot be taught as installed. Convert `render.ns/directory`, both refer installers and test-only `agent.loop` callers. |
| S2 | Pair seed deletion with its replacement opening/delivery, or keep the seed until S3. Existing persisted `:generate` rows need a terminal disposition before deleting their handler. Dead debug views can be an earlier file-disjoint deletion. |
| S3 | Depends on an origin/assignment-opening fact and ordered-history writer; v2 adds origin only in S4. Add the fact and populate new evaluations before switching the context reader, without guessing historical `:system` rows' origins. Publish/adopt schema and consumer together. |
| S4 | Includes reader semantics, kondo/observation timing, same-pass facts, the settlement owner, origins, all entrants, faults/effects, ordering, lifecycle, wake logic, schemas and many tests. Split into in-place preparations (shared settlement, explicit stage inputs, origin/order facts), each used by existing entrants, followed by one dependency-complete cut. No alternate loop or dormant v2 runner. |
| R | Schema retirement must already adopt on a branch of the live store **before** the cut lands/restarts. A later reset cannot make an unbootable S4 valid. Name the archival and deliberate-loss manifest, not just tasks. |
| S5 | Proof completion may follow only if the S4 intermediate loop retains an installed stop/wait and launch contract. Re-home host/SCI test entrance proof in S4; waiting until S5 to repair the named SCI test failure leaves “all entrants work” unproved. Task-home completion versus branch disposition needs idempotent recovery. |
| S6 | Experiments are appropriate after the shared path works. Reader behavior for prompt markers and imitations needs a safe default before S4, not a decision deferred until S6. Test result-map rejection as data without the existing nil fault before using it in experiments. |

Correct S3/S4's deletion labels: r-delete **D1c** is the 852-line prompt/capture/history assembly; **D1d** is stored
read evidence (18 lines plus edits), not vice versa. The ~5,700 union is a def-span census with alternatives,
not a promised net diff. The “<600 pass / <1,000 total adds” estimates have no implementation proof; use them as
review targets, not a reason to compress distinct obligations into one function.

### 7. Medium — the budgets are goals, not demonstrated consequences of §4

Deleting the system turn removes the largest measured waste, but the warm fixture's remaining pre-settlement
stages already total about **457 ms / 550 MB** (r-turn §3a stages 2–6). That arithmetic is a residual baseline,
not a prediction: S3/S4 must eliminate most of it too. `acquire-program!` survives through MCP JVM acquisition
(`src/seon/mcp.clj:267`) and post-definition context changes; deleting the call from the refresh does not prove
the work disappears. Require the next-pass-after-defn probe, not just unchanged arithmetic.

The 200-row raw narrow pull alone costs 9 MB; index+pull about 17 MB; concatenation another 8.7 MB in r-context.
The wrapper path is ~11 ms / 22 MB before composition. Thus the stated narrow-read design has not met either
allocation target. Name and measure a smaller history acquisition/composition at the existing owner before
promising <5 MB. A direct index fold is an experiment, not an installed ordered-index API: today's query joins
runs and explicitly sorts (`src/seon/eval.clj:54`). Full unclipped history necessarily scales with its text size;
specify the S0 workload size and provider-window exhaustion behavior without clipping or changing §1.

“Write measurements only when significant” is **already installed** (`src/seon/profile/measurement.clj:142,182,190`).
Its own stored-measurements read is also a consumer, so “read only by health” is literally false. Moving writes
off the transaction merely adds another machinery write unless the design deletes durable observations after
checking the health consumer. Specify the actual simplification; no poller/background-flush subsystem.

The same-shape `or`+join query still allocated **180,851,688 bytes / 60.713 ms for one symbol** in this review.
Task `seon.db/an-or-clause-joined-to-a-pattern-costs-a-scan-per-binding` survives S2. Batching is already present
and does not cure it. Keep it as an independently measured producer fix, not an incidental promise inside S4.

### 8. Medium — §6 options omit guarantees and the reset's loss boundary

1. **P0/reset:** conditional stale-row removal then one cut reset was reasonable on the old basis. Rebase the
   decision on the current cluster. Reset replaces the branch with the selected source commit; it is not selective
   runtime-schema cleanup (`src/seon/cluster/registry.clj:319`). Runtime-only program changes, tasks, assignments,
   messages, settings, schedules, results and error evidence need an explicit preserve/discard inventory. Saved
   task EDNs alone do not preserve all of those. Verify task identity/references round-trip from the committed
   archive, preserve needed evidence outside the discarded head, and retire only owned/unclaimed branches through
   their owner. Do not treat a disappearing error count as proof its producer was fixed.
2. **Subtree counts:** the three alternatives are useful, but the recommended child-count format change needs an
   old-store migration guarantee. Old child counts remain unknown until derived/rewritten; a root reset is not
   an index-node rebuild. Price disk/allocation growth (dh-fix estimates one long per child, ~1 KB per 128-way
   branch before compression), mixed-version reads, crash safety, and whether old nodes ever converge without
   explicit rebuilding. Loading children at split trades synchronous I/O for no format migration; a recount
   alone is temporary until the split bug is fixed. Do not promise that adding the fields immediately ends all
   whole-range counting. It is separate PSS work, not a prerequisite for the reviewed correctness gitlink bump.
3. **Store budget:** the existing snapshot's two 26.7 KB root records already exceed 50 KB together. But
   “write the root once” saves only 26.7 KB from ~152 KB, leaving ~125 KB. Node bytes were ~99 KB, the majority.
   Price three choices: accept the measured per-pass cost temporarily (recommended), remove duplicate root
   payload only (smaller change, still misses budget), or reduce root plus per-attribute node rewrites (larger
   dependency work with durability/GC proof, needed to substantiate <50 KB). Carry the accepted exception next
   to the §3 budget; it must not silently turn a red number green. Root/head duplicate payload is source-verified
   at pinned Datahike `writing.cljc:539–540`; bytes depend on branch shape and touched attributes.
4. **Replacement spec:** approve only after carrying forward the non-context invariants that v2 has dropped:
   immutable handle identities, agent-wide ordinal allocation, task-home custody, unknown commit ownership,
   actual-exit joins, reply totality, failed-call accounting, and completion's cross-branch crash window. Retire
   the old context design in place; do not lose these independent requirements with it.

### 9. Medium — presentation/reader options need precise meanings

DATA holds stored strings, which fixes the old proposal's attempt to recover live values from shown text.
But “maps … concatenated, never re-printed as one value” does not explain escaping/layout through the one printer.
Specify the value printer's presentation of the envelope once and verbatim reuse thereafter, or an explicitly
supported composition of already printed units. Hand-building EDN maps with string concatenation would be a
second serializer; printing the history vector under the ordinary budget would clip it again.

Keep handles available in **both** presentations: §4.3 lists only ns/form/shown, while §1 promises runnable
`result/<id>` digs. Include output/errors/diagnostics and trailing prose in the stored exchange contract; “shown”
alone is the printed value, not today's full MCP response. A failed-render fallback and a vanished live value
after restart must remain distinguishable. `bind-settled-results!` currently binds gone-value markers from
history (`src/seon/sci/eval.clj:2786`); its run-joined read converts in S4 too.

Keep the owner's anti-imitation rule, but define the accepted syntax before implementation. A result-shaped map
can also be a legitimate authored value; source alone cannot distinguish all such intentions. The existing
refusal applies to MCP code too (r-read §3/§7). An explicit mode/grammar table and regression for flagged prose
are required; regex guesses about whether a sentence claims verification are not a complete semantic contract.
Do not grow a general per-agent parser plugin system just to compare the two named presentations.

## §2 / §4 factual audit

Every §2 table row was checked against its cited note; numbers below remain historical unless re-probed above.

| Claim | Verdict / replacement text |
|---|---|
| Warm 2.1 s, 3.65 GB, ~400k calls, .94 MB, six commits; DeepSeek 3.4 s / 1.4 s provider | Matches r-turn §3a. Label one fixture reply/run, allocation measured over a whole-JVM window, calls affected by probe counters, store bytes over the window. Not medians or universal pass costs. |
| Refresh ~80%, 1.3–2.3 s, 3–4 GB; 300–408 datoms | Supported across different runs. The warm fixture's refresh wrote **228** datoms; 300–408 is lp-add-2 (r-context), not the same specimen. Reacquisition was measured after preceding program-changing replies; “on every turn” is too broad. |
| Six commits and quadratic capture | Correct clean observed model trace, no separate close. Capture is repeated growing prefixes while history fits selection; not unbounded quadratic after a fixed prompt cap. Faults add commits; no-provider path differs. |
| SCI (+ 1 2): 27 datoms, ~150 KB, 205–358 ms, 159–232 MB | Supported by r-record §1a, with measurement method caveats. One machinery commit holds for that no-effect/no-fault case. |
| defn: 254 ms settlement, 712–733 MB; following JVM 1.3 s / 3.2 GB | Correct numbers, but allocation is **whole call**, not just settlement. 104 ms post-commit installation is separately measured in r-record §1b. |
| Read split/re-read/per-form kondo, 441/667 ms; 76 KB / 4.1 s / 94 GB | Matches r-read's 40-form and 76,408-character mixed-reply probes. Cubic-like allocation is an empirical scaling result, not all source inputs. `forgive-fix` is not installed by the evidence here. |
| code-currency “one query per value” | False: **one batched query with pathological per-binding cost**, `src/seon/turn.clj:1885`. Raw 8 ms was 142 symbols; 42 s was root's 672-symbol case. Do not imply those are equal populations. |
| “reading 200 exchanges”: narrow 8 ms /17 MB; of-agent .4–.5 s /.8–.95 GB | Narrow pull selects 200 after indexing **1,387** evaluations. The expensive of-agent number reads all 1,387. Per-row text cost 180–220 ms /370 MB and concatenation 3.3 ms are for 200. Label each population. |
| 130–150 KB floor “mostly” duplicate root +4 KB/attribute | Observed workload cost, not a universal storage lower bound. Root copies are ~53.4 KB, **36%** of 152 KB; index nodes are ~64%. Per-attribute cost is an empirical approximation. |
| Measurement upserts 51–78%, “only health” | Percentages supported for the larger sampled settlements; significance comparison also reads them. Already filtered for significance. |
| Ten refresh units, outside setup, 1,241-token error list; fabricated results; ignored prompt namespace; prose first word | Research transcript/reader evidence supports these observations, not every agent. Link exact specimen and historical basis. Reader defects have named tasks in r-read. |
| author conflates MCP/refresh | Correct (`src/seon/mcp.clj:226,254`, r-context origin table). New origin is TARGET; live schema probe found none. |
| stale row, of-agent refusal, 17 faults “now” | Supported on research snapshot; stale as current claims. Initial status already had 18; later database replacement removed the stale entity. Do not claim tests now pass: none run. |
| §4 existing submission helper, second writer JVM, third test recorder | Correct source census, but the duties/custody differ (findings 3–5). |
| §4 single-read stage exists, test-only | Correct for code-request/read-next; it is sequential and still uses forgiving machinery. The one-read migration does not implement one batch analysis by itself. |
| §4 ordered narrow index, origin/current namespace on agent, opening once | Targets, not installed facts. Existing order is turn-transaction + within-run ordinal (`src/seon/eval.clj:54`); writer-owned agent ordinals and assignment opening facts must land. |
| §4 SCI lookups | `dir-fn`/`apropos` provide data; `find-doc` prints, so a thin data-returning traversal is needed, as repl-lookup already says. Bare lookup wiring and task/schema doc are targets. Retain `my.program/declaration`: the deletion census found another caller. |

## Exact live probes and checks

MCP envelopes below contained no errors/diagnostics; the full-contract check carried the stated slow finding.

1. JVM, default, explicit root (the earlier lane JVM read returned the same nil stale-row/origin results):

```clojure
(let [c (seon.cluster.boot/connection "default") d @c]
  {:review/basis (seon.db/basis-t d)
   :review/stale-row (seon.db/pull d [:seon.fn/sym :seon.program/definition-digest]
                                 [:seon.fn/sym 'user.sgprobe/fix])
   :review/schemas (mapv (fn [k] [k (get (:schema d) k)])
                        [:seon.cluster.eval/author :seon.cluster.eval/origin :seon.agent/answered-t])})
```

Result `result/e1fe317ad80fa`: basis 536871068, stale-row nil, author installed as keyword/cardinality-one,
origin and answered-t absent; no `:ms` field in this envelope. Lane JVM probe `result/e25272ec0dbcc`, 3 ms,
also resolved installed Vars `code-request`, `read-next`, contracts `check`, `submission-open-tx`, `with-submission`.

2. SCI, agent `plan-v2-review`, scratch namespace `scratch.planv2`, one submission:

```clojure
(ns scratch.planv2 (:require [clojure.string :as s]))
::s/probe
`s/join
```

Values nil, `:clojure.string/probe`, `clojure.string/join`; each 0 ms as rounded by MCP.
Handles `e86f362d2bbbe`, `e1c51c1d50f9e`, `eb91ac0bca922`. This is sequential alias evidence, not a benchmark.

3. JVM root, default, query cost (one finite binding; no profile mark that would inflate counters):

```clojure
(let [d @(seon.cluster.boot/connection "default")
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.getId (Thread/currentThread))
      a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
      t (System/nanoTime)
      v (seon.db/q '[:find ?sym ?digest :in $ [?sym ...]
                    :where (or [?e :seon.fn/sym ?sym] [?e :seon.test/sym ?sym])
                    [?e :seon.program/definition-digest ?digest]] d ['seon.id/id])]
  {:review/value v :review/ms (/ (- (System/nanoTime) t) 1000000.0)
   :review/allocated-bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid) a)})
```

`result/e183596dd6320`: `#{[seon.id/id "d8b6486179b278a7f5fb6d2b4d0a1f4da4c1458b45842790ca45e18a93fd405c"]}`,
60.713417 ms, 180851688 bytes. Existing query-cost task above; no new duplicate task. Armed calls/store bytes
not measured for this review probe. No claimed before/after optimization.

4. Static candidate checks: archive of HEAD's `src script test resources .clj-kondo deps.edn`, with ignored lint
configuration linked exactly as bin/lane does; no shared dirty files. The sole diff is this Markdown note, so the
program inputs are identical to HEAD. **Archive lint: 5,810 ms, 0 errors, 1,481 warnings**. An initial incidental
working-tree lint also completed (10,964 ms, 0 errors, 1,481 warnings); it is not the candidate proof. Exit 2 is
clj-kondo's warning status; no error was hidden. No warnings were claimed fixed.

Installed `seon.contracts-compile-test/check` ran via MCP JVM root over **all 471 archived src/test .clj/.cljc
files**, `:root` set to the archive and `:candidate` from `(seon.db/carried-projection
@(seon.cluster.boot/connection "default"))`. The checker compares packaged forms before reusing that candidate
(`test/seon/contracts_compile_test.clj:802`), then compiles through Malli `m/function-schema` (`:697`).
Result `result/e98a9e188ece9`: findings `[]`, **1,720.555541 ms / 3,176,962,216 calling-thread bytes**; MCP measured
1,736 ms and returned `:slow`. This required whole-corpus check is proportional to 471 files; linked existing
`seon.schema/contract-check-on-three-files-exceeds-one-second`. This is static compilation, not a test run, boot,
or adoption proof. No definitions were loaded/redefined. Allocation remains a gate-cost finding.

5. Root SCI cleanup: `(my.agent/retire-branch! {:seon.store/branch :agent-1d8a870dc1e1})` returned
`:my.agent/retired? true`, 91 ms, 1,145 calls (`result/e0b6960456693`). This is the only branch created here.
No resource created by this lane requires default restart/reset.

## Tests for root's central round and open questions

No runtime test is required by this notes-only commit. For the plan's affected behaviors, preserve/convert these
exact existing tests; none was run by this lane:

- `seon.mcp-entrance-test/an-sci-call-answers-what-the-agents-next-context-reads`
- `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`
- `seon.mcp-entrance-test/an-agent-runs-its-own-test-on-its-own-branch`
- `seon.mcp-entrance-test/a-one-defn-run-settles-in-proportion-to-the-defn`
- `seon.run-test/item-1-a-stage-throw-is-a-diagnostic-beside-each-value`
- `seon.run-test/item-19-a-write-after-the-observation-closed-is-refused-to-its-writer`
- `seon.turn-test/a-call-omitting-its-database-sees-the-forms-own-write`
- `seon.turn-test/a-definition-changed-since-the-run-read-it-is-refused-at-the-writer`
- `seon.turn-test/a-schema-key-changed-since-the-run-read-it-is-refused-at-the-writer`
- `seon.turn-test/a-settlement-that-throws-still-shows-each-evaluated-value`
- `seon.turn-test/settlement-claims-covered-messages-without-removing-their-wakes`
- `seon.effect-test/request-commits-before-io-dispatch-and-settles-once`
- `seon.effect-test/effect-refs-its-evaluation-and-handler`
- `seon.blob-error-test/publication-preserves-a-refused-root-transaction`
- `seon.cluster.agent-test/disarm-drops-the-route-before-closing-it`
- `seon.cluster.host-test/an-unsettled-host-run-keeps-ownership-until-it-exits`

New regression obligations (proposed, not existing tests): same-agent model/SCI/JVM serialization; wake during a
model call remains unanswered; outside work cannot acknowledge it; crash after effect before settlement; unknown
settlement prevents overlap; opening once per assignment across restart; a new message/proof failure becomes
visible through an ordinary recorded evaluation; one kondo invocation with same-reply macro/alias/definition
changes; every entrant's fault/blob/refusal preserves values and the declared transaction count.

Questions for plan revision: What is the once-recorded input exchange for new messages and proof failures? How
does one end-of-reply analysis preserve same-pass admission without re-expanding macros? Which fault/completion
facts are authoritative on which branch? What exact preservation manifest is approved for R? What finite S0
workload bounds the <5 MB/<200 ms targets while history remains whole? These are missing design decisions, not
requests to revisit §1.

Design check: deletion and shared stage values hold up; treating the submission helper as the entire design does
not. In-place preparations plus one complete cut are smaller and more reviewable than a replacement runner.
Net source/test lines: **0**. Only this note is claimed for commit. Historical multi-second/GB operations were
not re-run merely to reconfirm them; no full old-population history, provider, successful test, crash, publication,
or browser proof is claimed. Runtime defects are linked to their existing task classes above and in r-read /
r-record; plan-only gaps are findings for root, not invented runtime incidents.
