---
type: plan
status: implementation specification; proof gates stated below
created: 2026-09-21
tags: [agent-platform, lane-b2, sci, core-async-flow, datastar, seon.turn, seon.render]
---

# Lane B2 — the walk is the history; the turn is one serial function; the fork is retained and updated

Owned: `src/seon/sci/*`, `src/seon/turn.clj`, `src/seon/run.clj` (rename),
`src/seon/cluster/{agent,wake,message,prompt}.clj`, `src/seon/flow.clj`,
`src/seon/oversight.clj`, `src/seon/render.clj`, `src/seon/render/*`,
`src/seon/print.cljc`, `src/seon/repl.clj`, `src/seon/ai.clj`, `src/my/*` except
`my/issue.clj` — 40 files, 33,353 lines at `209a6652a` (`wc -l`). Tests: the 79
files matched by `test/seon/turn*_test.clj test/seon/sci/*.clj test/seon/render/*.clj
test/seon/cluster/{agent,wake,message,prompt,reply,armed,turn,bootstrap_resume_child}*.clj
test/seon/{flow,oversight,print,repl,concurrency_independence}_test.clj test/seon/ai*_test.clj
test/my/*.clj` — 35,826 lines, 826 `deftest`s. Source anchors and historical measurements refer to
HEAD `209a6652a` and the vendored gitlinks (sci `fcbd8862`, core.async `dc35f3e`,
hyperlith `b08a8e8`, datastar-clojure `1cef624`, http-kit `238a85c`). Historical JVM evaluations are preserved in §4. No fresh runtime measurement is claimed; implementation begins with the orchestrator's current baseline.

## 0. For the owner: what was dumb, and the simpler way

**What the code does today.** The agent's SCI context is treated as disposable.
Every evaluation first asks whether the context "acquired" the database it was
handed, and the answer compares two *database* identities, not two *program*
identities (`sci/eval.clj:2252-2262` → `db.clj:2739`). Any ordinary write — a
message, a turn opening, a wake — makes the answer "no", and the next evaluation
rebuilds the whole program-only base from scratch (`acquire!` `:2265-2291`:
`load-core-namespaces!`, `base-ctx`, `acquire-program!` over 4,600 rows) and then
reconstructs the agent's private layer by snapshotting all 5,326 bindings of the
base, walking the agent's 5,326 bindings a second time and diffing (`:2100-2182`).
On `default` at that recorded basis the base was acquired 17 transactions ago, so the next
evaluation would pay that rebuild — measured **392 ms** for the base alone (§4 Q2,
Q1). Two atoms per context copy the program's function rows out of the database so
code can ask "is this installed" without a query (`sci/kernel.clj:108-182`). The
turn loop has three sibling functions that fork, evaluate and settle in the same
order from different sources (`turn.clj:4676-5010`), a 315-line paid call
(`:4175-4489`), a relay proc whose only job is to forward a wake
(`cluster/agent.clj:442-464`), a 704-line work launcher with its own refusing buffer
and capacity observer (`flow.clj:192-944`), and a since-diff that re-implements read
currency beside the one Datahike-backed owner (`turn.clj:1909-2103` vs
`db.clj:1102`). The agent's history is assembled by hand in a 2,443-line namespace
with three extra views of the same evaluations, although the ruling since
2026-09-08 is that the walk over stored evaluations through the evaluation schema's
declared pair IS the history (`seon.eval.edn:7-8`, `walk.clj:979`). The web page
keeps revisioned keyframe/delta packages, per-tab registration and a render-
invocation cache with hand-rolled invalidation (`web.clj:1849-1916`, `:2689-2890`,
`render.clj:701-889`) when the browser's morph already diffs and Datahike already
answers "did anything this read depends on move". The namespace page clips HTML
through a three-tier budget ladder (`ns.clj:416-837`), which the law forbids.

**Why that was the wrong shape.** Each is a mirror kept beside the authority that
holds the fact, plus machinery to keep the mirror current, plus tests policing that
machinery: database identity stands in for program identity; the binding snapshot
stands in for "which Vars are mine"; the kernel atoms stand in for the database
value the context already carries; the transcript stands in for the walk; the
packages stand in for the client morph; the invocation cache stands in for read
evidence. Each does work proportional to the whole program or the whole page on an
event proportional to one change.

**The simpler way, as data flow.** Acquire the program once per cluster, then
process admitted differences. A transaction that changes no program declaration
advances the context's database value and installs nothing. When declarations do
change — a development adoption, an agent's accepted definition, a deletion — the
changed identities are already facts in the transaction that admitted them
(`:seon.test/adoption-identities`, `cluster.clj:2140`; the writer's own row
installs, `sci/eval.clj:1100-1165`), and exactly those rows are installed into the
base and into each retained agent fork. An agent forks once and keeps that context;
its private defs, atoms, closures and result objects are never discovered,
snapshotted or reinterned because nothing replaces the context they live in. Which
loaded implementation a row binds is decided by definition-digest equality with
what the JVM loaded, never by who authored it. The turn is one function — source
selection → evaluate → settle — run inline on one serial `:io` proc whose in-port is
the agent's wake channel, so a second wake cannot start until the first transform
returns; the deadlines that stop work are SCI's `time-limit` and the provider
deadline, and the completion observer stays because a host call inside an
evaluation cannot be interrupted. Read currency is one call to the Datahike-backed
owner per distinct read. The history is the walk. The page is the whole current
view per tab per notification over a dropping buffer, written only after the socket
drained. Installation work follows changed declarations and affected contexts; history rendering remains proportional to retained evaluations and full-page delivery to interested tabs × view size. Measure these separately.

## 1. Goal and the numbers that prove it

