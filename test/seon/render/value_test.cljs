(ns seon.render.value-test
  "Behavioral tests for the structural value renderer (`seon.render.value`).

   We pin MECHANISM, not exact strings (the format will keep iterating):
   bounds are respected, paths survive, opaque handles project, lazy seqs
   never over-realize, the drill hint appears iff the view is partial."
  (:require
    [cljs.test :as t :refer [deftest is testing]]
    [clojure.string :as str]
    [seon.config :as config]
    [seon.render.value :as v]))

(def configuration (config/resolve-config-singleton {}))

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
    (is (= [1 2 3]
           (:seon.render.value/shown (v/sample configuration [1 2 3] {}))))
    (is (= {:a 1 :b 2} (v/sample configuration {:a 1 :b 2} {})))))

(deftest breadth-bound-on-vectors
  (testing "a wide vector keeps max-items elements + an exact elided tail"
    (let [skel (v/sample configuration (vec (range 100)) {:max-items 8})]
      (is (= 8 (count (:seon.render.value/shown skel))))
      (is (= 92 (:seon.render.value/elided skel))))))

(deftest breadth-bound-on-maps
  (testing "a wide map keeps max-keys entries + an elided-keys count"
    (let [m    (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 20)))
          skel (v/sample configuration m {:max-keys 6})]
      (is (= 6 (count (dissoc skel :seon.render.value/elided-keys))))
      (is (= 14 (:seon.render.value/elided-keys skel))))))

(deftest direct-error-maps-use-ordinary-map-sampling
  (let [error {:seon.error/message "writer unavailable"
               :seon.error/kind :system
               :seon.error/data {:operation :transact}}
        sampled (v/sample configuration error {})]
    (is (= error sampled))
    (is (not (contains? sampled :seon.db/ok?)))
    (is (not (contains? sampled :seon.db/error)))))

(deftest depth-bound-prunes-nested
  (testing "nesting past max-depth becomes a typed+counted prune marker"
    (let [skel (v/sample configuration {:a {:b {:c {:d 1 :e 2}}}}
                         {:max-depth 3})
          c    (get-in skel [:a :b :c])]
      (is (= :map (:seon.render.value/pruned c)))
      (is (= 2 (:seon.render.value/count c))))))

(deftest empty-colls-not-pruned-at-depth
  (testing "an empty coll at the depth boundary renders verbatim, not a marker"
    (let [skel (v/sample configuration {:a {:b {:c []}}} {:max-depth 3})]
      (is (= [] (:seon.render.value/shown (get-in skel [:a :b :c])))))))

(deftest navigation-paths-preserved
  (testing "a path read off the skeleton resolves on the LIVE value"
    (let [live {:api/results [{:user/id 1 :user/name "John"}
                              {:user/id 2 :user/name "Jane"}]}
          skel (v/sample configuration live {})]
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

(deftest long-string-clipped-with-length
  (let [skel (v/sample configuration (apply str (repeat 300 "x"))
                       {:max-string 80})]
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
