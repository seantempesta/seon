(ns seon.cluster.wake
  "The wake: a commit says LOOK, and the woken pass derives from facts.

  This contract layer is fully implemented and live-proven.

  EVENT-DRIVEN, NOT POLLED. Datahike's own `listen!` fires on every
  commit, so nothing ever asks \"is there work?\" on a timer. The wake
  carries NO information — work is derived from facts, so a wake that
  lost its payload lost nothing, and one wake standing for three
  commits is correct rather than lossy.

  THE HANDLER CONTRACT IS FOUR LINES AND TWO ABSOLUTE PROHIBITIONS,
  because both were measured, not feared:

  1. IT MUST NEVER THROW. Datahike fires listeners INSIDE the
     transaction's go block and BEFORE `(deliver p tx-report)`
     (`reference-code/datahike/src/datahike/writer.cljc:384-386`).
     Probe A reproduced the consequence: the listener's exception
     escaped onto `async-mixed-6`, the deliver never happened, and the
     committing caller waited forever — the probe JVM had to be killed.
     A handler that throws does not lose a wake; it hangs the writer.
  2. IT MUST NEVER PARK. The handler runs on the committing caller's
     critical path: probe B's 800 ms listener made the triggering
     `transact` take 804 ms. So delivery is `offer!` — never `>!!`,
     never `put!` with a callback that could block — and a saturated
     channel DROPS rather than parks. Dropping is safe by construction:
     the channel is `(sliding-buffer 1)` and a wake means only \"look\".

  Nothing else belongs inside it. No query, no derivation, no commit —
  which also settles the API's own deadlock warning
  (`api/specification.cljc:1076-1078`): N3 never transacts from a
  callback at all.

  ROUTING HAS THREE TRAPS, all measured (probe A Q3, probe B Q10):

  - `:db/txInstant` is in EVERY tx-data, so routing on \"any datom\"
    would wake an agent on every commit including its own;
  - the routed set and the set of attributes a turn itself commits must
    be DISJOINT, and that is a computed property rather than a reviewed
    list (L8, L17). The routed set is `wake-attributes`; a turn commits
    `:seon.cluster.run/*`, `:seon.cluster.run.form/*`,
    `:seon.cluster.eval/*`, `:seon.cluster.agent/run`. The RENDER wake
    is deliberately outside that property: it is per-report and
    unconditional, and its consumer derives pages rather than work, so
    it cannot make an idle cluster do anything;
  - an unchanged value emits no datom, so it emits no wake. Naming a
    routed attribute whose value is idempotently re-asserted produces
    ZERO wakes, silently. Both routed attributes are safe because a new
    message and a new agent are each a new entity.

  A REFUSED TRANSACTION DOES NOT WAKE: dispatch is gated on
  `(map? tx-report)` (`writer.cljc:372`), so a refusal cannot storm a
  mailbox.

  BOOT IS ONE INJECTED WAKE, not a special path. A listener only fires
  on future commits, so work committed before this process started is
  found by `offer!`ing one wake after the graph resumes — the same code
  path as every other pass.

  Crash walk: registration is process-local and durable state is
  untouched. A kill leaves no listener (the process is gone), the
  channel's contents are discarded (`flow/impl.clj:174-183`), and the
  next boot's injected wake re-derives everything from facts."
  (:require [clojure.core.async :as async]
            [datahike.api :as d]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/wake.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Contracts
;;; ---------------------------------------------------------------------------

(defn wake-attributes
  "The attributes `route!` routes on — the ONE derivation of its set.

  Re-grounded at F2 §3.3: it was `listen!`'s input, and `listen!` is
  gone with the one-global-channel delivery. It survives because the
  disjointness property (C2) needs two COMPUTED sets to compare rather
  than one list to believe — this against
  `seon.cluster.loop/committed-attributes` — so `route!`'s `case` can
  never drift from the property silently.

  Both are safe against the unchanged-value trap: a new message and a
  new agent are each a new entity, so the datom always exists and the
  wake always fires."
  {:malli/schema [:=> [:cat] :seon.cluster.wake/attributes]}
  []
  #{:seon.cluster.message/to :seon.cluster.agent/id})

(defn route!
  "Register the ROUTING wake handler on a connection (F1 §4).
  The per-agent successor of `listen!`'s one-channel delivery, under
  the SAME two absolute prohibitions (it never throws and never parks
  — every delivery is `offer!` and the whole handler is one
  try/catch). For each committed datom:

  - `:seon.cluster.message/to` — the datom's VALUE is the recipient's
    entity id, so delivery is one lookup in the routing map the
    supplied `channels` fn returns: `offer!` a payload-free wake into
    that agent's mailbox. No query, no derivation, no commit. A
    recipient with NO routing entry offers to the ARMER instead — the
    belt for the created-and-messaged-in-one-commit window;
  - `:seon.cluster.agent/id` — a committed agent creation IS an arm
    wake: `offer!` to the armer, whose pass derives
    (agents in facts) − (armed set) and arms each.

  THE THIRD DELIVERY (F2 §1.4) is per REPORT, not per datom: one
  payload-free wake into the render channel, UNCONDITIONALLY. Every
  commit is render interest — receipts, replies, and problems are all
  page content — so matching would be a hand-list of read attributes
  (the class F2 R6 refuses until the program graph can compute it).
  One line under the same two prohibitions, and one listener per
  cluster instead of resurrecting a second registration.

  A closed mailbox refusing delivery is a FAULT fact, exactly as in
  `listen!` — a swallowed failure nobody hears about is an invisible
  one. Coalescing on every `(sliding-buffer 1)` target is safe by the
  standing argument: a wake says only \"look\", and the woken pass
  derives everything from facts. L8 holds by construction: the armer's
  own work commits no wake-set attribute (arming writes nothing; the
  prime is an `offer!`)."
  {:malli/schema [:=> [:cat :seon.cluster.wake/route-request]
                  :seon.cluster.wake/key]}
  [{:keys [:seon.cluster.wake/connection :seon.cluster.wake/channels
           :seon.cluster.wake/armer-channel :seon.cluster.wake/render-channel
           :seon.cluster.wake/fault-channel :seon.cluster.wake/key]}]
  (d/listen
   connection
   key
   (fn [report]
     (try
       ;; the render wake FIRST and once for the whole report: a page
       ;; is derived from facts, so the pass wants only "look" — and
       ;; putting it ahead of the routing keeps it unconditional by
       ;; construction rather than by a reviewer checking every arm
       (when-not (async/offer! render-channel ::wake)
         (async/offer! fault-channel
                       (ex-info "the render channel refused delivery"
                                {:seon.error/kind ::undeliverable-wake
                                 ::key key})))
       (doseq [datom (:tx-data report)]
         (case (nth datom 1)
           :seon.cluster.agent/id
           (async/offer! armer-channel ::wake)

           :seon.cluster.message/to
           (if-let [channel (get (channels) (nth datom 2))]
             (when-not (async/offer! channel ::wake)
               (async/offer! fault-channel
                             (ex-info "the wake channel refused delivery"
                                      {:seon.error/kind ::undeliverable-wake
                                       ::key key})))
             (async/offer! armer-channel ::wake))

           nil))
       (catch #?(:clj Throwable :cljs :default) failure
         (async/offer! fault-channel failure)))))
  key)

(defn unlisten!
  "Remove the wake handler. Idempotent — removing an absent listener is
  a no-op, because `::flow/stop` may arrive after a store release."
  {:malli/schema [:=> [:cat :seon.cluster.wake/unlisten-request] :nil]}
  [{:keys [:seon.cluster.wake/connection :seon.cluster.wake/key]}]
  (try
    (d/unlisten connection key)
    ;; stop may arrive after a release, and an absent listener is the
    ;; state we wanted anyway
    (catch #?(:clj Throwable :cljs :default) _ nil))
  nil)
