---
type: research
status: complete
date: 2026-09-08
tags: [research, verification, wake, agent]
---

# Independent verification: listened attributes, answered-by-`:t`, the turn bound

Written by the `verify-listened-attributes` lane. Read end to end before
probing: [AGENTS.md](../../../../AGENTS.md) (through the `CLAUDE.md ->
AGENTS.md` link, same bytes);
[the turn-loop PRD](../plan/agent-record-and-turn-loop-prd-2026-09-07.md) at
r8, §1a and §3 (and §3a);
[the listened-attributes landing note](listened-attributes-landing-2026-09-07.md);
the complete diff `git diff 3b5102c6b..c3ea827db -- src resources` (21 files,
694 insertions);
[the REPL prototype](prototype-turn-loop-in-repl-2026-09-07.md) claims 1 and 4;
and both issues lane 2 filed —
[a turn that dies before replying still answers its wakes](../../../seon/issues/a-turn-that-dies-before-replying-still-answers-its-wakes.md)
and
[two turn backstops fire and the sliding fault channel keeps the wrong one](../../../seon/issues/two-turn-backstops-fire-and-the-sliding-fault-channel-keeps-the-wrong-one.md).

**Headline: the mechanism is real and the landing note's claims reproduce —
and three things it does not claim are broken.** A wake source is genuinely
one schema property with no code change, answered-by-`:t` collapses the double
pay, and the outside anchor works in both directions. But (1) a turn that
never replied — including a source submission — silently consumes every
pending wake, so PRD r8 is stated and NOT implemented, and a source run stores
a reply so r8's own join would not exclude it; (2) a wake declared
`:seon.wake/opens-turn? false` is invisible to EVERY derivation, so a schedule
firing never reaches a context and can never anchor the bound; (3) the turn
bound went from 0.16 ms to 47.5 ms per pass at 2,008 lifetime wakes and grows
linearly with history forever. Every one of the three sets fails silently when
empty, and one of those failures is fail-OPEN on a paid loop.

## Method

Own scratch cluster, nothing shared touched. `tmp/juniper-context-live` and
`default` were never contacted.

```
mkdir -p tmp/verify-wake-root
unset SEON_OPERATOR_EPHEMERAL_OWNER_PID
bin/seon --root tmp/verify-wake-root init            # first start refused: no current-src branch
bin/seon --root tmp/verify-wake-root start verify-wake
```

`:current-src` commit `6aa017ac-2e4f-5cd0-a985-7ee859b7d503`, digest
`4041eb89b37ccb4f41d39117831a3fe5af5901eff659a7e2c469e38867ea3438`, tree at
`c3ea827db`+ (session HEAD `9cd1f9ab4`). Seeded with

```clojure
(do (load-file "docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj")
    (juniper-fixture-2026-09-06/install! "verify-wake"))
```

Every probe ran through MCP `eval_clj`, `jvm` mode, root
`/Users/sean/src/seon/tmp/verify-wake-root`, cluster `verify-wake`, custody
`(def C (seon.operator/connection "verify-wake"))`.

**No provider credential is present in this JVM** (`DEEPSEEK_API_KEY`,
`SEON_DEEPSEEK_API_KEY`, `META_MODEL_API_KEY` all absent), so every turn the
cluster opened failed at the provider and no paid call was made. That is also
why probe 2 could measure the reply-less case at all.

## 1. The derived sets — HOLDS, with a boot-scoped caveat the note understates

### 1a. The three derivations agree with the landing note, verbatim

```clojure
(let [D @C] {:wake     (seon.cluster.wake/wake-attributes D)
             :opening  (seon.cluster.wake/turn-opening-attributes D)
             :inside   (seon.cluster.wake/inside-attributes D)})
;; => {:wake    #{:seon.effect/to :seon.cluster.message/to
;;                :seon.error/steward :seon.schedule.fire/agent}
;;     :opening #{:seon.effect/to :seon.cluster.message/to :seon.error/steward}
;;     :inside  #{:seon.effect/to :seon.cluster.message/about
;;                :seon.error/steward :seon.cluster.message/from}}
```

Identical to the note's three sets on an independently built cluster.

### 1b. A synthetic listened attribute, declared and nothing else — HOLDS

