---
type: research
status: complete
created: 2026-09-15
tags: [agent, context, schema]
---

# Data audit B — session inputs, context, and provable completion

## Finding

**Start with an existing plan item bound to an existing subject and an
executable success query. No problem family or metrics registry is needed.**
The concrete counterexample is `juniper/report`: it carries a completion
transaction, but there is no message from Juniper to root. Reading/handling
an inbox edge, accepting a provider reply, closing a turn, completing a plan
item, and satisfying the requested work are different facts. P2/P3 below
measure that difference rather than infer success from an idle agent.

Three larger tasks need additional evidence before they can be generated
honestly: semantic need for elided data, comparable render cost, and complete
per-function execution attribution. Their gaps belong beside evaluation,
contribution, and plan facts, not in a new problem taxonomy (§4–§7).

### Scope and evidence boundary

This is a read-only research lane; the only authored repository file is this
note. No provider call, agent turn, default reset, refork, reseed, production
edit, or other lane operation was performed. Source references describe the
observed working tree, **including other lanes' unfinished changes**, not a
claim that those changes passed a gate. HEAD observed during the audit was
`93883543de1d5f09bbccaed6931ec42d9d55bdfa`; the working tree was already dirty
on entry and continued changing. In particular, `my.plan.item/done-query`,
`subject`, `seon.plan/settle-call`, and direct message writing were in flight.

Read end to end: [AGENTS.md](../../../../AGENTS.md),
[the draft stewards PRD](../plan/stewards-self-improving-prd-2026-09-15.md),
and the three model accounts
[run 4](explain_probe_run4_2026_09_15.edn),
[run 5](explain_probe_run5_2026_09_15.edn),
[run 7](explain_probe_run7_2026_09_15.edn).
Schema audit covers the requested families and their render/request/value
schemas; the census in Appendix A is derived from those resource maps,
not a hand-maintained list of supposed database attributes. Owning source
seams and dependency semantics are cited at each chain below. The source
inspection is an audit of these seams, not certification of every unrelated
function in the large owning namespaces.

`bin/seon status` observed PID 23729, default alive, PREPL 54412, web 7994.
MCP `runtime_status` returned **health unknown / flow unknown / Read timed
out**. This is a tool defect observation, not healthy runtime evidence.
JVM `eval_clj`, explicit `(seon.operator/connection "default")`, session
`data-audit-b`, worked. A combined linkage probe timed out at 20,000 ms;
smaller subsequent probes succeeded. No diagnosis of the timeout's cause is
claimed. Oversized MCP results P1/P2/P5/P8 triggered the tool's automatic blob
projection; the submitted forms themselves only read. This tool behavior is
not a task/session write. There were no lane-created shells left running or
scratch roots to delete.

**All counts are current-database counts at the named basis, not lifetime
event counts.** Maintenance kept advancing the basis. Ref absence can reflect
retraction: P4 found 65 captures with no remaining turn ref. Therefore a
present historic artifact and an intact attribution chain are separate
checks. No absent row is interpreted as a passing subject.

### Dependency ledger

| Mechanism | Dependency and first-party consumer | Consequence for this audit |
|---|---|---|
| Mid-transaction decision | `reference-code/datahike/src/datahike/db/transaction.cljc:1152`; `src/seon/plan.clj:562`, `src/seon/cluster/message.clj:711` | Evaluate completion/recipient decisions in the existing writer; do not pre-read then assert success. |
| Immutable database and read evidence | `src/seon/db.clj:468`; `src/seon/eval.clj:9`; `src/seon/turn.clj:1910` | One database value per detector; generated reads retain their dependencies. |
| Direct Var call preparation | `reference-code/sci/src/sci/core.cljc:310`, `reference-code/sci/src/sci/impl/analyzer.cljc:1778`; `src/seon/call_preparation.clj:1137` | Has context, callee, arguments before execution; no completion observation; excludes computed callees and self-reference. |
| Contract wrapper | `src/seon/instrument.clj:490`, `:583`; Malli `m/-instrument` with input/output/guard scopes | Sees contracted function execution, but does not currently record per-agent calls. |
| Bounded execution | `src/seon/sci/eval.clj:184`, `src/seon/turn.clj:3975` | New observation must use existing evaluation custody/bounds, not an unbounded global trace. |
| Exact saved text and projection | `src/seon/render/value.clj:395`, `src/seon/turn.clj:66`, `src/seon/repl.clj:246` | Measure the saved observation; do not regenerate historical text to claim what an agent saw. |
| Conditional filesystem write | `src/my/fs.clj:59`, `src/seon/fs/jvm.clj:651` | Export can reuse expected-absence/digest and atomic move, followed by an actual file read/gate. |

## 1. Chain: an unanswered message

| Input facts → detector → context → success → missing data | Concrete chain |
|---|---|
| **Input facts, present today** | P1: `:seon.message/id`, `to`, `content` each 15; `from` 2; `about` 11; `inbox` 7; `read-tx` 8; `caused-by` installed, 0. Agent identity/namespace each 2. The permanent recipient, pending edge, subject, and causal predecessor are explicitly different attributes (`resources/seon/schemas/seon.message.edn:20`, `:25`, `:121`, `:155`, `:203`). |
| **Detector** | Candidate public `seon.cluster.message/unanswered` takes a database and returns message refs lacking a reverse sender/recipient response about that message. P3 runs the query below: `aa1a135089bf` (root→root), `e10231f6` (root→juniper). This is a **response-obligation candidate**, not proof every message requires a reply: `about` concerns any entity; `caused-by` is conversation provenance. The query must not turn acknowledgements into infinite reply tasks (`src/seon/cluster/message.clj:88`). |
| **Context** | Pull selector M below starts at the message, follows sender/recipient identity, plans, `about`, `caused-by`, and incoming `about` messages. Message AI/HTML pair exists (`src/seon/cluster/message.clj:379`, `:387`); agent identity pair (`src/seon/cluster/agent.clj:194`, `:211`); plan/item pairs (`src/seon/plan.clj:1105`, `:1137`, `:1229`, `:1252`); runtime/turn pairs in transcript. Unknown `about` subjects use their own schema pair or the value floor. No new message pair needed. |
| **Success function** | Candidate `seon.cluster.message/answered?`, bound to the message eid, requires the original message and endpoints still exist, a response from recipient to sender whose `about` is the original message, plus handled edge evidence. P3: **false** for `e10231f6`; independent Juniper→root query returns `[]`. Existing `delivery` writes response and handling together (`src/seon/cluster/message.clj:221`). `close-call` also clears the trigger edge after an ordinary reply (`src/seon/turn.clj:393`), so edge removal alone cannot prove a response was sent. |
| **Genuinely missing** | For an explicitly generated response task: no message attribute. Use existing `my.plan.item/subject` + `done-query`; populate them at task construction. To auto-generate for arbitrary unsolicited messages, whether a response is owed is not derivable from message existence; simplest constraint is an explicitly authored response plan item. If a universal message obligation is later required, an asserted expectation belongs in `seon.message.edn` beside `about`/`caused-by`, not a problem entity. Do not declare it for the MVP. |
| **Writer / estimated cost** | Existing delivery: approximately 6 response attribute assertions (id/to/inbox/from/content/about), optional caused-by, one inbox retraction and one read-tx assertion; transaction metadata extra. Query-backed item adds two assertions (subject and query), completion adds one. Estimates are assertion counts, not measured latency. |

Candidate contracts (proposal, not installed by this lane):

```clojure
;; seon.cluster.message/unanswered
[:=> [:catn [:database :seon.db/db]]
 [:or [:vector :seon.db/ref] :seon.error/value]]
;; seon.cluster.message/answered?
[:=> [:catn [:database :seon.db/db] [:message :seon.db/ref]]
 [:or :boolean :seon.error/value]]
```

Detector query executed in P3 (return `?m` instead of `?id` in the public
ref-returning function):

```clojure
[:find ?id ?sender ?recipient
 :where
 [?m :seon.message/id ?id]
 [?m :seon.message/from ?a] [?a :seon.agent/id ?sender]
 [?m :seon.message/to ?b] [?b :seon.agent/id ?recipient]
 (not-join [?m ?a ?b]
   [?reply :seon.message/about ?m]
   [?reply :seon.message/from ?b]
   [?reply :seon.message/to ?a])]
```

Success query, bind `?subject` to the message eid (P3 used its identity-string
equivalent, reproduced in Appendix B). Convert a witness to true only after
checking for a `:seon.error` result; missing original subject is a diagnostic.
This proves delivery, **not correctness of arbitrary prose inside content**.

```clojure
[:find ?reply . :in $ ?subject
 :where
 [?subject :seon.message/id _]
 [?subject :seon.message/from ?a]
 [?subject :seon.message/to ?b]
 [?reply :seon.message/id _]
 [?reply :seon.message/content _]
 [?reply :seon.message/about ?subject]
 [?reply :seon.message/from ?b]
 [?reply :seon.message/to ?a]
 [?subject :seon.message/read-tx _]
 (not [?subject :seon.message/inbox _])]
```

Selector M:

```clojure
[:db/id :seon.message/id :seon.message/content :seon.message/read-tx
 {:seon.message/inbox [:seon.agent/id]}
 {:seon.message/from
  [:seon.agent/id {:seon.agent/namespace [:seon.ns/name]}
   {:seon.agent/plan
    [:my.plan/objective {:my.plan/steps
      [:my.plan.item/id :my.plan.item/title :my.plan.item/done-when
       :my.plan.item/completed-tx]}]}]}
 {:seon.message/to [:seon.agent/id {:seon.agent/namespace [:seon.ns/name]}]}
 {:seon.message/about [:db/id :seon.message/id :seon.error/id :my.plan.item/id]}
 {:seon.message/caused-by [:seon.message/id :seon.message/content]}
 {:seon.message/_about
  [:seon.message/id :seon.message/content
   {:seon.message/from [:seon.agent/id]} {:seon.message/to [:seon.agent/id]}]}]
```

## 2. Chain: a plan step with an open done-when

| Stage | Concrete chain |
|---|---|
| **Input facts, present today** | P1/P2: seven item ids/titles/positions/done-when/completed-tx, six items with needs; two plan owners, one objective/root-step collection. `done-query`, `subject`, nested `steps`, `about`, item `agent` installed but empty. Ownership is component reach, not the obsolete item agent ref (`src/seon/plan.clj:32`; `resources/seon/schemas/my.plan.item.edn:16`). |
| **Detector** | Candidate `seon.plan/open-criteria` uses the first query below: P3 result `[]`. Companion `seon.plan/unverified-completions` returns all seven ids because no completion query backs their stamp. That is **unverified**, not seven proven false criteria. `juniper/report` is independently false by §1. Existing `seon.plan/ready`, `blocked`, `current`, `item` are already queries/functions (`src/seon/plan.clj:312`, `:372`, `:385`, `:401`). |
| **Context** | Selector P below plus owner obtained by `seon.plan/rules` `(owned ?agent ?step)`. Pull subject using the task function's selector. Reuse item/plan/agent pairs; call `seon.plan/item` to supply derived state and completion time. A bare stored item is not identical to the derived `:my.plan/render-step` input. A nested subject's pair is selected from its own schema. |
| **Success function** | Candidate `seon.plan/satisfied?` accepts db + item ref; resolves `done-query`/`subject`, runs the query, and returns true only for a witness/truthy scalar. Missing item/query/subject or query refusal is an error, never success. `:my.plan.item/completed-tx` is a recorded transition, not this predicate. Current in-flight `done-query-result`, `query-satisfied?`, `complete-step-call` implement the query check (`src/seon/plan.clj:534`, `:551`, `:584`); `settle-call` selects open query-backed steps at `:562`. P1/P3: there are **zero populated instances to verify**. |
| **Genuinely missing** | The attributes already exist in the inspected resources; **population and a proved settlement invocation** are missing from this lane's live evidence. The initial source inspection found no settlement caller. The final source recheck found the concurrent implementation now calling `plan/settle-call` from system turns (`src/seon/turn.clj:2192`), individual settlement (`:3412`), batch settlement (`:3451`), and close (`:4623`). This is source evidence; this lane did not execute a query-backed completion on default. First verify that existing connection and populate tasks. For reusable context per step, optional `:my.plan.item/context` pull selector in `my.plan.item.edn` is sufficient if context cannot be derived from the generating function; no problem ref required. |
| **Writer / estimated cost** | Task creation: two assertions for query+subject, optionally one for context. Reuse the existing writer's `:db.fn/call`; first completion adds one assertion and possibly retracts current-step. Query work grows with selected open criteria; use the existing deadline, not one unbounded query per step. `query-deadline` at `src/seon/plan.clj:518` supplies one bound for the set. |

Candidate contracts:

```clojure
;; seon.plan/open-criteria and seon.plan/unverified-completions
[:=> [:catn [:database :seon.db/db]]
 [:or [:vector :seon.db/ref] :seon.error/value]]
;; seon.plan/satisfied?
[:=> [:catn [:database :seon.db/db] [:step :seon.db/ref]]
 [:or :boolean :seon.error/value]]
```

```clojure
;; P3: []
[:find ?id ?criterion :where
 [?s :my.plan.item/id ?id]
 [?s :my.plan.item/done-when ?criterion]
 (not [?s :my.plan.item/completed-tx _])]

;; P3: all seven juniper/* ids
[:find ?id :where
 [?s :my.plan.item/id ?id]
 [?s :my.plan.item/completed-tx _]
 (not [?s :my.plan.item/done-query _])]

;; Selector P
[:db/id :my.plan.item/id :my.plan.item/title :my.plan.item/description
 :my.plan.item/done-when :my.plan.item/done-query :my.plan.item/position
 {:my.plan.item/subject [:db/id]}
 {:my.plan.item/completed-tx [:db/txInstant]}
 {:my.plan.item/needs [:my.plan.item/id :my.plan.item/title
                       :my.plan.item/completed-tx]}
 {:my.plan.item/steps 8}]
```

**Success function implementation proposal:** factor the existing bounded
`done-query-result` + `query-satisfied?` into `satisfied?`, preserving refusal
details, then use that function in manual completion and automatic settlement.
The query is the computation, not an object requiring a new execution engine.
Do not use an unqualified `(count ...)` result as truth: Clojure's `0` is
truthy. Write an existence query or explicitly compare the count. Success at
a historical completion is evaluated against that completion's database
value; success now may legitimately differ after later domain changes.

