(ns seon.warn-test
  "Pure warning transformations over the ordinary data acquired by
   seon.agent.ctx.warnings. Database batching, cutoff selection, and immutable
   database ownership belong to that acquisition namespace's tests."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing]]
    [seon.warn :as warn]))

(def ^:private slow-eval-duration-ms 1500)

(def ^:private function-rows
  [["warntest.main/no-spec" :warntest.main "" true false ""]
   ["warntest.main/any-ret" :warntest.main
    "[:=> [:cat :string] :any]" true false ""]
   ["warntest.main/any-arg" :warntest.main
    "[:=> [:catn [:warntest.main/payload :any]] :string]" true false ""]
   ["warntest.main/maybe-fn" :warntest.main
    "[:=> [:cat [:maybe :string]] :string]" true false ""]
   ["warntest.main/no-ret" :warntest.main
    "[:=> [:cat :string]]" true false ""]
   ["warntest.main/no-input" :warntest.main
    "[:=> :string]" true false ""]
   ["warntest.main/-helper" :warntest.main "" true true ""]
   ["warntest.main/clean" :warntest.main
    "[:=> [:cat :string] :string]" true false ""]
   ["warntest.other/also-unspecced" :warntest.other "" true false ""]])

(def ^:private acquired-data
  {::warn/function-rows function-rows
   ::warn/installed-schema
   {:warntest.dom/duration-seconds {}
    :warntest.dom/duration-minutes {}
    :warntest.dom/date {}
    :warntest.dom/type {}}
   ::warn/schema-provenance
   [[:warntest.dom/duration-seconds :seon.db.process/agent]
    [:warntest.dom/duration-minutes :seon.db.process/agent]
    [:warntest.dom/date :seon.db.process/agent]
    [:warntest.dom/type :seon.db.process/agent]]
   ::warn/schema-forms []
   ::warn/attribute-counts
   {:warntest.dom/duration-seconds 2
    :warntest.dom/duration-minutes 1
    :warntest.dom/date 2
    :warntest.dom/type 2}
   ::warn/failed-evals
   [["EVLwarnFAIL001" "boom — generic failure"]
    ["EVLwarnREF0001"
     "Error: Lookup ref attribute should be marked as :db/unique: [:kb.doc/path \"x\"]"]]
   ::warn/fs-results
   [["EVLwarnFSDENY1"
     (str "{:seon.agent.fs/ok? false, :seon.agent.fs/path \"/etc/passwd\", "
          ":seon.agent.fs/error \"path outside allowed-roots [\\\"/Users/x/work\\\"]\"}")
     (js/Date. 230)]
    ["EVLwarnFSGRANT"
     "{:seon.agent.fs/allowed-roots [\"/Users/x/work\"], :seon.agent.fs/read-only? false}"
     (js/Date. 240)]]
   ::warn/hop-messages
   [["MSGwarntestHOP" warn/hop-cap (js/Date. 400)
     "warntest-agent" "warntest-peer"]]
   ::warn/record-errors []
   ::warn/slow-evals [["EVLwarnSLOW001" slow-eval-duration-ms]]
   ::warn/failing-tests [["warntest.main/broken-test"]]
   ::warn/canvases []})

(defn- request
  ([] {::warn/data acquired-data})
  ([namespace] {::warn/data acquired-data :seon.warn/ns namespace}))

(defn- affected-syms [response]
  (set (map :seon.warn/sym (:seon.warn/affected response))))

