(ns seon.render.value-test
  "The render floor is one adapter over the sealed print emitter."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
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
         :seon.sci.eval/time-limit-ms 2000))

(defn- tree-cuts
  "The cuts of one prepared projection's tree: the floor's elided nodes,
  which keep path, offset, total and requery (the AI text shows only `…N`)."
  [projection]
  (filter #(and (map? %) (= :seon.print/elided (:seon.print/face %)))
          (tree-seq coll? seq (:seon.render.value/tree projection))))

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
       ;; Each value names its producer (design §1 rule 2): its declared
       ;; return names the pair.
       (doseq [[raw render-pair producer] [[report db/render-transaction-ai 'seon.db/transact!]
                                          [directory repl/render-directory-ai 'seon.sci.eval/directory-value]
                                          [step plan/render-item-ai 'seon.plan/item]
                                          [whole-plan plan/format-plan-ai 'seon.plan/plan]]]
         (is (= (render-pair raw)
                (render/render-ai (assoc request :seon.render/value raw
                                         :seon.render.call/function producer)))
             (str producer)))))))

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
                         :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)})
               shown (:seon.eval/shown result)
               tree-projection (value/prepare
                                (assoc (render-request connection raw)
                                       :seon.render.value/options {:seon.render.value/structural? true}
                                       :seon.render/profile profile :seon.repl/handle handle))
               cuts (tree-cuts tree-projection)
               shown-keys (keep #(when (vector? %) (:seon.print/value (first %)))
                                (:seon.print/entries (:seon.render.value/tree tree-projection)))]
           (is (= (second lookup) (get raw (first lookup))) (pr-str lookup raw))
           (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
           (is (= raw (:seon.sci.admit/value result)))
           (is (= shown (value/render-ai-data tree-projection)) "one selection, one text")
           (is (str/starts-with? shown "{") shown)
           (is (<= (tokens/estimate shown) (:seon.render.profile/token-budget profile)) shown)
           (is (seq shown-keys) shown)
           (is (every? (set (keys raw)) shown-keys) shown)
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

(deftest a-pulled-function-entity-is-its-attributes-not-steering-prose
  ;; THE CLASS: a one-attribute finding entity may not declare a render pair
  ;; over another family's identity attribute. `:seon.problems/stale-var`
  ;; is `[:map [:seon.fn/sym :seon.fn/sym]]` and maps are OPEN, so the pair
  ;; it declared was selected for EVERY function entity an agent pulled and
  ;; the whole published program graph answered "Restart the JVM to remove
  ;; stale loaded Var ...". The structural twin above cannot see it: that
  ;; one passes `:seon.render.value/structural? true`, which is precisely
  ;; the option an agent's own evaluation never sets. So this asserts the
  ;; agent's real path, and then the derivation behind it — no shape a bare
  ;; `:seon.fn` entity matches may carry an AI pair at all.
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
                     :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)})
           shown (:seon.eval/shown result)]
       (is (= 'seon.db/q (:seon.fn/sym raw)) (pr-str lookup raw))
       (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
       (is (= raw (:seon.sci.admit/value result)))
       (is (= raw (edn/read-string shown)) shown)
       (is (not (str/includes? shown "Restart the JVM")) shown)
       (is (empty? (filter :seon.render/ai
                           (schema/matching-shapes-in
                            (db/carried-projection database) raw)))
           "a bare :seon.fn entity must match no shape declaring an AI pair")))))

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
                              :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)})
                    shown (:seon.eval/shown result)]
                (is (:db-after written) (pr-str written))
                (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
                ;; Clojure's printer lifts the shared namespace (#:seon.effect{...}).
                (is (str/starts-with? shown "#:seon.effect{") shown)
                (is (str/includes? shown ":id \"poll-render-effect\"") shown)
                (is (str/includes? shown ":duration-ms 12") shown)
                ;; The payload is cut to its prefix; the cut names what remains.
                (is (re-find #":result-edn\s+\"x+\"…\+\d+" shown) shown)
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
    ;; Held order: an array map prints in its own order (design §3b).
    (is (= "#:unregistered{:value 1, :detail [2 3]}"
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

(deftest ai-values-are-laid-out-by-fipp-at-their-width
  ;; fipp breaks between tokens at the caller's width; the laid-out text
  ;; still reads back whole.
  (let [raw [{:example/title (apply str (repeat 180 "x"))
              :example/count 2 :example/nested {:example/value [1 2 3]}}]
        request (assoc (unit raw) :seon.print/options {:seon.print/width 10})
        shown (value/render-ai request)]
    (is (= raw (edn/read-string shown)))
    (is (< 3 (count (str/split-lines shown))) shown)
    (is (str/includes? (hiccup/->string (value/render-html request))
                       (apply str (repeat 180 "x"))))))

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
      (schema/canonical-schema-entities projection forms))}
   (fn [connection]
     (let [;; Selection is by declared identity, never structural
           ;; resemblance: the producer declares the value's schema.
           failure (with-meta {:my.message/no-recipient true
                               :seon.error/message "A recipient is required."}
                     {:seon.error/declared-schema :fixture/declared-producer})
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
     (let [entities (mapv (fn [ordinal]
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
           floor-unit (assoc (registered-unit connection entities)
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
                   :seon.sci.admit/caps caps})
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

(deftest rendering-live-values-does-not-apply-a-storage-bound
  (let [raw (vec (range 100))
        request (assoc (unit raw) :seon.sci.admit/caps
                       {:seon.config.eval.result/max-bytes 1})]
    (is (= 68 (:seon.print/omitted (last (tree-cuts (value/prepare request))))))
    (is (str/includes? (value/render-ai request) "…68"))
    (is (str/includes? (hiccup/->string (value/render-html request)) "…68")
        "HTML shows the same selection and the same cut as the text")
    (is (not (str/includes? (value/render-ai request) "over-bound")))))

(deftest elision-is-a-requeryable-structural-value
  ;; THE SECOND AND THIRD BOUNDS. The AI projection is cut by the render
  ;; profile — the one place presentation elides — and the cut is ordinary
  ;; data naming what was omitted, the bound that made it, and how to ask
  ;; again. HTML is the same selection: its cut is the same continuation.
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
      ;; One cut face carrying the form that reads the rest (owner 2026-09-27).
      (is (str/includes? text (str "…" (- (count raw) kept) " (drop " kept " result/e0123456789ab)]")) text)
      (is (not (str/includes? text ";;")) "no legend: the cut's own form says how to read on")
      (is (= '(seon.print/value-at result/e0123456789ab [])
             (:seon.print/requery-form elision))))
    (is (str/includes? html "seon-print-elision")
        "HTML shows the one selection, not a second unbounded one")
    (is (not (str/includes? html ">99<")))))

(deftest a-map-beyond-the-child-bound-costs-the-bound-not-its-size
  ;; The AI render keeps the first `max-children` members in the map's held
  ;; order and names the rest; nothing sorts the map, so a million entries
  ;; cost the bound (design §3b).
  (let [profile (render/agent-render-profile (support/effective-config))
        kept (:seon.render.profile/max-children profile)
        shown (fn [raw] (->> (value/prepare (assoc (unit raw) :seon.render/profile profile))
                             :seon.render.value/tree :seon.print/entries
                             (keep (fn [entry] (when (vector? entry)
                                                 (:seon.print/value (first entry)))))))
        whole (fn [raw] (keys raw))
        mixed (merge (zipmap (range 20) (range)) (zipmap (map (comp keyword str) (range 20)) (range)))
        large (zipmap (range 1000000) (range))
        started (System/nanoTime)
        projection (value/prepare (assoc (unit large) :seon.render/profile profile))
        elapsed-ms (/ (- (System/nanoTime) started) 1e6)
        cut (last (:seon.print/entries (:seon.render.value/tree projection)))]
    (is (= (whole (into {} (take kept mixed))) (shown (into {} (take kept mixed))))
        "at the bound every member shows in held order")
    (is (= (take kept (whole mixed)) (shown mixed))
        "beyond the bound the kept members are the held order's first")
    (is (= (- (count large) kept) (:seon.print/omitted cut)) (pr-str cut))
    (is (= :seon.render.profile/max-children (:seon.print/bound-by cut)))
    (is (< elapsed-ms 1000) (str "a million-entry map rendered in " elapsed-ms " ms"))))

(deftest references-stay-opaque
  ;; A reference is never entered: it contributes its class and nothing
  ;; else, so no private state leaks through a render. A value that is
  ;; ITSELF an opaque reference projects no data at all, and that answer is
  ;; the typed marker rather than an empty panel.
  (let [projection (value/prepare (unit {:reference (atom {:private/value 42})}))
        tree (:seon.render.value/tree projection)
        [_ reference] (first (:seon.print/entries tree))]
    (is (= #{:seon.print/face :seon.print/class :seon.print/rep :seon.render.data/path}
           (set (keys reference)))
        "an opaque reference carries its class and identity, never its value")
    (is (= :seon.print/object (:seon.print/face reference)))
    (is (= "clojure.lang.Atom" (:seon.print/class reference)))
    (is (re-matches #"\{:reference #object\[clojure.lang.Atom 0x[0-9a-f]+\]\}"
                    (value/render-ai-data projection)))
    (is (re-matches #"#object\[clojure.lang.Atom 0x[0-9a-f]+\]"
                    (value/render-ai (unit (atom {:private/value 42}))))
        "a live reference is named by class and identity even when no serialization exists")))

(deftest a-lazy-result-that-throws-while-printing-is-shown-with-its-failure
  ;; The printer realizes a lazy result as far as it prints (root, dogfood
  ;; cycle 1), as Clojure's printer does. The floor is total (AGENTS, the
  ;; REPL path is total): a member that throws never escapes the render and
  ;; is never a silent pending tail; it is the one diagnostic shown beside
  ;; the value and returned for the caller to record.
  (let [poison (fn [] (map (fn [_] (throw (ex-info "poison" {}))) [1]))
        projection (value/prepare (unit (poison)))
        text (:seon.render.value/text projection)]
    (is (string? (value/render-ai (unit (poison)))))
    (is (= ["poison"] (map ex-message (:seon.render.value/diagnostics projection))))
    (is (= 1 (count (re-seq #"renderer failed" text))) text)
    (is (str/includes? text "poison") text)))

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
                    cuts (atom (vec (remove #(= :pending (:seon.print/elision-unit %))
                                            (tree-cuts (value/prepare request)))))]
                (swap! observed-cuts + (count @cuts))
                (and (= first-render second-render)
                     (or (seq @cuts) (= raw (edn/read-string first-render)))
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
       (let [cut (first (filter #(= [:body] (:seon.render.data/path %))
                                (tree-cuts (value/prepare request))))]
         ;; The cut keeps the characters the profile admits and counts only
         ;; the remainder; it no longer omits the whole body.
         (is (= 24 (:seon.render.data/next-offset cut)))
         (is (= (subs body 0 24) (:seon.print/prefix cut)))
         (is (= (- (count body) 24) (:seon.print/omitted cut)))
         (is (= (count body) (:seon.render.data/total cut)))
         (is (= [:body] (:seon.render.data/path cut)))
         (is (= body (sci/eval-form ctx (:seon.print/requery-form cut)))))))))

(deftest ai-realizes-only-the-members-it-prints
  ;; An endless lazy result prints its first max-children members (3 in the
  ;; probe profile) then …?, and no member past those is realized.
  (let [visits (atom [])
        raw (map (fn [i] (swap! visits conj i) i) (iterate inc 0))
        text (value/render-ai (probe-unit raw))]
    (is (str/starts-with? text "(0 1 2 …? (drop 3 result/e0123456789ab))") text)
    (is (= [0 1 2] @visits) "no member past the printed ones is realized")))

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
    ;; Both faces are one selection: the HTML of the same unit shows the same cut.
    (is (str/includes? (lexical-hiccup-text (value/render-html request))
                       (str "…+" (:seon.print/omitted node))))))

(deftest depth-cuts-name-the-bound-in-both-faces
  (let [raw {:a {:b {:c {:d {:e "last-leaf"}}}}}
        request (probe-unit raw)
        ai (value/render-ai request)
        html (hiccup/->string (value/render-html request))]
    (is (= [:seon.render.profile/max-depth]
           (map :seon.print/bound-by (tree-cuts (value/prepare request)))))
    (is (= [[:a :b :c :d]] (map :seon.render.data/path (tree-cuts (value/prepare request)))))
    ;; A map's cut reads the whole map: its shown order is not its iteration order.
    (is (str/includes? ai "{:d {…1 (get-in result/e0123456789ab [:a :b :c :d])}}") ai)
    (is (not (str/includes? html "last-leaf")))
    (is (str/includes? html "seon-print-elision"))))

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
                    :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)})
           shown (:seon.eval/shown result)
           parsed (edn/read-string shown)]
       (is (not (:seon.cluster.eval/error result)) (pr-str result))
       (is (= "shape" (get-in parsed [:seon.agent/namespace :seon.ns/steward :seon.agent/id])) shown)
       (is (= (:seon.sci.admit/value result) parsed) shown)))))