## 3. Chain: a stalled or budget-exhausted session

| Stage | Concrete chain |
|---|---|
| **Input facts, present today** | P1: 155 turn ids/agents/opened-tx/closed-tx, 121 attempts, 398 evaluations, 396 saved shown/duration values, 128 evaluation error strings, eight dispositions; runtime owner/agent edges each two. `attempt/error` and evaluation `interrupted-at` are installed but empty. No durable session entity is needed to derive budget: `outside-wake-t` + `episode-runs` + effective max-episode-runs (`src/seon/turn.clj:2561`, `:2599`, `:2634`). |
| **Detector** | Candidate `seon.turn/deferred-work` combines the existing work queries, not a stored stalled flag. P3: Juniper `episode-runs=30`, `turns-left=0`, next work nil, deferred wake `b6913af3`; root `episode-runs=0`, `turns-left=100`, next nil, no unanswered wakes. P6's explicit closed-failed-attempt/no-reply query returns `[]`. This says there is current budget exhaustion; it does not reconstruct the earlier run-6 stall from absent current rows. |
| **Context** | Selector S below: agent→runtime→trigger/listens/turns, settings bound, plan, turns→attempts/errors and reverse evaluations/captures. Use existing agent/runtime/turn/evaluation/plan/message pairs. Attempts/usage have no declared entity pair; show through their owning turn or the floor. Capture AI intentionally returns nil (`src/seon/context.clj:440`); a diagnostic context must explicitly select the relevant capture evidence, not assume walking the capture prints the prompt. |
| **Success function** | Candidate `seon.turn/resumed?` requires a **later** closed turn with a successful agent-authored evaluation for the same agent after the recorded baseline. P6 ran it for Juniper after 536877341: **false**. This proves renewed execution only. A recovery task's terminal success must additionally call its original subject success function (§1/§2); replenishing budget, nil next work, and clearing an error are insufficient. |
| **Genuinely missing** | Budget and provider-refusal deferral need no new attributes. A claim that runnable work is stuck in Flow requires a bounded live proc observation or a stored fault carrying turn/agent attribution. A DB query alone cannot know whether a currently open evaluation is executing. Runtime health timed out here, so that branch is **unknown**. Use the existing `seon.error/run`/`agent` at the fault seam, not a second session status record. |
| **Writer / estimated cost** | Budget detector: zero writes. One task: ordinary plan facts + baseline (optional `:my.plan.item/basis-t` beside subject/query if a literal bound in the query is insufficient). Later execution already writes turn/evaluation facts. At most two additional refs per fault when custody was omitted. No heartbeat per function or mirrored remaining-turn counter. |

Candidate contracts:

```clojure
;; seon.turn/deferred-work
[:=> [:catn [:database :seon.db/db] [:agent :seon.agent/id]]
 [:or [:vector :seon.wake/unanswered] :seon.error/value]]
;; seon.turn/resumed?
[:=> [:catn [:database :seon.db/db] [:agent :seon.agent/id]
            [:after-basis :seon.db/basis-t]]
 [:or :boolean :seon.error/value]]
```

Detector's composition is exactly the P3 `:work` expression in Appendix B;
the public function first verifies agent/config/declarations, then returns
the pending wakes only when the same owner's admission rule defers them.
Provider-refusal query and positive success witness (P6):

```clojure
[:find ?id ?agent :where
 [?t :seon.turn/id ?id] [?t :seon.turn/agent ?a]
 [?a :seon.agent/id ?agent] [?t :seon.turn/closed-tx _]
 [?t :seon.turn/attempts ?attempt] [?attempt :seon.ai.attempt/error _]
 (not [?t :seon.turn/reply-size _])]

[:find ?e . :in $ ?agent-id ?basis :where
 [?a :seon.agent/id ?agent-id] [?t :seon.turn/agent ?a]
 [?t :seon.turn/id _ ?tx] [(> ?tx ?basis)]
 [?t :seon.turn/closed-tx _]
 [?e :seon.cluster.eval/run ?t] [?e :seon.cluster.eval/author :agent]
 [?e :seon.eval/shown _] (not [?e :seon.cluster.eval/error _])]

;; Selector S; historical windowing is the query's selection of turns,
;; not clipping a stored prompt or the HTML after it is rendered.
[:seon.agent/id {:seon.agent/namespace [:seon.ns/name]}
 {:seon.agent/settings [:seon.config.run/max-episode-runs
                        :seon.config.eval/time-limit-ms]}
 {:seon.agent/plan [:my.plan/objective {:my.plan/steps
   [:my.plan.item/id :my.plan.item/done-when :my.plan.item/done-query
    :my.plan.item/completed-tx]}]}
 {:seon.agent/runtime
  [{:seon.runtime/trigger [:seon.message/id :seon.message/content]}
   {:seon.runtime/listens [:seon.listen/attribute :seon.listen/entity :seon.listen/value]}
   {:seon.runtime/turns
    [:seon.turn/id :seon.turn/disposition :seon.turn.work/situation
     {:seon.turn/opened-tx [:db/txInstant]} {:seon.turn/closed-tx [:db/txInstant]}
     {:seon.turn/attempts [:seon.ai.attempt/id :seon.ai.attempt/usage-edn
                          {:seon.ai.attempt/error [:seon.error/id :seon.error/message]}]}
     {:seon.cluster.eval/_run [:seon.cluster.eval/id :seon.cluster.eval/source
       :seon.cluster.eval/author :seon.eval/shown :seon.eval/duration-ms
       :seon.cluster.eval/error :seon.cluster.eval/interrupted-at]}
     {:seon.context.capture/_run [:seon.context.capture/id
                                  :seon.context.capture/basis-t]}]}]}]
```

## 4. Chain: context elided something the agent needed

| Stage | Concrete chain |
|---|---|
| **Input facts, present today** | P1/P5: 396 shown strings; 65 selected renderer observations; 186 captures, 17,073 token/hash/position contribution records. Four tested structured elision attributes are **not installed**. P5's deliberately lexical audit finds 21 shown strings containing `:seon.print/omitted`; that is text evidence only, not 21 omitted nodes or 21 harmed tasks. Elision maps are in-memory print shapes (`resources/seon/schemas/seon.print.edn:221`, `:319`, `:352`), not currently queryable durable elision rows. |
| **Detector** | Candidate `seon.eval/needed-elisions` must return **unknown/missing evidence today**. Availability query below returns `[]`. A source-text search or adjacency between an error and an elision cannot prove that omission caused failure. P5's text observation merely supplies candidates to investigate with the saved context and model accounts. |
| **Context** | Selector E below: exact source/shown/error/renderer/read evidence, parent turn and capture. Existing evaluation pair is `seon.repl/render-ai`/`render-html` (`resources/seon/schemas/seon.eval.edn:3`). Elision has AI (`seon.print/render-elision-ai`), no dedicated HTML pair; HTML live values intentionally remain unclipped. Add a diagnostic pair only to a new **evaluation diagnostic projection**, rather than showing captured prompts in every ordinary context. Include the next query form and the subject's criterion. |
| **Success function** | Candidate `seon.eval/elision-recovered?` takes db + original evaluation + required path/identity. It must find a later successful read for that same required path and a stored observation that the required part was shown, then the task's success witness. Current source/read-evidence rows can prove that another query ran; they cannot prove that the needed path was visible. Return unknown today. No regex over source or printed output belongs in the production detector. |
| **Genuinely missing** | Proposal: `:seon.eval/elisions` in `seon.eval.edn`, carrying the bounded structured observations from the already-built print tree; an explicit empty collection or an `elisions-observed?` assertion distinguishes measured zero from pre-feature absence. Reuse `:seon.print/omitted`, `elision-unit`, `bound-by`, `requery-form`, and render-data path/offset definitions in the observation shape. Need is either declared in the task's context/criterion or explicitly reported: `:my.plan.item/required-paths` beside subject/context, or a later evaluation ref `:seon.eval/requeries` + requested path for an observed retrieval. Do not infer cognitive need. |
| **Writer / estimated cost** | `render.value/prepare` already has the final tree (`src/seon/render/value.clj:407`); return observation data alongside text to `turn/evaluation-facts` (`src/seon/turn.clj:66`) and settlement (`:1349`). One encoded bounded vector assertion per evaluation plus explicit observation coverage; size O(number of actual cuts), not full results. A retrieval needs one original-evaluation ref + path. Component encoding instead would cost roughly 5–9 assertions/cut; choose it only when Datalog over individual fields is required. Neither design serializes the live result. |

Candidate contracts:

```clojure
;; seon.eval/needed-elisions
[:=> [:catn [:database :seon.db/db] [:step :seon.db/ref]]
 [:or [:vector :seon.print/elision] :seon.error/value]]
;; seon.eval/elision-recovered?
[:=> [:catn [:database :seon.db/db] [:evaluation :seon.db/ref]
            [:path :seon.render.data/path]]
 [:or :boolean :seon.error/value]]

;; P5 availability query => []; report unknown, not no elisions.
[:find [?a ...] :in $ [?a ...] :where [_ :db/ident ?a]]
;; input attributes:
[:seon.print/omitted :seon.print/elision-unit
 :seon.print/requery-form :seon.print/bound-by]

;; Selector E
[:seon.cluster.eval/id :seon.cluster.eval/source :seon.cluster.eval/author
 :seon.eval/shown :seon.eval/renderer :seon.cluster.eval/error
 :seon.cluster.eval/read-basis-transaction
 {:seon.cluster.eval/read-evidence [*]}
 {:seon.cluster.eval/run
  [:seon.turn/id {:seon.turn/agent [:seon.agent/id]}
   {:seon.context.capture/_run
    [:seon.context.capture/id :seon.context.capture/basis-t
     :seon.context.capture/prompt]}]}]
```

**The model's own accounts constrain the task.** Run 4, line 1, says it read
elided output as empty and could not distinguish a live definition from
durable admission. Run 5, line 1, asks for failing contract paths and reports
empty change maps as distracting activity. Run 7, line 1, cites an omitted
988 rows, private definitions, unclear test-gate instructions, and missing
authorship cues. These are reports of confusion, not reliable execution
logs: its assertion that it sent root a message is falsified by P3, and its
claim about only seeing the budget at the end is not established by that
account. Preserve the exact captured prompt and explicit `author` already
stored; do not build success predicates from retrospective model prose.

## 5. Chain: an agent's renders cost more than a namespace median

| Stage | Concrete chain |
|---|---|
| **Input facts, present today** | P1/P4/P5: 65 evaluations have `renderer`; all 396 shown values can be sized; evaluation→turn→agent and renderer symbol→fn row→namespace establish attribution. `seon.render.cost/{shape-key,profile,estimated-tokens,at}` installed but **0 rows**. Contributions each have estimated tokens/hash/position, but `agent` and `evaluations` are 0. `contribution/agent` belongs to the older selection writer; do not populate a redundant agent backlink for captures—derive capture→turn→agent (`src/seon/context.clj:154`, `:471`). |
| **Detector** | Candidate `seon.render/expensive-agents` performs P5's query and aggregation: group saved selected-renderer evaluations by renderer namespace and receiving agent; compare each agent's mean uncalibrated shown-token estimate to the median of agent means in that namespace. Require at least two agents; report sample counts. P5 results below. This is an executable candidate detector, not a CPU-time or provider-dollar measurement. |
| **Context** | Selector E plus renderer function's `source`, `spec`, namespace, callers/tests, and contributing evaluations for baseline/comparison. Function/test raw entity AI/HTML pairs are absent (`resources/seon/schemas/seon.fn.edn:24`, `seon.test.edn:50`); function has a form producer. Use existing documentation/value floor or the program-side pair work. Cost/contribution rows themselves have no declared pair; a returned cost comparison needs an AI/HTML pair if retained as a whole diagnostic concern. |
| **Success function** | Candidate `seon.render/cost-improved?` binds a subject renderer and baseline evaluations/profile/input. Require a new successful render of the **same input and profile**, fewer estimated tokens, unchanged required-data checks, and the original task's success predicate. Comparing unrelated future mean cost, shrinking a profile, deleting samples, or omitting the subject must not satisfy it. No matched before/after render was performed here; result is unavailable. |
| **Genuinely missing** | First preserve existing contribution→evaluation refs: `contribution-row` accepts them (`src/seon/context.clj:486`), but `history-contributions` receives plain text segments and emits no refs (`src/seon/cluster/prompt.clj:145`). Preserve segment provenance from the existing history producer, rather than parse its text. To benchmark new renders, extend `seon.render.cost.edn` with evaluation/producer ref and observed profile/input identity; cost fact helper currently contains only shape/profile-id/tokens/time (`src/seon/render.clj:736`). A profile id alone does not capture changed dial values. Do not copy namespace/agent when refs already derive them. |
| **Writer / estimated cost** | Preserve one evaluation ref per contribution where it is known; no additional write for agent/namespace. For newly produced cost observations: existing four assertions + evaluation ref + producer ref + reproducible input key + concrete profile (about eight assertions), carried with the same evaluation settlement. Avoid writing another cost row on every unchanged history replay. Saved shown strings already support a rough baseline with zero new writes. |

P5 at basis **536877386**, all current linked evaluations with recorded
renderer, estimated tokens via `seon.ai.tokens/estimate`:

| Renderer namespace | Juniper mean (samples) | Root mean (samples) | Median of means | Candidate |
|---|---:|---:|---:|---|
| seon.bootstrap | 922 (1) | 882.6667 (3) | 902.3333 | Juniper |
| seon.error | 119.5 (2) | 141.2778 (18) | 130.3889 | root |
| seon.repl | 243.3333 (3) | 158.7143 (21) | 201.0238 | Juniper |
| seon.plan | 96.5 (12) | 53 (2) | 74.75 | Juniper |
| seon.test.accretion | 247 (2) | — | 247 | insufficient peers |
| seon.problems | — | 41 (1) | 41 | insufficient peers |

The tiny cohorts and different data sizes prohibit treating these candidates
as defects. The namespace is the **renderer owner's namespace**, not the
agent's assigned namespace; each assigned namespace has only one current
agent in P2. A lifetime total would penalize older sessions. A selected-value
token measure does not include repeated prompt exposure; that needs preserved
contribution provenance. Usage is stored as provider EDN on attempts, not in
the four `seon.ai.usage/*` datom attributes (`src/seon/turn.clj:3812`,
`src/seon/cluster/prompt.clj:88`).