(deftest corpus-checks-classify-exact-contract-defects
  (testing "scope and public-function filtering"
    (let [response (warn/check-no-malli-schema (request :warntest.main))]
      (is (= :no-malli-schema (:seon.warn/kind response)))
      (is (= #{"warntest.main/no-spec"} (affected-syms response)))))
  (testing "return and named argument locations"
    (let [return-response (warn/check-return-is-any (request :warntest.main))
          argument-response (warn/check-arg-is-any (request :warntest.main))]
      (is (= #{"warntest.main/any-ret"} (affected-syms return-response)))
      (is (= "return"
             (:seon.warn/where (first (:seon.warn/affected return-response)))))
      (is (= #{"warntest.main/any-arg"} (affected-syms argument-response)))
      (is (= "arg :warntest.main/payload"
             (:seon.warn/where (first (:seon.warn/affected argument-response)))))))
  (testing "maybe and incomplete function schemas"
    (is (= #{"warntest.main/maybe-fn"}
           (affected-syms (warn/check-uses-maybe (request :warntest.main)))))
    (is (= #{"warntest.main/no-ret"}
           (affected-syms (warn/check-no-return-spec
                           (request :warntest.main)))))
    (is (= #{"warntest.main/no-input"}
           (affected-syms (warn/check-no-input-spec
                           (request :warntest.main))))))
  (testing "unscoped checks include every acquired namespace"
    (is (contains? (affected-syms (warn/check-no-malli-schema (request)))
                   "warntest.other/also-unspecced"))))

(deftest parallel-attr-classifies-the-less-established-unit
  (let [response (warn/check-parallel-attr (request))
        entry (first (:seon.warn/affected response))]
    (is (= :parallel-attr (:seon.warn/kind response)))
    (is (= #{":warntest.dom/duration-minutes"} (affected-syms response)))
    (is (str/includes? (:seon.warn/where entry)
                       ":warntest.dom/duration-seconds"))
    (is (str/includes? (:seon.warn/where entry) "2"))
    (is (not (contains? (affected-syms response) ":warntest.dom/date")))))

(deftest domain-attrs-use-exact-schema-provenance
  (let [data (assoc acquired-data
                    ::warn/installed-schema
                    {:seon.agent/id {}
                     :my.workout/date {}
                     :seon.schema/key {}}
                    ::warn/schema-provenance
                    [[:seon.agent/id :seon.db.process/boot]
                     [:my.workout/date :seon.db.process/agent]])
        attrs (set (warn/domain-attrs {::warn/data data}))]
    (is (= #{:my.workout/date} attrs))))

(def ^:private unmarked-schema-forms
  [[:warntest.shared/core-id
    (pr-str [:string {:seon.db/identity true}])]
   [:warntest.shared/agent-id
    (pr-str [:string {:seon.db/identity true}])]
   [:warntest.shared/lookup
    (pr-str [:map
             [:warntest.shared/agent-id :warntest.shared/agent-id]])]])

(defn- unmarked-check-data [attribute-counts]
  {::warn/installed-schema
   {:warntest.shared/core-id {:db/unique :db.unique/identity}
    :warntest.shared/agent-id {:db/unique :db.unique/identity}}
   ::warn/schema-provenance
   [[:warntest.shared/core-id :seon.db.process/boot]
    [:warntest.shared/agent-id :seon.db.process/agent]]
   ::warn/schema-forms unmarked-schema-forms
   ::warn/attribute-counts attribute-counts})

(deftest unmarked-entity-kinds-excludes-exact-boot-attrs-and-heals-from-forms
  (let [data (unmarked-check-data
              {:warntest.shared/core-id 1
               :warntest.shared/agent-id 1})
        response (warn/check-unmarked-entity-kinds {::warn/data data})
        entry (first (:seon.warn/affected response))]
    (is (= :unmarked-entity-kinds (:seon.warn/kind response)))
    (is (= #{":warntest.shared/agent-id"} (affected-syms response))
        "only the exact boot-authored attr is excluded, not its namespace")
    (is (str/includes? (:seon.warn/where entry)
                       ":warntest.shared/lookup"))
    (let [marked-data
          (update data ::warn/schema-forms conj
                  [:warntest.shared/entity
                   (pr-str
                    [:map {:seon.db/entity true}
                     [:warntest.shared/agent-id
                      :warntest.shared/agent-id]])])]
      (is (= []
             (:seon.warn/affected
              (warn/check-unmarked-entity-kinds
               {::warn/data marked-data})))
          "the database-owned marked form heals the warning"))))

(deftest unmarked-entity-kinds-requires-stored-identity-rows
  (let [data (unmarked-check-data
              {:warntest.shared/core-id 0
               :warntest.shared/agent-id 0})]
    (is (= []
           (:seon.warn/affected
            (warn/check-unmarked-entity-kinds {::warn/data data}))))))

(deftest runtime-checks-classify-acquired-relations
  (testing "failed evals and lookup-ref failures have separate owners"
    (is (= #{"EVLwarnFAIL001"}
           (affected-syms (warn/check-failed-evals (request)))))
    (let [response (warn/check-bad-ref (request))]
      (is (= #{"EVLwarnREF0001"} (affected-syms response)))
      (is (str/includes?
           (:seon.warn/where (first (:seon.warn/affected response)))
           ":kb.doc/path"))))
  (testing "filesystem denial excludes a grants read-back"
    (let [response (warn/check-fs-denied (request))]
      (is (= #{"EVLwarnFSDENY1"} (affected-syms response)))
      (is (str/includes?
           (:seon.warn/where (first (:seon.warn/affected response)))
           "path outside allowed-roots"))))
  (testing "hop, slow-eval, and failing-test relations retain exact identity"
    (is (= #{"MSGwarntestHOP"}
           (affected-syms (warn/check-hop-exhausted (request)))))
    (let [slow (warn/check-slow-evals (request))]
      (is (= #{"EVLwarnSLOW001"} (affected-syms slow)))
      (is (= (str slow-eval-duration-ms "ms")
             (:seon.warn/where (first (:seon.warn/affected slow))))))
    (is (= #{"warntest.main/broken-test"}
           (affected-syms (warn/check-failing-tests (request)))))))

(deftest canvas-warning-is-pure-over-acquired-content
  (let [missing 'my.agent.warntst/missing-canvas
        broken (assoc acquired-data ::warn/canvases [["agent-a" missing]])
        healthy (-> acquired-data
                    (assoc ::warn/canvases
                           [["agent-a" 'my.shared/current-canvas]
                            ["agent-b" [:div "literal"]]])
                    (update ::warn/function-rows conj
                            ["my.shared/current-canvas" :my.shared
                             "[:=> [:cat :seon.render/system-input] :seon.render/html-response]"
                             true false ""]))
        response (warn/check-canvas-unresolved {::warn/data broken})]
    (is (true? (:seon.warn/urgent? response)))
    (is (= #{(str missing)} (affected-syms response)))
    (is (str/includes?
         (:seon.warn/where (first (:seon.warn/affected response)))
         "agent-a"))
    (is (str/includes? (:seon.warn/explain response)
                       "absent from the current database program"))
    (is (str/includes? (:seon.warn/explain response)
                       "return Hiccup through my.canvas/view"))
    (is (str/includes? (:seon.warn/example response) "my.canvas/show!"))
    (is (= []
           (:seon.warn/affected
            (warn/check-canvas-unresolved {::warn/data healthy})))
        "a resolving symbol or literal canvas is clean")))

(defn- healthy-fake-check [_request]
  {:seon.warn/kind :fake-healthy
   :seon.warn/affected [{:seon.warn/sym "warntest.main/fake-defect"}]
   :seon.warn/explain "a healthy check that fires"
   :seon.warn/example "; fixture"})

(defn- throwing-fake-check [_request]
  (throw (ex-info "boom from fake check" {})))

(deftest rendering-is-empty-when-clean-and-isolates-a-throwing-check
  (with-redefs [warn/checks []]
    (is (= "" (warn/render-warnings (request)))))
  (with-redefs [warn/checks [healthy-fake-check throwing-fake-check]]
    (let [clusters (warn/run-checks (request))
          by-kind (group-by :seon.warn/kind clusters)
          synthetic (first (:warn-check-error by-kind))
          text (warn/render-warnings (request))]
      (is (= 2 (count clusters)))
      (is (some? (:fake-healthy by-kind)))
      (is (str/includes? (:seon.warn/explain synthetic)
                         "boom from fake check"))
      (is (str/includes? (:seon.warn/explain synthetic)
                         "throwing-fake-check"))
      (is (str/includes? text "fake-healthy"))
      (is (str/includes? text "warn-check-error")))))
