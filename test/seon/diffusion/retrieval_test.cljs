(ns seon.diffusion.retrieval-test
  "Offline proof for the RETRIEVAL leg of the diffusion buzzsaw
   (`seon.diffusion.retrieval`) — NO GPU, NO embeddings. Each test seeds a
   small in-memory program graph (a few real `:seon.fn` rows), feeds a code-buffer
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
    [clojure.string :as str]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop :include-macros true]
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

(defn- fresh-db-rows
  "Open a fresh :memory datahike conn, install the fn schema + `rows`, and
   resolve to the db VALUE."
  [rows]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? false}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact! conn fn-schema)
                     (.then (fn [_] (d/transact! conn rows)))
                     (.then (fn [_] @conn))))))))

(defn- fresh-db
  "The fixed three-fn graph (the example tests' fixture)."
  []
  (fresh-db-rows fn-rows))

(defn- with-db [f done]
  (-> (fresh-db)
      (.then f)
      (.catch (fn [e] (is false (str "test chain threw — " e))))
      (.then (fn [_] (done)))))

;; ---------------------------------------------------------------------------
;; The canonical wrong-name code-buffer: a qualified hallucination (db/store!) AND a
;; bare near-name hallucination (transct!), among real refs (db, save!, m).
;; ---------------------------------------------------------------------------

(def ^:private code-buffer
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
        (let [unres (ret/unresolved-references {::ret/code-buffer-text code-buffer ::ret/db db})
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
              (is (= "transct!" (subs code-buffer s e)))))))
      done)))

