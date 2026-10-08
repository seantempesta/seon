(ns seon.render.web-debug-test
  "Schema-authored block metadata and honest unavailable dependencies."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.walk]
            [seon.render.web :as web]
            [seon.render.web-test :as web-test]
            [seon.db :as db]
            [seon.config :as config]
            [seon.error :as error]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as support]))

(defn- element-text [node]
  (cond
    (string? node) node
    (vector? node) (apply str (map element-text (drop (if (map? (second node)) 2 1) node)))
    (sequential? node) (apply str (map element-text node))
    :else ""))

(deftest the-agent-page-shows-a-comment-as-thinking-and-a-result-through-its-pair
  ;; S11 acceptance (c). The comment is a separately stored fact
  ;; (`:seon.cluster.eval/comment`), so the evaluation schema's declared HTML
  ;; pair gives it its own block while the AI pair keeps it inside the REPL
  ;; grammar. This asserts the PAGE path: the pair is SELECTED by
  ;; `render/render-call`, not called directly.
  (support/with-database
   (fn [connection]
     (let [written
           (support/transacted!
            connection
            (into
             [(support/namespace-entity @connection 'my.agents.thinking-probe)]
             cat
             [(support/agent-tx @connection "thinking-probe" 'my.agents.thinking-probe)
              [{:db/id "thinking-turn" :seon.turn/id "thinking-probe-turn"
              :seon.turn/agent [:seon.agent/id "thinking-probe"]
              :seon.turn/opened-tx "datomic.tx"}
             {:seon.cluster.eval/id "thinking-probe-evaluation"
              :seon.cluster.eval/at (java.util.Date.)
              :seon.cluster.eval/run [:seon.turn/id "thinking-probe-turn"]
              :seon.cluster.eval/ordinal 0
              :seon.cluster.eval/ns [:seon.ns/name 'my.agents.thinking-probe]
              :seon.cluster.eval/comment ";; I should read the plan first."
              :seon.cluster.eval/source "(my.task/steps {})"
              :seon.eval/shown "The plan's shown text."}]]))
           _ (is (:db-after written))
           database @connection
           lookup [:seon.cluster.eval/id "thinking-probe-evaluation"]
           saved (db/pull database '[*] lookup)
           request {:seon.db/db database
                    :seon.db/connection connection
                    :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 5000
                    :seon.render/value saved
                    :seon.render.call/id [lookup]}
           html (render/render-call
                 (assoc request :seon.render/output :seon.render/html))
           ai (render/render-call
               (assoc request :seon.render/output :seon.render/ai))
           blocks (filter vector? (tree-seq vector? seq html))
           classed (fn [css-class]
                     (filter #(= css-class (:class (second %))) blocks))]
       (is (vector? html))
       (is (= 1 (count (classed "seon-eval-thinking"))))
       (is (= ";; I should read the plan first."
              (last (first (classed "seon-eval-thinking")))))
       (is (= 1 (count (classed "seon-eval-prompt"))))
       (is (= "my.agents.thinking-probe=> (my.task/steps {})"
              (last (first (classed "seon-eval-prompt"))))
           "the prompt block no longer carries the comment")
       (is (str/includes? (element-text html) "The plan's shown text.")
           "the result renders through the evaluation schema's HTML pair")
       (is (str/starts-with?
            ai
            "my.agents.thinking-probe=> ;; I should read the plan first.\n(my.task/steps {})")
           "the AI pair keeps the REPL grammar unchanged")
       (doseq [namespace-ref [(get-in saved [:seon.cluster.eval/ns :db/id])
                             [:seon.ns/name 'my.agents.thinking-probe]]]
         (let [rendered (render/render-call
                         (assoc request :seon.render/output :seon.render/html
                                :seon.render/value
                                (assoc saved :seon.cluster.eval/ns namespace-ref)))
               blocks (filter vector? (tree-seq vector? seq rendered))]
           (is (= 1 (count (filter #(= "seon-eval-thinking" (:class (second %)))
                                   blocks))))
           (is (str/includes? (element-text rendered)
                              "my.agents.thinking-probe=> (my.task/steps {})")
               "namespace references resolve through the carried database")))))))

(deftest blocks-use-the-values-schema-documentation
  (support/with-database
    (fn [connection]
      (support/transacted! connection (support/schema-entities @connection
                        [[::title [:string {:title "Notebook title"}]]
                         [::notebook [:map {:seon.db/attributes true
                                            :title "Notebook"
                                            :description "Notes for this concern."}
                                      [::title ::title]]]]))
      (let [database @connection
            projection (schema/projection-from-database database)
            metadata (#'web/block-metadata projection database
                                            {::title "Example"} ::unlabelled nil)]
        (is (= ::notebook (:seon.schema/key metadata)))
        (is (= "Notebook" (:seon.render.web/block-title metadata)))
        (is (= "Notes for this concern."
               (:seon.render.web/block-description metadata)))
        (doseq [value [nil 42 "text" [{::title "Example"}]]]
          (is (map? (#'web/block-metadata projection database value ::unlabelled nil))
              "A scalar, absent attribute, or collection never enters entity transacting."))))))

(deftest every-declared-attribute-has-an-ordered-pair-even-when-absent
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/schema-entities @connection
                       [[::name :string] [::notes :string]
                        [::record [:map {:seon.db/attributes true}
                                   [::name ::name]
                                   [::notes {:optional true} ::notes]]]]))
     (let [database @connection
           projection (schema/projection-from-database database)
           declared (#'web/declared-entity-units projection database {::name "Example"})
           html (#'web/debug-found-values-html
                 projection {:seon.db/db database} {} {} declared
                 {:seon.render.data/incoming {:seon.render.data/complete? true}}
                 {} {})
           nodes (tree-seq coll? seq html)
           units (keep #(when (and (vector? %) (= :article (first %)))
                          (:data-seon-unit (second %))) nodes)
           headings (filter #(and (vector? %) (= :h4 (first %))) nodes)]
       (is (= [::name ::notes] declared))
       (is (= (mapv str declared) (vec units)))
       (is (= [[ :h4 "AI"] [:h4 "HTML"] [:h4 "AI"] [:h4 "HTML"]]
              (filterv #(#{"AI" "HTML"} (second %)) headings)))))))

(deftest reverse-blocks-do-not-depend-on-the-graph-page
  (support/with-database
   {::support/extra-schema
    [{:db/ident ::id :db/valueType :db.type/string :db/cardinality :db.cardinality/one
      :db/unique :db.unique/identity}
     {:db/ident ::left :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
     {:db/ident ::right :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
     {:db/ident ::hidden :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}]}
   (fn [connection]
     (support/transacted! connection (support/schema-entities @connection
                       [[::declared-concerns [:map {:seon.db/attributes true :seon.program/partition :seon.data
                                                    :seon.render/units [::_left ::_right]}
                                              [:seon.agent/id :seon.agent/id]]]]))
     (support/transacted! connection
                          (into (support/agent-tx @connection "relationships")
                            ;; Each relationship entity has an identity: the writer
                            ;; refuses an identity-less entity with no owner.
                            [{::id "left" ::left [:seon.agent/id "relationships"]}
                             {::id "right" ::right [:seon.agent/id "relationships"]}
                             {::id "hidden" ::hidden [:seon.agent/id "relationships"]}]))
     (let [database @connection
           projection (schema/projection-from-database database)
           result (schema/call-with-projection
                   projection
                   #(#'web/acquire-debug-data
                     projection database
                     {:seon.render.debug/subject [:seon.agent/id "relationships"]
                      :seon.render.data/limit 1
                      :seon.render.data/max-ref-attributes 1
                      :seon.render.data/max-result-weight 4000
                      :seon.render.web/pull-max-work 4000}
                     {}))
           output (:seon.render.call/output (:seon.render.web/debug-data-entry result))]
       (is (seq (get-in output [:seon.render.web/reverse-values ::_left])))
       (is (seq (get-in output [:seon.render.web/reverse-values ::_right])))
       (is (nil? (get-in output [:seon.render.web/reverse-values ::_hidden]))
           "an installed reverse ref is not automatically an entity concern")))))


(deftest reverse-declarations-receive-the-actual-relationship-value
  (support/with-database
   (fn [connection]
     ;; The cluster is seeded through its production path: a bare
     ;; {:seon.cluster/name …} entity is refused by write admission, and
     ;; discarding that refusal is what left this fixture inert.
     (support/seed-cluster! connection "reverse-render-fixture")
     (let [effective config/defaults
           caps (config/result-caps effective)
           ctx (support/fork-cluster-ctx connection)
           fault (error/normalize
                  {:seon.schema/projection (db/carried-projection @connection)
                   :seon.error/declared-schema :seon.render/request-error
                   :seon.error/source {:seon.error/at (java.util.Date. 0) :seon.error/layer :seon.render.web-debug-test/fixture :seon.error/operation 'seon.render.web-debug-test/reverse-declarations-receive-the-actual-relationship-value :seon.render/refused-member :seon.render/output
                                       :seon.error/message "A declared fault card."}
                   :seon.error/id "reverse-render-fixture"
                   :seon.error/at (java.util.Date.)
                   :seon.error/process "reverse-render-fixture"
                   :seon.sci.admit/caps caps
                   :seon.config.error/max-evidence-bytes
                   (:seon.config.error/max-evidence-bytes effective)})
           experiment (#'web/selected-unit-experiment
                       {:seon.db/db @connection
                        :seon.db/connection connection
                        :seon.sci.eval/ctx ctx
                        :seon.sci.admit/caps caps
                        :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                        :seon.render/profile (render/agent-render-profile effective)
                        :seon.render/captured-calls (atom {})
                        :seon.render/captured-invocations (atom {})}
                       :seon.error/_agent :seon.render/html [fault]
                       [:seon.error/id "reverse-render-fixture"]
                       {:seon.render.data/path [] :seon.render.data/offset 0})
           preview (get-in experiment [:seon.render/previews 'seon.error/render-faults-html])]
       (is (some? preview))
       (is (str/includes? (.text (web-test/dom (hiccup/->string preview))) "A declared fault card.")
           "the reverse unit renders the actual related fault")))))

(deftest debug-links-omit-defaults-and-round-trip-every-override
  (let [subject [:seon.agent/id "juniper"]
        viewer 'my.agents.juniper]
    (doseq [query [{} {"details" "true" "output" ":seon.render/ai"
                      "limit" "17" "maxWork" "31" "offset" "3"
                      "path" "[:seon.agent/settings]"}]]
      (let [request (#'web/debug-query query subject viewer "juniper")
            url (#'web/debug-page-url request {})
            parameters (#'web/query-params
                        {:query-string (.getRawQuery (java.net.URI. url))})]
        (is (= request (#'web/debug-query parameters subject viewer "juniper")))
        (is (not-any? #{"maxRefAttributes" "viewer"} (keys (:query (web-test/href url))))
            "a default or implied parameter is omitted from the link")))))

(deftest agent-identity-groups-scalars-and-keeps-declared-components
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "identity-blocks")
     (support/transacted! connection (support/agent-tx @connection "identity-blocks"))
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           projection (schema/projection-from-database database)
           effective config/defaults
           request {:seon.db/db database
                    :seon.db/connection connection
                    :seon.sci.eval/ctx ctx
                    :seon.sci.admit/caps (config/result-caps effective)
                    :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                    :seon.render/profile (render/agent-render-profile effective)
                    :seon.render/captured-calls (atom {})
                    :seon.render/captured-invocations (atom {})}
           ;; The stored agent entity: `:seon.agent/agent` requires its branch.
           entity (select-keys (db/pull database '[*] [:seon.agent/id "identity-blocks"])
                               [:seon.agent/id :seon.agent/branch])
           declared (#'web/declared-entity-units projection database entity)
           html (#'web/debug-found-values-html
                 projection request
                 {:seon.render.debug/subject [:seon.agent/id "identity-blocks"]}
                 entity declared
                 {:seon.render.data/incoming {:seon.render.data/complete? true}}
                 {} {})
           units (into []
                       (keep #(when (and (vector? %) (= :article (first %)))
                                (:data-seon-unit (second %))))
                       (tree-seq coll? seq html))]
       (is (= [":seon.agent/agent" ":seon.agent/settings" ":seon.agent/runtime"] units))
       (is (str/includes? (pr-str html) "seon.cluster.agent/render-identity-ai"))
       (is (str/includes? (pr-str html) "seon.cluster.agent/render-identity-html"))))))

(deftest a-refused-selection-is-not-reported-as-an-empty-render
  ;; the preview renders against the page's database (a6ec14a8c)
  (support/with-database
   (fn [connection]
     (let [refusal {:seon.error/at (java.util.Date. 0) :seon.error/layer :seon.render.web-debug-test/fixture :seon.error/operation 'seon.render.web-debug-test/a-refused-selection-is-not-reported-as-an-empty-render :seon.render/refused-member :seon.render/output
                    :seon.error/message "The render profile is unavailable."}
           html (#'web/experiment-preview-html
                 :seon.render/html
                 {:seon.render/selection {:seon.render.selection/selected refusal}}
                 (db/db connection))]
       (is (str/includes? (pr-str html) "The render profile is unavailable."))
       (is (not (str/includes? (pr-str html) "No render function produced")))))))

(deftest block-reference-headers-link-identities-and-omit-unidentified-entities
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/schema-entities @connection
                                                          ;; Identity is the declaring attribute's own
                                                          ;; property: an alias of :seon.agent/id is
                                                          ;; not an upsert key (26f1f40d6).
                                                          [[::probe-identity [:string {:min 1 :seon.db/identity true}]]]))
     (let [installed (db/transact!
                      connection
                      [(schema.datahike/malli->datahike-attr-in
                        (schema/projection-from-database @connection)
                        ::probe-identity)])
           _ (is (:db-after installed))
           written (db/transact! connection
                                 (into (support/agent-tx @connection "reference-agent")
                                   [{::probe-identity "aliased-reference"}
                                    ;; An identity-less entity exists only as a
                                    ;; component of its one owner (the writer
                                    ;; refuses an unowned one).
                                    (assoc (support/task-row "referenceuni" "Unidentified step owner")
                                           :seon.task/steps [{:db/id "unidentified" :seon.step/position 0
                                                              :seon.step/text "No identity" :seon.step/status :open}])]))
           _ (is (:db-after written))
           database @connection
           projection (schema/projection-from-database database)
           agent-ref (db/pull database '[:db/id] [:seon.agent/id "reference-agent"])
           probe-ref (db/pull database '[:db/id] [::probe-identity "aliased-reference"])
           unidentified {:db/id (get-in written [:tempids "unidentified"])}
           plan-ref (db/pull database '[:db/id] [:seon.config/agent (:db/id agent-ref)])
           effective config/defaults
           request {:seon.db/db database
                    :seon.db/connection connection
                    :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                    :seon.sci.admit/caps (config/result-caps effective)
                    :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                    :seon.render/profile (render/agent-render-profile effective)
                    :seon.render/captured-calls (atom {})
                    :seon.render/captured-invocations (atom {})}
           debug-request {:seon.render.debug/subject [:seon.agent/id "reference-agent"]
                          :seon.render.debug/viewer-namespace 'my.agents.reference-agent}]
       (is (integer? (:db/id agent-ref)))
       (is (integer? (:db/id unidentified)))
       (is (= [[[::probe-identity "aliased-reference"] "aliased-reference"]]
              (#'web/referenced-identities database [probe-ref])))
       (is (= [[[:seon.config/agent (:db/id agent-ref)] "reference-agent"]]
              (#'web/referenced-identities database plan-ref)))
       (is (= plan-ref
              (db/pull database '[:db/id] [:seon.config/agent (:db/id agent-ref)])))
       (doseq [[references expected] [[[agent-ref unidentified agent-ref] ["reference-agent"]]
                                    [[agent-ref plan-ref] ["reference-agent" "reference-agent"]]
                                    [[unidentified] []]]]
         (let [html (#'web/debug-found-value
                     projection request debug-request ::references references true
                     {})
               header (nth html 2)
               nodes (tree-seq coll? seq header)
               links (filter #(and (vector? %) (= :a (first %))) nodes)
               text (str/join " " (filter string? (tree-seq sequential? seq header)))]
           (is (= expected (mapv #(second (nth % 2)) links)))
           (is (not (str/includes? text (str (:db/id agent-ref)))))
           (is (not (str/includes? text (str (:db/id unidentified)))))
           (is (not (str/includes? text ":db/id")))
           (is (not (str/includes? text "referenced entities")))
           (when-let [link (first links)]
             (let [parameters (#'web/query-params
                               {:query-string (.getRawQuery
                                               (java.net.URI. (:href (second link))))})]
               (is (= (pr-str [:seon.agent/id "reference-agent"])
                      (get parameters "subject")))))
           (when (empty? expected)
             (is (not-any? #(and (map? %)
                                (= "seon-debug-stored-value" (:class %))) nodes)))))))))