Candidate contracts:

```clojure
;; seon.render/expensive-agents; proposed response schema in seon.render.cost.edn
[:=> [:catn [:database :seon.db/db]
            [:from-basis :seon.db/basis-t] [:through-basis :seon.db/basis-t]]
 [:or :seon.render.cost/comparisons :seon.error/value]]
;; comparisons: vector of namespace ref, agent ref, sample count,
;; mean estimated tokens, peer count, median, candidate boolean;
;; unavailable comparison is a typed diagnostic, not a zero median.
;; seon.render/cost-improved?
[:=> [:catn [:database :seon.db/db] [:baseline :seon.db/ref]
            [:candidate :seon.db/ref]]
 [:or :boolean :seon.error/value]]
```

P5 includes the full runnable query/aggregation in Appendix B. A production
window adds evaluation identity transaction bounds to that query; it must
also report missing renderer/profile coverage, not silently treat the 331
shown values without renderer observations as costless.

## 6. What render pairs actually receive

**There is no uniform five-key request on every pair today.** P7 passed viewer,
subject, detail, basis, window into `render-argument`; none survived. Returned
keys were agent id, database, distance, profile, value, total, turn id.
This is a read-only call of the loaded JVM Var, not a new SCI evaluation or
proof of browser paint.

| Layer / pair | Actual data path | Required accretion for a task context |
|---|---|---|
| `render-argument` | Explicit `select-keys`: db, ctx, agent id, turn id, call id, caps, time limit, core-error dial, connection, total, distance, profile, value root/cursor, walk attribute, partial AI output, rendering stack; value under `:seon.render/value` (`src/seon/render.clj:127`). | Declare and forward viewer/subject/window when used. `:seon.render/profile` already is detail; reuse it rather than introduce a second independent policy. Derive basis from the supplied db with `seon.db/basis-t`; an optional explicit basis must agree with db. |
| Ordinary entity producer | `producer-argument` merges entity attributes with custody and retains raw value; removes call id (`src/seon/render.clj:180`). | Keep viewer distinct from subject's identity. The merged entity's `:seon.agent/id` must not be overloaded as both. |
| Attribute-declared producer | `render-invocation-argument` can hand only the scalar/ref/collection when its contract accepts that, otherwise the unit (`src/seon/render.clj:359`). | Adding five request keys does not reach raw-value arities. Accrete a request-aware arity in each relevant pair and have this seam select it. Do not wrap a vector in an unexpected map and call that compatible. |
| Walk | Acquires members, computes remaining ref distance, associates entity and owning namespace for selection (`src/seon/render/walk.clj:665`). `seon.render/namespace` is the owner for candidate discovery, not the viewer. | Task function supplies viewer and subject root plus selector and exact db. Distance is reach, not amount of text. Window selects historical evidence before rendering. |
| Block | `src/seon/render/block.clj:61` only derives stable DOM ids. | No task-request propagation mechanism belongs here. |
| Plan | AI emits `(seon.plan/plan {})`, with selected-step advice; HTML derives owner/view (`src/seon/plan.clj:1229`, `:1252`). | A task about another agent's plan must supply explicit subject ownership in generated forms. Empty `{}` uses calling-agent defaults and can show the viewer's plan. |
| Agent | Identity pair and settings are separate (`src/seon/cluster/agent.clj:194`; `src/seon/agent.clj:122`). Settings AI emits `(seon.agent/settings)`. | For another subject, generated source needs that subject agent explicitly; viewer is guidance context, not custody replacement. |
| Runtime / turn | Runtime AI resolves owner and prints an explicit owner pull (`src/seon/render/transcript.clj:1058`, `:1079`); turn/history pairs at `:781`, `:884`, `:1041`. | Reuse owner resolution and saved history; task windows must not rewrite earlier shown text. |
| Message | AI produces a read by message id; inbox may receive a collection/ref (`src/seon/cluster/message.clj:379`, `:473`, `:516`). | Existing message identity is sufficient subject. Guidance can vary by viewer once the request-aware contract reaches it. |

Minimal request shape proposal in `resources/seon/schemas/seon.render.edn`:
existing db/value/profile/distance + `viewer` (namespace ref), `subject`
(entity ref), optional `window` (explicit lower/upper transaction bounds).
The caller carries task state separately as the existing plan item. Basis is
derived from db. If the owner retains the PRD spellings `detail`/`basis`, make
them one validated projection of profile/db at construction, not new sources
of truth. Include new semantic inputs in retained-call/invocation evidence
(`src/seon/render.clj:634`, `:655`); forwarding alone is insufficient if a
cached render for a different viewer can be reused.

## 7. Call ledger: place it with evaluation observations

| Question | Evidence and recommendation |
|---|---|
| Is a top-level evaluation already a call? | No. One stored source can contain many calls and effects. `seon.eval/duration-ms` measures the entire evaluation, not each function (`src/seon/turn.clj:66`). `fn-entries`, allocation, interop count, and outcome are declared but are not installed datom attributes in P1; the terminal whitelist at `src/seon/turn.clj:1349` persists duration but not those counters. |
| Which seam sees every call? | **None of the inspected seams has that guarantee.** SCI's hook sees every direct Var call, before its body; computed callees/self-reference/non-Var functions bypass it (dependency docs at `core.cljc:310`). Interpreted/host contract wrappers see their contracted invocations, including return/refusal, but anonymous bodies and unwrapped built-ins are not a universal named-call ledger (`src/seon/instrument.clj:490`, `:583`). |
| Smallest useful guarantee | Record **named contracted calls made under an agent evaluation**, and state this coverage. Attach an observation accumulator to that evaluation's carried environment/context, and record at the existing wrapper's completion/refusal seam. Preparation refusals need their own terminal observation before `reduced` returns. Never claim a preparation attempt executed the function. |
| Where do facts belong? | Prefer `:seon.eval/calls` owned components in `seon.eval.edn`, with fn ref, ordinal, outcome, duration; turn and agent derive through the parent evaluation. If every sample is instead aggregated per turn, put turn-owned components in `seon.turn.edn`; do not label a whole-turn aggregate an evaluation. A new top-level `seon.call` family is unnecessary for the MVP. |
| Is outcome an allowed enum? | Yes, a bounded observation disposition (returned, refused, threw, interrupted), not an entity kind. Preserve returned `:seon.error` versus a thrown core fault. Duration is observed elapsed time; nested inclusive durations do not sum to total turn time. |
| Cost / bound | Roughly 5 assertions per sample (parent edge, fn, ordinal, outcome, duration), plus coverage evidence. Full per-call durability scales O(calls), potentially millions. For the MVP use bounded per-evaluation aggregates by fn+outcome (count + total/max duration), with explicit aggregate coverage and any omitted count. No per-call database transaction, no copied arguments/results, and no global mutable collector. A hard interruption must record incomplete coverage rather than zero calls. |

Coverage must be an explicit observation; absence of samples is ambiguous
without it. Do not use call-preparation's cached plan state as an execution
ledger: it belongs to the cluster and is shared by contexts
(`src/seon/call_preparation.clj:74`). The new accumulator belongs to an
evaluation, not that shared state. The wrapper needs carried agent/turn/eval
custody; instrumentation currently selecting a schema projection does not
itself provide that attribution. This is future work and not needed to
construct the first complete-by-definition task.

### Explicit observation fields for the non-viable chains

These are proposed additions, not populated rows or implemented guarantees.
They make the unknown success predicates in §4–§5 concrete.

| Addition / resource | Producer and guarantee | Consumer / estimated writes |
|---|---|---|
| `:seon.eval/requeries` ref and `:seon.eval/requested-path` in `seon.eval.edn` | An explicit requery request carries its originating evaluation and logical path through evaluation admission. Do not infer these from source text. | Retrieval attribution; two assertions per attributed read. |
| `:seon.eval/shown-paths` in `seon.eval.edn` | The existing value-render step records which **explicitly requested** paths survived projection, using its final tree. This is a small set of requested paths, not an enumeration of the result. An observation-coverage value distinguishes unobserved from measured empty. | Positive visibility witness; O(requested paths) values plus one coverage observation. This is additional to the structured cuts proposed in §4. |
| `:seon.render.cost/evaluation`, `producer`, `input-key`, `profile-value` in `seon.render.cost.edn` | The same render settlement carries evaluation/producer refs and the concrete effective profile. `input-key` identifies a reproducible input specification (source, immutable input database basis, and selector/path); a private object with no reproducible input specification is ineligible. | Matched comparison, approximately four additional assertions per measured render. Keep the existing profile-id attribute's semantics unchanged. |
| `:seon.eval/calls` component refs; `callee`, `call-ordinal`, `call-outcome`, `call-duration-ms` in `seon.eval.edn` | Proposed bounded wrapper observations from §7; use distinct call attributes so existing whole-evaluation `outcome` and `duration-ms` retain their meaning. Aggregate variants add count/total/max fields in the same resource. | Named contracted-call report. No copied agent/turn refs: derive through the owning evaluation. Five assertions/sample before coverage. |

Proposed retrieval witness (not run: its new attributes are absent today).
The public function checks presence/coverage first, and conjuncts this
witness with the original task's domain success query:

```clojure
[:find ?later . :in $ ?original ?required-path
 :where
 [?original :seon.cluster.eval/id _ ?original-t]
 [?later :seon.eval/requeries ?original]
 [?later :seon.cluster.eval/id _ ?later-t]
 [(> ?later-t ?original-t)]
 [?later :seon.eval/requested-path ?required-path]
 [?later :seon.eval/shown-paths ?required-path]
 [?later :seon.eval/shown _]
 (not [?later :seon.cluster.eval/error _])]
```

Proposed matched-cost witness (not run: the current cost population is empty
and the attribution attributes are absent). The supplied refs name fixed
observations. The complete success function must also run the subject's
required-data checks on the candidate result; this query alone proves only
a measured reduction for matched inputs:

```clojure
[:find ?candidate . :in $ ?baseline ?candidate
 :where
 [?baseline :seon.render.cost/producer ?producer]
 [?candidate :seon.render.cost/producer ?producer]
 [?baseline :seon.render.cost/input-key ?input]
 [?candidate :seon.render.cost/input-key ?input]
 [?baseline :seon.render.cost/profile-value ?profile]
 [?candidate :seon.render.cost/profile-value ?profile]
 [?baseline :seon.render.cost/estimated-tokens ?before ?baseline-t]
 [?candidate :seon.render.cost/estimated-tokens ?after ?candidate-t]
 [(> ?candidate-t ?baseline-t)]
 [(< ?after ?before)]
 [?candidate :seon.render.cost/evaluation ?evaluation]
 [?evaluation :seon.eval/shown _]
 (not [?evaluation :seon.cluster.eval/error _])]
```

A missing cost row, missing coverage, or absent required-data predicate is a
typed refusal. The useful first task remains §10; none of these observations
is a prerequisite to proving a response was delivered.

## 8. Cross-family links verified, not guessed

| Claim in the draft | Probe / owner evidence | What follows |
|---|---|---|
| Fault op names the responsible fn and run reveals its user | P2: 3,448 error ids, 3,390 op values, one run ref, zero steward refs. P6's one linked fault is `7ec5ffc9-a329-44b1-9b0b-9ce722352f4f`, op `:seon.agent/turn-completion-backstop`, failing fn `seon.turn/turn-completion-backstop-failure`, turn `958adc16c4b1`, agent root. `error/prepare` records **Flow op** separately from `seon.instrument/fn` (`src/seon/error.clj:540`). | Use failing-function attribution, not op-as-function. The one linked fault proves this path can write custody, not that every turn fault does. `record-attempt!` supplies agent+turn (`src/seon/turn.clj:3782`); `error/prepare` stores them (`src/seon/error.clj:557`). No new run attribute is needed; audit individual callers missing custody. |
| Tests have subject and basis | Schema uses `:seon.test/sym` and `run-basis-t`, not id/basis-t (`resources/seon/schemas/seon.test.edn:1`). An initial exploratory query for the latter spellings correctly refused; it is not evidence that test results lack a basis. P4's Juniper test has basis 536876445, pass 2/fail 0/error 0, no subject. | `seon.test/run` and `runner/record-tx` already write basis/results (`src/seon/test.clj:9`, `src/seon/test/runner.clj:1290`). No mirrored test result family needed. |
| Agent function namespace/callers exist | P4 function `my.agents.juniper/largest-customer` has `admission/source :agent` and `fn/ns my.agents.juniper`, but no incoming calls. P6's exact test source calls it twice; P4's test calls list contains only `clojure.core/=` and `clojure.test/is`. P7 `seon.fn/tests-reaching` returns `[]`. | Namespace link works; call graph coverage is not proven. Repair analysis/admission into existing `seon.fn/calls`; never add a hand-rostered test list. The test's use of private `example-rows` also prevents treating its current pass as a source-file round-trip proof. |
| Every capture can be assigned to an agent | P4: 65 of 186 captures lack a turn ref; P1 `contribution/evaluations` empty. | Exclude/report unlinked observations. Do not assign them by reading prose. Current counts cannot establish when/why refs disappeared; no cause is attributed to a lane. |

Existing tracked issues, read for scope:
[asserted completion](../../../seon/issues/a-plan-step-can-be-marked-complete-without-its-done-when-being-true.md)
and [discarded nested send](../../../seon/issues/archive/my-message-send-inside-a-compound-form-is-silently-lost.md)
(archived by its owner during this audit).
This note records the additional audit gaps (call-graph coverage, elision
observations, contribution provenance, tool timeout) within the explicitly
assigned one-note boundary; it does not create another issue registry.

## 9. One-page system picture — inputs → functions → outputs

Every row is a family touched by this audit; concrete writers/readers above
are the evidence. Request/projection families are values, not automatically
stored datoms.