| quantity | before (measured / declared) | after (target) | form |
|---|---|---|---|
| base rebuilt on the first evaluation after any transaction | yes: `acquired-database?` false at basis 536870949 vs acquired 536870932 (Q1); rebuild **392.3 ms** for 419 namespaces / 5,326 interns / 4,600 rows (Q2) | never; an ordinary write installs 0 rows; a declaration change installs exactly the changed rows (§2a) | Q1, F1 |
| private-layer reconstruction per turn | `base-bindings` 1.5–2.4 ms twice + a merge walk (pack §2.5) on every regeneration | 0 — no regeneration exists | F1, F2 |
| agent fork | `sci/fork` 0.002–0.02 ms (pack), 0.007 ms (Q2) | once per agent, retained | F2 |
| Seon mirrors carried on a context | 4 (`::base-bindings`, `::kernel/installed-functions`, the snapshot's `:functions`/`:namespaces`, `::print-session`; `eval.clj:273-274, 2197-2204`) | 0; the snapshot keeps only the acquired `:seon.db/db` and `::acquisition` refusals; `::result-objects` stays | F3 |
| procs per agent graph | 3 (`mailbox`, `turn`, `schedule`; `agent.clj:469-513`) | 2 | F4 |
| history render paths | walk + 3 hand-assembled transcript views | the walk, honouring the requested output | F5 |
| read currency in the system turn | five turn-side helpers (`turn.clj:1909-2103`) + `db/replay-read` (`db.clj:971`) | `db/read-evidence-current?` (`db.clj:1102`) per distinct read | F6 |
| web delivery | keyframe/delta packages + per-tab registration + render proc retention | whole view per notification per tab; drain-or-close wait kept (`web.clj:2704-2757`) | F7 |
| render invocation cache | 189 lines (`render.clj:701-889`) + consumers | 0 | F7 |
| namespace page presentation | 6 renderings (3 tiers × AI/HTML) | 1 AI text (or one elision value) + 1 full HTML view | F8 |
| load-cycle delays in owned files (`grep -c requiring-resolve`) | 36 sites in 11 files; 1 legitimate (`ai.clj:604`, a configured coercion) | 1 | grep |
| owned src lines | 33,353 | **≈25,738 provisional** (§9 includes ≈300 incoming error-render lines) | `wc -l` |
| owned test lines | 35,826 in 79 files | **≈23,100** (§7 ledger; 15,747 lines not mapped this pass) | `wc -l` |

Any operation above 2 s requires measured attribution; the observed full-program rebuild is one known source of work, not an explanation for every slow operation. Measure full-page derivation before choosing its sharing shape (§6).

## 2. The data flow

### 2a. The context — acquire once, install differences, never regenerate

| datum | computed when | carried in | recomputed on | proportional to |
|---|---|---|---|---|
| base ctx (program only) | cluster boot (`cluster.clj:1686`, `:3146` → `sci/eval.cluster-ctx`) | the cluster handle `:seon.sci.eval/ctx`; snapshot holds the acquired `:seon.db/db` + `::acquisition` | never rebuilt | installed declarations, once |
| loaded-vs-interpreted decision per row | at install | the SCI namespace map: a `clojure.lang.Var` object when the row's `:seon.program/definition-digest` (B1 §2g) equals the digest of the definition the JVM loaded; else the interpreted Var armed in that context (`install-function-contract!` `:695`) | that row's digest changes, or the loaded digest changes at adoption | 1 row |
| changed identities since the acquired basis | at each evaluation boundary that sees a newer database | B1's complete admitted-definition changes, covering adoption, agent writes, deletions, resolver bindings and affected contracts; adoption-only `:seon.test/adoption-identities` (`cluster.clj:2140`) is insufficient by itself. Accepted rows use `install-evaluated-rows!` (`:1100,1152`) | per adoption / per accepted definition | changed rows |
| installation of a changed row | on the boundary above | `install-row!` (`:841`) into a replacement base generation and, only at each idle agent's next boundary, into its retained fork; `:seon.program/delete-identities` (`:855`) unbinds | — | changed rows × contexts |
| custody advance on an ordinary write | every evaluation | the snapshot's `:seon.db/db` and `advance-context-projection!` | every transaction | 0 rows |
| an agent's fork | first turn (`acquire-context!` `agent.clj:685`) | `:seon.agent/context-state` (retained handle) | never | 1 fork |
| the private layer | as the agent `def`s | its own Vars, atoms, closures, `::result-objects` (`admit.clj:704`, handle `id/symbol-in`) | never discovered; carried by not being replaced | — |
| collision: a program row installed over an uncommitted agent binding of the same symbol | at install | the committed row wins in every context; the install returns the shadowed symbols as data | — | 1 |
| print options | evaluation time | the render profile on the request (§2.4 law) | — | 0 |

**Isolation is an implementation gate.** A JVM Var in a namespace map is a supported callable (`sci/core.cljc:306-317`, `analyzer.cljc:1783-1803`) and a pre-existing fork observes its root replacement (§4 JVM Var probe). Digest equality at installation does not freeze that root, its wrapper contract, or an indirect JVM caller's callee. Before removing acquisition/arming state, B1/A1/B2 must prove two clusters with different definitions and contracts, direct and indirect calls, concurrent adoption, macros, dynamic Vars and non-callable defs. Retain the existing resolution/isolation path for cases not proven. A typed SCI-unloadable fallback is visible state, never evidence that an unexecuted proposed implementation passed D1's gate.

Do not mutate a base-owned SCI Var shared by active forks. Construct the updated program base with SCI's copy-on-write generation and update each retained agent context only at its execution boundary. `bind-root!` on a fork copies an inherited Var before mutation (`utils.cljc:362-379`), but closures can retain prior Var references: prove a private closure calling an updated function through two successive changes. Initial installation and definition replacement must preserve aliases/imports/refers/unmaps as well as function rows. Ordinary fact writes install no rows and perform no namespace/private-state discovery.

Candidates retain `(sci/fork agent-ctx)` (`turn.clj:3253`, `eval.clj:3170-3335`) until D1 replaces their complete semantics. Arm candidate-owned functions before tests; inherited functions are not candidate-owned merely because they are present. Invalid candidates and failing tests leave live aliases, bindings and private atom identities unchanged. After writer acceptance, transfer evaluated roots through the existing installation seam without replaying initializers (`eval.clj:1100-1165`). The same writer refuses two conflicting definitions based on one opening basis.

### 2b. The turn — one serial function, bounded at the operation seams

| datum | computed when | carried in | recomputed on | proportional to |
|---|---|---|---|---|
| next work | each wake | `next-agent-work` (`turn.clj:2888`) over one `db/db` | each wake; the in-port is `(sliding-buffer 1)` so a burst coalesces | this agent's open turn + unanswered wakes (indexed `:t`) |
| source of a turn | one place, `turn-source`: `:generate` → system forms; `:call` → the provider reply; `:resume` → stored unevaluated forms; `:close` → none | the request map | per turn | forms in this turn |
| evaluate + settle | `evaluate-sources` (`:4519`) → `settle!` (`:3742`), shared by every source | transaction report | per form | 1 form |
| wake-answering and continuation | unchanged: `latest-answering-turn-t` (`:2980`), `unanswered-wakes` (`:3007`), the no-paid-loop-after-refusal park (`:5310-5316`) and write-refusal bound (`:5103`) | facts | — | — |
| the executor | the proc is `:io` (`agent.clj:497`); an evaluation runs on the carried compute executor through `flow.impl/futurize` (`impl.clj:29-36`) with the arm transferred, waiting bounded by the evaluation's declared limit, followed by actual-exit evidence before releasing serial ownership | `:seon.flow/executor` on the handle | — | 1 evaluation |
| what stops work | SCI `time-limit`/`:interrupt-fn` for interpreted code (`kernel.clj:57-94`, `:363`); the provider's HTTP deadline in `seon.ai`; nothing stops a blocking host call (`sci/doc/interrupt.md`) | the arm | — | — |
| what makes an unexited transform visible | the completion observer (`arm-turn-completion-backstop!` `:5217-5262`): armed after the permit, extended by each admitted part's allowance, commits ONE fault naming agent, turn and the part that never arrived | `:seon.agent/turn-backstop-state` on the handle | — | 1 |
| what forbids overlap | flow's proc loop reads the next input only after the transform returns (`impl.clj:271-320`); the permit `:seon.turn.loop/completion` serializes the proc against the out-of-proc callers (`submit-source!` `agent.clj:540`, the debug controls) and lets `disarm!` wait for the actual exit (`agent.clj:805-845`, `cluster.clj:3089`) | the handle | — | — |
| the wake | Datahike listener → `wake-channel` (`agent.clj:118`, `CountedSlidingBuffer` `:95` keeps the dropped-wake count) = the turn proc's `::flow/in-ports {:seon.agent/wake …}` (today the mailbox's, `:457`) | — | — | 1 |
| read currency in the system turn | latest evaluation of each distinct read form, with its namespace and basis (`latest-evaluations` `:1956`) → `db/read-evidence-current?` (`db.clj:1102`) | evidence on the evaluation | per system turn | distinct reads |

