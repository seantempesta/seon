---
type: research
status: active
tags: [research, runtime]
---

# Capability ledger for the execution deletions

This is the owner-keyed index of functionality removed by the execution
deletion program through commit `1832764de`. It is an index of capabilities,
not a request to restore deleted namespaces. The conversion test is
simplification: pure returned values, genuine capability requests through one
guarded door, and durable facts committed by the driver are the only
agent-facing shapes.

The primary deletion is `8dc8623ad` (48 files, 155 insertions, 12,110
deletions; 5,715 deleted source lines under `src/seon/host.clj` and
`src/seon/host/`). Earlier capability cuts are also included:
`fbc6b28b5`, `02563d013`, `c45616a38`, `bd12fdc7d`, `f6f6673b6`,
`60a3b9621`, `2911dfbba`, `78aab36a0`, `851a2edc7`, and `a9422a1e7`.

The supplied history pointer `574ac70ed` is not an O12 deletion in this
checkout. It is “Prove mixed namespace reply evaluation” and adds 15 test
lines. The actual O12 cuts are distributed across `60a3b9621`,
`bd12fdc7d`, `c45616a38`, `851a2edc7`, and `a9422a1e7`.

## 1. How to read a row

Each row has exactly four fields:

- **Capability** states what a user or agent could do.
- **Deleted implementation** points into the parent of the deletion commit.
  The line ranges are historical source coordinates, not current files.
- **Verdict** uses exactly one of `ALREADY OWNED`, `RE-IMAGINED`, `NOT
  NEEDED`, or `GAP`. “Currently absent” is not automatically a gap: when the
  replacement shape is already settled and deletes mechanisms, the verdict is
  `RE-IMAGINED`.
- **Simpler?** names the reduction. A replacement that preserved the old
  mechanism count would be a port and is rejected.

The public-form census searched top-level `def`, `defn`, `defmacro`, and
`schema/register!` forms in the deleted production sources. It found 788 forms:
224 in the `8dc8623ad` source cut, 99 in `f6f6673b6`, 84 in `fbc6b28b5`, 26
in `60a3b9621`, 59 in `bd12fdc7d`, 98 in `2911dfbba`, three in `78aab36a0`,
44 in `c45616a38`, 122 in `851a2edc7` (77 are adversarial harness forms), 14
in `02563d013`, and 15 schema registrations in `a9422a1e7`. The 49 rows below
are the capability collapse of that census. The 63-file pod disposition is not
duplicated here; see
[[pod-cut-verdict-2026-07-26]], which records DELETE-NOW 48/24,037 lines,
DELETE-WITH-RENDER 15/6,920, and PORT-TO-CLJC zero.

## 2. Capability index

**Count summary: 13 ALREADY OWNED / 15 RE-IMAGINED / 16 NOT NEEDED / 5
GAP — 49 capabilities total.**

The 16 `NOT NEEDED` capabilities are the clearest explanation of how a
12,110-line cut can remove so much code without removing 12,110 lines of
product function: session, child, retained-context, runtime-indexing, placement,
and phase assumptions each carried large state machines.

### Surviving owner: `seon.repl.parse`

#### 1. Parse a model reply into executable forms while retaining prose, fences, and namespace structure

- **Capability:** An agent can return mixed explanation and Clojure and have the executable program identified in source order.
- **Deleted implementation:** `fbc6b28b5^:src/seon/eval.cljs:2043-2280,3173-3335` and `fbc6b28b5^:src/seon/repl.cljs:1-296`.
- **Verdict:** **ALREADY OWNED** — `src/seon/repl/parse.cljc:941-1239,1424-1517` owns the pure parser/projector, called by `src/seon/agent/driver.clj:584-591`.
- **Simpler?** Yes: compiler-state parsing and a second REPL disappear into one pure source projection.

#### 2. Repair a malformed reply before execution

- **Capability:** A repairable delimiter or unresolved-symbol mistake can become an exact corrected form vector rather than losing the whole reply.
- **Deleted implementation:** `8dc8623ad^:src/seon/host/preflight.clj:21-121,137-189,268-344` and the mid-loop splice at `8dc8623ad^:src/seon/host/eval.clj:381-465`.
- **Verdict:** **RE-IMAGINED** — shape 1, a pure pre-plan transformation. `src/seon/repl/parse/repair.cljc:292-336` supplies delimiter repair; corpus-assisted semantic correction queries one immutable database value and returns corrected sources or an error value before `seon.agent.driver/plan-tx-data`. The disposable SCI analysis context, mutable queue splice, and post-plan source rewrite disappear.
- **Simpler?** Yes: one complete reply becomes one frozen ordered form vector before any receipt exists.

The surviving parser does **not** already own old preflight. Its namespace
documentation at `src/seon/repl/parse.cljc:74-84` explicitly declines automatic
missing-paren repair, and the driver rejects the entire reply on any parser
error at `src/seon/agent/driver.clj:584-591`. Section 8.2 of
[[measurements-2026-07-25]] measured six emitted entries becoming seven
executed forms because old preflight spliced during execution. Current
`:seon.eval/total` is coherent only because it counts the already-frozen plan;
repair restored after plan commit would invalidate the resume proof.

### Surviving owner: `seon.sci.interrupt` and `seon.sci.eval`

#### 3. Stop interpreted computation after a configured time limit

- **Capability:** A spinning authored function is stopped without crashing or wedging the cluster JVM.
- **Deleted implementation:** `8dc8623ad^:src/seon/host/guard.cljc:51-250`, `8dc8623ad^:src/seon/host/eval.clj:101-250`, and `851a2edc7^:src-flow-prototype/src/flow/interrupt.clj:16-65`.
- **Verdict:** **ALREADY OWNED** — `src/seon/sci/interrupt.clj:53-105` owns the one `:interrupt-fn`; `src/seon/sci/eval.clj:82-139` arms it and returns flat error values.
- **Simpler?** Yes: interpreter-step arrays, policy modes, and session deadlines become one `time-limit` plus diagnostics.

#### 4. Bound concurrent SCI evaluations while one blocked call retains its permit

