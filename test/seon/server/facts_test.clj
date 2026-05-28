(ns seon.server.facts-test
  "Tests for the facts knowledge base schema + seed data.

   Verifies:
   - facts-schema.edn installs cleanly
   - facts-seed.edn parses, all entries can be transacted
   - re-seed is idempotent (:fact/id uniqueness)
   - query by :fact/subject and :fact/predicate work
   - record-fact upsert by :fact/id

   Each test spawns its own JVM writer (in-memory backend) and tears it down."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [seon.server.test-util :as tu]
            [seon.server.transit :as transit]))

(set! *warn-on-reflection* true)

;; ---------- Fixture (shared in-process writer, see seon.server.test-util) ----------

(use-fixtures :each tu/with-fresh-writer)

(defn- req! [op extra] (tu/req! op extra))

(defn- result-of [resp] (transit/read-str (get resp "result")))

;; ---------- Loaders ----------

(def schema-path "resources/seed/facts-schema.edn")
(def seed-path   "resources/seed/facts-seed.edn")

(defn- read-edn [p]
  (-> p io/resource slurp edn/read-string))

(defn- install-schema! []
  (let [schema (read-edn (subs schema-path (count "resources/")))]
    (req! "transact" {"tx-data" (pr-str schema)})))

(defn- normalize-fact
  "Match the guest's sidecar-poc.facts/normalize-seed-fact behavior: :fact/object
   must be a string in storage; raw values get pr-str'd."
  [m]
  (if-let [obj (:fact/object m)]
    (assoc m :fact/object (if (string? obj) obj (pr-str obj)))
    m))

(defn- transact-seed! []
  (let [raw   (read-edn (subs seed-path (count "resources/")))
        norms (mapv normalize-fact raw)]
    (req! "transact" {"tx-data" (pr-str norms)})))

;; ---------- Tests ----------

(deftest test-schema-installs
  (testing "facts-schema.edn parses and transacts cleanly"
    (let [r (install-schema!)]
      (is (= true (get r "ok"))
          (str "schema install failed: " (pr-str r)))
      (is (pos? (get r "datoms-added"))))))

(deftest test-seed-installs
  (testing "facts-seed.edn parses, normalizes, and transacts cleanly"
    (install-schema!)
    (let [r (transact-seed!)]
      (is (= true (get r "ok"))
          (str "seed transact failed: " (pr-str r))))
    ;; Total fact count matches the seed.
    (let [raw (read-edn (subs seed-path (count "resources/")))
          q   (req! "q" {"query" "[:find (count ?e) . :where [?e :fact/id _]]"
                         "args"  []})]
      (is (= true (get q "ok")))
      (is (= (count raw) (result-of q))
          "all seed facts present"))))

(deftest test-seed-idempotent
  (testing "re-running the seed is a no-op (upsert by :fact/id)"
    (install-schema!)
    (transact-seed!)
    (let [first-count (result-of (req! "q" {"query" "[:find (count ?e) . :where [?e :fact/id _]]"
                                            "args"  []}))]
      (transact-seed!)
      (transact-seed!)
      (let [final-count (result-of (req! "q" {"query" "[:find (count ?e) . :where [?e :fact/id _]]"
                                              "args"  []}))]
        (is (= first-count final-count)
            "re-seeding did not duplicate facts")))))

(deftest test-query-by-subject
  (testing "query facts by :fact/subject returns all facts about that subject.
            With the Transit wire format, keywords round-trip as keywords:
            no more string coercion at the boundary."
    (install-schema!)
    (transact-seed!)
    (let [q (req! "q" {"query" "[:find ?id ?p
                                  :where
                                  [?e :fact/subject :seon/project]
                                  [?e :fact/id ?id]
                                  [?e :fact/predicate ?p]]"
                       "args" []})]
      (is (= true (get q "ok")))
      (let [rows (result-of q)]
        (is (pos? (count rows))
            "at least one fact about :seon/project")
        (let [predicates (set (map second rows))]
          (is (contains? predicates :thesis))
          (is (contains? predicates :uses-library)))))))

(deftest test-query-by-predicate
  (testing "query facts by :fact/predicate returns all facts with that predicate"
    (install-schema!)
    (transact-seed!)
    (let [q (req! "q" {"query" "[:find ?id ?s
                                  :where
                                  [?e :fact/predicate :uses-library]
                                  [?e :fact/id ?id]
                                  [?e :fact/subject ?s]]"
                       "args" []})]
      (is (= true (get q "ok")))
      (let [rows (result-of q)]
        (is (pos? (count rows))
            "at least one :uses-library fact")
        ;; With Transit, keywords round-trip as keywords.
        (is (every? #(= :seon/project (second %)) rows))))))

(deftest test-record-fact-upsert
  (testing "transacting a fact with an existing :fact/id updates rather than duplicating"
    (install-schema!)
    (let [f1 {:fact/id "test-upsert-1"
              :fact/subject :test/subject
              :fact/predicate :test/pred
              :fact/object "\"v1\""
              :fact/confidence 50
              :fact/recorded-by :test/bot
              :fact/recorded-at (java.util.Date.)}
          f2 (assoc f1 :fact/object "\"v2\"" :fact/confidence 100)]
      (req! "transact" {"tx-data" (pr-str [f1])})
      (req! "transact" {"tx-data" (pr-str [f2])})
      (let [q (req! "q" {"query" "[:find ?o ?c
                                    :where
                                    [?e :fact/id \"test-upsert-1\"]
                                    [?e :fact/object ?o]
                                    [?e :fact/confidence ?c]]"
                         "args" []})
            rows (result-of q)]
        (is (= 1 (count rows))
            "exactly one fact entity with the id")
        (let [[o c] (first rows)]
          (is (= "\"v2\"" o))
          ;; Float comparison.
          (is (= 100 c) "confidence was updated to 100"))))))
