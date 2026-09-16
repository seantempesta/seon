---
type: decision-brief
status: open (awaiting owner ruling)
created: 2026-09-17
tags: [decision, seon.db, write-admission, render, prompt, turn-loop, vocabulary]
about: [6-partial-upsert, 7-prompt-budget, 8b-undisposed-notice, 9-render-selection-fallback]
---

# Decision brief B — write admission, the prompt's budget, the undisposed turn, render selection

Four of the ten decisions parked in
[the overnight report](../overnight-report-2026-09-17.md) §"Owner decisions"
(numbered 6, 7, 8b and 9 there). Each section is: background, one real
example with exact bytes or a measured number, the options simplest-viable
first, and a recommendation argued from [AGENTS.md](../../../../../AGENTS.md)
§2 and from what Datahike and Malli already do.

Read end to end for this brief: the overnight report; the three issue notes
named below; the peer's
[undisposed-turn-notice ruling check](../../../context-generation/research/undisposed-turn-notice-ruling-check-2026-09-16.md);
the turn PRD's §14 session-continuation ruling. Every `file:line` below was
opened and verified in this pass. Read-only: no source, test or cluster
state was changed; one live read query was spent (§7(b)).

---

## 6. Partial upsert admission

Issue:
[a-partial-upsert-of-an-existing-entity-is-validated-against-its-complete-required-keys](../../../../seon/issues/a-partial-upsert-of-an-existing-entity-is-validated-against-its-complete-required-keys.md).

### (a) Background

`seon.db/transact!` admits transaction data before handing it to Datahike.
The admission walk is `write-error` (`src/seon/db.clj:2901`), which for every
map entry calls `write-map-error` (`src/seon/db.clj:2849`). `write-map-error`
collects the entity schemas that declare each of the map's IDENTITY
attributes as a required key (`write-entity-schemas`, `src/seon/db.clj:2680`,
which keeps an attribute only when it is non-optional AND
`schema/identity-attr?`), then validates the WHOLE map against each of those
entity schemas (`src/seon/db.clj:2871-2889`). So the moment a map carries
`:seon.schedule/id`, it must also carry every other required key of the
schedule entity — whether or not that entity already exists and already
carries them.

The seam runs at `src/seon/db.clj:3030`: `(or (write-error database
projection transaction) (let [report (d/transact connection …)]))`, where
`database` is `(d/db connection)` — the database BEFORE the transaction.
That is the structure AGENTS.md §2 names as a defect class in its own
sentence: "No seam may act on a pre-read or a mirror that its authority will
re-decide: derive at the authority, or hand it the decision." Datahike's
transactor is the authority that decides whether this map creates an entity
or updates one: `upsert-eid`
(`reference-code/datahike/src/datahike/db/transaction.cljc:640-671`) resolves
the map's `:db.unique/identity` attributes through AVET and, when one hits,
`entity-map->op-vec` reuses that entity id
(`reference-code/datahike/src/datahike/db/transaction.cljc:956-963`) instead
of minting a new one. The admission check runs before that resolution and
therefore cannot know which of the two it is validating. The practical
consequence named in the issue is the perverse one: callers work around the
refusal with `[:db/add …]` datoms, which `write-error`
(`src/seon/db.clj:2906-2910`) validates per attribute only — so the rule
pushes every update onto the LESS validated grammar.

### (b) Real example — the exact refusal bytes

`tmp/orchestrator/gate-results/batch-67/named.log:206`, the
`seon.maintenance-schema-test` sighting, verbatim:

```
ERROR in (root-owned-portfolio-initializes-as-queryable-schedule-facts) (test_support.clj:250)
Uncaught exception, not in assertion.
expected: nil
actual: clojure.lang.ExceptionInfo: Fixture write was refused at the write: seon.db/transact! refused transaction data at [0 :seon.schedule/zone-id]: expected the required key :seon.schedule/zone-id with a string, got a map missing :seon.schedule/zone-id. Fix: Supply :seon.schedule/zone-id with a string. Offending row 0: #:seon.schedule{:id "root/maintenance/footprint-schedule", :expression "7 4 * * *"}.
```

The map being refused carries `:seon.schedule/id
"root/maintenance/footprint-schedule"` — an identity that
`src/seon/schedule.clj` had already seeded with a zone. The second sighting,
verbatim from `tmp/orchestrator/gate-results/batch-65/named.log:3603` (note
that the refusal MESSAGE is itself cut at a character offset by the value
profile — that is decision 7, in the same bytes):

