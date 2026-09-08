---
type: research
status: complete
date: 2026-09-07
tags: [research, prototype, agent, wake]
---

# REPL prototype of the turn-loop PRD's claims

**Headline: the model works, and one number in it is wrong.** Answered-by-`:t`
agrees with today's trigger derivation, collapses two wakes in one transaction
into one paid call, and needs no stored reference — **but only when the basis is
the opening TRANSACTION's `:t`, not `:seon.cluster.run/opening-commit-id`**,
which records the pre-open database and is off by one. Under the PRD's stored
basis a fresh agent's opening wake is unanswerable forever. With the amendment,
byte identity reproduced a real 345,439-character prompt EXACTLY, and the basis
itself stops being stored: it is the `:t` of the turn's own identity datom.

Read end to end before probing: `AGENTS.md` (through the `CLAUDE.md ->
AGENTS.md` link, same bytes); the PRD
[agent-record-and-turn-loop-prd-2026-09-07.md](../plan/agent-record-and-turn-loop-prd-2026-09-07.md)
at r5, commit `354aac38e2730167cbc74819f6691f21b94d4964`; both reviews
([opus](prd-review-turn-loop-opus-2026-09-07.md),
[astra](prd-review-turn-loop-astra-2026-09-07.md));
`.agents/skills/datahike/SKILL.md` and `.agents/skills/repl/SKILL.md`.

## Method

Own scratch cluster, no shared cluster touched:

```
mkdir -p tmp/prototype-root
unset SEON_OPERATOR_EPHEMERAL_OWNER_PID
bin/seon --root tmp/prototype-root init          # first start refused: no current-src branch
bin/seon --root tmp/prototype-root start prototype
bin/seon --root tmp/prototype-root start prototype-b   # claim 6 only
```

`:current-src` commit `6a9f62a8-2540-5bcc-b178-765fa93105f1`, digest
`fab07788020a8f96d83266f87ad75623b263aac7366805d5f54b6608e4a303a9`.
Every probe below ran through MCP `eval_clj`, `jvm` mode, root
`/Users/sean/src/seon/tmp/prototype-root`, cluster `prototype`, with
`(def C (seon.operator/connection "prototype"))` for custody. Seeded with

```clojure
(do (load-file "docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj")
    (juniper-fixture-2026-09-06/install! "prototype"))
;; => {:error nil, :keys (:seon.cluster.agent/id :my.plan/steps :my.plan/ready
;;                        :my.plan/blocked :my.plan/recent-completions :my.plan/current-step)}
```

**Read the numbers with this caveat.** The cluster's own turn loop kept running
while I probed: it opened runs, failed at the provider, and advanced the basis.
Every claim below is therefore pinned to an explicit `as-of` or to a `:db-after`
from the probe's own transaction, never to "the live basis at the time".

Seeded state (before any of my transactions):

| | value |
|---|---|
| wakes on `:seon.cluster.message/to` for juniper (eid, `:t`) | `33966 → 536870937`, `33973 → 536870939`, `33974 → 536870939` |
| runs (eid, id, identity-datom `:t`) | `33967 bootstrap:juniper 536870937`, `34028 7ba80f99… 536870949`, `34087 0259dae9… 536870983` |
| `opening-commit-id` `:t` per run (`d/commit-as-db` → `:max-tx`) | `536870921`, `536870948`, `536870981` |
| `:seon.cluster.run/trigger` refs | `33967→33966`, `34028→33973`, `34087→33974` |

Note `33973` and `33974` share `:t 536870939` — the fixture transacts both
messages in ONE transaction — and today's loop opened TWO runs for them.

## Claim 1 — §3 answered by `:t`

### 1(a) agreement with today's derivation — HOLDS, under the amended basis

