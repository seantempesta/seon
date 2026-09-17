(ns seon.error.refusal-test
  "The cause-chain reader enumerates the errors it can return.

  `seon.error.refusal/refusal` is a genuine pass-through: the map it returns
  is whatever data another operation attached to a throwable, so the host
  wrapper's per-arity facet enforcement observed it returning
  `:seon.instrument/undeclared-error` and `:seon.instrument/contract-error`
  values its `[:or :nil :map]` output never declared
  (`docs/prds/steward-platform/research/error-wrapper-enforcement-2026-09-18.md`).
  Program-facts PRD §1q rules the repair: the output lists, as an explicit
  `:or` union, every error facet the function can return — the complete
  canonical population for a helper that genuinely passes any through, never
  a projection-derived catch-all spelling.

  Two properties hold that enumeration honest. The first compares the two
  declarations with the projection's own canonical facet population, so a
  facet landing in the manifest fails here instead of drifting silently. The
  second ARMS both Vars with their declared contracts, on the canonical
  database fixture's projection, and calls them across the three families a
  cause chain actually carries: a base-only error value, a facet-bearing one,
  and ordinary unclassified `ex-data`. Under `:panic` an undeclared return
  throws, so a returned value IS the proof the wrapper admitted it."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.error.refusal :as error.refusal]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(def ^:private entries [#'error.refusal/refusal #'error/refusal])

(def ^:private base
  {:seon.error/at #inst "2026-09-18T00:00:00Z"
   :seon.error/layer :seon.error.refusal-test/probe
   :seon.error/operation 'seon.error.refusal-test/probe})

(def ^:private domain
  (assoc base :seon.agent/error-agent-id "refusal-agent"))

(defn- declared-output
  [projection entry]
  (#'instrument/declared-result projection (last (:malli/schema (meta entry)))))

(deftest both-refusal-entries-enumerate-the-canonical-facet-population
  (test-support/with-database
   (fn [connection]
     (let [projection (schema/projection-from-database (db/db connection))]
       (doseq [entry entries]
         (testing (str entry)
           (let [declared (declared-output projection entry)]
             (is (= (error/facet-keys projection) (:seon.instrument/declared declared))
                 "The declared union is the projection's complete facet population.")
             (is (true? (:seon.instrument/base? declared))
                 "A base-shaped return needs explicit base permission."))))))))

(deftest armed-refusal-returns-every-declared-family-without-refusing
  (test-support/preserving-instrumentation-state
   (fn []
     (test-support/with-database
      (fn [connection]
        (let [projection (schema/projection-from-database (db/db connection))
              caps (config/result-caps (test-support/effective-config))
              buried (fn [data]
                       (RuntimeException. "wrapper" (ex-info "buried" data)))]
          (doseq [entry entries]
            (#'instrument/arm-var! entry (:malli/schema (meta entry))
                                   projection projection caps
                                   {:seon.config/on-core-error :panic}))
          (doseq [entry entries
                  [label data] [[:base base]
                                [:facet domain]
                                [:unclassified {:rule :ordinary-ex-data}]]]
            (testing (str entry " " label)
              (let [returned (@entry (buried data))]
                (is (= data returned)
                    "The armed wrapper admits the value the cause chain carried."))))
          (testing "each classified family validates against a declared alternative"
            (doseq [[value facet] [[base nil]
                                   [domain :seon.agent/error]]]
              (is ((schema/projection-validator projection :seon.error/base) value)
                  (pr-str value))
              (when facet
                (is (contains? (error/facets projection value) facet)
                    (pr-str value)))))
          (doseq [entry entries]
            (testing (str entry " carries no data anywhere in the chain")
              (is (nil? (@entry (RuntimeException. "no data"))))
              (is (nil? (@entry nil)))))))))))