```
actual: #error {:cause {:seon.ai.tokens/estimate 46, :seon.print/bound-by :seon.render.profile/token-budget, :seon.print/elision-unit :characters, :seon.print/omitted 149, :seon.print/prefix "Fixture write was refused at the write: seon.db/transact! refused transaction data at [0 :seon.turn/agent]: expected the required key :seon.turn/agent with a value satisfying unknown error, got a map miss", …
```

The third sighting is the config overlay class routed through `apply!`,
recorded in the issue.

The strongest evidence that the fix belongs at the transactor is that this
codebase ALREADY wrote it, for this exact schedule entity.
`src/seon/schedule.clj:72-78`:

> "Existing task identities are sovereign. In particular, reopening a cluster
> never restores the recommended cron or timezone over an ordinary cadence
> transaction. This function runs through `:db.fn/call`, so absence is decided
> by the serial writer rather than by a caller pre-read."

`test/seon/maintenance_schema_test.clj:262-264` transacts exactly
`[[:db.fn/call #'schedule/root-maintenance-seed-call]]`. What the dependency
gives us: `:db.fn/call` is handled at
`reference-code/datahike/src/datahike/db/transaction.cljc:1152-1153` as
`(let [[_ f & args] op-vec] [report (apply f db args)])`, where `db` is bound
at `:1238-1240` to `(:db-after report)` — the database AS IT STANDS at that
point in the transaction — and the ops the function returns are spliced into
the remaining work at `:1306-1307`. `seon.db` already uses that mechanism as
a pair of before/after calls in `retain-transaction`
(`src/seon/db.clj:3003-3015`).

What Malli gives us, and does not: a `:map` explainer checks each
non-optional key with `find` against the value it is handed
(`reference-code/malli/src/malli/core.cljc:1294-1299`, emitting
`::missing-key`). Malli has no notion of an entity that already exists — it
validates the value in front of it. So the merge must happen BEFORE the
schema sees the map; there is no Malli lever that makes "required unless the
entity already has it" expressible. Seon maps are open (AGENTS.md §2.5), so
merging extra existing attributes into the validated value is admitted by
construction, not a widening.

### (c) Options

1. **Validate the merged entity inside `[:db.fn/call …]`** (simplest
   viable). `write-map-error`'s entity-schema pass moves from the pre-read to
   a transaction function: for a map whose identity resolves to an existing
   entity on the mid-transaction db, validate `(merge existing supplied)`; a
   map whose identity resolves to nothing is validated exactly as today.
   *Guarantee:* an update never fails for a key the entity already carries; a
   creation still needs every required key; the decision is made by the same
   serial writer that decides upsert-vs-create, so it cannot disagree with
   it. *Cost:* one AVET lookup plus one pull per upserted identity, inside
   the transaction; the refusal becomes a Datahike abort classified back to a
   `:seon.error` value at `src/seon/db.clj:3040-3055` rather than a value
   returned before `d/transact` (the `write-attribute-error` per-attribute
   pass can stay where it is and keep refusing cheaply). *What we give up:*
   admission is no longer a pure pre-transaction function of
   `(db, projection, transaction)`; part of it now only runs when the
   transaction runs.
2. **Validate only the supplied keys when the identity resolves to an
   existing entity**, still as a pre-read. *Guarantee:* the same refusals
   disappear, with no change to where admission runs. *Cost:* near zero.
   *What we give up:* correctness under concurrency — the pre-read's answer
   ("this entity exists") can be false by the time the writer acts (a racing
   retraction), and the entity can then be left incomplete with no refusal.
   This is the pre-read-the-authority-will-re-decide shape AGENTS.md §2 rules
   against, and it is the same disease as the two live defects logged last
   night (`operator-root` answering before the caller's root; the install
   seam comparing re-printed bytes).
3. **Keep whole-schema validation; callers pull-merge before writing.**
   *Guarantee:* the mechanism is unchanged and every validated write is
   complete. *Cost:* every update site grows a pull and a merge, each of them
   its own pre-read with the same race. *What we give up:* in practice
   nothing changes — writers keep using `[:db/add …]`, which is the least
   validated grammar, and the issue's "the validated path is the one nobody
   uses for updates" stands.

### (d) Recommendation — option 1