```clojure
;; at the final seeded state, before my own transactions
{:answered-by-trigger-today        #{33974 33973 33966}
 :unanswered-by-work-ns            []        ; seon.cluster.work/unanswered-triggers
 :answered-by-stored-basis         #{33974 33973 33966}
 :unanswered-by-stored-basis       #{}
 :answered-by-creation-tx          #{33974 33973 33966}}
```

Aggregate agreement, three ways. But per turn the two candidate bases disagree,
and that is where the model is decided (1d).

### 1(b) a new wake after the latest basis — HOLDS

```clojure
(seon.db/transact! C {:tx-data [{:seon.cluster.message/id "prototype/new-wake/1"
                                 :seon.cluster.message/to [:seon.cluster.agent/id "juniper"]
                                 :seon.cluster.message/content "a wake asserted after the latest turn's basis"
                                 :seon.cluster.message/at (java.util.Date.)}]})
;; => {:new-basis 536871215
;;     :latest-stored-basis 536870981
;;     :unanswered-by-t [[33962 536871215]]
;;     :unanswered-triggers-today ["prototype/new-wake/1"]}
```

Both models say unanswered. No stored reference was needed.

### 1(c) two wakes in ONE transaction are one answer — HOLDS, and deletes a live double-pay

```clojure
;; two messages, one transact!
;; => {:tx-of-both 536871278 :both-same-tx? true
;;     :pair [["prototype/pair/a" 536871278] ["prototype/pair/b" 536871278]]
;;     :unanswered-by-t-with-basis (dec t) [["prototype/pair/b"] ["prototype/pair/a"]]
;;     :unanswered-by-t-with-basis t       []
;;     :unanswered-triggers-today ["prototype/pair/a" "prototype/pair/b"]}
```

One basis at or after their shared `:t` answers both; today's trigger model
still reports two. The seeded data shows this is not hypothetical: at
`(d/as-of db 536870981)` — the database run `34087` opened from —

```clojure
{:answered-by-t              #{33974 33973 33966}
 :unanswered-by-t            #{}
 :unanswered-triggers-today  ["design-lab/root-to-juniper/2"]}
```

Run `34087` — a third turn, a paid model call — **would not have opened** under
answered-by-`:t`. Opus B3 is real and answered-by-`:t` dissolves it.

### 1(d) trying to break it — TWO breaks found

**Break 1 (fatal to the PRD as written): a wake in the turn's own opening
transaction.** The bootstrap run `33967` and the wake it answers (`33966`) share
`:t 536870937`, but the run's stored `opening-commit-id` resolves to `:t
536870921`:

```clojure
{:wake 33966 :wake-t 536870937 :its-run-stored-basis 536870921 :wake-t<=basis? false}
```

`(db/commit-id db)` is taken from the database value the opener HELD
(`src/seon/cluster/run.clj:554-555, 910, 1014, 1085`) — the pre-open value. For
every ordinary run the gap is exactly one transaction (`536870949 −
536870948 = 1`); for a run transacted together with its own wake the gap swallows
the wake. Under the PRD's `:seon.turn/basis-t = opening-commit-id`, a fresh
agent's opening wake is **never** answered and the agent turns forever. Verified
a second time on a brand-new agent: `probe-idle`, freshly created, had
`latest-basis-t 536870921` and an unanswered bootstrap wake, so `step` returned
`:reply` for an agent that had already had its opening turn.

**Amendment that fixes it** — see claim 2: the basis must be the opening
TRANSACTION's `:t`, which equals the `:t` of the turn's own identity datom:

```clojure
{:turn-identity-datom-t 536870949 :capture-basis-t 536870949 :equal? true
 :opening-commit-id-t   536870948 :off-by 1
 :bootstrap-run-t 536870937 :bootstrap-wake-t-originally 536870937}   ; 937 <= 937 ✓
```

**Break 2 (survives the amendment): retract-then-reassert re-wakes an answered
wake.**

```clojure
;; retract then re-add :seon.cluster.message/to on already-answered wake 33966
{:wake-33966-tx-before 536870937
 :wake-33966-tx-after  536871392
 :moved? true
 :answered-by-trigger-still? true}
