;; Run: bb -cp src:resources docs/prds/steward-platform/research/adoption_margin_cleanup_probe_2026_09_17.clj
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[seon.fs :as fs]
         '[seon.operator.state :as state]
         '[taoensso.timbre :as log])

(let [root (.getCanonicalPath (io/file "tmp/adoption-review-cleanup-root"))
      target (io/file root "data/store")
      observed (atom [])]
  (assert (not (.exists (io/file root))) "Probe root already exists")
  (try
    (.mkdirs target)
    (spit (io/file target "object") "stored")
    (let [result
          (binding [log/*config*
                    (assoc log/*config* :appenders
             {::capture
              {:enabled? true
               :fn (fn [data]
                     (let [message (str (force (:msg_ data)))]
                       (when (str/includes? message "seon recursive deletion:")
                         (swap! observed conj
                                {::before-deletion? (.exists target)
                                 ::message message}))))}})]
            (state/cleanup-root-under-lock! root root root))]
      (assert (:seon.operator.cleanup/complete? result))
      (assert (not (.exists target)))
      (assert (= 1 (count @observed)))
      (assert (::before-deletion? (first @observed)))
      (assert (str/includes? (::message (first @observed))
                             ":caller \"seon.operator.state/cleanup-root-under-lock!\""))
      (prn {::observed @observed ::complete? true}))
    (finally (fs/delete-recursively! root root))))