`:verify.wake/nudge`, a ref attribute whose ONLY distinguishing content is the
property, put through the same population path production boot uses
(`seon.schema/call-with-forms` over `packaged-forms` plus this one entry, then
`seon.cluster/declaration-changes` and `seon.cluster/schema-row-changes`):

```clojure
[:and {:description "Synthetic verification wake: …"
       :seon.db/index true
       :seon.wake/listen true
       :seon.wake/opens-turn? true}
 :seon.db/ref]

;; the population produced EXACTLY one declaration and one row:
{:declaration-changes [{:db/ident :verify.wake/nudge :db/valueType :db.type/ref}]
 :row-change-keys     [:verify.wake/nudge]}
```

After transacting those two, with **zero lines of code changed**:

```clojure
{:wake    #{… :verify.wake/nudge}
 :opening #{… :verify.wake/nudge}
 :inside  #{…}}          ; correctly absent — no :seon.wake/inside declared
```

A freshly registered `route!` closing over the new set delivered the wake, and
the work derivation saw it:

```clojure
(seon.db/transact! C {:tx-data [{:db/id "nudge-1" :verify.wake/nudge JUN}]})
;; => {:tx-t 536871195
;;     :probe-mailbox-offers 1                       ; the proc mailbox offer
;;     :unanswered-after [{:db/id 34764
;;                         :seon.wake/attribute :verify.wake/nudge
;;                         :seon.wake/t 536871195}]
;;     :next-work {:seon.cluster.work/situation :open
;;                 :seon.cluster.agent/id "juniper"}}
```

**Verdict: HOLDS.** The property lift is general; declaring a wake source is
one schema property.

### 1c. The caveat, measured: the router's set is frozen at boot, and the work derivation's is not

The live cluster's own listener, registered before the row existed, ignored
that same datom completely. Six seconds later:

```clojure
{:runs ["e469e6d3-…" "bootstrap:juniper"]      ; unchanged
 :unanswered [{:seon.wake/attribute :verify.wake/nudge :seon.wake/t 536871195}]}
```

An openable wake that nothing will ever deliver, with no refusal, no fault and
no log line. After `bin/seon --root tmp/verify-wake-root stop verify-wake`
then `start`, the same pending wake opened a real turn
(`4b0dcc22-0018-4f40-92a9-9a0927b67e75`) and derived answered.

The note documents "a cluster learns a new wake source at its next boot" as an
intended consequence. What it does not say is that **`route!` and
`seon.cluster.work` now hold two different answers to the same question** —
one frozen at registration, one re-derived per call — which is the pre-read
shape the owner law names. It is benign only while nothing changes a schema
row between boot and shutdown, and the development cluster's whole point is
that schema rows change in place. Ranked as a friction, not a blocker, because
the divergence is one-directional (the derivation over-reports; nothing acts
on a wake it cannot see).

## 2. Answered by `:t` — HOLDS for messages, and answers things no model ever saw

### 2a. One message: unanswered, a run opens, answered — HOLDS

```clojure
;; message transacted
{:message-t 536871452 :unanswered-before ["verify/source-race/1"]}
;; after the loop's turn a4111c35-…
{:unanswered-after []}
```

### 2b. Two messages in ONE transaction are ONE turn — HOLDS, double pay gone

```clojure
{:tx-t 536871207
 :both-same-tx [["verify/pair/a" 536871207] ["verify/pair/b" 536871207]]
 :unanswered-immediately ["verify/pair/a" "verify/pair/b"]}
;; eight seconds later
{:new-runs ["eb4add1c-ddf7-43df-80f4-07ed67c55d0f"] :new-run-count 1
 :unanswered-now [] :latest-turn-t 536871208}
```

One transaction, two wakes, **one** turn. The prototype measured the old model
paying twice on exactly this shape.

### 2c. A message mid-turn opens the NEXT turn — HOLDS

With an open turn at `:t 536871639` and a message at `:t 536871640`:

```clojure
{:during     {:unanswered ["verify/midturn/1"]}
 :after-close {:unanswered []
               :next-work {:seon.cluster.work/situation :call
                           :seon.cluster.run/id "e531c97d-…"}}}   ; a NEW turn
```

