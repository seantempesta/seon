(ns detectors-and-standards-probe-2026-09-16
  "Read-only MCP jvm-mode probes for the detector live counts (R4).
  Each form is ONE independent probe; explicit custody, no writes, no test JVM.
  Measured on default at basis 536871978, 2026-09-16."
  (:require [clojure.edn] [clojure.set] [clojure.string]
            [seon.db] [seon.fn] [seon.operator] [seon.schema.internal]))

;; D1 — entity map without a render pair. The lifted :seon.render/ai and
;; :seon.render/html are STORED attributes on the schema entity; never read
;; the :seon.schema/form text.
;; => {:entity-maps 76 :paired 25 :without-pair 51}
(let [db (seon.db/db (seon.operator/connection "default"))
      all (set (seon.db/q '[:find [?k ...] :where [?e :seon.schema/key ?k] [?e :seon.db/attributes true]] db))
      paired (set (seon.db/q '[:find [?k ...] :where [?e :seon.schema/key ?k] [?e :seon.db/attributes true]
                               [?e :seon.render/ai _] [?e :seon.render/html _]] db))]
  {:entity-maps (count all) :paired (count paired)
   :without-pair (count (clojure.set/difference all paired))
   :subjects (vec (sort (clojure.set/difference all paired)))})

;; D2/D8 and population sanity.
;; => {:public-fns 1137 :public-no-doc 39 :public-no-spec 65 :namespaces 437
;;     :ns-no-steward 431 :tests 1779 :tests-no-subject 1779 :errors 4 :issues 1630}
(let [db (seon.db/db (seon.operator/connection "default"))]
  {:public-fns (count (seon.db/q '[:find [?s ...] :where [?f :seon.fn/sym ?s] [?f :seon.fn/private? false] [?f :seon.fn/source _]] db))
   :public-no-doc (count (seon.db/q '[:find [?s ...] :where [?f :seon.fn/sym ?s] [?f :seon.fn/private? false] [?f :seon.fn/source _] (not [?f :seon.fn/doc _])] db))
   :public-no-spec (count (seon.db/q '[:find [?s ...] :where [?f :seon.fn/sym ?s] [?f :seon.fn/private? false] [?f :seon.fn/source _] (not [?f :seon.fn/macro? true]) (not [?f :seon.fn/spec _])] db))
   :namespaces (count (seon.db/q '[:find [?n ...] :where [?e :seon.ns/name ?n]] db))
   :ns-no-steward (count (seon.db/q '[:find [?n ...] :where [?e :seon.ns/name ?n] (not [?e :seon.ns/steward _])] db))
   :tests (count (seon.db/q '[:find [?s ...] :where [?t :seon.test/sym ?s]] db))
   :tests-no-subject (count (seon.db/q '[:find [?s ...] :where [?t :seon.test/sym ?s] (not [?t :seon.test/subject _])] db))
   :errors (count (seon.db/q '[:find [?s ...] :where [?e :seon.error/signature ?s]] db))
   :issues (count (seon.db/q '[:find [?e ...] :where [?e :seon.issue/title _]] db))})

;; D4 — permissive contract position on a public source-bearing function,
;; through the schema owner's own inspector, not a text scan.
;; => {:with-spec 1066 :functions-with-unjustified 0 :positions 0}
(let [db (seon.db/db (seon.operator/connection "default"))
      rows (seon.db/q '[:find ?s ?spec :where [?f :seon.fn/sym ?s] [?f :seon.fn/private? false]
                        [?f :seon.fn/source _] [?f :seon.fn/spec ?spec]] db)
      findings (for [[s spec] rows
                     :let [form (try (clojure.edn/read-string spec) (catch Throwable _ nil))
                           ps (when form (seon.schema.internal/permissive-positions
                                          {:seon.schema/definition form :seon.schema/stored? false :seon.schema/forms {}}))
                           unjust (remove :seon.schema/justified? ps)]
                     :when (seq unjust)]
                 [s (count unjust) (vec (distinct (map :seon.schema.advisory/kind unjust)))])]
  {:with-spec (count rows) :functions-with-unjustified (count findings)
   :positions (reduce + (map second findings))})

;; Falsification of "the inspector returns nothing here": one known-permissive
;; contract returns its position with :seon.schema/justified? true.
;; => [{:seon.schema.advisory/kind :undefined :seon.schema/justified? true ...}]
(let [db (seon.db/db (seon.operator/connection "default"))
      spec (:seon.fn/spec (seon.db/pull db [:seon.fn/spec] [:seon.fn/sym "seon.schema/malli-form?"]))]
  (seon.schema.internal/permissive-positions
   {:seon.schema/definition (clojure.edn/read-string spec) :seon.schema/stored? false :seon.schema/forms {}}))

;; D3 — public function reached by no test. 193 ms for the whole graph.
;; => {:untested 303 :untested-with-source 303}
(let [db (seon.db/db (seon.operator/connection "default"))
      t0 (System/nanoTime)
      untested (seon.fn/functions-without-tests db)]
  {:ms (/ (- (System/nanoTime) t0) 1e6) :untested (count untested)})

;; D5/D6/D7 in one read.
;; => {:non-generatable-schemas 172 :public-fns-blocked 205
;;     :tests-without-first-party-call 135
;;     :error-occurrences [[..2] [..2] [..2] [..1]]}
(let [db (seon.db/db (seon.operator/connection "default"))
      nongen (set (seon.db/q '[:find [?k ...] :where [?e :seon.schema/key ?k] [?e :seon.schema/generatable? false]] db))
      specs (seon.db/q '[:find ?s ?spec :where [?f :seon.fn/sym ?s] [?f :seon.fn/private? false]
                         [?f :seon.fn/source _] [?f :seon.fn/spec ?spec]] db)
      blocked (for [[s spec] specs
                    :let [form (try (clojure.edn/read-string spec) (catch Throwable _ nil))]
                    :when (and form (some #(and (keyword? %) (nongen %)) (tree-seq coll? seq form)))] s)]
  {:non-generatable-schemas (count nongen)
   :public-fns-blocked (count blocked)
   :tests-without-first-party-call
   (count (seon.db/q '[:find [?s ...] :where [?t :seon.test/sym ?s]
                       (not-join [?t] [?t :seon.fn/calls ?c] [?c :seon.fn/sym ?cs]
                                 [(clojure.string/starts-with? ?cs "clojure.") ?core] [(not ?core)])] db))
   :error-occurrences (seon.db/q '[:find ?sig ?n :where [?e :seon.error/signature ?sig]
                                   [?e :seon.error/occurrences ?o] [?o :seon.error.occurrence/count ?n]] db)})

;; D9 — naive database reach over the runner's three fixture owners.
;; => {:expensive-tests 837 :without-observation 782}  ** OVERCOUNT **
(let [db (seon.db/db (seon.operator/connection "default"))
      expensive (set (mapcat #(seon.fn/tests-reaching db %)
                             ["seon.test-support/populate-published-root!"
                              "seon.test-support/populate-published-operator-root!"
                              "seon.test-support/with-fresh-database"]))
      observed (set (seon.db/q '[:find [?s ...] :where [?t :seon.test/sym ?s] [?t :seon.test/fixture-observation _]] db))]
  {:expensive-tests (count expensive)
   :without-observation (count (clojure.set/difference expensive observed))})

;; D9 corrected — the runner excludes with-database's OPTIONAL fresh-store
;; branch (src/seon/test/runner.clj:711-714): demand is direct reach of the two
;; publication owners, plus with-fresh-database reach by a test that actually
;; requests it. => {:direct 51 :narrow-expensive 54 :narrow-without-observation 0}
(let [db (seon.db/db (seon.operator/connection "default"))
      direct (set (mapcat #(seon.fn/tests-reaching db %)
                          ["seon.test-support/populate-published-root!"
                           "seon.test-support/populate-published-operator-root!"]))
      fresh (set (seon.fn/tests-reaching db "seon.test-support/with-fresh-database"))
      requesters (set (seon.db/q '[:find [?s ...] :where [?t :seon.test/sym ?s]
                                   (or [?t :seon.fn/keywords :seon.test-support/fresh-store?]
                                       [?t :seon.fn/keywords :seon.test-support/database-id])] db))
      observed (set (seon.db/q '[:find [?s ...] :where [?t :seon.test/sym ?s] [?t :seon.test/fixture-observation _]] db))
      narrow (clojure.set/union direct (clojure.set/intersection fresh requesters))]
  {:direct (count direct) :fresh-reach (count fresh) :keyword-requesters (count requesters)
   :narrow-expensive (count narrow)
   :narrow-without-observation (count (clojure.set/difference narrow observed))
   :observed (count observed)})

;; The first generator run's size and its distribution by responsible namespace.
;; => {:counts {:d1 51 :d2 39 :d3 303 :d5 205 :d7 135} :total 733 :distinct-ns 176}
(let [db (seon.db/db (seon.operator/connection "default"))
      nongen (set (seon.db/q '[:find [?k ...] :where [?e :seon.schema/key ?k] [?e :seon.schema/generatable? false]] db))
      specs (seon.db/q '[:find ?s ?spec :where [?f :seon.fn/sym ?s] [?f :seon.fn/private? false]
                         [?f :seon.fn/source _] [?f :seon.fn/spec ?spec]] db)
      d5 (for [[s spec] specs :let [form (try (clojure.edn/read-string spec) (catch Throwable _ nil))]
               :when (and form (some #(and (keyword? %) (nongen %)) (tree-seq coll? seq form)))] s)
      d1 (clojure.set/difference
          (set (seon.db/q '[:find [?k ...] :where [?e :seon.schema/key ?k] [?e :seon.db/attributes true]] db))
          (set (seon.db/q '[:find [?k ...] :where [?e :seon.schema/key ?k] [?e :seon.db/attributes true]
                            [?e :seon.render/ai _] [?e :seon.render/html _]] db)))
      d2 (seon.db/q '[:find [?s ...] :where [?f :seon.fn/sym ?s] [?f :seon.fn/private? false]
                      [?f :seon.fn/source _] (not [?f :seon.fn/doc _])] db)
      d3 (seon.fn/functions-without-tests db)
      d7 (seon.db/q '[:find [?s ...] :where [?t :seon.test/sym ?s]
                      (not-join [?t] [?t :seon.fn/calls ?c] [?c :seon.fn/sym ?cs]
                                [(clojure.string/starts-with? ?cs "clojure.") ?core] [(not ?core)])] db)
      nsof #(namespace (symbol %))
      all (concat (map namespace d1) (map nsof d2) (map nsof d3) (map nsof d5) (map nsof d7))]
  {:counts {:d1 (count d1) :d2 (count d2) :d3 (count d3) :d5 (count d5) :d7 (count d7)}
   :total (count all) :distinct-ns (count (distinct all))
   :top15 (take 15 (sort-by (comp - val) (frequencies all)))})
