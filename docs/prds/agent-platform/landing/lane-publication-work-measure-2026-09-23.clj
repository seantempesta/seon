;; Source-build phase timings for a leaf publication (lane publication-work).
;; Run from a checkout snapshot (git archive + reference-code links + shared .clj-kondo/.cache):
;;   GIT_DIR=<repo>/.git clojure -M:test -e '(load-file "<this file>")'
(require '[clojure.java.io :as io]
         '[seon.cluster.source :as source]
         '[seon.fn.analyzer :as analyzer]
         '[seon.test.cache :as test.cache])
(defn ms [f] (let [t0 (System/nanoTime) r (f)] [(/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0) r]))
(def dir (.getCanonicalPath (io/file ".")))
(def leaf "src/seon/eval.clj")
(def leaf-path (.getCanonicalPath (io/file leaf)))
(def roots ["src" "test" "resources" "script"])
(def entry-count #(count (filter (fn [f] (.endsWith (.getName f) ".transit.json"))
                                 (file-seq (io/file ".clj-kondo/.cache/v1")))))
(when (< (entry-count) 100)
  (let [[warm-ms _] (ms #(analyzer/analyze {::analyzer/paths [(str dir "/src") (str dir "/test")]}))]
    (prn {:populate-shared-cache-ms warm-ms :entries (entry-count)})))
(let [inventory (source/discover-paths dir roots)
      [observed _] (source/capture-paths dir inventory)
      text (slurp leaf)
      analyze-leaf #(analyzer/analyze {::analyzer/sources {leaf-path (str text "\n;; " (random-uuid) "\n")}})
      rows (vec (for [_ (range 5)]
                  (let [[capture-ms _] (ms #(source/capture-paths dir [leaf]))
                        [gitlinks-ms _] (ms #(test.cache/gitlink-digests dir))
                        [roots-ms input-roots] (ms #(test.cache/input-roots dir))
                        [whole-ms whole] (ms #(test.cache/test-input-digest dir (test.cache/input-digests dir)))
                        snapshot-fn (resolve 'seon.cluster.source/snapshot-test-input-digest)
                        [derived-ms derived] (if snapshot-fn
                                               (ms #(snapshot-fn {:seon.fn/root dir :seon.cluster.source/roots input-roots
                                                                  :seon.source/relative-file-digests observed}))
                                               [nil nil])
                        [reuse-ms reuse] (if snapshot-fn
                                           (ms #(snapshot-fn {:seon.fn/root dir :seon.cluster.source/roots input-roots
                                                              :seon.source/relative-file-digests observed
                                                              :seon.cluster.source/published whole
                                                              :seon.source/changed-paths [leaf]}))
                                           [nil nil])
                        [analyze-ms analysis] (ms analyze-leaf)]
                    {:capture-leaf capture-ms :gitlinks gitlinks-ms :input-roots roots-ms
                     :whole-test-input-digest whole-ms
                     :derived-test-input-digest derived-ms
                     :derived-equal? (when derived (= whole (:seon.source/test-input-digest derived)))
                     :derived-read (when derived (count (::source/read derived)))
                     :derived-hits (when derived (::source/hits derived))
                     :reused-test-input-digest reuse-ms
                     :kondo-analyze-leaf analyze-ms
                     :kondo-cache (when-let [c (::analyzer/cache analysis)]
                                    (update c ::analyzer/stale count))})))]
  (doseq [r rows] (prn r))
  (prn {:entries (entry-count)}))
(shutdown-agents)
(System/exit 0)
