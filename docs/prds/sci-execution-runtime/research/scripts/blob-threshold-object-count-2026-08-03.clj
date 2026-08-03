;; Count threshold-eligible receipts and distinct content-addressed objects in
;; the preserved 198-sample archive. This reads the stores without opening a
;; Datahike connection and writes nothing.

#_{:clj-kondo/ignore [:duplicate-require]}
(require '[clojure.java.io :as io]
         '[org.replikativ.persistent-sorted-set.fressian :as pss-fress]
         '[seon.cluster.loop]
         '[seon.config]
         '[seon.render.value])

(def source-path
  "docs/prds/sci-execution-runtime/research/scripts/eval-sample-store-decomposition-2026-08-02.clj")

(def forms
  (with-open [reader (java.io.PushbackReader. (io/reader source-path))]
    (loop [found []]
      (let [form (read {:eof ::eof} reader)]
        (if (= ::eof form)
          found
          (recur (conj found form)))))))

(doseq [form (butlast forms)]
  (eval form))

(defn- resolved
  [var-name]
  (var-get (ns-resolve 'eval-sample-store-decomposition-2026-08-02 var-name)))

(defn- values-in-node
  [node]
  (tree-seq
   (fn [value]
     (and (not (instance? datahike.datom.Datom value))
          (coll? value)))
   (fn [value]
     (cond
       (map? value) (mapcat identity value)
       (coll? value) (seq value)
       :else nil))
   (pss-fress/node->map node)))

(defn- result-values
  [root node-by-address]
  (letfn [(walk [node]
            (concat
             (keep
              (fn [value]
                (when (and (instance? datahike.datom.Datom value)
                           (= :seon.cluster.eval/result-edn (:a value)))
                  (:v value)))
              (values-in-node node))
             (when (instance?
                    org.replikativ.persistent_sorted_set.Branch node)
               (mapcat walk
                       (keep node-by-address
                             (.addresses
                              ^org.replikativ.persistent_sorted_set.Branch
                              node))))))]
    (vec (walk root))))

(let [archive-samples (resolved 'archive-samples)
      sample-row (resolved 'sample-row)
      object-headers (resolved 'object-headers)
      datahike-serializer (resolved 'datahike-serializer)
      read-object-value (resolved 'read-object-value)
      db-record? (resolved 'db-record?)
      samples (archive-samples)
      rows (mapv sample-row samples)
      caps (seon.config/result-caps (seon.config/defaults))
      blob-smaller?
      (var-get (ns-resolve 'seon.cluster.loop 'result-blob-smaller?))
      window-edn
      (fn [result-edn]
        (seon.render.value/result-window-edn
         {:seon.sci.admit/caps caps
          :seon.render.value/options
          {:seon.render.value/max-collection 8}}
         result-edn))
      root-names ["333214a0f358" "d0cd8fbc1fa3"]
      summaries
      (mapv
       (fn [root-name]
         (let [root-rows
               (filterv #(= root-name (:seon.store.growth/root-name %)) rows)
               sample-keys
               (set (map (comp keyword :seon.eval.drive/cluster) root-rows))
               grading-keys
               (set (map (comp keyword :seon.eval.drive/grading-branch)
                         root-rows))
               serializer (datahike-serializer)
               objects (object-headers root-name)
               decoded
               (into {}
                     (keep
                      (fn [object]
                        (let [object-key (:konserve.object/key object)]
                          (when (not= :binary
                                      (:konserve.object/value-type object))
                            [object-key
                             (read-object-value serializer object)]))))
                     objects)
               node-by-address
               (into {}
                     (filter
                      (fn [[_ value]]
                        (instance?
                         org.replikativ.persistent_sorted_set.ANode value)))
                     decoded)
               branch-values
               (fn [branch-keys]
                 (mapcat
                  (fn [branch-key]
                    (let [record (get decoded branch-key)]
                      (when (db-record? record)
                        (result-values (:eavt-root record) node-by-address))))
                  branch-keys))
               sample-values (vec (branch-values sample-keys))
               grading-values (vec (branch-values grading-keys))
               object-count
               (fn [values threshold]
                 (count (into #{} (filter #(< threshold (count %))) values)))
               shape-object-count
               (fn [values threshold]
                 (count
                  (into #{}
                        (filter
                         (fn [result-edn]
                           (and (< threshold (count result-edn))
                                (blob-smaller? result-edn
                                               (window-edn result-edn)))))
                        values)))]
           {:root root-name
            :samples (count root-rows)
            :sample-result-count (count sample-values)
            :grading-result-count (count grading-values)
            :grading-over-343
            (count (filter #(< 343 (count %)) grading-values))
            :grading-over-4096
            (count (filter #(< 4096 (count %)) grading-values))
            :grading-blob-objects-at-343 (object-count grading-values 343)
            :grading-blob-objects-at-4096 (object-count grading-values 4096)
            :grading-shape-objects-at-343
            (shape-object-count grading-values 343)
            :grading-shape-objects-at-4096
            (shape-object-count grading-values 4096)
            :decoded-node-count (count node-by-address)
            :grading-min (when (seq grading-values)
                           (apply min (map count grading-values)))
            :grading-max (when (seq grading-values)
                           (apply max (map count grading-values)))}))
       root-names)]
  (prn {:sample-count (count samples)
        :roots summaries
        :totals
        (apply merge-with +
               (map #(select-keys
                      %
                      [:samples
                       :sample-result-count
                       :grading-result-count
                       :grading-over-343
                       :grading-over-4096
                       :grading-blob-objects-at-343
                       :grading-blob-objects-at-4096
                       :grading-shape-objects-at-343
                       :grading-shape-objects-at-4096])
                    summaries))}))

(shutdown-agents)