### 2d. PRD r8 — "only a turn holding a reply answers" — is NOT implemented. MEASURED.

This is the filed hole, and it is worse than the issue states.

**Reply-less turns answer.** In probe 2b, both wakes were answered by
`eb4add1c-…`, and at that moment the agent had stored **no reply at all**:

```clojure
(seon.db/q '[:find ?id ?reply :where … [?r :seon.cluster.run/reply ?reply]] D)
;; => []            ; zero turns hold a reply on this cluster — no credential
```

Every turn on this cluster failed at the provider, and every one of them
consumed the wakes that opened it.

**A source submission answers a pending message, silently.** Isolated with no
race, by putting the agent at its cap (`:seon.config.run/max-episode-runs 1`)
so the loop cannot open, then transacting an INSIDE message so it sits
deferred:

```clojure
;; before the submission
{:episode-runs 1 :unanswered [["verify/inside-deferred/1"
                              :seon.cluster.message/to 536871503]]
 :deferred [{:seon.cluster.message/id "verify/inside-deferred/1"}]
 :next-work nil}

(seon.cluster.agent/submit-source! {… :seon.cluster.reply/text "(+ 3 3)"})

;; after
{:source-run {:seon.cluster.run/id "source:e8b9d0a1-…"} :source-run-t 536871504
 :unanswered-after [] :deferred-after [] :episode-runs 2}
```

The message was consumed by a turn that had nothing to do with it, and the
bound was spent as well. Nothing refused, nothing logged.

**And r8's proposed fix, as written, would not close the source case.** A
source run DOES store `:seon.cluster.run/reply` — the submitted source text:

```clojure
{:source-run-t 536871204 :source-run-has-reply "(+ 1 1)"}
```

So a join `[?turn :seon.turn/reply _]` admits source submissions unchanged.
Whatever step 3 lands, "holds a reply" has to mean "holds a MODEL reply", and
today's attribute cannot tell the two apart.

**Verdict: REFUTED as implemented; the PRD r8 sentence describes an intention,
not the tree.** Blocker before step 3.

## 3. The outside anchor and the turn bound — HOLDS both directions

One transaction each, on the same live agent:

```clojure
{:anchor-before 536871452
 :inside-message-t 536871508  :anchor-after-inside  536871452  :inside-moved-anchor?  false
 :outside-message-t 536871511 :anchor-after-outside 536871511  :outside-moved-anchor? true
 :episode-runs-after-outside 0}
```

A human message (no `from`, no `about`) refills the bound; the agent's own
message does not. The fault probe (§4) is the third case: an inside wake on a
different family, anchor unmoved (`536871511` → `536871511`).

**Comparison with the pre-lane derivation.** Rather than a throwaway worktree
at `3b5102c6b` — which would have to be measured on DIFFERENT data — the
pre-lane `episode-runs` body was re-expressed verbatim from the diff and run
against the SAME database value. `:seon.cluster.run/trigger` is still written
as provenance, so the old query still resolves:

```clojure
{:new {:anchor 536871452 :runs 2}
 :old {:anchor 536871453 :runs 2}}
```

Same count; anchors one transaction apart — exactly the divergence the
prototype's ranked item 7 named (the wake's own `:t` versus the answering
turn's). The `:episode-runs-after-outside 0` above is that divergence made
visible: the new form refills the whole budget the instant an outside wake
ARRIVES, before any turn has answered it.

## 4. `:seon.error/steward` — the routing HOLDS; the fault seam records nothing to route

### 4a. A fault whose function has a steward wakes that agent — HOLDS

Steward declared on `seon.cluster.work` → juniper, then a fault carrying
`:seon.instrument/fn`:

```clojure
(seon.error/steward-call D {:seon.error/id "verify-fault-1"
                            :seon.instrument/fn "seon.cluster.work/resume-or-generate"})
;; => [{:db/id "seon.error/fact-verify-fault-1" :seon.error/steward 34417}]
```

Committed through the same `[:db.fn/call #'seon.error/steward-call …]` shape
`commit-tx` uses:

```clojure
{:fault-t 536871621
 :steward-datom "juniper"
 :unanswered-immediately [[:seon.error/steward 536871621]]
 :new-runs ["8d439726-9f6c-467b-af01-29e20d91369b"]     ; the turn it woke
 :anchor-before 536871511 :anchor-after 536871511}      ; INSIDE: bound not refilled
```

