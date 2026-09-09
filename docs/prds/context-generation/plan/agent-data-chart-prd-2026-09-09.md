---
type: prd
status: working
tags: [prd, agent-record, render, context-generation]
---

# The agent's data, item by item — storage, names, components, renders

Working PRD, iterated with the owner (2026-09-09). Inventory is grounded in
the live schema of `default` on the ninth refork (pre-`:seon.agent` rename;
attribute list at the end). Companion to the turn PRD §0, §4, §13–§18.

Six questions per item, from the owner:

1. Store it?
2. Better name?
3. Component (rendered together) or separate?
4. What matters most to the agent and to a person?
5. The AI projection: comments and forms that introduce it and show usage.
6. The HTML projection: what a person cares about, expressed well.

The AI examples below are the bytes as the model would see them; the
comment is the agent's own thinking voice; every form is one the agent
could type. `<…>` marks a derived value.

---

## 1. Identity — `:seon.agent/id`, `:seon.agent/namespace`

| | |
|---|---|
| store | yes; two scalars, the only ones on the record |
| names | landed: `:seon.agent/id`, `:seon.agent/namespace` (rename `3f07beb88`) |
| component | no; scalars many things need alone; rendered by the record's own pair |
| matters | who am I, where do my forms evaluate, who is responsible for my namespace |

AI

```clojure
;; Who am I, and where do my forms run?
my.agents.juniper=> (my.agent/identity)
#:seon.repl{:value {:id "juniper", :namespace my.agents.juniper, :steward "juniper"}}
```

HTML: name, namespace as a link to the namespace page, steward; one line.
Nothing else.

## 2. Plan — component `:seon.agent/plan` → `:my.plan/objective`, `:my.plan/steps*`, `:my.plan/current-step`; items `:my.plan.item/*`

| | |
|---|---|
| store | yes; the plan IS the instructions |
| names | `:my.plan.item/expected-result` → `:done-when`; `:my.plan.item/description` keep; `:about` (subject ref) keep; `:position` → derived from order, not stored (open question) |
| component | yes, one entity; rendered together, always |
| matters | agent: which step is current and what done means; person: objective, progress, the tree with states and dependencies |

AI (one form; `:state` derived: current / open / blocked / done)

```clojure
;; What am I working on? The current step's :done-when is my instruction.
my.agents.juniper=> (my.plan/items)
#:seon.repl{:value [{:id "orders/largest-customer", :title "Find the customer with the largest order total", :state :current,
                     :done-when "I know which :example/customer has the greatest sum of :example/amount"}
                    {:id "orders/add", :title "Add an order of 40 for that customer", :state :open, :needs ["orders/largest-customer"]}
                    {:id "orders/reply", :title "Tell root the new total", :state :open, :needs ["orders/add"]}]}
```

Writes teach themselves by returning the changed item:
`(my.plan/complete! "orders/largest-customer")`, `(my.plan/add! {:title … :done-when …})`,
`(my.plan/update! {:id … :title …})`, `(my.plan/current! "id")`.

HTML: objective as the heading; "current: <title>" with its done-when
quoted; "n of m done"; then the steps as a list with state badges and
"waits for …" links; descriptions collapsed. No entity ids anywhere.

## 3. Settings — component `:seon.agent/settings` (the `:seon.config.*` overlay) + derived `:turns-left`

| | |
|---|---|
| store | yes, the overrides only; defaults are config facts |
| names | fine; the component name is the concern |
| component | yes; rendered together |
| matters | agent: turns left, time limit, whether a provider is on; person: the overrides and the effective values side by side |

AI

```clojure
;; My settings: only what differs from the defaults, and how many turns I have.
my.agents.juniper=> (my.agent/settings)
#:seon.repl{:value {:seon.config.ai/no-provider true, :seon.config.eval/time-limit-ms 2500, :turns-left 3}}
```

Never the attribute-name dump; `(dir my.agent)` is where that lives.

HTML: two columns, override / effective, one row per dial that differs;
turns left as a number with the bound.

## 4. Messages, inbound — `:seon.cluster.message/*` where `to` = me

| | |
|---|---|
| store | yes; the wake fact and the content |
| names | `:seon.cluster.message/*` → `:seon.message/*` (same family move as the agent); `:my.message/reason` folds in; `:caused-by` keep (threading); `:ordinal` → derived from `at` (open question) |
| component | no; a message is its own entity, a declared reverse concern on the agent |
| matters | agent: what was I sent, by whom, what do they want; person: the thread |

AI

```clojure
;; Anything in my inbox I need to respond to?
my.agents.juniper=> (my.message/inbox)
#:seon.repl{:value [{:from "root", :at #inst "2026-09-09T11:00:00Z", :id "root/orders",
                     :content "Which customer has the largest order total? Add an order of 40 for them and tell me the new total."}]}
```

