(ns seon.cluster.wake
  "The wake: a commit says LOOK, and the loop derives what to do.

  DRAFT CONTRACT LAYER FOR ORCHESTRATOR SEAL (drafted 2026-07-27 — N3,
  package 2, from n3-plan §5 and its probes A and B). Nothing here is
  implemented: every body throws `awaits implementation`.

  EVENT-DRIVEN, NOT POLLED. Datahike's own `listen!` fires on every
  commit, so the loop never asks \"is there work?\" on a timer. The wake
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

  THE PREDICATE HAS THREE TRAPS, all measured (probe A Q3, probe B Q10):

  - `:db/txInstant` is in EVERY tx-data, so a predicate that looks for
    \"any datom\" wakes on every commit including the loop's own;
  - the wake set and the set of attributes the loop itself commits must
    be DISJOINT, and that is a computed property rather than a reviewed
    list (L8, L17). The wake set is `#{:seon.cluster.message/to}`; the
    loop commits `:seon.cluster.run/*`, `:seon.cluster.run.form/*`,
    `:seon.cluster.eval/*`, `:seon.cluster.agent/run`;
  - an unchanged value emits no datom, so it emits no wake. Naming a
    wake attribute whose value is idempotently re-asserted produces
    ZERO wakes, silently. `:seon.cluster.message/to` is safe because a
    new message is a new entity.

  A REFUSED TRANSACTION DOES NOT WAKE: dispatch is gated on
  `(map? tx-report)` (`writer.cljc:372`), so a refusal cannot storm the
  loop.

  BOOT IS ONE INJECTED WAKE, not a special path. A listener only fires
  on future commits, so work committed before this process started is
  found by `offer!`ing one wake after the graph resumes — the same code
  path as every other pass.

  Crash walk: registration is process-local and durable state is
  untouched. A kill leaves no listener (the process is gone), the
  channel's contents are discarded (`flow/impl.clj:174-183`), and the
  next boot's injected wake re-derives everything from facts."
  (:require [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/wake.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Contracts
;;; ---------------------------------------------------------------------------

(defn wake-attributes
  "The attributes whose commit means the loop should look.
  One derivation, so the predicate and the disjointness property read
  the same set."
  {:malli/schema [:=> [:cat] :seon.cluster.wake/attributes]}
  []
  (throw (ex-info "awaits implementation" {::fn `wake-attributes})))

(defn wake?
  "True when a transaction report touches a wake attribute (C1).
  Pure over the report's `:tx-data`, which is a sequence of datoms
  whose attribute is at index 1. `:db/txInstant` is present in every
  report and is never a wake attribute, so the loop's own commits do
  not wake it."
  {:malli/schema [:=> [:cat :seon.cluster.wake/attributes :any] :boolean]}
  [attributes report]
  (throw (ex-info "awaits implementation" {::fn `wake?})))

(defn listen!
  "Register the wake handler on a connection (C3).
  The handler is the four lines this namespace's docstring specifies,
  and its two prohibitions are the contract: it offers a wake or, on
  any throwable, offers a core fault — it NEVER throws and NEVER parks.
  Returns the key it registered under so `unlisten!` needs no bookkeeping
  elsewhere."
  {:malli/schema [:=> [:cat :seon.cluster.wake/listen-request]
                  :seon.cluster.wake/key]}
  [request]
  (throw (ex-info "awaits implementation" {::fn `listen!})))

(defn unlisten!
  "Remove the wake handler. Idempotent — removing an absent listener is
  a no-op, because `::flow/stop` may arrive after a store release."
  {:malli/schema [:=> [:cat :seon.cluster.wake/unlisten-request] :nil]}
  [request]
  (throw (ex-info "awaits implementation" {::fn `unlisten!})))