```

The reference model is stable; the `:t` model re-opens a paid turn. Nothing in
the system does this today, and the fix is a rule, not a mechanism: **a wake
datom is asserted once and never re-asserted**. Say it in §3 or it is a defect
waiting for the first reassignment feature.

**`noHistory` — not a break.** No listened attribute carries it:

```clojure
[{:attr :seon.cluster.message/to  :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}
 {:attr :seon.effect/to           :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one :db/index true}
 {:attr :seon.cluster.agent/id    :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
 {:attr :seon.error/agent         :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one :db/index true}]
```

Two side facts from that table: `:seon.cluster.message/to` carries **no
`:db/index`** while the two other wake refs do; and the PRD's list of listened
attributes omits `:seon.effect/to` (astra B6) while `:seon.cluster.agent/id`,
which IS a listened attribute today, is a `:db.type/string` identity — §3's
definition ("a ref attribute whose value is the agent to wake") cannot express
it.

**Verdict: HOLDS WITH TWO AMENDMENTS** — basis = the opening transaction's `:t`
(not `opening-commit-id`); a wake datom is asserted once, never re-asserted.

## Claim 2 — §5 byte identity

**Twice from the same database value: EQUAL.**

```clojure
;; render/acquire-context! twice, same :seon.db/db, cluster ctx + caps + channel
{:a-error nil :b-error nil :a-len 1619 :b-len 1619 :bytes-equal? true}
;; and at (d/as-of db 536870981): {:as-of-repeatable? true}
```

**Against a real stored capture: EXACTLY EQUAL, at the right basis.** Capture
`7ba80f99-…-context-536870949` for juniper's run `7ba80f99…`,
`:seon.context.capture/basis-t 536870949`, `:seon.ai.tokens/characters 345439`:

```clojure
;; re-project at (d/as-of db 536870949) with :seon.render/distance 2
{:stored-chars 345439 :d2-chars 345439
 :d2-equal-stored? true :d2-repeatable? true :first-diff-line nil}
```

The two inputs that decide it, both measured:

| input | wrong value | result |
|---|---|---|
| basis | `536870948` (the stored `opening-commit-id`'s `:t`) | 344,820 chars, **not equal** |
| basis | `536870949` (the opening transaction's `:t`) | 345,439 chars, **equal** |
| distance | `1` | 51,561 chars, differs at line 0 |
| distance | `2` (`prompt.clj:35 default-depth`, `loop.clj:1917`) | **equal** |

So byte identity is **achievable today**, and the PRD's §5 "two things make it
unreachable" (Opus B5: the wall-clock time limit at `render.clj:725-756` and
absence-on-refusal at `walk.clj:764-766`) did **not** fire on this data — no
producer refused. Those remain latent risks worth fixing, not observed blockers.

**Non-determinisms actually met: one, and it is an omitted input, not a clock.**
`:seon.render/distance` is a projection input that changes the bytes by 6.7× and
is stored nowhere. The PRD's "basis + profile ⇒ same bytes" is incomplete: the
reproducible tuple measured here is **(database value at the opening
transaction's `:t`, render distance, result caps, the SCI ctx / adopted program
commit, the render profile)**. No clock, hash-order or entity-id instability was
observed; `:seon.repl/result` entity ids were stable under `as-of` on one store,
as the Opus review predicted.

**Verdict: HOLDS WITH AN AMENDMENT** — the recorded basis must be the opening
transaction's `:t`, and `:seon.render/distance` must be named as a projection
input (recorded, or fixed by declaration).

## Claim 3 — §3 listened attributes as facts

**What a schema row carries today for `:seon.cluster.message/to`:**

```clojure
{:seon.schema/key :seon.cluster.message/to
 :seon.render/ai   seon.cluster.message/render-inbox-ai
 :seon.render/html seon.cluster.message/render-inbox-html
 :seon.render/form seon.render.transcript/inbox-form
 :seon.schema/references [{:db/id 762}] :seon.schema/shape {:db/id 13480}
 :seon.schema/generatable? true :seon.schema.admission/source :core :db/id 1685}
```

**The attribute ENTITY carries only Datahike's own properties** — confirmed
across every attribute in the database, not a sample:

```clojure
:attribute-entity-keys-across-all-582  #{:db/ident :db/valueType :db/cardinality :db/id}
```

**But the Opus review's B4/S3 is REFUTED.** Across all 2,459 schema rows, **25
distinct `seon.*` properties** already reach rows:

```clojure
#{:seon.db/identity :seon.db/attributes :seon.db/index :seon.db/component
  :seon.db/unique :seon.db/no-history? :seon.schema/identity-only
  :seon.schema/identity-projection :seon.schema/generatable?
  :seon.config/dial :seon.config/per-agent :seon.config/optional
  :seon.error/class :seon.error/refusal :seon.error/refusal-shape
  :seon.search/index :seon.print/default :seon.ai/extra-body
  :seon.maintenance/attention-when :seon.maintenance/result-projection
  :seon.render/ai :seon.render/html :seon.render/form :seon.render/units
  :error/message}