| Producer | Attributes / values | Consumer |
|---|---|---|
| Agent creation / namespace stewardship | `seon.agent/{id,namespace,plan,settings,runtime}` | identity, plan, configuration, per-agent Flow, task subject |
| Turn opening / accepted reply / close | `seon.runtime/{agent,turns,trigger}`; `seon.turn/{id,agent,opened-tx,closed-tx,disposition,attempts}` | budget/work derivation, history, positive execution witness |
| `next-agent-work`, settlement | `seon.turn.work/{situation,next,forms,settled?}` (mostly derived values) | per-agent proc transitions; diagnostic context |
| SCI source admission + settlement | `seon.cluster.eval/{id,run,ordinal,source,author,ns,error,read-evidence}` + `seon.eval/{shown,renderer,duration-ms}` | history, refreshed reads, error/context/cost detectors |
| Provider attempt settlement | `seon.ai.attempt/{id,at,settings-edn,usage-edn,error,...}` | turn status, token calibration, exact attempt evidence |
| Usage decoder | `seon.ai.usage/*` values | calibration/reporting; these are not separate live usage rows |
| Prompt assembly → capture transaction | `seon.context.capture/{id,run,basis-t,prompt,contributions}` | explain/debug, exact historical context; AI capture pair omits it ordinarily |
| History segment measurement | `seon.context.contribution/{id,position,hash,tokens}` + block name | prompt cost; evaluation attribution currently missing on captures |
| Message writer / turn close | `seon.message/{id,from,to,about,caused-by,inbox,read-tx,content}` | wake routing; response detector; response success |
| Plan author / completion transaction | `my.plan/{agent,objective,steps,current-step}`, `my.plan.item/{id,title,position,needs,done-when,done-query,subject,completed-tx}` | ready-work detector → task context → agent action → query witness → completion |
| Note writer | `my.note/{id,agent,content,about}` | ordinary remembered authored prose; not typed evidence that an arbitrary claim is true (`src/seon/note.clj:1`) |
| Authored listens / schema population | `seon.listen/{attribute,entity,value}`, `seon.wake/{listen,opens-turn?,inside,context-inert}` | Datahike listener→wake channel→existing work derivation (`src/seon/cluster/wake.clj:92`, `:369`) |
| Renderer selector + value projection | `seon.render/{value,profile,distance,...}`, `seon.render.walk/*`, `seon.render.value/*`, `seon.print/*` | AI shown text / HTML; only observation facts should be retained |
| Render data acquisition / lint / UI | `seon.render.data/*`, `lint/*`, `debug/*`, `web/*`, `hiccup/*`, `transcript/*` | bounded query pages, render QA, HTML and retained history; not new task state |
| Block address / delivery | `seon.render.block/name`, `seon.render.package/*` | stable DOM target and revisioned browser package; no task success authority |
| Optional render cost capture | `seon.render.cost/{shape-key,profile,estimated-tokens,at}` | potential cost comparison; empty today; evaluation text is usable first |
| Schedule task + cron definition → firing | `seon.schedule/*`, `seon.schedule.task/{owner,function,schedule}`, `seon.schedule.fire/{task,agent,nominal-at,observed-at}` | maintenance function execution; fire's wake does not itself open a model turn (`src/seon/schedule.clj:318`) |
| Maintenance invocation / settlement | `seon.maintenance.request/*`, `seon.maintenance.receipt/*`, `seon.maintenance.result/*`, declared result-projection/attention-when | existing report and pair; P1: six tasks, 704 starts, 703 completed results; absence of terminal result is visible, not success (`src/seon/maintenance.clj:309`) |

Thus the useful chain is ordinary data all the way:

```text
domain facts + immutable db
  → detector function → subject refs
  → existing plan item (subject + success query + criterion)
  → subject pull + existing pairs + explicit viewer/profile
  → system evaluation with saved shown text
  → ordinary bounded agent turns
  → domain/program writes
  → success function against writer db
  → completion transaction
  → exact program source projection + conditional file write + fresh gate
```

Scheduling the detector later is an existing schedule task pointing to a
function; it does not require a registry of problem kinds. Initial operation
can invoke the detector once. Query read evidence already describes what a
detector observed; wiring continuous detection is separate from the first
complete chain.

## 10. Minimum viable point — one reusable response task, then files

**Recommended constraint:** first support an explicitly chosen agent-to-agent
request that owes a response about that message. Prove delivery; do not claim
to machine-verify arbitrary prose correctness. The message chain needs no new
domain attributes and already has a concrete false witness (`e10231f6`).
To satisfy the owner's programming-files goal, have the agent install the
small pure response predicate and its test as the reusable result of this
task, then produce the owed response. A message-only result is useful but
does not itself yield programming files.

Build in this dependency order (proposal; nothing in this list was executed
against default by this research lane):

1. **Data:** finish/populate existing `my.plan.item/subject` and `done-query`
   in the owning lane; use existing `done-when` for the exact guarantee.
   Require the subject row before generating an item. Stable item id derives
   from the detector fn identity + subject + relevant basis through `seon.id`.
   No `seon.problem`, task-kind stamp, metric registry, or call ledger.
2. **Read functions:** install `seon.cluster.message/unanswered` and
   `answered?` from §1 with complete contracts and typed absent-subject
   behavior. Retain the query body as the computation; a tiny generating
   function turns a chosen subject into ordinary plan transaction data.
   For this first task, the context selector is known from the generating
   function; a stored context attribute is optional, not prerequisite.
3. **Write and prove:** reuse the concurrent fix for direct `my.message/send`
   delivery inside a compound form (its owner archived the issue during this
   audit); verify the existing plan success function's new settlement connections.
   Completion must occur after response facts exist in the writer database.
   Test reply absence, deleted subject, wrong sender/recipient, unhandled
   edge, and proper witness. A count of zero and a query error must not pass.
4. **Context:** use message/agent/plan/item pairs with explicit subject
   arguments in generated source. The single-subject MVP can use the
   current profile and supplied db; universal viewer-sensitive pairs and a
   statistics window can follow. Teach the exact completing/reporting calls
   and return the success query's observed result, not a prose assertion.
5. **Bounded session:** the ordinary turn graph runs it. Juniper currently
   has no budget; an owner-authorized new task/session is necessary for a
   live trial. This lane does not mutate its budget or wake it. Persist the
   reusable predicate and test through ordinary program admission. The test
   must construct its data through canonical fixtures/explicit arguments,
   with no dependency on private `example-rows` or retained result objects.
6. **Positive success:** response witness true **and** the intended function
   has source/spec/ns, a reaching test with positive passes/zero failures
   at the relevant program basis, and a fresh execution of that test. The
   observed Juniper artifact currently fails the reaching-test condition
   (P7). Repair existing call-edge production, or explicitly test the selected
   function as the initial bounded constraint; do not infer reach from names.
7. **Persist as programming files:** add one pure `seon.program/source-files`
   projection over the **existing** program shapes and source attributes
   (`src/seon/program.cljc:45`), taking selected identities and an explicit
   destination mapping. Return exact `{path, text, source identities, basis}`
   data with a declared `seon.program` response shape. It must include
   namespace/requires, function contracts, required schema resources, and
   standalone test source; refuse missing source/private dependencies.
   The schema family belongs in `resources/seon/schemas/seon.program.edn`.
   Reuse `my.fs/write!` with absence/digest preconditions; don't add a file
   writer or confuse `seon.cluster.export/export!` (a store export) with
   source files. This is the session-side export boundary proposal, not a
   claim a complete source exporter already exists.
8. **Final proof:** read the actual files, load them through the normal
   publication/test harness in a fresh bounded fixture, run the selected
   tests under armed contracts, and verify the response predicate against
   its subject. Record the source-file digest/basis using existing file
   effect/test evidence. Multi-file publication is a reviewed change set;
   one atomic file move is not an atomic multi-file publication. Only then
   claim stored data → detector → context → session → success → files.

Estimated MVP new domain facts: **zero** beyond populating the two in-flight
plan attributes; optional per-task baseline/context if needed. New pure
functions: detector, success predicate, plan-data construction, and source-file
projection. Pair work: explicit-subject source generation where current pairs
assume the calling agent; no mandatory new message/plan pair. The harder
elision, cost, and call-observation chains should not delay this first proof.

The two additional candidate public functions have these proposed contracts;
they are ordinary data transformations, not new execution mechanisms:

```clojure
;; seon.cluster.message/response-task-data
[:=> [:catn [:database :seon.db/db] [:message :seon.db/ref]]
 [:or :seon.db/tx-data :seon.error/value]]
;; seon.program/source-files
[:=> [:catn [:database :seon.db/db]
            [:request :seon.program/source-files-request]]
 [:or :seon.program/source-files :seon.error/value]]
```

Declare the exporter request/result shapes in `seon.program.edn` beside the
existing `identity`, `source-attribute`, and `rows` declarations. The request
carries selected program identities and explicit namespace→destination data;
each returned file carries namespaced path/text/source-identities/basis keys.
The function returns no live objects or hidden filesystem effects. Transaction
admission still verifies plan ownership and subject refs at the writer.

## Verification and landing boundary

`bin/test --paths docs/prds/context-generation/research/data-audit-b-2026-09-15.md --platform`
passed against snapshot HEAD `af278535cb7369726983f4ee60cf739abc5381bf`
plus only this note: **84 tests, 505 assertions, 0 failures, 0 errors**.
The gate reported 179 seconds for coordinator/tests and removed its successful
isolated root `tmp/test-runs/run.AFgRZv`. It did not exercise the proposed
functions in this document or certify concurrent production edits. Later
edits to this note add probe evidence and wording only. The final document
also passes whitespace and local-link checks.

The exact live verification boundary is P1–P8: immutable database queries,
existing work functions, four successful context pulls, and one loaded JVM
render-request preparation call. No generated task was executed; no proposed
attribute, pair, call observation, or source exporter was installed. This
research result is the input to that implementation, not a claim it exists.

## Appendix A — live schema census

P1 ran at basis **536877292**. Counts are distinct current entities carrying
the attribute, as returned by the Datalog aggregate. Installed-empty means
the attribute exists in Datahike and no row carries it; non-installed schema
keys include legitimate request, result, predicate, and render shapes.
They must not all be relabeled missing durable data.

The exact census expression and measured populated/empty attributes follow.

### P1 — exact census

```clojure
(let [db @(seon.operator/connection "default")
 files (filter #(and (.isFile %) (.endsWith (.getName %) ".edn")
                    (some (fn [prefix] (.startsWith (.getName %) prefix))
                          ["seon.agent." "seon.runtime." "seon.turn." "seon.cluster.eval." "seon.eval." "seon.ai.attempt." "seon.ai.usage." "seon.context.capture." "seon.context.contribution." "seon.message." "my.plan." "my.note." "seon.listen." "seon.wake." "seon.render." "seon.print." "seon.schedule." "seon.maintenance."]))
               (file-seq (clojure.java.io/file "resources/seon/schemas")))
 attrs (sort (mapcat #(keys (clojure.edn/read-string (slurp %))) files))
 used (into {} (seon.db/q '[:find ?a (count ?e) :where [?e ?a _]] db))
 installed (set (seon.db/q '[:find [?a ...] :where [_ :db/ident ?a]] db))]
 {:basis (:max-tx db)
  :populated (into (sorted-map) (for [a attrs :when (get used a)] [a (get used a)]))
  :installed-empty (vec (filter #(and (installed %) (not (get used %))) attrs))
  :not-installed (vec (remove installed attrs))})
```

Returned value (JSON encoding; elapsed 1040 ms):

