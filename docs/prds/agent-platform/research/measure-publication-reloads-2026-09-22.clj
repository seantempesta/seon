(require '[clojure.edn :as edn]
         '[seon.operator :as operator]
         '[clojure.walk :as walk])

; Companion to measure-publication-path-2026-09-22.sh. Run only on its scratch JVM.
; capture/report surround a request; compile measures require itself, without arming.
(let [[root mode] *command-line-args*
      endpoint (when-not (= mode "compile-offline")
                 (edn/read-string (slurp (str root "/data/clusters/head/prepl.edn"))))
      prepare
      `(do
         (require '~'my.note '~'seon.id)
         (seon.cluster/refresh-source! ~root ["src/my/note.clj" "src/seon/id.clj"] "head")
         (let [database# (seon.db/db (seon.cluster.boot/connection "head"))]
           (seon.instrument/apply!
            {:seon.schema/projection (seon.schema/projection-from-database database#)
             :seon.config/on-core-error :panic})))
      capture
      '(do
         (intern 'user 'publication-clock-reloads (atom []))
         (add-watch @(ns-resolve 'seon.cluster 'source-refresh-holder)
                    :publication-clock
                    (fn [_ _ _ current]
                      (let [phase (:seon.operator.lock/phase current)
                            prefix "development reload "]
                        (when (and phase (clojure.string/starts-with? phase prefix))
                          (swap! @(ns-resolve 'user 'publication-clock-reloads)
                                 conj (symbol (subs phase (count prefix))))))))
         (intern 'user 'publication-clock-roots
               (into {} (for [n (all-ns) v (vals (ns-interns n))
                              :when (and (bound? v) (:seon.instrument/var (meta @v)))]
                          [v @v]))) nil)
      report
      '(let [before @(ns-resolve 'user 'publication-clock-roots)
             changed (into []
                           (for [n (all-ns) v (vals (ns-interns n))
                                 :when (and (bound? v) (:seon.instrument/var (meta @v))
                                            (not (identical? (get before v) @v)))]
                             (symbol (str (ns-name (:ns (meta v))))
                                     (str (:name (meta v))))))]
         {:vars-rearmed (count changed) :symbols (vec (sort changed))
          :namespaces-reloaded
          (let [namespaces @@(ns-resolve 'user 'publication-clock-reloads)]
            (remove-watch @(ns-resolve 'seon.cluster 'source-refresh-holder) :publication-clock)
            namespaces)})
      compile-form
      '(let [database (seon.db/db (seon.cluster.boot/connection "head"))
             namespaces (seon.cluster/development-namespaces database [[:seon.ns/name 'seon.id]])
             requires (#'seon.cluster/namespace-requires database namespaces)
             order (filterv
                    (fn [n]
                      (let [resource (.. (str n) (replace \- \_) (replace \. \/))]
                        (or (clojure.java.io/resource (str resource ".clj"))
                            (clojure.java.io/resource (str resource ".cljc")))))
                    (seon.cluster/reload-order namespaces requires))
             _ (doseq [n order] (require n))
             start (System/nanoTime)
             rows (mapv (fn [n]
                          (let [began (System/nanoTime)]
                            (require n :reload)
                            {:namespace n :ms (/ (- (System/nanoTime) began) 1e6)}))
                        order)]
         {:case :seon.id-compile :namespace-count (count rows)
          :elapsed-ms (/ (- (System/nanoTime) start) 1e6)
          :heap-used-bytes (- (.totalMemory (Runtime/getRuntime))
                              (.freeMemory (Runtime/getRuntime)))
          :namespaces rows})
      form (case mode "prepare" prepare "capture" capture "report" report "compile" compile-form
                     "compile-offline" compile-form)]
  (if (= mode "compile-offline")
    (do
      (require 'seon.cluster 'seon.cluster.source 'seon.cluster.store)
      (let [held ((resolve 'seon.cluster.store/open-store!)
                  {:seon.store/dir (str root "/data/store")})]
        (try
          (let [published ((resolve 'seon.cluster.source/current) held)
                database ((resolve 'seon.cluster.source/database)
                          held (:seon.source/commit-id published))]
            (try
              (intern 'user 'publication-clock-database database)
              (prn (assoc (eval (walk/postwalk-replace
                                 {'(seon.db/db (seon.cluster.boot/connection "head"))
                                  '(deref (ns-resolve 'user 'publication-clock-database))}
                                 form))
                          :boundary :published-database-unarmed-jvm
                          :commit (:seon.source/commit-id published)))
              (finally
                (ns-unmap 'user 'publication-clock-database)
                ((resolve 'datahike.api/release-materialized-db) database))))
          (finally ((resolve 'seon.cluster.store/release-store!) held))))
      (shutdown-agents))
    (prn (operator/prepl-value! endpoint (pr-str form) 600000))))
