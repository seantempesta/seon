(ns seon.render.value-test
  "The render floor is one adapter over the sealed print emitter."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.registry :as mr]
            [seon.ai.tokens :as tokens]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.error :as error]
            [seon.instrument :as instrument]
            [sci.core :as sci]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.print :as print]
            [seon.plan :as plan]
            [seon.repl :as repl]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(def ^:private caps
  (config/result-caps config/defaults))

(defn- unit
  [raw]
  {:seon.agent/id "root"
   :seon.render.call/id [:seon.render.value-test/floor]
   :seon.render/value raw
   :seon.sci.admit/caps caps})

(defn- registered-unit
  [connection raw]
  (assoc (unit raw)
         :seon.db/db (db/db connection)
         :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
         :seon.render.value/root [:seon.render.value-test/registered raw]))

(defn- render-request
  [connection raw]
  (assoc (registered-unit connection raw)
         :seon.sci.eval/time-limit-ms 2000
         :seon.config/on-core-error :panic))

(deftest block-pairs-remain-explicit
  (support/with-database
   (fn [connection]
     (let [report (db/transact! connection
                                [{:my.plan.item/id "prepare"
                                  :my.plan.item/title "Prepare input"
                                  :my.plan.item/done-when "Input is available"}
                                 {:my.plan.item/id "check"
                                  :my.plan.item/title "Check output"}])
           database (db/db connection)
           directory (evaluation/directory-value database 'seon.repl true)
           step (plan/item {:seon.db/db database :my.plan.item/id "prepare"})
           _ (support/transacted! connection (support/agent-tx @connection "plan-reader"))
           installed (plan/plan!
                      {:my.plan/objective "Verify the render"
                       :my.plan/current-step {:my.plan.item/id "focus"}
                       :my.plan/steps [{:my.plan.item/id "focus" :my.plan.item/title "Current task"
                                        :my.plan.item/done-when "Current criterion"}
                                       {:my.plan.item/id "later" :my.plan.item/title "Later task"
                                        :my.plan.item/done-when "Later criterion"}
                                       {:my.plan.item/id "finished" :my.plan.item/title "Finished task"
                                        :my.plan.item/done-when "Finished criterion"}]}
                      (db/db connection) connection "plan-reader")
           completed (plan/complete! "finished" connection "plan-reader")
           whole-plan (plan/plan {:seon.db/db (db/db connection) :seon.agent/id "plan-reader"})
           request (assoc (render-request connection nil)
                          :seon.render/profile
                          {:seon.render.profile/id :seon.render.profile/test
                           :seon.render.profile/token-budget 20000
                           :seon.render.profile/max-depth 3
                           :seon.render.profile/max-children 256
                           :seon.render.profile/composition :single-line})]
       (is (seq (:tx-data report)) (pr-str report))
       (is (map? installed) (pr-str installed))
       (is (map? completed) (pr-str completed))
       (let [shown-plan (edn/read-string (plan/format-plan-ai whole-plan))]
         (is (str/includes? (:seon.plan/current-line shown-plan) "Current criterion"))
         (is (= #{"later" "finished"} (set (keys (:seon.plan/step-lines shown-plan)))))
         (is (not (str/includes? (pr-str shown-plan) "Later criterion")))
         (is (not (str/includes? (pr-str shown-plan) "Finished criterion")))
         (is (str/includes? (get-in shown-plan [:seon.plan/step-lines "finished"])
                            "completed — Finished task — "))
         (is (= 'my.plan/current! (first (edn/read-string (:seon.plan/update-example shown-plan))))))
       (is (seq (:functions directory)))
       (is (str/starts-with? (db/render-transaction-ai report)
                             "Wrote 5 facts on 2 entities:"))
       (doseq [[raw render-pair] [[report db/render-transaction-ai]
                                 [directory repl/render-directory-ai]
                                 [step plan/render-item-ai]
                                 [whole-plan plan/format-plan-ai]]]
         (is (= (render-pair raw)
                (render/render-ai (assoc request :seon.render/value raw)))))))))

(deftest explicit-structural-results-retain-attributes-through-real-evaluation
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "render-results")
     (is (= 'seon.shell.jvm/run
            (:seon.effect/capability
             (db/pull (db/db connection) '[:seon.effect/capability]
                      [:seon.fn/sym 'my.shell/run!]))))
     (let [written (support/transacted! connection
                     (into (agent/creation-tx
                            {:seon.agent/id "render-results"
                             :seon.ns/name 'my.agents.render-results
                             :seon.cluster/name "render-results"})
                       [{:seon.turn/id "render-results-turn"
                         :seon.turn/agent [:seon.agent/id "render-results"]
                         :seon.turn/opened-tx (db/basis-t (db/db connection))
                         :seon.turn/reply (.repeat "turn detail " 1000)}
                        {:seon.effect/id "render-results-effect"
                         :seon.effect/run [:seon.turn/id "render-results-turn"]
                         :seon.effect/owner [:seon.fn/sym 'my.shell/run!]
                         :seon.effect/capability 'seon.shell.jvm/run
                         :seon.effect/form-ordinal 0
                         :seon.effect/ordinal 0
                         :seon.effect/opened-at (java.util.Date. 0)
                         :seon.effect/request-edn "{}"
                         :seon.effect/result-edn (.repeat "payload " 1000)
                         :seon.effect/duration-ms 12}]))
           database (db/db connection)
           ctx (support/fork-cluster-ctx connection "render-results")
           configuration (support/effective-config)
           profile (assoc (render/agent-render-profile configuration)
                          :seon.render.profile/max-children 32
                          :seon.render.profile/max-string-length 128)
           handle 'result/e0123456789ab]
       (is (:db-after written) (pr-str written))
       (doseq [lookup [[:seon.fn/sym 'seon.db/q]
                       [:seon.config/cluster "render-results"]
                       [:seon.turn/id "render-results-turn"]
                       [:seon.effect/id "render-results-effect"]]]
         (let [raw (db/pull database '[*] lookup)
               result (evaluation/evaluate
                        {:seon.cluster.eval/source (pr-str (list 'seon.db/pull (list 'quote '[*])
                                                                 (list 'quote lookup)))
                         :seon.sci.eval/ctx ctx :seon.db/db database
                         :seon.render/profile profile :seon.repl/handle handle
                         :seon.render.value/options {:seon.render.value/structural? true}
                         :seon.sci.admit/caps (config/result-caps configuration)
                         :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)
                         :seon.config/on-core-error :panic})
               shown (:seon.eval/shown result)
               parsed (edn/read-string shown)
               cuts (filter #(and (map? %) (:seon.print/omitted %))
                            (tree-seq coll? seq parsed))]
           (is (= (second lookup) (get raw (first lookup))) (pr-str lookup raw))
           (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
           (is (= raw (:seon.sci.admit/value result)))
           (is (map? parsed) shown)
           (is (<= (tokens/estimate shown) (:seon.render.profile/token-budget profile)) shown)
           (is (seq (dissoc parsed :seon.print/elision)) shown)
           (is (every? (set (keys raw)) (keys (dissoc parsed :seon.print/elision))) shown)
           (when (#{:seon.turn/id :seon.effect/id} (first lookup))
             (is (seq cuts) shown))
           (evaluation/bind-result! ctx handle raw)
           (doseq [cut cuts]
             (let [path (:seon.render.data/path cut)
                   original (print/value-at raw path)]
               (is (= (count original) (:seon.render.data/total cut)))
               (is (= (- (count original) (:seon.render.data/next-offset cut))
                      (:seon.print/omitted cut)))
               (is (= original (sci/eval-form ctx (:seon.print/requery-form cut))))))
           (let [prepared (value/prepare
                            (assoc (render-request connection raw)
                                   :seon.render.value/options {:seon.render.value/structural? true}
                                   :seon.render/profile profile :seon.repl/handle handle))]
             (is (= (value/render-ai-data prepared)
                    (value/render-ai-data (assoc prepared :seon.render.value/truncated? true))))
             (is (not (str/includes? (pr-str (:seon.render.value/html prepared))
                                    "seon-data-capped"))))))))))

(deftest a-pulled-function-row-is-its-attributes-not-steering-prose
  ;; THE CLASS: a one-attribute finding row may not declare a render pair
  ;; over another family's identity attribute. `:seon.problems/stale-var`
  ;; is `[:map [:seon.fn/sym :seon.fn/sym]]` and maps are OPEN, so the pair
  ;; it declared was selected for EVERY function row an agent pulled and
  ;; the whole published program graph answered "Restart the JVM to remove
  ;; stale loaded Var ...". The structural twin above cannot see it: that
  ;; one passes `:seon.render.value/structural? true`, which is precisely
  ;; the option an agent's own evaluation never sets. So this asserts the
  ;; agent's real path, and then the derivation behind it — no shape a bare
  ;; `:seon.fn` row matches may carry an AI pair at all.
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "fn-row")
     (let [database (db/db connection)
           ctx (support/fork-cluster-ctx connection "fn-row")
           configuration (support/effective-config)
           pattern [:seon.fn/sym :seon.fn/private?]
           lookup [:seon.fn/sym 'seon.db/q]
           raw (db/pull database pattern lookup)
           result (evaluation/evaluate
                    {:seon.cluster.eval/source
                     (pr-str (list 'seon.db/pull (list 'quote pattern) (list 'quote lookup)))
                     :seon.sci.eval/ctx ctx :seon.db/db database
                     :seon.render/profile (render/agent-render-profile configuration)
                     :seon.sci.admit/caps (config/result-caps configuration)
                     :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)
                     :seon.config/on-core-error :panic})
           shown (:seon.eval/shown result)]
       (is (= 'seon.db/q (:seon.fn/sym raw)) (pr-str lookup raw))
       (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
       (is (= raw (:seon.sci.admit/value result)))
       (is (= raw (edn/read-string shown)) shown)
       (is (not (str/includes? shown "Restart the JVM")) shown)
       (is (empty? (filter :seon.render/ai
                           (schema/matching-shapes-in
                            (db/carried-projection database) raw)))
           "a bare :seon.fn row must match no shape declaring an AI pair")))))

(deftest background-poll-keeps-identity-while-payloads-grow
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "poll-render")
     (is (= 'seon.shell.jvm/run
            (:seon.effect/capability
             (db/pull (db/db connection) '[:seon.effect/capability]
                      [:seon.fn/sym 'my.shell/run!]))))
     (let [configuration (support/effective-config)
           profile (assoc (render/agent-render-profile configuration)
                          :seon.render.profile/max-string-length 128)
           observations
           (mapv
            (fn [size]
              (let [payload (.repeat "x" size)
                    written (support/transacted! connection
                              (into (agent/creation-tx
                                     {:seon.agent/id "poll-render"
                                      :seon.ns/name 'my.agents.poll-render
                                      :seon.cluster/name "poll-render"})
                                [{:seon.turn/id "poll-render-turn"
                                  :seon.turn/agent [:seon.agent/id "poll-render"]
                                  :seon.turn/opened-tx (db/basis-t (db/db connection))}
                                 {:seon.effect/id "poll-render-effect"
                                  :seon.effect/run [:seon.turn/id "poll-render-turn"]
                                  :seon.effect/owner [:seon.fn/sym 'my.shell/run!]
                                  :seon.effect/capability 'seon.shell.jvm/run
                                  :seon.effect/form-ordinal 0 :seon.effect/ordinal 0
                                  :seon.effect/opened-at (java.util.Date. 0)
                                  :seon.effect/request-edn "{}"
                                  :seon.effect/result-edn payload
                                  :seon.effect/duration-ms 12}]))
                    ctx (support/fork-cluster-ctx connection "poll-render")
                    result (evaluation/evaluate
                             {:seon.cluster.eval/source
                              "(my.background/poll {:my.background/result [:seon.effect/id \"poll-render-effect\"]})"
                              :seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                              :seon.render/profile profile
                              :seon.repl/handle 'result/e0123456789ab
                              :seon.sci.admit/caps (config/result-caps configuration)
                              :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)
                              :seon.config/on-core-error :panic})
                    shown (:seon.eval/shown result)
                    parsed (edn/read-string shown)]
                (is (:db-after written) (pr-str written))
                (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
                (is (= "poll-render-effect" (:seon.effect/id parsed)) shown)
                (is (= 12 (:seon.effect/duration-ms parsed)))
                (let [cut (get parsed :seon.effect/result-edn)]
                  (is (= size (:seon.render.data/total cut)) shown)
                  (is (= (- size (:seon.render.data/next-offset cut))
                         (:seon.print/omitted cut))
                      shown))
                (is (= payload (get-in result [:seon.sci.admit/value :seon.effect/result-edn])))
                (is (< (tokens/estimate shown) (:seon.render.profile/token-budget profile)))
                (tokens/estimate shown)))
            [8000 80000])]
       (is (< (abs (- (first observations) (second observations))) 16))))))

(defn- lexical-hiccup-text
  [form]
  (cond
    (string? form) form
    (sequential? form)
    (let [[tag & body] form
          body (if (map? (first body)) (next body) body)]
      (if (contains? #{:summary :nav} tag)
        ""
        (apply str (map lexical-hiccup-text body))))
    :else ""))

(deftest ordinary-values-use-one-readable-structural-printer
  (let [raw {:unregistered/value 1 :unregistered/detail [2 3]}
        projection (value/prepare (unit raw))]
    (is (= raw (edn/read-string (value/render-ai-data projection))))
    (is (= "#:unregistered{:detail [2 3], :value 1}"
           (value/render-ai-data projection)))
    (is (str/includes? (hiccup/->string (value/render-html (unit raw)))
                       "seon-print-map"))))

(deftest settings-remain-data-when-block-and-problem-renderers-match
  (support/with-database
   (fn [connection]
     (let [groups [{:seon.config.ai/model "deepseek/deepseek-v4-flash-20260731"
                    :seon.config.ai/no-provider true}
                   {:seon.config.ai.retry/maximum-retries 2}
                   {:my.agent/turns-left 20}]
           request (render-request connection groups)]
       (doseq [raw [groups (first groups)]]
         (let [shown (value/render-ai (assoc request :seon.render/value raw))]
           (is (= raw (edn/read-string shown)))
           (is (not (str/includes? shown "seon.render/ambiguous")))))))))

(deftest ai-values-never-wrap-at-a-display-width
  (let [raw [{:example/title (apply str (repeat 180 "x"))
              :example/count 2 :example/nested {:example/value [1 2 3]}}]
        request (assoc (unit raw) :seon.print/options {:seon.print/width 10})
        shown (value/render-ai request)]
    (is (= raw (edn/read-string shown)))
    (is (not (str/includes? shown "\n")))
    (is (str/includes? (hiccup/->string (value/render-html request))
                       (apply str (repeat 180 "x"))))))

(deftest collection-cardinality-never-changes-values-into-text-tables
  (doseq [n [0 1 2 3]
          choice [:derived true false]]
    (let [rows (mapv (fn [i] {:my.message/id (str i)
                             :my.message/content "Read this message."}) (range n))
          projection (value/prepare
                      (assoc (unit rows) :seon.print/options {:seon.print/table? choice}))
          shown (value/render-ai-data projection)]
      (is (= rows (edn/read-string shown)) shown)
      (is (false? (get-in projection [:seon.render.value/options :seon.print/table?]))))))

(deftest component-members-render-in-declared-position-order
  (support/with-database
   (fn [connection]
     (let [rows [{:my.plan.item/id "order/a" :my.plan.item/position 2}
                 {:my.plan.item/id "order/z" :my.plan.item/position 1}]
           render-rows (fn [members]
                         (edn/read-string
                          (value/render-ai
                           (assoc (unit {:my.plan/steps members})
                                  :seon.db/db (db/db connection)))))]
       (is (= (vec (reverse rows)) (:my.plan/steps (render-rows rows))))
       (is (= (set rows) (:my.plan/steps (render-rows (set rows)))))
       (let [shown (value/render-ai (assoc (unit {:my.plan/steps (set rows)})
                                          :seon.db/db (db/db connection)))]
         (is (< (str/index-of shown "order/z") (str/index-of shown "order/a"))))
       (let [unordered [(first rows) (dissoc (second rows) :my.plan.item/position)]]
         (is (= unordered (:my.plan/steps (render-rows unordered)))))
       (let [visited? (atom false)
             uncounted (lazy-cat rows (lazy-seq (reset! visited? true)
                                                (throw (ex-info "omitted tail" {}))))
             profile (assoc (render/agent-render-profile (support/effective-config))
                            :seon.render.profile/max-children 1)
             shown (value/render-ai (assoc (unit {:my.plan/steps uncounted})
                                          :seon.db/db (db/db connection)
                                          :seon.render/profile profile))]
         (is (string? shown))
         (is (false? @visited?) "ordering does not realize an uncounted component tail"))
       (is (= rows
              (:fixture/rows
               (edn/read-string
                (value/render-ai
                 (assoc (unit {:fixture/rows rows}) :seon.db/db (db/db connection)))))))))))

(deftest plan-dependencies-show-ids-without-changing-the-pulled-value
  (support/with-database
   (fn [connection]
     (let [raw {:my.plan.item/needs #{{:my.plan.item/id "b"} {:my.plan.item/id "a"}}}
           shown (value/render-ai (assoc (unit raw) :seon.db/db (db/db connection)))]
       (is (= {:my.plan.item/needs ["a" "b"]} (edn/read-string shown)))
       (is (set? (:my.plan.item/needs raw)))
       (let [detailed {:my.plan.item/needs #{{:my.plan.item/id "a" :my.plan.item/title "Keep detail"}}}]
         (is (= detailed (edn/read-string (value/render-ai
                                          (assoc (unit detailed) :seon.db/db (db/db connection)))))))))))

(deftest declared-producers-still-have-absolute-precedence
  (support/with-database
   {:seon.test-support/extra-schema
    (let [forms {:fixture/declared-producer
                 [:map {:seon.render/ai 'seon.error/render-ai
                        :seon.render/html 'seon.error/render-html}
                  [:my.message/no-recipient [:= true]]
                  [:seon.error/message :string]]}
          projection (schema/build-projection
                      (merge (:seon.schema.projection/forms (schema/handed-projection))
                             forms))]
      (schema/canonical-schema-rows projection forms))}
   (fn [connection]
     (let [failure {:my.message/no-recipient true
                    :seon.error/message "A recipient is required."}
           request (render-request connection failure)
           projection (schema/projection-from-database (db/db connection))]
       (is (some? (mr/schema (:seon.schema.projection/registry projection)
                             :fixture/declared-producer)))
       (is (some #(= :fixture/declared-producer (:seon.schema/key %))
                 (schema/matching-shapes-in projection failure)))
       (is (= 'seon.error/render-ai
              (#'seon.render/producer request
               :seon.render/ai :seon.render/ai)))
       (is (= 'seon.error/render-html
              (#'seon.render/producer request
               :seon.render/html :seon.render/html)))))))

(deftest registered-floor-elisions-do-not-invent-a-result-handle
  (support/with-database
   (fn [connection]
     (let [rows (mapv (fn [ordinal]
                        {:my.message/id (str "m-" ordinal)
                         :my.message/at (java.util.Date. (* 1000 ordinal))
                         :my.message/preview (str "message " ordinal)})
                      (range 3))
           root [:my.message/inbox "root"]
           profile (assoc (render/agent-render-profile
                           (support/effective-config))
                          :seon.render.profile/id :seon.render.profile/test
                          :seon.render.profile/token-budget 1024
                          :seon.render.profile/max-depth 8
                          :seon.render.profile/max-children 1
                          :seon.render.profile/composition :single-line)
           floor-unit (assoc (registered-unit connection rows)
                             :seon.render.value/root root
                             :seon.render/profile profile)
           projection (value/prepare floor-unit)
           root-elision (last (:seon.print/items
                               (:seon.render.value/tree projection)))
           ai (value/render-ai-data projection)
           html (hiccup/->string (value/render-html-data projection))]
       (is (= :seon.print/elided (:seon.print/face root-elision)))
       (is (nil? (:seon.print/requery-id root-elision)))
       (is (= 2 (:seon.print/omitted root-elision)))
       (is (not (str/includes? ai "requery-form")))
       (is (not (str/includes? html "requery-form")))))))

(deftest a-live-list-feeds-both-render-sinks
  (let [projection
        (value/prepare
         (assoc (unit '(1 2 3))
                :seon.render.call/id [:seon.render.value-test/list]))]
    (is (= "(1 2 3)" (:seon.render.value/text projection)))
    (is (= "(1 2 3)"
           (lexical-hiccup-text
            (:seon.print/hiccup
             (print/emit-both (:seon.render.value/tree projection)
                              (:seon.render.value/options projection))))))))

(deftest anonymous-roots-refuse-instead-of-colliding
  (let [anonymous {:seon.agent/id "root"
                   :seon.render/value {:same/value 1}
                   :seon.sci.admit/caps caps}
        results [(value/render-html anonymous)
                 (value/render-html anonymous)]]
    (is (every? string? (map :seon.render.value/root-description results)))
    (is (every? #(= 'seon.render.value/node-id (:seon.error/operation %)) results))
    (is (every? #(= "A rendered value root requires a caller-supplied block id."
                    (:seon.error/message %))
                results))
    (is (not-any? vector? results)
        "no anonymous Hiccup root can carry a colliding invented id")))

(deftest caller-supplied-block-ids-are-stable-and-distinct
  (let [raw {:same/value 1}
        left (assoc (unit raw) :seon.render.call/id [:test/block-a])
        right (assoc (unit raw) :seon.render.call/id [:test/block-b])
        left-id (value/node-id left [])
        right-id (value/node-id right [])]
    (is (string? left-id))
    (is (not= left-id right-id))
    (is (= left-id (value/node-id left [])))))

(deftest value-artifact-stores-only-the-print-node-source
  (let [admitted (admit/admit
                  {:seon.sci.admit/value {:alpha [1 2 3]}
                   :seon.sci.admit/interrupt-fn (fn [])
                   :seon.sci.admit/caps caps
                   :seon.config/on-core-error :record})
        artifact (value/artifact admitted)
        stored (value/artifact-edn artifact)
        restored (value/read-artifact stored)]
    (is (contains? artifact :seon.sci.admit/print-node))
    (is (not (contains? artifact :seon.sci.admit/capped?))
        "the retired key is absent from the durable artifact")
    (is (= (:seon.sci.admit/value admitted)
           (value/artifact-value restored)))
    (is (= (:seon.sci.admit/edn admitted)
           (admit/canonical-edn (:seon.sci.admit/print-node restored))))))

(deftest profile-fit-supersedes-legacy-print-cuts-with-values
  (is (= "(1 2 3)" (value/render-ai (unit '(1 2 3)))))
  (is (not (str/includes?
       (value/render-ai
        (assoc (unit (vec (range 20)))
               :seon.print/options {:seon.print/width 20}))
       "\n"))))

(deftest rendering-live-values-does-not-apply-a-storage-bound
  (let [raw (vec (range 100))
        request (assoc (unit raw) :seon.sci.admit/caps
                       {:seon.config.eval.result/max-bytes 1})]
    (is (= 68 (:seon.print/omitted (last (edn/read-string (value/render-ai request))))))
    (is (str/includes? (hiccup/->string (value/render-html request)) ">99<"))
    (is (not (str/includes? (value/render-ai request) "over-bound")))))

(deftest elision-is-a-requeryable-structural-value
  ;; THE SECOND AND THIRD BOUNDS. The AI projection is cut by the render
  ;; profile — the one place presentation elides — and the cut is ordinary
  ;; data naming what was omitted, the bound that made it, and how to ask
  ;; again. HTML is not bounded at all: the same unit serves every item.
  (let [handle 'result/e0123456789ab
        raw (vec (range 100))
        profile (render/agent-render-profile (support/effective-config))
        kept (:seon.render.profile/max-children profile)
        result-unit (assoc (unit raw) :seon.repl/handle handle)
        projection (value/prepare result-unit)
        html (hiccup/->string (value/render-html result-unit))
        elision (last (:seon.print/items
                       (:seon.render.value/tree projection)))]
    (is (= {:seon.print/omitted (- (count raw) kept)
            :seon.render.data/total (count raw)
            :seon.render.data/path []
            :seon.render.data/next-offset kept
            :seon.print/bound-by :seon.render.profile/max-children
            :seon.render.profile/id (:seon.render.profile/id profile)
            :seon.print/requery-id handle}
           (select-keys elision
                        [:seon.print/omitted
                         :seon.render.data/total
                         :seon.render.data/path
                         :seon.render.data/next-offset
                         :seon.print/bound-by
                         :seon.render.profile/id
                         :seon.print/requery-id])))
    (let [text (:seon.render.value/text projection)]
      (let [cut (last (edn/read-string text))]
        (is (= (- (count raw) kept) (:seon.print/omitted cut)))
        (is (= :seon.render.profile/max-children (:seon.print/bound-by cut)))
        (is (= '(seon.print/value-at result/e0123456789ab [])
               (:seon.print/requery-form cut)))))
    (is (not (str/includes? html "seon-print-elision"))
        "HTML is not bounded at all — it elides nothing to requery")
    (is (str/includes? html ">99<")
        "the last item of the whole value reaches the page")))

(deftest references-stay-opaque
  ;; A reference is never entered: it contributes its class and nothing
  ;; else, so no private state leaks through a render. A value that is
  ;; ITSELF an opaque reference projects no data at all, and that answer is
  ;; the typed marker rather than an empty panel.
  (let [projection (value/prepare (unit {:reference (atom {:private/value 42})}))
        tree (:seon.render.value/tree projection)
        [_ reference] (first (:seon.print/entries tree))]
    (is (= #{:seon.print/face :seon.print/class :seon.render.data/path}
           (set (keys reference)))
        "an opaque reference carries no value or representation field")
    (is (= :seon.print/object (:seon.print/face reference)))
    (is (= "clojure.lang.Atom" (:seon.print/class reference)))
    (is (= "{:reference #object[clojure.lang.Atom]}"
           (value/render-ai-data projection)))
    (is (= "#object[clojure.lang.Atom]"
           (value/render-ai (unit (atom {:private/value 42}))))
        "a live reference is named even when no serialization exists")))

(deftest a-window-of-one-shows-one-item-not-an-empty-claim-of-more
  (let [window (value/window [:a :b :c] 0 1)]
    (is (= 1 (:seon.render.value/shown window)))
    (is (= [:a] (:seon.render.value/window window)))
    (is (true? (:seon.render.value/more? window)))))

(deftest a-window-beyond-a-counted-value-states-the-offset-and-length
  (let [window (value/window [:a :b :c] 9 2)]
    (is (= [] (:seon.render.value/window window)))
    (is (= 9 (:seon.render.value/offset window)))
    (is (= 3 (:seon.render.value/total window)))
    (is (true? (:seon.render.value/beyond-end? window)))))

(deftest realization-failure-is-visible-data
  ;; A value whose realization throws is not silence and not an exception
  ;; escaping the render: admission projected no data, so the answer is the
  ;; typed marker, in both projections. `window` — the paging owner the
  ;; stored-value route still calls — stays total over the same value and
  ;; keeps the failure's own message.
  (let [poison (fn [] (map (fn [_] (throw (ex-info "poison" {}))) [1]))
        text (value/render-ai (unit (poison)))
        html (hiccup/->string (value/render-html (unit (poison))))
        window (value/window (poison) 0 3)]
    (is (str/includes? text "poison"))
    (is (str/includes? html "poison"))
    (is (= 0 (:seon.render.value/window-offset
              (:seon.render.value/window window))))
    (is (= 'seon.render.value/window
           (:seon.error/operation (:seon.render.value/window window))))
    (is (= "poison" (:seon.error/message (:seon.render.value/window window))))
    (is (zero? (:seon.render.value/shown window)))))

(def ^:private probe-profile
  {:seon.render.profile/id :seon.render.profile/test
   :seon.render.profile/token-budget 1024
   :seon.render.profile/max-depth 4
   :seon.render.profile/max-children 3
   :seon.render.profile/composition :single-line})

(defn- probe-unit [raw]
  (assoc (unit raw)
         :seon.render/profile probe-profile
         :seon.render.value/root 'result/e0123456789ab))

(deftest generated-values-have-deterministic-readable-executable-elisions
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           handle 'result/e0123456789ab
           observed-cuts (atom 0)
           scalar (gen/one-of [gen/small-integer gen/boolean gen/ratio
                              (gen/fmap bigdec gen/small-integer)
                              (gen/double* {:infinite? false :NaN? false})
                              gen/keyword gen/symbol gen/char
                              (gen/fmap #(apply str (repeat % "whole word "))
                                        (gen/choose 0 12))])
           values (gen/recursive-gen
                   (fn [child]
                     (gen/one-of [(gen/vector child 0 8)
                                  (gen/fmap #(apply list %) (gen/vector child 0 8))
                                  (gen/set child {:max-elements 8})
                                  (gen/map (gen/one-of [scalar
                                                       (gen/elements [:sample/a :sample/b :sample/c
                                                                      :seon.print/elision])])
                                           child {:max-elements 4})])) scalar)
           result
           (tc/quick-check
            80
            (prop/for-all [raw values]
              (let [request (assoc (probe-unit raw) :seon.repl/handle handle
                                   :seon.render/profile
                                   (assoc probe-profile
                                          :seon.render.profile/token-budget 1024
                                          :seon.render.profile/max-string-length 24))
                    _ (evaluation/bind-result! ctx handle raw)
                    first-render (value/render-ai request)
                    second-render (value/render-ai request)
                    parsed (edn/read-string first-render)
                    cuts (atom [])]
                (walk/postwalk #(do (when (and (map? %) (:seon.print/omitted %))
                                      (swap! cuts conj %)) %) parsed)
                (swap! observed-cuts + (count @cuts))
                (and (= first-render second-render)
                     (or (seq @cuts) (= raw parsed))
                     (every?
                      (fn [cut]
                        (let [path (:seon.render.data/path cut)
                              expected (print/value-at raw path)
                              actual (sci/eval-form ctx (:seon.print/requery-form cut))]
                          (and (= expected actual)
                               (= (count expected) (:seon.render.data/total cut))
                               (= (- (count expected) (:seon.render.data/next-offset cut))
                                  (:seon.print/omitted cut)))))
                      @cuts))))
            :seed 20260914)]
       (is (:pass? result) (pr-str result))
       (is (pos? @observed-cuts) "the generated cases must exercise elision and requery")))))

(deftest documentation-body-is-whole-or-one-executable-elision
  (support/with-database
   (fn [connection]
     (let [documentation (evaluation/documentation-value (db/db connection) 'seon.db/read-evidence 'seon.db/read-evidence)
           body (:body documentation)
           ctx (support/fork-cluster-ctx connection)
           handle 'result/e0123456789ab
           request (assoc (probe-unit documentation)
                          :seon.repl/handle handle
                          :seon.render/profile
                          (assoc probe-profile :seon.render.profile/max-children 64
                                               :seon.render.profile/max-string-length 24))]
       (is (string? body) (pr-str documentation))
       (is (> (count body) 24) "the real documentation must exceed the string limit")
       (evaluation/bind-result! ctx handle documentation)
       (let [shown (edn/read-string (value/render-ai request))
             cut (:body shown)]
         ;; The cut keeps the characters the profile admits and counts only
         ;; the remainder; it no longer omits the whole body.
         (is (= 24 (:seon.render.data/next-offset cut)))
         (is (= (subs body 0 24) (:seon.print/prefix cut)))
         (is (= (- (count body) 24) (:seon.print/omitted cut)))
         (is (= (count body) (:seon.render.data/total cut)))
         (is (= [:body] (:seon.render.data/path cut)))
         (is (= body (sci/eval-form ctx (:seon.print/requery-form cut)))))))))

(deftest equal-unordered-values-render-identical-bytes
  (doseq [[left right]
          [[(array-map :z/value 3 :a/value 1 :m/value 2)
            (array-map :m/value 2 :a/value 1 :z/value 3)]
           [(into #{} [{:z 2 :a 1} {:z 4 :a 3}])
            (into #{} [(array-map :a 3 :z 4) (array-map :a 1 :z 2)])]]]
    (is (= left right))
    (is (= (value/render-ai (probe-unit left))
           (value/render-ai (probe-unit right))))
    (is (= (value/render-html (probe-unit left))
           (value/render-html (probe-unit right))))))

(deftest ai-never-visits-the-omitted-tail
  (let [visits (atom [])
        raw (map (fn [i] (swap! visits conj i) i) (iterate inc 0))
        text (value/render-ai (probe-unit raw))]
    (is (str/includes? text "0 1 2"))
    (is (= :seon.render.profile/max-children
           (:seon.print/bound-by (last (edn/read-string text)))))
    (is (<= (count @visits) 4)
        "only the retained children and one lookahead are realized")))

(deftest strings-are-quoted-and-their-cut-path-is-associative
  (let [raw "a\n\"b\\c\t"
        text (value/render-ai (probe-unit raw))
        large (.repeat "x\n" 10000)
        request (probe-unit {:payload/text large})
        node (-> (value/prepare request) :seon.render.value/tree
                 :seon.print/entries first second)]
    (is (= (pr-str raw) text))
    (is (not (str/includes? text "\n")))
    (is (= [:payload/text] (:seon.render.data/path node)))
    (is (= '(seon.print/value-at result/e0123456789ab [:payload/text])
           (:seon.print/requery-form node)))
    (is (= (count large) (:seon.render.data/total node)))
    (is (pos? (:seon.print/omitted node)))
    (is (= (pr-str large)
           (lexical-hiccup-text (value/render-html (probe-unit large)))))))

(deftest depth-cuts-name-the-bound-and-html-keeps-the-leaf
  (let [raw {:a {:b {:c {:d {:e "last-leaf"}}}}}
        request (probe-unit raw)
        ai (value/render-ai request)
        html (hiccup/->string (value/render-html request))]
    (is (= :seon.render.profile/max-depth
           (get-in (edn/read-string ai) [:a :b :c :d :seon.print/bound-by])))
    (is (str/includes? ai "[:a :b :c :d]"))
    (is (str/includes? html "last-leaf"))
    (is (not (str/includes? html "seon-print-elision")))))

(deftest default-entity-map-renders-refs-as-installed-identities
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           target (db/pull database '[:db/id :seon.ns/name] [:seon.ns/name 'seon.print])
           raw {:seon.agent/namespace (:db/id target)
                :seon.ns/requires #{'seon.print}
                :fixture/title "no render pair"}
           request (assoc (probe-unit raw) :seon.db/db database)
           shown (edn/read-string (value/render-ai request))]
       (is (:db/id target) "the reference target must really exist")
       (is (= [:seon.ns/name 'seon.print] (:seon.agent/namespace shown)))
       (is (= #{'seon.print} (:seon.ns/requires shown)))
       (is (= "no render pair" (:fixture/title shown)))))))

(deftest an-explicit-pull-keeps-its-nested-shape-in-shown-text
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "shape")
     (let [written (support/transacted!
                    connection
                    (agent/creation-tx {:seon.agent/id "shape"
                                        :seon.ns/name 'seon.print
                                        :seon.cluster/name "shape"}))
           _ (is (= "shape" (:seon.agent/id
                              (db/pull (db/db connection) '[:seon.agent/id]
                                       [:seon.agent/id "shape"]))) (pr-str written))
           ctx (support/fork-cluster-ctx connection "shape")
           configuration (support/effective-config)
           result (evaluation/evaluate
                   {:seon.cluster.eval/source
                    "(seon.db/pull '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}] [:seon.agent/id \"shape\"])"
                    :seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                    :seon.sci.admit/caps (config/result-caps configuration)
                    :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)
                    :seon.config/on-core-error :panic})
           shown (:seon.eval/shown result)
           parsed (edn/read-string shown)]
       (is (not (:seon.cluster.eval/error result)) (pr-str result))
       (is (= "shape" (get-in parsed [:seon.agent/namespace :seon.ns/steward :seon.agent/id])) shown)
       (is (= (:seon.sci.admit/value result) parsed) shown)))))

(deftest large-values-have-bounded-ai-work-and-complete-html
  (doseq [[label raw terminal]
          [[:vector (vec (range 100000)) ">99999<"]
           [:string (.repeat "x" (* 5 1024 1024)) nil]]]
    (let [request (probe-unit raw)
          _ (dotimes [_ 3] (value/render-ai request))
          start (System/nanoTime)
          ai (value/render-ai request)
          middle (System/nanoTime)
          html (value/render-html request)
          end (System/nanoTime)
          ai-ms (/ (- middle start) 1e6)
          html-ms (/ (- end middle) 1e6)]
      (println "VALUE-RENDERER" label "AI-ms" ai-ms "HTML-ms" html-ms)
      (is (< ai-ms 100.0) (str label " AI took " ai-ms " ms"))
      (is (str/includes? ai "(seon.print/value-at result/e0123456789ab [])"))
      (if terminal
        (is (str/includes? (hiccup/->string html) terminal))
        (is (= (pr-str raw) (lexical-hiccup-text html)))))))

(deftest elision-forms-execute-in-the-real-sci-context
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           raw {:payload/text (.repeat "x" 20000)}
           node (-> (value/prepare (probe-unit raw)) :seon.render.value/tree
                    :seon.print/entries first second)
           _ (sci/eval-form ctx '(create-ns 'result))
           _ (sci/intern ctx 'result 'e0123456789ab raw)]
       (is (= (:payload/text raw)
              (sci/eval-form ctx (:seon.print/requery-form node))))
       (let [cut (print/elision
                  {:seon.print/requery-id [:seon.ns/name 'seon.print]
                   :seon.render.data/path [:seon.ns/name]
                   :seon.render.data/total 1
                   :seon.print/omitted 1
                   :seon.print/bound-by :seon.render.profile/max-depth})]
         (is (= 'seon.print (support/agent-value ctx (pr-str (:seon.print/requery-form cut))))))))))

(deftest caller-print-bindings-do-not-change-string-bytes
  (let [raw "quoted \"line\"\nnext\t\\"
        expected (pr-str raw)]
    (binding [*print-readably* false *print-length* 1 *print-level* 1]
      (is (= expected (value/render-ai (probe-unit raw)))))))

;;; A CUT NEVER SHOWS NOTHING. Three regressions for one class: the AI
;;; projection of an over-budget value used to be an elision whose omitted
;;; count equalled its total — zero content, no usable coordinate, and a
;;; CHARACTER count where AGENTS.md §2.4 rules estimated tokens. Issues:
;;; `a-value-larger-than-the-budget-is-elided-to-nothing` and
;;; `dir-of-a-namespace-returns-an-elision-with-nothing-shown`.

(defn- elision-in
  "The one elision value an agent's shown text carries, read back as data."
  [shown]
  (let [value (edn/read-string shown)]
    (first (filter #(and (map? %) (contains? % :seon.print/omitted))
                   (tree-seq coll? #(if (map? %) (concat (keys %) (vals %)) (seq %))
                             value)))))

(deftest an-oversized-string-shows-its-prefix-not-only-a-count
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "string-floor")
     (let [configuration (support/effective-config)
           profile (assoc (render/agent-render-profile configuration)
                          :seon.render.profile/max-string-length 128)
           ctx (support/fork-cluster-ctx connection "string-floor")
           result (evaluation/evaluate
                    {:seon.cluster.eval/source "(apply str (repeat 3000 \"ab\"))"
                     :seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                     :seon.render/profile profile
                     :seon.repl/handle 'result/e0123456789ab
                     :seon.sci.admit/caps (config/result-caps configuration)
                     :seon.sci.eval/time-limit-ms
                     (:seon.config.eval/time-limit-ms configuration)
                     :seon.config/on-core-error :panic})
           shown (:seon.eval/shown result)
           cut (elision-in shown)]
       (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
       (is (> (count (:seon.sci.admit/value result))
              (:seon.render.profile/max-string-length profile)))
       (is (some? cut) shown)
       ;; The floor: content, not a bare count.
       (is (pos? (count (:seon.print/prefix cut))) shown)
       (is (str/starts-with? (:seon.print/prefix cut) "abab") shown)
       ;; The remainder is what is missing, never the whole value.
       (is (pos? (:seon.print/omitted cut)) shown)
       (is (< (:seon.print/omitted cut) (:seon.render.data/total cut)) shown)
       ;; The offset is a real coordinate: it names what was already shown.
       (is (pos? (:seon.render.data/next-offset cut)) shown)
       (is (= (:seon.render.data/total cut)
              (+ (:seon.print/omitted cut) (:seon.render.data/next-offset cut)))
           shown)
       (is (= 6000 (:seon.render.data/total cut)) shown)
       (is (= (count (:seon.print/prefix cut))
              (:seon.render.data/next-offset cut))
           shown)
       ;; And the reader can ask again rather than being told a refusal.
       (is (= 'seon.print/value-at (first (:seon.print/requery-form cut)))
           shown)
       (evaluation/bind-result! ctx 'result/e0123456789ab (:seon.sci.admit/value result))
       (is (= (:seon.sci.admit/value result)
              (sci/eval-form ctx (:seon.print/requery-form cut))))))))

(deftest an-agent-facing-cut-reports-its-size-in-estimated-tokens
  ;; AGENTS.md §2.4: display sizes for humans are estimated tokens via
  ;; `seon.ai.tokens/estimate`; character counts are storage projections.
  (let [characters 4000
        node (print/elision {:seon.print/omitted characters
                             :seon.print/elision-unit :characters
                             :seon.print/prefix "abc"
                             :seon.render.data/path []
                             :seon.render.data/next-offset 0
                             :seon.render.data/total characters
                             :seon.render.profile/id :seon.render.profile/agent})
        shown (edn/read-string (print/render-elision-ai node))]
    (is (= (tokens/estimate-of-characters characters)
           (:seon.ai.tokens/estimate shown))
        (pr-str shown))
    ;; The declared fields stay the value's own units, because `next-offset`
    ;; and `total` are the reader's COORDINATES, not display sizes. Restating
    ;; a coordinate as a floored estimate names a position that does not
    ;; exist; `generated-values-have-deterministic-readable-executable-elisions`
    ;; executes the requery form against exactly these numbers.
    (is (= :characters (:seon.print/elision-unit shown)))
    (is (= characters (:seon.print/omitted shown)))
    (is (= characters (:seon.render.data/total shown)))
    (is (= "abc" (:seon.print/prefix shown)))
    ;; A member cut has no derivable token size, so the field is absent
    ;; rather than invented.
    (let [members (edn/read-string
                   (print/render-elision-ai
                    (print/elision {:seon.print/omitted 268
                                    :seon.print/elision-unit :children
                                    :seon.render.data/path []
                                    :seon.render.data/next-offset 32
                                    :seon.render.data/total 300
                                    :seon.render.profile/id
                                    :seon.render.profile/agent})))]
      (is (= :children (:seon.print/elision-unit members)))
      (is (not (contains? members :seon.ai.tokens/estimate))))))

(deftest dir-of-a-large-namespace-shows-members-and-how-to-continue
  ;; `dir` is the agent's index into a namespace. On `default` it answered
  ;; only `{:seon.print/omitted 41040, :seon.render.data/total 41040, ...}`
  ;; — a count of what the agent was not told.
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "dir-floor")
     (let [configuration (support/effective-config)
           profile (assoc (render/agent-render-profile configuration)
                          :seon.render.profile/token-budget 1024)
           ctx (support/fork-cluster-ctx connection "dir-floor")
           result (evaluation/evaluate
                    {:seon.cluster.eval/source "(dir seon.turn)"
                     :seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                     :seon.render/profile profile
                     :seon.repl/handle 'result/e0123456789ab
                     :seon.sci.admit/caps (config/result-caps configuration)
                     :seon.sci.eval/time-limit-ms
                     (:seon.config.eval/time-limit-ms configuration)
                     :seon.config/on-core-error :panic})
           shown (:seon.eval/shown result)
           cut (elision-in shown)]
       (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
       (is (str/includes? shown "seon.turn/") shown)
       (is (> (tokens/estimate
               (repl/render-directory-ai (:seon.sci.admit/value result)))
              (:seon.render.profile/token-budget profile)))
       (is (some? cut) shown)
       (is (pos? (:seon.render.data/next-offset cut)) shown)
       (is (< (:seon.print/omitted cut) (:seon.render.data/total cut)) shown)
       (is (= 'seon.print/value-at (first (:seon.print/requery-form cut)))
           shown)
       (evaluation/bind-result! ctx 'result/e0123456789ab (:seon.sci.admit/value result))
       (is (= (:seon.sci.admit/value result)
              (sci/eval-form ctx (:seon.print/requery-form cut))))))))

(deftest transacted-preserves-error-entities-as-data
  ;; Both arities normalize entity maps without interpreting them as failures.
  (support/with-database
   (fn [connection]
     (let [projection (schema/handed-projection)
           refusal (try ((instrument/wrap-interpreted
                          'my.agents.audit/vector-input
                          "[:=> [:cat [:vector :int]] :int]"
                          projection :panic caps (constantly 1))
                         "not-a-vector")
                        (catch Exception failure (ex-data failure)))]
       (is ((seon.schema/projection-validator projection :seon.instrument/contract-error) refusal)
           (str "the reproduction is a genuinely declared base error: "
                (pr-str refusal)))
       ;; The canonical runner arms both data-conversion arities.
       (doseq [[label returned]
               [["shape-only arity" (value/transacted refusal)]
                ["database arity" (value/transacted refusal (db/db connection))]]]
         (is ((seon.schema/projection-validator projection :seon.instrument/contract-error) returned)
             (str label " returns a value satisfying its declared alternative"))
         (is (= (:seon.error/operation refusal) (:seon.error/operation returned))
             label))
       ;; An ordinary success value still normalizes and validates.
       (is (= {:seon.agent/id "root"}
              (value/transacted {:db/id 7 :seon.agent/id "root"})))
       (is (= {:seon.agent/id "root"}
              (value/transacted {:db/id 7 :seon.agent/id "root"}
                                (db/db connection))))
       (is (= refusal (value/transacted refusal (db/db connection))))))))