### 4b. A fault carrying no `:seon.instrument/fn` — nothing, silently

```clojure
(seon.error/steward-call D {:seon.error/id "verify-fault-2"})     ; => []
;; committed anyway; then:
{:fault4-steward nil :fault4-mailbox-offers 0 :new-runs []}
```

No datom, no offer, no turn, no diagnostic. And the live population is not a
minority case — it is **all of it**:

```clojure
{:fault-count 23 :with-instrument-fn 0 :with-steward 0
 :classes {:seon.await/backstop-fired 1 :seon.cluster.prompt/refused 1
           :seon.error/unclassified 1 :seon.ai/no-credential 1}}
```

**23 of 23 faults on a live cluster carry no failing function, so
`:seon.error/steward` routes exactly nothing in production today.** That
reproduces the branch/SCI/wake research's 14-of-14 and confirms the landing
note's own caveat with a second, larger sample. The mechanism is correct and
currently inert.

## 5. `:seon.wake/opens-turn? false` — REFUTED. The wake reaches NO derivation.

The claim under test is the PRD's: a firing "still routes and still reaches
the agent's next context; it never causes a model call on its own".

```clojure
(seon.db/transact! C {:tx-data [{:seon.schedule.fire/id "verify/fire/1"
                                 :seon.schedule.fire/agent JUN
                                 :seon.schedule.fire/nominal-at (java.util.Date.)
                                 :seon.schedule.fire/observed-at (java.util.Date.)}]})
;; => {:fire-t 536871625
;;     :fire-mailbox-offers 1          ; it DOES route
;;     :unanswered-default []          ; expected
;;     :fire-in-any? FALSE             ; NOT expected
;;     :new-runs []}                   ; correctly opens no turn
```

The second half fails. `unanswered-wakes` binds its attribute collection to
`(wake/turn-opening-attributes db)` **unconditionally** — the
`:seon.cluster.work/answered? :any` request only moves `since` to `-1`; it
does not widen the attribute set (`src/seon/cluster/work.clj`,
`unanswered-wakes`). So:

- a firing is invisible to `unanswered-wakes` in both modes;
- it is therefore invisible to `outside-wake-t`, which is built on the `:any`
  call — **a schedule firing can never anchor the turn bound**, contradicting
  §3's "a human message and a firing are not [inside]";
- it is invisible to `deferred-triggers` and to `next-agent-work`;
- nothing else in the diff reads firings either. The only effect of
  `:seon.schedule.fire/agent` today is one mailbox offer that provokes a pass
  which finds nothing.

Confirmed on the same data: juniper's complete `:any` wake list contains the
`verify.wake/nudge`, ten messages and the steward ref — and no firing.

**Verdict: REFUTED.** The declaration exists, the routing exists, the
consumption does not. This is the project's named failure class: the absence
of a firing in every derivation reads exactly like health.

## 6. The two deleted hand lists — HOLDS

```
rg ':seon\.cluster\.message/to|:seon\.effect/to|:seon\.error/steward|
    :seon\.schedule\.fire/agent|:seon\.cluster\.agent/id' src/seon/cluster/wake.clj
```

Two hits, both inside the `inside-attributes` docstring (lines 145-146). No
wake-source attribute name appears in executable position anywhere in
`wake.clj`; `route!`'s `case` and `wake-attributes`' literal set are both gone.
The C2 disjointness counterpart (`seon.cluster.loop/committed-attributes`)
remains a computed set, so the property still compares two derivations.

## 7. `:seon.cluster.message/to` carries no `:db/index` — measured, and it does not matter

Confirmed asymmetry: `:seon.effect/to`, `:seon.error/steward`,
`:seon.schedule.fire/agent`, `:seon.cluster.message/from` and
`:seon.cluster.message/about` all carry `:seon.db/index true`;
`:seon.cluster.message/to` carries none.

1,000 messages plus 1,000 datoms on the indexed synthetic attribute, same
agent, same shape of query (attribute and value bound, entity free), 20
iterations after warmup:

| query | ms/call |
|---|---|
| `[?w :seon.cluster.message/to ?agent ?tx]` (UNINDEXED, 1,008 datoms) | **0.051** |
| `[?w :verify.wake/nudge ?agent ?tx]` (indexed, 1,000 datoms) | **0.051** |

**Verdict: the missing index costs nothing at this size.** Do not add it on
speculation; the number that matters is the next one.

### 7a. What the same measurement DID find: the turn bound is now 290× slower and grows forever

Same database value, same agent:

| derivation | ms/call | note |
|---|---|---|
| `unanswered-wakes` (unanswered only, 1,000 pending) | 20.1 | one `db/pull` per wake |
| `unanswered-wakes` `:any` (2,008 lifetime wakes) | 46.5 | pulls EVERY wake ever |
| `outside-wake-t` | 49.3 | = the `:any` call + `inside-wakes` |
| `episode-runs` (new) | **47.5** | calls `outside-wake-t` |
| `episode-runs` (pre-lane, trigger-anchored, same data) | **0.163** | |

`episode-runs` runs on every turn-proc pass, and `outside-wake-t` asks for
`{:seon.cluster.work/answered? :any}` — every wake the agent has ever
received, pulled one by one, forever. The old derivation was bounded by the
agent's RUN count; the new one is unbounded in the agent's LIFETIME WAKE
count. The prototype's "marginally cheaper, 0.115 vs 0.134 ms" was measured on
an agent with three wakes; at 2,008 it is 47.5 vs 0.163.

By [FAST BY DEFAULT — SLOW IS A BUG](../../../../CLAUDE.md) this is a defect
on sight, and by AGENTS.md §2.1 it is the fetch-at-call-time shape: the
`db/pull` of message id/at/ordinal is done for every wake in history when the
caller wants one integer. Ranked as a blocker before step 3, because step 3
renames this code and is the cheap moment to fix the shape.

## 8. The new red — `agent-test/disarm-has-a-declared-loud-turn-completion-backstop`

Read-only diagnosis; I made no edit.

The test (`test/seon/cluster/agent_test.clj:960-1074`) asserts that the value
`disarm!` throws IS the value on the agent's fault channel, including
`:seon.cluster.run/id`. Two backstop failures are now constructed for one
turn; the fault channel is `(sliding-buffer 1)`; it keeps the second, whose
`held-run-id` re-derivation returned `nil` because custody was already
released.

Two causes, and the issue names only the second:

1. **The deleted self-rewake suppressor.** The diff removes
   `(not (:seon.cluster.loop/trigger-already-answered report))` from the guard
   at `src/seon/cluster/agent.clj:619`, so after a turn transform returns,
   `work/more-agent-work?` is consulted unconditionally and can offer a
   self-rewake where a refused open previously suppressed it. That is what
   produces a SECOND turn pass — and therefore a second armed backstop —
   while the first turn is still blocked in the provider. The deletion was
   correct in intent (the refusal class is gone) but it also deleted a
   suppression whose job nothing replaced.
2. **`offer-turn-backstop-fault!` re-derives its subject at fire time**
   (`src/seon/cluster/agent.clj:482-503`): `held-run-id` is queried when the
   bound FIRES, not carried from when it was ARMED. That is the owner law's
   pre-read: the seam re-decides something its authority already decided, and
   the answer legitimately changes in between. `arm-turn-completion-backstop!`
   knows the run; the fault should carry it.

The same shape appears a few lines below at `agent.clj:626-628`, where the
proc's returned state re-derives `held-run-id` rather than carrying it.

**Verdict: a real defect, correctly attributed by lane 2, with one more cause
than the issue records.** The fix is to carry the armed run identity into the
fault AND to decide whether the second pass should exist; both belong to the
owner of `seon.cluster.agent`.

## 9. Gates

Run on this tree, isolated operator root, `bin/test` (no `--root` needed —
the runner makes its own).

### `bin/test seon.cluster.wake-test seon.cluster.work-test seon.cluster.loop-test seon.cluster.agent-test seon.bootstrap-test seon.schedule-test`

**91 tests, 408 assertions, 30 failures, 21 errors, 23 failing tests.** Every
one was confirmed reproducible by the runner's own isolated re-run, so none is
a pool-interaction artefact. Retained root
`tmp/test-runs/run.qhRA9A`.

