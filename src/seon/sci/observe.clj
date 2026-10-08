(ns seon.sci.observe
  "The write observer stage: the SCI fork's producer observer
  (`sci.lang/*write-observer*`) bound around one evaluation.

  Each macro call expanded, with its call occurrence's authored span and the
  expansion, is an :expansion observation (the per-form analyzer's evidence
  for a generated definition). Each completed root write, intern, unmap and namespace removal arrives
  once, outside the env swap's retries and before the Var's watches, with
  the source occurrence the definition ran as. A definition is armed at its
  write, on the exact Var written, with the observer unbound so arming's own
  root replacement is not a write of the program. After the evaluation, each
  namespace whose resolver state changed is one more write; cached
  namespace objects and private interns do not change the program."
  (:require [sci.core :as sci]
            [sci.ctx-store]
            [sci.lang]
            [seon.sci.arm :as arm]))

(defn observe
  "Run `thunk` (one evaluation) under the producer observer, handing each
  write to `on-write`. The observer bindings are handed to `thunk` as its
  scope, so the evaluation's world carries them to any thread it moves to. The
  thunk runs with `ctx` as the current SCI context, so a definition made while
  the show stage realizes a lazy value is observed too."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value [:=> [:cat :seon.batch/write] :boolean] [:=> [:cat :seon.batch/scope] :seon.batch/value]] :seon.batch/value]}
  [{{ctx :seon.sci.eval/ctx} :seon.batch/world :as request} working on-write thunk]
  (let [env (:env ctx)
        before (:namespaces @env)
        ;; the observation scope as data: the fold puts it in the world, so any
        ;; thread that re-establishes the world (seon.turn.world/with-world)
        ;; observes the same evaluation, never an inherited binding
        ;; The observers are bindings, so any host call the evaluation makes
        ;; (a test body's thread, an acquisition installing a program into
        ;; another context) runs under them; only the evaluating context's own
        ;; writes and expansions are this evaluation's (SCI binds the
        ;; evaluating context in `sci.ctx-store/*ctx*`).
        own? (fn [] (identical? env (some-> sci.ctx-store/*ctx* :env)))
        scope {#'sci.lang/*expansion-observer*
               (fn [call expansion]
                 ;; SCI's end-location metadata names :end-row/:end-col
                 (let [{:keys [line column end-row end-col]} (meta call)
                       m (some-> (sci/resolve ctx (first call)) meta)]
                   (when (and line end-row (:ns m) (own?))
                     (on-write {:seon.batch/op :expansion :seon.batch/macro (symbol (str (:ns m)) (str (:name m)))
                                :seon.batch/call-span [line column end-row end-col]
                                :seon.batch/expansion expansion}))))
               #'sci.lang/*write-observer*
               (fn [w]
                 (when (own?)
                  (let [armed (binding [sci.lang/*write-observer* nil]
                               (if (and (= :bind (:op w)) (:ns (meta (:var w))))
                                 (arm/arm! request working w)
                                 w))]
                   (when (or (not= :bind (:op w)) (:ns (meta (:var w))))
                     (on-write (arm/write-event armed))))))}]
    (try
      (with-bindings scope (sci.ctx-store/with-ctx ctx (thunk scope)))
      (finally
        (doseq [n (arm/changed-namespaces before (:namespaces @env))]
          (on-write {:seon.batch/op :namespace :seon.batch/ns n}))))))