```json
{
  "basis": 536877292,
  "populated": {
    "seon.maintenance.request/log-dir": 704,
    "seon.schedule/expression": 6,
    "seon.schedule/id": 6,
    "my.note/id": 1,
    "seon.turn.work/situation": 123,
    "seon.maintenance.receipt/fire": 704,
    "seon.cluster.eval/triage-edn": 93,
    "seon.print/level": 1,
    "seon.cluster.eval/read-basis-transaction": 396,
    "my.note/agent": 1,
    "seon.cluster.eval/error": 128,
    "seon.schedule.task/schedule": 6,
    "seon.cluster.eval/read-evidence": 308,
    "seon.schedule.fire/nominal-at": 704,
    "seon.schedule/zone-id": 6,
    "seon.cluster.eval/source": 398,
    "seon.maintenance.receipt/request": 704,
    "seon.cluster.eval/author": 398,
    "seon.maintenance.result/process-census-dead": 11,
    "seon.wake/inside": 4,
    "seon.schedule.fire/id": 704,
    "seon.cluster.eval/comment": 265,
    "seon.turn/disposition": 8,
    "seon.agent/plan": 2,
    "seon.message/to": 15,
    "seon.message/about": 11,
    "my.plan.item/title": 7,
    "seon.turn/trigger": 9,
    "seon.eval/duration-ms": 396,
    "seon.wake/context-inert": 61,
    "seon.eval/renderer": 65,
    "seon.context.capture/contributions": 186,
    "seon.maintenance.request/agent": 704,
    "seon.maintenance.result/root-claim-creator-start-instant": 3144,
    "seon.print/length": 1,
    "seon.maintenance.receipt/task": 704,
    "seon.ai.attempt/finish-reason": 121,
    "seon.turn/agent": 155,
    "seon.maintenance.request/fire": 704,
    "seon.cluster.eval/id": 398,
    "seon.maintenance.request/cluster-name": 704,
    "seon.runtime/trigger": 1,
    "seon.context.contribution/id": 17073,
    "seon.maintenance.receipt/started-at": 704,
    "my.plan.item/completed-tx": 7,
    "seon.maintenance.request/managed-root": 704,
    "seon.maintenance.result/process-census-processes": 11,
    "seon.maintenance.result/root-claim-path": 3144,
    "seon.context.capture/id": 186,
    "seon.wake/opens-turn?": 4,
    "seon.turn/starting-ns": 155,
    "seon.message/id": 15,
    "seon.runtime/agent": 2,
    "seon.agent/runtime": 2,
    "seon.maintenance.result/root-claim-creator-pid": 3144,
    "my.note/content": 1,
    "seon.agent/id": 2,
    "my.plan.item/position": 7,
    "seon.render/html": 376,
    "seon.maintenance.request/repository-root": 704,
    "seon.message/from": 2,
    "seon.render/units": 2,
    "seon.render/form": 12,
    "seon.ai.attempt/id": 121,
    "seon.context.contribution/hash": 17073,
    "seon.ai.attempt/ordinal": 121,
    "my.plan.item/needs": 6,
    "seon.schedule.task/function": 6,
    "seon.cluster.eval/ns": 398,
    "seon.ai.attempt/at": 121,
    "seon.maintenance.result/process-census-roots": 11,
    "seon.turn/reply": 151,
    "seon.ai.attempt/usage-edn": 121,
    "seon.message/content": 15,
    "seon.cluster.eval/run": 398,
    "seon.cluster.eval/output": 2,
    "seon.maintenance.receipt/result": 703,
    "seon.agent/settings": 2,
    "seon.maintenance.request/handler": 704,
    "seon.schedule.fire/observed-at": 704,
    "seon.turn/attempts": 121,
    "seon.maintenance.receipt/id": 704,
    "seon.agent/namespace": 2,
    "seon.cluster.eval/ordinal": 398,
    "seon.message/inbox": 7,
    "seon.schedule.task/id": 6,
    "seon.wake/listen": 4,
    "my.plan/objective": 1,
    "seon.eval/shown": 396,
    "seon.ai.attempt/settings-edn": 121,
    "seon.turn/closed-tx": 155,
    "seon.turn/opened-tx": 155,
    "seon.schedule.fire/task": 704,
    "seon.maintenance.result/root-claim-id": 3144,
    "seon.maintenance.receipt/completed-at": 703,
    "seon.context.capture/basis-t": 186,
    "seon.print/default": 5,
    "seon.maintenance/attention-when": 13,
    "seon.maintenance.request/nominal-at": 704,
    "my.plan.item/done-when": 7,
    "seon.maintenance.result/root-claim-reap-on-owner-exit?": 3144,
    "my.plan/agent": 2,
    "seon.turn/reply-blob": 2,
    "seon.maintenance.request/observed-at": 704,
    "seon.turn/id": 155,
    "seon.cluster.eval/at": 398,
    "seon.maintenance/result-projection": 4,
    "seon.context.capture/prompt": 186,
    "seon.context.contribution/position": 17073,
    "seon.render.value/max-collection": 1,
    "seon.context.contribution/tokens": 17073,
    "seon.maintenance.receipt/handler": 704,
    "seon.maintenance.request/id": 704,
    "seon.context.capture/run": 121,
    "seon.schedule.fire/agent": 704,
    "seon.turn/reply-size": 154,
    "seon.render/ai": 381,
    "my.plan.item/id": 7,
    "seon.schedule.task/owner": 6,
    "seon.maintenance.request/task": 704,
    "seon.message/read-tx": 8,
    "seon.runtime/turns": 2,
    "my.plan/steps": 1,
    "seon.maintenance.result/id": 703,
    "seon.render.block/name": 17073
  },
  "installed-empty": [
    "my.note/about",
    "my.plan/current-step",
    "my.plan.item/about",
    "my.plan.item/agent",
    "my.plan.item/description",
    "my.plan.item/done-query",
    "my.plan.item/steps",
    "my.plan.item/subject",
    "seon.ai.attempt/delay-ms",
    "seon.ai.attempt/error",
    "seon.ai.attempt/failover-from",
    "seon.ai.attempt/reasoning",
    "seon.ai.attempt/reasoning-blob",
    "seon.ai.attempt/reasoning-size",
    "seon.ai.attempt/truncation",
    "seon.cluster.eval/interrupted-at",
    "seon.cluster.eval/refreshes",
    "seon.context.contribution/agent",
    "seon.context.contribution/error",
    "seon.context.contribution/evaluations",
    "seon.listen/attribute",
    "seon.listen/entity",
    "seon.listen/value",
    "seon.maintenance.receipt/error",
    "seon.maintenance.receipt/interrupted-at",
    "seon.maintenance.result/cluster-cleanup-collection",
    "seon.maintenance.result/collect-branches",
    "seon.maintenance.result/process-census-claim-errors",
    "seon.maintenance.result/process-census-unclaimed",
    "seon.maintenance.result/process-census-unresponsive",
    "seon.maintenance.result/reap-census",
    "seon.maintenance.result/reap-refused",
    "seon.maintenance.result/reap-roots",
    "seon.maintenance.result/reap-stopped-processes",
    "seon.message/caused-by",
    "seon.render.cost/at",
    "seon.render.cost/estimated-tokens",
    "seon.render.cost/profile",
    "seon.render.cost/shape-key",
    "seon.runtime/listens"
  ],
  "not-installed": [
    "my.note/about-not-found",
    "my.note/about-not-found-error",
    "my.note/add-request",
    "my.note/agent-not-found",
    "my.note/agent-not-found-error",
    "my.note/forget-request",
    "my.note/identity-owned-by-another-agent",
    "my.note/identity-owned-by-another-agent-error",
    "my.note/not-found",
    "my.note/not-found-error",
    "my.note/not-owned",
    "my.note/not-owned-error",
    "my.note/note",
    "my.note/notes",
    "my.plan/add-request",
    "my.plan/added",
    "my.plan/agent-not-found",
    "my.plan/agent-not-found-error",
    "my.plan/agent-state",
    "my.plan/basis-t",
    "my.plan/blocked",
    "my.plan/changed",
    "my.plan/complete-request",
    "my.plan/completion",
    "my.plan/component-input",
    "my.plan/component-view",
    "my.plan/converged?",
    "my.plan/current-request",
    "my.plan/current-value",
    "my.plan/current?",
    "my.plan/dependency-cycle",
    "my.plan/dependency-cycle-error",
    "my.plan/dependency-not-found",
    "my.plan/dependency-not-found-error",
    "my.plan/depth",
    "my.plan/diff",
    "my.plan/duplicate-identity",
    "my.plan/duplicate-identity-error",
    "my.plan/duplicate-position",
    "my.plan/duplicate-position-error",
    "my.plan/entity",
    "my.plan/foreign-identity",
    "my.plan/foreign-identity-error",
    "my.plan/identity-exists",
    "my.plan/identity-exists-error",
    "my.plan/intent-subjects",
    "my.plan/item-ids",
    "my.plan/item-reference-not-found",
    "my.plan/item-reference-not-found-error",
    "my.plan/item-reference-not-owned",
    "my.plan/item-reference-not-owned-error",
    "my.plan/item-request",
    "my.plan/items-request",
    "my.plan/needs",
    "my.plan/not-found",
    "my.plan/not-found-error",
    "my.plan/not-owned",
    "my.plan/not-owned-error",
    "my.plan/older-completions",
    "my.plan/parent",
    "my.plan/parent-step",
    "my.plan/plan-result",
    "my.plan/ready",
    "my.plan/ready-items",
    "my.plan/recent-completions",
    "my.plan/render-step",
    "my.plan/render-steps",
    "my.plan/request",
    "my.plan/retracted",
    "my.plan/stable-item-reference",
    "my.plan/state",
    "my.plan/step-summary",
    "my.plan/subject-not-found",
    "my.plan/subject-not-found-error",
    "my.plan/unusable-current-step",
    "my.plan/unusable-current-step-error",
    "my.plan/update-fields",
    "my.plan/update-request",
    "my.plan.item/about-token",
    "my.plan.item/add-request",
    "my.plan.item/item",
    "seon.agent/agent",
    "seon.agent/arm-request",
    "seon.agent/armed",
    "seon.agent/armer-quiescence-undeliverable",
    "seon.agent/armer-quiescence-undeliverable-error",
    "seon.agent/blueprint-request",
    "seon.agent/context-state",
    "seon.agent/count",
    "seon.agent/creation-incomplete",
    "seon.agent/creation-incomplete-error",
    "seon.agent/creation-request",
    "seon.agent/creation-result",
    "seon.agent/creation-tx",
    "seon.agent/disarm-request",
    "seon.agent/eid",
    "seon.agent/identity-request",
    "seon.agent/namespace-ref",
    "seon.agent/no-such-agent",
    "seon.agent/no-such-agent-error",
    "seon.agent/open-run-ref",
    "seon.agent/protocol-namespaces",
    "seon.agent/routing",
    "seon.agent/situation",
    "seon.agent/source-submission-request",
    "seon.agent/source-submission-result",
    "seon.agent/supervision-not-committed",
    "seon.agent/supervision-not-committed-error",
    "seon.agent/turn-completion-backstop",
    "seon.agent/turn-completion-backstop-error",
    "seon.agent/turn-completion-undeliverable",
    "seon.agent/turn-completion-undeliverable-error",
    "seon.agent/unread-message-count",
    "seon.ai.usage/cached-tokens",
    "seon.ai.usage/completion-tokens",
    "seon.ai.usage/prompt-tokens",
    "seon.ai.usage/total-tokens",
    "seon.cluster.eval/read-evidence-entity",
    "seon.cluster.eval/receipt",
    "seon.cluster.eval/settle-request",
    "seon.context.capture/capture",
    "seon.context.contribution/contribution",
    "seon.eval/allocated-bytes",
    "seon.eval/entity",
    "seon.eval/fn-entries",
    "seon.eval/host-interop-count",
    "seon.eval/outcome",
    "seon.eval.drive/absent",
    "seon.eval.drive/absent-error",
    "seon.eval.drive/evaluation",
    "seon.eval.drive/outcome",
    "seon.eval.drive/run-cap",
    "seon.eval.drive/run-ids",
    "seon.eval.drive/terminal-state",
    "seon.eval.drive/value",
    "seon.listen/pattern",
    "seon.maintenance/entries",
    "seon.maintenance/entry",
    "seon.maintenance/error-facts",
    "seon.maintenance/fact-map",
    "seon.maintenance/receipt-facts",
    "seon.maintenance/report",
    "seon.maintenance/result-entity-request",
    "seon.maintenance/result-entity-response",
    "seon.maintenance/result-facts",
    "seon.maintenance.receipt/receipt",
    "seon.maintenance.request/entity",
    "seon.maintenance.request/value",
    "seon.maintenance.result/cluster-cleanup-collection-component",
    "seon.maintenance.result/cluster-cleanup-component",
    "seon.maintenance.result/collect-branch",
    "seon.maintenance.result/collect-component",
    "seon.maintenance.result/entity",
    "seon.maintenance.result/process-census-claim-error",
    "seon.maintenance.result/process-census-component",
    "seon.maintenance.result/process-census-identity",
    "seon.maintenance.result/process-census-process",
    "seon.maintenance.result/process-census-root",
    "seon.maintenance.result/reap-component",
    "seon.maintenance.result/reap-refusal",
    "seon.maintenance.result/reap-root",
    "seon.maintenance.result/reap-stopped-process",
    "seon.maintenance.result/value",
    "seon.message/ambiguous-about",
    "seon.message/ambiguous-about-error",
    "seon.message/blank-content",
    "seon.message/blank-content-error",
    "seon.message/chain-limit",
    "seon.message/chain-limit-error",
    "seon.message/content-too-large",
    "seon.message/content-too-large-error",
    "seon.message/delivery",
    "seon.message/delivery-request",
    "seon.message/inbound",
    "seon.message/inbound-content",
    "seon.message/inbound-request",
    "seon.message/inbox-unit",
    "seon.message/limit",
    "seon.message/message",
    "seon.message/no-limit",
    "seon.message/no-limit-error",
    "seon.message/pulled",
    "seon.message/pulled-reference",
    "seon.message/reply-request",
    "seon.message/size",
    "seon.message/unknown-about",
    "seon.message/unknown-about-error",
    "seon.message/unknown-recipient",
    "seon.message/unknown-recipient-error",
    "seon.print/bound-by",
    "seon.print/elision",
    "seon.print/elision-base",
    "seon.print/elision-request",
    "seon.print/elision-unit",
    "seon.print/face",
    "seon.print/identity-attributes",
    "seon.print/namespace-maps?",
    "seon.print/node",
    "seon.print/node-child",
    "seon.print/node-face",
    "seon.print/omitted",
    "seon.print/options",
    "seon.print/ordered?",
    "seon.print/prefix",
    "seon.print/reference",
    "seon.print/references",
    "seon.print/requery-form",
    "seon.print/requery-id",
    "seon.print/requery-refusal",
    "seon.print/result",
    "seon.print/sink",
    "seon.print/table?",
    "seon.print/text",
    "seon.print/unknown-face",
    "seon.print/unknown-face-error",
    "seon.print/width",
    "seon.render/acquired-context",
    "seon.render/ambiguous",
    "seon.render/ambiguous-error",
    "seon.render/cache",
    "seon.render/call-request",
    "seon.render/candidate-request",
    "seon.render/candidates",
    "seon.render/context-action",
    "seon.render/context-change-request",
    "seon.render/context-change-result",
    "seon.render/context-request",
    "seon.render/distance",
    "seon.render/failure",
    "seon.render/failure-request",
    "seon.render/hiccup",
    "seon.render/invalid-output",
    "seon.render/invalid-output-error",
    "seon.render/namespace",
    "seon.render/output",
    "seon.render/output-schema",
    "seon.render/package",
    "seon.render/page",
    "seon.render/profile",
    "seon.render/rendered",
    "seon.render/rendering",
    "seon.render/selection",
    "seon.render/selection-candidate",
    "seon.render/selection-candidate-reason",
    "seon.render/selection-candidate-status",
    "seon.render/selection-request",
    "seon.render/selection-stage",
    "seon.render/selection-stage-name",
    "seon.render/selection-stage-status",
    "seon.render/source",
    "seon.render/surface-id",
    "seon.render/unit",
    "seon.render/unknown",
    "seon.render/unknown-reason",
    "seon.render/unknown-request",
    "seon.render/value",
    "seon.render/walk-failed",
    "seon.render/walk-failed-error",
    "seon.render/would-fall-to-floor?",
    "seon.render.cost/fact",
    "seon.render.data/attribute-offset",
    "seon.render.data/complete?",
    "seon.render.data/continuation",
    "seon.render.data/cursor",
    "seon.render.data/datoms",
    "seon.render.data/direction",
    "seon.render.data/eid",
    "seon.render.data/identities",
    "seon.render.data/identities-complete?",
    "seon.render.data/incoming",
    "seon.render.data/incoming-cursor",
    "seon.render.data/index-cursor",
    "seon.render.data/limit",
    "seon.render.data/max-ref-attributes",
    "seon.render.data/max-result-weight",
    "seon.render.data/next-offset",
    "seon.render.data/no-such-path",
    "seon.render.data/no-such-path-error",
    "seon.render.data/observation",
    "seon.render.data/observation-request",
    "seon.render.data/offset",
    "seon.render.data/outgoing",
    "seon.render.data/outgoing-cursor",
    "seon.render.data/page",
    "seon.render.data/path",
    "seon.render.data/ref-attributes-probed",
    "seon.render.data/snapshot",
    "seon.render.data/subject",
    "seon.render.data/total",
    "seon.render.data/window",
    "seon.render.debug/details?",
    "seon.render.hiccup/tag",
    "seon.render.hiccup/unparseable-tag",
    "seon.render.hiccup/unparseable-tag-error",
    "seon.render.lint/balance",
    "seon.render.lint/characters",
    "seon.render.lint/classes",
    "seon.render.lint/counts",
    "seon.render.lint/defect",
    "seon.render.lint/detail",
    "seon.render.lint/duplicate-node-floor",
    "seon.render.lint/excerpt",
    "seon.render.lint/fence-tags",
    "seon.render.lint/finding",
    "seon.render.lint/findings",
    "seon.render.lint/floors",
    "seon.render.lint/id",
    "seon.render.lint/id-request",
    "seon.render.lint/nodes",
    "seon.render.lint/path",
    "seon.render.lint/placeholder-classes",
    "seon.render.lint/region",
    "seon.render.lint/region-absent",
    "seon.render.lint/render-request",
    "seon.render.lint/repeats",
    "seon.render.lint/report",
    "seon.render.lint/request",
    "seon.render.lint/required-regions",
    "seon.render.lint/soup-character-floor",
    "seon.render.lint/subject",
    "seon.render.lint/tag",
    "seon.render.lint/unclosed",
    "seon.render.lint/unexpected-close",
    "seon.render.lint/unterminated-string",
    "seon.render.package/base-revision",
    "seon.render.package/basis-transaction",
    "seon.render.package/delta",
    "seon.render.package/frame",
    "seon.render.package/keyframe",
    "seon.render.package/revision",
    "seon.render.package/size",
    "seon.render.package/streaming?",
    "seon.render.profile/composition",
    "seon.render.profile/id",
    "seon.render.profile/max-children",
    "seon.render.profile/max-depth",
    "seon.render.profile/max-string-length",
    "seon.render.profile/profile",
    "seon.render.profile/token-budget",
    "seon.render.transcript/entries",
    "seon.render.transcript/history",
    "seon.render.transcript/history-request",
    "seon.render.transcript/run",
    "seon.render.transcript/runs",
    "seon.render.value/artifact",
    "seon.render.value/html",
    "seon.render.value/missing-root-identity",
    "seon.render.value/missing-root-identity-error",
    "seon.render.value/options",
    "seon.render.value/projection",
    "seon.render.value/text",
    "seon.render.value/tree",
    "seon.render.value/truncated?",
    "seon.render.value/window-failed",
    "seon.render.value/window-failed-error",
    "seon.render.value/window-realization-failed",
    "seon.render.value/window-realization-failed-error",
    "seon.render.walk/acquisition-request",
    "seon.render.walk/attribute",
    "seon.render.walk/back-reference?",
    "seon.render.walk/branch",
    "seon.render.walk/connection",
    "seon.render.walk/connections-failed",
    "seon.render.walk/connections-failed-error",
    "seon.render.walk/elided",
    "seon.render.walk/elided-error",
    "seon.render.walk/found-depth",
    "seon.render.walk/history-request",
    "seon.render.walk/lookup",
    "seon.render.walk/no-such-entity",
    "seon.render.walk/no-such-entity-error",
    "seon.render.walk/path",
    "seon.render.walk/request",
    "seon.render.walk/target",
    "seon.render.walk/unit",
    "seon.render.walk/units",
    "seon.render.web/feed-request",
    "seon.render.web/function-unavailable",
    "seon.render.web/function-unavailable-error",
    "seon.render.web/http-server",
    "seon.render.web/inbound",
    "seon.render.web/interest",
    "seon.render.web/invalid-context-action",
    "seon.render.web/invalid-context-action-error",
    "seon.render.web/latest-packages",
    "seon.render.web/live-processes-unavailable",
    "seon.render.web/live-processes-unavailable-error",
    "seon.render.web/missing-port",
    "seon.render.web/missing-port-error",
    "seon.render.web/owner-not-ensured",
    "seon.render.web/owner-not-ensured-error",
    "seon.render.web/page-request",
    "seon.render.web/pages-mult",
    "seon.render.web/paint-request",
    "seon.render.web/port",
    "seon.render.web/preview-unavailable",
    "seon.render.web/preview-unavailable-error",
    "seon.render.web/prospective-context-unavailable",
    "seon.render.web/prospective-context-unavailable-error",
    "seon.render.web/registration",
    "seon.render.web/root-agent-id",
    "seon.render.web/server",
    "seon.render.web/service",
    "seon.render.web/url",
    "seon.render.web/value-not-found",
    "seon.render.web/value-not-found-error",
    "seon.render.web/value-unreadable",
    "seon.render.web/value-unreadable-error",
    "seon.render.web/view",
    "seon.render.web/wanted-port",
    "seon.runtime/entity",
    "seon.schedule/channel",
    "seon.schedule/execution-context",
    "seon.schedule/execution-handle",
    "seon.schedule/fire-count",
    "seon.schedule/incomplete-task",
    "seon.schedule/incomplete-task-error",
    "seon.schedule/invalid-fire-id",
    "seon.schedule/invalid-fire-id-error",
    "seon.schedule/invalid-task-owner",
    "seon.schedule/invalid-task-owner-error",
    "seon.schedule/invalid-terminal-arm",
    "seon.schedule/invalid-terminal-arm-error",
    "seon.schedule/missing-execution-handle",
    "seon.schedule/missing-execution-handle-error",
    "seon.schedule/missing-receipt",
    "seon.schedule/missing-receipt-error",
    "seon.schedule/nominal-request",
    "seon.schedule/proc-request",
    "seon.schedule/reference-at",
    "seon.schedule/schedule",
    "seon.schedule/unresolved-handler",
    "seon.schedule/unresolved-handler-error",
    "seon.schedule.fire/fire",
    "seon.schedule.fire/request",
    "seon.schedule.task/task",
    "seon.turn/basis-t",
    "seon.turn/changes",
    "seon.turn/compaction-request",
    "seon.turn/evaluation-facts-request",
    "seon.turn/form",
    "seon.turn/forms",
    "seon.turn/generated-form-request",
    "seon.turn/generated-run-request",
    "seon.turn/missing-opening-datom",
    "seon.turn/missing-opening-datom-error",
    "seon.turn/missing-results",
    "seon.turn/record-evaluated-call-request",
    "seon.turn/record-evaluated-request",
    "seon.turn/refused",
    "seon.turn/refused-error",
    "seon.turn/rule",
    "seon.turn/status",
    "seon.turn/system-request",
    "seon.turn/system-result",
    "seon.turn/system-run-request",
    "seon.turn/text",
    "seon.turn/transition",
    "seon.turn/turn",
    "seon.turn/turns-remaining",
    "seon.turn/virtual-request",
    "seon.turn/write?",
    "seon.turn.loop/admitted-form",
    "seon.turn.loop/cluster",
    "seon.turn.loop/commit-outcome",
    "seon.turn.loop/completion",
    "seon.turn.loop/evaluate-sources-request",
    "seon.turn.loop/evaluated-source",
    "seon.turn.loop/evaluated-sources",
    "seon.turn.loop/evaluation",
    "seon.turn.loop/evaluation-settlement",
    "seon.turn.loop/failure-settlement",
    "seon.turn.loop/forms-run",
    "seon.turn.loop/lint-rejected",
    "seon.turn.loop/lint-rejected-error",
    "seon.turn.loop/now",
    "seon.turn.loop/outcome",
    "seon.turn.loop/phase",
    "seon.turn.loop/phase-failed",
    "seon.turn.loop/phase-failed-error",
    "seon.turn.loop/preview",
    "seon.turn.loop/preview-sources-request",
    "seon.turn.loop/prompt-failed",
    "seon.turn.loop/prompt-failed-error",
    "seon.turn.loop/settle-evaluation-request",
    "seon.turn.loop/settle-failure-request",
    "seon.turn.loop/settle-request",
    "seon.turn.loop/settlement",
    "seon.turn.loop/terminal-refusal-settlement-refused",
    "seon.turn.loop/terminal-refusal-settlement-refused-error",
    "seon.turn.loop/terminal-request",
    "seon.turn.loop/turn-report",
    "seon.turn.loop/turn-request",
    "seon.turn.work/agent-request",
    "seon.turn.work/answered?",
    "seon.turn.work/attributes",
    "seon.turn.work/episode-runs",
    "seon.turn.work/form-settlement",
    "seon.turn.work/form-state",
    "seon.turn.work/forms",
    "seon.turn.work/next",
    "seon.turn.work/now",
    "seon.turn.work/plan-settlement",
    "seon.turn.work/settled?",
    "seon.turn.work/wake-request",
    "seon.wake/attribute",
    "seon.wake/t",
    "seon.wake/unanswered"
  ]
}
```

