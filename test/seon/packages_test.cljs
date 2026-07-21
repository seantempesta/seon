(ns seon.packages-test
  (:require
   [cljs.reader :as reader]
   [cljs.test :refer [deftest is testing]]
   [seon.db :as db]
   [seon.packages :as packages]
   [seon.schema :as schema]))

(def npm-request
  {::packages/npm "playwright-core@^1.61"
   ::packages/as 'seon.packages.browser})

(def deps-request
  {::packages/deps
   '{org.clojure/data.csv {:mvn/version "1.1.0"}}
   ::packages/as 'seon.packages.csv})

(def npm-row
  {::packages/as 'seon.packages.browser
   :seon.packages.npm/name "playwright-core"
   :seon.packages.npm/range "^1.61"})

(def deps-row
  {::packages/as 'seon.packages.csv
   :seon.packages.deps/lib 'org.clojure/data.csv
   :seon.packages.deps/coord "{:mvn/version \"1.1.0\"}"})

(deftest install-request-shape-and-boundary-namespace-are-explicit
  (is (schema/valid-candidate-value? ::packages/install-request npm-request))
  (is (schema/valid-candidate-value? ::packages/install-request deps-request))
  (is (not (schema/valid-candidate-value?
            ::packages/install-request
            {::packages/as 'seon.packages.both
             ::packages/npm "left-pad@1.3.0"
             ::packages/deps '{org.clojure/data.csv {:mvn/version "1.1.0"}}})))
  (is (packages/boundary-namespace? 'seon.packages.browser))
  (doseq [illegal ['browser 'seon.packages 'my.packages.browser
                   'seon.packages.bad/child]]
    (let [result
          (packages/validate-install
           {::packages/request (assoc npm-request ::packages/as illegal)
            ::packages/rows []})]
      (is (false? (packages/boundary-namespace? illegal)))
      (is (= :seon.packages.rule/illegal-as
             (get-in result [:seon/error :seon.error/data ::packages/rule]))))))

(deftest ledger-attributes-bridge-to-the-settled-database-shapes
  (let [facets
        (into {}
              (map (juxt :db/ident identity))
              (db/malli->datahike-schema
               [::packages/as
                :seon.packages.npm/name
                :seon.packages.npm/range
                :seon.packages.deps/lib
                :seon.packages.deps/coord]))]
    (is (= :db.type/symbol (get-in facets [::packages/as :db/valueType])))
    (is (= :db.unique/identity (get-in facets [::packages/as :db/unique])))
    (is (= :db.type/string
           (get-in facets [:seon.packages.npm/name :db/valueType])))
    (is (= :db.type/symbol
           (get-in facets [:seon.packages.deps/lib :db/valueType])))
    (is (= :db.type/string
           (get-in facets [:seon.packages.deps/coord :db/valueType])))))

(deftest routing-and-collisions-follow-attribute-presence
  (is (= :seon.packages.host/bun (packages/row->host npm-row)))
  (is (= :seon.packages.host/jvm (packages/row->host deps-row)))
  (testing "an occupied namespace cannot silently switch ecosystems"
    (let [result
          (packages/validate-install
           {::packages/request
            (assoc deps-request ::packages/as 'seon.packages.browser)
            ::packages/rows [npm-row]})]
      (is (= :seon.packages.rule/ecosystem-switch
             (get-in result [:seon/error :seon.error/data ::packages/rule])))))
  (testing "one ecosystem coordinate cannot map to two namespaces"
    (let [result
          (packages/validate-install
           {::packages/request
            (assoc npm-request ::packages/as 'seon.packages.browser2)
            ::packages/rows [npm-row]})]
      (is (= :seon.packages.rule/coordinate-collision
             (get-in result [:seon/error :seon.error/data ::packages/rule])))
      (is (= 'seon.packages.browser
             (get-in result
                     [:seon/error :seon.error/data ::packages/occupying-as]))))))

(deftest npm-manifest-is-byte-stable-and-complete
  (let [other-row
        {::packages/as 'seon.packages.sharp
         :seon.packages.npm/name "sharp"
         :seon.packages.npm/range "0.34.3"}
        rows [other-row npm-row]
        generated
        (packages/npm-manifest
         {::packages/rows rows
          :seon.config.packages/trusted-lifecycle-scripts :all})
        parsed (js->clj (js/JSON.parse generated))
        regenerated-rows
        (mapv (fn [[package-name package-range]]
                {::packages/as
                 (symbol (str "seon.packages.roundtrip."
                              (if (= package-name "sharp") "sharp" "browser")))
                 :seon.packages.npm/name package-name
                 :seon.packages.npm/range package-range})
              (get parsed "dependencies"))]
    (is (= {"playwright-core" "^1.61" "sharp" "0.34.3"}
           (get parsed "dependencies")))
    (is (= ["playwright-core" "sharp"]
           (get parsed "trustedDependencies")))
    (is (= generated
           (packages/npm-manifest
            {::packages/rows regenerated-rows
             :seon.config.packages/trusted-lifecycle-scripts :all})))
    (is (= ["sharp"]
           (get
            (js->clj
             (js/JSON.parse
              (packages/npm-manifest
               {::packages/rows rows
                :seon.config.packages/trusted-lifecycle-scripts #{"sharp"}})))
            "trustedDependencies")))))

(deftest deps-manifest-preserves-the-verbatim-coordinate-data
  (let [rows
        [deps-row
         {::packages/as 'seon.packages.tools
          :seon.packages.deps/lib 'org.example/tools
          :seon.packages.deps/coord
          "{:git/sha \"abc123\", :git/url \"https://example.test/tools.git\"}"}]
        generated (packages/deps-manifest {::packages/rows rows})
        parsed (reader/read-string generated)
        regenerated-rows
        (mapv (fn [[lib coord]]
                {::packages/as
                 (symbol (str "seon.packages.roundtrip." (name lib)))
                 :seon.packages.deps/lib lib
                 :seon.packages.deps/coord (pr-str coord)})
              (:deps parsed))]
    (is (= {:mvn/version "1.1.0"}
           (get-in parsed [:deps 'org.clojure/data.csv])))
    (is (= {:git/sha "abc123"
            :git/url "https://example.test/tools.git"}
           (get-in parsed [:deps 'org.example/tools])))
    (is (= generated
           (packages/deps-manifest {::packages/rows regenerated-rows})))))

(deftest install-planning-upserts-converges-and-enforces-policy
  (let [fresh
        (packages/plan-install
         {::packages/request npm-request ::packages/rows []})
        update
        (packages/plan-install
         {::packages/request (assoc npm-request ::packages/npm
                                    "playwright-core@^1.62")
          ::packages/rows [npm-row]})
        converged
        (packages/plan-install
         {::packages/request npm-request ::packages/rows [npm-row]})
        closed
        (packages/plan-install
         {::packages/request npm-request
          ::packages/rows []
          :seon.config.packages/policy :closed})]
    (is (= :install (::packages/operation fresh)))
    (is (= [npm-row] (::packages/tx-data fresh)))
    (is (= :update (::packages/operation update)))
    (is (= "^1.62"
           (-> update ::packages/tx-data first
               :seon.packages.npm/range)))
    (is (true? (::packages/converged? converged)))
    (is (empty? (::packages/tx-data converged)))
    (is (= :seon.packages.rule/policy-closed
           (get-in closed [:seon/error :seon.error/data ::packages/rule])))
    (is (= :seon.config.packages/policy
           (get-in closed
                   [:seon/error :seon.error/data ::packages/config-key])))))

(deftest remove-and-installed-views-derive-from-ledger-facts
  (let [rows [(assoc npm-row
                     :seon.packages.npm/resolved "1.61.2"
                     :seon.packages.npm/integrity "sha512-example"
                     ::packages/generation 4)
              deps-row]
        view (packages/installed rows)
        remove
        (packages/plan-remove
         {::packages/request {::packages/as 'seon.packages.browser}
          ::packages/rows rows})
        absent
        (packages/plan-remove
         {::packages/request {::packages/as 'seon.packages.absent}
          ::packages/rows rows})]
    (is (= ['seon.packages.browser 'seon.packages.csv]
           (mapv ::packages/as view)))
    (is (= "playwright-core@^1.61" (::packages/npm (first view))))
    (is (= '{org.clojure/data.csv {:mvn/version "1.1.0"}}
           (::packages/deps (second view))))
    (is (= :remove (::packages/operation remove)))
    (is (= [[:db.fn/retractEntity
             [::packages/as 'seon.packages.browser]]]
           (::packages/tx-data remove)))
    (is (true? (::packages/converged? absent)))
    (is (empty? (::packages/tx-data absent)))))