- **Capability:** One bad evaluation cannot invent capacity or consume every evaluation slot.
- **Deleted implementation:** `8dc8623ad^:src/seon/host.clj:29-65,333-338`, `8dc8623ad^:src/seon/host/invoke.clj:37-263`, and `851a2edc7^:src-flow-prototype/src/flow/eval.clj:23-68`.
- **Verdict:** **ALREADY OWNED** — `src/seon/sci/eval.clj:33-56,87-139` owns the semaphore and releases a permit only when the platform thread returns.
- **Simpler?** Yes: fixed host pools, invocation workers, and child accounting become one process-wide permit count.

#### 5. Catch normal SCI errors without allowing an authored catch to swallow the interrupt

- **Capability:** Authored code can catch ordinary throwable roots, while an infinite loop still terminates with outcome `:time`.
- **Deleted implementation:** class policy and throwable conversion at `8dc8623ad^:src/seon/host/context.clj:1385-1424`, `8dc8623ad^:src/seon/host/guard.cljc:164-250`, and `fbc6b28b5^:src/seon/eval.cljs:2679-2819`.
- **Verdict:** **ALREADY OWNED** — `src/seon/sci/ctx.clj:15-35` exposes `Throwable`, `Error`, and SCI's existing `Exception`; `src/seon/sci/interrupt.clj:101-105` recognizes SCI's uncatchable marker. Commit `ce5e061f2` proves the catch/loop case.
- **Simpler?** Yes: a small explicit class map and SCI's marker replace host-specific throwable policy.

#### 6. Isolate shared vars and definitions between independent forks

- **Capability:** One agent or evaluation cannot mutate another agent's interpreter namespace accidentally.
- **Deleted implementation:** self-host result/shared-var machinery at `fbc6b28b5^:src/seon/eval.cljs:946-1120,1370-1616` and retained-context stamping at `8dc8623ad^:src/seon/host/context.clj:511-597,1385-1496`.
- **Verdict:** **ALREADY OWNED** — `src/seon/sci/ctx.clj:15-42` owns one base and a fresh `sci/fork` for every evaluation.
- **Simpler?** Yes: mutable cross-fork stamping and cleanup disappear; visibility comes only from a database basis.

#### 7. Admit and deeply realize authored values before the armed boundary closes

- **Capability:** Lazy authored data is fully realized, bounded, and diagnosed before any renderer, serializer, or driver touches it.
- **Deleted implementation:** result admission and sampling at `fbc6b28b5^:src/seon/eval.cljs:1058-1120,2663-2819` and `8dc8623ad^:src/seon/host/sample.clj:1-116`.
- **Verdict:** **RE-IMAGINED** — shape 1, a total ordinary-value admission operation inside `seon.sci.eval/evaluate` before `stop!`. The raw-lazy return, per-consumer realization, and dropped diagnostic path disappear. See [[../../../seon/issues/lazy-authored-values-escape-the-armed-interrupt-boundary]].
- **Simpler?** Yes: one choke point replaces every downstream consumer's attempted containment.

Commit `40ea7e29c` measured a returned `LazySeq` with zero authored callbacks
inside evaluation and one outside. Current `src/seon/sci/eval.clj:108-130`
returns SCI's raw value and stops the timer first; the current driver terminal
projection also drops `:seon.eval/fn-entries` and allocated bytes. The new
admission returns either an eager ordinary value or a flat error, and the
terminal receipt carries the diagnostic record.

### Surviving owner: `seon.agent.driver` and durable run facts

#### 8. Freeze an ordered form plan and identify the first unterminated form

- **Capability:** Form order and completed ordinals survive process loss as database facts.
- **Deleted implementation:** batch state at `8dc8623ad^:src/seon/host/eval.clj:334-552`, receipt builders at `8dc8623ad^:src/seon/host/record.clj:301-400`, and prototype plan state at `851a2edc7^:src-flow-prototype/src/flow/program.clj:1-33`.
- **Verdict:** **ALREADY OWNED** — `src/seon/agent/driver.clj:112-153` builds and orders committed form facts; `src/seon/eval/receipt.cljc:121-200` owns receipt identity and terminal state.
- **Simpler?** Yes: a mutable batch context becomes form facts plus receipt queries.

#### 9. Claim, fence, renew, release, finish, and take over an expired run

- **Capability:** Exactly one process holds a run at an epoch, and another process can resume after the lease expires.
- **Deleted implementation:** `bd12fdc7d^:src/seon/agent/run.cljs:417-1101` and `851a2edc7^:src-flow-prototype/src/flow/driver.clj:87-195`.
- **Verdict:** **ALREADY OWNED** — pure transition builders live at `src/seon/agent/run/core.cljc:78-221`, including `run-fence` at 104-114 and `claim-plan` at 156-192.
- **Simpler?** Yes: heartbeat/watchdog API state becomes process, epoch, lease, and CAS facts.

#### 10. Return lifecycle intent as an ordinary value

- **Capability:** Agent code can wait, complete, pause, resume, or terminate without mutating run state from inside SCI.
- **Deleted implementation:** effectful lifecycle leaves at `02563d013^:src/seon/agent/lifecycle/core.cljc:1-114`, `leaf.cljc:1-54`, `pod.cljs:1-19`, plus host bindings at `8dc8623ad^:src/seon/host/context.clj:638-812`.
- **Verdict:** **ALREADY OWNED** — `src/seon/agent/lifecycle.cljc:42-86` returns disposition values, interpreted by `src/seon/agent/driver.clj:74-110`.
- **Simpler?** Yes: leaf calls and in-eval transactions disappear.

#### 11. Settle a form, turn, run, and terminal user message atomically

- **Capability:** A terminal result cannot publish a message while leaving the run or receipt open.
- **Deleted implementation:** `f6f6673b6^:src/seon/agent/turn.cljs:546-719`, `f6f6673b6^:src/seon/agent/turn/core.cljc:34-195`, and `bd12fdc7d^:src/seon/agent/run.cljs:542-787`.
- **Verdict:** **ALREADY OWNED** — `src/seon/agent/driver.clj:74-110,225-323` combines disposition transaction data with the terminal receipt under the run fence; `src/seon/agent/run/core.cljc:209-221` closes and detaches a run.
- **Simpler?** Yes: phase close APIs and delivery side channels become one transaction.