Because the thing being validated ("is this entity complete?") is decided by
Datahike's transactor, and `:db.fn/call` is the dependency's own way to ask a
question of the database mid-transaction. §2.2 (facts over inference): the
completeness of an entity is a fact of the database after the write, not an
inference from the caller's map. §2.4 (total, honest boundaries): a refusal
must name what is actually missing; today it names a key the entity has.
§2.5 (one mechanism): option 3 keeps two grammars with different rigour, and
option 2 keeps the pre-read AGENTS.md already ruled against. The precedent,
the vocabulary and the docstring for this exact decision already exist at
`src/seon/schedule.clj:72-78`; option 1 applies the sentence that is already
written there to the admission seam itself.

---

## 7. Which budget owns the prompt

Issue:
[the-agents-history-is-cut-as-one-string-by-the-value-budget](../../../../seon/issues/the-agents-history-is-cut-as-one-string-by-the-value-budget.md).

### (a) Background

An agent's prompt is assembled by `seon.cluster.prompt/prompt`
(`src/seon/cluster/prompt.clj:215-242`). It resolves the agent's effective AI
settings, then calls `acquire-context-report`
(`src/seon/cluster/prompt.clj:188-212`), which calls
`seon.render/acquire-context!` (`src/seon/render.clj:1550-1571`) for the
history text, appends the turn frame from `seon.repl/frame`, and finally
computes `(tokens/budget-report text budget calibration)` with
`:seon.config.ai/prompt-token-budget` (`src/seon/cluster/prompt.clj:236`).
That report is a VERDICT, not a cut: `seon.ai.tokens/budget-report`
(`src/seon/ai/tokens.cljc:195-210`) classifies the assembled text as
`:over` / `:near-limit` / `:within` and nothing trims. So the prompt's own
declared dial measures, and never bounds, the prompt.

The trimming that does happen is the value renderer's. The history is
rendered by `seon.render.transcript/render-ai`
(`src/seon/render/transcript.clj:742-747`), whose `ai-output`
(`src/seon/render/transcript.clj:623-636`) deliberately elides nothing —
its comment says so in as many words. The entries are joined into ONE string,
and that string is then fitted as a single `::string` node by
`seon.print/fit-text` (`src/seon/print.cljc:1212-1237`) under the AGENT VALUE
profile, whose `:seon.render.profile/token-budget` comes from
`:seon.config.render.agent/token-budget` (`src/seon/render.clj:52-66`;
`config/default.edn:91`). The result is a cut at a CHARACTER OFFSET, with
`::path []` — it names no turn and no evaluation. The two budgets are
`config/default.edn:91` = 1024 estimated tokens for one shown result and
`config/default.edn:387` = 32768 estimated tokens for the whole prompt, a
32× gap, and today the smaller one bounds the larger one's contents.

### (b) Real example — exact bytes, and an honest caveat

`tmp/orchestrator/gate-results/batch-70/named.log:1659`, the failing payload
assertion, with the rendered history shown through the same value profile:

```
FAIL in (n-agents-fold-independently-on-one-live-cluster) (concurrency_independence_test.clj:497)
s0-n5 with N=5
expected: (str/includes? rendered (:seon.concurrency-independence-test/payload incoming))
actual: (not (str/includes? {:seon.ai.tokens/estimate 492, :seon.print/bound-by :seon.render.profile/token-budget, :seon.print/elision-unit :characters, :seon.print/omitted 1575, :seon.print/prefix ";; I should read this message and decide how to respond.\n(my.message/read #:my.message{:id \"9f9f0d0b\"})\n\nmy.agents.concurrency.s0-n5.a0=> (seon.db/transact! {:tx-data [#:seon.test.run{:id \"stress-s0-n5-row-0-0\", …}]})\n#:seon.repl{:valu
```

The bytes say exactly what the issue says: `:seon.print/bound-by
:seon.render.profile/token-budget`, `:seon.print/elision-unit :characters`,
500 characters shown, 1,575 omitted out of 2,075, and the prefix ending
mid-token at `#:seon.repl{:valu`. Tallies from the same log:
`seon.concurrency-independence-test/n-agents-fold-independently-on-one-live-cluster`
is "Ran 1 tests containing 2826 assertions. / 199 failures, 0 errors."
(`:2684-2685`); in batch 75 (`tmp/orchestrator/gate-results/batch-75/named.log:1757-1758`)
the same test is 154 / 2826. `seon.render.transcript-run-test/render-run-selects-only-the-requested-run`
is 11 / 17 (`tmp/orchestrator/gate-results/batch-70/named.log:1434-1435`),
and four of its failures are an AI render answering the empty string while
the HTML render answers a turn header, e.g. `:1390-1392`:

