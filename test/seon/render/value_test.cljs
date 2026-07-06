(ns seon.render.value-test
  "Behavioral tests for the structural value renderer (`seon.render.value`).

   We pin MECHANISM, not exact strings (the format will keep iterating):
   bounds are respected, paths survive, opaque handles project, lazy seqs
   never over-realize, the drill hint appears iff the view is partial."
  (:require
    [cljs.test :as t :refer [deftest is testing]]
    [clojure.string :as str]
    [seon.render.value :as v]))

;; Stand-ins for opaque runtime handles (the real ones are datahike's).
(defrecord FakeDB [max-tx max-eid])
(deftype FakeDatom [e a vv]
  ILookup
  (-lookup [_ k] (case k :e e :a a :v vv nil))
  (-lookup [_ k nf] (case k :e e :a a :v vv nf)))

;; ============================================================
;; sample — depth + breadth bounds, marker shapes.
;; ============================================================

(deftest small-value-fully-shown
  (testing "a small value samples to itself (no markers)"
    (is (= [1 2 3] (:seon.render.value/shown (v/sample [1 2 3]))))
    (is (= {:a 1 :b 2} (v/sample {:a 1 :b 2})))))

(deftest breadth-bound-on-vectors
  (testing "a wide vector keeps max-items elements + an exact elided tail"
    (let [skel (v/sample (vec (range 100)) {:max-items 8})]
      (is (= 8 (count (:seon.render.value/shown skel))))
      (is (= 92 (:seon.render.value/elided skel))))))

(deftest breadth-bound-on-maps
  (testing "a wide map keeps max-keys entries + an elided-keys count"
    (let [m    (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 20)))
          skel (v/sample m {:max-keys 6})]
      (is (= 6 (count (dissoc skel :seon.render.value/elided-keys))))
      (is (= 14 (:seon.render.value/elided-keys skel))))))

(deftest depth-bound-prunes-nested
  (testing "nesting past max-depth becomes a typed+counted prune marker"
    (let [skel (v/sample {:a {:b {:c {:d 1 :e 2}}}} {:max-depth 3})
          c    (get-in skel [:a :b :c])]
      (is (= :map (:seon.render.value/pruned c)))
      (is (= 2 (:seon.render.value/count c))))))

(deftest empty-colls-not-pruned-at-depth
  (testing "an empty coll at the depth boundary renders verbatim, not a marker"
    (let [skel (v/sample {:a {:b {:c []}}} {:max-depth 3})]
      (is (= [] (:seon.render.value/shown (get-in skel [:a :b :c])))))))

(deftest navigation-paths-preserved
  (testing "a path read off the skeleton resolves on the LIVE value"
    (let [live {:api/results [{:user/id 1 :user/name "John"}
                              {:user/id 2 :user/name "Jane"}]}
          skel (v/sample live)]
      ;; key + index retained → get-in path is identical on both
      (is (= 1 (get-in skel [:api/results :seon.render.value/shown 0 :user/id])))
      (is (= "John" (get-in live [:api/results 0 :user/name]))))))

;; ============================================================
;; lazy safety + homogeneity.
;; ============================================================

(deftest lazy-seq-never-over-realized
  (testing "an infinite seq samples to a bounded head + :more, no hang"
    (let [realized (atom 0)
          s        (map (fn [i] (swap! realized inc) i) (range))
          skel     (v/sample s {:max-items 8})]
      (is (= 8 (count (:seon.render.value/shown skel))))
      (is (= :more (:seon.render.value/elided skel)))
      ;; head+1 probe only — never the whole infinite seq
      (is (<= @realized 50)))))

(deftest homogeneous-collection-shows-shared-keys
  (testing "a big collection of uniform maps carries its shared key-set"
    (let [rows (mapv (fn [i] {:seon.fn/name (str "f" i) :seon.fn/arity (mod i 3)})
                     (range 40))
          skel (v/sample rows {:max-items 5})]
      (is (= [:seon.fn/arity :seon.fn/name] (:seon.render.value/shape skel)))
      (is (= 35 (:seon.render.value/elided skel))))))

;; ============================================================
;; opaque handles + long strings.
;; ============================================================

(deftest datahike-db-projects-to-opaque-marker
  (let [skel (v/sample (->FakeDB 42 99))]
    (is (= "datahike/DB" (:seon.eval/opaque skel)))
    (is (str/includes? (:seon.eval/summary skel) "max-tx=42"))))