#### 12. Persist an agent-authored function, namespace, schema, and test

- **Capability:** Code authored in one evaluation becomes durable corpus data in the same identity space as first-party code.
- **Deleted implementation:** `8dc8623ad^:src/seon/host/record.clj:120-157,249-301,343-483` and the older duplicate at `fbc6b28b5^:src/seon/eval.cljs:1848-1956,2281-2638,3093-3335`.
- **Verdict:** **RE-IMAGINED** — shape 3, canonical `:seon.fn`, `:seon.ns`, `:seon.schema`, require-edge, and applicable `:seon.test` facts in the driver's terminal transaction. The tee and post-commit install handshake disappear. See [[../../../seon/issues/driver-terminal-transactions-do-not-commit-authored-corpus-facts]].
- **Simpler?** Yes: one terminal transaction replaces tee, projection refresh, and install.

The terminal transaction at current `src/seon/agent/driver.clj:225-323` is the
right home, and `src/seon/db/program.clj:19-20,30-95,100-175` already reads the
same identities. Two corrections matter:

- `:seon.fn/source` alone is not a complete row. The canonical minimum includes
  function symbol, namespace, and source (`src/seon/schema.cljc:409-470`).
- Current driver transactions use user/REPL provenance unless the terminal
  commit binds agent and process context (`src/seon/db/host.clj:949-964`,
  `src/seon/db/internal.cljc:585-591`). The replacement must correct
  provenance in the same implementation unit.

#### 13. Make durable authored code available to later evaluations and other agents

- **Capability:** A function committed by one agent can be required and called from a later basis without installation ceremony.
- **Deleted implementation:** registry/load/cache at `8dc8623ad^:src/seon/host/context.clj:511-636,1385-1496`, nursery/rebuild at `8dc8623ad^:src/seon/host/graduate.clj:197-276`, and the terminal install handshake at `8dc8623ad^:src/seon/host/eval.clj:311-332,508-528`.
- **Verdict:** **RE-IMAGINED** — shape 3 facts acquired by a basis-fenced query/materializer through the shared SCI base's one `:load-fn`. Registry, retained contexts, install/rebuild, the tee handshake, and `:seon.fn/execution-tier` disappear. See [[../../../seon/issues/cluster-jvm-boot-does-not-install-durable-corpus-functions]].
- **Simpler?** Yes: roughly five runtime mechanisms become one corpus transaction plus one basis query/materializer.

The owner's “five mechanisms to one fact plus one query” is directionally
correct but “one fact” is too literal. Namespace availability requires
canonical namespace, function, schema, and require-edge facts. SCI consults
`:namespaces` first and `:load-fn` only on a namespace miss
(`reference-code/sci/src/sci/impl/load.cljc:180-206`,
`impl/opts.cljc:47-63,275-300`, `README.md:589-630`). The current base at
`src/seon/sci/ctx.clj:15-35` has no `:load-fn`.

#### 14. Make a definition in form 1 visible to form 2 of the same reply

- **Capability:** A multi-form reply can define a function and call it in the next committed form.
- **Deleted implementation:** retained batch context and declared-next-namespace handling at `8dc8623ad^:src/seon/host/eval.clj:185-250,282-334`, with self-host namespace setup at `fbc6b28b5^:src/seon/eval.cljs:946-1038,1616-1775`.
- **Verdict:** **RE-IMAGINED** — fold the previous terminal transaction report's `:db-after` and current namespace into the next evaluation; materialize that namespace from corpus facts into a fresh fork. Retained interpreter state disappears.
- **Simpler?** Yes: immutable database value plus namespace identity replace a retained mutable context.

The defect is current. `src/seon/agent/driver.clj:225-329` invokes evaluation
without a base context, and `src/seon/sci/eval.clj:82-105` forks the pristine
base. A disposable JVM probe on commit `42a9faf2e`, using
`.cpcache/2925466413.cp`, called `seon.sci.eval/evaluate` first with
`(defn capability-ledger-two-form [] 42)` and then with
`(capability-ledger-two-form)`: form 1 returned
`#'user/capability-ledger-two-form`; form 2 returned
`Unable to resolve symbol`. The complete live driver/database reply remains
**[UNVERIFIED]** because the default cluster was down during this audit.

Section 8.1 of [[measurements-2026-07-25]] already measured the required
read-your-own-writes property: the same read returned 0 at the turn basis and 9
at the previous form's `:db-after`. `:load-fn` alone is insufficient for a bare
symbol in an already-present namespace; the fresh fork must materialize the
current namespace from that basis.

#### 15. Resume execution from the committed plan after process loss

- **Capability:** A replacement process continues at the first form without a terminal receipt and never calls the model again.
- **Deleted implementation:** run/session resumption at `bd12fdc7d^:src/seon/agent/run.cljs:238-387,788-1101`, `c45616a38^:src/seon/agent/driver.cljc:233-508`, and `c45616a38^:src/seon/agent/driver/host.clj:318-672`.
- **Verdict:** **ALREADY OWNED** — commit `3946b7192` made `src/seon/agent/driver.clj:343-455,675-720,813-880` scan recoverable runs, acquire at a lease/epoch, select `next-form`, and drive the committed sources. The process-local reply and model call are absent from resumption.
- **Simpler?** Yes: resumption is a database query plus the existing form executor.

#### 16. Wake exactly when committed work or an expired lease becomes actionable

- **Capability:** New messages and lease expiry drive work without polling every agent or waking on the driver's own writes.
- **Deleted implementation:** listener, trigger, ticker, and activity machinery at `60a3b9621^:src/seon/agent/loop.cljs:142-649`.
- **Verdict:** **ALREADY OWNED** — commits `3946b7192` and `1832764de` landed database-interest scans and exact lease wake readiness in `src/seon/agent/driver.clj:813-880` and `src/seon/agent/run/core.cljc:97-102`. Activity channels, periodic ticks, and per-agent triggers are gone.
- **Simpler?** Yes: committed interest and one-shot wake replace a ticker subsystem.