```
FAIL in (render-run-selects-only-the-requested-run) (transcript_run_test.clj:75)
expected: (str/includes? ai "interrupted before the reply arrived")
actual: (not (str/includes? "" "interrupted before the reply arrived"))
```

**Caveat the owner should have before sizing this.** In the same batch-70
block, `concurrency_independence_test.clj:490` fails with
`(not (= #{"stress-s0-n5-run-0-4-message-0" "stress-s0-n5-run-4-4-message-0"} #{}))`
(`:1653-1657`) — the agent has NO messages attached at all — and `:443`
fails the same way for `turn/unanswered-triggers`. So the 199 failures are at
least two families, and the report's phrasing "199 concurrency assertions
could never see their payload" over-attributes them to the render cut. The
cut is real and the bytes above prove it; the count is not yet apportioned.
The isolating evidence for the cut itself is the issue's own A/B in one JVM
(`bb33b93fa~1`: `omitted 2075`, no prefix; HEAD: `omitted 1051`, 1,024-char
prefix), which also shows that the floor slice strictly increased what these
assertions can see and that relaxing them would hide the defect.

**Live reading (the one query spent, `default`, jvm mode, explicit
custody).** `:seon.config.render.agent/token-budget` = **1024**;
`:seon.config.ai/prompt-token-budget` = **32768**. The same query asked for
Juniper's evaluation count through `seon.eval/of-agent` with a
`[:seon.eval/id …]` selector and was refused —
`Bad entity attribute :seon.eval/id … not defined in current schema` — because
the evaluation entity's identity attribute is still `:seon.cluster.eval/id`
(`resources/seon/schemas/seon.eval.edn:10`). That is itself a finding; see
the vocabulary section. The per-agent evaluation count and the exact offset
in Juniper's own history remain unmeasured in this pass, and I did not spend
a second query to get them.

### (c) Options

