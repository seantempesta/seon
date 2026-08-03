#_{:clj-kondo/ignore [:namespace-name-mismatch]}
(ns schema-created-at-saving-2026-08-02
  "Measure the physical saving from deleting the schema-row clock."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.store :as store]
            [seon.fn :as seon.fn]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import [java.io File]
           [java.util Date UUID]))

;; Run from the repository root after the deletion:
;;
;;   clojure -M:dev \
;;     docs/prds/sci-execution-runtime/research/scripts/schema-created-at-saving-2026-08-02.clj
;;
;; The rig creates four UUID-named private file stores below tmp/. It never
;; opens, mutates, or deletes an operator root or preserved eval store. The two
;; paired cells contain the complete production source population, the exact
;; archived 158/40 branch split, 198 surviving heads, and 13,204 post-base
;; commits. Post-base transactions are deterministic marker rows, not a replay
;; of the model's logical transaction stream; the result is therefore a
;; physical exact-topology measurement of this deletion, not an identical eval
;; rerun.

(def ^:private clock-attribute :seon.schema/created-at)
(def ^:private epoch-zero (Date. 0))
(def ^:private treatment-canonical-schema-rows
  schema/canonical-schema-rows)
(def ^:private treatment-forms (schema.edn/packaged-forms))

(def ^:private archived-root-topology
  [{:schema-clock/root "333214a0f358"
    :schema-clock/samples 158
    :schema-clock/commit-counts
    (vec (concat (repeat 114 67) (repeat 44 66)))}
   {:schema-clock/root "d0cd8fbc1fa3"
    :schema-clock/samples 40
    :schema-clock/commit-counts
    (vec (concat (repeat 22 67) (repeat 18 66)))}])

(defn- insert-before-last
  [values value]
  (into (conj (vec (butlast values)) value) [(last values)]))

(defn- baseline-forms
  [current-forms]
  (-> current-forms
      (assoc clock-attribute :inst)
      (update :seon.schema/schema
              insert-before-last
              [clock-attribute {:optional true} clock-attribute])))

(defn- baseline-canonical-schema-rows
  ([]
   (baseline-canonical-schema-rows (baseline-forms
                                    (schema.edn/packaged-forms))))
  ([forms]
   (mapv #(assoc % clock-attribute epoch-zero)
         (treatment-canonical-schema-rows forms))))

(defn- regular-file-bytes
  [path]
  (reduce + 0
          (map #(.length ^File %)
               (filter #(.isFile ^File %)
                       (file-seq (io/file path))))))

(defn- configuration
  [path]
  (assoc (store/datahike-configuration path)
         :writer {:backend :self :commit-wait-time 0}))

(defn- marker-row
  [root-name sample-index commit-index]
  {:seon.cluster.message/id
   (str "schema-clock-" root-name "-" sample-index "-" commit-index)
   :seon.cluster.message/content
   (str "retained commit " commit-index)})

(defn- populate-base!
  [connection manifest baseline?]
  (if baseline?
    (with-redefs [schema.edn/packaged-forms
                  (constantly (baseline-forms treatment-forms))
                  schema/canonical-schema-rows
                  baseline-canonical-schema-rows]
      (cluster/populate-source!
       {:seon.store/branch-connection connection
        :seon.fn/manifest manifest}))
    (cluster/populate-source!
     {:seon.store/branch-connection connection
      :seon.fn/manifest manifest})))

(defn- assert-cell-base!
  [connection baseline?]
  (let [database @connection
        installed? (contains? (:schema database) clock-attribute)
        clocks
        (if installed?
          (d/q '[:find ?row ?clock
                 :where [?row :seon.schema/created-at ?clock]]
               database)
          [])
        row-count
        (d/q '[:find (count ?row) .
               :where [?row :seon.schema/key]]
             database)]
    (assert (= baseline? installed?))
    (if baseline?
      (do
        (assert (= 691 row-count))
        (assert (= row-count (count clocks)))
        (assert (every? #(= epoch-zero (second %)) clocks)))
      (do
        (assert (= 690 row-count))
        (assert (empty? clocks))))))

(defn- run-root!
  [measurement-root manifest cell baseline?
   {:schema-clock/keys [root samples commit-counts]}]
  (assert (= samples (count commit-counts)))
  (let [path (.getCanonicalPath
              (io/file measurement-root (name cell) root))
        config (configuration path)]
    (d/create-database config)
    (let [connection (d/connect config)]
      (populate-base! connection manifest baseline?)
      (assert-cell-base! connection baseline?)
      (let [base-commit (d/commit-id @connection)]
        (doseq [sample-index (range samples)]
          (let [branch (keyword (str "sample-" sample-index))]
            (d/branch! connection base-commit branch)
            (let [child (d/connect (assoc config :branch branch))]
              (try
                (dotimes [commit-index (nth commit-counts sample-index)]
                  (d/transact
                   child
                   [(marker-row root sample-index commit-index)]))
                (finally
                  (d/release child)))))))
      (d/release connection))
    {:schema-clock/root root
     :schema-clock/path path
     :schema-clock/samples samples
     :schema-clock/commits (reduce + commit-counts)
     :schema-clock/bytes (regular-file-bytes path)}))

(defn- run-cell!
  [measurement-root manifest cell baseline?]
  (let [roots
        (mapv #(run-root! measurement-root manifest cell baseline? %)
              archived-root-topology)]
    {:schema-clock/cell cell
     :schema-clock/roots roots
     :schema-clock/samples
     (reduce + (map :schema-clock/samples roots))
     :schema-clock/commits
     (reduce + (map :schema-clock/commits roots))
     :schema-clock/bytes
     (reduce + (map :schema-clock/bytes roots))}))

(defn -main
  "Run the paired exact-topology clock-deletion measurement."
  [& _]
  (let [measurement-root
        (.getCanonicalPath
         (io/file "tmp" (str "schema-created-at-saving-" (UUID/randomUUID))))
        manifest (seon.fn/build-manifest {:seon.fn/roots seon.fn/source-roots})]
    (assert (= 690 (count (schema/canonical-schema-rows treatment-forms))))
    (assert (not (contains? treatment-forms clock-attribute)))
    (assert (not-any? #(contains? % clock-attribute)
                      (schema/canonical-schema-rows treatment-forms)))
    (let [baseline (run-cell! measurement-root manifest :baseline true)
          treatment (run-cell! measurement-root manifest :treatment false)
          baseline-bytes (:schema-clock/bytes baseline)
          treatment-bytes (:schema-clock/bytes treatment)
          saving (- baseline-bytes treatment-bytes)
          result
          {:schema-clock/measurement-root measurement-root
           :schema-clock/workload
           {:schema-clock/samples 198
            :schema-clock/commits 13204
            :schema-clock/heads 198
            :schema-clock/root-split [158 40]
            :schema-clock/post-base-data :deterministic-marker-rows}
           :schema-clock/baseline baseline
           :schema-clock/treatment treatment
           :schema-clock/saving-bytes saving
           :schema-clock/saving-per-sample (/ saving 198.0)
           :schema-clock/saving-fraction (/ saving (double baseline-bytes))}]
      (assert (= 198 (:schema-clock/samples baseline)
                 (:schema-clock/samples treatment)))
      (assert (= 13204 (:schema-clock/commits baseline)
                 (:schema-clock/commits treatment)))
      (assert (pos? saving))
      (prn result)))
  (shutdown-agents))

(apply -main *command-line-args*)