#### 17. Make one hosted-provider completion request

- **Capability:** The driver can send the frozen model request and receive text or a flat provider error.
- **Deleted implementation:** provider phase execution at `f6f6673b6^:src/seon/agent/turn/llm.cljc:299-537`.
- **Verdict:** **ALREADY OWNED** — `src/seon/ai/http.clj:172-264` owns the HTTP request and response; the current driver calls it from `src/seon/agent/driver.clj:561-582,722-813`.
- **Simpler?** Yes: a phase envelope becomes one ordinary request value and one host call.

#### 18. Preserve provider retries, fallbacks, usage, and transport evidence

- **Capability:** A failed provider attempt can retry from the same frozen input, and later inspection can explain every attempt.
- **Deleted implementation:** attempt schemas and transitions at `f6f6673b6^:src/seon/agent/turn.cljs:81-261`, `turn/core.cljc:34-256`, and `turn/llm.cljc:162-447`.
- **Verdict:** **RE-IMAGINED** — shape 3, append bounded attempt facts and prompt/reply blob refs from one frozen request value; the driver interprets a pure retry disposition. Turn phases, nested component mutation, and side-channel evidence disappear.
- **Simpler?** Yes: immutable request plus attempt facts replace the turn phase machine.

#### 19. Publish streaming progress without a second delivery channel

- **Capability:** A user can see a coalesced current partial reply while the provider is still producing it.
- **Deleted implementation:** `f6f6673b6^:src/seon/agent/turn/llm.cljc:47-137,132-146,326-447` and `turn/core.cljc:132-141`.
- **Verdict:** **RE-IMAGINED** — shape 3, coalesced complete-value snapshots in one cardinality-one, unindexed, no-history database attribute, retracted in the terminal transaction. Scheduled callbacks, attempt component rows, and any non-database stream disappear.
- **Simpler?** Yes: one optional current fact feeds the existing interest/equality/latest-wins chain.

#### 20. Expose run administration as queries, facts, and lifecycle values

- **Capability:** An operator can inspect availability and request pause, resume, terminate, or injection without calling a pod run API.
- **Deleted implementation:** `bd12fdc7d^:src/seon/agent/run.cljs:238-416,788-1101` and loop activity at `60a3b9621^:src/seon/agent/loop.cljs:632-649`.
- **Verdict:** **RE-IMAGINED** — shape 1 query values plus shape 3 requested facts interpreted by the driver. Effectful `pause!`/`resume!`, watchdog closes, and activity-log RPC disappear.
- **Simpler?** Yes: administrative intent becomes ordinary data at the existing driver boundary.

### Surviving owner: prompt, context, render, and corpus acquisition

#### 21. Compose a frozen prompt from one immutable database value

- **Capability:** Model input is derived once from agent facts, configuration, and selected context blocks.
- **Deleted implementation:** prompt invocation and render phase at `f6f6673b6^:src/seon/agent/turn.cljs:356-546` and host prompt bindings at `8dc8623ad^:src/seon/host/context.clj:443-495,812-1018`.
- **Verdict:** **ALREADY OWNED** — `src/seon/agent/prompt.cljc:50-94` is a pure composer over already-acquired values.
- **Simpler?** Yes: the prompt function has no database read, phase transition, or renderer side channel.

#### 22. Evaluate authored render code once and reuse its exact output

- **Capability:** An agent-authored canvas is contained, rendered once for an exact input, and reused across tabs and reconnects.
- **Deleted implementation:** pod-served authored rendering and context drivers covered by `f6f6673b6^:src/seon/agent/turn.cljs:376-545` and the 15 DELETE-WITH-RENDER files indexed in [[pod-cut-verdict-2026-07-26]].
- **Verdict:** **RE-IMAGINED** — shape 1 guarded evaluation followed by shape 3 committed eager render data keyed by exact consumption identity. Per-tab SCI evaluation, process-local output caches, and pod rendering disappear. The grounded design and mockup are in [[jvm-render-design-2026-07-26]].
- **Simpler?** Yes, if implemented as one producer and one durable output; a per-tab port would fail this row.

#### 23. Build the first-party program index and initialization pages

- **Capability:** A fresh cluster starts with every compiled function, schema, test, and namespace already indexed.
- **Deleted implementation:** self-host analyzer projection at `fbc6b28b5^:src/seon/analyzer_info.cljs:36-349`, runtime program acquisition at `2911dfbba^:src/seon/execution.cljs:341-926`, and placement registrations removed by `a9422a1e7^:src/seon/schema.cljc:404-470`.
- **Verdict:** **RE-IMAGINED** — O15/O16: one JVM build indexes at compile time and emits mandatory initialization pages; fresh boot loads pages and resume reads config overrides then the database. Shadow indexing, runtime source reading, runtime derivation, and `seon.db.program/compile-tx-data` as a runtime reconciler disappear.
- **Simpler?** Yes: two runtime states consume facts; neither indexes source.

#### 24. Derive toolkit bindings and discovery from the compiled corpus

- **Capability:** An agent can discover and call the maintained `my.*`, database, messaging, blob, filesystem, shell, and web surface without hand-maintained lists.
- **Deleted implementation:** `8dc8623ad^:src/seon/host/context.clj:616-636,638-1018,1020-1383` and authored alias setup at `fbc6b28b5^:src/seon/eval.cljs:1616-1684`.
- **Verdict:** **RE-IMAGINED** — compile-time corpus indexing plus basis-fenced namespace materialization derives the callable surface and discovery views. `host-toolkit-bindings`, `host-toolkit-implementation-namespaces`, per-agent alias install, and a second search registry disappear.
- **Simpler?** Yes: one corpus answers both “what exists?” and “load it.”

#### 25. Derive capability effects and replay behavior from the real functions