## Appendix B — reproducible read-only probes

P2 (basis 536877309) established the message/plan rows and rough fault/test
link census. Its exploratory `seon.test/id` and `seon.test/basis-t` spellings
were rejected; P4 uses the actual schema. P3 (536877341), P4 (536877364), P5
(536877386), P6 (536877420), and P7 (536877430) are the successful bounded
forms below. They install no Vars and transact no domain data.

Production versions must propagate `:seon.error` before boolean/sequence
conversion, use fully namespaced request/result keys, validate subject
presence, and return query-work refusals. The audit harness maps below use
short local labels solely to make exact observed outputs readable.

### P2 — subjects

```clojure
(let [db @(seon.operator/connection "default")]
 {:basis (seon.db/basis-t db)
 :agents (seon.db/q '[:find (pull ?a [:seon.agent/id {:seon.agent/namespace [:seon.ns/name]}]) :where [?a :seon.agent/id]] db)
 :messages (seon.db/q '[:find (pull ?m [:seon.message/id {:seon.message/from [:seon.agent/id]} {:seon.message/to [:seon.agent/id]} {:seon.message/about [:seon.message/id :seon.error/id]} {:seon.message/inbox [:seon.agent/id]} :seon.message/read-tx]) :where [?m :seon.message/id]] db)
 :plans (seon.db/q '[:find (pull ?s [:my.plan.item/id :my.plan.item/title :my.plan.item/done-when :my.plan.item/completed-tx :my.plan.item/done-query :my.plan.item/subject]) :where [?s :my.plan.item/id]] db)
 :links (into {} (for [a [:seon.error/id :seon.error/run :seon.error/op :seon.error/steward :seon.test/id :seon.test/subject :seon.test/basis-t :seon.test/ns :seon.test/fail-count :seon.test/error-count :seon.fn/ns :seon.fn/calls]]
 [a (seon.db/q '[:find (count ?e) . :in $ ?a :where [?e ?a _]] db a)]))})
```

Returned value (JSON encoding; elapsed 8243 ms):

