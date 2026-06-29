(ns seon.diffusion.retrieval-test
  "Offline proof for the RETRIEVAL leg of the diffusion buzzsaw
   (`seon.diffusion.retrieval`) — NO GPU, NO embeddings. Each test seeds a
   small in-memory program graph (a few real `:seon.fn` rows), feeds a canvas
   that references a hallucinated near-name (`transct!` / `db/store!`), and
   asserts the three steps:

     before (hallucinated symbol) → retrieved (real API) → injection payload.

   The fake→real→injection chain IS the capability's value; the worker turns
   the descriptor into a mid-denoise clamp + encoder-KV inject once on GPU.

   Run interactively via MCP eval:
     (require 'seon.diffusion.retrieval-test :reload)
     (cljs.test/run-tests 'seon.diffusion.retrieval-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [datahike.api :as d]
    [seon.diffusion.retrieval :as ret]))

;; ---------------------------------------------------------------------------
;; A seeded :memory program graph — three real fns. Raw datahike schema so the
;; test is self-contained (no seon.agent require). Returns a Promise of the db
;; VALUE the retrieval fns read.
;; ---------------------------------------------------------------------------

(def ^:private fn-schema
  [{:db/ident :seon.fn/sym      :db/cardinality :db.cardinality/one
    :db/valueType :db.type/string :db/unique :db.unique/identity}
   {:db/ident :seon.fn/arglists :db/cardinality :db.cardinality/one :db/valueType :db.type/string}
   {:db/ident :seon.fn/doc      :db/cardinality :db.cardinality/one :db/valueType :db.type/string}
   {:db/ident :seon.fn/spec     :db/cardinality :db.cardinality/one :db/valueType :db.type/string}
   {:db/ident :seon.fn/source   :db/cardinality :db.cardinality/one :db/valueType :db.type/string}])

(def ^:private fn-rows
  [{:seon.fn/sym "seon.db/transact!"
    :seon.fn/arglists "([& call-args])"
    :seon.fn/doc "Commit tx-data. Two call shapes: map-in or positional."
    :seon.fn/spec "[:=> [:cat :seon.db/transact-request] :seon.db/transact-response]"
    :seon.fn/source "(defn transact! [& call-args] …)"}
   {:seon.fn/sym "seon.db/query"
    :seon.fn/arglists "([& args])"
    :seon.fn/doc "Run a Datalog query."
    :seon.fn/source "(defn query [& args] …)"}
   {:seon.fn/sym "my.app/save-note!"
    :seon.fn/arglists "([m])"
    :seon.fn/doc "Persist a note map."
    :seon.fn/source "(defn save-note! [m] …)"}])

(defn- fresh-db
  "Open a fresh :memory datahike conn, install the fn schema + rows, and
   resolve to the db VALUE."
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? false}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact! conn fn-schema)
                     (.then (fn [_] (d/transact! conn fn-rows)))
                     (.then (fn [_] @conn))))))))

(defn- with-db [f done]
  (-> (fresh-db)
      (.then f)
      (.catch (fn [e] (is false (str "test chain threw — " e))))
      (.then (fn [_] (done)))))

;; ---------------------------------------------------------------------------
;; The canonical wrong-name canvas: a qualified hallucination (db/store!) AND a
;; bare near-name hallucination (transct!), among real refs (db, save!, m).
;; ---------------------------------------------------------------------------

(def ^:private canvas
  (str "(ns my.work (:require [seon.db :as db]))\n"
       "(defn save! [m]\n"
       "  (db/transct! {:db/tx-data [m]})\n"
       "  (transct! m))"))

;; ---------------------------------------------------------------------------
;; (1) DETECT — the unresolved symbols are surfaced; real refs are not.
;; ---------------------------------------------------------------------------

(deftest detect-unresolved-symbols
  (async done
    (with-db
      (fn [db]
        (let [unres (ret/unresolved-references {::ret/canvas-text canvas ::ret/db db})
              syms  (set (map ::ret/symbol unres))]
          (testing "both hallucinated names are flagged (bare + qualified)"
            (is (contains? syms "transct!"))
            (is (contains? syms "db/transct!")))
          (testing "real refs, locals, and the ns/require decl are NOT flagged"
            (is (not (contains? syms "save!")))   ; the def name
            (is (not (contains? syms "m")))       ; a local arg
            (is (not (contains? syms "db")))      ; the require alias
            (is (not (contains? syms "seon.db"))) ; the required ns
            (is (not (contains? syms "my.work"))));
          (testing "each flag carries an absolute char span pointing at the token"
            (let [by-sym (into {} (map (juxt ::ret/symbol identity)) unres)
                  [s e]  (::ret/span (by-sym "transct!"))]
              (is (= "transct!" (subs canvas s e)))))))
      done)))