- **Capability:** The driver can distinguish pure, read, idempotent, and external calls and make duplicate writes safe.
- **Deleted implementation:** wrapper metadata and invocation receipts at `8dc8623ad^:src/seon/host/context.clj:131-162,638-1018,1877-2184`, with inventory projection at `8dc8623ad^:src/seon/host.clj:286-305`.
- **Verdict:** **RE-IMAGINED** — compile-time function facts carry source metadata and call-graph edges; genuine write requests use the existing operation ID contract in `src/seon/db.cljc:29-30,398-429` and `src/seon/agent/message.cljc:266-279,475-537`. Runtime wrapper inventory and per-session effect receipts disappear.
- **Simpler?** Yes: function facts plus the one request identity replace wrapper inspection.

#### 26. Admit durable function contracts and apply instrumentation

- **Capability:** A durable authored function cannot publish without a complete Malli contract, and loaded calls are instrumented from the program graph.
- **Deleted implementation:** durable-defn preflight at `8dc8623ad^:src/seon/host/preflight.clj:217-266`, wrapper reconciliation at `8dc8623ad^:src/seon/host/instrument.clj:48-242`, and analyzer projections at `fbc6b28b5^:src/seon/analyzer_info.cljs:96-349`.
- **Verdict:** **RE-IMAGINED** — shape 3 terminal admission validates canonical function/schema facts; acquisition derives instrumentation from the indexed graph at the basis. Preflight disposable evaluation, watches, registry mutation, and cross-context refresh disappear.
- **Simpler?** Yes: validate once at publication, derive once at acquisition.

#### 27. Invoke an authored symbol with ordinary arguments and a current namespace

- **Capability:** A driver or renderer can call a corpus function by symbol and receive an ordinary value or flat error.
- **Deleted implementation:** `8dc8623ad^:src/seon/host/invoke.clj:64-263`, namespace resolution at `8dc8623ad^:src/seon/host/eval.clj:31-100,282-334`, and selected invocation at `2911dfbba^:src/seon/execution.cljs:953-1071`.
- **Verdict:** **RE-IMAGINED** — shape 1, a call plan names symbol, arguments, database value, and namespace; the one SCI evaluator materializes the namespace and returns an admitted value. Session invocation IDs, retained namespaces, and a separate authored-call protocol disappear.
- **Simpler?** Yes: invocation is data consumed by the existing evaluator.

#### 28. Retain process-local result-symbol handles

- **Capability:** A later form can address a tier-local value that has no ordinary durable projection.
- **Deleted implementation:** `fbc6b28b5^:src/seon/eval.cljs:1038-1120,1370-1616` and sampling/ownership protocol at `2911dfbba^:src/seon/execution.cljs:1036-1177`.
- **Verdict:** **RE-IMAGINED** — R32's process-identity-backed result registry binds `result/<id>`, wipes it on process restart, and returns steering to re-derive. The self-host global object, cross-tier sampling messages, and retained per-agent context disappear. See [[../../../seon/issues/jvm-result-symbols-not-bound-r32]].
- **Simpler?** Yes: one explicitly process-local table owns only nonordinary values.

### Assumptions that died with the old system

#### 29. Retain one mutable SCI context per agent

- **Capability:** The old host kept definitions alive by parking an interpreter context for each agent.
- **Deleted implementation:** `8dc8623ad^:src/seon/host.clj:133-171,318-355` and `host/context.clj:1385-1496`.
- **Verdict:** **NOT NEEDED** — the retained-context assumption died; corpus facts plus a fresh fork at a database basis own visibility.
- **Simpler?** Yes: no cache lifecycle, eviction, replay, or shared holder remains.

#### 30. Install and rebuild a mutable wrapper registry

- **Capability:** The old host installed functions into contexts and rebuilt the registry after publication or boot.
- **Deleted implementation:** `8dc8623ad^:src/seon/host/context.clj:511-638`, `host/graduate.clj:197-276`, and `host/eval.clj:311-332,508-528`.
- **Verdict:** **NOT NEEDED** — basis acquisition replaces installation.
- **Simpler?** Yes: registry, installer, rebuild, and handshake all disappear.

#### 31. Lock every read and write while hot-reconciling wrappers

- **Capability:** The old host serialized admission around mutable SCI var replacement.
- **Deleted implementation:** `8dc8623ad^:src/seon/host/instrument.clj:17-242` and call sites at `8dc8623ad^:src/seon/host.clj:136-161,329-332`.
- **Verdict:** **NOT NEEDED** — immutable corpus facts and per-eval forks remove the mutable root race that required the lock.
- **Simpler?** Yes: the lock, watches, fingerprinted roots, and reconciliation passes disappear.

#### 32. Promote functions through nursery and graduated execution tiers

- **Capability:** The old host classified authored functions into native and interpreted installation tiers.
- **Deleted implementation:** `8dc8623ad^:src/seon/host/graduate.clj:15-276`.
- **Verdict:** **NOT NEEDED** — native promotion and trust-gate assumptions died; SCI interprets corpus code and placement is derived later from the call graph.
- **Simpler?** Yes: fingerprint, trust gate, test gate, wrappers, nursery, and rebuild disappear.

#### 33. Store an execution tier and make placement decisions at runtime

- **Capability:** The old system stamped functions and selected a pod, child, or host before invocation.
- **Deleted implementation:** `a9422a1e7^:src/seon/schema.cljc:404-470`, `c45616a38^:src/seon/program/plan.cljc:1-671`, and `2911dfbba^:src/seon/execution/host.cljs:810-1035`.
- **Verdict:** **NOT NEEDED** — the stored tier and runtime router died. Future `plan-execution` derives leaf placement from the compiled call graph; current cluster-JVM code needs no placement record.
- **Simpler?** Yes: stored classification and parallel routing disappear.

#### 34. Admit portable toolkit blocks with a source regex

- **Capability:** The old host guessed portability from source text before evaluating toolkit blocks.
- **Deleted implementation:** `8dc8623ad^:src/seon/host/context.clj:1020-1203`, especially `pure-block?` at 1063-1068.
- **Verdict:** **NOT NEEDED** — O15's JVM compile index and transitive call graph replace name/source heuristics.
- **Simpler?** Yes: regex admission, dependency-sort duplication, and runtime filesystem reads disappear.

