(ns seon.diffusion.oracle-test
  "Offline proof for the UNIFIED control-signal oracle
   (`seon.diffusion.oracle/refine`) — NO GPU, NO embeddings. A single code-buffer
   carries BOTH a syntax error (an unbalanced form) AND a hallucinated symbol
   (`db/transct!`, a near-name of the seeded `seon.db/transact!`). One `refine`
   call must return the COMBINED control set:

     (a) a renoise span for the broken form,
     (b) a retrieval injection (real symbol + span + spec_text) for the
         hallucination,
     (c) clamp spans for the GOOD forms — and NOT for the hallucination form
         (it overlaps an injection) nor the broken form.

   A second test proves the EVAL FOLD: a syntactically-clean form retrieval
   cannot fix (no near candidate) is a CLAMP until an eval verdict demotes it
   to a renoise span.

   Run interactively via MCP eval:
     (require 'seon.diffusion.oracle-test :reload)
     (cljs.test/run-tests 'seon.diffusion.oracle-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.diffusion.oracle :as oracle]))

;; ---------------------------------------------------------------------------
;; A seeded :memory program graph — two real fns (raw datahike schema, so the
;; test is self-contained). Returns a Promise of the db VALUE refine reads.
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
    :seon.fn/source "(defn query [& args] …)"}])