**Cancellation and overlap, stated completely.** When a deadline fires inside an
evaluation, SCI's interrupt stops the interpreted form at its next `fn` entrance or
`loop`; the evaluation settles as `:time` with `interrupted-at` (`sci/eval.clj:2977`)
and the turn continues to the next form or closes. When the provider deadline
fires, `seon.ai` returns a typed error and the turn settles it. When neither fires
because the transform is blocked in a host call, the observer commits its fault
and the transform keeps running: no new wake is taken (flow's loop is blocked),
`disarm!` waits on `turn-stopped` and the observer's failure channel, and a
late settlement of an already-closed turn is refused by the writer
(`require-open-run` `:330`, `close-call` `:442`). A wake arriving while a turn
runs sits in the sliding-1 in-port and is read after the transform returns; it
re-derives from facts, so the turn it finds is the one the facts say is open.
A timed `.get` abandons its wait and does not cancel or join the task (`FutureTask`). Keeping the outer proc serial is insufficient if its nested computation outlives that wait. Keep the existing completion/permit ownership until the controlled late-operation probe proves no subsequent wake or out-of-proc caller enters the same context before actual exit. A deadline reports failure; it is not exit evidence. No core.async fork change is required by this plan.

**The launcher retirement gate.** `flow.clj:241-944` exists to run evaluations on the compute pool
with the arm carried and to refuse when the pool is saturated. One evaluation per agent bounds demand by agent count only while actual-exit ownership holds. Verify the supplied executor's queue/admission bound; neither `:compute` nor `futurize` supplies a bounded pool by itself. Retire refusal/capacity machinery only when that existing admission owner preserves its guarantee; `submit-evaluation!!` (`turn.clj:3358`) becomes the futurize call
above. The capacity observer (`:192-240`) goes with its consumers. This deletion is
a coordinated slice (§5 commit 7): `cluster.clj:3166, 3177, 3540` (B1, **held**
today), `effect.clj:991` (B3), `env_test.clj:136` and `cluster/turn_test.clj:178,
276` (B4) name the launcher.

### 2c. The history, the page, the namespace page

| datum | computed when | carried in | recomputed on | proportional to |
|---|---|---|---|---|
| history | `walk/history` (`walk.clj:979-1016`) over `seon.eval/of-agent` through the pair `seon.repl/render-ai\|html` (`seon.eval.edn:7-8`); today it always requests `:seon.render/ai` (`:1005`) — it takes the requested output, AI from saved shown text, HTML from the live result map | the walk's units | each render | evaluations of one agent (O(history), stated as such) |
| prompt | `prompt/compose` (`prompt.clj:266`) over the units; whole-unit selection under the token budget stays | attempt row | each turn | units |
| a page | per tab, on each take from a `(dropping-buffer 1)` tap of the refresh mult: read ONE current `db/db`, render the whole requested view; skipped when `db/read-evidence-current?` says nothing it read moved AND no `::runtime-eval` (code or private object) notification arrived | the tab's loop: last basis `:t` + read evidence | a notification (payload-free "look") | one page; coalesced per tab |
| delivery | `->sse-response` (`http_kit.clj:23-76`) with `write-profile (brotli/->brotli-profile)` (`brotli.clj:99-113`); one `patch-elements!` of the whole view; the next write waits on http-kit's drain-or-close (`server.clj:321-326` `write-state`, our fork; the wait is `web.clj:2704-2757` today) | the connection | per take | bytes of one view |
| backpressure, honestly | the dropping tap bounds pending *notifications*; the SDK queues sends (`impl.cljc:46-65` buffers then `send!`) and compression is not backpressure; only the drain wait bounds pending *bytes* — it stays, and a slow tab delays only itself | — | — | — |
| namespace page AI | `ns/render-ai` (`ns.clj:895`) emits bounded AI content once: under the profile's token budget or ONE `seon.print` elision value naming the omitted definitions and the requery `(dir ns)` | — | per render | definitions |
| namespace page HTML | `ns/render-html` (`:928`), the full view, never budgeted | — | per render | definitions |
| walk distance | kept: a query-work bound reported as an elision (`walk.clj:669-703`, `:698`; owner ruling) | the request | — | — |

Packages, revision reconciliation, `register-tab!`/`deregister-tab!`, the render
proc's `::packages`/`::calls`/`::invocations` retention (`web.clj:2340-2428`), the
coalesce `Thread/sleep` (`:2662`) and the invocation cache dissolve. Hyperlith is the
model (`hyperlith/impl/datastar.clj:122-181`: tap `:143-145`, `:first-render` `:146`,
CPU pool `:161`, close `:179-181`): subscribe before the first paint, render and
encode on the compute executor, IO owns waiting and sends, disconnect closes the
tap. Whether rendering stays *shared* (today `derive-page!` `:2155` shares one
derivation across tabs) or goes *per tab* is decided by the §6 measurement, not by
this table: per-tab cost is T tabs × one full render + encode.

### 2d. Load order (resolve each cycle with all callers in one commit)

| cycle today (breaker) | resolution |
|---|---|
| `admit` → `kernel` (`admit.clj:61-62`) | `interrupted?` (`kernel.clj:31`) moves beside the guard's reader; `kernel` requires `admit` |
| `render.value` → `render`, `config` (`value.clj:22-27`, `:419`) | the profile and the nested render invocation ride the request; `value` requires nothing above `print` |
| `repl` → `turn`, `cluster.agent` (`repl.clj:25-28`) | `turns-since-wake` and the armed ctx are handed by the caller (the unit already carries `:seon.sci.eval/agent-ctx`) |
| `render` → `web`, `turn`, `walk` (`render.clj:46-50`); `render` requires `sci.admit`/`sci.kernel` (`:31-32`) | `web/derive-context!` dies with the cache; `opening-db` is an argument; `walk/neighborhood` is called by `web`/`prompt`; admission and kernel stay BELOW render (they are leaves) |
| `turn` ↔ `cluster.agent`, `cluster.prompt`, `context`, `problems` (`turn.clj:51-62`); `context.clj:45,152,359` calls `turn/terminal?` | `terminal?` (`turn.clj:225`) moves to `seon.eval` (the evaluation owner, owned by B3; B2 supplies this paired move and all consumers); `prompt`/`context` read `:seon.turn/*` attributes by Datalog and never require `turn`; the routed-problem block (`:2397-2613`, callers `problems.clj:191-211`) leaves with B3's task family; `cluster.agent` supplies `acquire-context!`/`submit-source!` on the handle |
| `flow` → `operator.runtime` (`flow.clj:30-31`) | executors ride the request (`:seon.flow/executor`, `agent.clj:511`) |

Resulting order: `print` → `sci.admit` → `sci.kernel` → `render.value` → `repl` →
`render` → `render.walk` → `sci.eval` → `cluster.prompt`/`context` → `turn` →
`cluster.agent` → `render.web`/`oversight`. Proof is `clojure -M -e "(require …)"`
from a cold JVM per commit, not this table.

## 3. Reading list (re-read the 2026-09-21 evidence collection at the gitlinks above)

| block | guarantees |
|---|---|
| `reference-code/sci/src/sci/core.cljc:345-350` `fork` | a new env atom over the same namespace map + a fresh generation; no per-Var work; later base interns are NOT visible to an existing fork |
| `sci/impl/utils.cljc:356-379` `bind-root!` | equal generation ⇒ mutate in place (visible to every context sharing the Var); unequal ⇒ copy stamped with the ctx's generation, interned in this env only |
| `sci/core.cljc:260-271` `intern`, `:273-276` `bind-root!` | the two writes an install uses |
| `sci/core.cljc:306-317` hook docstring; `impl/analyzer.cljc:1783-1803` | a `clojure.lang.Var` installed in `:namespaces` is a supported callee, prepared like a sci Var |
| `sci/core.cljc:112-140` `copy-var*` | copies the ROOT; never follows the source Var — what the JVM-Var binding replaces |
| `core.async/.../flow/impl.clj:271-320` | the proc loop is sequential: `alts!!` then `transform` then loop; `:308-318` the only report shape for a transform failure |
| `flow/impl.clj:29-36` `futurize`; `:243-261` `proc` | `futurize` returns a `FutureTask`; `.get` with a timeout bounds the wait and does not cancel |
| `flow.clj:76-105` `create-flow`, `:136-155` `ping`, `:265-286` `:compute-timeout-ms` | executors per workload; ping is the state a proc chooses to show, not occupancy |
| `impl/dispatch.clj:82-116` | `:io` is virtual threads when the JVM supports them |
| `hyperlith/impl/datastar.clj:122-181` | whole view per notification, dropping-1 tap, render on the CPU pool, tap closed on disconnect |
| `datastar-clojure/libraries/sdk-brotli/.../brotli.clj:86-113`, `sdk-brotli/deps.edn:2-4` | `->brotli-profile` exists in the SDK; brotli4j + netty-buffer declared, platform natives resolved on the host (host dependency proof) |
| `sdk-http-kit/.../http_kit.clj:23-76`; `adapter/http_kit/impl.cljc:46-65, 83-97` | `->sse-response` takes the ring request + options; sends are buffered then `send!`; no backpressure of its own |
| `reference-code/http-kit/src/org/httpkit/server.clj:321-326` `write-state` | pending bytes and the drain-or-close completion — the only socket completion fact |
| `sci/doc/interrupt.md` | interruption at `fn` entrance and `loop`; host calls are not interrupted |
| first-party: `install-row!` (`eval.clj:841`), `install-evaluated-rows!` (`:1100`), `read-evidence-current?` (`db.clj:1102`), `walk/history` (`walk.clj:979`), `repl/render-ai\|html` (`repl.clj:447, 480`), `wake-channel` (`agent.clj:118`), `write-package!`'s drain wait (`web.clj:2704-2757`), `fault-committer-step` (`flow.clj:1051`) | the mechanisms the cut builds on |

## 4. REPL protocol (`mcp__seon__eval_clj`, jvm, `read_only`, cluster `default`)

| id | form | before (measured) | after |
|---|---|---|---|
| Q1 (done, the 2026-09-21 evidence collection) | `(let [h (get-in @seon.operator.runtime/running-instances ["default" :seon.turn.loop/cluster]) base (:seon.sci.eval/ctx h) conn (seon.operator/connection "default") current (seon.db/db conn) snapshot @(:seon.sci.kernel/program-snapshot base)] {:acquired-database? (#'seon.sci.eval/acquired-database? base current) :acquired-basis-t (seon.db/basis-t (:seon.db/db snapshot)) :current-basis-t (seon.db/basis-t current) :installed (count @(:seon.sci.kernel/installed-functions base))})` | `{:acquired-database? false :acquired-basis-t 536870932 :current-basis-t 536870949 :installed 4600}` (check 0.033 ms; the agent ctx carried `::base-bindings`, `::print-session`, `::kernel/installed-functions`, `::kernel/program-snapshot`) | the predicate is deleted; the snapshot's `:seon.db/db` basis equals the current basis after any evaluation, with 0 rows installed |
| Q2 (done, the 2026-09-21 evidence collection) | `(let [conn (seon.operator/connection "default") current (seon.db/db conn) t0 (System/nanoTime) fresh (seon.sci.eval/base-ctx current) t1 (System/nanoTime) fork (sci.core/fork fresh) t2 (System/nanoTime) state (sci.core/namespace-state fresh)] {:base-ctx-ms (/ (- t1 t0) 1e6) :fork-ms (/ (- t2 t1) 1e6) :namespaces (count state) :interns (reduce + (map count (vals state))) :installed (count @(:seon.sci.kernel/installed-functions fresh))})` | `{:base-ctx-ms 392.335 :fork-ms 0.007 :namespaces 419 :interns 5326 :installed 4600}` (0 acquisition refusals) | `base-ctx` runs once at boot; the same form still measures the cold cost; the per-boundary cost is the F1 form |

The following are required implementation scenarios, not executable snippets or measured after-results. The lane writes each as a complete bounded canonical-fixture form in its landing script, with concrete handles, request fields and asserted outcomes before deleting its old mechanism.

| id | Scenario | Required observation |
|---|---|---|
| F1 | Carry an acquired context across one ordinary message write, one accepted definition, a contract change, resolver change and deletion. Count installation calls at the existing seam in the isolated fixture. | ordinary write installs zero rows and advances custody; only changed declarations/dependents install; deletion removes resolution. Absence of a retired Var is no substitute for the zero-work observation |
| F2 | Retain context, private Var, atom, result and closure identities across two virtual turns and two adoptions; include a closure calling an updated program function. | identities and intended updated resolution hold; another agent/base sees no private binding |
| F3 | Inspect the actual acquired context and snapshot after F1. | no Seon binding/function/print mirrors; database custody and result objects remain |
| F4 | Compare expected proc ids with idle ping responders; hold a host operation, cross its deadline, queue a wake and an out-of-proc submission, then release it. | two expected procs; missing reply is unknown; admitted-work facts name the turn/part; one fault, no overlap or late settlement; disarm acknowledges actual exit |
| F5 | Call `walk/history` with explicit db, ctx, profile, agent lookup and each requested output; render a live result then its saved text after restart. | ordered AI units preserve exact stored text; HTML uses live objects when available; every declared render pair resolves |
| F6 | Submit distinct generated/agent reads with namespace and individual bases, including empty, retracted, temporal and multiple-source reads; change one subject plus one unrelated entity. | only changed reads append system evaluations; writes/effects never rerun; A2 owns every currency decision |
| F7 | Subscribe two tabs before first paint; relevant/irrelevant changes, renderer/private-object changes and burst with one slow tab. Measure render, encode, compression, send/drain and pending bytes independently. | both converge on latest db; initial paint needs no later write; slow tab does not block fast; pending bytes drain before next write; disconnect releases resources |
| F8 | Render one namespace through explicit HTML and AI requests under a 2,000-token profile, including an oversized definition set. | full HTML; AI bounded once with queryable elision, no three-tier ladder |

The dependency probes below are exact historical forms from 2026-09-21, `eval_clj` mode `jvm`, `read_only true`, root `/Users/sean/src/seon`, cluster `default`, timeout 8,000 ms. Mutation was confined to newly allocated local contexts/Vars. They prove sharing semantics, not Seon's replacement implementation.

JVM Var probe, envelope 1 ms:

```clojure
(let [v (clojure.lang.Var/create (fn [x] [:old x])) ctx (sci.core/init {:namespaces {'probe {'f v}}}) fork (sci.core/fork ctx) before (sci.core/eval-string* fork "(probe/f 1)") _ (.bindRoot v (fn [x] [:new x])) after (sci.core/eval-string* fork "(probe/f 1)")] {:before before :after after :same-var (identical? v (sci.core/resolve fork 'probe/f)) :resolved-type (str (type (sci.core/resolve fork 'probe/f))) :generation-equal (= (:sci/generation (meta v)) (:sci/generation @(:env fork)))})
;; {:before [:old 1], :after [:new 1], :same-var true,
;;  :resolved-type "class clojure.lang.Var", :generation-equal false}
```

SCI root probe, envelope 5 ms:

```clojure
(let [base (sci.core/init {}) _ (sci.core/eval-string* base "(def x 1)") fork (sci.core/fork base) inherited (sci.core/resolve fork 'user/x) before (sci.core/eval-string* fork "x") _ (sci.core/bind-root! base (sci.core/resolve base 'user/x) 2) after (sci.core/eval-string* fork "x") _ (sci.core/eval-string* fork "(def y (atom 3))") private (sci.core/resolve fork 'user/y)] {:inherited-before before :inherited-after-base-bind after :same-inherited-var (identical? inherited (sci.core/resolve base 'user/x)) :inherited-generation-matches (= (:sci/generation (meta inherited)) (:sci/generation @(:env fork))) :private-generation-matches (= (:sci/generation (meta private)) (:sci/generation @(:env fork))) :namespace-entry-count (reduce + (map count (vals (sci.core/namespace-state fork))))})
;; {:inherited-before 1, :inherited-after-base-bind 2, :same-inherited-var true,
;;  :inherited-generation-matches false, :private-generation-matches true,
;;  :namespace-entry-count 684}
```

Codex reaches the same server through `.codex/config.toml` → `bin/mcp-server`
(`script/seon/dev/mcp.clj:848-875`). A scratch cluster is `bin/seon --root
tmp/b2-root start b2` (root directory created first, downed and deleted after); a
fresh cluster seeds agent `root` (`bootstrap.clj:887`).

## 5. The work, ordered as commits

Every commit: net-negative unless named; includes its production callers, resource
symbols, contracts and the tests that name what it deletes (no cleanup commit);
ends with `clojure -M -e "(require 'seon.turn 'seon.render.web 'seon.sci.eval 'seon.cluster.agent)"`
from a cold JVM and `require :reload` of the touched namespaces on `default`; names
its probe (the one read that shows `default` is sound). A graph-port or proc-option
change is applied by `disarm!`/`arm!` of the affected agents (`agent.clj:854, 706`)
through the existing owner, never by restarting the host. A classpath change is the
orchestrator's host plan before the commit that uses it.

| # | commit | deletes | adds / converts | probe |
|---|---|---|---|---|
| 1 | **program identity replaces database identity** — the boundary check reads changed identities since the acquired basis through B1's complete declaration/resolver/contract change facts (§2a), then updates a replacement base generation and retained forks only at their idle boundaries; `acquired-database?` (`:2252`), `acquire!`'s rebuild branch (`:2273-2291`), `installation-covers-program-change?` (`:1052-1099`) deleted; the snapshot keeps `:seon.db/db` + `::acquisition` only. **Interface:** B1's `:seon.program/definition-digest` decides JVM Var vs interpret; B1's fact and the A1/B2 isolation probes must land before this retirement; provenance-based admission is not a fallback | ~420 | ~90 | Q1 → F1; `runtime_status` |
| 2 | **fork once, keep it** — `regenerate-agent-context!`, `base-bindings`, `same-program-root?` (`:2100-2182`) deleted; `fork-for-turn` returns the retained ctx or forks once; `kernel.clj:108-182` program mirror and `::installed-functions` deleted, readers (`render.clj:293, 679`, `eval.clj:707, 1047, 1163-1170`) pull the row from the ctx's database; `latest-print-fact`/`session-print-options`/`::print-session` (`:2070-2099`) deleted — the profile is on the request | ~330 | ~40 | F2, F3; one SCI eval defining an atom, one turn, one adoption, the atom identical |
| 3 | **doc/dir as data** — `program-documentation` … `install-program-doc!` (`:1407-1658`) → one pull of `:seon.fn/{sym,arglists,doc,spec}` rendered by the declared pair, still exposing contract and supplied arguments (`:1491-1535` semantics) | ~350 | ~80 | `(dir my.task)` in sci mode returns data |
| 4 | **turn = one function** — `turn-source`; `resume-turn`/`close-turn`/`generate-turn` (`:4676-4935`) fold into `turn`; `call-turn` (`:4175-4489`) splits into `provider-attempt` + the shared tail; delimiter repair (`:3099-3178`) deleted (`sci.reader`'s typed refusal is the reply); wake-answering, continuation, park and write-refusal rules untouched | ~900 | ~200 | one virtual turn through the debug control; `:seon.turn/closed-tx` present; a reply message answered |
| 5 | **read currency has one owner** — `declared-sources`/`system-plan` (`:1909-2103`) call `db/read-evidence-current?` per latest distinct read; `db/replay-read` and `read-evidence-changes` (`db.clj:971, 1055`) retire at A2's seam in the same commit or STOP if `db.clj` is held; a read's namespace and basis stay in its identity; writes and effects never rerun | ~240 (+ A2's) | ~40 | F6 with: a retracted test, a previously empty read, a temporal read |
| 6 | **the wake is the in-port** — `mailbox-step` (`agent.clj:442-464`) and the `:seon.agent/episode` port/conn deleted; `wake-channel` (with its counted buffer) becomes `turn/step`'s `::flow/in-ports`; `episode-runs` → `turns-since-wake` (config key stays B3's) | ~90 | ~15 | F4; a message to root opens a turn |
| 7 | **launcher retired, workloads kept** — `submit-evaluation!!` (`:3358`) → `futurize` on the carried compute executor with the arm transferred; `flow.clj:192-944` deleted with `cluster.clj:3166, 3177, 3540` (B1), `effect.clj:991` (B3), `env_test.clj:123-136`, `cluster/turn_test.clj:177-276` (B4) in ONE slice; `turn-completion-error` (`:5011`) keeps its one caller (`agent.clj:834`); the completion observer and permit stay | ~800 | ~40 | F4; a deliberately slow SCI form → one fault naming agent, turn, part; `disarm!` returns after the form exits |
| 8 | **the walk is the history** — `render/transcript.clj` deleted; `walk/history` takes `:seon.render/output`; `seon.turn.edn:5-6, 55-57`, `seon.runtime.edn:4-5, 11-12` pairs → thin functions in `render/agent.clj`; `seon.message.edn:29, 66` forms → `cluster/message.clj`; `:seon.render.transcript/request-error` retracted — consumers use the EXISTING `:seon.render/request-error` with `:seon.render/refused-member` (`seon.render.edn:249-251`): `seon.effect.edn:386`, `seon.render.edn:255`, `error.clj:103, 250, 347, 876, 924, 1816, 1883, 2073` (B3), `sci/admit.clj:590`, `sci/kernel.clj:552`, `render/lint.clj:528`; callers `web.clj:69, 400, 780-785, 1481, 2523-2530, 3136-3279`, `eval/drive.clj:16, 285`, `bootstrap.clj:816` (B3) → the walk. **RESET NEEDED** (a stored error attribute is removed) | ~2,450 | ~220 | F5; `/agent/root` history paints; one reply's bytes identical on page, in history and in the prompt |
| 9 | **whole view per notification** — packages (`web.clj:1849-1916`), registration (`:2689-2699`), `await-feed-package!` (`:2759`), render proc retention (`:2340-2428`) and `Thread/sleep` (`:2662`), the invocation cache (`render.clj:701-889`) and its consumers (`web.clj:436-554, 1409-1576, 2083-2511`, `walk.clj:442-497`, `turn.clj:1910-1918`), `*walk-context*` (`render.clj:1719-1874`) deleted; `write-package!` becomes `write-view!` and keeps the drain-or-close wait; `->sse-response` with the brotli profile (`deps.edn` + host plan first); `source-generation` (`:692`) and `refresh-read-evidence` (`:835`) stay; shared vs per-tab derivation per §6 | ~1,000 | ~150 | F7 |
| 10 | **namespace page** — ladder (`ns.clj:416-837`) deleted; `render-ai` bounded once, `render-html` full; the `::schema-row-cache` atom (`:405`) → the projection's `:seon.schema/key` index | ~440 | ~30 | F8 |
| 11 | **debug page** — the four §16 sections kept; `generic-entity`, `declared-entity-units`, `debug-applicable-candidates`, `applicable-renderers-html` (`web.clj:583-1357`) → `render/selection-inspection` (`:616`), `walk/root-selector`, `walk/neighborhood`; the "context now" as-of check kept (its cost is measured, §6) | ~700 | ~40 | `/agent/root?debug=true` shows state line, context now, would-be system turn, prompt digest |
| 12 | **`seon.run` → `my.turn`** — the four functions fold into `my.turn`; `run.clj`, `background.clj:4`, `my/agent.clj:5`, the Datalog literal `run.clj:96`, `bootstrap.clj:863, 871` opening bytes (B3), 21 test files' spellings — one commit or STOP at a held file. **RESET NEEDED** (stored openings hold the old bytes) | 149 | ~70 | complete a virtual turn through `my.turn` with its full declared request |
| 13 | **cycles** — the §2d rows; `grep -c requiring-resolve` over owned files = 1 (`ai.clj:604`) | ~60 | 0 | cold `require` of every owned namespace |
| 14 | **writer conflict basis** — `declaration-diverged-since-open?` (`turn.clj:1162-1201`) moves INTO `program/exact-replacement-tx` (`program.cljc:1042`, D1's file) as a basis input; deleted from `turn` only in the commit that lands it there | ~40 | ~30 (D1) | two candidates from one basis change one identity; the second acceptance reports the conflict |

RESET items (the orchestrator batches them; a lane never resets `default`): commit 8
(`:seon.render.transcript/request-error` retracted), commit 12 (opening bytes). A
reset loses recorded turns, evaluations, tasks, messages, in-memory private objects
and result objects; fixture agents are reseeded. That is accepted loss, not "nothing".

## 6. Better than the floor (probe first; each decides one row of §5)

| candidate | smaller than | probe that decides | outcome |
|---|---|---|---|
| bind eligible first-party callables as JVM Var objects | `copy-var*` per row at every rebuild | §4 proves root visibility, not version isolation; test direct/indirect calls and two different cluster contracts through concurrent adoption | use only for cases passing §2a; the 8.58 ms historical binding enumeration is not acquisition or page rendering |
| the SDK's brotli profile | vendoring hyperlith's writer | `(require 'starfederation.datastar.clojure.brotli)` on the host classpath and one compressed response inspected | decided if the natives resolve; else gzip profile (`http_kit.clj:44`) and the issue filed |
| shared page derivation vs per-tab render | the render proc + packages + interest | on `default` with root's page: render, HTML encode, brotli, send and drain measured separately for 1 and 3 tabs, initial paint, an irrelevant transaction, a relevant one, a burst with one slow tab; histories of 10 and 200 evaluations | per-tab only if 3 tabs × one render stays under the shared derivation's cost at 200 evaluations; the number lands in the note |
| context-now as-of check on the debug page | a digest column | elapsed measurement around the existing context-now derivation for a canonical 200-evaluation history | keep if ≤ 2 s; else per-evaluation as-of on demand — never "drop the check" |
| `read-evidence-current?` batched per system turn | per-read calls | count evidence reads and elapsed for root's distinct reads | batching only at A2's owner, only if measured |

## 7. Tests (each row names the deleted behaviour or the surviving class)

| today (lines) | disposition | after |
|---|---|---|
| 7 turn namespaces, 8,786, 143 `deftest`s (pack §7) | three classes: transitions as facts (`turn_test` + `turn_loop_test`'s pure parts), next-work/answering/continuation/bound (`turn_work_test` + `turn_continue_test` + `turn_work_cost_test` + `turn_error_test`), one real cluster with one provider reply per interruption position (`cluster/turn_test`, kill positions, isolation and wake distinctions preserved; setup hoisted, not trials cut) | ≈3,000 |
| `turn_backstop_test` 93 | the observer survives (§2b): keep ONE regression — a blocked host call → one fault naming agent, turn, part; `disarm!` waits for exit | ≈60 |
| `render/transcript_test` 1,431 + `history_test` 307 + `transcript_run_test` 100 + `episode_test` 50 | `history_test` keeps `one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt` (`transcript_test.clj:1172`) over the walk plus the HTML-output case; the rest dies with the views | ≈350 |
| `render/web_test` 2,340 + `web_debug_test` 1,071 | the four §16 sections, the SSE join, two-tabs-converge-under-a-burst, drain-before-next-write | ≈1,200 |
| `call_test` 31, `retained_test` 198, `web_context_test` 252, `web_feed_test` 26, `web_performance_test` 57, `web_adoption_test` 23 | cache/package cases die; `web_feed_test` becomes the burst/convergence regression; adoption case moves to the F1 class | ≈120 |
| `flow_test` 1,617 | launcher deftests die with commit 7; `:1427` (child JVM death) and the fault-committer cases stay | ≈700 |
| `sci/eval_test` 2,482 (`:159, 208-215` base-bindings) | rewritten as: ordinary write installs 0 rows; one definition change installs 1; a deletion unresolves; a private atom keeps identity across a turn and an adoption; two clusters keep their digests; an interpreted override called from a loaded JVM function | ≈1,500 |
| `lazy_acquisition_test` 44 (30 s), `eval_instrumentation_test` 75 (ns-long), `kernel_arm_carriage_test` 233, `fork_isolation_test` 30 | acquisition: "unchanged commit installs zero rows" under the default bound; carriage dies with the atoms; fork isolation stays | ≈120 |
| `concurrency_independence_test` 629 (ns-long) | dies: it verifies receipts through the launcher; one 5-agent fold under the default bound survives in the cluster class | ≈200 |
| `oversight_test` 204 | the counted-buffer case stays (the buffer stays); the launcher observation dies | ≈150 |
| time escapes (grep, 2026-09-21): `my/test_test:13-14` 300 s, `ai_stream_fold_test:374-375` 90 s, `lazy_acquisition_test:9-10` 30 s, `turn_test:1322-1323` 60 s; `^{:seon.test/long …}` without `long-ms` at `cluster/armed_test:1`, `flow_test:1427`, `concurrency_independence_test:1`, `oversight_test:113`, `print_test:240, 264`, `transcript_test:968`, `eval_instrumentation_test:1` | `test_test` and `lazy_acquisition` lose the escape after commit 1 (acquisition is no longer O(program)); `ai_stream_fold` keeps its reason + number; `turn_test:1322` ("two-analysis publication") is B1's class; `print_test:240, 264` keep their trials and hoist setup — a reason without a number is refused, so each survivor gets one | as stated |
| the remaining 15,747 lines (render value/hiccup/lint/data/route/…, cluster message/wake/prompt/agent, ai, print, repl, `my/*`) | not mapped this pass; repeated canonical setup is the namespace agents' second task class | unchanged |

The lane runs only B4's final `seon.test/run` over the tests reaching each commit's changed
functions (`seon.fn/tests-reaching`); never a suite. Text search omits namespaced-map metadata (`^#:seon.test{:long …}`); use B1's indexed marker facts and B4's declaration validation for the authoritative inventory.

## 8. Done, landing note, stop rules

**Done** = §1 filled with measured afters, in particular: an ordinary write installs
0 rows and advances the basis; a changed definition reaches the base and every
retained fork; a private atom keeps identity across a turn and an adoption; a late
host call cannot overlap the next wake or settle a closed turn (F4 + commit 7's
probe); every history render goes through the schema pair; two tabs converge to the
latest basis under a burst with pending bytes returning to 0; `grep -c
requiring-resolve` = 1; `wc -l` at or under §9. A missing symbol, an empty ping map
or an HTTP 200 is not proof; each probe records host REPL reachability and one
ordinary virtual turn.

**Landing note**: `docs/prds/agent-platform/landing/lane-b2.md` — the §4 forms with
values, the §6 numbers, the deletions as `git diff --stat`, which observation was a
hot reload, a fork or an in-place adoption.

**Stop rules** (report, do not work around): a held file (`git status` names a foreign
dirty path — `src/seon/cluster.clj` and `src/seon/fn.clj` were held on 2026-09-21);
`effect.clj:991`, `bootstrap.clj:816, 863, 871`, `error.clj`'s eight sites, `db.clj`
(A2), `program.cljc` (D1), `context.clj`/`problems.clj`/`eval.clj` (B3 owns the surviving files; B2 pairs `terminal?`'s move), the config key names (`:seon.config.run/
max-episode-runs`, `:seon.config.agent/turn-completion-backstop-ms`, B3) and the
`seon.error` render split (B3 §2a: functions move selectively into
`seon.render.error`, no `db`↔`error` cycle). Unsettled, three options in the note:
the brotli natives failing to resolve (SDK gzip profile recommended / resolve supported native dependency / uncompressed); the per-tab measurement not deciding (keep shared derivation / per
tab / per tab with a shared render memo keyed by basis — the last is a cache and
needs the owner).

**Interfaces.** A1 (A1-1b): the
wrapper is a pure function of (compiled contract, original, policy), idempotent
through `::interpreted-original`, never reads `:sci/generation`; B2 decides where
`bind-root!` runs and deletes `same-program-root?`/`base-bindings`; host projection
selection stays until A1's two-generation probe passes. B1 (§2g): one
`:seon.program/definition-digest` on every row; B2 consumes its complete admitted changes, including agent writes/resolver changes/deletions, and adds no digest. A2: `read-evidence-current?` is the
one currency owner; B2 calls it and adds no since-diff. B3:
the error result is prepared through B2's result mechanism with an explicit profile
and, when available, the owning ctx, never reclipping shown text; renderers move
selectively; B2 names the existing occurrence/message facts that wake an assigned
agent and proves one task/agent/notification per repeated delivery; B2 removes the
coalesce sleep and verifies slow-consumer delivery without claiming the sliding
buffer is SSE readiness; B2 converts `sci/eval.clj:2909`'s environment binding to
the request-carried environment and agrees `shell/jvm.clj:401, 492` ownership; the
turn bound covers the admitted evaluations, attempts and settlement, not one
evaluation plus one HTTP wait. C1: profile observations are composed
immutably at the existing settlement owners (`close-tx` sites `turn.clj:432-464,
3563, 3686, 3726, 4483, 4837, 4888`; recovery `:1704`; system turns `:2103`) and
handed to the writer; B2 changes no close site's shape without C1. D1: candidate
acceptance and the conflict basis live in `program.cljc`; B2's candidate fork stays
until D1 lands.

## 9. Size ledger (disjoint; transfers named; `wc -l` at `209a6652a`)

| file | today | target | what leaves / arrives |
|---|---:|---:|---|
| `turn.clj` | 5,417 | 3,700 | siblings, delimiter repair, schema/declaration helpers, since-diff helpers, `submit-evaluation!!`; −217 transferred to B3 (routed-problem block) counted as a transfer, not a deletion |
| `sci/eval.clj` | 3,382 | 2,500 | rebuild branch, regeneration, documentation block, `installation-covers-program-change?` |
| `sci/kernel.clj` | 750 | 620 | the program mirror |
| `sci/admit.clj` / `sci/reader.cljc` | 904 / 944 | 900 / 944 | one delay |
| `render/web.clj` | 3,694 | 2,500 | packages, registration, retention, debug helpers, transcript views |
| `render/transcript.clj` | 2,443 | 0 | +176 into `render/agent.clj` (24 → 200), +36 into `cluster/message.clj` (724 → 760) |
| `render.clj` | 1,891 | 1,500 | invocation cache, `*walk-context*`, delays |
| `flow.clj` | 1,364 | 700 | launcher, capacity observer |
| `render/ns.clj` | 936 | 500 | the ladder |
| `cluster/agent.clj` | 1,048 | 950 | mailbox proc, relay conns |
| `run.clj` / `my/turn.clj` | 149 / 41 | 0 / 110 | fold |
| `render/walk.clj`, `render/value.clj`, `repl.clj` | 1,016 / 680 / 517 | 950 / 670 / 505 | cache consumers, delays |
| unchanged: `ai.clj` 1,682, `print.cljc` 1,377, `prompt.clj` 411, `wake.clj` 577, `oversight.clj` 300, `render/{block,data,hiccup,lint,route,test}.clj` 1,607, `my/*` other 11 files 1,475 | 7,429 | 7,429 | `ai.clj:604` is the one surviving dynamic resolve |
| incoming `seon.render.error` from B3 | 0 in original B2 scope | ≈300 transferred | retained repository code, not deletion |
| **owned src including transfer** | **33,353** | **≈25,738 provisional** | 24 % — the historical audit floor sum was ≈28,550; the gap is the retained fork (no regeneration path at all), one turn function, one history, one delivery |
| **owned tests** | **35,826** | **≈23,100** | §7 rows sum: 20,079 touched → 7,400; 15,747 untouched |