#### 35. Maintain two literal toolkit binding lists

- **Capability:** The old host used literal lists to decide which toolkit namespaces and functions existed.
- **Deleted implementation:** `8dc8623ad^:src/seon/host/context.clj:1301-1311` plus the library-by-library installer at 638-1018.
- **Verdict:** **NOT NEEDED** — the compiled corpus is the inventory.
- **Simpler?** Yes: two lists become zero lists and one query.

#### 36. Keep a UDS session protocol between the pod and the run-holding JVM

- **Capability:** The old system negotiated startup, invoke, cancel, ready, result, and error frames.
- **Deleted implementation:** `8dc8623ad^:src/seon/host/session.cljc:1-83`, `host/session/leaf.clj:1-192`, `host/session/leaf.cljs:1-261`, and `host.clj:62-241`.
- **Verdict:** **NOT NEEDED** — O1 co-locates the driver, SCI, and database in the cluster JVM; there is no agent-path wire.
- **Simpler?** Yes: protocol version, frames, codecs, startup handshake, channel state, and cancel messages disappear.

#### 37. Supervise a long-lived execution child and its host/child bands

- **Capability:** The old pod spawned a child, connected a host session, selected a band, retired it, and sampled it.
- **Deleted implementation:** `2911dfbba^:src/seon/execution/host.cljs:1-1390`, `2911dfbba^:src/seon/execution.cljs:1-1520`, and `78aab36a0^:src/seon/execution/runtime.cljs:1-136`.
- **Verdict:** **NOT NEEDED** — O13 removes the pod and long-lived execution children; the cluster JVM holds runs.
- **Simpler?** Yes: process bands, child generations, idle retirement, host reconcile, and cross-tier sampling disappear.

#### 38. Maintain a cljs.js compiler, analyzer state, and replayable bootstrap in the pod

- **Capability:** The old pod compiled and loaded authored CLJS dynamically.
- **Deleted implementation:** `fbc6b28b5^:src/seon/analyzer_info.cljs:1-349`, `src/seon/eval.cljs:260-1038`, and `src/seon/repl.cljs:1-296`.
- **Verdict:** **NOT NEEDED** — one JVM SCI interpreter and compile-time JVM indexing replace self-host compilation.
- **Simpler?** Yes: compiler state, phantom-def cleanup, bootstrap, analyzer projections, and source replay disappear.

#### 39. Defer, auto-await, and race Promises inside the agent evaluator

- **Capability:** The old self-host evaluator managed pending placeholders and Promise timeouts.
- **Deleted implementation:** `fbc6b28b5^:src/seon/eval.cljs:97-259,1155-1370,1775-1847`.
- **Verdict:** **NOT NEEDED** — CLJ evaluation is synchronous; async exists only in platform leaves and long work is addressable by result symbol.
- **Simpler?** Yes: async metadata, placeholders, Promise races, and global warning/print dispatch disappear.

#### 40. Index source and derive projections during runtime startup

- **Capability:** The old runtime could reconstruct program rows or projections when precomputed artifacts were missing.
- **Deleted implementation:** acquisition at `2911dfbba^:src/seon/execution.cljs:341-926` and host projection preparation at `8dc8623ad^:src/seon/host/context.clj:217-366,1016-1203`.
- **Verdict:** **NOT NEEDED** — O15/O16 make missing initialization pages a loud failure; fresh boot loads pages and resume reads database facts.
- **Simpler?** Yes: no runtime source walk, index, projection build, or slow fallback exists.

#### 41. Mutate a turn through render, attempt, and publish phases

- **Capability:** The old loop advanced a durable phase enum while agent evals called turn operations.
- **Deleted implementation:** `f6f6673b6^:src/seon/agent/turn/core.cljc:6-33`, `turn.cljs:546-719`, and `turn/llm.cljc:326-537`.
- **Verdict:** **NOT NEEDED** — the phase-stack assumption died; the driver interprets values and commits terminal facts.
- **Simpler?** Yes: phase enum, successor table, phase fences, and effectful transitions disappear.

#### 42. Poll activity, heartbeat runs, and close them by turn/deadline watchdogs

- **Capability:** The old loop used periodic ticks, beats, deadlines, pause timestamps, and turn budgets to infer progress.
- **Deleted implementation:** `60a3b9621^:src/seon/agent/loop.cljs:229-649` and `bd12fdc7d^:src/seon/agent/run.cljs:28-137,387-416,788-1101`.
- **Verdict:** **NOT NEEDED** — process/epoch/lease facts and terminal receipts replace inferred liveness and work-budget ceremony. Explicit administrative intent is row 20, not restoration of these fields.
- **Simpler?** Yes: seven dangling run-policy attributes and the ticker/watchdogs disappear.

#### 43. Serialize every result through a child/host wire frame

- **Capability:** The old system converted every result to transit-safe data and enforced transport frame sizes.
- **Deleted implementation:** `8dc8623ad^:src/seon/host/eval.clj:101-183`, `host/session.cljc:6-83`, both session leaves, and `2911dfbba^:src/seon/execution.cljs:24-340`.
- **Verdict:** **NOT NEEDED** — there is no agent-path wire in the co-located JVM. Bounded ordinary-value admission remains necessary and is the separate GAP row 45.
- **Simpler?** Yes: codec projection and frame protocol disappear without waiving value bounds.

#### 44. Keep the flow prototype and adversarial harness as a runtime subsystem

- **Capability:** The prototype could demonstrate claims, leases, crashes, starvation, and resource attacks through a second flow implementation.
- **Deleted implementation:** all 42 files at `851a2edc7^:src-flow-prototype/`, including `src/flow/driver.clj:1-195`, `eval.clj:1-68`, `interrupt.clj:1-65`, and 77 public attack-harness forms.
- **Verdict:** **NOT NEEDED** — its lessons now constrain the one production driver and recurring boundary tests; a second runtime is forbidden.
- **Simpler?** Yes: proofs remain as contracts, not a parallel implementation.

### Unsettled surviving owners

#### 45. Let agent code call database, filesystem, shell, web, blob, messaging, and LLM capabilities

