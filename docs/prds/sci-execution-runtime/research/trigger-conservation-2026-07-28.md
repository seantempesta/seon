---
type: research
status: active
tags: [research, agent, runtime]
---

# Trigger conservation audit — 2026-07-28

Adversarial audit of the run loop at commit `1b72cc8da`, against the
owner's standard: any loop, duplicate paid call, or lost wake must be
unconstructible by design — a cap is never a safety net for a bug.
REPL evidence: `tmp/trigger-conservation-probe.clj`
(`clojure -M:dev tmp/trigger-conservation-probe.clj`), all three
probes committed with this document.

Verdicts, one line each:

- **(a) no unconsumed-producer cycle: VIOLATED.** A recovered unheld
  planned run whose remaining form carries a disposition is a hot
  livelock — identical `:resume` derived forever, one error fact per
  pass (probe P1; issue
  `docs/seon/issues/unheld-resume-cannot-commit-a-disposition.md` —
  independently discovered and filed the same day by the
  zombie-constructibility audit; one issue, two probes).
- **(b) no trigger answered twice: answeredness itself HOLDS, but the
  paid call it guards is VIOLATED.** A held run whose lease lapses
  before its `:call` pass re-pays the model on every rewake, forever
  (probe P2; escalated onto
  `a-turns-model-work-can-outlive-its-own-run-lease.md`).
- **(c) no trigger lost: PROVEN** at the fact level (answeredness is a
  permanent datom, wake delivery is fenced at every edge), with the
  honest caveat that (a)/(b) wedge an agent so its later triggers stay
  conserved but unanswerable until restart.
- **Episode semantics: DERIVABLE purely from committed facts.** No new
  stored counter is needed; the two-query walk costs ~34 µs on a
  64-run agent history (probe P3).

## 1. Producer/consumer inventory

### 1.1 Trigger producers (everything that commits `:seon.cluster.message/to`)

The wake set is exactly `#{:seon.cluster.message/to}`
(`src/seon/cluster/wake.cljc:75-85`), so trigger production and wake
production are one inventory:

| # | producer | `from` | tx-meta `:seon.db/trigger` | bound |
|---|---|---|---|---|
| 1 | Human/operator transact (REPL, drive scripts; no web POST exists in fresh `src/`) | absent | absent | none needed — chain depth 0 by construction (`src/seon/cluster/message.cljc:99-119`) |
| 2 | Agent delivery rows in a terminal transaction (`src/seon/cluster/loop.cljc:829-899` riding `seon.cluster.message/delivery`, `message.cljc:201-290`) | present | present when rows deliver (`loop.cljc:896-899`) | `:seon.config.message/max-chain`, all-or-nothing per form, FAIL-CLOSED when the dial is absent (`message.cljc:239-260`) |
| 3 | The derived completion reply (`message/reply`, `message.cljc:134-180`) — same delivery path as #2 | present | present | same bound, plus the reply-is-not-a-question terminator (`answering-us?`) |
| 4 | Error-recorder explanation messages (`error/message-tx`, `src/seon/error.clj:683-703`; committed by the loop's `error-tx`/`refused!` `loop.cljc:219-278` and by `commit-fault!` `src/seon/cluster.clj:532-567`) | absent | absent (fault path) / rides the terminal tx's meta on the refusals path | per-signature-per-process recurrence fence, silent past the limit, FAIL-CLOSED when the dial is absent (`error.clj:786-830`); derived message id makes re-commit an upsert, never a double-send (`error.clj:683-692`) |

There are no schedule fires and no self-message channel outside these
in fresh `src/` (`rg`-verified). An agent messaging ITSELF is case #2.

### 1.2 Wake producers and the one consumer

- listener on every committed transaction touching the wake set
  (`wake/listen!`, `wake.cljc:99-135`; registered `cluster.clj:680-688`
  with the fan-out's fault channel);
- the boot prime, one `offer!` AFTER registration
  (`cluster.clj:689`);
- the self-rewake, offered after a turn exactly when `more-work?`
  (`loop.cljc:476-477`).

One consumer: the loop proc's pass (`loop.cljc:450-482`), reading a
`(sliding-buffer 1)` in-port (`cluster.clj:631`). Coalescing is safe
because a wake carries no information and a pass derives ALL work from
one fresh database value.

### 1.3 Trigger consumers (what answers)

- **The `:open` transaction** commits the run + claim with
  `:seon.db/trigger` tx-meta (`loop.cljc:560-576`). That datom IS the
  answer: `unanswered-triggers` is one `not`-clause over transactions
  (`work.cljc:250-277`), and nothing deletes runs, so an answer is
  permanent.
- **The `:call` prompt** re-reads the SAME trigger from the run's
  opening transaction (`message/trigger`, `message.cljc:70-86`;
  `loop.cljc:657-669` — the 7bb7ccbfe fix; one derivation, one owner).
- **The chain walk** (`chain-depth`, `caused-by`) consumes tx-meta
  read-only for the bound and the reply terminator.

## 2. Property (a) — no cycle produces without consuming

### 2.1 Cycles that ARE conserved (choke points)

- **Conversation loop (agent↔agent, or agent→self).** Every hop's
  depth is a walk over already-committed tx-meta ending at a message
  whose transaction named no trigger — the human barrier is free
  (`message.cljc:99-119`); over `max-chain` delivers nothing and
  records a fact (`message.cljc:250-260`); an ABSENT dial delivers
  nothing (`message.cljc:239-248`). The walk itself is total against a
  corrupt database (the `seen` set, `message.cljc:113-119`). The
  delegation bounce is separately terminated by `answering-us?`
  (`message.cljc:170-180`).
- **error → message → wake → turn → error.** An error fact alone wakes
  nobody (`wake.cljc:85`); messages are mailed only for Throwables
  that interrupted a run or at the recurrence limit, and PAST the
  limit nothing is said (`error.clj:796-830`); refused transitions are
  flat values (no `class`) and mail nobody. Runs caused by errors are
  bounded by distinct signatures, not by error count.
- **Self-rewake.** `offer!` into the proc's own sliding-1 port after
  the transform returns cannot recurse (`loop.cljc:28-32, 476-477`),
  and `more-work?` is exactly `next-work` (`work.cljc:198-205`) — the
  rewake never drifts from the derivation. BUT this conservation rests
  on an unstated premise: *every derived work item can commit a
  transition that changes the derivation*. §2.2 breaks that premise.

### 2.2 VIOLATION — the recovered-run fixed point (probe P1)

Filed as
`docs/seon/issues/unheld-resume-cannot-commit-a-disposition.md`
(independently constructed the same day by the zombie-constructibility
audit, which also proved a CRASH-FREE reachability: a `my.run/wait`
that is not the plan's final form releases custody with forms
remaining, so the very next pass is already the unheld `:resume`).

Construction (the designed crash path, no bug required):

1. Process P1 opens+claims run r (trigger tx-meta), freezes a 2-form
   plan, settles form 0, dies. (Crash-walk "kill mid-fold".)
2. Boot recovery on P2 releases the dead custody, epoch unchanged
   (`recover-tx`, `run.cljc:598-639`; `recover-runs!`,
   `cluster.clj:405-446`).
3. `next-work` on P2: open + unheld + planned → `:resume` ordinal 1
   ("committed work we may pick up", `work.cljc:183-185`).
4. The `:resume` branch NEVER CLAIMS (`loop.cljc:767-777` pulls the
   run and uses its current epoch). `receipt-start` commits — receipts
   fence epoch only, not custody (`receipt-run`, `run.cljc:469-478`).
5. Form 1 evaluates to `(my.run/complete …)`. The terminal transaction
   is settle + `close-tx` in ONE commit (`terminal-tx`,
   `loop.cljc:163-205`); `close-call` → `held-run` → refuses
   `::not-the-holder` (`run.cljc:187-209`) → the WHOLE transaction
   aborts, receipt still dangling.
6. Fixed point: `next-work` re-derives the identical `:resume`
   ordinal 1 (the dangling receipt is non-terminal, `work.cljc:105-133`);
   `receipt-start` now refuses `::receipt-exists` (`run.cljc:493-516`);
   the pass reports `:error` and commits one error fact
   (`refused!`, `loop.cljc:245-278`); `more-work?` is true; rewake.
   Hot livelock, agent wedged busy forever, unbounded error facts.
   `interruptions` cannot rescue it (plan-digest present,
   `work.cljc:207-222`); boot recovery only runs at boot.

REPL proof (P1): `next-work` identical across three derivations with
`{:refused ::not-the-holder}` then `{:refused ::receipt-exists}`
between them. `my.run/wait` hits the same wall through `release-call`.

**Superclass.** The same fixed point arises whenever a non-terminal
receipt exists at the run's current epoch under a live holder — e.g. a
Throwable escaping between receipt-start and settle inside one pass
(contractually impossible, but that is precisely a cap-as-safety-net:
the escape is already a recorded fault class, and it converts a
degraded state into an infinite hot loop). This is the third instance
of one class — the measured `:close` livelock (fixed by claim-first,
`loop.cljc:924-936`) and P2 below are the others. The class-killer is
one rule at one choke point: **custody precedes work** — a pass may
act on a run only under a live lease it verified or acquired in that
pass, and taking over an epoch stamps the old epoch's dangling
receipts `interrupted-at` exactly as `recover-tx` does for dead
processes. That makes the fixed point unrepresentable rather than
handled.

## 3. Property (b) — no trigger answered twice

### 3.1 Answeredness holds by construction

- The answer is a permanent datom (`[?tx :seon.db/trigger ?m]` on the
  open transaction) — no flag to desynchronize, and nothing deletes
  runs (`work.cljc:250-277`).
- Two concurrent opens for one agent: the second refuses
  `::agent-already-running` on the pointer read inside the serialized
  transaction (`open-call`, `run.cljc:226-257`); the prior race audit's
  two-futures probe confirmed one winner. Cross-process duplication is
  excluded by one live process per store (flock + one connection per
  branch).
- No auto-retry re-answers a crashed trigger: an open unclaimed
  unplanned run is settled, never re-called (`work.cljc:33-43`,
  `settle-interruption!` `loop.cljc:484-530`); a terminal transaction
  re-naming the same trigger changes nothing existential
  (`loop.cljc:888-899`).
- Within one answer, the paid call is bounded per PASS: plan freeze is
  CAS-on-absence (`::plan-frozen`, `run.cljc:413-450`), failover is
  bounded at exactly two calls (`loop.cljc:692-697`), the backoff
  schedule is finite and empty when a backup exists
  (`loop.cljc:674-676`), and a failure whose record could not commit
  refuses to call again (`record-attempt!` returning nil,
  `loop.cljc:713-718`).

### 3.2 VIOLATION — the lapsed-lease re-pay cycle (probe P2)

`next-work` selects `:call` for a run whose `process` is ours with NO
lease check (`work.cljc:173-178`). Nothing ever renews a lease:
`heartbeat-tx` has zero call sites outside `run.cljc` (rg-verified).
So when the `:call` pass STARTS more than 60 s after the `:open` pass
— reachable by ordinary starvation, since turns are serial and
`next-work`'s `some` over a sorted agent set always serves the
earliest agent with work first (`work.cljc:166-196`) — the pass makes
the PAID call, then `plan-call` refuses `::lease-expired` through
`held-run` at the pass-start clock, the run stays held-unplanned, and
the next rewake derives the identical `:call`. Every iteration of the
cycle is one paid model call plus one refused freeze, unbounded until
restart (whereupon recovery + `interruptions` settle it — restart is
the only exit).

REPL proof (P2): `next-work` = `:call` at lease+30 s, `plan-tx`
refuses `::lease-expired`, `next-work` = the identical `:call`.

Note the in-pass clock is NOT the hole: `now` is pinned at pass start
(`loop.cljc:453`), so a single slow model call inside one pass freezes
fine. The hole is the open-pass→call-pass gap, and the same
custody-precedes-work rule from §2.2 closes it (re-claim/renew at
`:call` entry; the takeover path already exists in `claim-call`).

## 4. Property (c) — no trigger lost

PROVEN at the fact and wake levels; every edge has a fence:

- **Produced but never visible:** impossible — a refused transaction
  commits neither message nor wake (`writer.cljc:372` gate, noted at
  `wake.cljc:48-50`), and a committed message is a permanent fact the
  next `next-work` derivation sees regardless of any wake.
- **Wake dropped by coalescing:** safe by design — a wake says only
  "look", a pass scans ALL agents at one fresh basis, and the
  commit-then-listen ordering means a commit racing a running pass
  lands its `offer!` in the buffer the proc reads next
  (`wake.cljc:22-27`, listener fires post-commit inside the writer's
  go block).
- **Commit before the listener existed:** covered by ordering in
  `arm-loop!` — `listen!` registers, THEN the boot prime is offered
  (`cluster.clj:680-689`), and the prime's pass derives from facts, so
  anything committed earlier (REPL layer-0 traffic included) is seen.
- **Wake into a closed channel:** the one genuinely lossy edge, and it
  is fenced as a FAULT fact (`wake.cljc:124-132`); the channel closes
  only in `disarm-loop!` after the graph has stopped
  (`cluster.clj:713-726`), i.e. while the cluster is going away, and
  the next boot's prime re-derives.
- **Orphan wedging the agent:** an open unclaimed unplanned run is
  settled at the TOP of every pass, before deriving
  (`loop.cljc:458-464`), so a dead process's wreckage cannot hold an
  agent's later triggers hostage.

Caveat, stated honestly: the (a)/(b) violations wedge an agent while
the process lives. Its triggers remain conserved facts — produced,
unanswered, derivable — but are unanswerable until restart. Fixing
§2.2/§3.2 restores liveness; conservation itself never breaks.

### 4.1 Class-mate: the nil-`:in` wildcard

`docs/seon/issues/a-nil-query-input-matches-anything-so-prompt-cannot-refuse.md`
(open) is this audit's mis-consumption class-mate: a nil query input
silently widens "match this trigger" to "match any message", so a
consumer can ANSWER THE WRONG TRIGGER without any refusal firing. The
loop's own `:call` still passes `(message/trigger @connection run-id)`
unguarded into the prompt (`loop.cljc:666-669`); `message/trigger` is
non-nil for every run the current `:open` writes, so the hole is
latent, but the choke-point fix the issue names (refuse nil before any
`d/q`, or a query helper refusing nil inputs) is the same
make-it-unrepresentable shape as §2.2's.

## 5. What per-agent mailboxes change (AGENTS-ARE-FLOWS, §F)

Which proofs survive F2's deletion of the central pass:

- **Independent of the serial pass (survive as-is):** answered-once
  (permanent tx-meta datom), the busy fence (`open-call` pointer CAS),
  settle-once (receipt presence), plan-frozen, the conversation bound
  (tx-meta walk), the error storm fence, and refusal-aborts-the-whole-
  transaction. All are database-level, enforced inside the one serial
  writer, and no per-agent topology can un-enforce them.
- **Dependent on the serial pass (must move):**
  - `attributed-run` exactness is EXPLICITLY serial-dependent
    (`cluster.clj:513-520` says so): with concurrent per-agent turns
    the current run must ride in each proc's own state.
  - Wake coalescing's safety argument ("one consumer scans all
    agents") becomes per-agent: the listener must ROUTE each
    `message/to` datom to the recipient's channel. New production
    edge: resolve `?to` → mailbox in the handler (still no query, no
    park — the datom carries the ref). New loss edge: a message for an
    agent whose graph is not yet armed (agent created and messaged
    before its mailbox exists, or during a per-agent topology
    rebuild, whose channel contents are discarded). Conservation is
    kept by the SAME boot-prime idiom: arming any agent graph ends
    with one primed wake, and its first pass derives that agent's work
    from facts — so a lost routed wake costs nothing that the arm
    prime does not recover. The invariant to test: *every mailbox arm
    primes once, and no commit path can observe an agent that has
    triggers but will never be armed.*
  - `interruptions` settling is today a global pre-pass; per-agent
    graphs settle their own orphan, and whoever ARMS graphs must
    settle orphans of agents that have no graph yet (else the wedge
    returns through the arming gap).
  - `next-work`'s global `some` dies with the pass (the prior art's
    `next-agent-work` point stands, verified against today's source:
    `loop.cljc:900-906` still asks GLOBAL `next-work` for the fold's
    next ordinal, which is wrong the moment two agents run).
- **Effect on the violations:** per-agent mailboxes REMOVE P2's
  starvation reachability (no sorted-order queueing behind siblings)
  but not its cause — any wake→pass latency exceeding the lease
  reconstructs it, so custody-precedes-work is still the fix, at the
  transition layer where it belongs. P1 is completely indifferent to
  the topology: it is a disagreement between the work derivation and
  the transition fences, and it must be fixed before F2 multiplies the
  number of loops that can hit it.

## 6. Episode semantics (F1's input)

**"Runs since the last outside trigger" is derivable purely from
committed facts. No new fact and no stored counter is needed.**

The two facts already committed suffice: every run's opening
transaction names its trigger (`:seon.db/trigger` tx-meta), and a
trigger's sender is present exactly when it is an agent
(`:seon.cluster.message/from`; absence = outside the population,
`src/seon/schema/message.edn`). The derivation:

```clojure
(defn episode-runs [db agent-id]
  (let [outside-tx (d/q '[:find (max ?tx) . :in $ ?agent-id
                          :where
                          [?agent :seon.cluster.agent/id ?agent-id]
                          [?run :seon.cluster.run/agent ?agent]
                          [?run :seon.cluster.run/id _ ?tx]
                          [?tx :seon.db/trigger ?message]
                          (not [?message :seon.cluster.message/from _])]
                        db agent-id)]
    (if (nil? outside-tx)
      0
      (or (d/q '[:find (count ?run) . :in $ ?agent-id ?outside-tx
                 :where
                 [?agent :seon.cluster.agent/id ?agent-id]
                 [?run :seon.cluster.run/agent ?agent]
                 [?run :seon.cluster.run/id _ ?tx]
                 [(>= ?tx ?outside-tx)]]
               db agent-id outside-tx)
          0))))
```

REPL-tested (probe P3, in-memory fixture with the real transitions and
real tx-meta): 3 after outside+2 agent-triggered runs; 1 after a fresh
outside trigger; 0 for an agent with no runs. The episode gate is then
one comparison at `:open` time: refuse (or defer) when
`(>= (episode-runs db agent-id) max-consecutive-runs)` and the
pending trigger's sender is an agent — a fresh outside trigger opens
regardless, because its own run becomes the new `outside-tx`.

**Measured cost:** ~34 µs per derivation on a 64-run agent history
(in-memory, M-series, 100-call mean after warmup). Both queries are
index walks scoped to one agent's runs — linear in that agent's total
run count, not in the episode. At the point of use (once per `:open`,
adjacent to a multi-second paid model call) this is three orders of
magnitude below noise; if a years-old agent's run count ever makes it
measurable, an `as-of`/`since` window is available without any schema
change.

**One semantic note, not a missing fact:** "outside" today includes
the error recorder (its messages have no `from`). If the owner wants
human-only episode resets, the recorder's messages are already
distinguishable — they carry `:seon.cluster.message/about` pointing at
an error fact (`error.clj:703`) — so that refinement is one more
`not`-clause, still zero new facts.

## 7. Prior art verified

The 2026-07-27 turn-dispatcher race audit's "races the existing
transitions already kill" table was re-verified against today's
source: all nine rows still hold (line numbers drifted; the fences did
not). Its dispatch-era table correctly anticipated the active-agent
exclusion need; it did NOT catch P1 or P2, because both live in the
seam between `next-work`'s custody assumptions and the transition
fences — the fences all work; the loop asks them to do transitions it
never made itself eligible for.

## Appendix — the probe (tmp/trigger-conservation-probe.clj, verbatim)

```clojure
;; Trigger-conservation audit probes — 2026-07-28.
;; Run: clojure -M:dev tmp/trigger-conservation-probe.clj
;; Three probes, all transition-level (no model call, no loop proc):
;;   P1 recovered-unheld-run completion livelock (property a violation)
;;   P2 lapsed-lease :call re-pay cycle (property b violation)
;;   P3 the episode query — runs since the last outside trigger,
;;      derived purely from committed facts, with measured cost.
(ns trigger-conservation-probe
  (:require [datahike.api :as d]
            [seon.cluster.run :as run]
            [seon.cluster.work :as work]
            [seon.schema.datahike :as schema.datahike])
  (:import [java.util Date]))

(def attributes
  [:seon.cluster.agent/id
   :seon.cluster.agent/run
   :seon.cluster.run/id
   :seon.cluster.run/agent
   :seon.cluster.run/opened-at
   :seon.cluster.run/closed-at
   :seon.cluster.run/process
   :seon.cluster.run/claim-epoch
   :seon.cluster.run/lease-until
   :seon.cluster.run/plan-digest
   :seon.cluster.run/forms
   :seon.cluster.run.form/id
   :seon.cluster.run.form/run
   :seon.cluster.run.form/ordinal
   :seon.cluster.run.form/source
   :seon.cluster.eval/id
   :seon.cluster.eval/run
   :seon.cluster.eval/ordinal
   :seon.cluster.eval/claim-epoch
   :seon.cluster.eval/at
   :seon.cluster.eval/interrupted-at
   :seon.cluster.eval/result-edn
   :seon.cluster.eval/error
   :seon.cluster.message/id
   :seon.cluster.message/to
   :seon.cluster.message/from
   :seon.cluster.message/content
   :seon.cluster.message/at
   :seon.db/trigger])

(defn fresh-connection []
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}]
    (d/create-database configuration)
    (let [connection (d/connect configuration)]
      (d/transact connection
                  (schema.datahike/malli->datahike-schema attributes))
      connection)))

(def digest (apply str (repeat 64 "a")))
(def t0 (Date. 1700000000000))
(defn plus [inst ms] (Date. (+ (inst-ms inst) ms)))

(defn refusal-rule [throwable]
  (loop [candidate throwable deepest nil]
    (if (nil? candidate)
      deepest
      (recur (ex-cause candidate)
             (or (:seon.cluster.run/rule (ex-data candidate)) deepest)))))

(defn transact-outcome [connection tx]
  (try {:ok (d/transact connection tx)}
       (catch Throwable failure {:refused (refusal-rule failure)})))

;;; ---------------------------------------------------------------------------
;;; P1 — recovered unheld planned run, completing form
;;; ---------------------------------------------------------------------------

(println "== P1: recovery -> :resume of unheld planned run -> completing form")
(let [connection (fresh-connection)
      dead "dead-process"
      live "live-process"]
  (d/transact connection [{:seon.cluster.agent/id "a"}
                          {:seon.cluster.message/id "m1"
                           :seon.cluster.message/to [:seon.cluster.agent/id "a"]
                           :seon.cluster.message/content "do it"
                           :seon.cluster.message/at t0}])
  ;; the dead process's history: open+claim (trigger tx-meta), plan of 2 forms,
  ;; form 0 settled, then the process died before form 1 started.
  (d/transact connection
              {:tx-data (into (run/open-tx {:seon.cluster.run/id "r1"
                                            :seon.cluster.run/agent
                                            [:seon.cluster.agent/id "a"]
                                            :seon.cluster.run/opened-at t0})
                              (run/claim-tx {:seon.cluster.run/id "r1"
                                             :seon.cluster.run/process dead
                                             :seon.cluster.run/lease-until (plus t0 60000)
                                             :seon.cluster.run/now t0}))
               :tx-meta {:seon.db/trigger [:seon.cluster.message/id "m1"]}})
  (d/transact connection (run/plan-tx {:seon.cluster.run/id "r1"
                                       :seon.cluster.run/process dead
                                       :seon.cluster.run/claim-epoch 1
                                       :seon.cluster.run/plan-digest digest
                                       :seon.cluster.run/sources ["(+ 1 2)" "(my.run/complete \"3\")"]
                                       :seon.cluster.run/now t0}))
  (d/transact connection (run/receipt-start-tx {:seon.cluster.run/id "r1"
                                                :seon.cluster.run/claim-epoch 1
                                                :seon.cluster.eval/ordinal 0
                                                :seon.cluster.eval/at t0}))
  (d/transact connection (run/receipt-settle-tx {:seon.cluster.run/id "r1"
                                                 :seon.cluster.run/claim-epoch 1
                                                 :seon.cluster.eval/ordinal 0
                                                 :seon.cluster.eval/result-edn "3"}))
  ;; boot recovery by the next process: release dead custody (form 1 never
  ;; started, so no receipt to stamp)
  (let [db @connection
        open-run (d/pull db '[*] [:seon.cluster.run/id "r1"])
        receipts (d/q '[:find [(pull ?r [*]) ...] :where
                        [?r :seon.cluster.eval/id _]] db)
        recovery (run/recover-tx {:seon.cluster.run/run open-run
                                  :seon.cluster.run/receipts receipts
                                  :seon.cluster.run/live-processes #{live}
                                  :seon.cluster.run/now (plus t0 120000)})]
    (when (seq recovery) (d/transact connection recovery)))
  (let [now (plus t0 130000)
        request {:seon.cluster.run/process live :seon.cluster.work/now now}
        work-1 (work/next-work @connection request)
        _ (println "  next-work after recovery:" work-1)
        ;; the loop's :resume pass: start the receipt at the run's CURRENT epoch
        started (transact-outcome connection
                                  (run/receipt-start-tx
                                   {:seon.cluster.run/id "r1"
                                    :seon.cluster.run/claim-epoch 1
                                    :seon.cluster.eval/ordinal 1
                                    :seon.cluster.eval/at now}))
        _ (println "  receipt-start ordinal 1:" (if (:ok started) :committed started))
        ;; terminal transaction: settle + the :completed disposition's close
        terminal (transact-outcome
                  connection
                  (into (run/receipt-settle-tx
                         {:seon.cluster.run/id "r1"
                          :seon.cluster.run/claim-epoch 1
                          :seon.cluster.eval/ordinal 1
                          :seon.cluster.eval/result-edn
                          "{:my.run/disposition :completed :my.run/result \"3\"}"})
                        (run/close-tx {:seon.cluster.run/id "r1"
                                       :seon.cluster.run/process live
                                       :seon.cluster.run/claim-epoch 1
                                       :seon.cluster.run/closed-at now
                                       :seon.cluster.run/now now})))
        _ (println "  terminal tx (settle+close):" terminal)
        work-2 (work/next-work @connection request)
        _ (println "  next-work after refusal:" work-2)
        restart (transact-outcome connection
                                  (run/receipt-start-tx
                                   {:seon.cluster.run/id "r1"
                                    :seon.cluster.run/claim-epoch 1
                                    :seon.cluster.eval/ordinal 1
                                    :seon.cluster.eval/at now}))
        _ (println "  receipt-start retry:" restart)
        work-3 (work/next-work @connection request)]
    (println "  next-work after retry refusal:" work-3)
    (println "  CYCLE:" (= work-1 work-2 work-3)
             "— identical work derived, no transition can commit."))
  (d/release connection))

;;; ---------------------------------------------------------------------------
;;; P2 — held run whose lease lapsed before the :call pass
;;; ---------------------------------------------------------------------------

(println "\n== P2: lapsed lease -> next-work :call (paid) -> plan freeze refused")
(let [connection (fresh-connection)
      live "live-process"]
  (d/transact connection [{:seon.cluster.agent/id "b"}
                          {:seon.cluster.message/id "m2"
                           :seon.cluster.message/to [:seon.cluster.agent/id "b"]
                           :seon.cluster.message/content "slow day"
                           :seon.cluster.message/at t0}])
  (d/transact connection
              {:tx-data (into (run/open-tx {:seon.cluster.run/id "r2"
                                            :seon.cluster.run/agent
                                            [:seon.cluster.agent/id "b"]
                                            :seon.cluster.run/opened-at t0})
                              (run/claim-tx {:seon.cluster.run/id "r2"
                                             :seon.cluster.run/process live
                                             :seon.cluster.run/lease-until (plus t0 60000)
                                             :seon.cluster.run/now t0}))
               :tx-meta {:seon.db/trigger [:seon.cluster.message/id "m2"]}})
  ;; 90 seconds later — the loop was busy with other agents' serial turns
  (let [now (plus t0 90000)
        request {:seon.cluster.run/process live :seon.cluster.work/now now}
        work-1 (work/next-work @connection request)
        _ (println "  next-work at lease+30s:" work-1)
        _ (println "  (the loop would now make the PAID model call)")
        freeze (transact-outcome connection
                                 (run/plan-tx {:seon.cluster.run/id "r2"
                                               :seon.cluster.run/process live
                                               :seon.cluster.run/claim-epoch 1
                                               :seon.cluster.run/plan-digest digest
                                               :seon.cluster.run/sources ["(+ 1 1)"]
                                               :seon.cluster.run/now now}))
        _ (println "  plan freeze after the paid call:" freeze)
        work-2 (work/next-work @connection request)]
    (println "  next-work after refusal:" work-2)
    (println "  CYCLE:" (= work-1 work-2)
             "— every iteration contains one paid model call.")
    (println "  heartbeat call sites in src/:"
             "NONE (rg heartbeat-tx|heartbeat-call, run.cljc only)"))
  (d/release connection))

;;; ---------------------------------------------------------------------------
;;; P3 — the episode query: runs since the last OUTSIDE trigger
;;; ---------------------------------------------------------------------------

(println "\n== P3: episode derivation from committed facts only")
(defn open-run! [connection agent-id run-id message-id at]
  (d/transact connection
              {:tx-data (into (run/open-tx {:seon.cluster.run/id run-id
                                            :seon.cluster.run/agent
                                            [:seon.cluster.agent/id agent-id]
                                            :seon.cluster.run/opened-at at})
                              (run/claim-tx {:seon.cluster.run/id run-id
                                             :seon.cluster.run/process "p"
                                             :seon.cluster.run/lease-until (plus at 60000)
                                             :seon.cluster.run/now at}))
               :tx-meta {:seon.db/trigger [:seon.cluster.message/id message-id]}}))

(defn close-run! [connection run-id epoch at]
  (d/transact connection (run/close-tx {:seon.cluster.run/id run-id
                                        :seon.cluster.run/process "p"
                                        :seon.cluster.run/claim-epoch epoch
                                        :seon.cluster.run/closed-at at
                                        :seon.cluster.run/now at})))

(defn message! [connection id to from content at]
  (d/transact connection
              [(cond-> {:seon.cluster.message/id id
                        :seon.cluster.message/to [:seon.cluster.agent/id to]
                        :seon.cluster.message/content content
                        :seon.cluster.message/at at}
                 from (assoc :seon.cluster.message/from
                             [:seon.cluster.agent/id from]))]))

(defn episode-runs
  "How many consecutive runs of `agent-id` since (and including) the one
  answering the last OUTSIDE trigger. Purely committed facts: the run's
  opening tx carries :seon.db/trigger; a trigger with no
  :seon.cluster.message/from came from outside the agent population."
  [db agent-id]
  (let [outside-tx
        (d/q '[:find (max ?tx) .
               :in $ ?agent-id
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               [?run :seon.cluster.run/agent ?agent]
               [?run :seon.cluster.run/id _ ?tx]
               [?tx :seon.db/trigger ?message]
               (not [?message :seon.cluster.message/from _])]
             db agent-id)]
    (if (nil? outside-tx)
      0
      (or (d/q '[:find (count ?run) .
                 :in $ ?agent-id ?outside-tx
                 :where
                 [?agent :seon.cluster.agent/id ?agent-id]
                 [?run :seon.cluster.run/agent ?agent]
                 [?run :seon.cluster.run/id _ ?tx]
                 [(>= ?tx ?outside-tx)]]
               db agent-id outside-tx)
          0))))

(let [connection (fresh-connection)]
  (d/transact connection [{:seon.cluster.agent/id "alice"}
                          {:seon.cluster.agent/id "bob"}])
  ;; episode 1: human -> alice (run e1), bob -> alice (runs e2 e3)
  (message! connection "h1" "alice" nil "human asks" t0)
  (open-run! connection "alice" "e1" "h1" (plus t0 1000))
  (close-run! connection "e1" 1 (plus t0 2000))
  (message! connection "b1" "alice" "bob" "bob replies" (plus t0 3000))
  (open-run! connection "alice" "e2" "b1" (plus t0 4000))
  (close-run! connection "e2" 1 (plus t0 5000))
  (message! connection "b2" "alice" "bob" "bob again" (plus t0 6000))
  (open-run! connection "alice" "e3" "b2" (plus t0 7000))
  (close-run! connection "e3" 1 (plus t0 8000))
  (println "  after outside+2 agent runs, episode-runs ="
           (episode-runs @connection "alice") "(expect 3)")
  ;; a FRESH outside trigger resets the episode
  (message! connection "h2" "alice" nil "human again" (plus t0 9000))
  (open-run! connection "alice" "e4" "h2" (plus t0 10000))
  (close-run! connection "e4" 1 (plus t0 11000))
  (println "  after a fresh outside trigger, episode-runs ="
           (episode-runs @connection "alice") "(expect 1)")
  ;; agent with runs but NO outside trigger ever (error-recorder-only history
  ;; still counts as outside; a purely agent-caused history yields 0 + count)
  (println "  agent with no runs, episode-runs ="
           (episode-runs @connection "bob") "(expect 0)")
  ;; cost: 60 more runs, measure the two queries
  (doseq [i (range 60)]
    (let [message-id (str "b-loop-" i)
          run-id (str "e-loop-" i)
          at (plus t0 (+ 20000 (* i 1000)))]
      (message! connection message-id "alice" "bob" "loop" at)
      (open-run! connection "alice" run-id message-id (plus at 100))
      (close-run! connection run-id 1 (plus at 200))))
  (let [db @connection
        warm (dotimes [_ 3] (episode-runs db "alice"))
        began (System/nanoTime)
        n 100
        _ (dotimes [_ n] (episode-runs db "alice"))
        per-call-us (quot (- (System/nanoTime) began) (* n 1000))]
    (println "  64-run agent history: episode-runs =" (episode-runs db "alice")
             "(expect 61); cost" per-call-us "us/derivation"))
  (d/release connection))

(println "\nprobes complete")
```
