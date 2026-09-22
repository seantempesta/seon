;; End-to-end leaf publication on a private operator root (lane publication-work).
;; Run from a checkout snapshot (git archive + reference-code links + shared .clj-kondo/.cache):
;;   GIT_DIR=<repo>/.git clojure -M:test -e '(load-file "<this file>")'
;; Round 0 builds the root's current-src from nothing (its cost is reported); then
;; unchanged publications, then three leaf edits each published with the path named.
(require '[clojure.java.io :as io]
         '[seon.cluster :as cluster]
         '[seon.cluster.source :as source]
         '[seon.fn.analyzer :as analyzer]
         '[seon.schema :as schema])

(def directory (.getCanonicalPath (io/file ".")))
(def root (str directory "/tmp/leaf-root/data/clusters"))
(def leaf "src/seon/eval.clj")
(def leaf-text (slurp leaf))
(def counts (atom {}))

(defn- counted [sym label]
  (let [v (or (resolve sym) (throw (ex-info "unresolved measurement target" {:sym sym}))) original @v]
    [v (fn [& args]
         (let [t0 (System/nanoTime)
               result (apply original args)]
           (swap! counts update label (fnil (fn [m] (-> m (update :calls inc) (update :ms + (/ (- (System/nanoTime) t0) 1e6))))
                                            {:calls 0 :ms 0.0}))
           (when-let [c (and (map? result) (::analyzer/cache result))]
             (swap! counts update :kondo-cache (fnil conj []) (update c ::analyzer/stale count)))
           (when (and (map? result) (contains? result ::source/reused))
             (swap! counts update :test-input (fnil conj [])
                    (-> (select-keys result [::source/reused ::source/hits ::source/read])
                        (update ::source/read count))))
           result))]))

(defn- publish! [label changed]
  (reset! counts {})
  (let [phases (atom [])
        t0 (System/nanoTime)
        stamp #(swap! phases conj [% (/ (- (System/nanoTime) t0) 1e6)])
        result (with-redefs-fn
                 (into {} (map (fn [[s l]] (counted s l)))
                       [['seon.cluster.source/capture-paths :capture-paths]
                        ['seon.cluster.source/snapshot-test-input-digest :test-input-digest]
                        ['seon.test.cache/input-digests :whole-input-digests]
                        ['seon.test.cache/gitlink-digests :git-gitlinks]
                        ['seon.test.cache/input-paths :git-input-paths]
                        ['seon.fn/analyze-rows :analyze-rows]
                        ['seon.fn.analyzer/analyze :kondo-analyze]
                        ['seon.db/transact! :transactions]
                        ['seon.cluster.source/publish! :source-publish]
                        ['seon.cluster.source/database :published-database]
                        ['seon.db/carried-projection :carried-projection]
                        ['seon.schema/projection-from-database :projection-from-database]
                        ['seon.fn/analysis-projection :analysis-projection]
                        ['seon.fn/file-rows :file-rows]
                        ['seon.fn/analyzed-artifacts :analyzed-artifacts]
                        ['seon.fn/dependent-paths :dependent-paths]
                        ['seon.fn/assert-capability-contracts! :capability-contracts]
                        ['seon.fn/index! :index!]
                        ['seon.cluster/populate-source! :populate-source]])
                 #(with-bindings {#'cluster/*source-progress!* stamp}
                    (cluster/refresh-source! root changed nil directory)))
        total (/ (- (System/nanoTime) t0) 1e6)]
    (prn {:round label :total-ms (Math/round total)
          :built? (:seon.source/built? result)
          :error (:seon.error/message result)
          :progress (mapv (fn [[p ms]] [p (Math/round ms)]) @phases)
          :counts (into (sorted-map) (map (fn [[k v]] [k (if (map? v) (update v :ms #(Math/round %)) v)])) @counts)})))

(let [projection (schema/call-with-forms ((requiring-resolve 'seon.schema.edn/packaged-forms))
                                         schema/declaration-projection)]
  (schema/call-with-projection
   projection
   (fn []
     ;; The root's store persists across runs; build it from nothing only once.
     (when-not (.isDirectory (io/file directory "tmp/leaf-root/data/store"))
       (publish! :from-zero []))
     (publish! :unchanged-full [])
     (publish! :unchanged-leaf [leaf])
     (try
       (doseq [n (range 3)]
         (spit leaf (str leaf-text "\n(defn- leaf-probe-" n " [] " n ")\n"))
         (publish! (keyword (str "leaf-edit-" n)) [leaf]))
       (finally (spit leaf leaf-text))))))
(shutdown-agents)
(System/exit 0)
