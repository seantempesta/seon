(ns seon.render.value-test
  "Behavioral tests for the structural value renderer (`seon.render.value`).

   We pin MECHANISM, not exact strings (the format will keep iterating):
   bounds are respected, paths survive, opaque handles project, lazy seqs
   never over-realize, the drill hint appears iff the view is partial."
  (:require
    [cljs.test :as t :refer [deftest is testing]]
    [clojure.string :as str]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.render.value :as v]
    [seon.schema :as schema]))

(def configuration (config/resolve-config-singleton {}))

;; Stand-ins for opaque runtime handles (the real ones are datahike's).
(defrecord FakeDB [max-tx max-eid])
(deftype FakeDatom [e a vv]
  ILookup
  (-lookup [_ k] (case k :e e :a a :v vv nil))
  (-lookup [_ k nf] (case k :e e :a a :v vv nf)))

(deftype CountingMap [n visits value-at]
  IMap
  (-dissoc [this _] this)
  ICounted
  (-count [_] n)
  ISeqable
  (-seq [_]
    (letfn [(entries [i]
              (lazy-seq
                (when (< i n)
                  (swap! visits inc)
                  (cons [(keyword (str "k" i)) (value-at i)]
                        (entries (inc i))))))]
      (entries 0))))

(deftype UncountedMap [n visits value-at]
  IMap
  (-dissoc [this _] this)
  ISeqable
  (-seq [_]
    (letfn [(entries [i]
              (lazy-seq
                (when (< i n)
                  (swap! visits inc)
                  (cons [(keyword (str "u" i)) (value-at i)]
                        (entries (inc i))))))]
      (entries 0))))

(deftype KeyedCountingMap [n visits key-at value-at]
  IMap
  (-dissoc [this _] this)
  ICounted
  (-count [_] n)
  ISeqable
  (-seq [_]
    (letfn [(entries [i]
              (lazy-seq
                (when (< i n)
                  (swap! visits inc)
                  (cons [(key-at i) (value-at i)]
                        (entries (inc i))))))]
      (entries 0))))

(deftype HugePrintedRecord [writes]
  IRecord
  IPrintWithWriter
  (-pr-writer [_ writer _]
    (let [chunk (apply str (repeat 1024 "x"))]
      (dotimes [_ 102400]
        (swap! writes + (count chunk))
        (-write writer chunk)))))

(deftype ThrowingPrintedValue []
  IPrintWithWriter
  (-pr-writer [_ _ _]
    (throw (js/Error. "unrelated printer failure"))))

(defn- sampled-map [sampled]
  (into {}
        (map (fn [[k v]]
               [k (if (and (map? v)
                           (contains? v :seon.render.value/map-entries))
                    (sampled-map v)
                    v)]))
        (:seon.render.value/map-entries sampled)))

(defn- with-active-projection [forms body]
  (let [before (schema/snapshot-state)]
    (try
      (schema/activate-projection! (schema/build-projection forms))
      (body)
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

;; ============================================================
;; sample — depth + breadth bounds, marker shapes.
;; ============================================================

(deftest small-value-fully-shown
  (testing "a small value samples to itself (no markers)"
    (is (= [1 2 3]
           (:seon.render.value/shown (v/sample configuration [1 2 3] {}))))
    (is (= {:a 1 :b 2}
           (sampled-map (v/sample configuration {:a 1 :b 2} {}))))))

(deftest breadth-bound-on-vectors
  (testing "a wide vector keeps max-items elements + an exact elided tail"
    (let [skel (v/sample configuration (vec (range 100)) {:max-items 8})]
      (is (= 8 (count (:seon.render.value/shown skel))))
      (is (= 92 (:seon.render.value/elided skel))))))

(deftest breadth-bound-on-maps
  (testing "a wide map keeps max-keys entries + an elided-keys count"
    (let [m    (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 20)))
          skel (v/sample configuration m {:max-keys 6})]
      (is (= 6 (count (:seon.render.value/map-entries skel))))
      (is (= 14 (:seon.render.value/elided-keys skel))))))

(deftest million-entry-map-work-is-bounded
  (let [entry-visits  (atom 0)
        child-touches (atom 0)
        poison-touches (atom 0)
        n 1000000
        k 8
        work-budget 32
        value-at (fn [i]
                   (if (< i work-budget)
                     (map (fn [x] (swap! child-touches inc) x) [i])
                     (map (fn [_]
                            (swap! poison-touches inc)
                            (throw (js/Error. "poison beyond map budget")))
                          [i])))
        m (CountingMap. n entry-visits value-at)
        skel (v/sample configuration m {:max-keys k
                                        :max-map-visits work-budget})]
    (is (<= @entry-visits (inc work-budget))
        "only the bounded candidate window plus tail sentinel is enumerated")
    (is (<= @child-touches work-budget)
        "only candidate values are recursively sampled")
    (is (zero? @poison-touches)
        "the tail sentinel and every later value remain untouched")
    (is (= k (count (:seon.render.value/map-entries skel))))
    (is (= (- n k) (:seon.render.value/elided-keys skel)))))

(deftest projected-key-index-work-stays-inside-the-map-visit-budget
  (let [entry-visits (atom 0)
        key-writes (atom 0)
        work-budget 16
        child-touches (atom 0)
        m (KeyedCountingMap.
            1000000 entry-visits
            (fn [i]
              (if (even? i)
                (keyword (str "safe" i))
                (HugePrintedRecord. key-writes)))
            (fn [i]
              (map (fn [x] (swap! child-touches inc) x) [i])))
        skel (v/sample configuration m {:max-keys 6
                                        :max-map-visits work-budget})]
    (is (<= @entry-visits (inc work-budget))
        "index derivation never revisits the source map")
    (is (<= @child-touches work-budget)
        "only candidate children are sampled")
    (is (zero? @key-writes)
        "retained and discarded hostile key printers remain untouched")
    (is (seq (:seon.render.value/non-drillable-key-indexes skel)))
    (is (every? #(< % (count (:seon.render.value/map-entries skel)))
                (:seon.render.value/non-drillable-key-indexes skel)))))

(deftest uncounted-map-work-is-bounded-with-an-honest-unknown-tail
  (let [entry-visits (atom 0)
        child-touches (atom 0)
        work-budget 12
        m (UncountedMap. 1000000 entry-visits
                         (fn [i]
                           (map (fn [x] (swap! child-touches inc) x) [i])))
        skel (v/sample configuration m {:max-keys 4
                                        :max-map-visits work-budget})]
    (is (<= @entry-visits (inc work-budget)))
    (is (<= @child-touches work-budget))
    (is (= 4 (count (:seon.render.value/map-entries skel))))
    (is (= :more (:seon.render.value/elided-keys skel)))))

(deftest bounded-map-projection-is-deterministic-and-collision-free
  (let [pairs (mapv (fn [i] [(keyword (str "k" i)) {:row/id i}]) (range 1000))
        render #(pr-str (v/sample configuration % {:max-keys 8
                                                   :max-map-visits 32}))
        a (into {} pairs)
        b (into {} (reverse pairs))
        projected-pairs (mapv (fn [i]
                                [(if (even? i)
                                   [(keyword (str "display" i))]
                                   (keyword (str "path" i)))
                                 {:row/id i}])
                              (range 24))
        projected-a (v/sample configuration (into {} projected-pairs)
                              {:max-keys 8 :max-map-visits 24})
        projected-b (v/sample configuration (into {} (reverse projected-pairs))
                              {:max-keys 8 :max-map-visits 24})
        reserved {:seon.render.value/elided-keys 99 :ordinary/value 1}
        reserved-out (v/render-ai configuration "reserved" reserved)]
    (is (= (render a) (render b))
        "ordinary persistent hash-map iteration is insertion-independent")
    (is (= 1 (count (set (repeatedly 25 #(render a))))))
    (is (= (pr-str projected-a) (pr-str projected-b))
        "insertion-equivalent projected keys produce identical bytes")
    (is (= (:seon.render.value/non-drillable-key-indexes projected-a)
           (:seon.render.value/non-drillable-key-indexes projected-b)))
    (is (= (:seon.render.value/non-drillable-key-indexes projected-a)
           (vec (sort (:seon.render.value/non-drillable-key-indexes
                        projected-a)))))
    (is (= reserved (sampled-map (v/sample configuration reserved {})))
        "every user key stays inside the explicit entry collection")
    (is (= (pr-str reserved) reserved-out)
        "the reserved-looking user key does not fabricate a partial view")))

(deftest opaque-and-huge-map-keys-force-safe-bounded-projections
  (let [writes (atom 0)
        opaque-key (HugePrintedRecord. writes)
        opaque-ai (v/render-ai configuration "opaque-key" {opaque-key 1})
        opaque-html (v/render-html-data configuration "opaque-key" {opaque-key 1})
        projected-key (-> opaque-html
                          :seon.render.value/tree
                          :seon.render.value/map-entries
                          first first)
        huge-key (apply str (repeat 1000000 "k"))
        huge-ai (v/render-ai configuration "huge-key" {huge-key 1})]
    (is (zero? @writes) "opaque map-key printers are never invoked")
    (is (str/includes? opaque-ai "partial view"))
    (is (= "seon.render.value-test/HugePrintedRecord"
           (:seon.eval/opaque projected-key)))
    (is (not (identical? opaque-key projected-key))
        "the ordinary HTML projection carries no host-object key")
    (is (zero? @writes))
    (is (< (count huge-ai) 600))
    (is (str/includes? huge-ai "map-key/string"))
    (is (str/includes? huge-ai "partial view"))))

(deftest map-key-drillability-is-output-local-and-honest
  (let [huge (apply str (repeat 1000000 "h"))
        raw-children (atom {})
        source (array-map
                 :safe/keyword 1
                 "short" 2
                 [:collection] 3
                 huge 4
                 -7.5 5
                 false 6)
        skel (v/sample configuration source {:max-keys 5
                                             :max-map-visits 6})
        entries (:seon.render.value/map-entries skel)
        indexes (:seon.render.value/non-drillable-key-indexes skel)
        index-set (set indexes)]
    (is (= indexes (vec (sort indexes)))
        "indexes ascend in final retained output order")
    (is (every? #(< % (count entries)) indexes))
    (doseq [[i [display-key sampled-child]] (map-indexed vector entries)]
      (if (contains? index-set i)
        (is (map? display-key) "a display-only key exposes no path component")
        (do
          (swap! raw-children assoc display-key (get source display-key))
          (is (= sampled-child (get-in source [display-key]))
              "every unmarked displayed key is the exact original lookup key"))))
    (is (seq indexes))
    (is (= @raw-children
           (into {}
                 (keep-indexed
                   (fn [i [k _]]
                     (when-not (contains? index-set i)
                       [k (get source k)])))
                 entries)))
    (is (not (str/includes? (pr-str skel) huge))
        "the unsafe original huge key never enters the returned skeleton")))

(deftest non-finite-and-negative-zero-map-keys-are-display-only
  (doseq [k [js/NaN js/Infinity js/-Infinity (/ -1 js/Infinity)]]
    (let [skel (v/sample configuration {k :child} {})]
      (is (= [0] (:seon.render.value/non-drillable-key-indexes skel)))
      (is (= "map-key/number"
             (get-in skel [:seon.render.value/map-entries 0 0
                           :seon.eval/opaque]))))))

(deftest direct-error-maps-use-ordinary-map-sampling
  (let [error {:seon.error/message "writer unavailable"
               :seon.error/kind :system
               :seon.error/data {:operation :transact}}
        sampled (sampled-map (v/sample configuration error {}))]
    (is (= error sampled))
    (is (not (contains? sampled :seon.db/ok?)))
    (is (not (contains? sampled :seon.db/error)))))

(deftest depth-bound-prunes-nested
  (testing "nesting past max-depth becomes a typed+counted prune marker"
    (let [skel (v/sample configuration {:a {:b {:c {:d 1 :e 2}}}}
                         {:max-depth 3})
          c    (get-in (sampled-map skel) [:a :b :c])]
      (is (= :map (:seon.render.value/pruned c)))
      (is (= 2 (:seon.render.value/count c))))))

(deftest empty-colls-not-pruned-at-depth
  (testing "an empty coll at the depth boundary renders verbatim, not a marker"
    (let [skel (v/sample configuration {:a {:b {:c []}}} {:max-depth 3})
          c (get-in (sampled-map skel) [:a :b :c])]
      (is (= [] (:seon.render.value/shown c))))))

(deftest navigation-paths-preserved
  (testing "a path read off the skeleton resolves on the LIVE value"
    (let [live {:api/results [{:user/id 1 :user/name "John"}
                              {:user/id 2 :user/name "Jane"}]}
          skel (v/sample configuration live {})
          results (:api/results (sampled-map skel))]
      ;; key + index retained → get-in path is identical on both
      (is (= 1 (-> results :seon.render.value/shown first sampled-map :user/id)))
      (is (= "John" (get-in live [:api/results 0 :user/name]))))))

;; ============================================================
;; lazy safety + homogeneity.
;; ============================================================

(deftest lazy-seq-never-over-realized
  (testing "an infinite seq samples to a bounded head + :more, no hang"
    (let [realized (atom 0)
          s        (map (fn [i] (swap! realized inc) i) (range))
          skel     (v/sample configuration s {:max-items 8})]
      (is (= 8 (count (:seon.render.value/shown skel))))
      (is (= :more (:seon.render.value/elided skel)))
      ;; head+1 probe only — never the whole infinite seq
      (is (<= @realized 50)))))

(deftest poisoned-lazy-seq-never-crashes-the-walk
  ;; Regression — T4 D1 pod crash (error-workflow 2026-07-06). An agent eval
  ;; can return a lazy seq that THROWS when forced, e.g. `(keys non-map)` →
  ;; a KeySeq whose -first calls `(key non-map-entry)`. The eval records
  ;; `ok? true` (lazy, unrealized); forcing it in the renderer must NOT
  ;; propagate — a propagated throw is recorded `:core` and CRASHES the pod.
  (testing "sample degrades a throw-on-realize seq to an opaque marker"
    (let [skel (v/sample configuration (keys [[1 2] [3 4]]) {})]
      (is (contains? skel :seon.eval/opaque))
      (is (str/includes? (:seon.eval/opaque skel) "realization threw"))))
  (testing "render-ai NEVER throws on a poisoned value — top / nested / deep"
    (doseq [val [(keys [[1 2] [3 4]])
                 (vals [[1 2]])
                 {:a (map (fn [_] (throw (js/Error. "boom"))) [1 2 3])}
                 {:a {:b (keys [[9 9]])}}]]
      (let [out (v/render-ai configuration "rid" val)]
        (is (string? out))
        (is (str/includes? out "result/rid")))))
  (testing "a normal value still renders verbatim (guard is inert)"
    (is (= "{:a 1, :b [1 2 3]}"
           (v/render-ai configuration "n" {:a 1 :b [1 2 3]})))))

(deftest homogeneous-collection-shows-shared-keys
  (testing "a big collection of uniform maps carries its shared key-set"
    (let [rows (mapv (fn [i] {:seon.fn/name (str "f" i) :seon.fn/arity (mod i 3)})
                     (range 40))
          skel (v/sample configuration rows {:max-items 5})]
      (is (= [:seon.fn/arity :seon.fn/name] (:seon.render.value/shape skel)))
      (is (= 35 (:seon.render.value/elided skel))))))

;; ============================================================
;; opaque handles + long strings.
;; ============================================================

(deftest datahike-db-projects-to-opaque-marker
  (let [skel (v/sample configuration (->FakeDB 42 99) {})]
    (is (= "datahike/DB" (:seon.eval/opaque skel)))
    (is (str/includes? (:seon.eval/summary skel) "max-tx=42"))))

(deftest datom-projects-to-datom-marker
  (let [skel (v/sample configuration (FakeDatom. 42 :user/name "Jane") {})]
    (is (= [42 :user/name "Jane"] (:seon.eval/datom skel)))))

(deftest opaque-handle-nested-in-collection-is-projected
  (testing "an opaque node inside a vector is sanitized, not just a top-level one"
    (let [skel (v/sample configuration [(->FakeDB 7 7) :ok] {})]
      (is (= "datahike/DB" (:seon.eval/opaque (first (:seon.render.value/shown skel))))))))

(deftest opaque-values-never-invoke-arbitrary-printers
  (let [writes (atom 0)
        marker (v/sample configuration (HugePrintedRecord. writes) {})]
    (is (string? (:seon.eval/opaque marker)))
    (is (<= (count (:seon.eval/opaque marker)) 80))
    (is (nil? (:seon.eval/summary marker)))
    (is (zero? @writes)
        "a logical 100 MiB printer is never entered for an opaque value")))

(deftest capped-printer-bounds-ordinary-data-and-propagates-real-failures
  (let [huge (apply str (repeat 1000000 "x"))
        nested {:payload huge :after :still-bounded}
        out (tokens/bounded-pr-str nested 20)]
    (is (<= (count out) 81))
    (is (str/ends-with? out "…"))
    (is (= "…" (tokens/bounded-pr-str nested 0)))
    (is (try
          (tokens/bounded-pr-str (ThrowingPrintedValue.) 20)
          false
          (catch :default e
            (= "unrelated printer failure" (.-message e)))))))

(deftest datom-value-is-sampled-through-the-same-bounds
  (let [payload (apply str (repeat 1000 "x"))
        marker (v/sample configuration (FakeDatom. 42 :demo/value payload)
                         {:max-string 20})
        sampled-value (get-in marker [:seon.eval/datom 2])]
    (is (= 1000 (:seon.render.value/string-len sampled-value)))
    (is (<= (count (:seon.render.value/head sampled-value)) 20))
    (let [rendered (v/render-ai configuration "datom-long"
                                (FakeDatom. 42 :demo/value payload))]
      (is (< (count rendered) 500))
      (is (str/includes? rendered "tokens⟩")))))

(deftest long-string-clipped-with-length
  (let [skel (v/sample configuration (apply str (repeat 300 "x"))
                       {:max-string 80})]
    (is (= 300 (:seon.render.value/string-len skel)))
    (is (<= (count (:seon.render.value/head skel)) 80))))

(deftest huge-named-scalars-never-reach-raw-pr-str
  (let [huge-name (apply str (repeat 1000000 "n"))
        huge-keyword (keyword "demo" huge-name)
        huge-symbol (symbol "demo" huge-name)]
    (doseq [x [huge-keyword huge-symbol]]
      (let [out (v/render-ai configuration "huge-named" x)]
        (is (< (count out) 500))
        (is (str/includes? out "partial view"))))))

;; ============================================================
;; project-plain — the UNBOUNDED reader-safe projection (the read-side net
;; reused by seon.eval/sanitize-result-edn). Opaque → marker, plain
;; survives, full structure preserved (no breadth/depth bound).
;; ============================================================

(deftest project-plain-leaves-plain-data-untouched
  (testing "scalars + plain collections (incl. #inst) survive verbatim, unbounded"
    (let [plain {:a [1 2 3] :b #{:x :y} :c {:d (vec (range 100))}
                 :t #inst "2020-01-01"}]
      (is (= plain (v/project-plain plain)))
      ;; UNbounded — every one of the 100 elements is kept (unlike `sample`)
      (is (= 100 (count (get-in (v/project-plain plain) [:c :d])))))))

(deftest project-plain-projects-opaque-nodes-to-markers
  (testing "a datahike-shaped handle / datom becomes a compact marker"
    (is (= "datahike/DB" (:seon.eval/opaque (v/project-plain (->FakeDB 5 5)))))
    (is (= [1 :user/name "Jo"] (:seon.eval/datom (v/project-plain (FakeDatom. 1 :user/name "Jo")))))))

(deftest project-plain-projects-opaque-nested-in-collections
  (testing "an opaque node nested in a coll is projected; plain siblings survive"
    (let [out (v/project-plain {:keep [1 2] :db (->FakeDB 7 7)})]
      (is (= [1 2] (:keep out)))
      (is (= "datahike/DB" (:seon.eval/opaque (:db out))))
      ;; round-trips through pr-str (the sanitize-result-edn use)
      (is (string? (pr-str out))))))

;; ============================================================
;; render-ai — text composition + the drill hint contract.
;; ============================================================

(deftest prepared-ai-reuses-one-lazy-realization-across-eval-ids
  (testing "preparation owns lazy effects; ID formatting reads immutable data"
    (let [realized       (atom 0)
          raw            (map (fn [i]
                                (swap! realized inc)
                                {:row/id i})
                              (range 2000))
          prepared       (v/prepare-ai {:seon.config/configuration configuration
                                        ::v/value raw})
          after-prepare  @realized
          first-out      (v/format-ai {::v/eval-id "first-id"
                                       ::v/prepared prepared})
          after-first    @realized
          second-out     (v/format-ai {::v/eval-id "second-id"
                                       ::v/prepared prepared})
          after-second   @realized]
      (is (pos? after-prepare) "the raw lazy value was actually sampled")
      (is (< after-prepare 2000) "preparation remains bounded")
      (is (= after-prepare after-first after-second)
          "formatting two allocator candidates performs no further realization")
      (is (str/includes? first-out "partial view"))
      (is (str/includes? first-out "result/first-id"))
      (is (not (str/includes? first-out "result/second-id")))
      (is (str/includes? second-out "partial view"))
      (is (str/includes? second-out "result/second-id"))
      (is (not (str/includes? second-out "result/first-id")))
      (is (every? qualified-keyword? (keys prepared))
          "the prepared contract has only fully namespaced keys"))))

(deftest render-ai-small-value-has-no-hint
  (testing "a fully-shown value renders verbatim, no partial-view hint"
    (let [out (v/render-ai configuration "abc" [1 2 3])]
      (is (= "[1 2 3]" out))
      (is (not (str/includes? out "partial view"))))))

(deftest render-ai-small-deep-renders-whole
  (testing "a small but deep/long value prints VERBATIM — the agent sees the
            real nesting of its own stored data, not {…N keys}/\"…\""
    (let [v   {:name "widget" :stock {:warehouse {:shelf {:bin 42}}}
               :note (apply str (repeat 90 "x"))}
          out (v/render-ai configuration "s2" v)]
      (is (= (pr-str v) out))
      (is (not (str/includes? out "partial view")))
      (is (not (str/includes? out "…"))))))

(deftest render-ai-truncated-names-the-live-var
  (testing "a clipped value points the agent at result/<id> for the whole value"
    (let [out (v/render-ai configuration "xyz123" (vec (range 2000)))]
      (is (str/includes? out "partial view"))
      (is (str/includes? out "result/xyz123"))
      (is (str/includes? out "get-in")))))

(deftest map-elision-keeps-smallest-load-bearing-keys
  (testing "over the key bound, tiny keys (hashes/counts) survive and the bulk
            payload strings are elided — ranked by rendered size, not first-N"
    (let [big  (apply str (repeat 400 "X"))   ; huge payload
          mid  (apply str (repeat 30 "m"))    ; medium filler (> a hash)
          m    (into {:seon.agent.shell/out-blob "c4685deadbeefc4685deadbeef"
                      :seon.agent.shell/err-tokens 17}
                     (concat [[:payload-a big] [:payload-b big] [:payload-c big]]
                             (for [i (range 8)] [(keyword (str "f" i)) mid])))
          out  (v/render-ai configuration "eid1" m)]
      ;; the two tiny load-bearing keys survive
      (is (str/includes? out "c4685deadbeefc4685deadbeef"))
      (is (str/includes? out "err-tokens"))
      ;; the huge payloads are elided (never rendered whole)
      (is (not (str/includes? out big)))
      ;; honest elision marker
      (is (str/includes? out "more keys"))
      ;; every retained key still resolves against the live value (path valid)
      (is (str/includes? out "out-blob")))))

(deftest dominant-string-renders-as-body-not-stub
  (testing "a map whose payload is ONE dominant string (a read function's content)
            renders that string as a bounded BODY BLOCK — many lines, honest
            ⟨N tokens⟩, header keys intact — not a 2-line stub (O1)"
    (let [content (apply str (for [i (range 1 54)]
                               (str " " i "\t# line " i " of the file body\n")))
          env     {:seon.agent.fs/ok? true
                   :seon.agent.fs/path "/testbed/two_bucket.py"
                   :seon.agent.fs/content content
                   :seon.agent.fs/from-line 1
                   :seon.agent.fs/lines-returned 53
                   :seon.agent.fs/total-lines 53
                   :seon.agent.fs/file-sha "f1b6e41cabc123"}
          out     (v/render-ai configuration "yPy-1" env)]
      ;; a real body is shown — not just the first ~80 chars (the old stub
      ;; stopped around line 3; the body now reaches deep into the file)
      (is (str/includes? out "line 30 of the file body"))
      ;; honest truncation marker on the dominant string
      (is (str/includes? out "tokens⟩"))
      ;; header keys survive verbatim next to the body
      (is (str/includes? out "f1b6e41cabc123"))
      (is (str/includes? out ":seon.agent.fs/total-lines 53"))
      ;; recovery handle present (result/<id> + keep + get-in)
      (is (str/includes? out "result/yPy-1"))
      (is (str/includes? out "get-in")))))

(deftest dominant-rule-does-not-fire-on-many-similar-strings
  (testing "a map of several comparably-sized strings has NO dominant payload,
            so each stays inline-clipped — the body-block rule must not fire"
    (let [s   (fn [n] (apply str (repeat 500 (str n))))
          m   {:a (s 1) :b (s 2) :c (s 3)}
          out (v/render-ai configuration "m1" m)]
      ;; no single string is shown as a 500-char body block
      (is (not (str/includes? out (s 1))))
      (is (not (str/includes? out (s 2))))
      ;; still a partial view with the handle
      (is (str/includes? out "result/m1")))))

(deftest render-ai-hint-teaches-durability-promotion
  (testing "a partial view's drill hint names BOTH recovery and the my.blob/put!
            keep idiom when a result id exists"
    (let [out (v/render-ai configuration "keep1" (vec (range 2000)))]
      (is (str/includes? out "partial view"))
      ;; recovery idiom
      (is (str/includes? out "get-in"))
      ;; durability idiom
      (is (str/includes? out "keep:"))
      (is (str/includes? out "my.blob/put! result/keep1")))))

(deftest render-ai-long-string-reports-length
  (let [out (v/render-ai configuration "s1"
                         (apply str (repeat 2000 "x")))]
    (is (str/includes? out "tokens⟩"))
    (is (str/includes? out "result/s1"))))

(deftest render-ai-output-is-bounded
  (testing "even a huge deeply-nested value renders to a small bounded string"
    (let [huge (vec (repeat 500 (into {} (map (fn [i] [(keyword (str "k" i))
                                                       (vec (range 50))])
                                              (range 30)))))
          out  (v/render-ai configuration "big" huge)]
      (is (< (count out) 4000)))))

(deftest render-ai-never-emits-fences-or-backticks
  (testing "output stays valid comment prose (no ``` / ` that break the eval'able context)"
    (let [out (v/render-ai configuration "h"
                           {:a (vec (range 100)) :b "x"})]
      (is (not (str/includes? out "`"))))))

;; ============================================================
;; render-html-data — the U panel DATA CONTRACT.
;; ============================================================

(deftest html-data-contract-shape
  (let [data (v/render-html-data configuration "eid42" (vec (range 100)))]
    (is (= "eid42" (:seon.render.value/eval-id data)))
    (is (true? (:seon.render.value/truncated? data)))
    (is (string? (:seon.render.value/summary data)))
    (is (contains? data :seon.render.value/tree))
    ;; the tree is the same skeleton render-ai emits
    (is (= (v/sample configuration (vec (range 100)) {})
           (:seon.render.value/tree data)))))

(deftest html-data-samples-once-and-returns-the-identical-skeleton
  (let [calls (atom 0)
        skeleton {:seon.render.value/kind :vector
                  :seon.render.value/shown [1]
                  :seon.render.value/elided 1}]
    (with-redefs [v/sample (fn [_ _ _]
                             (swap! calls inc)
                             skeleton)
                  schema/candidate-shapes (constantly [])]
      (let [data (v/render-html-data configuration "one-pass" [1 2])]
        (is (= 1 @calls))
        (is (identical? skeleton (:seon.render.value/tree data)))
        (is (= [] (:seon.render.value/schemas data)))))))

(deftest html-data-schema-status-is-activated-ordered-and-invalid-only
  (let [a :value-test.schema/a
        b :value-test.schema/b
        alpha :value-test.schema/alpha
        beta :value-test.schema/beta
        specific :value-test.schema/specific
        forms {a :string
               b :int
               alpha [:map [a a]]
               beta [:map [a a]]
               specific [:map [a a] [b b]]}]
    (with-active-projection
      forms
      (fn []
        (let [valid (v/render-html-data configuration "valid" {a "yes" b 7})
              invalid (v/render-html-data configuration "invalid"
                                          {a 42 b "wrong"})]
          (is (= [[specific :valid] [alpha :valid] [beta :valid]]
                 (mapv (juxt :seon.schema/key
                             :seon.render.value/status)
                       (:seon.render.value/schemas valid))))
          (is (not (contains? valid :seon.render.value/explanation)))
          (is (= [[specific :invalid]]
                 (mapv (juxt :seon.schema/key
                             :seon.render.value/status)
                       (:seon.render.value/schemas invalid))))
          (is (map? (get-in invalid [:seon.render.value/explanation
                                     :seon.render.value/humanized])))
          (is (map? (get-in invalid [:seon.render.value/explanation
                                     :seon.render.value/error-value]))))))))

(deftest html-data-every-partial-marker-forbids-validation-and-explanation
  (let [sample-calls (atom 0)
        candidate-calls (atom 0)
        matching-calls (atom 0)
        explainer-calls (atom 0)
        row {:seon.schema/key :value-test.partial/shape
             :seon.schema/entity? false}
        skeletons
        [{:seon.render.value/kind :seq
          :seon.render.value/shown [] :seon.render.value/elided 1}
         {:seon.render.value/map-entries []
          :seon.render.value/elided-keys 1}
         {:seon.render.value/map-entries [[:safe 1]]
          :seon.render.value/non-drillable-key-indexes [0]}
         {:seon.render.value/pruned :map :seon.render.value/count 1}
         {:seon.eval/opaque "host/value"}
         {:seon.eval/datom [1 :value-test.partial/a 2]}
         {:seon.render.value/string-len 100
          :seon.render.value/head "head"}]]
    (with-redefs [v/sample (fn [_ _ _]
                             (let [skeleton (nth skeletons @sample-calls)]
                               (swap! sample-calls inc)
                               skeleton))
                  schema/candidate-shapes (fn [_]
                                            (swap! candidate-calls inc)
                                            [row])
                  schema/matching-shapes (fn [_]
                                           (swap! matching-calls inc)
                                           [row])
                  schema/explain-shape (fn [_ _]
                                         (swap! explainer-calls inc)
                                         {:errors [:unsafe]})]
      (doseq [i (range (count skeletons))]
        (let [data (v/render-html-data configuration (str "partial-" i)
                                       {:value-test.partial/a "value"})]
          (is (true? (:seon.render.value/truncated? data)))
          (is (= [[:value-test.partial/shape :shape-only]]
                 (mapv (juxt :seon.schema/key
                             :seon.render.value/status)
                       (:seon.render.value/schemas data))))
          (is (not (contains? data :seon.render.value/explanation)))))
      (is (= (count skeletons) @sample-calls))
      (is (= (count skeletons) @candidate-calls))
      (is (zero? @matching-calls))
      (is (zero? @explainer-calls)))))

(deftest html-data-million-entry-map-and-schema-work-are-bounded
  (let [shared :k0
        shapes (into {}
                     (map (fn [i]
                            [(keyword "value-test.bound" (str "shape-" i))
                             [:map [shared shared]]]))
                     (range 100))
        forms (assoc shapes shared :int)]
    (with-active-projection
      forms
      (fn []
        (let [entry-visits (atom 0)
              child-touches (atom 0)
              poison-touches (atom 0)
              schema-visits (atom 0)
              value (CountingMap.
                      1000000 entry-visits
                      (fn [i]
                        (if (< i schema/shape-input-key-limit)
                          (map (fn [x] (swap! child-touches inc) x) [i])
                          (map (fn [_]
                                 (swap! poison-touches inc)
                                 (throw (js/Error. "poison beyond budget")))
                               [i]))))
              data (binding [schema/*candidate-visit!*
                             (fn [_] (swap! schema-visits inc))]
                     (v/render-html-data configuration "million" value))]
          (is (<= @entry-visits
                  (+ (inc 32) schema/shape-input-key-limit))
              "sampler head+tail and schema input windows are both bounded")
          (is (zero? @poison-touches)
              "neither bounded pass touches the value beyond its window")
          (is (<= @child-touches 32))
          (is (= schema/shape-candidate-limit @schema-visits))
          (is (= schema/shape-candidate-limit
                 (count (:seon.render.value/schemas data))))
          (is (every? #(= :shape-only
                          (:seon.render.value/status %))
                      (:seon.render.value/schemas data)))
          (is (= (- 1000000
                    (count (get-in data [:seon.render.value/tree
                                         :seon.render.value/map-entries])))
                 (get-in data [:seon.render.value/tree
                               :seon.render.value/elided-keys]))))))))

(deftest html-data-status-is-deterministic-and-activated-only
  (let [before (schema/snapshot-state)
        attr :value-test.activation/value
        shape :value-test.activation/shape
        string-forms {attr :string shape [:map [attr attr]]}
        int-forms {attr :int shape [:map [attr attr]]}]
    (try
      (schema/activate-projection! (schema/build-projection string-forms))
      (let [value-a (into {} [[attr "active"] [:ordinary/x 1]])
            value-b (into {} [[:ordinary/x 1] [attr "active"]])
            p1 (v/render-html-data configuration "same" value-a)]
        (is (= (:seon.render.value/schemas p1)
               (:seon.render.value/schemas
                 (v/render-html-data configuration "same" value-b))))
        (schema/restore! int-forms)
        (is (= (:seon.render.value/schemas p1)
               (:seon.render.value/schemas
                 (v/render-html-data configuration "candidate-only" value-a))))
        (schema/activate-projection! (schema/build-projection int-forms))
        (let [p2 (v/render-html-data configuration "p2" value-a)]
          (is (= :invalid
                 (get-in p2 [:seon.render.value/schemas 0
                             :seon.render.value/status])))
          (is (contains? p2 :seon.render.value/explanation))))
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

;; ------------------------------------------------------------
;; Explicit-whitespace rendering (transcript-render redesign) — the central
;; capability for surgical edits. Gated by config; default is byte-identical.
;; ------------------------------------------------------------

(deftest visible-whitespace-is-gated-and-central
  (testing "all knobs off (default) → byte-identical passthrough"
    (is (= "a\tb c\n  x "
           (v/visible-whitespace configuration "a\tb c\n  x "))))
  (testing ":visible → every space `·` and every tab `→`"
    (let [configuration (assoc configuration
                               :seon.config.render/whitespace :visible)]
      (is (= "a→b·c"
             (v/visible-whitespace configuration "a\tb c")))))
  (testing ":tabs :arrow alone → tabs `→`, spaces untouched"
    (let [configuration (assoc configuration
                               :seon.config.render/tabs :arrow)]
      (is (= "a→b c"
             (v/visible-whitespace configuration "a\tb c")))))
  (testing ":trailing-ws :dot marks ONLY trailing whitespace"
    (let [configuration (assoc configuration
                               :seon.config.render/trailing-ws :dot)]
      (is (= "a b·\nx"
             (v/visible-whitespace configuration "a b \nx"))
          "interior space kept, trailing space dotted")))
  (testing ":line-numbers prepends a 1-based gutter"
    (let [configuration (assoc configuration
                               :seon.config.render/line-numbers true)]
      (is (= "1  a\n2  b"
             (v/visible-whitespace configuration "a\nb"))))))

(deftest render-ai-string-value-uses-whitespace-view-only-when-active
  (testing "default → string value renders as quoted pr-str (byte-identical)"
    (is (= (pr-str "a\tb")
           (v/render-ai configuration "eidX" "a\tb"))
        "quoted/escaped form, exactly as today"))
  (testing "whitespace active → string value renders RAW bytes with glyphs"
    (let [configuration (assoc configuration
                               :seon.config.render/whitespace :visible)]
      (is (= "a→b" (v/render-ai configuration "eidX" "a\tb"))
          "raw content with tab→ glyph, not the quoted pr-str form"))))