```

The lift is **general**, not a hand list: `canonical-schema-rows`
(`src/seon/schema.clj:2919`) merges `storable-properties-in`
(`src/seon/schema/datahike.clj:295-302`) = every namespaced property of the
definition whose property key is itself a storable attribute; the candidate set
is `schema.form/property-attributes` over the whole population
(`schema/datahike.clj:304-322`). `render-declaration-properties`
(`schema.clj:1419-1420`, added `bc1732d26`, 2026-08-12) builds render
DECLARATION rows for producer selection — a different concern; it is not what
lifts properties.

**So "declaring one is one schema property" is TRUE, and the only work is
declaring `:seon.wake/listen` itself.** Today the query refuses, honestly:

```clojure
(seon.db/q '[:find [?k ...] :where [?e :seon.wake/listen true] [?e :seon.schema/key ?k]] D)
;; => {:seon.error/kind :seon.db/invalid-read
;;     :seon.error/message "seon.db/q cannot read uninstalled attribute :seon.wake/listen."}
```

versus today's hand list, which really is a hand list, in two copies
(`wake.clj:78-93` and the `case` at `wake.clj:232-250`):

```clojure
(seon.cluster.wake/wake-attributes)
;; => #{:seon.cluster.agent/id :seon.effect/to :seon.cluster.message/to}
(= :db.type/ref (:db/valueType (pull [:db/ident :seon.cluster.agent/id])))  ; => false
```

**Verdict: HOLDS — and §8 step 2 is smaller than the PRD says.** Declare
`:seon.wake/listen` / `:seon.wake/opens-turn?` as schemas, put them as
properties on the listened attributes, derive `route!`'s set and dispatch from
the query, delete both hand lists. Do NOT "generalise the render-property hand
list" — that list is unrelated. Correct the PRD's §3 and §6 sentences, and
correct §3's definition so it covers `:seon.cluster.agent/id` (a string identity
whose ENTITY is the agent) and `:seon.effect/to`.

## Claim 4 — §1a turns bound derived

At `(d/as-of db 536870981)`, on the seeded agent:

```clojure
{:episode-runs-today 2   :episode-runs-ms 0.134
 :by-t {:anchor 536870937 :turns 2} :by-t-ms 0.115
 :equal? true  :max-episode-runs-dial 100}
```

The `:t` form, in full:

```clojure
(let [anchor (or (seon.db/q '[:find (max ?tx) . :in $ ?id :where
                              [?a :seon.cluster.agent/id ?id]
                              [?m :seon.cluster.message/to ?a ?tx]
                              (not [?m :seon.cluster.message/from _])
                              (not [?m :seon.cluster.message/about _])] db agent-id) 0)]
  (seon.db/q '[:find (count ?r) . :in $ ?id ?since :where
               [?a :seon.cluster.agent/id ?id]
               [?r :seon.cluster.run/agent ?a]
               [?r :seon.cluster.run/id _ ?tx] [(>= ?tx ?since)]] db agent-id anchor))