(defn- fresh-db []
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

(defn- span-source [code-buffer [s e]] (subs code-buffer s e))

;; ---------------------------------------------------------------------------
;; THE crux code-buffer: a clean ns + a clean defn (CLAMP), a hallucinated call
;; (INJECTION, not a clamp), and an unbalanced tail (RENOISE).
;; ---------------------------------------------------------------------------

(def ^:private code-buffer
  (str "(ns my.work (:require [seon.db :as db]))\n"  ; 0  — clean clamp
       "(defn good [x] (inc x))\n"                    ; 1  — clean clamp
       "(db/transct! {:seon.db/tx-data []})\n"        ; 2  — hallucination → injection
       "(defn broken [x"))                            ; 3  — broken syntax → renoise

(deftest combined-control-set
  (async done
    (with-db
      (fn [db]
        (let [{::oracle/keys [clamps renoise-spans injections legs]}
              (oracle/refine {::oracle/code-buffer-text code-buffer ::oracle/db db})
              clamp-srcs   (set (map ::oracle/source clamps))
              by-unres     (into {} (map (juxt :seon.diffusion.retrieval/unresolved identity)) injections)]

          (testing "both PARSE and RETRIEVE legs ran (no eval verdicts supplied)"
            (is (= [:parse :retrieve] legs)))

          ;; (a) the broken form is a renoise span
          (testing "(a) renoise span for the unbalanced form, eof-classified"
            (is (= 1 (count renoise-spans)))
            (let [r (first renoise-spans)]
              (is (= :eof (::oracle/error-kind r)))
              (is (str/includes? (::oracle/source r) "(defn broken"))
              (is (= (::oracle/source r) (span-source code-buffer (::oracle/span r))))))

          ;; (b) the hallucination is an injection toward the real API
          (testing "(b) injection corrects db/transct! → db/transact! with span + spec"
            (let [inj (by-unres "db/transct!")]
              (is (some? inj))
              (is (= :clamp (:seon.diffusion.retrieval/op inj)))
              (is (= "db/transact!" (:seon.diffusion.retrieval/replacement inj)))
              (is (= "db/transct!" (span-source code-buffer (:seon.diffusion.retrieval/span inj))))
              (is (str/includes? (:seon.diffusion.retrieval/spec-text inj) "seon.db/transact!"))))

          ;; (c) clamps cover the good forms ONLY
          (testing "(c) clamps HOLD the clean forms — ns + good defn"
            (is (contains? clamp-srcs "(ns my.work (:require [seon.db :as db]))"))
            (is (contains? clamp-srcs "(defn good [x] (inc x))"))
            (doseq [c clamps]
              (is (= (::oracle/source c) (span-source code-buffer (::oracle/span c))))))

          (testing "(c') the hallucination form is NOT clamped (it overlaps an injection)"
            (is (not-any? #(str/includes? % "db/transct!") clamp-srcs)))

          (testing "(c'') the broken form is NOT clamped"
            (is (not-any? #(str/includes? % "(defn broken") clamp-srcs)))

          (testing "the three sets never double-cover: clamp spans are disjoint from renoise + injection spans"
            (let [bad (concat (map ::oracle/span renoise-spans)
                              (map :seon.diffusion.retrieval/span injections))]
              (doseq [c clamps [bs be] bad]
                (let [[cs ce] (::oracle/span c)]
                  (is (not (and (< cs be) (< bs ce)))
                      (str "clamp " (pr-str (::oracle/span c)) " overlaps bad " (pr-str [bs be])))))))

          ;; the wire flattener carries all three sets in one {op:"refine"} object
          (testing "to-wire emits the combined {op:\"refine\", clamps, renoise_spans, injections} object"
            (let [w (oracle/to-wire {::oracle/control-set
                                     {::oracle/clamps clamps
                                      ::oracle/renoise-spans renoise-spans
                                      ::oracle/injections injections
                                      ::oracle/legs legs}})
                  ^js first-renoise (aget (.-renoise_spans w) 0)
                  ^js first-injection (aget (.-injections w) 0)]
              (is (= "refine" (.-op w)))
              (is (= ["parse" "retrieve"] (vec (.-legs w))))
              (is (= (count clamps) (alength (.-clamps w))))
              (is (= (count renoise-spans) (alength (.-renoise_spans w))))
              (is (= (count injections) (alength (.-injections w))))
              (is (= "eof" (.-error_kind first-renoise)))
              (is (= "db/transact!" (.-replacement first-injection)))))))
      done)))

;; ---------------------------------------------------------------------------
;; STRUCTURAL tier (T1) — a form that READS clean but has a wrong SHAPE the AST
;; proves (def-vs-defn) renoises at the ~free structural tier, NO eval. A real
;; vector-binding def and a docstring def stay valid CLAMPS.
;; ---------------------------------------------------------------------------

(def ^:private struct-code-buffer
  (str "(def mean [v] (/ (reduce + v) (count v)))\n"  ; 0 — def-vs-defn → RENOISE
       "(def xs [1 2 3])\n"                            ; 1 — real vector binding → clamp
       "(def cfg \"the config\" 42)\n"                 ; 2 — docstring def → clamp
       "(defn ok [v] (count v))"))                     ; 3 — clean defn → clamp

(deftest structural-def-vs-defn
  (async done
    (with-db
      (fn [db]
        (let [{::oracle/keys [clamps renoise-spans legs]}
              (oracle/refine {::oracle/code-buffer-text struct-code-buffer ::oracle/db db})
              clamp-srcs (set (map ::oracle/source clamps))]

          (testing "no eval verdicts supplied → structural tier rides the PARSE leg"
            (is (= [:parse :retrieve] legs)))

          (testing "the def-vs-defn form is the ONLY renoise span, kind :def-vs-defn"
            (is (= 1 (count renoise-spans)))
            (let [r (first renoise-spans)]
              (is (= :def-vs-defn (::oracle/error-kind r)))
              (is (str/includes? (::oracle/source r) "(def mean [v]"))
              (is (= (::oracle/source r) (span-source struct-code-buffer (::oracle/span r))))))

          (testing "valid defs (vector binding, docstring) and the clean defn are CLAMPED"
            (is (contains? clamp-srcs "(def xs [1 2 3])"))
            (is (contains? clamp-srcs "(def cfg \"the config\" 42)"))
            (is (contains? clamp-srcs "(defn ok [v] (count v))")))

          (testing "the malformed def is NOT clamped"
            (is (not-any? #(str/includes? % "(def mean") clamp-srcs)))))
      done)))

;; ---------------------------------------------------------------------------
;; PHASE grammar gate — ordered generation phases. :schemas allows ns+register!
;; (rejects def/defn); :functions allows ns+defn (rejects register!/bare-def).
;; A disallowed head renoises; an allowed one clamps.
;; ---------------------------------------------------------------------------

(def ^:private phase-code-buffer
  (str "(ns my.work)\n"                                   ; allowed in BOTH
       "(schema/register! ::id :string)\n"                ; schemas-only
       "(defn mean [v] (/ (reduce + v) (count v)))\n"     ; functions-only
       "(def x 5)"))                                       ; allowed in NEITHER

(deftest phase-grammar-gate
  (async done
    (with-db
      (fn [db]
        (letfn [(run [phase]
                  (let [cs (oracle/refine
                             (cond-> {::oracle/code-buffer-text phase-code-buffer ::oracle/db db}
                               phase (assoc ::oracle/phase phase)))]
                    {:violations (->> (::oracle/renoise-spans cs)
                                      (filter #(= :phase-violation (::oracle/error-kind %)))
                                      (map ::oracle/source) set)
                     :clamps (set (map ::oracle/source (::oracle/clamps cs)))}))]

          (testing ":schemas phase — ns + register! clamp; defn + def renoised"
            (let [{:keys [violations clamps]} (run :schemas)]
              (is (contains? violations "(defn mean [v] (/ (reduce + v) (count v)))"))
              (is (contains? violations "(def x 5)"))
              (is (contains? clamps "(ns my.work)"))
              (is (contains? clamps "(schema/register! ::id :string)"))))

          (testing ":functions phase — ns + defn clamp; register! + def renoised"
            (let [{:keys [violations clamps]} (run :functions)]
              (is (contains? violations "(schema/register! ::id :string)"))
              (is (contains? violations "(def x 5)"))
              (is (contains? clamps "(ns my.work)"))
              (is (contains? clamps "(defn mean [v] (/ (reduce + v) (count v)))"))))

          (testing "no phase supplied — the gate is inert (no :phase-violation spans)"
            (is (empty? (:violations (run nil)))))))
      done)))

;; :tests phase — model-written cljs.test deftests. ns + deftest + comment
;; clamp; a defn (implementation before the pin) and register! both renoise.

(def ^:private tests-phase-code-buffer
  (str "(ns my.work-test (:require [cljs.test :refer [deftest is]]))\n" ; allowed
       "(deftest mean-test (is (= 2 (mean [1 2 3]))))\n"                ; allowed
       "(comment (mean []))\n"                                          ; allowed
       "(defn mean [v] (/ (reduce + v) (count v)))\n"                   ; violation
       "(schema/register! ::id :string)"))                              ; violation

(deftest tests-phase-gate
  (async done
    (with-db
      (fn [db]
        (let [cs (oracle/refine {::oracle/code-buffer-text tests-phase-code-buffer
                                 ::oracle/db db
                                 ::oracle/phase :tests})
              violations (->> (::oracle/renoise-spans cs)
                              (filter #(= :phase-violation (::oracle/error-kind %)))
                              (map ::oracle/source) set)
              clamps (set (map ::oracle/source (::oracle/clamps cs)))]
          (testing ":tests phase — ns + deftest + comment clamp"
            (is (contains? clamps "(ns my.work-test (:require [cljs.test :refer [deftest is]]))"))
            (is (contains? clamps "(deftest mean-test (is (= 2 (mean [1 2 3]))))"))
            (is (contains? clamps "(comment (mean []))")))
          (testing ":tests phase — defn + register! are violations"
            (is (contains? violations "(defn mean [v] (/ (reduce + v) (count v)))"))
            (is (contains? violations "(schema/register! ::id :string)")))))
      done)))

;; ---------------------------------------------------------------------------
;; EVAL FOLD — a syntactically-clean form retrieval cannot fix (the symbol has
;; no near candidate in the graph) is a CLAMP, until an eval verdict (`:compile`
;; — undeclared var) demotes it to a renoise span. The verdict is span-keyed, so
;; we take the clamp span the parse leg produced and feed it back as the verdict.
;; ---------------------------------------------------------------------------

(def ^:private eval-code-buffer "(defn f [] (zzzqqq))")

(deftest eval-verdict-demotes-clean-form-to-renoise
  (async done
    (with-db
      (fn [db]
        (let [base (oracle/refine {::oracle/code-buffer-text eval-code-buffer ::oracle/db db})]
          (testing "with NO eval verdict, the clean (but semantically-dubious) form is a CLAMP"
            (is (= [:parse :retrieve] (::oracle/legs base)))
            (is (= 1 (count (::oracle/clamps base))))
            (is (empty? (::oracle/injections base))
                "zzzqqq has no near candidate — retrieval produces no injection")
            (is (empty? (::oracle/renoise-spans base))))

          (let [clamp-span (::oracle/span (first (::oracle/clamps base)))
                folded (oracle/refine {::oracle/code-buffer-text eval-code-buffer
                                       ::oracle/db db
                                       ::oracle/eval-verdicts
                                       [{::oracle/span clamp-span
                                         ::oracle/ok? false
                                         ::oracle/error-kind :compile
                                         ::oracle/message "Use of undeclared Var zzzqqq"}]})]
            (testing "the eval verdict folds in: the form moves CLAMP → RENOISE"
              (is (= [:parse :retrieve :eval] (::oracle/legs folded)))
              (is (empty? (::oracle/clamps folded)))
              (is (= 1 (count (::oracle/renoise-spans folded))))
              (let [r (first (::oracle/renoise-spans folded))]
                (is (= :compile (::oracle/error-kind r)))
                (is (= clamp-span (::oracle/span r))))))))
      done)))

;; ---------------------------------------------------------------------------
;; FALSIFY — an eval-bad form already covered by an injection does NOT also
;; emit a renoise span (the injection's correction supersedes a re-noise).
;; ---------------------------------------------------------------------------

(deftest injection-supersedes-eval-renoise
  (async done
    (with-db
      (fn [db]
        (let [c "(db/transct! {})"
              base (oracle/refine {::oracle/code-buffer-text c ::oracle/db db})
              inj-span (:seon.diffusion.retrieval/span (first (::oracle/injections base)))
              folded (oracle/refine {::oracle/code-buffer-text c ::oracle/db db
                                     ::oracle/eval-verdicts
                                     [{::oracle/span inj-span
                                       ::oracle/ok? false
                                       ::oracle/error-kind :throw}]})]
          (is (some? inj-span) "the hallucination produced an injection")
          (is (empty? (::oracle/renoise-spans folded))
              "an eval-bad span covered by an injection is NOT re-noised")
          (is (= 1 (count (::oracle/injections folded))))))
      done)))