Unread only, oldest first; `[]` when empty (the form still teaches).
Answered messages leave the inbox because the reply names them
(`:caused-by`).

HTML: a thread: from, time, content; replies indented under what they
answer; unread marked.

## 5. Messages, outbound — same entity, `from` = me

| | |
|---|---|
| store | yes (it is the same fact) |
| component | no |
| matters | agent: that it sent, and to whom (the returned value); person: the thread |

AI: not a block; the write returns the fact:

```clojure
;; Root asked for the new total; I'll tell them.
my.agents.juniper=> (my.message/send {:to "root" :content "Alice now totals 220." :about "root/orders"})
#:seon.repl{:value {:id "juniper/…", :to "root", :at #inst "…"}}
```

## 6. Turns — `:seon.turn/id|agent|opened-at|closed-at|reply|attempts|trigger|starting-ns`

| | |
|---|---|
| store | yes: id, agent, opened, closed, reply (+blob/size), attempts, trigger. DELETE: `plan-digest`, `supersedes`, `undisposed-at`, `background-results` (retired designs still in the schema), `error` (a fault is a fact of its own) |
| names | `:seon.turn/*` landed; `reply` → keep |
| component | attempts are a component of the turn; the turn is a concern of the agent |
| matters | agent: nothing directly — it sees evaluations, not turns; person: when, why (trigger), how many attempts, the reply |

AI: no block. Turns are invisible to the agent except as the grouping of
its history (§7) and `:turns-left` (§3).

HTML: the history grouped by turn: opened → trigger → attempts → reply →
evaluations; a turn header line with duration.

## 7. Evaluations — `:seon.eval/*` (turn, ordinal, comment, source, ns, ending-ns, shown text, out, error, duration, read evidence)

| | |
|---|---|
| store | yes; this IS the history; shown text stored, the live object never |
| names | landed family; `:seon.eval/value` (string) → `:shown` is the honest name (it is the shown text, not the value) |
| component | ordered under the turn; rendered as the REPL transcript |
| matters | agent: the exact transcript, unchanged bytes; person: the same, readable, with evidence behind a toggle |

AI: the transcript itself, one entry per evaluation, in turn order:

```clojure
;; <the agent's own comment>
my.agents.juniper=> <source>
#:seon.repl{:value <shown>, :out "…", :error …, :result result/e…, :ms 9}
```

Nothing else is ever in the prompt.

HTML: the same entries, monospace, comment above, error highlighted,
evidence and basis behind "evidence".

## 8. Provider attempts — `:seon.ai.attempt/*` (at, ordinal, finish-reason, usage, reasoning, sent-body, error, failover, settings)

| | |
|---|---|
| store | yes but bounded: usage, finish reason, error, ordinal; `sent-body` NEVER (prompts are generated, not stored — owner 09-07); `reasoning` as a blob aged by size; `settings-edn` → a ref to the settings component's state, not a copy |
| names | fine |
| component | component of the turn |
| matters | agent: nothing; person: cost, why it stopped, what failed, reasoning on demand |

AI: none. HTML: per turn, a small line: model, tokens in/out, finish reason,
duration; reasoning expandable.

## 9. Faults — `:seon.error/*` with `agent` (happened to) and `steward` (routed to)

| | |
|---|---|
| store | yes, with provenance; `data-edn/data-blob` bounded (landed) |
| names | fine; `:seon.error/run` → `:seon.error/turn` (rename leftover) |
| component | no; a concern of the agent it happened to and of the steward |
| matters | agent: what failed, in which function, with what message — so it can fix or route around; person: the same, plus signature grouping and counts |

AI (only when there are any; the block is the flat error value)

```clojure
;; Something failed in my namespace; I should read it before doing more.
my.agents.juniper=> (my.agent/faults)
#:seon.repl{:value [{:kind :seon.ai/no-credential, :message "The environment variable OPENROUTER_API_KEY is not set.", :at #inst "…", :in seon.ai/attempt}]}
```

HTML: cards: kind, message, when, function, turn link; repeats collapsed
by signature with a count.

## 10. Notes — `:my.note/*` (id, agent, about, content)

| | |
|---|---|
| store | yes |
| names | fine |
| component | no; a concern |
| matters | agent: its own notes about a subject; person: the notes |

AI: no block by default (not needed to orient); `(my.note/notes)` when
the agent wants them. HTML: list, newest first, subject links.

## 11. Namespace — `:seon.ns/*` (name, doc, requires, aliases, steward) + the program rows it owns (`:seon.fn`, `:seon.schema`, `:seon.test`)

