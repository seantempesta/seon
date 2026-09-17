(ns seon.adoption-rows-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.fn :as seon.fn]
            [seon.program :as program]
            [seon.db :as db]
            [seon.error :as error]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(deftest development-acquisition-contains-one-agent-row-fault
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "adoption-rows")
      (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name "adoption-rows"
                      :seon.config/manifest {}})
      (let [source-database @connection
            namespace-name 'acquire.rows
            agent-id "acquire-rows-author"
            good-source
            (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                 "good [x] (inc x))")
]
        (test-support/transacted!
                     connection
                     [{:seon.agent/id agent-id
                       :seon.agent/namespace
                       {:seon.ns/name namespace-name
                        :seon.ns/source "(ns acquire.rows)"}}
                      {:seon.fn/sym "acquire.rows/bad"
                       :seon.schema.admission/source :agent
                       :seon.fn/ns [:seon.ns/name namespace-name]
                       :seon.fn/source
                       (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                            "bad [x] (unavailable-function x))")
                       :seon.fn/arglists "([x])"
                       :seon.fn/private? false
                       :seon.fn/spec "[:=> [:cat :int] :int]"}
                      {:seon.fn/sym "acquire.rows/good"
                       :seon.schema.admission/source :agent
                       :seon.fn/ns [:seon.ns/name namespace-name]
                       :seon.fn/source good-source
                       :seon.fn/arglists "([x])"
                       :seon.fn/private? false
                       :seon.fn/spec "[:=> [:cat :int] :int]"}])
        (let [ctx
              (assoc (eval/build-base-ctx (seon.schema/handed-projection))
                     :seon.sci.eval/custody
                     {:seon.db/connection connection})
              acquired
              (#'cluster/acquire-development!
               connection "adoption-rows" ctx
               (schema/projection-from-database @connection))
              refusal
              ;; The durable error identity row carries id, signature, kind
              ;; and its occurrences; the MESSAGE is an occurrence fact
              ;; (`src/seon/error.clj:1392` mints the identity row without
              ;; one). `seon.error/latest-fact` is the declared projection
              ;; that answers `:seon.error/message` from the latest
              ;; occurrence, so this reads the refusal the way every other
              ;; error consumer does.
              (some-> (first
                       (db/q '[:find [(pull ?error [*]) ...]
                               :where
                               [?error :seon.error/kind
                                :seon.sci.eval/acquisition-refused]]
                             @connection))
                      error/latest-fact)]
          (is (nil? (db/pull source-database [:seon.ns/name]
                            [:seon.ns/name namespace-name])))
          (is (= 1 (count (db/q '[:find [?e ...] :where
                                  [?e :seon.error/kind :seon.sci.eval/acquisition-refused]]
                                @connection))))
          (is (= 42 (sci/eval-string* ctx "(acquire.rows/good 41)"))
              "a later valid row installs and works")
          (is (= 1 (count (:seon.sci.eval/acquisition-refusals acquired)))
              (pr-str (mapv #(select-keys % [:seon.error/message :seon.error/kind])
                            (:seon.sci.eval/acquisition-refusals acquired))))
          (is (true?
               (:seon.sci.eval/acquisition-refusals-recorded? acquired)))
          (is (some? refusal) "the contained agent mistake is a durable fact")
          (is (str/includes? (:seon.error/message refusal)
                             "[:seon.fn/sym \"acquire.rows/bad\"]"))
          (is (str/includes? (:seon.error/message refusal)
                             "unavailable-function")
              "the fact retains the row's typed cause as queryable evidence"))))))

(deftest adoption-identities-carry-no-nil-member
  ;; A scalar publication's rows carry the file-digest row and any analyzer
  ;; findings alongside declarations. A derivation that rosters identity
  ;; attributes per call reads those rows as nil, and the adoption record then
  ;; refuses as a `[:set :seon.db/ref]` whose member is `#{nil}`.
  (let [path (.getCanonicalPath (io/file "src/seon/cluster.clj"))
        rows (:seon.fn.file/rows
              (seon.fn/build-artifact
               {:seon.fn/source-path path
                :seon.fn.file/first-party-functions []}))
        attributes (into #{} (map #(first (program/row-identity %))) rows)
        identities (#'cluster/adoption-identities
                    (into [] (keep program/row-identity) rows))]
    (is (contains? attributes :seon.fn.file/relative-path)
        "the file-digest row is part of a published file's rows")
    (is (every? some? identities))
    (is (not (contains? (set identities) nil))
        "the transacted set carries no nil member")
    (is (every? #(contains? #{:seon.ns/name :seon.fn/sym :seon.schema/key
                              :seon.test/sym}
                            (first %))
                identities)
        (pr-str (into #{} (map first) identities)))
    (is (some #(= :seon.ns/name (first %)) identities)
        "declarations are still recorded")))