(deftest large-values-have-bounded-work-in-both-faces
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
      (is (< html-ms 100.0) (str label " HTML took " html-ms " ms"))
      (is (re-find #"\((drop|subs) .*result/e0123456789ab" ai) ai)
      ;; HTML is the same selection under the same 1,000-token budget: its
      ;; text is the AI text, cut and continuation included, never more.
      (is (= ai (lexical-hiccup-text html)))
      (is (<= (count ai) (tokens/estimate-chars (:seon.render.profile/token-budget probe-profile))) ai)
      (when terminal
        (is (not (str/includes? (hiccup/->string html) terminal)))))))

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
  "The one cut an evaluation's shown text carries: the same selection over
  its value (`shown` is that projection's text) yields the cut node."
  [shown value profile]
  (let [projection (value/prepare (assoc (unit value) :seon.render/profile profile
                                         :seon.repl/handle 'result/e0123456789ab))]
    (is (= shown (value/render-ai-data projection)) "the shown text is this selection's")
    (first (tree-cuts projection))))

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
                     (:seon.config.eval/time-limit-ms configuration)})
           shown (:seon.eval/shown result)
           cut (elision-in shown (:seon.sci.admit/value result) profile)]
       (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
       (is (str/includes? shown "\"…+") shown)
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

(deftest dir-of-a-large-namespace-is-its-declared-renderer-text
  ;; `dir` is the agent's index into a namespace. Its value is a
  ;; `:seon.repl/directory`, whose declared AI renderer selects the columns
  ;; (47cfeacfa). The budget is chosen by provenance (owner 2026-09-26): that
  ;; text is its own deliberate bound, laid out whole under the ceiling, so
  ;; the raw budget does not cut it.
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
                     (:seon.config.eval/time-limit-ms configuration)})
           shown (:seon.eval/shown result)
           rendered (repl/render-directory-ai (:seon.sci.admit/value result))]
       (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
       (is (> (tokens/estimate rendered) (:seon.render.profile/token-budget profile))
           "the directory exceeds the raw budget")
       ;; Owner 2026-09-27: a render follows the value's budget (no separate
       ;; ceiling). Its text is cut there, still printed as the render's REPL
       ;; text, and the renderer that produced it is still named.
       (is (str/starts-with? shown (subs rendered 0 500)) shown)
       (is (re-find #"…\+\d+$" shown) (subs shown (max 0 (- (count shown) 80))))
       (is (<= (count shown) (+ (tokens/estimate-chars (:seon.render.profile/token-budget profile)) 16)))
       (is (= 'seon.repl/render-directory-ai (:seon.eval/renderer result)))))))

(deftest transacted-preserves-error-entities-as-data
  ;; Both arities normalize entity maps without interpreting them as failures.
  (support/with-database
   (fn [connection]
     (let [projection (schema/handed-projection)
           refusal (try ((instrument/wrap-interpreted
                          'my.agents.audit/vector-input
                          "[:=> [:cat [:vector :int]] :int]"
                          projection caps (constantly 1))
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

(deftest every-sequential-prints-as-a-list
  ;; Classified by interface, as Clojure's printer prints them: any ISeq or
  ;; other Sequential is a list; a lazy result is realized as far as it
  ;; prints, and an endless one ends at the member bound with …?.
  (let [text #(:seon.render.value/text (value/prepare (unit %)))]
    (is (= "(1 2 3)" (text (sort [3 1 2]))))
    (is (= "(:a :b)" (text (keys {:a 1 :b 2}))))
    (is (= "(1 2)" (text (conj clojure.lang.PersistentQueue/EMPTY 1 2))))
    (is (= "(2 3)" (text (map inc [1 2]))))
    (is (re-find #"(?s)^\(0 1 2 .*…\?\)" (text (iterate inc 0))))))

(deftest a-seq-past-the-member-bound-is-cut-never-shortened
  ;; A seq holding more members than max-children shows a named cut, as a
  ;; vector does: a counted seq names its remaining count and bound, and an
  ;; uncounted one (a cons) its pending tail. Dropping members silently
  ;; showed agents `(0 0 0)` for `(0 0 0 0)` (red triage 3, defect 1).
  (let [text #(value/render-ai (probe-unit %))
        cut #(first (tree-cuts (value/prepare (probe-unit %))))]
    (doseq [[raw shown] [['(0 0 0 0) "(0 0 0 …1 (drop 3 result/e0123456789ab))"]
                         [(range 5) "(0 1 2 …2 (drop 3 result/e0123456789ab))"]
                         [(vec (repeat 30 0)) "[0 0 0 …27 (drop 3 result/e0123456789ab)]"]]]
      (is (= shown (text raw)))
      (is (= {:seon.render.data/total (count raw) :seon.render.data/next-offset 3
              :seon.print/omitted (- (count raw) 3)
              :seon.print/bound-by :seon.render.profile/max-children}
             (select-keys (cut raw) [:seon.render.data/total :seon.render.data/next-offset
                                     :seon.print/omitted :seon.print/bound-by]))))
    (is (= "(9 1 2 …? (drop 3 result/e0123456789ab))" (text (cons 9 [1 2 3]))))
    (is (= "(1 2 3)" (text '(1 2 3))) "a seq within the bound is whole")))

(deftest a-value-s-own-rendered-data-is-its-ai-face
  ;; The master override (AGENTS render law): a value's own :seon.render/ai
  ;; key may be the rendered data itself. Only a named producer has indexed
  ;; contracts to read source intent from, so rendered data is shown as given.
  (support/with-database
   (fn [connection]
     (is (= "just text"
            (:seon.render.value/text
             (value/prepare (render-request connection {:seon.render/ai "just text" :x 1}))))))))

(deftest a-value-s-own-inline-function-renders-it
  ;; The master override's third form (AGENTS render law): an inline function
  ;; is invoked under the same arm as a named producer, at the root only. The
  ;; value printer never re-dispatches (AGENTS.md, renders return data): a
  ;; nested value prints as the data it is.
  (support/with-database
   (fn [connection]
     (let [text #(:seon.render.value/text (value/prepare (render-request connection %)))]
       (is (= "hello 1" (text {:seon.render/ai (fn [unit] (str "hello " (:x unit))) :x 1})))
       (is (str/starts-with? (text {:a {:seon.render/ai (fn [_] "inner")}}) "{:a {:seon.render/ai #object[")
           (text {:a {:seon.render/ai (fn [_] "inner")}}))))))

(deftest ^{:seon/tasks #{"135d92ea1308"}} a-failing-renderer-never-replaces-the-value
  ;; AGENTS.md, the REPL path is total: a renderer that throws or refuses
  ;; leaves its value printed as data, with ONE diagnostic line beside it for
  ;; the whole value, and the failures returned for the caller to record.
  (support/with-database
   (fn [connection]
     (let [boom (fn [_] (throw (ex-info "boom" {})))
           prepared (value/prepare (render-request connection {:seon.render/ai boom :a 1 :b {:c 2}}))
           shown (:seon.render.value/text prepared)
           {diagnostics true value-lines false}
           (group-by #(str/starts-with? % ";; renderer failed") (str/split-lines shown))
           ;; Where the value breaks depends on its compiled fn class name's
           ;; width; the value printed as data is what matters, not its layout.
           value-text (str/replace (str/join " " value-lines) #"\s+" " ")
           nested (value/prepare (render-request connection [{:seon.render/ai boom :a 1}]))]
       (is (str/includes? value-text ":a 1") shown)
       (is (str/includes? value-text ":b {:c 2}") shown)
       (is (str/includes? value-text ":seon.render/ai #object[") shown)
       (is (= 1 (count diagnostics)) shown)
       (is (str/starts-with? (first diagnostics) ";; renderer failed at []") shown)
       (is (= 1 (count (:seon.render.value/diagnostics prepared))))
       (is (nil? (:seon.render.value/diagnostics nested))
           "a nested render is never invoked, so it cannot fail")))))

(deftest selection-is-one-rule-at-every-depth
  ;; Render-specificity audit slice 2 (owner 2026-09-26: "No specific 'if
  ;; s/error/' shit"): the value printer asks the one `render/selection` for
  ;; the root only and never re-dispatches (AGENTS.md, renders return data): a
  ;; nested error prints as the data it is, its message among its short
  ;; members; an error nobody declared is data too.
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           refusal (db/pull database '[*] [:no.such/attr 1])
           undeclared (vary-meta refusal dissoc :seon.error/declared-schema)
           text #(:seon.render.value/text (value/prepare (render-request connection %)))
           sentence "cannot read uninstalled attribute :no.such/attr"]
       (is (= :seon.db/invalid-read-error (:seon.error/declared-schema (meta refusal))))
       (is (str/includes? (text refusal) sentence) (text refusal))
       (is (str/includes? (text [refusal]) sentence) (text [refusal]))
       (is (str/includes? (text {:k [1 refusal]}) sentence) (text {:k [1 refusal]}))
       (is (str/starts-with? (text undeclared) "{") (text undeclared))
       (is (str/includes? (text [undeclared]) ":seon.error/operation seon.db/pull")
           (text [undeclared]))))))

(deftest ^{:seon/tasks #{"135d92ea1308"}} a-map-that-cannot-hold-keywords-prints-as-clojure-prints-it
  ;; Smoke 2026-09-26: `(sorted-map 'b 2 'a 1)` printed a ClassCastException
  ;; from the renderer's keyword lookup in place of the map. Only a map
  ;; `keyword-lookup?` permits carries an attachment or an identity; a keyword
  ;; sorted map still carries its own face.
  (support/with-database
   (fn [connection]
     (let [prepared #(value/prepare (render-request connection %))
           text #(:seon.render.value/text (prepared %))]
       (is (= "[42 {a 1, b 2}]" (text [42 (sorted-map 'b 2 'a 1)])))
       (is (= "{a 1, b 2}" (text (sorted-map 'b 2 'a 1))))
       (is (= "{:k {\"x\" 1}}" (text {:k (sorted-map "x" 1)})))
       (is (= "{z 1}" (text (sorted-map-by compare 'z 1))))
       (is (nil? (:seon.render.value/diagnostics (prepared (sorted-map 'b 2 'a 1)))))
       (is (= "custom face" (text (sorted-map :seon.render/ai "custom face" :x 1))))
       (is (false? (value/keyword-lookup? (sorted-map 'a 1))))
       (is (true? (value/keyword-lookup? (sorted-map :a 1))))))))

(deftest a-reference-prints-as-its-referent-s-identity
  ;; A non-component ref attribute's entity id prints as the referent's
  ;; identity lookup (dogfood cycle 2: `:my.note/agent 34185`), never an eid.
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "ref-floor")
     ;; A test world holds only the agents its seeds create: make the referent.
     (support/transacted! connection (support/agent-tx (db/db connection) "ref-floor-agent"))
     (let [id "ref-floor-agent"
           eid (db/q '[:find ?e . :in $ ?id :where [?e :seon.agent/id ?id]] (db/db connection) id)
           expected (str "{:seon.turn/agent [:seon.agent/id " (pr-str id) "]}")
           text #(:seon.render.value/text (value/prepare (render-request connection %)))]
       (is (int? eid))
       (is (= expected (text {:seon.turn/agent {:db/id eid}})) "a pull's reference is its referent")
       (is (= (str "{:db/id 7, :seon.turn/agent [:seon.agent/id " (pr-str id) "]}")
              (text {:db/id 7 :seon.turn/agent eid}))
           "inside an entity an integer under a ref attribute is its referent")
       ;; Fault-resolution design 2026-09-27 §9.2: `(frequencies …)` returned
       ;; `{:seon.error/resolved-tx 9}` and printed the entity 9 names.
       (is (= (str "{:seon.turn/agent " eid "}") (text {:seon.turn/agent eid}))
           "outside an entity a number is a number, whatever its key")
       (is (= (str "{:seon.error/resolved-tx " eid "}") (text {:seon.error/resolved-tx eid})))))))

(deftest a-relation-query-result-prints-as-the-set-it-is
  ;; Stability sweep 2026-09-27 / fault-resolution §9.3: Datahike answers a
  ;; relation `:find` with `datahike.java.QueryResult`, a set the floor showed
  ;; as an opaque object.
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "set-floor")
     (let [database (db/db connection)
           empty-result (db/q '[:find ?e :where [?e :seon.agent/id "no-such-agent"]] database)
           rows (db/q '[:find ?name :where [_ :seon.cluster/name ?name]] database)
           text #(:seon.render.value/text (value/prepare (registered-unit connection %)))]
       (is (instance? datahike.java.QueryResult empty-result) (pr-str (class empty-result)))
       (is (= "#{}" (text empty-result)))
       ;; Datahike answers a non-empty relation here with a plain set; only
       ;; the empty relation is a QueryResult (measured on default, 2026-09-27)
       (is (set? rows) (pr-str (class rows)))
       (is (= rows (edn/read-string (text rows))))))))

(deftest a-cut-costs-less-than-it-hides-and-never-lands-in-a-tuple
  ;; Stability sweep 2026-09-27: an MCP reply showed frame tuples as
  ;; `[…4 (get-in … [0 :seon.error/frames 4])]`, each cut costing more than
  ;; the four values it hid. A tuple is admitted whole or not at all, and a
  ;; tail that costs no more than its cut is shown instead of the cut.
  (let [projection #(value/prepare (probe-unit %))
        text #(:seon.render.value/text (projection %))
        frame ['seon.cluster.agent/evaluate-source! 'invoke "agent.clj" 100]
        frames (vec (repeat 20 frame))]
    (is (= "[0 0 0 0]" (text [0 0 0 0])) "past the member bound, a tuple is whole")
    (is (= "[0 0 0 …27 (drop 3 result/e0123456789ab)]" (text (vec (repeat 30 0))))
        "a tail larger than its cut is still cut")
    (is (every? #(= [] (:seon.render.data/path %)) (tree-cuts (projection frames)))
        "the only cut is the frames' own, never one inside a frame")
    (is (= (vec (repeat 3 frame))
           (vec (butlast (edn/read-string (str/replace (text frames) #"…\d+ \(drop 3 result/e0123456789ab\)" ":cut"))))))
    (let [strings [(apply str (repeat 40 "x")) (apply str (repeat 400 "y"))]
          capped (assoc-in (probe-unit strings) [:seon.render/profile :seon.render.profile/token-budget] 35)]
      (is (not-any? #(= [0] (:seon.render.data/path %)) (tree-cuts (value/prepare capped)))
          "a string whose rest is shorter than its cut is shown whole"))))

(deftest a-tuple-wider-than-its-line-stays-within-the-charge
  ;; value-hygiene 2026-09-27: MCP replies showed "The floor's saved text
  ;; exceeded its charge." A tuple is one leaf to the allocator, but fipp fills
  ;; its members, so a tuple past the line broke without being charged:
  ;; `{... :p-keys (:k/long :k/long) ...}` laid out 4 characters over, a vector
  ;; of three objects 1 over. The floor is total: no value fails on its charge.
  (let [wide #(assoc (unit %) :seon.render/profile
                     (assoc probe-profile :seon.render.profile/composition :multiline
                            :seon.render.profile/max-children 8 :seon.render.profile/max-depth 6))
        diagnostics #(:seon.render.value/diagnostics (value/prepare (wide %)))
        tuple (gen/one-of [(gen/vector gen/keyword-ns 1 4) (gen/list gen/keyword-ns)])
        values (gen/recursive-gen
                (fn [inner] (gen/one-of [(gen/vector inner 0 6) (gen/map gen/keyword-ns inner {:max-elements 5})]))
                (gen/one-of [gen/keyword-ns gen/small-integer (gen/return nil) tuple]))
        observed (tc/quick-check 200 (prop/for-all [v values] (empty? (diagnostics v))) :seed 20260927)]
    (is (empty? (diagnostics {:p-has false
                              :p-keys (list :seon.config.db/validation-node-limit
                                            :seon.schema.projection/compile-options)
                              :h-has false :fc-type nil})))
    (is (empty? (diagnostics [(atom 1) identity (Object.)])))
    (is (:pass? observed) (pr-str (:shrunk observed)))))

(deftest vars-and-namespaces-print-by-name
  ;; As clojure.main prints them: #'ns/name and #namespace[name], from their
  ;; final fields, in either world (dogfood cycle 3).
  (let [text #(:seon.render.value/text (value/prepare (unit %)))]
    (is (= "#'user/f" (text (sci/eval-string "(defn f [] 1) #'f"))))
    (is (= "#namespace[user]" (text (sci/eval-string "*ns*"))))
    (is (= "#namespace[clojure.core]" (text (the-ns 'clojure.core))))))

(deftest agent-records-and-types-print-as-clojure-prints-them
  ;; An agent's record prints #user.Card{…} and its type user.Card, from
  ;; SCI's own fields, as `pr-str` prints them (dogfood cycle 9, #19).
  (let [text #(:seon.render.value/text (value/prepare (unit %)))
        [card card-type] (sci/eval-string "(defrecord Card [rank suit]) [(->Card 1 :s) Card]")]
    (is (= (pr-str card) (text card)))
    (is (= "#user.Card{:rank 1, :suit :s}" (text card)))
    (is (= "user.Card" (text card-type)))
    (is (= "{:hand [#user.Card{:rank 1, :suit :s}]}" (text {:hand [card]})))))

(deftest a-cut-continues-from-its-node-path-and-absolute-offset
  ;; One-render-path §3g: a cut's coordinates are the walk node, the path and
  ;; the absolute offset; the same selection answers from there, and a node
  ;; that is no longer retained answers :gone rather than an empty value.
  (support/with-database
   (fn [connection]
     ;; The agent's own turn fork retains result objects; bind-result! is
     ;; the evaluation's writer of that binding.
     (let [value {:rows (vec (range 100))}
           ctx (:seon.sci.eval/ctx
                (evaluation/fork-for-turn {:seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                                           :seon.db/db (db/db connection)
                                           :seon.agent/id "root"}))
           _ (evaluation/bind-result! ctx 'result/e0123456789ab value)
           request (fn [node path offset]
                     {:seon.render.walk/continuation-node node :seon.sci.eval/ctx ctx :seon.sci.admit/caps caps
                      :seon.render/profile (render/agent-render-profile (support/effective-config))
                      :seon.render.data/cursor {:seon.render.data/path path :seon.render.data/offset offset}})
           first-page (value/prepare (assoc (unit value) :seon.repl/handle 'result/e0123456789ab))
           cut (first (tree-cuts first-page))
           next-page ((requiring-resolve 'seon.render.walk/continue)
                      (request 'result/e0123456789ab (:seon.render.data/path cut) (:seon.render.data/next-offset cut)))
           next-cut (first (tree-cuts next-page))
           gone ((requiring-resolve 'seon.render.walk/continue) (request 'result/eabsent [] 0))]
       (is (= [[:rows] 32 100] ((juxt :seon.render.data/path :seon.render.data/next-offset :seon.render.data/total) cut)))
       (is (str/starts-with? (value/render-ai-data next-page) "[32 33 34"))
       (is (= [[:rows] 64 36] ((juxt :seon.render.data/path :seon.render.data/next-offset :seon.print/omitted) next-cut))
           "every expansion shows new members and keeps absolute coordinates")
       (is (= :gone (:seon.render.walk/continuation-reason gone)))))))

(deftest one-selection-bounds-both-faces-and-each-cut-expands-in-place
  ;; Astra F1 (2026-09-26): HTML selected a second tree with every bound
  ;; lifted. Both faces now come from the one selection under one B, and an
  ;; HTML cut is a link to the same continuation at its path and offset.
  (let [request (assoc (probe-unit (vec (range 3000))) :seon.render.value/route-base "/data?value=abc")
        projection (value/prepare request :seon.render/html)
        cut (last (tree-cuts projection))
        leaves (filter #(and (map? %) (= :seon.print/number (:seon.print/face %)))
                       (tree-seq coll? seq (:seon.render.value/tree projection)))
        html (hiccup/->string (:seon.render.value/html projection))]
    (is (= (:seon.render.value/tree projection) (:seon.render.value/tree (value/prepare request))))
    (is (= (:seon.render.data/next-offset cut) (count leaves)))
    (is (str/includes? html (str "offset=" (:seon.render.data/next-offset cut))) html)
    (is (not (str/includes? html ">2999<")))))

(deftest a-composite-key-cut-addresses-the-key-it-was-cut-from
  ;; Astra F2 (2026-09-26): a key's cut carried the entry ordinal as a plain
  ;; step, which `at` read as the map key 0 and so continued another value.
  (support/with-database
   (fn [connection]
     ;; the set key hides enough to be cut: a tail no costlier than its cut
     ;; is shown whole (46be90e36), as a four-member set now is
     (let [value (array-map (vec (range 50)) :x 0 :zero (set (map #(vector % %) (range 50))) :set)
           ctx (:seon.sci.eval/ctx
                (evaluation/fork-for-turn {:seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                                           :seon.db/db (db/db connection) :seon.agent/id "root"}))
           _ (evaluation/bind-result! ctx 'result/e0123456789ab value)
           cuts (tree-cuts (value/prepare (probe-unit value)))
           key-cut (first (filter #(= [[:seon.render.data/entry 0 :seon.render.data/key]] (:seon.render.data/path %)) cuts))
           set-cut (first (filter #(= [[:seon.render.data/entry 2 :seon.render.data/key]] (:seon.render.data/path %)) cuts))
           next-page ((requiring-resolve 'seon.render.walk/continue)
                      {:seon.render.walk/continuation-node 'result/e0123456789ab :seon.sci.eval/ctx ctx
                       :seon.sci.admit/caps caps :seon.render/profile probe-profile
                       :seon.render.data/cursor {:seon.render.data/path (:seon.render.data/path key-cut)
                                                 :seon.render.data/offset (:seon.render.data/next-offset key-cut)}})]
       (is (= (vec (range 50)) (print/value-at value (:seon.render.data/path key-cut))) (pr-str cuts))
       (is (= 50 (:seon.render.data/total key-cut)))
       (is (str/starts-with? (value/render-ai-data next-page) "[3 4 5") (pr-str next-page))
       (is (= 50 (count (print/value-at value (:seon.render.data/path set-cut)))) (pr-str cuts))
       (is (= :zero (print/value-at value [0])) "a plain step keeps get-in's meaning")))))

(deftest an-unaffordable-root-or-first-member-is-the-declared-refusal
  ;; Astra F4 (2026-09-26): the root was installed unconditionally and a page
  ;; whose first member could not fit repeated its own offset forever.
  (let [at-budget (fn [tokens raw]
                    (value/prepare (assoc (probe-unit raw) :seon.render/profile
                                          (assoc probe-profile :seon.render.profile/token-budget tokens))))
        valid? (schema/projection-validator (schema/handed-projection)
                                            :seon.render.value/insufficient-floor-budget-error)
        root (at-budget 1 "abcdefghijklmnop")
        ;; 8 tokens (25 chars) holds the root with its cut and continuation
        ;; (25) but not its first member too (31): the member refusal.
        member (at-budget 8 {:a 1 :b 2})]
    (is (valid? root) (pr-str root))
    (is (= [(tokens/estimate-chars 1) 18] ((juxt :seon.render.value/budget :seon.render.value/needed) root)))
    (is (valid? member) (pr-str member))
    (is (< (:seon.render.value/budget member) (:seon.render.value/needed member)))
    (is (= "42" (:seon.render.value/text (at-budget 1 42))) "a root that fits whole prints whole")))

(deftest a-stored-value-continues-over-the-backing-its-first-page-selected
  ;; Astra F3 (2026-09-26): MCP selected the held value but continued over
  ;; the stored artifact, whose sorted map reads back as a hash map in another
  ;; order. The one owner continues the artifact the first page selected.
  (support/with-database
   (fn [connection]
     (let [original {:t (into (sorted-map) (map (fn [i] [i (str i)])) (range 100))}
           artifact (value/artifact (admit/admit {:seon.sci.admit/value original :seon.sci.admit/interrupt-fn (fn [])
                                                  :seon.sci.admit/caps caps}))
           digest ((requiring-resolve 'seon.blob/put!) connection (value/artifact-edn artifact))
           backing (value/artifact-value artifact)
           request (fn [path offset]
                     {:seon.render.walk/continuation-node [:seon.blob/digest digest] :seon.db/connection connection
                      :seon.sci.admit/caps caps :seon.render/profile probe-profile
                      :seon.render.data/cursor {:seon.render.data/path path :seon.render.data/offset offset}})
           first-page (value/prepare (assoc (probe-unit backing) :seon.render.value/root [:seon.blob/digest digest]))
           cut (first (filter #(= [:t] (:seon.render.data/path %)) (tree-cuts first-page)))
           next-page ((requiring-resolve 'seon.render.walk/continue) (request [:t] (:seon.render.data/next-offset cut)))
           expected (key (nth (seq (:t backing)) (:seon.render.data/next-offset cut)))]
       (is (str/starts-with? (value/render-ai-data next-page) (str "{" expected " ")) (pr-str next-page))
       (is (= :unavailable (:seon.render.walk/continuation-reason
                            ((requiring-resolve 'seon.render.walk/continue)
                             (dissoc (request [:t] 0) :seon.db/connection)))))))))

(deftest every-cut-prints-the-plain-clojure-form-that-reads-its-rest
  ;; Owner 2026-09-27: each elision carries its exact continuation, copyable,
  ;; and evaluating it answers the omitted rest; the whole value's size is
  ;; estimated from the cuts, never by printing it; seon.print/full opts one
  ;; value out of every bound.
  (let [raw (vec (range 100))
        projection (value/prepare (probe-unit raw))
        text (:seon.render.value/text projection)
        cut (first (tree-cuts projection))
        form (:seon.print/continuation cut)]
    (is (= '(drop 3 result/e0123456789ab) form))
    (is (str/includes? text "…97 (drop 3 result/e0123456789ab)") text)
    (is (= (drop 3 raw) (eval (map #(if (= 'result/e0123456789ab %) raw %) form)))
        "evaluating the continuation answers exactly the omitted rest")
    (is (= 1 (:seon.render.value/elisions projection)))
    (is (< (count (first (str/split-lines text))) (:seon.render.value/whole-characters projection))
        "the whole estimate exceeds what was shown, without printing the value")
    (clojure.test/testing "a string cut continues with subs from its next offset"
      (let [cut (first (tree-cuts (value/prepare (probe-unit {:body (apply str (repeat 5000 "ab"))}))))]
        (is (= 'subs (first (:seon.print/continuation cut))))
        (is (= '(get-in result/e0123456789ab [:body]) (second (:seon.print/continuation cut))))))
    (clojure.test/testing "the root's own cut is a valid continuation for a seq, a string and a map (live 2026-09-27: the armed contract refused the bare handle)"
      (is (= 'result/e0123456789ab
             (print/continuation {:seon.print/requery-id 'result/e0123456789ab :seon.print/elision-unit :children
                                  :seon.render.data/path [] :seon.render.data/next-offset 0}))
          "a whole collection at the root is read by its handle")
      (is (str/includes? (value/render-ai (probe-unit (vec (range 3000)))) "…2997 (drop 3 result/e0123456789ab)]"))
      (is (str/includes? (value/render-ai (probe-unit (apply str (repeat 5000 "ab"))))
                         " (subs result/e0123456789ab "))
      (let [text (value/render-ai (probe-unit (into (sorted-map) (map (fn [i] [i i])) (range 10))))]
        (is (str/includes? text "…7 result/e0123456789ab}") text)
        (is (not (str/includes? text "renderer failed")) text)))
    (clojure.test/testing "seon.print/full prints the value whole and says so"
      (let [whole (value/prepare (probe-unit (print/full raw)))]
        (is (:seon.render.value/full? whole))
        (is (zero? (:seon.render.value/elisions whole)))
        (is (str/includes? (:seon.render.value/text whole) " 99]"))))))

(deftest a-renderer-that-throws-still-prints-through-the-floor-and-says-large-value
  ;; Owner 2026-09-27: a failed render falls back to the floor, still bounded
  ;; at the value budget, and keeps the floor's whole-size estimate, so the
  ;; reply shows the bounded print, one renderer-failed diagnostic and the
  ;; :large-value finding in the one reply map, and the evaluation records whole
  ;; and shown tokens.
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "large-render" {})
     (let [ctx (support/fork-cluster-ctx connection)
           handle 'result/elargerender1
           source (str "{:seon.render/ai (fn [_] (throw (ex-info \"boom\" {})))"
                       " :rows (mapv (fn [i] {:id i :title \"a title that takes some room\"}) (range 300))}")
           evaluation (evaluation/evaluate
                       {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                        :seon.db/connection connection :seon.agent/id "large-render"
                        :seon.cluster.eval/source source :seon.repl/handle handle
                        :seon.sci.admit/caps (config/result-caps config/defaults)
                        :seon.sci.eval/time-limit-ms 10000})
           shown (:seon.eval/shown evaluation)
           reply (repl/response (assoc (select-keys evaluation [:seon.eval/shown :seon.eval/value-tokens
                                                                 :seon.eval/shown-tokens :seon.eval/duration-ms
                                                                 :seon.cluster.eval/diagnostics])
                                       :seon.cluster.eval/source source :seon.repl/handle handle))]
       (is (not (:seon.cluster.eval/error evaluation)) (pr-str (dissoc evaluation :seon.sci.admit/value)))
       (is (< 1000 (:seon.eval/value-tokens evaluation)) "the whole value's estimate is recorded")
       (is (<= (:seon.eval/shown-tokens evaluation) 1100) "the print stays within the value budget")
       (is (str/includes? shown "(drop 32 (get-in result/elargerender1 [:rows]))") shown)
       ;; The diagnostic holds the renderer's refusal as its offending value
       ;; (which has its own :seon.error/at), so count the render-layer entry.
       (is (= 1 (count (re-seq #":seon.error/layer :seon.render/invocation"
                               (str (:seon.cluster.eval/diagnostics evaluation)))))
           "one renderer-failed diagnostic, beside the value")
       (is (str/starts-with? reply "#:seon.repl{") "the reply is one map, nothing above it")
       (is (= :large-value
              (get-in (binding [*read-eval* false *default-data-reader-fn* tagged-literal] (read-string reply)) [:seon.repl/findings 0 :seon.repl/finding]))
           reply)))))