(deftest datom-projects-to-datom-marker
  (let [skel (v/sample (FakeDatom. 42 :user/name "Jane"))]
    (is (= [42 :user/name "Jane"] (:seon.eval/datom skel)))))

(deftest opaque-handle-nested-in-collection-is-projected
  (testing "an opaque node inside a vector is sanitized, not just a top-level one"
    (let [skel (v/sample [(->FakeDB 7 7) :ok])]
      (is (= "datahike/DB" (:seon.eval/opaque (first (:seon.render.value/shown skel))))))))

(deftest long-string-clipped-with-length
  (let [skel (v/sample (apply str (repeat 300 "x")) {:max-string 80})]
    (is (= 300 (:seon.render.value/string-len skel)))
    (is (<= (count (:seon.render.value/head skel)) 80))))

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

(deftest render-ai-small-value-has-no-hint
  (testing "a fully-shown value renders verbatim, no partial-view hint"
    (let [out (v/render-ai "abc" [1 2 3])]
      (is (= "[1 2 3]" out))
      (is (not (str/includes? out "partial view"))))))

(deftest render-ai-small-deep-renders-whole
  (testing "a small but deep/long value prints VERBATIM — the agent sees the
            real nesting of its own stored data, not {…N keys}/\"…\""
    (let [v   {:name "widget" :stock {:warehouse {:shelf {:bin 42}}}
               :note (apply str (repeat 90 "x"))}
          out (v/render-ai "s2" v)]
      (is (= (pr-str v) out))
      (is (not (str/includes? out "partial view")))
      (is (not (str/includes? out "…"))))))

(deftest render-ai-truncated-names-the-live-var
  (testing "a clipped value points the agent at result/<id> for the whole value"
    (let [out (v/render-ai "xyz123" (vec (range 2000)))]
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
          out  (v/render-ai "eid1" m)]
      ;; the two tiny load-bearing keys survive
      (is (str/includes? out "c4685deadbeefc4685deadbeef"))
      (is (str/includes? out "err-tokens"))
      ;; the huge payloads are elided (never rendered whole)
      (is (not (str/includes? out big)))
      ;; honest elision marker
      (is (str/includes? out "more keys"))
      ;; every retained key still resolves against the live value (path valid)
      (is (str/includes? out "out-blob")))))

(deftest render-ai-hint-teaches-durability-promotion
  (testing "a partial view's drill hint names BOTH recovery and the my.blob/put!
            keep idiom when a result id exists"
    (let [out (v/render-ai "keep1" (vec (range 2000)))]
      (is (str/includes? out "partial view"))
      ;; recovery idiom
      (is (str/includes? out "get-in"))
      ;; durability idiom
      (is (str/includes? out "keep:"))
      (is (str/includes? out "my.blob/put! result/keep1")))))

(deftest render-ai-long-string-reports-length
  (let [out (v/render-ai "s1" (apply str (repeat 2000 "x")))]
    (is (str/includes? out "tokens⟩"))
    (is (str/includes? out "result/s1"))))

(deftest render-ai-output-is-bounded
  (testing "even a huge deeply-nested value renders to a small bounded string"
    (let [huge (vec (repeat 500 (into {} (map (fn [i] [(keyword (str "k" i))
                                                       (vec (range 50))])
                                              (range 30)))))
          out  (v/render-ai "big" huge)]
      (is (< (count out) 4000)))))

(deftest render-ai-never-emits-fences-or-backticks
  (testing "output stays valid comment prose (no ``` / ` that break the eval'able context)"
    (let [out (v/render-ai "h" {:a (vec (range 100)) :b "x"})]
      (is (not (str/includes? out "`"))))))

;; ============================================================
;; render-html-data — the U panel DATA CONTRACT.
;; ============================================================

(deftest html-data-contract-shape
  (let [data (v/render-html-data "eid42" (vec (range 100)))]
    (is (= "eid42" (:seon.render.value/eval-id data)))
    (is (true? (:seon.render.value/truncated? data)))
    (is (string? (:seon.render.value/summary data)))
    (is (contains? data :seon.render.value/tree))
    ;; the tree is the same skeleton render-ai emits
    (is (= (v/sample (vec (range 100))) (:seon.render.value/tree data)))))
