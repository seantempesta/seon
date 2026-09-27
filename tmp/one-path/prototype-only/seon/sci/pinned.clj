(ns seon.sci.pinned
  "PROTOTYPE ONLY, never landed: the write observer stage on the pinned SCI
  that default runs today, which has no producer observer.

  An env-atom watch holds each Var an env swap reveals and reports a root it
  has not seen; after each form it re-reads every Var held in this batch, so
  an intern or alter-var-root of a Var the batch defined is reported as a
  rebinding (no origin). It cannot see writes to a Var the batch never
  revealed, nor order a rebinding before later defs in the same form: the SCI
  fork's producer observer (`seon.sci.observe`) replaces this namespace."
  (:require [sci.impl.vars]
            [seon.sci.arm :as arm]))

(defn observer-state
  "The adapter's per-batch memory: roots seen, by Var."
  []
  (volatile! {}))

(defn observe
  "Run `thunk` under an env watch, handing each revealed write to `on-write`."
  [{ctx :seon.sci.eval/ctx roots :seon.sci.pinned/roots :as request} working on-write thunk]
  (let [env (:env ctx)
        before (:namespaces @env)
        roots (or roots (volatile! {}))
        held (volatile! #{})
        seen (fn [v]
               (when (and (instance? sci.lang.Var v) (:ns (meta v)) (sci.impl.vars/hasRoot v))
                 (let [root (.getRawRoot ^sci.lang.Var v)
                       prior (get @roots v ::none)]
                   (when-not (identical? root prior)
                     (let [armed (arm/arm! request working
                                                 ;; SCI's bind-root! (intern, alter-var-root)
                                                 ;; marks :sci/rebound; a def resets the meta
                                                 (cond-> {:op :bind :var v :root root :kind :root}
                                                   (not (:sci/rebound (meta v)))
                                                   (assoc :origin (not-empty (select-keys (meta v) [:line :column])))))]
                       (vswap! roots assoc v (.getRawRoot ^sci.lang.Var v))
                       (on-write (arm/write-event armed)))))))
        k (keyword "seon.sci.pinned" (str (gensym "observe")))]
    (add-watch env k
               (fn [_ _ old-env new-env]
                 (let [old-nss (:namespaces old-env) new-nss (:namespaces new-env)]
                   (when-not (identical? old-nss new-nss)
                     (doseq [[n m] new-nss :let [om (get old-nss n)] :when (not (identical? m om))]
                       (doseq [[s v] m :when (and (symbol? s) (not (identical? v (get om s))))]
                         (vswap! held conj v))
                       (doseq [[s v] om :when (and (symbol? s) (instance? sci.lang.Var v) (not (contains? m s)))]
                         (vswap! roots dissoc v)
                         (on-write {:seon.batch/op :unmap :seon.batch/sym (symbol (str n) (str s))})))
                     (doseq [[n _] old-nss :when (not (contains? new-nss n))]
                       (on-write {:seon.batch/op :remove-ns :seon.batch/ns n})))
                   ;; a Var first seen unbound (a def's declaration) is bound
                   ;; by a later swap that assocs the same Var
                   (run! seen @held))))
    (try
      (thunk)
      (finally
        (remove-watch env k)
        ;; a root written without an env swap (intern or alter-var-root of a
        ;; Var this batch defined) is a rebinding
        (run! seen (keys @roots))
        (doseq [n (arm/changed-namespaces before (:namespaces @env))]
          (on-write {:seon.batch/op :namespace :seon.batch/ns n}))))))
