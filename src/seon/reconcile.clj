(ns seon.reconcile
  "Main's commits through a sliding buffer of one, and the reconciler that reads them.
  From proto/flow f2-final proto.control/reconciler-proc (lane F2)."
  (:require [seon.sock :as sock] [seon.step :as step]))

(defn reconciler-proc
  "Main's commits through a sliding buffer of one: a reconciler is a function of main's
  latest db, so a commit it missed is covered by the next. Calls (:on-main args) with
  each report {:t :ops :db :ns} when given; reports commit -> seen latency to the api."
  {:malli/schema step/step-schema}
  ([] {:params {:on-main "fn of a main report, or nil"} :ins {:main "main's commits"} :outs {:to-api "latency"}
       :workload :io :ping-map-fn (fn [st] {:seen (:seen st 0) :t (:t st)})})
  ([args] (assoc args :seen 0))
  ([st _] st)
  ([st _ m]
   (when-let [f (:on-main st)] (f m))
   [(assoc st :seen (inc (:seen st)) :t (:t m))
    {:to-api [{:op :stat :k :main-seen-us :v (/ (- (sock/now-ns) (:ns m)) 1000.0)}]}]))