```

Equal results, and marginally cheaper (0.115 ms vs 0.134 ms per call over 50
iterations, 2 runs). **Neither needs a stored counter, and the derivation is not
a performance problem** — Opus B2's "if the derivation is too slow" premise does
not apply at this size; nothing here justifies astra's stored allowance either.

**But the two anchors are not the same fact.** Today's `episode-runs`
(`work.clj:379-419`) anchors on the tx of the FIRST RUN ANSWERING the last
outside trigger; the `:t` form anchors on the WAKE's own tx. Measured
divergence, live: `{:episode-runs-today 1, :by-t {:turns 0}}` — an outside wake
had arrived with no run since. The `:t` form refills the budget the moment an
outside wake ARRIVES; today's refills when it is ANSWERED, so under the cap the
`:t` form grants budget to turns still answering older work.

**Verdict: HOLDS WITH A DECISION** — one query, equal, fast; but §1a must state
which anchor it means, because they differ and only "answered" preserves
today's behaviour.

## Claim 5 — §7 the two-arm `step`

```clojure
(defn unsettled-eval-in-open-turn [db agent-id]
  (seon.db/q '[:find ?e . :in $ ?id :where
               [?a :seon.cluster.agent/id ?id]
               [?r :seon.cluster.run/agent ?a]
               (not [?r :seon.cluster.run/closed-at _])
               [?e :seon.cluster.eval/run ?r]
               (not [?e :seon.cluster.eval/result-edn _])
               (not [?e :seon.cluster.eval/result-blob _])
               (not [?e :seon.cluster.eval/error _])
               (not [?e :seon.cluster.eval/interrupted-at _])] db agent-id))

(defn step [db agent-id]
  (cond (unsettled-eval-in-open-turn db agent-id)                     :evaluate
        (and (unanswered-wake db agent-id) (turns-left? db agent-id)) :reply
        :else                                                         :idle))
```

Three agents, one database value (`:db-after` of the probe's own transaction,
basis `536871460`):

```clojure
{:step {"probe-eval" :evaluate "probe-wake" :reply "probe-idle" :idle}
 :probe-wake {:latest-basis 536871429 :unanswered-wake 34911 :turns-left? true}}
