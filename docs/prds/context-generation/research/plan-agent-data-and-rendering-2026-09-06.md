---
type: research
status: complete
tags: [research, agent, context, data-model]
---

# Agent-linked plan data and rendering

## Conclusion

The authored plan is already on the agent through ordinary database refs. Each
item carries `:my.plan.item/agent`; the agent may carry the optional
`:my.plan/anchor`. `:my.plan.item/parent` and `:my.plan.item/needs` connect plan
items. Adding a forward item collection to the agent would mirror a relation
Datahike already indexes and can traverse in reverse.

The smallest useful change is documentation at the existing schema and render
owners. The current plan render now introduces those stored refs, names
`seon.db/q`, `seon.db/pull`, and `seon.db/transact!` as the direct read/write
surface, and identifies ready, blocked, and completion sections as derived
current state. It adds no fact family, authored render form, or generic render
composition mechanism.

## Stored facts and derived values

| Value | Authority | Stored on the agent? |
|---|---|---|
| Agent identity and optional `:my.plan/anchor` | `resources/seon/schemas/my.plan.edn:49-55` | Yes |
| Item identity, content, agent, parent, needs, about, completion | `resources/seon/schemas/my.plan.item.edn:1-69` | No; each item is its own entity and points to the agent |
| Ready and blocked collections | `src/my/plan.clj:26-57,630-680` | No; Datalog derives them from current item facts |
| Current view sections | `src/my/plan.clj:838-883` | No; `plan` assembles a bounded value for the caller |

Datahike marks ref attributes as indexed
(`reference-code/datahike/src/datahike/db/utils.cljc:307-313`). Its pull
implementation reads a forward attribute through EAVT and a reverse attribute
through AVET (`reference-code/datahike/src/datahike/pull_api.cljc:368-390`). A
bounded pull rooted at an agent can therefore use
`{:my.plan.item/_agent [...]}` without maintaining a forward collection.

## Direct database operations and helper semantics

Plan items are ordinary database facts. The focused regression in commit
`1dd53a9e3` creates an item with a tempid and agent lookup ref, reverse-pulls it
from the agent, updates it through the item identity lookup ref, and observes
`my.plan/ready` derive the updated value. Its focused result was 76 assertions,
0 failures, and 0 errors (4 test assertions plus 72 fixture assertions).

The convenience functions add semantics beyond the schema and Datahike
transaction boundary:

| Function | Additional behavior |
|---|---|
| `add!` | Checks the agent and referenced items, item ownership, subject tokens, and optional anchor update before transacting (`src/my/plan.clj:248-263`). |
| `complete!` | Checks ownership, makes completion idempotent, and clears a matching anchor (`src/my/plan.clj:265-281`). |
| `plan!` | Resolves identities, checks ownership, dependencies, and subject tokens, preserves completed identities, diffs the whole authored tree, and fences the transaction at the observed basis (`src/my/plan.clj:293-624`). |

A direct `seon.db/transact!` receives Datahike and installed-schema guarantees,
but it does not execute those helper checks. Whether every raw plan edit must
obey the convenience semantics is an open design choice. This change documents
the distinction and does not move those checks into a new write path.

## Runtime evidence and limits

A read-only probe against the live default cluster confirmed the installed
identity and ref facets for the agent, anchor, item agent, parent, and needs
attributes. A bounded reverse pull was accepted and returned only the agent
identity in about 1 ms because that database contained no plan items. The first
JVM evaluation attempted an elided database call and correctly refused with
`:seon.config/missing-projection`; repeating the observation with explicit
database custody separated the evaluation surface limitation from the data
model result.

The current AI and HTML contracts require terminal strings and Hiccup. Generic
composition of a recursive database value with constructed query/update forms
crosses the render owner and remains unimplemented. This slice only makes the
existing plan projections truthful about the underlying data and the direct
database surface.

The executable primer experiment at
`thinking_primer_probe_2026_09_06.clj` uses that surface as an ordinary agent
reply: source comments explain the next step, and `seon.cluster.reply/sources`
retains those comments beside three ordered forms for namespace discovery,
batched function documentation, and current-plan rendering. The forms run in
one disposable turn fork. The experiment records each exact source and its
admitted result separately and never evaluates displayed or serialized result
text. It proves the current reader and evaluator path; it does not implement
production context composition or a separate thinking field.

One bounded live execution against the sovereign default cluster parsed
exactly three sources, all attributed to `my.agents.root`. In order, their
admitted results were the 12 public `my.plan` names, the two requested function
documentation rows, and the rendered empty current plan. The evaluations took
104.35 ms, 9.57 ms, and 101.28 ms; all three reported `capped? false` and no
error. The cluster still held its older published plan docstrings and heading,
which is expected: this probe exercises the live reply-reader and turn-fork
path and does not refork a sovereign cluster onto edited source.

The complete `plan-context-prd-2026-08-13.md` and the data-oriented Clojure,
data-modeling, Datahike, and REPL skills were read end to end before this
change. The implementation was checked against the pinned Datahike source
cited above rather than inferred from attribute names.
