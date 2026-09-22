(ns seon.render-coverage-test
  "Focused coverage for important root-runtime and effect receipt faces."
  (:require [seon.schema.internal] [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.ai.tokens :as tokens]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.turn :as turn]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.instrument :as instrument]
            [seon.repl :as repl]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.render.walk :as walk]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]
            [sci.core :as sci])
  (:import [java.util Date]))

(def ^:private cluster-name "render-coverage")
(def ^:private agent-id "render-coverage-agent")
(def ^:private run-id "render-coverage-run")
(def ^:private owner-symbol 'my.fs/read)
(def ^:private opened-at (Date. 1000))
(def ^:private settled-at (Date. 1012))
(def ^:private interrupted-at (Date. 1015))
(def ^:private request-edn "#:my.fs{:path \"README.md\"}")
(def ^:private result-edn "#:my.fs{:content \"rendered content\"}")
(def ^:private blob-digest (apply str (repeat 64 "a")))
(def ^:private caps (config/result-caps config/defaults))

(defn- render-request
  [database ctx value]
  {:seon.db/db database
   :seon.sci.eval/ctx ctx
   :seon.render/value value
   :seon.sci.admit/caps caps
   :seon.sci.eval/time-limit-ms 2000
   :seon.config/on-core-error :panic})

(defn- family-properties
  [schema-key]
  (-> (schema.edn/packaged-forms)
      (get schema-key)
      (comp seon.schema.internal/entity-properties seon.schema/structural-schema)))

(defn- card?
  [css-class value]
  (and (= :article (first value))
       (= css-class (get-in value [1 :class]))))

(defn- one-to-three-lines?
  [text]
  (<= 1 (count (str/split-lines text)) 3))

(defn- seed-entities!
  {:malli/schema [:=> [:cat :seon.db/connection] :nil]}
  [connection]
  (config/apply-compiled!
   connection
   (config/compile-manifest {:seon.boot/cluster-name cluster-name}))
  (cluster/ensure-cluster-entity!
   connection cluster-name cluster/boot-process-identity)
  ;; ASSERT THE TRANSACTION REPORT. `seon.db/write-error` validates every map
  ;; keyed by an identity attribute against that attribute's entity schema, so
  ;; one inadmissible map refuses the WHOLE seed and every later assertion then
  ;; reads absence as render behaviour: a bare `{:seon.fn/sym owner-symbol}`
  ;; (missing the required `:seon.schema.admission/source`,
  ;; `resources/seon/schemas/seon.fn.edn:92`) and a turn row without its
  ;; required `:seon.turn/opened-tx` refused this seed entirely, and the 23
  ;; resulting reds read as a value-renderer defect. `my.fs/read` already IS a
  ;; declaration in the canonical population, so the row is deleted rather than
  ;; repaired — the fixture reuses the real one.
  ;; TWO transactions, because the effect rows reference the turn by lookup
  ;; ref and Datahike resolves a lookup ref against the database the
  ;; transaction STARTS from — an entity minted in the same transaction is
  ;; `:entity-id/missing`.
  (let [written!
        (fn [tx]
          (let [report (db/transact! connection tx)]
            (is (some? (:db-after report)) (pr-str report))
            report))]
    (written!
     (into
      (agent/creation-tx
       {:seon.agent/id agent-id
        :seon.cluster/name cluster-name
        :seon.ns/name 'my.agents.render-coverage})
      [{:seon.turn/id run-id
        :seon.turn/agent [:seon.agent/id agent-id]
        :seon.turn/opened-tx "datomic.tx"}]))
    (written!
     [{:seon.effect/id "effect-pending"
       :seon.effect/run [:seon.turn/id run-id]
       :seon.effect/owner [:seon.fn/sym owner-symbol]
       :seon.effect/form-ordinal 3
       :seon.effect/ordinal 0
       :seon.effect/request-edn request-edn
       :seon.effect/opened-at opened-at}
      {:seon.effect/id "effect-returned"
       :seon.effect/run [:seon.turn/id run-id]
       :seon.effect/owner [:seon.fn/sym owner-symbol]
       :seon.effect/form-ordinal 3
       :seon.effect/ordinal 1
       :seon.effect/request-edn request-edn
       :seon.effect/opened-at opened-at
       :seon.effect/result-edn result-edn
       :seon.effect/result-blob blob-digest
       :seon.effect/result-size 99
       :seon.effect/duration-ms 12
       :seon.effect/settled-at settled-at}
      {:seon.effect/id "effect-interrupted"
       :seon.effect/run [:seon.turn/id run-id]
       :seon.effect/owner [:seon.fn/sym owner-symbol]
       :seon.effect/form-ordinal 3
       :seon.effect/ordinal 2
       :seon.effect/request-edn request-edn
       :seon.effect/opened-at opened-at
       :seon.effect/interrupted-at interrupted-at}]))
  nil)

(defn- pulled
  [database lookup]
  (db/pull database '[*] lookup))

(deftest render-without-a-carried-profile-or-projection-refuses
  (let [world (atom nil)]
    (support/with-database
     (fn [connection]
       (seed-entities! connection)
       (let [database @connection]
         (reset! world
                 {:database database
                  :ctx (support/fork-cluster-ctx connection)
                  :value (pulled database
                                 [:seon.config/cluster cluster-name])}))))
    (let [{:keys [database ctx value]} @world
          result (with-redefs [schema/handed-projection (constantly nil)]
                   (render/render-ai (render-request database ctx value)))]
      (is (some? (:seon.render/refused-member result)))
      (is (= 'seon.render/request-profile
             (get-in result
                     [:seon.error/data
                      :seon.error/diagnostic-operation]))))))

(deftest only-agent-context-renders-prepare-cost-facts-for-the-caller
  (support/with-database
   (fn [connection]
     (seed-entities! connection)
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           value (pulled database [:seon.config/cluster cluster-name])
           profile (render/agent-render-profile config/defaults)
           web-call-id [:web :config]
           web-captured (atom {})
           before-web (db/basis-t database)
           web-output
           (render/render-call
            (assoc (render-request database ctx value)
                   :seon.db/connection connection
                   :seon.render/output :seon.render/html
                   :seon.render/profile profile
                   :seon.render.call/id web-call-id
                   :seon.render/captured-calls web-captured))
           after-web (db/basis-t @connection)
           web-fact-count
           (count
            (db/q '[:find ?cost
                    :where
                    [?cost :seon.render.cost/estimated-tokens]]
                  @connection))
           agent-call-id [:agent-context :config]
           agent-captured (atom {})
           agent-output
           (render/render-call
            (assoc (render-request database ctx value)
                   :seon.db/connection connection
                   :seon.turn/id run-id
                   :seon.render/output :seon.render/ai
                   :seon.render/profile profile
                   :seon.render.call/id agent-call-id
                   :seon.render/captured-calls agent-captured))
           after-agent (db/basis-t @connection)
           prepared (get-in @agent-captured [agent-call-id :seon.db/tx-data])
           settled (db/transact! connection prepared)
           facts (db/q '[:find ?shape ?profile ?tokens ?at
                         :where
                         [?cost :seon.render.cost/shape-key ?shape]
                         [?cost :seon.render.cost/profile ?profile]
                         [?cost :seon.render.cost/estimated-tokens ?tokens]
                         [?cost :seon.render.cost/at ?at]]
                       @connection)]
       (is (vector? web-output))
       (is (= #{web-call-id} (set (keys @web-captured))))
       (is (= before-web after-web)
           "a web-like HTML render retains its call without writing")
       (is (zero? web-fact-count))
       (is (string? agent-output))
       (is (= #{agent-call-id} (set (keys @agent-captured))))
       (is (= after-web after-agent)
           "rendering prepares costs without moving the caller's database basis")
       (is (= 1 (count prepared)))
       (is (some? (:db-after settled)) (pr-str settled))
       (is (= 1 (count facts)))
       (let [[shape profile estimated at] (first facts)]
         (is (= :seon.config/entity shape))
         (is (= :seon.render.profile/agent profile))
         (is (= (tokens/estimate agent-output) estimated))
         (is (inst? at)))))))

(defn- walk-output-by-attribute
  [units attribute]
  (:seon.render/output
   (some #(when (= attribute (:seon.render.walk/attribute %)) %) units)))

(deftest important-runtime-entities-declare-and-use-readable-faces
  (is (= {:seon.render/ai `agent/render-identity-ai
          :seon.render/html `agent/render-identity-html}
         (select-keys (family-properties :seon.agent/agent)
                      [:seon.render/ai :seon.render/html])))
  (is (= {:seon.render/ai `agent/render-creation-ai
          :seon.render/html `agent/render-creation-html}
         (select-keys
          (family-properties :seon.agent/creation-result)
          [:seon.render/ai :seon.render/html])))
  (is (= {:seon.render/ai `repl/render-ai
          :seon.render/html `repl/render-html}
         (select-keys (family-properties :seon.eval/entity)
                      [:seon.render/ai :seon.render/html])))
  (is (= {:seon.render/ai `cluster/render-ai
          :seon.render/html `cluster/render-html}
         (select-keys (family-properties :seon.cluster/cluster)
                      [:seon.render/ai :seon.render/html])))
  (is (= {:seon.render/ai `config/render-ai
          :seon.render/html `config/render-html}
         (select-keys (family-properties :seon.config/entity)
                      [:seon.render/ai :seon.render/html])))
  (support/with-database
   (fn [connection]
     (seed-entities! connection)
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           cluster-value (pulled database [:seon.cluster/name cluster-name])
           config-value (pulled database [:seon.config/cluster cluster-name])
           cases [{:value cluster-value
                   :direct-ai cluster/render-ai
                   :direct-html cluster/render-html
                   :class "seon-family-entry seon-cluster-entry"}
                  {:value config-value
                   :direct-ai config/render-ai
                   :direct-html config/render-html
                   :class "seon-family-entry seon-config-entry"}]]
       (doseq [{:keys [value direct-ai direct-html class]} cases]
         (let [request (render-request database ctx value)
               ai (render/render-ai request)
               html (render/render-html request)]
           (is (= (direct-ai (assoc value :seon.db/db database)) ai))
           (is (= (direct-html (assoc value :seon.db/db database)) html))
           (is (one-to-three-lines? ai))
           (is (card? class html))
           (is (not (str/includes? ai ":db/id")))
           (is (not (str/includes? (hiccup/->string html) ":db/id")))))
       (let [config-ai (config/render-ai (assoc config-value
                                                 :seon.db/db database))
             model-value
             (pulled database
                     [:seon.ai.model/id (:seon.config.ai/model config-value)])
             model-ai (render/render-ai
                       (render-request database ctx model-value))]
         (is (not (str/includes? config-ai "DEEPSEEK_API_KEY")))
         (is (not (str/includes? config-ai
                                 "https://api.deepseek.com")))
         (is (not (str/includes? config-ai "Available models"))
             "the recurring config face does not inline the model roster")
         (is (str/includes? model-ai
                            (str "Model " (:seon.config.ai/model config-value)))
             "the configured model remains reachable through its own face"))
       (doseq [output [:seon.render/ai :seon.render/html]]
         (let [units (walk/neighborhood
                      {:seon.db/db database
                       :seon.sci.eval/ctx ctx
                       :seon.render.walk/lookup
                       [:seon.agent/id agent-id]
                       :seon.render/output output
                       :seon.sci.admit/caps caps
                       :seon.sci.eval/time-limit-ms 2000
                       :seon.config/on-core-error :panic
                       :seon.render/distance 2})]
           (is (seq units))
           (is (nil? (walk-output-by-attribute units :seon.agent/cluster))
               "the branch is not a stored connection on the agent")))))))

(deftest effect-receipts-render-state-from-attribute-presence
  (is (= {:seon.render/ai `effect/render-ai
          :seon.render/html `effect/render-html}
         (select-keys (family-properties :seon.effect/receipt)
                      [:seon.render/ai :seon.render/html])))
  (support/with-database
   (fn [connection]
     (seed-entities! connection)
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           pending (pulled database [:seon.effect/id "effect-pending"])
           returned (pulled database [:seon.effect/id "effect-returned"])
           interrupted (pulled database [:seon.effect/id
                                          "effect-interrupted"])
           faces
           (into {}
                 (map
                  (fn [[state value]]
                    (let [request (render-request database ctx value)]
                      [state {:ai (render/render-ai request)
                              :html (render/render-html request)}])))
                 {:pending pending
                  :returned returned
                  :interrupted interrupted})]
       (doseq [[_ {:keys [ai html]}] faces]
         (is (one-to-three-lines? ai))
         (is (card? "seon-family-entry seon-effect-receipt-entry" html))
         (is (str/includes? ai (str owner-symbol)))
         (is (str/includes? ai run-id))
         (is (not (str/includes? ai ":db/id"))))
       (let [pending-ai (get-in faces [:pending :ai])
             pending-html (hiccup/->string (get-in faces [:pending :html]))
             returned-ai (get-in faces [:returned :ai])
             returned-html (hiccup/->string (get-in faces [:returned :html]))
             interrupted-ai (get-in faces [:interrupted :ai])
             interrupted-html
             (hiccup/->string (get-in faces [:interrupted :html]))]
         (testing "pending shows its request and omits terminal fields"
           (is (str/includes? pending-ai "Request"))
           (is (str/includes? pending-ai
                              (str "~" (tokens/estimate request-edn)
                                   " tokens")))
           (is (not (str/includes? pending-ai "Result")))
           (is (not (str/includes? pending-html "seon-effect-result")))
           (is (not (str/includes? pending-html "Duration"))))
         (testing "returned shows result, duration, and blob handle"
           (is (str/includes? returned-ai "Result"))
           (is (str/includes? returned-ai
                              (str "~" (tokens/estimate result-edn)
                                   " tokens")))
           (is (str/includes? returned-html "Duration"))
           (is (str/includes? returned-html blob-digest))
           (is (not (str/includes? returned-ai "bytes")))
           (is (not (str/includes? returned-ai "chars"))))
         (testing "interrupted shows the interruption and no result"
           (is (str/includes? interrupted-ai "interrupted"))
           (is (not (str/includes? interrupted-ai "Result")))
           (is (not (str/includes? interrupted-html "seon-effect-result")))
           (is (not (str/includes? interrupted-html "Duration")))))
       ;; THE WALK SELECTS THE SAME PAIR. Rooted at the receipt, because the
       ;; agent's declared `:seon.render/units`
       ;; (`resources/seon/schemas/seon.agent.edn:5`) are plan, issues, inbox,
       ;; settings, notes, namespace, runtime and steward errors — no turn and
       ;; no effect. This clause previously walked from the agent at distance
       ;; 2 and asserted an `:seon.effect/run` unit that the declared record
       ;; cannot produce; it read as green only while the whole seed was
       ;; refused and every render fell to one refusal. Nothing declares
       ;; effect receipts as units of anything today — recorded in the landing
       ;; note rather than invented here.
       (doseq [output [:seon.render/ai :seon.render/html]]
         (let [units (walk/neighborhood
                      {:seon.db/db database
                       :seon.sci.eval/ctx ctx
                       :seon.render.walk/lookup
                       [:seon.effect/id "effect-returned"]
                       :seon.render/output output
                       :seon.sci.admit/caps caps
                       :seon.sci.eval/time-limit-ms 2000
                       :seon.config/on-core-error :panic
                       :seon.render/distance 1})
               face (:seon.render/output (first units))]
           (is (some? face))
           (is (str/includes? (str face) (str owner-symbol)))
           (is (vector? face)
               "the walk's own request shape reaches a total render")))))))

;;; ---------------------------------------------------------------------------
;;; A producer that delegates its own value never re-selects itself
;;; ---------------------------------------------------------------------------

(deftest a-producer-that-delegates-its-own-value-is-never-re-entered
  ;; THE CLASS: a declared producer may render its value THROUGH another
  ;; producer — `seon.ai/attempt-html` hands the attempt, minus reasoning,
  ;; to the value floor `seon.render.value/render-html`. The floor projects
  ;; that value, selection answers `seon.ai/attempt-html` again, and the
  ;; chain never returns. Measured 2026-08-07 in
  ;; `seon.render.web-test/thinking-stream-morphs-into-the-settled-session-transcript`:
  ;; the render proc's virtual thread sat past 1024 frames of
  ;; project-node → attempt-html → prepare → project-node, so its transform
  ;; never ended, its `::flow/stop` transition never ran, and the completion
  ;; `disarm-agents!` joins before releasing the branch connection never
  ;; arrived. `invoke-selected` now records what it is running and
  ;; `project-node` refuses a producer already on the chain, so the cycle
  ;; cannot be built.
  ;;
  ;; The unguarded code does not fail here, it never returns — so the
  ;; oracle is the shared loud backstop around the render, and the
  ;; assertions read the value the walk did produce.
  (support/with-database
   (fn [connection]
     (seed-entities! connection)
     (db/transact!
      connection
      [{:seon.ai.attempt/id "re-entrance-attempt"
        :seon.turn/_attempts [:seon.turn/id run-id]
        :seon.ai.attempt/ordinal 0
        :seon.ai.attempt/at opened-at
        :seon.ai/endpoint "https://provider.invalid"
        :seon.ai/model "fixture-attempt"
        :seon.ai.attempt/settings-edn "{}"
        :seon.ai.attempt/reasoning "private provider reasoning"}])
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           attempt (pulled database [:seon.ai.attempt/id "re-entrance-attempt"])
           request (render-request database ctx attempt)
           html (support/await-event!
                 (future (render/render-html request))
                 [:attempt-html-returns])
           ai (support/await-event!
               (future (render/render-ai request))
               [:attempt-ai-returns])]
       (is (hiccup/hiccup? html)
           "the delegating producer returns hiccup rather than recursing")
       (is (string? ai))
       (is (str/includes? (hiccup/->string html) "fixture-attempt")
           "the attempt's ordinary facts still reach the page")
       (is (not (str/includes? (hiccup/->string html)
                               "private provider reasoning"))
           "reasoning keeps its own disclosure")
       (is (not (str/includes? ai "private provider reasoning")))))))

;;; ---------------------------------------------------------------------------
;;; A refused render producer contributes a stable typed unknown
;;; ---------------------------------------------------------------------------

(def ^:private probe-source
  (str "(ns my.render-probe)"
       " (defn steady [_] \"steady bytes\")"
       " (defn slow [_] (loop [n 0] (if (< n 100000000000) (recur (inc n)) \"never\")))"
       " (defn boom [_] (/ 1 0))"
       " (defn contracted [_] \"contract bytes\")"))

(def ^:private probe-producers
  '[my.render-probe/steady my.render-probe/slow
    my.render-probe/boom my.render-probe/contracted])

(defn- probe-ctx
  "Fork one cluster context carrying the four probe producers.

  The fork is this test's own — `mark-installed!` says these Vars need no
  database row, exactly as an already-installed function needs none — so
  nothing here touches the shared worker JVM's program state."
  [connection]
  (let [ctx (support/fork-cluster-ctx connection)]
    (sci/eval-string* ctx probe-source)
    (doseq [probe probe-producers]
      (kernel/mark-installed! ctx probe))
    ;; ONE CONTRACT, ARMED. `:record` instruments nothing, so a contract
    ;; refusal is only observable under the dial that raises it — the same
    ;; call `seon.sci.eval/install-function-contract!` makes for a program row.
    (let [contracted (sci/resolve ctx 'my.render-probe/contracted)]
      (sci/bind-root!
       ctx contracted
       (instrument/wrap-interpreted 'my.render-probe/contracted
                                    "[:=> [:cat :string] :string]"
                                    (kernel/context-projection ctx)
                                    :panic caps @contracted)))
    ctx))

(def ^:private probe-call-id
  [:seon.render/ai [:seon.agent/id agent-id] 1])

(defn- probe-render
  [database ctx value producer limit-ms]
  (render/render-ai (assoc (render-request database ctx value)
                           :seon.render/ai producer
                           :seon.render.call/id probe-call-id
                           :seon.sci.eval/time-limit-ms limit-ms)))

(deftest a-refused-render-producer-contributes-a-stable-typed-unknown
  ;; THE CLASS: every producer runs under `:seon.sci.eval/time-limit-ms`, and
  ;; a refusal used to contribute ABSENCE — the walk kept a unit only when its
  ;; output was a non-empty string with no error. So a slow machine silently
  ;; moved the prompt bytes a fast one produced, which is why "same database
  ;; value, same adopted commit, same profile ⇒ same bytes" was unreachable
  ;; (PRD §5, review B5), and it was a standing §2.4 violation besides: an
  ;; unavailable observation is the typed unknown, never absence.
  (support/with-database
   (fn [connection]
     (seed-entities! connection)
     (let [database @connection
           ctx (probe-ctx connection)
           value (pulled database [:seon.agent/id agent-id])]
       (testing "a producer that runs past the limit names itself and the bound"
         (let [refused (probe-render database ctx value
                                     'my.render-probe/slow 50)]
           (is (some? (:seon.render.unknown/reason refused)))
           (is (= :time-limit (:seon.render.unknown/reason refused)))
           (is (= 'my.render-probe/slow
                  (:seon.render.unknown/producer refused)))
           (is (= probe-call-id (:seon.render.unknown/call refused)))
           (is (= :seon.render/ai (:seon.render.unknown/output refused)))
           (is (= :seon.sci.kernel/time-limit
                  (:seon.render.unknown/refusal refused)))))

       (testing "a producer that throws carries the throwable's class"
         (let [refused (probe-render database ctx value
                                     'my.render-probe/boom 2000)]
           (is (= :refused (:seon.render.unknown/reason refused)))
           (is (= 'my.render-probe/boom
                  (:seon.render.unknown/producer refused)))
           (is (= :seon.sci.kernel/invocation-failed
                  (:seon.render.unknown/refusal refused)))
           (is (string? (:seon.render.unknown/throwable refused)))))

       (testing "a producer whose declared contract refuses carries it"
         (let [refused (probe-render database ctx value
                                     'my.render-probe/contracted 2000)]
           (is (= :refused (:seon.render.unknown/reason refused)))
           (is (= 'my.render-probe/contracted
                  (:seon.render.unknown/producer refused)))
           (is (= :seon.instrument/contract-violated
                  (:seon.render.unknown/refusal refused))
               "the contract's own refusal survives the render boundary")))

       (testing "the unknown reads as ONE line of data the agent can act on"
         (let [refused (probe-render database ctx value
                                     'my.render-probe/slow 50)
               line (render/unknown-output :seon.render/ai refused)]
           (is (= 1 (count (str/split-lines line))))
           (is (not (str/starts-with? (str/triml line) ";"))
               "never comment-shaped (ruling 45)")
           (is (str/includes? line "my.render-probe/slow"))
           (is (str/includes? line ":time-limit"))))

       (testing "and as one labeled block for a person"
         (let [refused (probe-render database ctx value
                                     'my.render-probe/boom 2000)
               block (render/unknown-output :seon.render/html refused)
               text (hiccup/->string block)]
           (is (hiccup/hiccup? block))
           (is (str/includes? text "renderer unavailable"))
           (is (str/includes? text "my.render-probe/boom"))))

       (testing "a producer that RETURNS an error value is not a refusal"
         (let [returned (probe-render database ctx value
                                      'my.render-probe/steady 2000)]
           (is (= "steady bytes" returned))))))))

(deftest one-refused-producer-moves-no-other-rendered-bytes
  ;; The bound must be able to fire without moving a byte. Two passes with
  ;; DIFFERENT limits make the refused producer run for measurably different
  ;; wall-clock times; the working producer's bytes, and the refusal's own
  ;; line, must be identical across both — which holds only because the typed
  ;; unknown carries nothing from the kernel's diagnostic record.
  (support/with-database
   (fn [connection]
     (seed-entities! connection)
     (let [database @connection
           ctx (probe-ctx connection)
           value (pulled database [:seon.agent/id agent-id])
           pass (fn [limit-ms]
                  {:steady (probe-render database ctx value
                                         'my.render-probe/steady 2000)
                   :refused (render/unknown-output
                             :seon.render/ai
                             (probe-render database ctx value
                                           'my.render-probe/slow limit-ms))})
           first-pass (pass 50)
           second-pass (pass 150)]
       (is (= (:steady first-pass) (:steady second-pass))
           "the unrefused unit's bytes do not move")
       (is (= (:refused first-pass) (:refused second-pass))
           "and the refusal's own bytes do not carry the clock")))))
