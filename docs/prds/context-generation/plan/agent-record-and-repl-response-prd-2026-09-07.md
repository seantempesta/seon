---
type: prd
status: SUPERSEDED
superseded-by: agent-record-and-turn-loop-prd-2026-09-07.md
tags: [prd, agent, context, repl, sci, datahike, run-loop, wake]
---

# The agent record and the REPL response

SUPERSEDED by [the turn-loop PRD](agent-record-and-turn-loop-prd-2026-09-07.md); the text below is historical evidence.

Historically the one design document (ruling 59a) for the agent's record, the REPL reply
the agent reads, the SCI context those two share, and the run loop that
produces them. It says what is added, what is refactored in place, and what
is deleted, on one platform. Nothing in it is deleted before this document
is accepted. It supersedes the presentation half of
[entity-debug-curation-prd-2026-09-06.md](entity-debug-curation-prd-2026-09-06.md)
and the text spelling of B2/B12/B13 in
[repl-first-behavior-2026-09-03.md](repl-first-behavior-2026-09-03.md).

Evidence lives in three dated notes, every claim with `file:line` and every
number measured on 2026-09-07:
[eval points and caches](../research/eval-points-and-caches-census-2026-09-07.md),
[cluster, branch, SCI, wake](../research/cluster-branch-sci-wake-model-2026-09-07.md),
[the run loop unpacked](../research/run-loop-unpacked-2026-09-07.md).

## 1. Why

Agents echo results instead of writing forms because their reply and the
REPL's echo are the same text grammar. The agent's record is attributes and
reverse refs nobody can read at a glance. The page and the prompt render
through different formatters. Turns are slow for one measured reason that has
nothing to do with the loop's shape. And faults reach agents by pretending to
be messages, which is the overloaded queue that produced a six-faults-in-1.5 s
storm. Each of these is a mechanism where a fact belongs.

## 2. The model in one page

- **A store holds branches; a cluster is one branch; a fork is a head-pointer
  copy** (note 2 §1). Every agent on the branch shares program rows, schema,
  config, and every other agent's entities. Per-agent facts are the agent's
  record: its defs and its evaluations (§2.2).
- **The SCI context is a function of facts.** The cluster's base context is
  acquired from program rows; each turn forks it copy-on-write and rehydrates
  that agent's `:seon.def` rows; the fork is discarded at turn end and only
  committed rows install back (note 2 §3). `result/eN` handles are bound in
  the fork from the agent's stored evaluations, so any evaluation is
  shareable: another agent's context can render it, and a fork can bind a
  handle to a stored value on demand (ruling 59c).
- **One eval point.** `seon.sci.reader/read` → `seon.sci.eval/evaluate` →
  settlement is already one path (note 1 §1, proven by a program-graph
  query). The debug page is the one bypass: `seon.render.web/render-source-call`
  forks a turn and evaluates during a render. That bypass goes.
- **One cache.** Identity is code + input (`[agent ns source producer]`);
  validity is read evidence (`seon.db/read-evidence-current?`, 1–4 µs). The
  database is not the cost: an agent's whole record pulls in 331 µs and its
  evaluations query in 104 µs (note 1 §4). Two render caches holding the
  same output collapse to one store plus pointers (note 1 §2.6).
- **Storage ≠ generation.** The stored unit is one evaluation entity per
  form. The transcript, the debug page's AI column, the agent's history, and
  the provider prompt are the same pure function over those entities.
  Compaction (ruling 58d) is retract evaluations and regenerate.
- **Wake sources are separate components** routed by their own attribute
  through the one Datahike listener; handled-ness is a claim ref from the
  handling run, never a retraction of the routed edge (note 2 §4.4, §4.4.6).

## 3. The agent record

Every key is deliberate. Lookup and plumbing keys carry no renderer and are
not units; every rendered key points at data that IS the thing (one hop), and
declares its `:seon.render/ai` and `:seon.render/html` functions on its
attribute schema. Attribute namespaces are owning code namespaces.