| | |
|---|---|
| store | yes (program facts) |
| names | fine |
| component | no; the agent's namespace is a scalar ref; its contents are program rows |
| matters | agent: what I have already defined here, what data I declared; person: the namespace page |

AI (derived: a definitions line when any exist; the data query when
schema keys are declared)

```clojure
;; What have I already defined here?
my.agents.juniper=> (dir my.agents.juniper)
#:seon.repl{:value [{:sym largest, :arglists ([rows]), :doc "Return the row with the greatest :example/amount."}]}
;; What data is in my namespace?
my.agents.juniper=> (seon.db/q '[:find (count ?e) . :where [?e :example/amount]])
#:seon.repl{:value 6}
```

HTML: the namespace page: functions with first docstring lines, schemas,
tests with last results.

## 12. Wakes — `:seon.wake/listen|opens-turn?|inside` on attributes; answered derived by `:t`

| | |
|---|---|
| store | the DECLARATIONS on the schema, yes; the wake itself is the datom — nothing extra stored |
| names | fine |
| component | not data on the agent |
| matters | agent: nothing to see (it sees the message / fault); person: "why did this turn open" |

AI: none. HTML: the turn header's trigger line ("woke on message
root/orders").

## 13. Retired or to delete (still in the live schema)

`:seon.turn/plan-digest`, `supersedes`, `undisposed-at`,
`background-results`, `error`; `:seon.ai.attempt/sent-body`;
`:seon.eval/missing`, `:seon.eval/size` (admission caps retired with
result serialization); `:my.plan.item/position` if order derives;
`:seon.cluster.message/ordinal` if `at` orders. Each deletion is a schema
reset; batch them.

---

## Root — connected to every agent, without the explosion

Root is an agent like any other (id, namespace, plan, settings, messages,
history) with one extra concern: **the agents it supervises**. That concern
renders as ONE line per agent, derived, never the agent's full context:

```clojure
;; How are my agents doing?
my.agents.root=> (my.agent/all)
#:seon.repl{:value [{:id "juniper", :namespace my.agents.juniper, :turns-left 3, :open-turn? false,
                     :unread 1, :faults 0, :current "Find the customer with the largest order total"}]}
```

Detail is on demand, one agent at a time, through the same functions with
an explicit id: `(my.plan/items {:agent "juniper"})`,
`(my.message/inbox {:agent "juniper"})`, `(my.agent/faults {:agent "juniper"})`.
Faults route to the steward, so root's own faults block already shows what
it is responsible for. Messages to root aggregate in its inbox like anyone
else's. Root's HTML page: its own record, then a table of agents (the same
one-line fields, each a link to the agent's page).

That is the whole difference: one derived block, and the `:agent`
argument on the ordinary functions. No separate root renderers.

## Juniper — the general agent

Exactly the blocks above in the §18 order: help, identity, plan, namespace
definitions and data (when any), inbox, settings, then history. Faults and
notes appear only when present. Nothing is special-cased.

---

## Open questions for the owner

1. `:my.plan.item/position` and `:seon.cluster.message/ordinal`: store, or
   derive order from `at`/tree order?
2. Attempts: keep `reasoning` at all (blob, aged), or drop?
3. Faults in the agent's own context: always a block when present, or only
   when routed to it as steward?
4. Notes: a default block or on demand?
5. Root's one-line summary fields: is `{:id :namespace :turns-left :open-turn? :unread :faults :current}` the right seven?

## Live attribute inventory (default, ninth refork, pre-rename)

my.message/reason; my.note/{about,agent,content,id}; my.plan.item/{about,agent,completed-at,description,expected-result,id,needs*,position,steps*comp,title}; my.plan/{current-step,objective,steps*comp}; seon.agent/{plan comp,settings comp}; seon.ai.attempt/{at,delay-ms,error,failover-from,finish-reason,id,ordinal,reasoning,reasoning-blob,reasoning-size,sent-body,settings-edn,truncation,usage-edn}; seon.cluster.message/{about,at,caused-by,content,from,id,ordinal,to}; seon.config.agent/turn-completion-backstop-ms; seon.error/{agent,at,basis-t,capped?,cid,class,data-blob,data-edn,data-size,dropped-fault-count,dropped-fault-digest,id,kind,message,op,proc,process,refusal,refusal-shape,run,signature,steward,throwable-class}; seon.eval/{duration-ms,missing,size,value}; seon.ns/{aliases*comp,doc,imports*comp,name,refers*comp,requires*,source,steward}; seon.turn/{agent,attempts*comp,background-results*,closed-at,error,id,opened-at,plan-digest,reply,reply-blob,reply-size,starting-ns,supersedes*,trigger,undisposed-at}; seon.wake/{inside,listen,opens-turn?}.