| namespace | landing note | this run | agreement |
|---|---|---|---|
| `seon.cluster.wake-test` | 0 | **0** | yes |
| `seon.cluster.work-test` | 0 | **0** | yes |
| `seon.bootstrap-test` | 0 | **0** | yes |
| `seon.schedule-test` | 1 | **1** | yes |
| `seon.cluster.loop-test` | 5 | **5** | yes |
| `seon.cluster.agent-test` | 17 (16 inherited + 1 new) | **17** | yes |

**Name for name, this run reproduces the landing note's table exactly** for
every namespace in the selection. The five `loop-test` reds are the ones it
lists; the `schedule-test` red is
`returned-and-thrown-handler-errors-use-the-existing-root-wake`, whose eight
assertion failures all trace to one projection refusal at `schedule.clj:481`
→ `seon.schema/call_with_projection_state` (`schema.clj:948`), matching the
note's "`:seon.error/data-size` refused by its own long schema".

The `agent-test` count of 17 is the note's "one new red, filed". The 17:

```
answered-trigger-is-a-terminal-work-verdict
disarm-does-not-depend-on-the-turn-proc-starting
disarm-drops-the-route-before-closing-it
disarm-has-a-declared-loud-turn-completion-backstop          <- the new one
episode-cap-refusal-test
fenced-is-the-derived-quarantine-state
graph-definition-inherits-the-cluster-io-executor
install-gate-failure-settles-commits-and-cancels-the-turn-backstop
n-agent-parallel-turns-property
park-wake-test
pause-during-in-flight-call-test
prompt-refusal-answers-its-trigger-once
prompt-request-without-context-channel-is-a-flat-refusal
restamp-recovery-test
routing-conservation-waits-for-terminal-evidence
system-source-submission-uses-the-ordinary-durable-run
wake-routing-conservation-property
```

The new red's two assertions failed exactly as lane 2 recorded them, and the
`nil` is visible in the diff between the thrown value and the channel value:

```text
FAIL in (disarm-has-a-declared-loud-turn-completion-backstop) (agent_test.clj:1046)
expected: (= failure (:clojure.core.async.flow/ex fault))
  actual: … thrown failure carries
          :seon.cluster.run/id "ab633ad4-cb7b-459f-ab7e-09014c4c0b34"

FAIL in (disarm-has-a-declared-loud-turn-completion-backstop) (agent_test.clj:1048)
expected: (= run-id (:seon.cluster.run/id fault))
  actual: (not (= "ab633ad4-cb7b-459f-ab7e-09014c4c0b34" nil))
```

### `bin/test --platform`

**GREEN — 73 tests, 398 assertions, 0 failures, 0 errors.** Identical to the
landing note's figure, test count and assertion count. The declared platform
tier is intact; nothing in this change touched it.

**Gate verdict: the landing note's gate table is honest.** It neither
understated nor overstated a single namespace. The one thing worth adding is
scale: `seon.cluster.agent-test` sits at 17 red of 32, so this wave is landing
onto an already-red namespace, and the new red is the 17th rather than the
first. Twenty-two of the twenty-three are inherited and pre-date the diff.

## 10. Absence-as-health sweep of the diff

Every `when`/`or`/`if-let` over the three derived sets was probed live by
redefining the derivation to return `#{}`. All three fail silently; one fails
open.

| seam | set empty ⇒ | reported? |
|---|---|---|
| `route!`'s `(contains? listened attribute)` | **zero offers, ever** — nothing is routed and every agent is permanently idle | **no**: probe registered a listener with an empty set, transacted a message, received `0` offers, and NO refusal or fault was constructed |
| `unanswered-wakes`' `[?attribute ...]` binding | `unanswered` 0, `next-agent-work` nil, `deferred-triggers` 0 for every agent | **no** |
| `outside-wake-t` via the same binding | anchor `0`, so `episode-runs` counts EVERY turn the agent ever took (measured 1 → 11) — the bound reads as spent | **no** |
| `inside-wakes`' `[?inside ...]` binding | every wake counts as OUTSIDE, so the anchor jumps to the newest wake and the bound REFILLS (measured on juniper: anchor `536871511` → `536871621`, `episode-runs` 2 → 1) | **no** — and this direction is **fail-OPEN on a paid model loop** |

