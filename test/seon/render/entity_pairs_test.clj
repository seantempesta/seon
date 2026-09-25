(ns seon.render.entity-pairs-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.render.ns :as render.ns]
            [seon.render.test :as render.test]
            [seon.render.walk :as walk]
            [seon.schema :as schema]
            [seon.test-support :as support]
            [seon.program :as program]))

(defn- exercise-pair
  [entity-lookup expected-ai expected-html]
  (support/with-database
   (fn [connection]
     (let [report
           (support/transacted! connection
             (into (support/agent-tx @connection "entity-pairs-agent")
               [(support/namespace-row @connection 'entity-pairs.fixture)
              
                (support/program-fn-row (db/db connection) 'entity-pairs.fixture/function
                                        "(defn function [] true)")
                (support/program-row (db/db connection) [:seon.test/sym 'entity-pairs.fixture/test]
                                     "(clojure.test/deftest test (clojure.test/is true))"
                                     {:seon.fn/calls #{'entity-pairs.fixture/function}})
                {:seon.issue/id "entity-pairs-issue"
                 :seon.issue/title "Render linked entities"
                 :seon.issue/problem "Verify the entity pairs"
                 :seon.issue/status :open :seon.issue/severity :cleanup
                 :seon.issue/agent [:seon.agent/id "entity-pairs-agent"]
                 :seon.issue/functions [[:seon.fn/sym (quote entity-pairs.fixture/function)]]
                 :seon.issue/tests [[:seon.test/sym (quote entity-pairs.fixture/test)]]}]))]
       (is (:db-after report) (pr-str report)))
     (let [database (db/db connection)
           entity (db/pull database '[*] entity-lookup)
           ctx (support/fork-cluster-ctx connection)
           request {:seon.db/db database :seon.sci.eval/ctx ctx
                    :seon.render/value entity
                    :seon.render/profile (render/agent-render-profile config/defaults)
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 5000
                    :seon.config/on-core-error :panic
                    :seon.render/distance 1
                    :seon.render.walk/lookup [:seon.issue/id "entity-pairs-issue"]}
           acquisition (walk/root-acquisition request)]
       (is (= 1 (get-in acquisition [:seon.render.walk/members entity-lookup
                                     :seon.render.walk/found-depth])))
       (doseq [[output expected] [[:seon.render/ai expected-ai]
                                  [:seon.render/html expected-html]]]
         (let [request (assoc request :seon.render/output output)
               decision (render/selection request)
               rendered (if (= output :seon.render/ai)
                          (render/render-ai request) (render/render-html request))
               units (walk/neighborhood request)
               unit (first (filter #(= entity-lookup (:seon.render.walk/lookup %)) units))]
           (is (= expected (:seon.render.selection/selected decision)) (pr-str decision))
           (is (if (= output :seon.render/ai) (string? rendered) (hiccup/hiccup? rendered))
               (pr-str rendered))
           (is unit (pr-str units))
           (is (nil? (:seon.error/value unit)) (pr-str unit))
           (is (= rendered (:seon.render/output unit)) (pr-str unit))))
       (when (= :seon.fn/sym (first entity-lookup))
         ;; The function node is its authored source with its neighbours by name
         ;; (renderers read the supplied database value: owner ruling 2026-09-25).
         (let [source (render.ns/function-ai {:seon.db/db database :seon.render/value entity})]
           (is (= '(do (defn function [] true))
                  (read-string (str "(do\n" source "\n)"))))
           (is (str/includes? source "\n;; calls: none\n;; called by: none"))
           (is (= ";; entity-pairs.fixture/function"
                  (render.ns/function-ai {:seon.db/db database :seon.render/value entity :seon.render/distance 0})))))
       (when (= :seon.test/sym (first entity-lookup))
         (doseq [[facts expected] [[{} "unrun or incomplete"]
                                   [{:seon.test/pass-count 1 :seon.test/fail-count 0 :seon.test/error-count 0} "pass"]
                                   [{:seon.test/fail-count 1} "fail"]
                                   [{:seon.test/error-count 1} "error"]]]
           (let [value (merge entity facts)
                 test-name (:seon.test/sym value)
                 source (render.test/render-ai {:seon.render/value value})
                 [summary evidence changed host] (rest (read-string (str "(do\n" source "\n)")))]
             (is (every? #(not (str/starts-with? (str/triml %) ";")) (str/split-lines source))
                 "Rendered source carries no comment-prefixed prose")
             (is (= 'clojure.core/identity (first summary)) (pr-str summary))
             (is (= (str "Test " test-name ": " expected)
                    (first (str/split-lines (second summary))))
                 (pr-str summary))
             (is (= (list 'seon.test/recorded-result (list 'seon.db/db) (list 'quote test-name)) evidence)
                 (pr-str evidence))
             (is (= (list 'seon.test/changed-since-green (list 'seon.db/db) (list 'quote test-name)) changed)
                 (pr-str changed))
             (is (= (list 'seon.test/host (list 'seon.db/db) (list 'quote test-name)) host) (pr-str host))
             (is (hiccup/hiccup? (render.test/render-html {:seon.render/value value}))))))))))

(deftest function-entity-pair-is-selected-through-the-issue-walk
  (exercise-pair [:seon.fn/sym (quote entity-pairs.fixture/function)]
                 'seon.render.ns/function-ai 'seon.render.ns/function-html))

(deftest test-entity-pair-is-total-through-the-issue-walk
  (exercise-pair [:seon.test/sym (quote entity-pairs.fixture/test)]
                 'seon.render.test/render-ai 'seon.render.test/render-html))

(deftest namespace-page-renders-one-full-namespace-and-linked-references
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "namespace-page")
     (support/transacted!
      connection
      (into [(program/declaration-row
        (db/carried-projection @connection)
        {:seon.ns/name 'fixture.namespace-page
         :seon.ns/source
         "(ns fixture.namespace-page (:require [seon.id]))\n\n(def own-marker true)"
         :seon.ns/requires ['seon.id]}
        :all :agent)]
            cat
            [(support/agent-tx @connection "namespace-page-agent" 'fixture.namespace-page)]))
     (let [database (db/db connection)
           request {:seon.db/db database
                    :seon.db/connection connection
                    :seon.agent/id "namespace-page-agent"
                    :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                    :seon.render.walk/lookup
                    [:seon.agent/id "namespace-page-agent"]
                    :seon.render/output :seon.render/html
                    :seon.render/distance 2
                    :seon.render/profile
                    (render/agent-render-profile config/defaults)
                    :seon.sci.admit/caps
                    (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 5000
                    :seon.config/on-core-error :panic}
           namespace-units
           (->> (walk/neighborhood request)
                (filter #(= :seon.ns/name
                            (first (:seon.render.walk/lookup %))))
                vec)
           html-by-lookup
           (into {}
                 (map (juxt :seon.render.walk/lookup
                            (comp hiccup/->string :seon.render/output)))
                 namespace-units)
           html (vals html-by-lookup)
           page (str/join "" html)]
       (is (= #{[:seon.ns/name 'fixture.namespace-page]}
              (into #{} (map :seon.render.walk/lookup) namespace-units))
           (pr-str (mapv :seon.render.walk/lookup namespace-units)))
       (is (= 1 (count (filter #(str/includes? % "namespace source") html)))
           page)
       (is (str/includes? page "own-marker") page)
       (is (str/includes? page "href=\"/ns/seon.id\"") page)
       (is (not (str/includes? page "seon.id/id"))
           page)))))

(deftest declared-identity-and-producer-lookups-name-their-owners
  ;; Slices 0a/0b of the one render path: ownership is the identity
  ;; attribute's declared row schema over a closed key set, and an error's
  ;; alternatives are its own operation's declared returns.
  (let [projection (schema/handed-projection)
        owner #(#'render/entity-owner projection %)
        ambiguous (#'render/ambiguity 'entity-pairs.fixture :seon.render/ai
                                      ['entity-pairs.fixture/a 'entity-pairs.fixture/b])]
    (is (= :seon.agent/agent (owner {:seon.agent/id "root"})) "a partial entity is owned")
    (is (nil? (owner {:seon.agent/id "root" :seon.render/output :seon.render/ai}))
        "an envelope carrying a foreign key stays on the floor")
    (is (nil? (owner {:seon.agent/id "root" :seon.turn/id "t"})) "conflicting identities own nothing")
    (is (= :seon.error/error (owner {:seon.error/id "e" :seon.error/signature "s"})))
    (is (nil? (owner {:db/id 1})) ":db/id alone names no owner")
    (is (= #{:seon.schema/key :seon.fn/sym :seon.ns/name :seon.lint/id :seon.test/sym
             :seon.fn.file/relative-path}
           (set program/identity-attributes))
        "data identity owners do not become program families")
    (is (= [:seon.render/ambiguous-error] (#'render/error-alternatives projection ambiguous)))
    (is (nil? (#'render/error-alternatives
               projection (assoc ambiguous :seon.error/operation 'entity-pairs.fixture/none)))
        "an uncontracted operation names no alternatives")
    (let [declared #(#'render/declared-producers projection {} % :seon.render/ai)]
      (is (= ['seon.error/render-ai] (declared ambiguous)) "the satisfied alternative's pair")
      (is (= ['seon.error/render-undeclared-ai]
             (declared (dissoc ambiguous :seon.render/candidates :seon.error/data)))
          "a contracted operation's unmatched error is a loud contract finding")
      (is (= ['seon.error/render-ai]
             (declared (assoc ambiguous :seon.error/operation 'entity-pairs.fixture/none)))
          "an uncontracted operation composes the base pair"))))

(deftest an-inline-face-overrides-the-schema-default-for-just-that-face
  ;; A value's own :seon.render/ai or :seon.render/html is the highest
  ;; priority (owner, 2026-09-25): a renderer symbol or the rendered data
  ;; itself, each face independent of the other and of the schema default.
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           ctx (support/fork-cluster-ctx connection)
           agent-value {:seon.agent/id "inline-face"}
           selected (fn [value output]
                      (:seon.render.selection/selected
                       (render/selection
                        {:seon.db/db database :seon.sci.eval/ctx ctx :seon.render/value value
                         :seon.render/output output
                         :seon.render/profile (render/agent-render-profile config/defaults)
                         :seon.sci.admit/caps (config/result-caps config/defaults)
                         :seon.sci.eval/time-limit-ms 5000 :seon.config/on-core-error :panic})))
           faces (fn [value] [(selected value :seon.render/ai) (selected value :seon.render/html)])
           ai-default 'seon.cluster.agent/render-identity-ai
           html-default 'seon.cluster.agent/render-identity-html]
       (is (= [ai-default html-default] (faces agent-value)) "the schema default for both faces")
       (is (= ['seon.error/render-ai html-default]
              (faces (assoc agent-value :seon.render/ai 'seon.error/render-ai)))
           "an inline AI renderer overrides only the AI face")
       (is (= [ai-default 'seon.error/render-html]
              (faces (assoc agent-value :seon.render/html 'seon.error/render-html)))
           "an inline HTML renderer overrides only the HTML face")
       (is (= ['seon.error/render-ai 'seon.error/render-html]
              (faces (assoc agent-value :seon.render/ai 'seon.error/render-ai
                            :seon.render/html 'seon.error/render-html)))
           "both faces can be overridden")
       (let [data {:inline/summary "shown as data"}]
         (is (= [data html-default] (faces (assoc agent-value :seon.render/ai data)))
             "rendered data in place of a symbol is the AI face's selection")
         (is (str/includes? (#'render/inline-rendered {:seon.db/db database :seon.render/profile (render/agent-render-profile config/defaults)}
                                                   :seon.render/ai data)
                            "shown as data")
             "and the AI face's emitter renders that data, never a pre-printed string"))))))

(deftest the-viewing-agents-own-renderer-beats-the-schema-default
  ;; Per face the first match wins (owner, 2026-09-25): the value's own face,
  ;; then a contract-fitting render function in the VIEWING agent's
  ;; namespace, then the schema's declared pair, then the floor.
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agent-tx @connection "viewer" 'my.agents.viewer))
     (support/transacted!
      connection
      [(support/program-fn-row
        (db/db connection) 'my.agents.viewer/agent-view
        "(defn agent-view {:malli/schema [:=> [:cat [:map [:seon.agent/id :seon.agent/id]]] :seon.render/ai]} [unit] \"viewer's view\")")])
     (let [database (db/db connection)
           ctx (support/fork-cluster-ctx connection)
           selected (fn [viewer value output]
                      (:seon.render.selection/selected
                       (render/selection
                        (cond-> {:seon.db/db database :seon.sci.eval/ctx ctx :seon.render/value value
                                 :seon.render/output output
                                 :seon.render/profile (render/agent-render-profile config/defaults)
                                 :seon.sci.admit/caps (config/result-caps config/defaults)
                                 :seon.sci.eval/time-limit-ms 5000 :seon.config/on-core-error :panic}
                          viewer (assoc :seon.agent/id viewer)))))
           agent-value {:seon.agent/id "someone"}]
       (is (= 'seon.cluster.agent/render-identity-ai (selected nil agent-value :seon.render/ai))
           "with no viewing agent the schema default renders")
       (is (= 'my.agents.viewer/agent-view (selected "viewer" agent-value :seon.render/ai))
           "the viewing agent's own contract-fitting renderer beats the schema default")
       (is (= 'seon.cluster.agent/render-identity-html (selected "viewer" agent-value :seon.render/html))
           "only for the face it returns")
       (is (= 'seon.error/render-ai
              (selected "viewer" (assoc agent-value :seon.render/ai 'seon.error/render-ai) :seon.render/ai))
           "and the value's own face beats both")))))

(deftest a-declared-database-input-is-supplied-and-a-callers-is-kept
  ;; Inputs are the contract's (owner, 2026-09-25): a render function taking
  ;; :seon.render/unit declares :seon.db/db; omitted, call preparation supplies
  ;; the request's own database; a value that carries one keeps it.
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agent-tx @connection "db-viewer" 'my.agents.db-viewer))
     (let [earlier (db/db connection)]
       (support/transacted!
        connection
        [(support/program-fn-row
          (db/db connection) 'my.agents.db-viewer/probe-view
          "(defn probe-view {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/ai]} [unit] (str \"db:\" (some-> (:seon.db/db unit) :max-tx)))")])
       (let [database (db/db connection)
             ctx (support/fork-cluster-ctx connection)
             rendered (fn [value]
                        (render/render-ai
                         {:seon.db/db database :seon.sci.eval/ctx ctx :seon.render/value value
                          :seon.agent/id "db-viewer"
                          :seon.render/profile (render/agent-render-profile config/defaults)
                          :seon.sci.admit/caps (config/result-caps config/defaults)
                          :seon.sci.eval/time-limit-ms 5000 :seon.config/on-core-error :panic}))]
         (is (= (str "db:" (:max-tx database)) (rendered {:probe/label "x"}))
             "an omitted declared database is the request's own, supplied")
         (is (= (str "db:" (:max-tx earlier)) (rendered {:probe/label "x" :seon.db/db earlier}))
             "a database the caller's value carries is never replaced"))))))