```json
{
  "basis": 536877309,
  "agents": [
    [
      {
        "seon.agent/id": "juniper",
        "seon.agent/namespace": {
          "seon.ns/name": "my.agents.juniper"
        }
      }
    ],
    [
      {
        "seon.agent/id": "root",
        "seon.agent/namespace": {
          "seon.ns/name": "my.agents.root"
        }
      }
    ]
  ],
  "messages": [
    [
      {
        "seon.message/id": "efeb4a20",
        "seon.message/read-tx": {
          "db/id": 536871316
        },
        "seon.message/to": {
          "seon.agent/id": "root"
        }
      }
    ],
    [
      {
        "seon.message/id": "db990b21",
        "seon.message/to": {
          "seon.agent/id": "root"
        },
        "seon.message/about": {
          "seon.error/id": "2062ff31-2b92-41a5-8a5e-b82efcc859be"
        },
        "seon.message/inbox": {
          "seon.agent/id": "root"
        }
      }
    ],
    [
      {
        "seon.message/id": "bd2a404c",
        "seon.message/to": {
          "seon.agent/id": "root"
        },
        "seon.message/about": {
          "seon.error/id": "ced5a4dc-a75e-4754-86f2-e45ecf9ce13b"
        },
        "seon.message/inbox": {
          "seon.agent/id": "root"
        }
      }
    ],
    [
      {
        "seon.message/id": "29e9b929",
        "seon.message/read-tx": {
          "db/id": 536871883
        },
        "seon.message/to": {
          "seon.agent/id": "root"
        },
        "seon.message/about": {
          "seon.error/id": "f0ccb5ad-405e-4cb5-950f-af45eb771f47"
        }
      }
    ],
    [
      {
        "seon.message/id": "1dc0c83c",
        "seon.message/to": {
          "seon.agent/id": "root"
        },
        "seon.message/about": {
          "seon.error/id": "943e339e-1305-44df-818b-55587709abc6"
        },
        "seon.message/inbox": {
          "seon.agent/id": "root"
        }
      }
    ],
    [
      {
        "seon.message/id": "924727ec",
        "seon.message/read-tx": {
          "db/id": 536870952
        },
        "seon.message/to": {
          "seon.agent/id": "root"
        }
      }
    ],
    [
      {
        "seon.message/id": "24065c9f",
        "seon.message/read-tx": {
          "db/id": 536870986
        },
        "seon.message/to": {
          "seon.agent/id": "root"
        },
        "seon.message/about": {
          "seon.error/id": "7ec5ffc9-a329-44b1-9b0b-9ce722352f4f"
        }
      }
    ],
    [
      {
        "seon.message/id": "aa1a135089bf",
        "seon.message/read-tx": {
          "db/id": 536871703
        },
        "seon.message/from": {
          "seon.agent/id": "root"
        },
        "seon.message/to": {
          "seon.agent/id": "root"
        }
      }
    ],
    [
      {
        "seon.message/id": "b6913af3",
        "seon.message/to": {
          "seon.agent/id": "juniper"
        },
        "seon.message/about": {
          "seon.error/id": "7a062173-c8ad-4d90-9113-cd3bccf9767f"
        },
        "seon.message/inbox": {
          "seon.agent/id": "juniper"
        }
      }
    ],
    [
      {
        "seon.message/id": "e10231f6",
        "seon.message/read-tx": {
          "db/id": 536875976
        },
        "seon.message/from": {
          "seon.agent/id": "root"
        },
        "seon.message/to": {
          "seon.agent/id": "juniper"
        }
      }
    ],
    [
      {
        "seon.message/id": "41cd18a9",
        "seon.message/to": {
          "seon.agent/id": "root"
        },
        "seon.message/about": {
          "seon.error/id": "736ee072-eadf-4c52-90bb-0cda278b3b1a"
        },
        "seon.message/inbox": {
          "seon.agent/id": "root"
        }
      }
    ],
    [
      {
        "seon.message/id": "e2e16de8",
        "seon.message/read-tx": {
          "db/id": 536872929
        },
        "seon.message/to": {
          "seon.agent/id": "root"
        },
        "seon.message/about": {
          "seon.error/id": "40f65538-23a5-49fe-aba7-378732fa3424"
        }
      }
    ],
    [
      {
        "seon.message/id": "9e868e11",
        "seon.message/to": {
          "seon.agent/id": "root"
        },
        "seon.message/about": {
          "seon.error/id": "ca45b636-df75-4330-a720-13032f55bcf1"
        },
        "seon.message/inbox": {
          "seon.agent/id": "root"
        }
      }
    ],
    [
      {
        "seon.message/id": "e6696059",
        "seon.message/to": {
          "seon.agent/id": "root"
        },
        "seon.message/about": {
          "seon.error/id": "c2e8f44d-f0da-4d81-8710-23f3545511dd"
        },
        "seon.message/inbox": {
          "seon.agent/id": "root"
        }
      }
    ],
    [
      {
        "seon.message/id": "80c32318",
        "seon.message/read-tx": {
          "db/id": 536871746
        },
        "seon.message/to": {
          "seon.agent/id": "root"
        },
        "seon.message/about": {
          "seon.error/id": "c2e22a98-a02f-45b5-b32a-24fbe666f12c"
        }
      }
    ]
  ],
  "plans": [
    [
      {
        "my.plan.item/id": "juniper/again",
        "my.plan.item/title": "Run it again and record the new total",
        "my.plan.item/done-when": "A query finds the :my.note entity's :my.note/content carrying the customer and both totals, with the new total verified by calling largest-customer on freshly queried orders.",
        "my.plan.item/completed-tx": {
          "db/id": 536877049
        }
      }
    ],
    [
      {
        "my.plan.item/id": "juniper/read",
        "my.plan.item/title": "Read the orders",
        "my.plan.item/done-when": "A query over :example/order entities has returned their :example/order ids, :example/customer values, and :example/amount values.",
        "my.plan.item/completed-tx": {
          "db/id": 536876005
        }
      }
    ],
    [
      {
        "my.plan.item/id": "juniper/define",
        "my.plan.item/title": "Define `largest-customer` with a `:malli/schema` contract (rows → `{:customer :total}`)",
        "my.plan.item/done-when": "A query finds the :seon.fn row for largest-customer with :seon.fn/spec present.",
        "my.plan.item/completed-tx": {
          "db/id": 536876512
        }
      }
    ],
    [
      {
        "my.plan.item/id": "juniper/test",
        "my.plan.item/title": "Write a `deftest` over the fixture data and run it",
        "my.plan.item/done-when": "After (my.test/run), a query finds the :seon.test row's last result with a positive :seon.test/pass-count, zero :seon.test/fail-count, and zero :seon.test/error-count.",
        "my.plan.item/completed-tx": {
          "db/id": 536876642
        }
      }
    ],
    [
      {
        "my.plan.item/id": "juniper/report",
        "my.plan.item/title": "Report and finish",
        "my.plan.item/done-when": "A query finds the :seon.message from Juniper to root containing the customer and both verified totals; after (my.agent/done), session state shows the session closed.",
        "my.plan.item/completed-tx": {
          "db/id": 536877055
        }
      }
    ],
    [
      {
        "my.plan.item/id": "juniper/add",
        "my.plan.item/title": "Add an order of 40 for that customer",
        "my.plan.item/done-when": "A query finds the new :example/order entity with that :example/customer and :example/amount 40.",
        "my.plan.item/completed-tx": {
          "db/id": 536876892
        }
      }
    ],
    [
      {
        "my.plan.item/id": "juniper/save",
        "my.plan.item/title": "Run it and save the answer",
        "my.plan.item/done-when": "A query finds a :my.note entity linked to Juniper whose :my.note/content records the customer and original total returned by largest-customer.",
        "my.plan.item/completed-tx": {
          "db/id": 536876727
        }
      }
    ]
  ],
  "links": {
    "seon.error/run": 1,
    "seon.test/id": {
      "seon.db/invalid-read": true,
      "seon.error/message": "seon.db/q cannot read uninstalled attribute :seon.test/id.",
      "seon.error/data": {
        "seon.error/diagnostic-layer": "database-read",
        "seon.error/diagnostic-operation": "seon.db/q",
        "seon.error/diagnostic-member": "seon.test/id",
        "seon.error/diagnostic-expected": {
          "seon.db/installed-declaration": "seon.error/unknown",
          "seon.db/registered-candidates": [
            "seon.test/error-count",
            "seon.test/fail-count",
            "seon.test/failing-assertions",
            "seon.test/failure-message",
            "seon.test/ns",
            "seon.test/pass-count",
            "seon.test/pending-subject",
            "seon.test/run-at",
            "seon.test/run-basis-t",
            "seon.test/source",
            "seon.test/subject",
            "seon.test/sym"
          ]
        },
        "seon.error/diagnostic-offending": [
          "?e",
          "?a",
          {
            "seon.sci.admit/type": "datalog.parser.type.Placeholder"
          }
        ],
        "seon.error/diagnostic-cause": "seon.db/attribute-not-installed",
        "seon.error/diagnostic-evidence-availability": "seon.error/known",
        "seon.error/diagnostic-evidence": {
          "seon.db/attribute": "seon.test/id",
          "seon.db/installed-declaration": null,
          "seon.db/registered-candidates": [
            "seon.test/error-count",
            "seon.test/fail-count",
            "seon.test/failing-assertions",
            "seon.test/failure-message",
            "seon.test/ns",
            "seon.test/pass-count",
            "seon.test/pending-subject",
            "seon.test/run-at",
            "seon.test/run-basis-t",
            "seon.test/source",
            "seon.test/subject",
            "seon.test/sym"
          ]
        }
      },
      "seon.error/kind": "seon.db/invalid-read"
    },
    "seon.error/op": 3390,
    "seon.test/fail-count": 1,
    "seon.error/id": 3448,
    "seon.fn/ns": 4608,
    "seon.test/subject": null,
    "seon.error/steward": null,
    "seon.test/ns": 1599,
    "seon.fn/calls": 5328,
    "seon.test/error-count": 1,
    "seon.test/basis-t": {
      "seon.db/invalid-read": true,
      "seon.error/message": "seon.db/q cannot read uninstalled attribute :seon.test/basis-t.",
      "seon.error/data": {
        "seon.error/diagnostic-layer": "database-read",
        "seon.error/diagnostic-operation": "seon.db/q",
        "seon.error/diagnostic-member": "seon.test/basis-t",
        "seon.error/diagnostic-expected": {
          "seon.db/installed-declaration": "seon.error/unknown",
          "seon.db/registered-candidates": [
            "seon.test/error-count",
            "seon.test/fail-count",
            "seon.test/failing-assertions",
            "seon.test/failure-message",
            "seon.test/ns",
            "seon.test/pass-count",
            "seon.test/pending-subject",
            "seon.test/run-at",
            "seon.test/run-basis-t",
            "seon.test/source",
            "seon.test/subject",
            "seon.test/sym"
          ]
        },
        "seon.error/diagnostic-offending": [
          "?e",
          "?a",
          {
            "seon.sci.admit/type": "datalog.parser.type.Placeholder"
          }
        ],
        "seon.error/diagnostic-cause": "seon.db/attribute-not-installed",
        "seon.error/diagnostic-evidence-availability": "seon.error/known",
        "seon.error/diagnostic-evidence": {
          "seon.db/attribute": "seon.test/basis-t",
          "seon.db/installed-declaration": null,
          "seon.db/registered-candidates": [
            "seon.test/error-count",
            "seon.test/fail-count",
            "seon.test/failing-assertions",
            "seon.test/failure-message",
            "seon.test/ns",
            "seon.test/pass-count",
            "seon.test/pending-subject",
            "seon.test/run-at",
            "seon.test/run-basis-t",
            "seon.test/source",
            "seon.test/subject",
            "seon.test/sym"
          ]
        }
      },
      "seon.error/kind": "seon.db/invalid-read"
    }
  }
}
```

### P3 — detectors

```clojure
(let [db @(seon.operator/connection "default")
 unanswered '[:find ?id ?sender ?recipient :where
 [?m :seon.message/id ?id] [?m :seon.message/from ?a] [?a :seon.agent/id ?sender]
 [?m :seon.message/to ?b] [?b :seon.agent/id ?recipient]
 (not-join [?m ?a ?b] [?reply :seon.message/about ?m] [?reply :seon.message/from ?b] [?reply :seon.message/to ?a])]
 open-step '[:find ?id ?criterion :where [?s :my.plan.item/id ?id]
 [?s :my.plan.item/done-when ?criterion] (not [?s :my.plan.item/completed-tx _])]
 answered '[:find ?reply . :in $ ?id :where [?m :seon.message/id ?id]
 [?m :seon.message/from ?a] [?m :seon.message/to ?b]
 [?reply :seon.message/about ?m] [?reply :seon.message/from ?b] [?reply :seon.message/to ?a]
 [?m :seon.message/read-tx _] (not [?m :seon.message/inbox _])]]
 {:basis (seon.db/basis-t db)
 :unanswered (seon.db/q unanswered db)
 :open-done-when (seon.db/q open-step db)
 :unverified-completions (seon.db/q '[:find ?id :where [?s :my.plan.item/id ?id] [?s :my.plan.item/completed-tx _] (not [?s :my.plan.item/done-query _])] db)
 :message-success (boolean (seon.db/q answered db "e10231f6"))
 :juniper-to-root (seon.db/q '[:find ?id :where [?a :seon.agent/id "juniper"] [?b :seon.agent/id "root"] [?m :seon.message/from ?a] [?m :seon.message/to ?b] [?m :seon.message/id ?id]] db)
 :work (mapv (fn [id] {:agent id :turns-left (seon.turn/turns-left db id)
 :episode-runs (seon.turn/episode-runs db id)
 :next (seon.turn/next-agent-work db {:seon.agent/id id})
 :deferred (seon.turn/deferred-triggers db id) :wakes (seon.turn/unanswered-triggers db id)}) ["root" "juniper"])})
```

Returned value (JSON encoding; elapsed 12444 ms):

```json
{
  "basis": 536877341,
  "unanswered": [
    [
      "aa1a135089bf",
      "root",
      "root"
    ],
    [
      "e10231f6",
      "root",
      "juniper"
    ]
  ],
  "open-done-when": [],
  "unverified-completions": [
    [
      "juniper/report"
    ],
    [
      "juniper/read"
    ],
    [
      "juniper/save"
    ],
    [
      "juniper/add"
    ],
    [
      "juniper/again"
    ],
    [
      "juniper/test"
    ],
    [
      "juniper/define"
    ]
  ],
  "message-success": false,
  "juniper-to-root": [],
  "work": [
    {
      "agent": "root",
      "turns-left": 100,
      "episode-runs": 0,
      "next": null,
      "deferred": [],
      "wakes": []
    },
    {
      "agent": "juniper",
      "turns-left": 0,
      "episode-runs": 30,
      "next": null,
      "deferred": [
        {
          "seon.message/id": "b6913af3"
        }
      ],
      "wakes": [
        {
          "seon.message/id": "b6913af3"
        }
      ]
    }
  ]
}
```

### P4 — detail

```clojure
(let [db @(seon.operator/connection "default")]
 {:basis (seon.db/basis-t db)
 :test (seon.db/pull db '[:seon.test/sym :seon.test/run-basis-t :seon.test/pass-count :seon.test/fail-count :seon.test/error-count :seon.test/subject {:seon.fn/calls [:seon.fn/sym]} {:seon.test/ns [:seon.ns/name]}] [:seon.test/sym "my.agents.juniper/largest-customer-test"])
 :fn (seon.db/pull db '[:seon.fn/sym :seon.schema.admission/source {:seon.fn/ns [:seon.ns/name]} {:seon.fn/_calls [:seon.fn/sym :seon.test/sym]}] [:seon.fn/sym "my.agents.juniper/largest-customer"])
 :renderers (seon.db/q '[:find ?renderer (count ?e) :where [?e :seon.eval/renderer ?renderer]] db)
 :orphan-captures (seon.db/q '[:find (count ?c) . :where [?c :seon.context.capture/id _] (not [?c :seon.context.capture/run _])] db)})
```

Returned value (JSON encoding; elapsed 6735 ms):

```json
{
  "basis": 536877364,
  "test": {
    "seon.test/sym": "my.agents.juniper/largest-customer-test",
    "seon.test/run-basis-t": 536876445,
    "seon.test/pass-count": 2,
    "seon.test/fail-count": 0,
    "seon.test/error-count": 0,
    "seon.fn/calls": [
      {
        "seon.fn/sym": "clojure.core/="
      },
      {
        "seon.fn/sym": "clojure.test/is"
      }
    ],
    "seon.test/ns": {
      "seon.ns/name": "my.agents.juniper"
    }
  },
  "fn": {
    "seon.fn/sym": "my.agents.juniper/largest-customer",
    "seon.schema.admission/source": "agent",
    "seon.fn/ns": {
      "seon.ns/name": "my.agents.juniper"
    }
  },
  "renderers": [
    [
      "seon.repl/render-directory-ai",
      24
    ],
    [
      "seon.plan/format-plan-ai",
      4
    ],
    [
      "seon.error/render-ai",
      20
    ],
    [
      "seon.plan/render-item-ai",
      10
    ],
    [
      "seon.bootstrap/render-help-ai",
      4
    ],
    [
      "seon.problems/stale-var-ai",
      1
    ],
    [
      "seon.test.accretion/render-ai",
      2
    ]
  ],
  "orphan-captures": 65
}
```

### P5 — cost