```clojure
{:seon.agent/id         "juniper"                 ; lookup identity
 :seon.agent/namespace  #ref my.agents.juniper    ; assigned working namespace, NOT unique; any agent may in-ns elsewhere
 ;; ── rendered keys ──
 :seon.agent/loop       {:seon.agent.loop/run      #ref run     ; present while a turn is open
                         :seon.agent.loop/process  "pid-start"  ; custody = presence
                         :seon.agent.loop/turns-left 3}         ; derived at render, shown here for the reader
 :seon.agent/inbox      #{message …}              ; component set; routed by :seon.message/to
 :seon.agent/faults     #{fault …}                ; component set; routed by :seon.error/to
 :seon.agent/schedule   #{firing …}               ; component set; routed by :seon.schedule.fire/to
 :seon.agent/plan       {:my.plan/objective … :my.plan/steps #{…} :my.plan/current-step #ref}
 :seon.agent/evals      #{evaluation …}           ; component set; the REPL transcript as data
 :seon.agent/sci        {:seon.agent.sci/ns my.agents.juniper   ; the session image
                         :seon.agent.sci/defs #{def …}}}
```

| Rendered key | What it is (stored) | Derived at render | AI source | HTML |
|---|---|---|---|---|
| identity (`id` + `namespace`, rendered from the entity's own scalars) | name, assigned namespace | namespaces it stewards (`:seon.ns/steward` reverse), created (tx instant), cluster (the branch) | `(seon.agent/whoami)` | identity card |
| `:seon.agent/loop` | current run ref, custody | situation (authorship, not a label), next form, turns left, deferred, what it is waiting on, the `my.run` commands | `(my.run/status {})` | state card |
| `:seon.agent/inbox` | messages delivered to me | unread = no claim ref | `(my.message/inbox {})` | Inbox (N) |
| `:seon.agent/faults` | faults for me: my escaped throwables, and faults in functions of the namespace I steward | unhandled = no claim ref; grouped by signature | `(my.faults/list {})` | Faults (N) |
| `:seon.agent/schedule` | timer firings for me | unhandled | `(my.schedule/due {})` | Schedule |
| `:seon.agent/plan` | objective, steps, current step | ready, blocked, completions | `(my.plan/plan {})` | tree + progress |
| `:seon.agent/evals` | one entity per evaluated form | history (group by run, newest first), result handles | `(my.repl/history {})` | transcript per run |
| `:seon.agent/sci` | current namespace, surviving defs | restorable partition, bound handles | `(dir my.agents.juniper)` | list |

Ruled names: `loop` (the vocabulary table's own word for the per-agent loop;
`runtime` means the environment there), `sci` (it is SCI data, owner 09-07).

**Namespace is not identity (owner, 2026-09-07).** Several agents may be
assigned one namespace (spin one up to handle an error without disturbing
another doing the user's work), and an agent is not confined to its
namespace: it may `in-ns` anywhere its REPL reaches. Stewardship is a fact
on the namespace entity, `:seon.ns/steward` (one agent per namespace): faults
in that namespace's functions, complaints, and feature requests from other
agents route to the steward. Agents message each other by `:seon.agent/id`.
The unique constraint on the agent's namespace attribute is deleted; the
`agent/owner-of` derivation becomes a read of `:seon.ns/steward`.
Deleted from the record: `cluster` (the branch), `instructions` (dead),
`run` pointer (the loop owns it), `:seon.render/units` (the rendered units are
the component-ref entries of the entity schema in declared order),
`:seon.render/form` (ruling 44), every reverse-ref unit.

**Handled-ness and wakes.** Each wake family has the same four parts, copied
from the working `:seon.effect/to` template (note 2 §4.4.1): a routed
attribute whose value is the recipient's entity id, asserted by the transition
that makes the item deliverable; a claim ref from the handling run
(`:seon.cluster.run/trigger` for messages, `:seon.cluster.run/faults`,
`:seon.cluster.run/firings`); an unhandled derivation by absence of the claim;
and the component on the agent. A retraction of a routed edge would itself
wake the agent (`route!` ignores the added flag), so nothing retracts one.
`next-agent-work` gains one arm: choose one unhandled item across faults,
inbox, schedule, background results, under the existing episode gate; a fault
is not an outside trigger. The steward derivation for a fault is
`:seon.instrument/fn` → `:seon.fn/ns` → `:seon.ns/steward`; it lands in two
steps because the fault seam does not yet record the failing function for
non-contract faults (note 2 §4.4.3 measured 0 of 14).

## 4. The REPL response

The agent writes comments and forms (unchanged, ruling 68's forgiving reader).
The REPL answers each form with one map the agent is never asked to write:

```
; the agent's comment, verbatim, above the prompt
my.agents.juniper=> (+ 1 1)
#:seon.repl{:value 2, :result result/e33866, :ms 3}

my.agents.juniper=> (in-ns 'my.tools)
#:seon.repl{:value #object[Namespace my.tools], :result result/e33884, :ns my.tools, :ms 0}

my.tools=> (println "hi")
#:seon.repl{:value nil, :result result/e33897, :out "hi\n", :ms 1}
```

- Keys in this order, absent when empty: `:seon.repl/value` or
  `:seon.repl/error` (exactly one), `:seon.repl/result` (the bound symbol —
  derived from the evaluation entity's identity, `result/e<entity-id>`, so
  handles never collide across runs; a valid symbol is the only constraint,
  owner 2026-09-07; an evaluation that never persisted has no handle),
  `:seon.repl/out`, `:seon.repl/ns` (only when the form changed it),
  `:seon.repl/ms`. Registered as `:seon.repl/response` in `seon.repl.edn`,
  the family that already holds `:seon.repl/comment` and `/form`. Order is
  enforced by the emitter, never by map printing.
- `:value` is rendered from the stored admitted value node
  (`result-edn`/`result-blob`), never stored a second time; a clipped value
  carries its elision node with a requery form (ruling 63c, B4), so no
  `capped?` key. Ruling 45 holds: nothing comment-shaped.
- The response is one datum. A wide value may wrap across lines by the
  printer's width; every wrapped line is a continuation of that one map and
  never comment-shaped (owner, 2026-09-07 afternoon).
- A multi-form reply echoes per form, in the namespace in effect for that
  form (`evaluate-sources` already threads it). Comments sit above the prompt
  so a prompt line holds exactly one form and HTML can label the comment.
- No reader guard: nothing in the agent's context looks like something it
  should write, and a `#:seon.repl{…}` map in a reply is just a form that
  evaluates to itself.
- One generator: `seon.repl/text` (evaluation entity → bytes), used by the
  page, the history unit, and the prompt. Deleted: `run/render-receipt-*`,
  `run/render-form-*`, `transcript/prompted-source`, `receipt-text`,
  `entry-bytes`, `receipt-printed-value`, `walk/generic-history-entries`
  and the `:seon.render/form` neighbourhood pass, `bootstrap/entry-source`.

## 5. The evaluation entity (storage)

Form and receipt merge into **one entity per (run, ordinal)** (note 3 §5.3):
`:seon.cluster.eval/run`, `/ordinal`, `/comment`, `/source` (the fixed source,
ruling 68), `/ns`, `/author`, `/at`, `/value` (admitted node) or `/error`
(+ `triage-edn`), `/out`, `:seon.eval/duration-ms`, `:seon.print/length` and
`/level` when the form set them, `/interrupted-at`, read evidence. Absence
of a terminal fact still means "running" (`run/terminal?`). This removes ~10
of the measured 38 datoms per freeze, the twin-identity ambiguity class, and
`fold-namespace`/`form-data`. The evaluation set is the agent's
`:seon.agent/evals` component; history is a query over it.

## 6. The run loop: keep, fix, delete (note 3)

**Fix first, no redesign:** the write path rebuilds the schema projection on
every commit because its cache key is the committed database identity
(`seon.db/transact-call`); a two-form turn spends 977 of 1050 ms there. Hand
the environment's projection to the writer. Expected: 1050 → ≈100 ms.

**Keep** (fourteen behaviours with their implementing lines and proving tests
in note 3 §5.6): dead-custody recovery, interrupted stamping, atomic
takeover, holder-only close, one settlement per ordinal, the bounded episode
rule, one trigger opens one run, delivery in the settlement transaction,
one-transaction batch settlement, refusal escalation once, everything the
loop writes installable by boot, `wait` frees the agent, generated prefix
grows only after its settled predecessor.

**Fences:** there is no `:db.fn/cas` anywhere and none is needed; every fence
is a `:db.fn/call` deciding on the mid-transaction value, which is stronger.
The ones that stay protect sequential re-entry and crash-recovery
interleaving (note 2 §5.3). Delete `::live-processes` (its only possible value
is the singleton); move `attempts`' ordinal derivation inside a transition
(the one real read-then-write, note 2 §5.4).

**Collapse:** close rides the settlement transaction whenever the fold is
complete (5–6 commit rounds → 4–5; 3 → 2 for a source turn); the commit
round, not the datom, is the unit of cost (8 sequential commits 4156 ms vs 8
concurrent 605 ms).

**Delete:** `:seon.cluster.work/situation` (authorship says it),
`generation-complete-call`, `:seon.cluster.run/forms` and the
`:seon.cluster.run.form/*` family, the `::agent-pointer-broken` refusal
(custody and pointer live on one loop entity), `render-source-call`'s private
evaluation, `current-read-evidence`, the hand-spelled reuse predicate, the
second output store.

**Preview** becomes an ordinary evaluation the agent did not ask for: the page
submits through the one path and marks it `:seon.cluster.eval/preview true`;
context regeneration skips previews; Add-to-context clears the mark. No
second evaluator, no second cache.

**The walk:** the distance-2 selector expands all ref attributes both ways
(769 million nodes at distance 2; +39 ms per run of history). Bounding by
declared render families instead of hop count is recorded here as the next
context-generation item, not part of this document.

## 7. Order of work (reset the dev database at every schema step)

Dev cluster per `.claude/seon-hook.edn`; data is disposable; each step lands
schema + code + tests together and is checked on the live page. Lanes are
file-disjoint; the orchestrator integrates, verifies on the page, commits
path-limited.

1. **Projection to the writer** (one value change) and the turn-time
   measurement before/after. Tests: `seon.db-test`, `seon.cluster.turn-test`.
2. **Evaluation entity + `seon.repl/text`**: merge form and receipt, retain
   comment/duration/print options, one generator, delete the formatters,
   page and prompt through it. Proof: one reply with a comment, a `println`,
   an `in-ns`, an error, a clipped value; page bytes = prompt bytes; the
   agent's next turn can `(count result/e33866)`.
3. **One eval point and cache**: the page submits previews through the loop;
   delete the bypass and the duplicate stores; regression derived from
   `:seon.fn/calls` (note 1 §5.3) plus closing the config-resolved evaluator
   so the edge is visible.
4. **The record**: rename to `seon.agent`; `:seon.agent/loop` (custody +
   pointer on one entity), `:seon.agent/evals`, `:seon.agent/sci`,
   `:seon.agent/plan` as one entity; delete `cluster`, `instructions`,
   `run`, units vector; `my.run/status`, `my.repl/history`.
5. **Wake components**: `:seon.agent/inbox` (messages, rename `to`),
   `:seon.agent/faults` with `:seon.error/to` from existing attribution
   (steward fallback after the fault seam records the function),
   `:seon.agent/schedule` folded into the one cluster listener; claim refs;
   `next-agent-work` arm; delete the `about` carve-out.
6. **Loop collapse and deletions** (§6), keeping every proof in note 3 §5.6
   green.
7. **Reset, reseed the Juniper fixture, screenshots, exact AI text per key,
   `bin/test` platform tier; vocabulary rows, architecture docs, AGENTS.md.**

## 8. Verification

- Page: the rendered keys once each, in schema order, description, executed
  AI source with one `#:seon.repl{…}` per form, HTML; ten loads write nothing.
- Same bytes: page AI column = history = prompt for the same evaluations.
- Turn: two-form source turn ≤ 150 ms wall after step 1 (from 1.1–1.6 s).
- Wakes: a sent message lands in the recipient's inbox and wakes it once;
  the handling run claims it; a throw attributed to an agent lands one fault
  in its faults once per signature; nothing wakes on a claim.
- Crash: kill mid-fold, reboot; custody released, interrupted stamped,
  nothing re-executed (existing recovery tests).

## 9. Stated assumptions (reversible, one each)

- A1 The steward fault route ships in two steps because the fault seam does
  not record the failing function today.
- A2 `loop` and `sci` are the names; `runtime` stays the environment's word.
- A3 History and context are derived; no stored key for either.
- A4 The walk's bounding by declared families is the next document.

## 10. Rulings recorded (design-ideas ledger 69–72)

69 data-shaped REPL reply; 70 agent-centric record with separate wake
components and claim-ref handling; 71 one eval point, one cache keyed by
code+input and validated by read evidence; 72 the run loop's keep list and
the projection fix first.
