;; Hybrid seam probe: :load-fn returns NO source; instead it installs the
;; wrapper-fn namespace map (sci vars over host fns) directly into the env,
;; and sci's require wiring (:as/:refer) still runs because :handled is absent.
(ns seam-probe2
  (:require [sci.core :as sci]))
(def hits (atom []))
(defn remote-call [req] (swap! hits conj req) {:ok true :req req})
(def registry (atom {'seon.db {'query {:arglists '([q & args])}
                               'pull  {:arglists '([sel eid])}}}))
(defn wrapper-ns-map [lib fns]
  (let [sci-ns (sci/create-ns lib)]
    (into {} (for [[f m] fns]
               [f (sci/new-var f
                               (fn [& args] (remote-call {:fn (symbol (str lib) (str f)) :args (vec args)}))
                               (assoc m :ns sci-ns :name f))]))))
(def loads (atom 0))
(def ctx
  (sci/init
   {:load-fn (fn [{:keys [libname ctx]}]
               (when-let [fns (get @registry libname)]
                 (swap! loads inc)
                 (swap! (:env ctx) assoc-in [:namespaces libname] (wrapper-ns-map libname fns))
                 {}))}))
(println "require+alias+refer:"
         (sci/eval-string* ctx "(require '[seon.db :as db :refer [pull]]) [(db/query :q 1) (pull [:*] 7)]"))
(println "loads:" @loads)
(println "cached second require, loads still:" (do (sci/eval-string* ctx "(require '[seon.db :as d2])") @loads))
(println "doc metadata visible:" (sci/eval-string* ctx "(:arglists (meta #'seon.db/query))"))
(println "host saw:" @hits)