```clojure
(let [db @(seon.operator/connection "default")
 rows (seon.db/q '[:find ?eid ?agent-id ?namespace-name ?renderer ?shown
 :where [?e :seon.eval/renderer ?renderer] [?e :seon.eval/shown ?shown]
 [?e :seon.cluster.eval/id ?eid] [?e :seon.cluster.eval/run ?turn]
 [?turn :seon.turn/agent ?agent] [?agent :seon.agent/id ?agent-id]
 [(str ?renderer) ?fn-name] [?fn :seon.fn/sym ?fn-name]
 [?fn :seon.fn/ns ?ns] [?ns :seon.ns/name ?namespace-name]] db)
 median (fn [xs] (let [v (vec (sort xs)) n (count v)] (when (pos? n) (if (odd? n) (nth v (quot n 2)) (/ (+ (nth v (dec (quot n 2))) (nth v (quot n 2))) 2.0)))))
 groups (mapv (fn [[[a n] samples]] {:agent a :namespace n :samples (count samples)
 :mean (/ (reduce + (map #(seon.ai.tokens/estimate (nth % 4)) samples)) (double (count samples)))})
 (group-by #(vector (nth % 1) (nth % 2)) rows))
 comparisons (mapv (fn [[n xs]] {:namespace n :peer-count (count xs) :agents xs
 :median (median (map :mean xs)) :above (if (> (count xs) 1) (mapv :agent (filter #(> (:mean %) (median (map :mean xs))) xs)) :insufficient-peers)}) (group-by :namespace groups))
 shown (seon.db/q '[:find ?id ?text :where [?e :seon.cluster.eval/id ?id] [?e :seon.eval/shown ?text]] db)]
 {:basis (seon.db/basis-t db) :cost-comparisons comparisons
 :elision-attributes (seon.db/q '[:find [?a ...] :in $ [?a ...] :where [_ :db/ident ?a]] db [:seon.print/omitted :seon.print/elision-unit :seon.print/requery-form :seon.print/bound-by])
 :lexical-shown-marker-count (count (filter #(.contains ^String (second %) ":seon.print/omitted") shown))
 :shown-count (count shown)})
```

Returned value (JSON encoding; elapsed 8435 ms):

```json
{
  "basis": 536877386,
  "cost-comparisons": [
    {
      "namespace": "seon.bootstrap",
      "peer-count": 2,
      "agents": [
        {
          "agent": "juniper",
          "namespace": "seon.bootstrap",
          "samples": 1,
          "mean": 922
        },
        {
          "agent": "root",
          "namespace": "seon.bootstrap",
          "samples": 3,
          "mean": 882.6666666666666
        }
      ],
      "median": 902.3333333333333,
      "above": [
        "juniper"
      ]
    },
    {
      "namespace": "seon.error",
      "peer-count": 2,
      "agents": [
        {
          "agent": "root",
          "namespace": "seon.error",
          "samples": 18,
          "mean": 141.27777777777777
        },
        {
          "agent": "juniper",
          "namespace": "seon.error",
          "samples": 2,
          "mean": 119.5
        }
      ],
      "median": 130.38888888888889,
      "above": [
        "root"
      ]
    },
    {
      "namespace": "seon.repl",
      "peer-count": 2,
      "agents": [
        {
          "agent": "juniper",
          "namespace": "seon.repl",
          "samples": 3,
          "mean": 243.33333333333334
        },
        {
          "agent": "root",
          "namespace": "seon.repl",
          "samples": 21,
          "mean": 158.71428571428572
        }
      ],
      "median": 201.02380952380952,
      "above": [
        "juniper"
      ]
    },
    {
      "namespace": "seon.test.accretion",
      "peer-count": 1,
      "agents": [
        {
          "agent": "juniper",
          "namespace": "seon.test.accretion",
          "samples": 2,
          "mean": 247
        }
      ],
      "median": 247,
      "above": "insufficient-peers"
    },
    {
      "namespace": "seon.plan",
      "peer-count": 2,
      "agents": [
        {
          "agent": "root",
          "namespace": "seon.plan",
          "samples": 2,
          "mean": 53
        },
        {
          "agent": "juniper",
          "namespace": "seon.plan",
          "samples": 12,
          "mean": 96.5
        }
      ],
      "median": 74.75,
      "above": [
        "juniper"
      ]
    },
    {
      "namespace": "seon.problems",
      "peer-count": 1,
      "agents": [
        {
          "agent": "root",
          "namespace": "seon.problems",
          "samples": 1,
          "mean": 41
        }
      ],
      "median": 41,
      "above": "insufficient-peers"
    }
  ],
  "elision-attributes": [],
  "lexical-shown-marker-count": 21,
  "shown-count": 396
}
```

### P6 — fault

```clojure
(let [db @(seon.operator/connection "default")]
 {:basis (seon.db/basis-t db)
 :faults (seon.db/q '[:find (pull ?e [:seon.error/id :seon.error/op :seon.instrument/fn {:seon.error/run [:seon.turn/id {:seon.turn/agent [:seon.agent/id]}]}]) :where [?e :seon.error/run _]] db)
 :test-source (seon.db/pull db [:seon.test/source :seon.test/pending-subject] [:seon.test/sym "my.agents.juniper/largest-customer-test"])
 :stalled-provider-turns (seon.db/q '[:find ?id ?agent :where [?t :seon.turn/id ?id] [?t :seon.turn/agent ?a] [?a :seon.agent/id ?agent] [?t :seon.turn/closed-tx _] [?t :seon.turn/attempts ?attempt] [?attempt :seon.ai.attempt/error _] (not [?t :seon.turn/reply-size _])] db)
 :post-basis-success (boolean (seon.db/q '[:find ?e . :in $ ?agent-id ?basis :where [?a :seon.agent/id ?agent-id] [?t :seon.turn/agent ?a] [?t :seon.turn/id _ ?tx] [(> ?tx ?basis)] [?t :seon.turn/closed-tx _] [?e :seon.cluster.eval/run ?t] [?e :seon.cluster.eval/author :agent] [?e :seon.eval/shown _] (not [?e :seon.cluster.eval/error _])] db "juniper" 536877341))})
```

Returned value (JSON encoding; elapsed 12981 ms):

```json
{
  "basis": 536877420,
  "faults": [
    [
      {
        "seon.error/id": "7ec5ffc9-a329-44b1-9b0b-9ce722352f4f",
        "seon.error/op": "seon.agent/turn-completion-backstop",
        "seon.instrument/fn": "seon.turn/turn-completion-backstop-failure",
        "seon.error/run": {
          "seon.turn/id": "958adc16c4b1",
          "seon.turn/agent": {
            "seon.agent/id": "root"
          }
        }
      }
    ]
  ],
  "test-source": {
    "seon.test/source": "(deftest largest-customer-test\n  (is (= {:customer \"Ada\" :total 115}\n         (largest-customer example-rows)))\n  (is (= {:customer \"Bea\" :total 100}\n         (largest-customer [{:example/order \"b1\" :example/customer \"Bea\" :example/amount 100}]))))"
  },
  "stalled-provider-turns": [],
  "post-basis-success": false
}
```

### P7 — renderRequest

```clojure
(let [db @(seon.operator/connection "default")
 request {:seon.db/db db :seon.agent/id "juniper" :seon.turn/id "62febc6305ee"
 :seon.render/value {:seon.agent/id "juniper"} :seon.render/distance 1
 :seon.render/profile {:seon.render.profile/id :seon.render.profile/agent :seon.render.profile/token-budget 1000 :seon.render.profile/max-depth 8 :seon.render.profile/max-children 32 :seon.render.profile/composition :multiline}
 :seon.render/viewer 'my.agents.root :seon.render/subject [:seon.agent/id "juniper"]
 :seon.render/detail :test :seon.render/basis (seon.db/basis-t db) :seon.render/window [0 (seon.db/basis-t db)]}]
 {:basis (seon.db/basis-t db)
 :forwarded (vec (sort (keys (#'seon.render/render-argument request))))
 :reaching-tests (seon.fn/tests-reaching db "my.agents.juniper/largest-customer")})
```

Returned value (JSON encoding; elapsed 13197 ms):

```json
{
  "basis": 536877430,
  "forwarded": [
    "seon.agent/id",
    "seon.db/db",
    "seon.render/distance",
    "seon.render/profile",
    "seon.render/value",
    "seon.render.data/total",
    "seon.turn/id"
  ],
  "reaching-tests": []
}
```

### P8 — context selectors

The four selectors above were pulled against actual subjects. All returned
present rows without a typed refusal at basis 536877542. This verifies pull
shape/availability; it does not prove every nested subject has a declared
pair or that browser paint occurred. The combined read took 28,990 ms under
the 60,000 ms MCP bound; production task context should select its evidence
window before pulling the complete turn history.

```clojure
(let [db @(seon.operator/connection "default")
 selectors {:message '[:db/id :seon.message/id :seon.message/content :seon.message/read-tx
 {:seon.message/inbox [:seon.agent/id]}
 {:seon.message/from
  [:seon.agent/id {:seon.agent/namespace [:seon.ns/name]}
   {:seon.agent/plan
    [:my.plan/objective {:my.plan/steps
      [:my.plan.item/id :my.plan.item/title :my.plan.item/done-when
       :my.plan.item/completed-tx]}]}]}
 {:seon.message/to [:seon.agent/id {:seon.agent/namespace [:seon.ns/name]}]}
 {:seon.message/about [:db/id :seon.message/id :seon.error/id :my.plan.item/id]}
 {:seon.message/caused-by [:seon.message/id :seon.message/content]}
 {:seon.message/_about
  [:seon.message/id :seon.message/content
   {:seon.message/from [:seon.agent/id]} {:seon.message/to [:seon.agent/id]}]}] :plan '[:db/id :my.plan.item/id :my.plan.item/title :my.plan.item/description
 :my.plan.item/done-when :my.plan.item/done-query :my.plan.item/position
 {:my.plan.item/subject [:db/id]}
 {:my.plan.item/completed-tx [:db/txInstant]}
 {:my.plan.item/needs [:my.plan.item/id :my.plan.item/title
                       :my.plan.item/completed-tx]}
 {:my.plan.item/steps 8}] :session ';; not clipping a stored prompt or the HTML after it is rendered.
[:seon.agent/id {:seon.agent/namespace [:seon.ns/name]}
 {:seon.agent/settings [:seon.config.run/max-episode-runs
                        :seon.config.eval/time-limit-ms]}
 {:seon.agent/plan [:my.plan/objective {:my.plan/steps
   [:my.plan.item/id :my.plan.item/done-when :my.plan.item/done-query
    :my.plan.item/completed-tx]}]}
 {:seon.agent/runtime
  [{:seon.runtime/trigger [:seon.message/id :seon.message/content]}
   {:seon.runtime/listens [:seon.listen/attribute :seon.listen/entity :seon.listen/value]}
   {:seon.runtime/turns
    [:seon.turn/id :seon.turn/disposition :seon.turn.work/situation
     {:seon.turn/opened-tx [:db/txInstant]} {:seon.turn/closed-tx [:db/txInstant]}
     {:seon.turn/attempts [:seon.ai.attempt/id :seon.ai.attempt/usage-edn
                          {:seon.ai.attempt/error [:seon.error/id :seon.error/message]}]}
     {:seon.cluster.eval/_run [:seon.cluster.eval/id :seon.cluster.eval/source
       :seon.cluster.eval/author :seon.eval/shown :seon.eval/duration-ms
       :seon.cluster.eval/error :seon.cluster.eval/interrupted-at]}
     {:seon.context.capture/_run [:seon.context.capture/id
                                  :seon.context.capture/basis-t]}]}]}] :evaluation '[:seon.cluster.eval/id :seon.cluster.eval/source :seon.cluster.eval/author
 :seon.eval/shown :seon.eval/renderer :seon.cluster.eval/error
 :seon.cluster.eval/read-basis-transaction
 {:seon.cluster.eval/read-evidence [*]}
 {:seon.cluster.eval/run
  [:seon.turn/id {:seon.turn/agent [:seon.agent/id]}
   {:seon.context.capture/_run
    [:seon.context.capture/id :seon.context.capture/basis-t
     :seon.context.capture/prompt]}]}]}
 evaluation (seon.db/q '[:find ?e . :where [?e :seon.cluster.eval/id _] [?e :seon.eval/shown _]] db)
 subjects {:message [:seon.message/id "e10231f6"] :plan [:my.plan.item/id "juniper/report"] :session [:seon.agent/id "juniper"] :evaluation evaluation}]
 {:basis (seon.db/basis-t db)
  :selectors (mapv (fn [[k selector]] (let [row (seon.db/pull db selector (get subjects k))]
                  {:selector k :subject (get subjects k) :refusal (:seon.error/kind row) :keys (vec (sort (keys row))) :present (boolean (seq row))})) selectors)})
```

Returned value (JSON encoding):

```json
{
  "basis": 536877542,
  "selectors": [
    {
      "selector": "message",
      "subject": [
        "seon.message/id",
        "e10231f6"
      ],
      "refusal": null,
      "keys": [
        "db/id",
        "seon.message/content",
        "seon.message/from",
        "seon.message/id",
        "seon.message/read-tx",
        "seon.message/to"
      ],
      "present": true
    },
    {
      "selector": "plan",
      "subject": [
        "my.plan.item/id",
        "juniper/report"
      ],
      "refusal": null,
      "keys": [
        "db/id",
        "my.plan.item/completed-tx",
        "my.plan.item/done-when",
        "my.plan.item/id",
        "my.plan.item/needs",
        "my.plan.item/position",
        "my.plan.item/title"
      ],
      "present": true
    },
    {
      "selector": "session",
      "subject": [
        "seon.agent/id",
        "juniper"
      ],
      "refusal": null,
      "keys": [
        "seon.agent/id",
        "seon.agent/namespace",
        "seon.agent/plan",
        "seon.agent/runtime",
        "seon.agent/settings"
      ],
      "present": true
    },
    {
      "selector": "evaluation",
      "subject": 37455,
      "refusal": null,
      "keys": [
        "seon.cluster.eval/author",
        "seon.cluster.eval/error",
        "seon.cluster.eval/id",
        "seon.cluster.eval/read-basis-transaction",
        "seon.cluster.eval/run",
        "seon.cluster.eval/source",
        "seon.eval/shown"
      ],
      "present": true
    }
  ]
}
```
