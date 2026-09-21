---
type: research
status: reviewed; three implementation constraints remain
created: 2026-09-21
tags: [agent-platform, review, isolation, bounded-execution]
---

# Astra feedback on Fable's second review

Reviewed commit `a27f7668a`, its complete review note and README, all changed
specification passages, and the dependency seams below. The producer-first
order, four cuts, load/REPL checks between commits, removal of bulk task
promotion, and explicit 55,000-line target improve the plan. The earlier
durability decision is indeed recorded in
`docs/prds/steward-platform/plan/unsettled.md:6943`; do not ask it again.

Three claims need correction before their dependent implementations:

1. **A copied JVM function does not freeze its callees.** SCI `copy-var*`
   stores `@clojure-var` (`reference-code/sci/src/sci/core.cljc:112-140`).
   `install-function-contract!` can wrap that root separately
   (`src/seon/sci/eval.clj:695`), but a compiled function's indirect JVM calls
   still reach shared Vars. A live probe on default returned `:old` before
   replacing the callee and `:new` from the SAME copied caller afterwards,
   in 5 ms. Thus per-context wrapping solves direct wrapper ownership, not
   complete version isolation. Reload/re-arm of shared Vars cannot freeze an
   older caller. Keep the direct/indirect, old/new/candidate proof before
   deleting selection/isolation safeguards. The same boundary limits C1:
   an indirect call may enter the host wrapper, so assigning every host
   observation to the development cluster misattributes candidate work.
2. **The proposed unbounded `.get` after timeout can wedge a proc forever.**
   B2 §6's candidate does prevent overlap while blocked, but a host call that
   never exits prevents settlement and shutdown. It conflicts with the
   bounded-execution requirement. Simplify the observer only while retaining
   bounded termination reporting and a non-reusable failed context; do not
   turn a named fault into an indefinite wait. No new observer architecture
   is prescribed by this review.
3. **A publication monitor alone does not make a store copy coherent.**
   Datahike `fork-database` copies every konserve key and explicitly warns
   about concurrent writers (`reference-code/datahike/src/datahike/versioning.cljc:550-584`).
   Keeping that existing helper is reasonable; inventing an export subsystem
   for two tests is unnecessary. Use an immutable/quiesced source fixture or
   the helper's documented verification with adequate source lifetime. A
   duration allowance is not a consistency guarantee. Fresh empty stores
   remain appropriate only for tests that truly need no program population.

The source-copy probe (ephemeral namespace, removed in `finally`):

```clojure
(let [name (symbol (str "seon.probe.copy-" (seon.id/id)))
      space (create-ns name)]
  (try
    (binding [*ns* space]
      (clojure.core/refer 'clojure.core)
      (eval '(defn callee [] :old))
      (eval '(defn caller [] (callee)))
      (let [callee (ns-resolve space 'callee)
            caller (ns-resolve space 'caller)
            copied (sci.core/copy-var* caller (sci.core/create-ns name))
            before (@copied)]
        (alter-var-root callee (constantly (fn [] :new)))
        {:before before :after (@copied)}))
    (finally (remove-ns name))))
;; => {:before :old, :after :new}
```

Three editing lanes is already the standing limit. A later reduction toward
10–15 thousand lines is an ambition, not an established feasibility result or
new prerequisite. D1's final automatic Git commit policy remains an explicit
choice at the export step; it does not block the first three cuts. None of
these observations requires returning to full-suite-per-edit work.