- **Capability:** An agent can perform the genuine external/read/idempotent operations required to complete real work.
- **Deleted implementation:** the only complete binding/routing path at `8dc8623ad^:src/seon/host/context.clj:411-495,638-1018,1301-1383` and session invocation at `host/invoke.clj:64-263`.
- **Verdict:** **GAP** — current `src/seon/sci/ctx.clj:15-35` exposes lifecycle only. The portable function families survive, but no accepted one-door JVM binding/dispatch design connects them to SCI. Observably, an authored form cannot call `seon.db/query`, `my.blob/get`, filesystem, shell, or web.
- **Simpler?** Not yet. The closing design must be one guarded door with computed bindings; recreating the registry or per-family session routes would be an equally complex port.

#### 46. Bound captured print output and stored result bytes

- **Capability:** Authored `println` and returned values cannot allocate or store unbounded text, and useful bounded output reaches the receipt.
- **Deleted implementation:** output capture at `8dc8623ad^:src/seon/host/eval.clj:151-183`, output policy in `host/guard.cljc:41-44,111-250`, frame/result limits in both session leaves and `host/session.cljc:6-83`, and self-host clipping at `fbc6b28b5^:src/seon/eval.cljs:2663-2819`.
- **Verdict:** **GAP** — `src/seon/sci/eval.clj:82-139` captures no print output, while the current driver terminal projection at `src/seon/agent/driver.clj:156-169` uses unbounded `pr-str`. Prints are lost and a large printable value has no byte cap. No single bounded-writer/value-projection contract has yet been accepted.
- **Simpler?** Not yet. One bounded admission inside `seon.sci.eval` must replace global print dispatch, session writers, wire sampling, and driver `pr-str`.

#### 47. Contain arbitrary allocation by authored code

- **Capability:** One agent cannot exhaust the cluster JVM heap with allocation that stays within its time limit.
- **Deleted implementation:** allocation/step policy state at `8dc8623ad^:src/seon/host/guard.cljc:8-250`, child resource containment at `2911dfbba^:src/seon/execution/host.cljs:109-320,489-697`, and resource attacks at `851a2edc7^:src-flow-prototype/attack-resource/:1-426`.
- **Verdict:** **GAP** — `src/seon/sci/interrupt.clj:1-5,46-96` explicitly records allocation only as a diagnostic. O4 is unratified. A fast allocator can still damage the shared cluster JVM before a time limit is useful.
- **Simpler?** Not yet. The owner must choose enforceable process isolation or a real allocation boundary; a counter that only observes is not containment.

#### 48. Cancel or isolate one blocking host call

- **Capability:** A filesystem, network, or foreign call that never returns cannot retain a compute permit forever or require killing every agent in the cluster JVM.
- **Deleted implementation:** child/session cancellation at `8dc8623ad^:src/seon/host/invoke.clj:26-63,91-263`, `host/session.cljc:8-80`, and child termination at `2911dfbba^:src/seon/execution/host.cljs:175-320,1293-1378`.
- **Verdict:** **GAP** — `src/seon/sci/eval.clj:125-139` deliberately retains the permit until the platform thread returns, but there is no accepted boundary for an unobservable blocked host call after long-lived children were deleted.
- **Simpler?** Not yet. The replacement must express workload/dependency placement and supervise genuinely foreign work; adding deadline threads around every call would recreate cancellation ceremony.

#### 49. Enumerate and load a JS/CLJS package's callable surface without the Shadow analyzer

- **Capability:** A future disposable leaf runtime can advertise installed package functions so `plan-execution` can route calls.
- **Deleted implementation:** self-host analyzer inventory at `fbc6b28b5^:src/seon/analyzer_info.cljs:36-349`, program acquisition at `2911dfbba^:src/seon/execution.cljs:341-926`, and the child artifact runtime at `78aab36a0^:src/seon/execution/runtime.cljs:1-136`.
- **Verdict:** **GAP** — O15 deliberately deletes Shadow indexing and records package surface enumeration as an open problem for the final packages wave. No manifest, self-report, or one-shot build design has been selected.
- **Simpler?** Not yet. The final mechanism must be package-native and compile/install-time; retaining Shadow runtime hooks would violate O15.

## 3. Dangling code

The scans in this section began at commit `42a9faf2e`; after the concurrent
driver lane landed, driver/run citations were re-grepped at `1832764de`. The
scans parsed or searched 417 Clojure, ClojureScript, CLJC, and EDN files. No
source was changed.

### Dangling attribute references

**Total: 36 distinct production attribute names with no registration: 24
attempt, seven run, five turn.** The prior 34 count considered only manually
confirmed Datalog/pull positions and omitted two attempt names used as a
lookup/schema key and historical row field. The owner-reported 25 attempt names
also does not reproduce: current `src/` has 24; `src/` plus `test/` has 27
because tests add `config-digest`, `deadline-at`, and `id`.

- **DELETE / owned-by-a-later-wave — 24 `:seon.ai.attempt/*`:**
  `adapter`, `adapter-timeout-ms`, `api-key-env`, `credential-class`,
  `dg-backend`, `endpoint`, `entity`, `error-status`, `evidence-error`,
  `extra-body-digest`, `max-tokens`, `ordinal`, `outcome`,
  `outer-timeout-ms`, `partial-text`, `provider`, `reply-evaluation`,
  `request-id`, `requested-model`, `response-model`, `stream?`,
  `system-fingerprint`, `temperature`, and `thinking`.
  `src/seon/web/serve.cljs:975-1185` and
  `src/seon/agent/ctx/transcript.cljc:799-810` consume them; zero source
  registrations exist. O13 deletes old pod/turn evidence consumers; a new
  partial fact must be registered and written by the JVM streaming owner, not
  restored as compatibility.
- **DELETE / owned-by-a-later-wave — seven `:seon.agent.run/*`:**
  `deadline`, `last-beat-at`, `paused-at`, `remaining-ms`, `result-ref`,
  `trigger`, and `turn-limit`. Readers include
  `src/seon/derive.cljs:68-75,129-141,449-463`,
  `src/seon/agent/ctx/subagents.cljc:107-141,328-337`, and
  `src/seon/agent/authorization.cljs:8-14`. The surviving schema at
  `src/seon/agent/run/core.cljc:11-65` omits them deliberately.