The last row is the serious one. `max-episode-runs` being absent is
deliberately fail-CLOSED (`episode-capped?` returns true on `nil`), but
`inside-attributes` being empty — one missing schema resource, one
unpopulated branch, one fork from before this commit — is fail-OPEN in the
same mechanism. The two halves of the bound disagree about which way absence
points.

`inside-wakes`' own `(if (empty? ids) #{} …)` guard protects the WAKE ids, not
the attribute set, so it does not cover this.

One smaller seam: `next-agent-work` now emits
`(some :seon.cluster.message/id wakes)`, so a turn opened purely by a fault or
an effect settlement carries no `:seon.cluster.message/id`. Consumers that
keyed on the trigger message (the page's "current message", the context's
current-trigger read) will see absence where they used to see a message. Not
probed downstream; named as a friction for step 3, which edits those readers
anyway.

## Ranked findings

### Blockers before step 3

1. **A turn that never held a model reply still answers every wake it opened
   over — and so does a source submission** (§2d). PRD r8 states the rule and
   the tree does not implement it; worse, a source run stores
   `:seon.cluster.run/reply`, so r8's own reply-join would not exclude it.
   Step 3 must define "holds a MODEL reply" as a distinguishable fact.
   Extends [the filed issue](../../../seon/issues/a-turn-that-dies-before-replying-still-answers-its-wakes.md).
2. **`:seon.wake/opens-turn? false` wakes reach no derivation at all** (§5).
   `unanswered-wakes` binds `turn-opening-attributes` unconditionally in both
   modes, so a schedule firing never surfaces in a context and can never
   anchor the bound — contradicting PRD §3 in both directions. Either widen
   the binding for `:any` (and give the context a reader), or delete
   `:seon.wake/opens-turn?` and the `:seon.schedule.fire/agent` declaration
   rather than ship a property with no consumer.
3. **The turn bound is 290× slower and unbounded in history** (§7a): 47.5 ms
   per turn-proc pass at 2,008 lifetime wakes versus 0.163 ms for the
   derivation it replaced, because `outside-wake-t` pulls every wake the agent
   has ever received. Fix the shape while step 3 is renaming this code.
4. **`inside-attributes` empty is fail-OPEN on a paid loop** (§10). The other
   half of the same bound is deliberately fail-closed. Make the absence of a
   declaration population a typed refusal, not a budget refill.

### Frictions

5. **`route!`'s set is frozen at boot while every other consumer re-derives**
   (§1c). Two answers to one question; the development cluster is exactly
   where a schema row changes in place. A live wake that nothing delivers
   produces no diagnostic.
6. **`:seon.error/steward` routes nothing in production** (§4b): 23 of 23
   faults on a live cluster carry no `:seon.instrument/fn`. The landing note
   says this; the second sample confirms the mechanism is inert until the
   fault seam records the failing function.
7. **The backstop red has a second cause the issue does not name** (§8): the
   deleted `trigger-already-answered` guard at `agent.clj:619` is what allows
   the second turn pass; the re-derived `held-run-id` is what makes the two
   firings describe different worlds.
8. **`next-agent-work` may now carry no `:seon.cluster.message/id`** (§10) for
   a fault- or effect-opened turn; the trigger readers step 3 must edit assume
   one.
9. **`:seon.cluster.message/to` has no `:db/index` and does not need one**
   (§7): 0.051 ms indexed and unindexed at 1,000 datoms. The prototype's
   ranked item 11 can be closed as measured-and-immaterial rather than left
   open.

### Agreement — re-proven independently

- The three derived sets, verbatim (§1a).
- A new wake source is one schema property, no code change: one declaration,
  one row, routed and derived (§1b).
- Two wakes in one transaction are one turn; the double pay is gone (§2b).
- A message mid-turn opens the next turn (§2c).
- The outside anchor refills on a human message and not on the agent's own
  (§3), and agrees with the pre-lane derivation's COUNT while differing by
  one transaction in its ANCHOR, exactly as the prototype predicted.
- `steward-call` resolves fn → ns → steward inside the committing transaction
  and wakes the steward as an INSIDE wake (§4a).
- Both hand lists are gone from `wake.clj` (§6).
