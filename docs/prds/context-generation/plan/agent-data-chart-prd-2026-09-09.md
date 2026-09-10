---
type: prd
status: working
tags: [prd, render, agent-context]
---

# The agent's data, item by item — data first

Working PRD, iterated with the owner (2026-09-09, r2). Grounded in the live
schema of `default` (attribute inventory at the end), in REPL probes on
`default` this afternoon (`research/raw-data-forms-probe-2026-09-09.md`),
in the read-evidence / wake / evaluation-point map
(`src/seon/db.clj:335-781`, `src/seon/cluster/wake.clj:325-444`,
`src/seon/turn.clj:4230-4315`), and in two Haiku comprehension trials
(`scratchpad` trials, then the committed harness §18a).

## 0. The rulings this revision applies (owner, 2026-09-09)

1. **Data, not verbs.** The agent reads its record with `seon.db/pull` and
   `seon.db/q` and writes with `seon.db/transact!`. The generated context
   shows those forms, so every block is also a schema lesson. `my.*`
   shrinks to forms that carry a rule (`send`, `done`) and to `help`,
   `dir`, `doc`; each is source the agent can read.
2. **Time is the transaction.** No `:at`, `:completed-at`, `:read-at`
   instants are written by agents. A fact that "happened now" is a ref to
   the current transaction — `[:db/add e :my.plan.item/completed-tx
   "datomic.tx"]` — and the instant derives from that transaction's
   `:db/txInstant`. Proven on `default`: 2 datoms, derived instant exact.
   (The reply reader accepts only `#inst`/`#uuid`; `(java.util.Date.)` is
   not in the agent's SCI classes — `reader.cljc:24-30`, `eval.clj:227`.)
3. **Ids.** One entry: `(seon.id/id data)` — SHA-256 of `(pr-str data)`,
   truncated to the default length 12; `(seon.id/id data n)` chooses the
   length; `(seon.id/id)` mints a random event of the default length.
   Things that ARE their parts hash (evaluations 12, plan items by title); genuine events are random (messages, 8).
4. **Components are addressable.** A component the agent writes to
   carries an identity derived from its owner (`:my.plan/agent` as a unique
   identity ref), because a nested map under a cardinality-one component
   REPLACES the component SILENTLY — no retraction datom appears in the
   report and `retract-components` never runs (transaction.cljc:738-795;
   probe: new plan entity, old orphaned with its six steps). Upsert by
   identity is the only safe raw write, proven live with a temporary
   identity attribute (`research/raw-data-forms-probe-2026-09-09.md`).
   `transact!` refuses a nested identity-less map under a cardinality-one
   component ("this would replace the component; address it by identity")
   — §9.7.
5. **Every read block is emitted once at turn 0, even when empty.**
   `[]` teaches the form and establishes read evidence, so a later change
   re-emits exactly that block (§14). No block appears for the first time
   mid-history.
6. **Exact evidence needs pattern-only queries.** `q` gets exact evidence
   only when every `:where` clause is a pattern and `:find` has no `pull`
   (`db.clj:335-372`); `pull` is exact for explicit finite selectors
   (`db.clj:374-423`); `[*]`, recursion and `not`/`or` fall back to
   attribute-level evidence (correct, coarse). Required platform work:
   patterns inside `not`/`or` count as patterns; a `pull` in `:find` adds
   its pull patterns. Until then generated queries are pattern-only.
7. **Schema feedback at every seam** (§9 below).
8. **Steps order.** Cardinality-many is a set (hash order: 0,2,1,4,3,5 on
   `default`); `:my.plan.item/position` stays stored and the read sorts.

The AI examples below are the bytes as the model sees them; comments are
the agent's own thinking; every form is one it could type.

---

## 1. Identity — `:seon.agent/id`, `:seon.agent/namespace`

| store | yes; the two scalars |
|---|---|
| names | landed (`3f07beb88`) |
| component | no |
| matters | who am I, where my forms run, who stewards my namespace |

```clojure
;; Who am I, and where do my forms run?
my.agents.juniper=> (seon.db/pull '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}] [:seon.agent/id "juniper"])
#:seon.repl{:value #:seon.agent{:id "juniper", :namespace #:seon.ns{:name my.agents.juniper, :steward #:seon.agent{:id "juniper"}}}}
```

HTML: name, namespace link, steward. One line.

## 2. Plan — component `:seon.agent/plan`; identity `:my.plan/agent`; items `:my.plan.item/*`

| store | yes; the plan IS the instructions |
|---|---|
| names | `:my.plan.item/expected-result` → `:my.plan.item/done-when` (the wrapper invented `:done-when` without it existing — that projection layer goes); `:my.plan.item/completed-at` → `:completed-tx` (ref to the transaction); `:my.plan/agent` NEW unique identity ref; `position` stays |
| component | yes; addressable by `[:my.plan/agent [:seon.agent/id "juniper"]]` |
| matters | agent: current step and its done-when; person: objective, progress, the tree |

Read (one form; done = has `:completed-tx`; blocked = a `needs` item is not done; sorted):

```clojure
;; What am I working on? A step is done when it has :completed-tx; blocked while a step it :needs is not done.
my.agents.juniper=> (->> (seon.db/pull '[{:seon.agent/plan [:my.plan/objective {:my.plan/current-step [:my.plan.item/id]} {:my.plan/steps [:my.plan.item/id :my.plan.item/title :my.plan.item/done-when :my.plan.item/position {:my.plan.item/completed-tx [:db/txInstant]} {:my.plan.item/needs [:my.plan.item/id]}]}]}] [:seon.agent/id "juniper"]) :seon.agent/plan)
#:seon.repl{:value #:my.plan{:objective "Which customer has the largest total? …", :current-step #:my.plan.item{:id "juniper/query"},
  :steps [#:my.plan.item{:id "juniper/query", :title "Query the orders", :done-when "I have read the order ids, customers, and amounts.", :position 0}
          #:my.plan.item{:id "juniper/aggregate", :title "Find the customer with the largest total", :done-when "…", :position 1, :needs ["juniper/query"]}
          …]}}
```

The value renderer sorts a component set by `:position` when every member
carries one — a render rule, not a stored fact.

The plan block has one thinking comment: "My plan is my instructions; a
step is done when it has :completed-tx. (doc my.plan) shows how to add,
complete, and remove steps; ids are (seon.id/id title 8)." The write
examples live in the namespace docstring. ID-only `:my.plan.item/needs`
pulls show a vector of ids; a pull requesting more dependency attributes
keeps those attributes. The live result remains the pulled value.

Writes, as data:

```clojure
;; Query done: I read the orders. Mark the step complete with this transaction.
(seon.db/transact! [[:db/add [:my.plan.item/id "juniper/query"] :my.plan.item/completed-tx "datomic.tx"]])
;; Add a step to my plan (upsert into the existing plan by its identity).
(seon.db/transact! [{:my.plan/agent [:seon.agent/id "juniper"]
                     :my.plan/steps [{:my.plan.item/id "orders/verify" :my.plan.item/title "Verify the new total" :my.plan.item/done-when "…" :my.plan.item/position 6}]}])
;; Make a step current.
(seon.db/transact! [[:db/add [:my.plan/agent [:seon.agent/id "juniper"]] :my.plan/current-step [:my.plan.item/id "juniper/aggregate"]]])
;; Remove a step: retractEntity cascades to the item and its incoming plan ref;
;; a bare :db/retract of the ref would sever the edge and leave the item behind (transaction.cljc:1059).
(seon.db/transact! [[:db.fn/retractEntity [:my.plan.item/id "orders/verify"]]])
```

Haiku wrote the completion and add forms correctly from the help alone
(trial 3, 8/8 comprehension). `(doc my.plan)` shows exactly these.

HTML: objective heading; current step with its done-when; n of m done;
steps in position order with state badges and "waits for" links.

## 3. Settings — component `:seon.agent/settings`; identity `:seon.config/agent`

| store | overrides only; defaults are config facts |
|---|---|
| names | grouped by namespace: provider `:seon.config.ai/*`, retry `:seon.config.ai.retry/*`, evaluation `:seon.config.eval/*`, budget `:seon.config.run/*`; `:seon.agent/turns-left` DERIVED |
| component | yes; addressable by `[:seon.config/agent [:seon.agent/id "juniper"]]` |
| matters | turns left, time limit, which provider; a person: override vs effective |

```clojure
;; My settings: only what differs from the defaults, and how many turns I have.
my.agents.juniper=> (seon.db/pull '[{:seon.agent/settings [*]} :seon.agent/turns-left] [:seon.agent/id "juniper"])
#:seon.repl{:value #:seon.agent{:settings #:seon.config{:ai/no-provider true, :eval/time-limit-ms 2500}, :turns-left 3}}
;; Switch provider for my next turn.
(seon.db/transact! [{:seon.config/agent [:seon.agent/id "juniper"] :seon.config.ai/model "deepseek-v4-flash" :seon.config.ai/no-provider false}])
```

The write's returned transaction report shows the datoms that changed;
the settings block re-emits on the next system turn because its evidence
changed, so old and new both sit in the history. The turn loop reads
effective settings at turn open, so a provider change is live next turn.

HTML: override / effective columns per changed dial, grouped; turns left.

The effective AI read shows model, no-provider, evaluation time limit,
turn budget, retry dials, and turns left. Setting
`:seon.config.agent/show-all-settings` to true includes the full declared
agent settings; absence or false selects the compact view. HTML retains
every declared dial.

## 4. Runtime — ONE component `:seon.agent/runtime` (owner: "the runtime state should all be one collection")

| store | the open turn's id, opened-tx, trigger (the wake datom); the listens; turns taken this session (derived) |
|---|---|
| names | NEW: `:seon.agent/runtime` component; `:seon.runtime/agent` unique identity ref, `/turns` component many, `/trigger`, `/listens`; retire `:seon.turn/plan-digest`, `supersedes`, `undisposed-at`, `background-results`, `error` |
| component | yes; rendered together; the agent's turn is part of it |
| matters | agent: am I mid-turn, what woke me, what I listen for; person: state, trigger, latency |

```clojure
;; Where is my turn, what woke me, and what am I listening for?
my.agents.juniper=> (seon.db/pull '[{:seon.agent/runtime [{:seon.runtime/turns [:seon.turn/id {:seon.turn/opened-tx [:db/txInstant]}]} {:seon.runtime/trigger [*]} {:seon.runtime/listens [*]}]}] [:seon.agent/id "juniper"])
#:seon.repl{:value #:seon.agent{:runtime #:seon.runtime{:turns [#:seon.turn{:id "e1b2…", :opened-tx #:db{:txInstant #inst "…"}}], :trigger {:seon.message/inbox …}, :listens [{:seon.listen/attribute :seon.message/inbox} {:seon.listen/attribute :example/amount}]}}}
;; Wake me when any order amount changes.
(seon.db/transact! [{:seon.runtime/agent [:seon.agent/id "juniper"] :seon.runtime/listens [{:seon.listen/attribute :example/amount}]}])
```

A listen is an index pattern (attribute, optional entity, optional value)
— the same shape read evidence stores. Roadmap step 8 will make the cluster's existing listener union them with the schema-declared listened
attributes; the union is a map lookup per datom, computed outside the
per-datom loop. Not a new mechanism.

HTML: state line (idle / turn open since …, woke on …), listens as chips.

## 5. Messages — `:seon.message/*` (family move from `:seon.cluster.message`)

| store | yes |
|---|---|
| names | `:seon.message/id` (random 8), `/to` (listened), `/from`, `/content`, `/about` (the message answered), `/read-tx` (ref to the transaction that handled it). DELETE `/at` (derive from the creating transaction) and `/ordinal` (order by tx); `my.message/reason` folds into content |
| component | no; a concern |
| matters | agent: what I was sent and haven't handled; person: the thread |

```clojure
;; Anything I have not handled yet?
my.agents.juniper=> (seon.db/q '[:find [(pull ?m [:seon.message/id :seon.message/content {:seon.message/from [:seon.agent/id]}]) ...] :where [?m :seon.message/to [:seon.agent/id "juniper"]] (not [?m :seon.message/read-tx])])
#:seon.repl{:value [#:seon.message{:id "a83d335a", :content "Which customer …", :from #:seon.agent{:id "root"}}]}
;; Answer root; the same transaction marks the question handled.
(seon.db/transact! [{:seon.message/id "9f2c41ab" :seon.message/to [:seon.agent/id "root"] :seon.message/from [:seon.agent/id "juniper"]
                     :seon.message/content "Alice totals 220." :seon.message/about [:seon.message/id "a83d335a"]}
                    [:db/add [:seon.message/id "a83d335a"] :seon.message/read-tx "datomic.tx"]])
```

Transacting a map with `:to` IS sending: `:to` is the listened attribute.
`(my.message/send {…})` remains as the form that mints the id and writes
both facts; `(doc my.message/send)` shows the two entries above. The
inbox query needs the `not`-clause evidence fix (§0.6) to be exact;
until then it is attribute-exact.

HTML: a thread; replies under what they answer; unhandled marked.

## 6. History — `:seon.turn/*` + `:seon.eval/*`

| store | turns: id, agent, opened-tx, closed-tx, reply, attempts, trigger. Evaluations: ordinal, comment, source, ns, shown text, out, error, duration, read evidence |
|---|---|
| names | `:seon.eval/value` → `/shown` (it is shown text, not the value); `opened-at`/`closed-at` → tx refs |
| component | evaluations ordered under the turn; the turn a concern of the agent |
| matters | agent: the exact transcript; person: the same, grouped by turn, evidence behind a toggle |

AI: the transcript itself — one REPL entry per evaluation, bytes fixed at
evaluation time, never re-listed (turn PRD §14; the prompt is a fold over
rows in turn order). HTML: grouped by turn with trigger, attempts, reply.

## 7. Provider attempts — `:seon.ai.attempt/*` (component of the turn)

| store | ordinal, finish reason, usage, error, failover. NEVER `sent-body` (prompts are generated). `reasoning` OFF by default; a settings dial keeps it bounded when wanted |
|---|---|
| matters | person only: cost, why it stopped, what failed |

No AI block. HTML: one line per attempt under the turn.

## 8. Faults — `:seon.error/*` (`agent` = happened to; `steward` = routed to)

| store | yes, bounded (landed) |
|---|---|
| names | `:seon.error/run` → `/turn` |
| matters | agent: what failed, where, the flat error — flagged as its job; person: cards grouped by signature |

```clojure
;; Has anything in my namespace failed? Fixing it is my job before anything else.
my.agents.juniper=> (seon.db/q '[:find [(pull ?f [:seon.error/kind :seon.error/message :seon.instrument/fn]) ...] :where [?f :seon.error/steward [:seon.agent/id "juniper"]]])
#:seon.repl{:value []}
```

Emitted at turn 0 even when empty (§0.5); re-emits when a fault routes
to the agent, with the comment above as the explicit flag.

## 9. Schema feedback — where the schema shows itself (owner, 2026-09-09)

Today `seon.db/transact!` validates nothing beyond Datahike's own
unknown-attribute / value-type checks (`db.clj:2397`; no Malli pass).
Rulings:

1. **`transact!` validates before Datahike.** Every map and `:db/add`
   value in the transaction is checked against the projection's attribute
   forms; a map carrying an identity attribute is checked against its
   entity schema. A refusal is data: `{:seon.error/kind :seon.db/invalid-write,
   :seon.db/attribute k, :seon.schema/form <form>, :seon.db/offending v,
   :seon.db/path […]}` plus the entity form when one applies. The agent
   sees the schema in the same turn it broke it; no turn spent asking.
2. **Unknown attributes name candidates** — `q`/`pull` already do
   (`:seon.db/registered-candidates`); `transact!` does the same.
3. **Contract violations carry the function's doc map** (`:summary
   :body :example :in :out` with schema forms expanded), so an
   instrumentation error teaches the call.
4. **`(dir ns)`** returns `{:schemas {key form …} :functions [{:sym :in
   :out :doc}]}` — each schema once, functions naming theirs.
   **`(doc sym)`** returns the doc map. Docstring convention: line 1 the
   summary; body; a final `Example:` form.
5. **Declared schemas render.** When the agent's namespace declares
   schema keys, turn 0 emits `(dir my.agents.juniper)` showing them, and
   the data-count query over them.
6. **Help says it once**: "A mistake returns :error data with the schema
   you violated."
7. **Structural refusals with a sentence.** A nested identity-less map
   under a cardinality-one component (silent replacement); a `:db/retract`
   of a component ref (orphans the child — say `retractEntity`); an entity
   map missing a key its entity schema requires (a raw message without
   `:id`: valid datoms, invalid message — probe §6). Each refusal names
   the form to use instead.

## 10. Notes — `:my.note/*`

Stays as data the agent transacts directly (`{:my.note/agent … :my.note/content … :my.note/about …}`);
`(doc my.note)` shows the map. No block at turn 0 unless notes exist;
then the same pattern as faults.

## 11. Namespace — `:seon.ns/*` + program rows

```clojure
;; What have I already defined here, and what data did I declare?
my.agents.juniper=> (dir my.agents.juniper)
#:seon.repl{:value {:schemas {:example/order [:string {:seon.db/identity true}], :example/customer :string, :example/amount :int}, :functions []}}
my.agents.juniper=> (seon.db/q '[:find ?a (count ?e) :in $ [?a ...] :where [?e ?a _]] [:example/order :example/customer :example/amount])
#:seon.repl{:value [[:example/order 4] [:example/customer 4] [:example/amount 4]]}
```

Both derived from the schema rows; absent when none.

## 12. Root — connected to every agent, one derived block

```clojure
;; How are my agents doing?
my.agents.root=> (seon.db/q '[:find [(pull ?a [:seon.agent/id :seon.agent/turns-left {:seon.agent/runtime [{:seon.runtime/turns [:seon.turn/id]}]} {:seon.agent/plan [{:my.plan/current-step [:my.plan.item/title]}]}]) ...] :where [?a :seon.agent/id]])
```

plus derived per-agent measures in the same block, computed by the render
function from facts: unread messages, faults routed, last turn latency
(closed-tx minus opened-tx), evaluations and total ms this session,
provider tokens and cost from attempts, storage bytes (shown text + blobs),
scheduled tasks. Cluster-wide, one more form: JVM heap and threads, store
footprint, adopted commit, fault signatures with counts. CPU and memory
per agent are not separable in one JVM; latency, cost, bytes are.
Detail on demand with the same `pull`/`q` against any agent's record.

## 13. Retire / delete

`:seon.turn/plan-digest`, `supersedes`, `undisposed-at`,
`background-results`, `error`; `:seon.ai.attempt/sent-body`;
`:seon.eval/missing`, `/size`; `:seon.cluster.message/at`, `/ordinal`;
`:my.plan.item/completed-at` (→ `completed-tx`); `:my.plan.item/expected-result`
(→ `done-when`). One batched reset.

## 14a. Page and help rulings from the owner's review

Restored from `7ec3a5bbc`, with the owner's 2026-09-09 render-pass ruling:
every block is a form the agent could type, one thinking comment, and its
response. No example code padded into thinking comments. `(help)` prints
its declared lines bare; `(doc my.plan)` carries the plan write examples.
Settings show effective values grouped by namespace, subject to §3's dial.
The Turns concern shows only headers: opened, trigger, evaluation count,
and whether a reply is recorded. Reply source stays in Context now; it is
never repeated in the header. The history concern emits no duplicate AI
projection.

## 14. Trials so far

| trial | model | help | result |
|---|---|---|---|
| A/B/C, wrapper forms | Haiku ×3 | map / printed / vector | 7/7 comprehension each; replies wrote the prompt marker (A,B), completed a step early (B), invented Datalog (C) |
| C2, wrapper forms | Haiku | vector + 3 lines | 8/8; clean reply |
| paid, wrapper forms | deepseek-v4-flash | vector | 10/12; wrote the marker AND fabricated a `#:seon.repl` response → reader rule §18b |
| C3, raw data forms | Haiku | vector, data-first | 8/8; correct pull/q/transact/get-in; wrote `(now)` (my bad example) and completed a step early (line trimmed) |
| plan operations, live help + raw plan block | Haiku | live (16:30) | complete/current/retitle via `my.plan` guesses; chained `sort-by`+`map` unprompted; could NOT add (no shape shown), could NOT remove (no verb, `retractEntity` untaught), did not know the id rule — the plan block must teach its writes by example and the id rule must be visible |

## 16. Roadmap — from here to there (status 2026-09-09 17:45)

Each step is a lane slice: one commit, gated, RESET NEEDED where marked.
Status: ✅ landed · ▶ running · ⏭ next · ◻ queued.

| # | change | reset | status |
|---|---|---|---|
| 1 | `transact!` validates against the projection; refusal carries form, offending value, path, candidates; returned flat errors render through their AI pair | no | ✅ `26ec13420` `24953d294` `6f7c6faa3` |
| 1a | The live loop: duplicate graphs on concurrent arming; empty virtual reply as a fault; the fixture disarming the live agent; completion faults bounded by the evaluation limit | no | ✅ `e12ba7535` `2b6913f4b` (wake→settled 0.6–8 s, proven on default) |
| 1b | Transaction report as resolved changes; positioned components ordered; nested pull shapes; `dir` shows declared schemas; forms printed as agent source; faults to root / routed stewards; root's agents block; raw component reads at turn 0 even when empty; data-first help lines | no | ✅ cookbook lane, 11 commits `d6377ac39`…`778be4b94` |
| 1c | `(help)` as `#:seon.help{:lines}` with its own render pair (bare lines); turns concern HTML-only and "Context now" showing the turn | no | ✅ `9f9f432a8` `7e7c74f01` |
| 1d | REPL grammar prompt-first (§18d); settings effective values grouped; plan writes by example | no | ✅ `617e538f3` `fd8646edd` |
| 1f | The page reads turns from the runtime component ("Context now" shows the opening) | no | ✅ `49869fd90` |
| 1g | One plan comment, documented writes, dependency ids, actionable settings, and turn headers | no | ✅ [render pass](../research/render-pass-landing-2026-09-09.md) |
| 1e | Incremental publication writes complete rows (validation caught it) | no | ✅ `1e778e880` |
| 2 | Read evidence exact for `not`/`or` pattern clauses and `pull` in `:find` | no | ◻ evidence lane |
| 3 | `(seon.id/id data [n])` as the one id entry; message ids random 8; plan item ids from title | no | ✅ data lane ([landing](../research/data-lane-landing-2026-09-09.md)) |
| 4 | Time is the transaction: NEW `completed-tx`, `read-tx` refs; DELETE `completed-at`, message `at`, `ordinal` | yes | ✅ data lane ([landing](../research/data-lane-landing-2026-09-09.md)) |
| 5 | Addressable components: `:my.plan/agent`, `:seon.config/agent` identities; `expected-result` → `done-when` | yes (batch) | ✅ data lane ([landing](../research/data-lane-landing-2026-09-09.md)) |
| 6 | Messages `:seon.message/*`; the inbox as an edge `:seon.message/inbox` retracted when handled; `send` mints id and writes both facts | yes (batch) | ✅ data lane ([landing](../research/data-lane-landing-2026-09-09.md)) |
| 7 | Runtime component `:seon.agent/runtime` with the turns inside it; retire `plan-digest`, `supersedes`, `undisposed-at`, `background-results`, `error`; `:seon.eval/value` → `/shown`; `sent-body` gone; reasoning off | yes (batch) | ✅ data lane ([landing](../research/data-lane-landing-2026-09-09.md)) |
| 8 | Agent-declared listens union into the wake matcher | no | ◻ evidence lane |
| 9–12 | Block functions from the cookbook on the new shapes; `dir`/`doc` structure; `my.plan`/`my.note` as documented data | no | ◻ render lane (after 3–7) |
| 13 | Root's cluster block (JVM, store, commit, fault signatures) | no | ◻ root lane |
| 14 | Fixture on the new shapes; reseed; read the prompt; harness score | reseed | every landing; paid trial rerun after 1c |

Review points for the owner: after 1c (help and page on the current
shapes), after 3–7 (raw writes in the history on a fresh fixture), after
9–12 (harness score beside the previous one).

## 15. Open for the owner

1. Message ids random 8 (a message is an event), or hash of
   `[from to content]` (identical messages merge)?
2. Faults block at turn 0 for every agent, or only for stewards?
3. Root's summary fields: the list in §12?
4. `my.message/send` and `my.agent/done` remain as the only `my.*` writes;
   `my.plan`/`my.note` become `doc`-only namespaces (examples), no functions?

## Live attribute inventory (default, tenth refork)

seon.agent/{id, namespace, plan comp, settings comp}; my.plan/{objective, steps*comp, current-step}; my.plan.item/{id, title, expected-result, description, about, position, needs*, completed-at, steps*comp, agent}; seon.cluster.message/{id, to, from, content, about, caused-by, at, ordinal}; seon.turn/{id, agent, opened-at, closed-at, reply, reply-blob, reply-size, attempts*comp, trigger, starting-ns, plan-digest, supersedes*, undisposed-at, background-results*, error}; seon.eval/{value, missing, size, duration-ms}; seon.ai.attempt/{…}; seon.error/{…}; my.note/{id, agent, about, content}; seon.ns/{…}; seon.wake/{listen, opens-turn?, inside}.