- **DELETE, except observability facts owned by the render wave — five
  `:seon.agent.turn/*`:** `scheduled?`, `phase`, `llm-attempts`,
  `prompt-blob`, and `reply-blob`. Readers are at
  `src/seon/agent/ctx/transcript.cljc:782-810`,
  `src/seon/agent/debug.cljs:102-123`, and
  `src/seon/web/serve.cljs:1247-1250`.

The broadened issue is
[[../../../seon/issues/pod-attempt-ordinal-consumers-reference-an-unregistered-attribute]].

### Zero-caller namespaces and unreachable branches

**Total: five zero-require hypotheses; two real namespace deletions and three
dynamic false positives. One additional helper is immediately unreachable;
three public functions await later owners.**

- **DELETE:** `src/seon/capability.cljc:1-114`. Its only caller was deleted at
  `8dc8623ad^:src/seon/host/context.clj:651,1016`. Current line 45 already
  reads `:seon.capability/effect`, not `:seon.host.context/effect`; the whole
  inventory branch is unreachable.
- **DELETE:** `src/seon/runtime/recovery/core.cljc:1-15`; zero require/call and
  stale deleted run-holder semantics.
- **DELETE:** `src/seon/client.cljs:462-466` `recovery-result!`; its definition
  is its sole occurrence after `901eee2d3`.
- **owned-by-a-later-wave:** `src/seon/runtime/recovery.cljs:283-408`
  `recover!` and `:510-541` `pending-notices` are test-only, but recovery facts
  still feed context rendering.
- **owned-by-O15/O16:** `src/seon/db/program.clj:292-297`
  `compile-tx-data` is test-only and must not become runtime indexing.
- **KEEP:** `seon.agent.interaction.render` is named by
  `config/system.edn:470`; `seon.demo` is a Shadow preload at
  `shadow-cljs.edn:84,141,182`; `seon.embed.preflight` is dynamically resolved
  at `src/seon/db/server.clj:576`.

The issue is
[[../../../seon/issues/deletion-waves-left-unreachable-runtime-code]].

### Dead requires

**Total: zero requires of missing local namespaces; 38 unused aliases (23
source, 15 test).** The exact path/alias list and the bare-require caveat are in
[[../../../seon/issues/deletion-waves-left-unused-require-aliases]].

Verdict is **FIX**, not blind deletion. A require used only to load schema or a
compile-time inclusion becomes bare. In particular, the unused
`seon.db.program` alias at `src/seon/db/writer.clj:28` belongs to O15/O16 and
must not justify deleting its compile-time owner.

### Empty directories

`find src test script bench -type d -empty` returned no paths. The deleted
`src/seon/agent/loop/` and `src/seon/agent/turn/` directories no longer exist.
There is nothing to delete in this class.

## 4. GAP list ordered by what breaks soonest

This is the owner's work queue. `RE-IMAGINED` rows that are not yet implemented
remain real work, but they have settled shapes and therefore do not dilute this
list.

1. **Agent capability door (`seon.sci.ctx` / capability dispatcher).**
   **Broken now:** the base exposes lifecycle only, so agent code cannot use
   database, blob, filesystem, shell, web, messaging, or LLM functions.
   **Closing change:** install one computed, schema'd binding table whose
   genuine capability functions all enter one guarded dispatcher; prove a
   single live reply uses db, blob, messaging, filesystem, and web without any
   registry/session path.
2. **Output and result bounds (`seon.sci.eval`).** **Broken now:** prints are
   lost and terminal `pr-str` is unbounded. **Closing change:** one bounded
   writer plus total bounded ordinary-value projection runs inside the armed
   evaluation and returns output/value/diagnostics for the terminal receipt.
3. **Blocking host-call containment (capability placement).** **Broken now:** a
   host call that never returns permanently retains a compute permit, and there
   is no per-agent process to reap. **Closing change:** forbid unobservable
   blocking work on the cluster-JVM compute path and route it through an
   explicitly supervised `:io`/foreign-process owner with completion as the
   readiness event.
4. **Allocation containment (`seon.sci.interrupt` / process isolation).**
   **Broken now:** allocation is diagnostic-only, so one agent can exhaust the
   shared heap. **Closing change:** settle O4 with an enforceable allocation or
   disposable-process boundary and prove an adversarial allocator cannot harm
   another run.
5. **JS/CLJS package surface enumeration (final packages wave).** **Broken
   when packages arrive:** after O15 deletes Shadow indexing, no selected
   mechanism can enumerate a leaf package's callable surface. **Closing
   change:** choose and prove one package-native compile/install-time surface
   source; fresh/resume runtime must only read its committed facts.

The earliest unsettled contract is the agent capability door. Its integrated
proof is a real cluster-JVM reply that discovers and calls the complete
agent-facing surface through one door, commits its receipts, and survives
restart. The next dependency-ready portfolio is bounded value admission,
blocking/allocation containment design, and O15 package research. The final
graduation gate remains the reset-boundary default-cluster proof after O13
removes the pod, followed by the packages/leaf-runtime wave last.

## 5. What could not be settled

- The full two-form driver/database reply was not run because the default
  cluster was down. The current evaluator defect was reproduced in a
  disposable JVM, and the driver call path is source-conclusive, but the final
  database-fold proof remains **[UNVERIFIED]**.
- O4 has not ruled whether allocation becomes a limit or forces process
  placement.
- No owner ruling selects the one-door capability binding/dispatch mechanism,
  the bounded print/value projection contract, or the blocked-host-call
  boundary.
- O15 explicitly defers JS/CLJS package surface enumeration to the final
  packages wave.
- The `d5-wake` lane owned `src/seon/agent/driver.clj` and
  `src/seon/agent/run/core.cljc` during the first pass. Its commits
  `3946b7192` and `1832764de` landed before finalization, so those files were
  re-grepped and rows 15-16 were upgraded to `ALREADY OWNED`.