;; The AUROC-0.471 split: a confidently-wrong-but-REAL core name (reduce-kv on
;; a vector) is NOT a graph-membership flag — only eval catches it. The graph
;; path must NOT false-positive on it.
(deftest real-core-name-not-flagged
  (async done
    (with-db
      (fn [db]
        (let [unres (ret/unresolved-references
                      {::ret/code-buffer-text "(defn sum [xs] (reduce-kv + 0 xs))" ::ret/db db})]
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
        (let [{::ret/keys [injections]} (ret/retrieve-for-code-buffer {::ret/code-buffer-text code-buffer ::ret/db db})
              by-unres (into {} (map (juxt ::ret/unresolved identity)) injections)
              inj      (by-unres "transct!")]
          (testing "the transct! injection names the real replacement + span + spec"
            (is (= :clamp (::ret/op inj)))
            (is (= "transct!" (::ret/unresolved inj)))
            (is (= "transact!" (::ret/replacement inj)))
            (let [[s e] (::ret/span inj)]
              (is (= "transct!" (subs code-buffer s e))))
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
              (is (= "transct!" (apply subs code-buffer (vec (.-span w)))))
              (is (re-find #"seon.db/transact!" (.-spec_text w)))))))
      done)))

;; ---------------------------------------------------------------------------
;; Falsification: a clean code-buffer yields no injections; membership is honest.
;; ---------------------------------------------------------------------------

(deftest clean-code-buffer-no-injections
  (async done
    (with-db
      (fn [db]
        (let [r (ret/retrieve-for-code-buffer
                  {::ret/code-buffer-text "(defn sum [xs] (reduce + 0 xs))" ::ret/db db})]
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

;; ===========================================================================
;; GENERATIVE properties over a RANDOM program graph. Each test generates a
;; fresh graph (distinct length-8 fn names across two namespaces), seeds ONE
;; :memory db, then runs a synchronous test.check property (100 cases) that
;; picks real syms / constructs near-misses from THAT graph — shrinking to the
;; smallest counterexample on failure. The names use the alphabet a–m and
;; reserve `z` for the guaranteed-far "no near candidate" name; every
;; near-miss is built by ±1 char, which (real names all being length 8) makes
;; it guaranteed-absent from the graph.
;; ===========================================================================

(def ^:private name-alpha "abcdefghijklm")   ; 'z' is reserved for the far name

(def ^:private gen-namepart
  (gen/fmap str/join (gen/vector (gen/elements (vec name-alpha)) 8)))

(defn- nm-of [fq] (let [i (.lastIndexOf fq "/")] (if (>= i 0) (subs fq (inc i)) fq)))
(defn- ns-of [fq] (let [i (.lastIndexOf fq "/")] (when (>= i 0) (subs fq 0 i))))

(defn- gen-graph-rows
  "A randomly generated program graph: up to 8 DISTINCT length-8 fn names
   spread over two namespaces, each a self-contained :seon.fn row."
  []
  (let [names (->> (gen/sample gen-namepart 80) distinct (take 8) vec)
        nss   ["gen.a" "gen.b"]]
    (vec (map-indexed
           (fn [i nm]
             {:seon.fn/sym      (str (nth nss (mod i 2)) "/" nm)
              :seon.fn/arglists "([x])"
              :seon.fn/doc      "Generated fn."
              :seon.fn/spec     "[:=> [:cat :any] :any]"
              :seon.fn/source   (str "(defn " nm " [x] x)")})
           names))))

(defn- check
  "Run a test.check property `n` times; assert it held, surfacing the shrunk
   counterexample (a falsification is a REAL bug in the retrieval fns)."
  [n property]
  (let [{:keys [result shrunk] :as res} (tc/quick-check n property)]
    (is (true? result)
        (str "retrieval property falsified — shrunk: "
             (pr-str (:smallest shrunk)) " | " (pr-str res)))))

;; Property 1: a symbol that IS in the graph is NEVER flagged unresolved (no
;; false-positive correction).
(deftest retrieve-prop-real-sym-never-flagged
  (async done
    (let [rows (gen-graph-rows)
          syms (mapv :seon.fn/sym rows)]
      (-> (fresh-db-rows rows)
          (.then
            (fn [db]
              (check 100
                (prop/for-all [fq (gen/elements syms)]
                  (let [code-buffer  (str "(defn use1 [x] (" fq " x))")
                        flagged (set (map ::ret/symbol
                                          (ret/unresolved-references
                                            {::ret/code-buffer-text code-buffer ::ret/db db})))]
                    (and (not (contains? flagged fq))
                         (ret/symbol-resolves?
                           {::ret/name (nm-of fq) ::ret/qualifier (ns-of fq)
                            ::ret/aliases {} ::ret/db db})))))))
          (.catch (fn [e] (is false (str "threw — " e))))
          (.then (fn [_] (done)))))))

;; Property 2: a code-buffer referencing an absent NEAR-name (1 edit from a real
;; sym) IS flagged AND retrieves that real sym.
(deftest retrieve-prop-near-miss-flagged-and-retrieved
  (async done
    (let [rows (gen-graph-rows)
          syms (mapv :seon.fn/sym rows)]
      (-> (fresh-db-rows rows)
          (.then
            (fn [db]
              (check 100
                (prop/for-all [fq    (gen/elements syms)
                               drop? gen/boolean
                               extra (gen/elements (vec name-alpha))]
                  (let [nm     (nm-of fq)
                        miss   (if drop? (subs nm 0 (dec (count nm))) (str nm extra))
                        code-buffer (str "(defn use1 [x] (" miss " x))")
                        flagged   (set (map ::ret/symbol
                                            (ret/unresolved-references
                                              {::ret/code-buffer-text code-buffer ::ret/db db})))
                        cand-syms (set (map ::ret/sym
                                            (ret/retrieve-candidates
                                              {::ret/name miss ::ret/db db})))]
                    (and (contains? flagged miss)        ; absent near-name flagged
                         (contains? cand-syms fq)))))))   ; retrieves the real sym
          (.catch (fn [e] (is false (str "threw — " e))))
          (.then (fn [_] (done)))))))

;; Property 3: every emitted injection has a span within code-buffer bounds (whose
;; substring IS the flagged token) and a replacement whose name EXISTS in the
;; graph — never a fabricated correction.
(deftest retrieve-prop-injection-span-and-replacement-valid
  (async done
    (let [rows   (gen-graph-rows)
          syms   (mapv :seon.fn/sym rows)
          gnames (set (map nm-of syms))]
      (-> (fresh-db-rows rows)
          (.then
            (fn [db]
              (check 100
                (prop/for-all [fq    (gen/elements syms)
                               real  (gen/elements syms)
                               extra (gen/elements (vec name-alpha))]
                  (let [miss   (str (nm-of fq) extra)
                        code-buffer (str "(defn use1 [x] (" real " x) (" miss " x))")
                        {::ret/keys [injections]}
                        (ret/retrieve-for-code-buffer {::ret/code-buffer-text code-buffer ::ret/db db})
                        clen (count code-buffer)]
                    (and (pos? (count injections))       ; the near-miss yields an injection
                         (every?
                           (fn [inj]
                             (let [[s e] (::ret/span inj)]
                               (and (<= 0 s) (<= s e) (<= e clen)
                                    (= (::ret/unresolved inj) (subs code-buffer s e))
                                    (contains? gnames (nm-of (::ret/replacement inj))))))
                           injections)))))))
          (.catch (fn [e] (is false (str "threw — " e))))
          (.then (fn [_] (done)))))))

;; Property 4 (fail-soft): a dead name with NO near candidate is flagged but
;; produces NO injection — never a wrong correction.
(deftest retrieve-prop-failsoft-no-candidate-no-injection
  (async done
    (let [rows (gen-graph-rows)]
      (-> (fresh-db-rows rows)
          (.then
            (fn [db]
              (check 100
                (prop/for-all [klen (gen/choose 3 6)]
                  (let [far    (apply str (repeat klen "z"))  ; 'z' ∉ the name alphabet
                        code-buffer (str "(defn use1 [x] (" far " x))")
                        {::ret/keys [unresolved injections]}
                        (ret/retrieve-for-code-buffer {::ret/code-buffer-text code-buffer ::ret/db db})
                        flagged (set (map ::ret/symbol unresolved))]
                    (and (contains? flagged far)         ; the dead name IS flagged
                         (empty? injections)))))))         ; but NO wrong injection
          (.catch (fn [e] (is false (str "threw — " e))))
          (.then (fn [_] (done)))))))