```

`probe-eval` held an open run `probe-open-run` with evaluation
`probe-open-run:0` and no terminal fact; `probe-wake` had a message and a basis
before it; `probe-idle` had a closed run whose basis was taken at head.

**Verdict: HOLDS.** Two notes. "No terminal fact = not yet evaluated" is a real
query today (`:seon.cluster.eval` has no `settled-at`; my first attempt used one
and `seon.db/q` returned a typed refusal that `cond` read as truthy — a good
argument for the PRD's own §2.4 rule). And the arm is only sound with the
amended basis: with `opening-commit-id`, `probe-idle` read `:reply`.

## Claim 6 — one JVM per operator root

From the `prototype-b` MCP session:

```clojure
{:pid 55586
 :clusters ("prototype" "prototype-b")
 :held-flocks ("/Users/sean/src/seon/tmp/prototype-root/data/store.lock")
 :store-dirs {"prototype"   #:seon.store{:dir ".../tmp/prototype-root/data/store" …}
              "prototype-b" #:seon.store{:dir ".../tmp/prototype-root/data/store" …}}}
```

One pid, both clusters, ONE flock, and the same
`#datahike/Connection[#uuid "cacff788-e4b0-3dc5-8150-4a64fcd2868c"]` object.
Statically: `store-directory` is `<root>/data/store`
(`script/seon/fresh_operator.clj:124-126`) and `lock-file` is
`<canonical store dir>.lock` (`src/seon/cluster/store.clj:125-134` →
`resources/seon/operator/state.clj:148-152`) — **root-scoped, never
cluster-scoped**.

**Verdict: HOLDS.** §1a's "one JVM per cluster … a second live process on any
branch of the root is unrepresentable" is correct in its conclusion; fix the
sentence to say per ROOT. Deleting the process stamp, `claim-call`'s takeover,
`release-call` and the holder-only close is safe on this evidence.

## Ranked: what must change in the PRD before code is written

1. **`:seon.turn/basis-t` is the opening TRANSACTION's `:t`, not
   `opening-commit-id`.** Off by one for every ordinary turn; fatal for a turn
   transacted with its own wake (measured: a fresh agent never answers its
   opening wake). This is the one change that decides claims 1, 2 and 5.
2. **Do not store the basis at all — derive it.** The turn's identity datom's
   `:t` IS the basis: `536870949` from `[?r :seon.cluster.run/id _ ?tx]` equals
   `:seon.context.capture/basis-t 536870949` and is the value that reproduced
   the capture byte for byte. §4a's `:seon.turn/basis-t` is a stored mirror of a
   datom Datahike already stamps — derive-or-die applies to the PRD's own new
   key. `opening-commit-id` is deleted with nothing added.
3. **Name `:seon.render/distance` (and the caps) as projection inputs in §5.**
   Distance 1 vs 2 changed the prompt by 6.7×. "Basis + profile ⇒ same bytes" is
   incomplete and would fail its own regression.
4. **§3 must forbid re-asserting a wake datom.** Retract-then-reassert moved an
   answered wake's `:t` forward and re-opened a paid turn. State it, or the
   first "reassign this message" feature pays twice.
5. **Correct §3's and §6's claim about the render-property hand list.** 25
   `seon.*` properties already reach schema rows through a general mechanism;
   `schema.clj:1419-1420` is unrelated to property lifting. The real work is
   declaring `:seon.wake/listen` and deleting the two wake hand lists
   (`wake.clj:78-93`, `:232-250`). §8 step 2 shrinks accordingly.
6. **Fix §3's definition of a listened attribute and its list.** Today's set is
   `#{:seon.cluster.message/to :seon.effect/to :seon.cluster.agent/id}`;
   `:seon.cluster.agent/id` is a `:db.type/string` identity, not a ref to an
   agent, and `:seon.effect/to` is missing from the PRD entirely (astra B6).
7. **§1a must say which anchor the turn bound uses** — the outside wake's `:t`
   or its answering turn's. They diverge (measured 1 vs 0) and only the latter
   preserves today's behaviour. Both are one query and both are ~0.12 ms; no
   stored counter is justified either way.
8. **Record the double-pay deletion as a benefit with its evidence.** Answered-
   by-`:t` would have prevented run `34087` — a real third paid call on this
   fixture. Opus B3 is dissolved, not fixed.
9. **Downgrade Opus B5 from blocker to latent risk.** Byte identity reproduced
   exactly with the time limit in place; no producer refused. Still fix
   `walk.clj:764-766` (absence on refusal) on §2.4 grounds, but it is not
   blocking this wave.
10. **Fix §1a's "one JVM per cluster" to "per operator root"** — measured, one
    pid and one root-scoped flock for two clusters.
11. **Add `:db/index` to the wake ref when the pending query becomes a scan.**
    `:seon.cluster.message/to` has none today while `:seon.effect/to` and
    `:seon.error/agent` do.

## What this prototype did NOT prove

No provider call succeeded on this cluster, so no turn stored a reply or an
evaluation result: the three-write shape (§1a) and the interrupted-at recovery
stamp are untested here. Claim 2's byte identity was measured on ONE capture
against ONE re-projection; it is a proof of possibility, not a regression. The
`turns-left` divergence was observed, not exercised at the cap.
