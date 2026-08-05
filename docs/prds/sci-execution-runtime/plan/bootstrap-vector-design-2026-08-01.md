---
type: prd
status: active
tags: [prd, agent, context, sci, testing]
---

# The bootstrap vector — the design to run (2026-08-01)

The owner's ask: *"get back to testing with live agents with the context
plan we came up with — coming up with a reasonable bootstrap vector of
forms that we always execute so it's fresh and it guides the deepseek
agents to achieve reasonable objectives by writing and running
functions."*

`state-of-the-design-2026-08-01.md` names the bootstrap content as the
one **genuinely unknown** item — an experiment, not a design problem.
This document is therefore deliberately narrow: it fixes the MECHANICS
(which turn out to need almost nothing new), hand-builds ONE candidate
vector with every byte measured against the live system, argues the
execute-versus-prose split, and defines the first experiment's
objectives, grading predicates, and harness slice. It does not try to
be right about the content. It tries to be runnable this week so the
evidence can arrive.

Every output quoted below was produced through the REAL door on a live
scratch cluster (`bootstrap-design`, HEAD of
`codex/runtime-reliability-refactor` plus the in-flight repair lanes).
Probe: `tmp/bootstrap-design/{door.clj,vector2.clj}`; captured artifact:
`tmp/bootstrap-design/candidate-v2.txt`.

## 1. Mechanics — the bootstrap is a run whose plan the system wrote

**Verified against the landed code: no new mechanism is required.**

`seon.cluster.work/next-agent-work` (work.cljc:556-561) derives the
situation from what is already committed:

```clojure
(if (:seon.cluster.run/plan-digest run)
  (fold-or-close db run agent-id)      ; :resume — fold the frozen plan
  {:seon.cluster.work/situation :call  ; :call — ask the model for a plan
   …})
```

A run that already has a `plan-digest` NEVER reaches `:call`. So:

> **The bootstrap vector is an ordinary run whose `run/plan-tx` sources
> were written by the system instead of by a model.**

At agent creation, one transaction commits `run/open-tx`, `run/claim-tx`
and `run/plan-tx` with the bootstrap sources. The existing `:resume` fold
(loop.cljc:1200-1459) then does everything: per-form receipt-start, lint,
`seon.sci.eval/evaluate` on the cluster's live ctx, terminal receipt with
`result-edn`, `install-row!` for declarations, next ordinal. The
run reaches `:close` when the forms run out (no disposition needed — the
`:close` arm at loop.cljc:1477 handles exactly that).

Consequences that fall out for free, all of them wanted:

- the bootstrap's forms and receipts are ORDINARY facts, so the
  transcript renders them like any other history and **stateless resume
  restores them like everything else** (ruling #28/#32) — a fresh JVM
  rebuilding the session re-derives the bootstrap from the same
  `:seon.def`/program rows, with no bootstrap-specific path;
- an eval error inside the bootstrap does not abort the fold
  (loop.cljc:1436 continues on any non-transaction failure), which is
  what makes the deliberate refusal→repair beat in §3 possible;
- the bootstrap is per-agent and per-cluster by construction, so
  per-model calibrated vectors (owner direction, midday batch) are a
  different sources vector, not a different mechanism.

### What does NOT exist yet (the honest gap list)

1. **The creation-time seeding call itself.** `seon.cluster.agent/creation-tx`
   (agent.clj:83-98) commits the agent and its namespace and nothing
   else. Adding the bootstrap run is a second transaction beside it,
   ~20 lines. This is the only new code the mechanics need.
2. **Pinning.** Ruling #24 (amended) requires the bootstrap to be PINNED
   — it never slides out. `seon.render.transcript` today elides
   **oldest-first** (`projection`, transcript.clj:479-521, walking
   backwards from the newest and counting the dropped prefix into
   `::elided`). That is exactly backwards for a pinned bootstrap: the
   bootstrap is the first thing dropped. Pinning is a real change to the
   transcript projection, not a configuration.
3. **`help`.** Does not exist. §4 argues it should, and gives the text.
4. **`my.repl/prompt!` / `:my/prompt`.** Does not exist. §7 recommends
   CUTTING it from the first vector (it is a charming beat that teaches
   nothing the objectives measure).
5. **The re-query beat** the REPL-session design calls mandatory (read
   something big → watch it age → re-query one part) needs the
   agent-facing blob-backed read by `:seon.cluster.eval/id`, which does
   not exist. Deferred out of vector 1 (see §7, Q3).

## 2. What the bootstrap must compensate for — measured, not assumed

The agents are `deepseek-v4-flash` with thinking **disabled** (ruling #34
addendum): 9.8 s median turns, ~$0.00025/turn off-peak
(`research/deepseek-v4-flash-calibration-2026-08-01.md`). Fast, cheap,
structurally reliable — and, per the quality interrogation's method
section, graded on EXECUTED answers rather than plausible-looking ones.
A non-thinking model does not carefully trace subtle constraints; it
pattern-matches on what is in front of it. So the bootstrap's job is to
put the right patterns in front of it.

The single largest hazard is not Clojure — it is **Seon's
authored-contract admission rules**, which are unlike anything in the
model's training data. `seon.schema.internal/assert-complete-schema!`
(internal.cljc:59-160) refuses an agent-authored contract for five
distinct reasons:

| Rule | Refusal kind | Triggered by the obvious thing a model writes |
|---|---|---|
| no `:any`/`:some`/`:nil` | `:seon.schema/undefined-contract` | `[:=> [:cat :any] :any]` |
| `[:fn …]` needs a qualified pure predicate + `:error/message` + a `:gen/*` | `:seon.schema/incomplete-predicate-contract` | `[:fn pos?]` |
| no `[:maybe …]` as a map value | `:seon.schema/nilable-map-value` | `[:map [:note [:maybe :string]]]` |
| no bare `[:maybe …]` return | `:seon.schema/nilable-return` | `[:=> … [:maybe :map]]` |

The `:any` rule was tripped by an early draft of the bootstrap exemplar. An
older live transcript also captured the now-deleted open-map refusal:

```text
my.agents.tally=> (defn largest
  "The row with the largest :amount."
  {:malli/schema [:=> [:cat [:sequential [:map [:label :string] [:amount :int]]]]
                  [:map [:label :string] [:amount :int]]]}
  [rows]
  (last (sort-by :amount rows)))
Historical execution error (superseded by ruling #48): my.agents.tally/largest has an open agent-authored argument map.
```

The refusal messages are genuinely good — each names its own repair. That
is the asset the bootstrap should exploit rather than duplicate in prose:
put ONE refusal and its repair in the agent's own history and the model
has both the failure face and the fix pattern, in the most trained-on
form there is (a REPL error followed by a working retry).

## 3. The candidate vector — the actual forms, in order

Agent `tally`, namespace `my.agents.tally`. `(dir …)`, `(doc …)`,
`seon.db/q`, contracted `defn`, arity errors and admission refusals are
all VERIFIED working through the door today. Token costs are
`seon.ai.tokens/estimate` over the exact rendered bytes.

| # | Form | Teaches | Tokens |
|---:|---|---|---:|
| 0 | *(banner line)* `Seon REPL · cluster <name> · agent tally` | where, minimally | ~10 |
| 1 | `(help)` | the loop grammar (see §4) | 341 |
| 2 | `(in-ns 'my.agents.tally)` | namespaces, by doing the one op that matters | 19 |
| 3 | `(dir my.run)` | discovery exists; the two dispositions | 12 |
| 4 | `(doc my.run/complete)` | how a run ends, from the graph's own facts | **205** |
| 5 | `(dir my.message)` | teammates are reachable | 13 |
| 6 | `(seon.db/q '[:find (count ?f) . :where [?f :seon.fn/sym _]])` | the cluster IS a graph database, and it is big (1611) | 21 |
| 7 | the parsed-contract discovery query (below) | find functions BY DATA SHAPE | 48 |
| 8 | `(defn largest …)` with an open input map | **the refusal face** | 124 |
| 9 | `(defn largest …)` closed input + closed envelope return | **the repair** | 93 |
| 10 | `(largest [{:label "a" :amount 3} {:label "b" :amount 9}])` | run what you wrote; check the result | 25 |
| 11 | `(largest)` | the arity error face | 26 |
| 12 | `(largest [])` | the edge case you should always probe | 8 |
| 13 | the persistence query (below) | **your defn became a queryable fact** | 35 |

Form 7:

```clojure
(seon.db/q '[:find ?sym
             :where [?s :seon.schema/key :my.run/result]
                    [?a :seon.fn.arity/input-refs ?s]
                    [?f :seon.fn/arities ?a]
                    [?f :seon.fn/sym ?sym]])
#{["my.run/complete"]}
```

Form 13:

```clojure
(seon.db/q '[:find ?spec . :in $ ?sym
             :where [?f :seon.fn/sym ?sym] [?f :seon.fn/spec ?spec]]
           "my.agents.tally/largest")
```

Ruling #33's parsed contract facts are LANDED and this query works
through the door today (verified live): `:seon.fn/arities` with
`:seon.fn.arity/input-refs`/`output-refs` as refs to `:seon.schema`
entities, plus a full `:seon.fn/ast`. "Which functions accept X" really
is one query — form 7 is the single highest-value form in the vector,
because it is the only one that teaches a capability the model cannot
guess from Clojure knowledge.

### Measured cost

| Piece | Tokens |
|---|---:|
| Forms 2-13 (measured, exact rendered bytes) | **638** |
| `(help)` output (§4 text) | 341 |
| Banner | ~10 |
| **Pinned bootstrap total** | **≈ 990** |

Against `deepseek-v4-flash`'s 1M context that is **0.1%**. Two
observations follow, and they matter more than the number:

1. **The token budget is not the binding constraint; attention is.** A
   non-thinking flash turn degrades on a long noisy context long before
   1M. The sliding-window bound (ruling #24) should therefore be
   calibrated to measured answer quality, not to a fraction of the
   context window — which is what the harness in §6 is for.
2. **Form 4 is 32% of the executed vector** and it is 32% spent on
   `my.run/complete`'s docstring, which is a maintenance diary about the
   seal revision of 2026-07-27 and "N3 has no addressable recipient".
   That prose is agent-facing (the standing docstrings-render-into-context
   rule) and it is the wrong prose. **Rewriting the `my.*` docstrings for
   their actual reader would take ~205 tokens down to ~60 and improve the
   lesson.** Recorded here as a finding to file; it is not this lane's
   edit.

Cache economics: the pinned prefix is stable between compactions, and
Flash's cache-hit input is $0.0028/M against $0.14/M cache-miss — the
bootstrap costs approximately nothing to carry once warm. Pinning it is
cheap; churning it is what costs.

## 4. The execute-versus-prose split, argued

The ruling frame says the context IS the session transcript, so the
reflex is "everything must be a form." That reflex is wrong at exactly
one boundary, and the boundary is principled:

**A form can teach anything that is true INSIDE the session. It cannot
teach anything that is true about the LOOP OUTSIDE the session.**

Inside the session — the namespace, what functions exist, what a
contract refuses, what a value prints like, what persists — the system's
own output is the lesson, and it has a property prose can never have:
**it cannot drift.** `(dir my.message)` prints whatever `my.message`
actually contains today. A sentence claiming the same thing is a
liability the moment someone adds a function.

Outside the session there are facts no form can produce, because the
thing being described is the harness that will read the agent's NEXT
reply:

- that a reply is read as forms and evaluated in order (the agent has
  not replied yet — nothing has demonstrated this);
- that round trips are expensive, so batch (an economic fact about the
  provider, invisible from inside);
- that a `defn` with a complete `:malli/schema` PERSISTS and one without
  does not (form 13 shows persistence happened; it cannot show the
  policy — the counterfactual is unobservable);
- what ends a run (calling `my.run/complete` in the bootstrap would end
  the bootstrap run, so this specific lesson is structurally
  unexecutable).

So the split is:

- **Prose is admitted exactly once, as the OUTPUT of `(help)`** — which
  makes even the prose an executed fact: it sits at a real prompt, it
  has a real receipt, and the agent can re-run `(help)` later. This is
  the strongest available form of "show, don't tell" for content that
  genuinely cannot be shown.
- **Inline `;` comments carry only NARRATION of the agent's own
  reasoning** ("; both of these read the graph; nothing depends on the
  first, so they go together") — which is behavior modelling, not
  instruction, and is preserved only as submitted source before its forms.
  Comments never represent a form's output.
- **Everything else is a form whose output is the lesson.**

`(help)` is deliberately a function rather than a system message: a
system message is a second mechanism with a second lifetime, and ruling
#24's whole point is that there is one artifact. The text (measured at
341 tokens) is in `tmp/bootstrap-design/help.txt`.

One rule for the vector's forms, derived from the measurement in §3:
**every bootstrap form's result must be small.** A single unbounded
result destroys the pinned prefix's economy — `(vec (range 12000))`
renders 39,908 characters (≈9,977 tokens) through the door, ten times
the entire bootstrap.

## 5. Objectives for the first experiment

Five, ordered by dependency. Each is write-and-run-function shaped and
each grading predicate is a query or an execution **on the grading
branch forked from the run's ending commit**
(`plan/grader-in-fact-space-2026-08-01.md`) — never a string match on a
reply.

**O1 — Author a contracted transform.** Surface: rows transacted as
facts. "Define a function in your namespace that takes these rows and
returns the total per label, run it on the data, and report the answer."

- `P1a` a `:seon.fn/sym` row exists under `my.agents.<id>/` with a
  `:seon.fn/spec` (it persisted at all — this is the contract-rule gate);
- `P1b` calling that function on **held-out** inputs in the grading
  branch returns the expected values (executed; also the anti-hardcode
  falsifier);
- `P1c` the run closed `:my.run/disposition :completed` with the correct
  answer in `:my.run/result`.

**O2 — Find and use a function you were not told the name of.** "End
this run using the function in this cluster that accepts
`:my.run/result`."

- `P2a` some receipt's form source queries `:seon.fn.arity/input-refs`
  or `:seon.fn/spec` (discovery happened rather than recall — the direct
  measurement of whether form 7 transferred);
- `P2b` disposition completed.

**O3 — Answer a question only the graph can answer.** "How many public
functions does `my.message` have?"

- `P3` the completed result parses to the number the grader's own query
  computes at the ending commit — **derived, never a stored expected
  value.**

**O4 — Two-agent delegation with a durable deliverable.** A must get B to
author a function, then call it.

- `P4a` messages exist A→B and B→A;
- `P4b` a `:seon.fn/sym` row exists under B's namespace;
- `P4c` a receipt in A's run whose form calls B's symbol, with no
  `:seon.cluster.eval/error`;
- `P4d` A completed. (This also exercises ruling #27's one-live-graph
  claim end to end: B's `defn` must be callable by A without a
  reinstall.)

**O5 — Repair a refused contract.** A task whose natural contract trips
the surviving `:any` rule.

- `P5` an eval error receipt carrying `:seon.schema/undefined-contract`
  exists AND a later `:seon.fn/spec` row for the same symbol exists in
  the same run — i.e. the agent read the refusal and repaired it
  in-session. This is the direct falsifier for the §3 refusal→repair
  beat, and the cheapest way to learn whether show-don't-tell works on a
  non-thinking model.

The judge (ruling #30) additionally scores CHEATING as its own axis —
hardcoded answers, a contract loose enough to accept anything, a
`complete` whose text asserts work that no receipt shows. `P1b`'s
held-out inputs make the first mechanically detectable; the other two are
the judge's job.

## 6. The experiment harness — slice 1

The minimum that produces evidence, sized as one lane:

1. **Seed** — one function that, given a cluster and a bootstrap sources
   vector, commits `open-tx` + `claim-tx` + `plan-tx` at agent creation.
   (§1; the only new mechanism.)
2. **Prepare** — fork the published commit into an exam branch per
   objective and transact the surface rows (grader plan step 1). Already
   possible: fork is ~17 ms.
3. **Drive** — N objectives × M runs, one fresh agent per run, flash
   **non-thinking** per ruling #34's addendum. At $0.00025/turn a
   5 × 5 × ~6-turn matrix is under $0.05; cost is not a constraint and
   should not shape the design.
4. **Grade** — fork each run's ending commit into a grading branch, run
   the predicates in §5 there, record scores as facts.
5. **Report** — one table: objective × run × predicate, plus the
   transcripts, in the same debug display as any other session.

Deliberately NOT in slice 1: the overseer's comprehension loop (ruling
#30 phase 1's self-taught bootstrap), the pinned-vs-bare-tail comparison,
per-model calibration. Those all consume slice 1's output; running them
first would be optimizing a bootstrap nobody has yet shown an agent can
work with at all.

Prerequisite, non-negotiable: **transcript pinning (§1, gap 2).** Without
it a long run silently elides the bootstrap oldest-first and the
experiment measures the wrong thing.

## 7. Open owner questions (recommendation first)

**Q1 — Ship `(help)` as one prose block, or push harder to eliminate it?**
*Recommendation: ship it (341 tokens).* The four facts in §4 are
genuinely unexecutable, and burying them in docstrings scatters them.
Alternative considered and rejected: a system message — a second
mechanism with a second lifetime, against ruling #24.

**Q2 — Include the deliberate refusal→repair beat (forms 8-9, 217 tokens)?**
*Recommendation: yes, and make O5 measure it.* It is the only part of the
vector aimed squarely at the measured hazard in §2, and O5 tells us
within one experiment whether it earns its tokens. If O5 shows no
transfer, delete it and put the closed-map rule in `help` instead.

**Q3 — The re-query/aging beat: defer?** *Recommendation: defer.* The
REPL-session design calls it mandatory for the tutorial, but it needs an
agent-facing blob-backed read by receipt identity that does not exist,
and the owner's own midday direction was "render the full transcript for
now — no aging variables until the floor is proven." It returns with
compaction.

**Q4 — `my.repl/prompt!` and `:my/prompt`: build for vector 1?**
*Recommendation: cut.* It is a lovely beat (ownership + visible
persistence in one line) and it teaches nothing any objective in §5
measures, while adding a new fact, a new function, and driver prompt
rendering. Revisit when the vector is being optimized rather than
established.

**Q5 — Where does the harness live?** *Recommendation: `src/seon/` as
ordinary maintained code* (the standing rule that anything which will run
again is real code, and the standing rule against maintained code in PRD
directories). `src-inspect-ai/` remains the external model-evaluation
bench; this harness is entirely in fact-space and in-process.

## 8. Findings from the live probes (to be filed as issues)

Each was observed through the real door on `bootstrap-design`; none is
this lane's to fix.

1. **`defn` returns a string, not a var.** A declaring form's receipt
   value is `"my.agents.tally/largest"`; a stock Clojure REPL prints
   `#'my.agents.tally/largest`. A REPL-parity divergence sitting on the
   single most common form an agent writes.
2. **Elision does not expose how much was elided in the returned value.** A
   capped collection renders `… 8190 :seon.sci.admit/elided]` — a bare
   keyword. The remedy must enrich the ordinary value or expose an explicit
   query; it must not append a synthetic "N of M shown" notice or comment.
3. **`my.*` docstrings are maintenance diaries.** `my.run/complete`'s
   costs 205 tokens of agent context to say something worth ~60, most of
   it about a 2026-07-27 seal revision (§3).
4. **Agent contract enforcement is not live in this build.** With a valid
   contract installed, `(largest "not-a-sequence")` produced a raw host
   `NullPointerException` rather than a contract violation. Ruling #33(2)
   is still in the `session-repair-contracts` lane. **The show-don't-tell
   guardrail beat (a form whose result SHOWS a contract catching a bad
   call) is BLOCKED on it** — which is why §3's vector teaches contracts
   through the ADMISSION refusal (which does work today) instead.
5. **Transcript elision runs oldest-first**, i.e. exactly backwards for a
   pinned bootstrap (§1, gap 2).