;; The AUROC-0.471 split: a confidently-wrong-but-REAL core name (reduce-kv on
;; a vector) is NOT a graph-membership flag — only eval catches it. The graph
;; path must NOT false-positive on it.
(deftest real-core-name-not-flagged
  (async done
    (with-db
      (fn [db]
        (let [unres (ret/unresolved-references
                      {::ret/canvas-text "(defn sum [xs] (reduce-kv + 0 xs))" ::ret/db db})]
          (is (empty? unres) "reduce-kv is a real core fn — graph path defers to eval")))
      done)))

;; ---------------------------------------------------------------------------
;; (2) RETRIEVE — the real API comes back, distance-ranked, with its signature.
;; ---------------------------------------------------------------------------

(deftest retrieve-real-candidate-for-typo
  (async done
    (with-db
      (fn [db]
        (testing "bare typo transct! retrieves seon.db/transact! (edit distance 1)"
          (let [cands (ret/retrieve-candidates {::ret/name "transct!" ::ret/db db})
                best  (first cands)]
            (is (seq cands))
            (is (= "seon.db/transact!" (::ret/sym best)))
            (is (= 1 (::ret/distance best)))
            (is (= :near-name (::ret/match-kind best)))
            (testing "candidate carries the real signature + spec for the encoder KV"
              (is (= "([& call-args])" (::ret/arglists best)))
              (is (re-find #"Commit tx-data" (::ret/spec-text best)))
              (is (re-find #":seon.db/transact-request" (::ret/spec best))))))
        (testing "an exact name (different ns) ranks as :exact, distance 0"
          (let [cands (ret/retrieve-candidates {::ret/name "query" ::ret/db db})]
            (is (= "seon.db/query" (::ret/sym (first cands))))
            (is (= :exact (::ret/match-kind (first cands))))
            (is (= 0 (::ret/distance (first cands)))))))
      done)))

;; ---------------------------------------------------------------------------
;; (3) EMIT — the injection descriptor in the worker's {op,…} contract shape.
;; ---------------------------------------------------------------------------

(deftest emit-injection-descriptor
  (async done
    (with-db
      (fn [db]
        (let [{::ret/keys [injections]} (ret/retrieve-for-canvas {::ret/canvas-text canvas ::ret/db db})
              by-unres (into {} (map (juxt ::ret/unresolved identity)) injections)
              inj      (by-unres "transct!")]
          (testing "the transct! injection names the real replacement + span + spec"
            (is (= :clamp (::ret/op inj)))
            (is (= "transct!" (::ret/unresolved inj)))
            (is (= "transact!" (::ret/replacement inj)))
            (let [[s e] (::ret/span inj)]
              (is (= "transct!" (subs canvas s e))))
            (is (re-find #"seon.db/transact!" (::ret/spec-text inj))))
          (testing "the qualified db/transct! injection preserves the alias in the replacement"
            (let [qinj (by-unres "db/transct!")]
              (is (some? qinj))
              (is (= "db/transact!" (::ret/replacement qinj)))))
          (testing "to-wire flattens to the worker's {op, span, replacement, spec_text} object"
            (let [w (ret/to-wire {::ret/injection inj})]
              (is (= "clamp" (.-op w)))
              (is (= "transact!" (.-replacement w)))
              (is (= "transct!" (.-unresolved w)))
              (is (= (::ret/span inj) (vec (.-span w))))
              (is (= "transct!" (apply subs canvas (vec (.-span w)))))
              (is (re-find #"seon.db/transact!" (.-spec_text w)))))))
      done)))

;; ---------------------------------------------------------------------------
;; Falsification: a clean canvas yields no injections; membership is honest.
;; ---------------------------------------------------------------------------

(deftest clean-canvas-no-injections
  (async done
    (with-db
      (fn [db]
        (let [r (ret/retrieve-for-canvas
                  {::ret/canvas-text "(defn sum [xs] (reduce + 0 xs))" ::ret/db db})]
          (is (empty? (::ret/unresolved r)))
          (is (empty? (::ret/injections r)))))
      done)))

(deftest membership-check
  (async done
    (with-db
      (fn [db]
        (is (true?  (ret/symbol-resolves? {::ret/name "transact!" ::ret/qualifier "db"
                                           ::ret/aliases {"db" "seon.db"} ::ret/db db})))
        (is (false? (ret/symbol-resolves? {::ret/name "transct!" ::ret/db db})))
        (is (false? (ret/symbol-resolves? {::ret/name "transact!" ::ret/qualifier "db"
                                           ::ret/aliases {"db" "wrong.ns"} ::ret/db db}))
            "a real name under the WRONG ns does not resolve"))
      done)))