1. **The prompt's own budget cuts whole evaluations, oldest first; the value
   profile keeps bounding each shown result** (simplest viable, and the one
   the issue's "Wanted" section asks for). `seon.cluster.prompt` selects how
   many of the agent's evaluations fit under
   `:seon.config.ai/prompt-token-budget` before the history is joined, and
   the omission becomes one elision value naming the dropped turn count and
   the oldest surviving ordinal. *Guarantee:* the most recent turns are
   always COMPLETE and nameable; every remaining evaluation's own result is
   still bounded at 1024 tokens by the value profile; the cut is judged
   against the dial that was declared for it. *Cost:* the prompt owner gains
   a selection step and an elision value; the budget stops being purely a
   verdict. *What we give up:* the prompt is no longer a pure `str` of
   whatever the walk produced.
2. **Raise `:seon.config.render.agent/token-budget` on the history path.**
   *Guarantee:* the tests pass and more history survives. *Cost:* one number.
   *What we give up:* everything — the cut stays character-shaped and
   mid-form, it just fires later; and a tuned constant replaces the
   observable event, which AGENTS.md §2.3 names as the defect, not the fix.
3. **Keep as is and relax the tests.** *Guarantee:* green. *Cost:* none
   today. *What we give up:* the only checks that notice an agent's own
   history losing its message payloads; the issue's A/B shows these
   assertions could never have passed, so relaxing them buries a live defect.

### (d) Recommendation — option 1

§2.4 states the one-clipping-spot rule as "the AI RENDER FUNCTIONS and the
VALUE RENDERER … apply its string, child-count, depth and token limits", and
option 1 does not add a second clipping spot: it changes WHICH EVALUATIONS
the history is built from, which is selection, not elision — the value
renderer keeps doing all the clipping, on each result, exactly as ruled. §2.4
also requires that "previously omitted detail is an elision value … carrying
count, path, and requery identity, never bare truncation"; today's cut
carries `::path []` and names no turn, so it fails that requirement on its
own terms. §2.3: the bound belongs at the seam that admits the work — the
prompt's assembly is what admits history into a paid provider call, and
`:seon.config.ai/prompt-token-budget` is that seam's declared bound
(`src/seon/cluster/prompt.clj:236`), currently enforcing nothing. And the
recurring-failure-class test in AGENTS.md — "what does this check report when
its subject is absent?" — is failed by option 3 by construction: an assertion
relaxed around an empty render reports health when the agent sees nothing.

---

## 8b. The undisposed-turn notice, and the continuation sub-question

Background note (read end to end):
[undisposed-turn-notice-ruling-check-2026-09-16](../../../context-generation/research/undisposed-turn-notice-ruling-check-2026-09-16.md).

### (a) Background

When a turn's last clean agent form returns neither `my.turn/complete` nor
`my.turn/wait`, the turn used to assert `:seon.turn/undisposed-at`, and
`seon.turn/render-ai` had one `cond` branch that told the agent "It ended
without my.turn/complete or my.turn/wait. Its trigger remains unanswered;
nothing was retried." `ae0e54841` (2026-09-09) deleted the attribute, the
`close-tx` argument and that branch in one commit, executing a PRD §1a row
whose stated reason was about the STAMP ("derived in the same transaction
from the evaluations"), not about the notice. The peer's verdict, which I
checked against the PRD: the PRD does not rule where the notice is written —
§13, §14, §15 and §16 never mention it — and the two rulings that touch the
same facts replace its purpose without naming a successor. §15 rules that
"nothing assembles the history but the walk", and §14's session continuation
(2026-09-10) rules that precisely this turn RE-OPENS, so the original
defect's "nothing wakes the agent again" half is gone by construction. What
remains unruled is only whether the agent can read WHY it got another turn.

What survives in the tree: `src/seon/turn.clj` derives `undisposed?` per
settlement in memory and closes the turn correctly (the test's first two
assertions are green in batch 85); `src/seon/eval/drive.clj:248,271` still
derives the episode terminal `:undisposed` with no stamp; and
`src/seon/render/transcript.clj:537` `undisposed-run-text` plus the `:run`
branch at `:569` are orphans — no code constructs a `::kind :run` entry any
more. The failing assertion therefore asks for bytes no seam produces,
through an entry kind no walk emits.

### (b) Real example — the failing assertion and the ruling text

`test/seon/turn_loop_test.clj:1130-1136`, verbatim:

```clojure
          (is (= {:seon.eval.drive/outcome :undisposed
                  :seon.eval.drive/run-ids [run-id]}
                 terminal)
              "the episode verdict names the missing disposition")
          (is (str/includes? rendered
                             "ended without my.turn/complete or my.turn/wait")
              "the following history carries the system-authored notice")
```

The ruling that replaced its purpose, `docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md:979-986`, verbatim:

> ### Session continuation (owner, 2026-09-10)
>
> A reply is one turn, not the end of a session. With no open turn, the
> session remains open exactly when the latest closed turn has an accepted
> provider reply (reply-size present and a successful attempt), its last
> evaluated form did not return `:completed` or `:wait`, and turns remain
> under the existing bound.

### (c) Options (the peer's A/B/C, restated)

- **A — dissolve it.** Delete the third assertion and the orphaned `:run`
  entry path. *Guarantee:* no notice exists; §14 continuation re-wakes the
  agent, `eval.drive` names `:undisposed` for episode verdicts, and the
  absent `:seon.turn/disposition` is already a queryable fact. *Cost:* one
  test expectation and ~15 lines of dead transcript code
  (`src/seon/render/transcript.clj:537`, `:569`). *What we give up:* the
  agent is never TOLD why it got another turn; it must query or infer.
- **B — one stored evaluation in the next system turn**, following §18b's
  precedent (a reply with no forms becomes one evaluation carrying a flat
  error): the system turn appends an evaluation whose value is
  `{:seon.error/kind :seon.turn/undisposed …}`, rendered by the evaluation
  schema pair like everything else. *Guarantee:* the agent reads the reason
  in its own history, through the one walk. *Cost:* one derivation at
  `src/seon/turn.clj:2039`; no new attribute, no new render branch, no second
  history assembly. *What we give up:* the opening algorithm gains a
  non-read-form emitter, which §14 otherwise restricts to changed read forms.
- **C — restore the turn-level render line** and re-admit turn entries into
  the history. *Guarantee:* the old bytes come back. *Cost:* resurrects the
  `:run` entry producer. *What we give up:* §15 directly — "nothing assembles
  the history but the walk".

### The continuation sub-question

`continuing-reply?` (`src/seon/turn.clj:2856-2878`) is:

```clojure
(defn- continuing-reply?
  "The latest closed turn accepted a provider reply and did not end the session."
  [database agent-id]
  (when-let [latest-t …]
    (some?
     (db/q '[:find ?turn . :in $ ?agent-id ?latest-t
             :where [?agent :seon.agent/id ?agent-id]
             [?turn :seon.turn/agent ?agent]
             [?turn :seon.turn/id _ ?t]
             [(= ?t ?latest-t)]
             [?turn :seon.turn/closed-tx _]
             [?turn :seon.turn/reply-size _]
             [?turn :seon.turn/attempts ?attempt]
             (not [?attempt :seon.ai.attempt/error _])
             (not [?turn :seon.turn/disposition _])]
           database agent-id latest-t))))
```

It is consulted at `src/seon/turn.clj:2928`. Note what is absent: no clause
requires that the turn settled ANY evaluation. §14 says continuation needs
"its last evaluated form did not return `:completed` or `:wait`"; with no
evaluated form at all, this query says yes, and a paid session continues.
The shrunk counterexample the peer quotes from batch 85's log is
`:smallest [true true true true nil []]` — planned, closed, triggered,
trigger-first, no lint ordinal, and an EMPTY evaluation vector, i.e. the
boot-closed / interrupted shape. The property clause it breaks is
`test/seon/turn_work_test.clj:499-502`:

```clojure
                 ;; Every answered closed turn is idle. Receipt content cannot
                 ;; manufacture a new trigger or corrective turn.
                 (or (not answered-closed?)
                     (nil? situation))
```

That sentence was true before 2026-09-10 and is false under §14. Options:

- **i (recommended) — require at least one settled evaluation.** Add to
  `continuing-reply?` the clause that the turn has a settled evaluation with
  no `:seon.eval/interrupted-at`, and repair the property's fourth clause to
  "an answered closed turn is idle OR its situation is the `:open` derived by
  continuation". *Guarantee:* a turn that evaluated nothing (boot recovery
  closed it; the JVM died mid-turn) never re-opens a paid session on its own.
  *Cost:* one clause and one expectation. *What we give up:* nothing that §14
  promises — §14's condition is stated about "its last evaluated form", which
  presumes one exists.
- **ii — keep as is** and repair only the property's fourth clause.
  *Guarantee:* the smallest diff. *Cost:* none. *What we give up:* the
  fail-closed property — an empty derived set currently fails OPEN onto a
  paid provider call, which is the shape AGENTS.md §2.3 and the PRD's §3
  ("An empty derived set fails CLOSED") both rule against, and which the
  write-storm post-mortem (overnight report §00) is the expensive instance
  of.
- **iii — require an explicit disposition to continue** (invert the default).
  *Guarantee:* continuation only where someone asserted it. *Cost:* a new
  stored assertion. *What we give up:* §14 itself, which rules that no
  session-open flag is stored.

### (d) Recommendation — A for the notice, i for the sub-question

**A**, because §15's ruling that "nothing assembles the history but the walk"
is exactly §2.5 (one mechanism, accreted in place), and B and C both add a
second producer of history text to carry a line whose information — the
absent `:seon.turn/disposition` — is already a fact the agent can query
(§2.2). A also deletes a mechanism, which AGENTS.md's "prefer dissolution to
addition" asks for, and it removes code that is provably dead
(`src/seon/render/transcript.clj:537`, `:569`) rather than leaving it to rot.
If the owner wants the agent TOLD rather than able to ask, B is the only
option that stays inside the walk; C is wrong on sight.

**i** for the sub-question, because `continuing-reply?` is a derived
predicate over facts and its empty case currently answers "continue" — the
recurring failure class AGENTS.md names in its own paragraph: "a check that
reads ABSENCE OF SIGNAL as health". Here absence of any settled evaluation
reads as a live session and spends money. Datahike gives us the honest form
for free: the clause is one more `:where` datom pattern on the same query, so
the fix costs one line and the derivation stays a single Datalog query with
no second mechanism.

---

## 9. Render selection fallback

Issue:
[the-selection-chain-regression-still-asserts-the-pre-inbox-edge-message-shapes](../../../../seon/issues/the-selection-chain-regression-still-asserts-the-pre-inbox-edge-message-shapes.md).

### (a) Background

Render selection is per render call. `seon.render/render-ai`
(`src/seon/render.clj:1238-1254`) and `render-html` (`:1256-1272`) each
derive the profile, then ask `producer` for the one function to invoke;
`present-output` (`:1231-1236`) deliberately passes the producer's output
through untouched, because elision belongs to the value renderer. The
selection stages are `[:explicit-value :explicit-request :namespace :schema
:floor]` (`src/seon/render.clj:430`). The stage under dispute is the schema
stage, whose entry point is `declared-producer` (`src/seon/render.clj:420-424`):
it asks `attribute-declared-producers` first and only falls through to
`schema-producer` when that returns nil.

`attribute-declared-producers` (`src/seon/render.clj:366-387`) is where the
open question actually lives:

```clojure
        attribute-value? (and attribute
                              (or (not (map? value))
                                  (contains? value attribute)))]
    (when attribute-value?
      (if-let [declared (attribute-producer projection request output)]
        [declared]
        (when (= :seon.render/form output)
          ['seon.render/render-form])))))
```

The guard's stated purpose (comment at `:375-378`) is that the walk stamps an
attribute on a NEIGHBOUR it reached through that attribute, and an entity map
lacking the attribute is that neighbour, so it must render by its own shape.
But the same guard fires when the OWNING entity simply does not carry the
attribute in this database — and then an attribute-scoped request silently
becomes an entity-scoped one. That is the fallback the owner is being asked
to rule on.

The regression that pins this is
`seon.render.history-test/form-is-the-third-output-of-the-existing-selection-chain`
(`test/seon/render/history_test.clj:28-103`), 3 / 9 / 0, red since
`ae0e54841` and first surfaced in batch 83 because that is the first gate
list to name the namespace.

### (b) Real example — the two concrete cases, with the declarations

**Case 1 — the agent entity pulled `'[*]`.**
`test/seon/render/history_test.clj:84-94` expects
`seon.cluster.agent/situation-form` for `(db/pull database '[*]
[:seon.agent/id "history-agent"])` and gets `seon.render/render-form`.
`situation-form` is declared on the derived SITUATION map, not on the agent's
attribute map — `resources/seon/schemas/seon.agent.edn:122-126`:

```clojure
                     [:map
                      {:seon.render/ai
                       seon.cluster.agent/render-situation-ai,
                       :seon.render/form
                       seon.cluster.agent/situation-form}
                      [:seon.agent/id :seon.agent/id]
```

and the fixture's filtered canonical schema set
(`test/seon/render/history_test.clj:33-43`) contains no agent schema key at
all, so the schema stage has nothing to find and the floor answers. Two
assertions.

**Case 2 — the namespace's `:seon.ns/requires` floor.**
`test/seon/render/history_test.clj:96-103` expects
`seon.render/render-form` with a `db/q` listing and gets
`seon.render.ns/namespace-form` with `(seon.db/pull '[*] [:seon.ns/name
fixture.history])`. `:seon.ns/requires` is declared at
`resources/seon/schemas/seon.ns.edn:23` (`{:optional true}`) and `:32`
(`[:set :seon.db/ref]`) with NO render properties, while `namespace-form` is
declared on the namespace entity map at `resources/seon/schemas/seon.ns.edn:15`.
The fixture's namespace is transacted as `{:seon.ns/name 'fixture.history}`
(`test/seon/render/history_test.clj:46`) — it has no requires — so
`(contains? value :seon.ns/requires)` is false, `attribute-value?` is false,
`attribute-declared-producers` returns nil, and `declared-producer` falls
through to the namespace pair. Two assertions.

**The four that are expectation drift, not a ruling** (per the issue, each
with a declaration already in the tree): `(my.message/read
#:my.message{:id …})` since `105acca21` moved `my` APIs to request maps
(`src/seon/render/transcript.clj:790`); two assertions on `inbox-form`
reading `:seon.message/_inbox` since `ae0e54841`; and `(selected message
:seon.message/to)` — `inbox-form` is declared on `:seon.message/inbox`
(`resources/seon/schemas/seon.message.edn:148-152`) while `:seon.message/to`
(`:70-73`) has no pair, so the generic pull is the declared answer and the
fixture seeds the wrong schema key. That is 4 drift + 4 needing the ruling
(cases 1 and 2, two assertions each); the ninth assertion is the
`namespace-form` shape check, which is green.

### (c) Options

1. **No fallback: an attribute-scoped request that finds no declared pair
   resolves to the generic printer** (simplest viable). Drop the
   `attribute-value?` escape for the OWNING entity — keep it only for a
   neighbour the walk reached, which is distinguishable because the walk
   stamps the attribute on a value it did not pull from that entity.
   *Guarantee:* `:seon.render.walk/attribute` means one thing; asking for an
   attribute never silently answers about the entity. *Cost:* absent optional
   attributes render as the generic `db/q` listing, which is exactly what
   `test/seon/render/history_test.clj:100-102` asserts. *What we give up:*
   convenient-looking output for an attribute nobody declared a pair for.
2. **Attribute-scoped requests fall back to the nearest declared pair**
   (today's behaviour, ruled rather than accidental). *Guarantee:* the reader
   always gets a declared, curated form when one exists anywhere up the
   chain. *Cost:* update four assertions. *What we give up:* the ability to
   ask about one attribute and get an answer about that attribute; the
   distinction between "this attribute has no pair" and "this attribute is
   absent" disappears, and a caller cannot tell which happened.
3. **Declare the missing pairs** (`:seon.agent` entity map, `:seon.ns/requires`)
   and leave selection alone. *Guarantee:* both cases produce curated forms by
   declaration, which is the §2.2 answer — the missing fact is the problem.
   *Cost:* two render functions and their schema declarations, plus the
   fixture must seed the agent schema key. *What we give up:* nothing
   structural, but it answers only these two sightings; the guard's ambiguity
   survives for the next absent attribute.

### (d) Recommendation — option 1, with option 3 alongside for case 1

§2.4: "an unavailable observation is the typed unknown, never absence,
success, or silence." Option 2 is precisely absence answered as success — an
attribute with no declared pair and an attribute absent from the entity both
resolve to the entity's form, and the reader cannot tell which. It is also
the recurring class AGENTS.md names: the check for "does this attribute
declare a pair?" currently reports "yes, here is the entity's" when its
subject is absent. §2.2 says the fix for case 1 is a declaration, not a
selection rule: if an agent pulled `'[*]` should render through a curated
form, declare that pair on the agent's entity map — `situation-form` is
declared on the derived situation map
(`resources/seon/schemas/seon.agent.edn:122-126`) and that is a DIFFERENT
value, so selecting it for the agent's attribute map would be the fallback
lying about which schema it matched.

Concretely: rule option 1, then update the four drift assertions in the same
pass (they each point at a declaration already in the tree), fix the
fixture's schema-key filter to seed `:seon.message/inbox` and the agent
schema key it needs, and — if the owner wants a curated agent form — land it
as a declared pair (option 3) rather than as a selection rule. The issue's
own warning stands: do not update all nine to match current behaviour first,
because that erases the only check pinning this chain.

---

## Vocabulary corrections

Invented or legacy nouns met while verifying the citations above, with the
grounded replacement (AGENTS.md §3 vocabulary table). None were changed in
this read-only pass.

| Sighting | Term met | Grounded replacement |
|---|---|---|
| `resources/seon/schemas/seon.eval.edn:10` | `:seon.cluster.eval/id` is the identity attribute of the `:seon.eval` entity | the [TARGET] evaluation entity's identity; `:seon.eval/id` is what the vocabulary table implies and what a live `seon.eval/of-agent` selector asks for (it was refused in this pass: `Bad entity attribute :seon.eval/id … not defined in current schema`). Either mint the current spelling or record in the table that the identity attribute keeps the legacy family name during the cut. |
| `src/seon/repl.clj:220-221` | `:seon.cluster.eval/comment`, `:seon.cluster.eval/source` in `input-text` | evaluation attributes (`:seon.eval/*`); legacy family per the table's "Legacy `:seon.cluster.eval` … are source references during the owning lane's cut" |
| `src/seon/render/transcript.clj:537` | `undisposed-run-text` | turn, not "run"; and the function is dead — nothing constructs a `::kind :run` entry (constructors are `:message` `:278`, `:eval` `:299`, `:attempt` `:613`) |
| `src/seon/render/transcript.clj:569` | the `:run` entry kind in the entry-text dispatch | same; orphan alongside `:537` |
| `test/seon/test_support.clj:323` | `" Offending row " index ": " (pr-str row)` — a transaction-data entity map called a "row" in an agent-visible refusal | entity map / transaction data. "Row" in this codebase is grounded for program-graph and config declarations; a map in `:tx-data` is an entity map. The bytes reach agents through `seon.db/transact!` refusals (quoted in §6(b)). |
| `test/seon/turn_work_test.clj:111`, `:147`, `:219` | `terminal-receipt!`, "each supplied value as a receipt result", "no receipts" | evaluation / result (table: "Use **evaluation** and **result** in prose, not 'receipt'") |
| `test/seon/turn_work_test.clj:39`, `:68` | `run-id`, `(str run-id "-attempt-0")` | turn id (table: turn; legacy `run`, `seon.cluster.run`) |
| `test/seon/turn_loop_test.clj:1129` | "the last clean receipt and undisposed close commit together" | evaluation |
| `test/seon/concurrency_independence_test.clj:2`, `:8`, `:286` | "verifies every receipt", "Assertions read receipts", `receipt-rows` | evaluations; `receipt-rows` is both legacy nouns in one name |
| `test/seon/turn-test` name in the issue: `recovery-preserves-terminal-receipts-exactly` | "receipts" in a test name | evaluations |
