(ns seon.ai.typeahead-test
  "Offline tests for the typeahead step-loop provider (`seon.ai.typeahead`).

   NO network, NO model — the loop tests drive the provider through the
   injected `seon.ai.diffusiongemma/*fetch*` seam (the same HTTP-boundary
   fake the diffusiongemma adapter tests use) with scripted mode=step
   responses, against a fresh hermetic `:memory` conn (the menu-test
   fixture pattern).

   Covers:
     - offers->wire / policy->wire (pure shape; the worst-token-gate
       unit-mismatch exclusion)
     - assemble-reply (locked forms + honest unfinished tail)
     - step-projection (readout scalars in, optional-is-absent out)
     - the step LOOP: committed/draft threading across scripted steps,
       reply assembly from locked forms, per-step datom projections
       recorded under the agent scope (incl. the additive observability
       fields), NO block self-install (owner constraint — the
       `:typeahead-steps` block is explicit-install only; its render
       tests live in seon.agent.ctx.typeahead-steps-test), and a keyless
       LOCAL (full-URL) endpoint working

   Run interactively via MCP eval:
     (require 'seon.ai.typeahead-test :reload)
     (cljs.test/run-tests 'seon.ai.typeahead-test)"
  (:require
    [cljs.reader :as reader]
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [my.plan :as plan]
    [seon.agent.ctx.menu :as menu]
    [seon.ai.diffusiongemma :as dg]
    [seon.ai.typeahead :as ta]
    [seon.client :as client]
    [seon.db :as db]))

(def ^:private a-id "typeaheadtestA")   ; 14 chars — the :seon.db/id shape

;; ============================================================
;; Fixtures — env, fetch seam, hermetic conn (menu-test pattern).
;; ============================================================

(defn- with-env
  "Run `body` (0-arg → Promise) with process.env vars set/deleted per
   `settings`; snapshot + restore each touched var after."
  [settings body]
  (let [env   (.. js/process -env)
        saved (into {} (map (fn [[k _]] [k (aget env k)])) settings)]
    (doseq [[k v] settings]
      (if (some? v) (aset env k v) (js-delete env k)))
    (-> (js/Promise.resolve (body))
        (.finally (fn []
                    (doseq [[k _] settings]
                      (let [v (get saved k)]
                        (if (some? v) (aset env k v) (js-delete env k)))))))))

(defn- json-response
  "A js/Response carrying `m` as a JSON body at `status`."
  [status m]
  (js/Response.
    (.stringify js/JSON (clj->js m))
    #js{:status  status
        :headers #js{"content-type" "application/json"}}))

(defn- scripted-fetch
  "A fetch stub returning `responses` in call order, recording each
   `{:url … :payload …}` (the parsed JSON `input` payload) into `calls`."
  [calls responses]
  (let [n (atom -1)]
    (fn [url init]
      (let [body (some-> init (aget "body"))
            payload (when body
                      (-> (.parse js/JSON body)
                          (js->clj :keywordize-keys false)
                          (get "input")))]
        (swap! calls conj {:url url :payload payload :signal (.-signal init)}))
      (js/Promise.resolve (nth responses (swap! n inc))))))

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema + one agent."
  []
  (-> (client/open-agent-conn!)
      (.then
        (fn [conn]
          (-> (db/with-tx-context
                {:seon.db/user [:seon.agent/id "root"]
                 :seon.db/process
                 [:seon.db.process/id :seon.db.process/boot]}
                (fn []
                  (db/transact! {:seon.db/conn conn
                                 :seon.db/tx-data
                                 [{:seon.agent/id a-id}]})))
              (.then (fn [env]
                       (when-not (:seon.db/ok? env)
                         (throw (ex-info "typeahead fixture seed failed" env)))
                       conn)))))))

(defn- with-conn
  "Fresh seeded conn set! as the root db/*conn* for `body` (conn → Promise),
   prior root restored after."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- with-local-worker
  "Bind a KEYLESS local (full-URL) worker env + the scripted fetch, run
   `body` (0-arg → Promise), restore everything. Proves the local-worker
   no-bearer-key path along the way."
  [stub-fetch body]
  (with-env {"SEON_DG_ENDPOINT"    "http://127.0.0.1:17999"
             "DIFFGEMMA_EP"        nil
             "RUNPOD_API_KEY"      nil
             "SEON_DG_API_KEY_ENV" nil}
    (fn []
      (set! dg/*fetch* stub-fetch)
      (-> (js/Promise.resolve (body))
          (.finally (fn [] (set! dg/*fetch* nil)))))))

(defn- step-response
  "A terminal COMPLETED /run response carrying one mode=step output."
  [output]
  (json-response 200 {:id "j1" :status "COMPLETED" :output output}))

;; ============================================================
;; Pure shape.
;; ============================================================

(deftest offers->wire-string-keyed
  (let [wire (ta/offers->wire
               [{:seon.typeahead/glyph    "①"
                 :seon.typeahead/label    "seon.db/query [req] …"
                 :seon.typeahead/template [["clamp" "(seon.db/query "]
                                           ["free" 24]
                                           ["clamp" ")"]]}])]
    (is (= 1 (count wire)))
    (is (= "①" (get (first wire) "glyph")) "string-keyed wire map")
    (is (= [["clamp" "(seon.db/query "] ["free" 24] ["clamp" ")"]]
           (get (first wire) "template"))
        "clamp/free template rides as the worker's segment tuples")))

(deftest policy->wire-known-knobs-only
  (let [wire (ta/policy->wire menu/default-policy)]
    (is (= 3.0 (get wire "auto_offer_margin")))
    (is (= 3   (get wire "probe_lengths"))  "probe-budget → probe_lengths")
    (is (= 8   (get wire "glyph_page_size")) "menu-cap → glyph_page_size")
    (is (= 8   (get wire "max_rounds")))
    (is (not (contains? wire "worst_entropy_gate"))
        "worst-token-gate (a probability) never maps onto the nats gate")))

(def ^:private rendered-prompt
  ;; The transcript shape the live renderer produces: masthead teaching,
  ;; message/eval event log, readline (status + `ns=>` cursor), brackets.
  (str ";;; ┌─ plan ─\n"
       "; PLAN «the task»\n"
       "; ▶ stepid00000001 [2026-07-11 00:00] the task\n"
       ";;; └─ end plan ─\n\n"
       ";;; ┌─ function-menu ─\n"
       "; ① (db/query [req] …) — Run a Datalog query.\n"
       ";;; └─ end function-menu ─\n\n"
       ";;; ┌─ transcript ─\n"
       "; seon · my.agent.X · live REPL\n"
       "; The flat, time-ordered log below is this REPL's history.\n\n"
       ";;; ◀ from user @ 20:13:32 [m1] — \"warmup\"\n"
       "(message/user \"ready\") ⟹ {:ok true} ⟸ result/r1\n"
       ";;; ▶ to user @ 20:13:36 [m2] — \"ready\"\n"
       ";;; ◀ from user @ 20:13:41 [m3] (NEW — unanswered; respond to this) — \"the task\"\n\n"
       "; my.agent.X · turn 2 · loop 0/20 · running · now · agent X\n"
       "my.agent.X=> \n"
       ";;; └─ end transcript ─"))

(deftest null-render-drops-event-log-keeps-scaffolding
  (let [nr (ta/null-render rendered-prompt)]
    (is (str/includes? nr ";;; ┌─ function-menu ─")
        "menu section rides verbatim")
    (is (str/includes? nr "; ① (db/query"))
    (is (str/includes? nr "; seon · my.agent.X · live REPL")
        "transcript masthead teaching stays")
    (is (str/ends-with? nr "my.agent.X=> \n;;; └─ end transcript ─")
        "the ns=> cursor + end bracket close the null render")
    (is (not (str/includes? nr "the task")) "task intent removed")
    (is (not (str/includes? nr "warmup")) "message history removed")
    (is (not (str/includes? nr "result/r1")) "eval history removed")
    (is (not (str/includes? nr "turn 2 · loop"))
        "the live status line (turn state) removed")
    (is (not (str/includes? nr ";;; ┌─ plan ─"))
        "the intent-derived plan section (incl. its ▶/☐ steps) dropped whole")))

(deftest null-render-no-events-or-no-transcript-is-identity
  (let [no-events (str ";;; ┌─ transcript ─\n; masthead\n\nmy.agent.X=> \n"
                       ";;; └─ end transcript ─")]
    (is (= no-events (ta/null-render no-events))
        "no message lines → nothing to null out")
    (is (= "plain prompt" (ta/null-render "plain prompt"))
        "no transcript section → unchanged")))

(deftest assemble-reply-locked-plus-tail
  (is (= "(def a 1)\n\n(def b 2)" (ta/assemble-reply ["(def a 1)" "(def b 2)"] "")))
  (is (= "(def a 1)\n\n(def b" (ta/assemble-reply ["(def a 1)"] "(def b\n"))
      "an unfinished tail rides last, trimmed — the loop's honest partial")
  (is (= "" (ta/assemble-reply [] "")) "nothing locked, nothing drafted"))

(deftest step-projection-scalars-optional-absent
  (let [full (ta/step-projection
               "call1" 2 {:transition "expand" :glyph "①"
                          :locked ["(def a 1)"] :forwards 4
                          :gen_s 1.7 :worker_sha "abc123def456"
                          :new_draft "(def b"
                          :readouts {:glyph_margin 7.5 :eos_logprob_tail -6.4
                                     :free_entropy_worst 0.42}})
        bare (ta/step-projection "call1" 0 {:transition "stuck" :locked []})]
    (is (= :expand (:seon.typeahead/transition full)))
    (is (= "①" (:seon.typeahead/glyph full)))
    (is (= 7.5 (:seon.typeahead/margin full)))
    (is (= -6.4 (:seon.typeahead/eos-logprob full)))
    (is (= 1 (:seon.typeahead/locked-count full)))
    (is (= 4 (:seon.typeahead/forwards full)))
    (is (= 1.7 (:seon.typeahead/gen-s full)) "wall per step projected")
    (is (= 0.42 (:seon.typeahead/entropy-worst full)))
    (is (= "abc123def456" (:seon.typeahead/worker-sha full)))
    (is (= "(def b" (:seon.typeahead/draft-preview full)))
    (is (= 1 (:seon.typeahead/draft-tokens full))
        "draft sized in TOKENS (chars/4), never chars")
    (is (= :stuck (:seon.typeahead/transition bare)))
    (is (= 0 (:seon.typeahead/locked-count bare)))
    (is (not (contains? bare :seon.typeahead/glyph))
        "optional = absent — no nil glyph/margin stored")
    (is (not (contains? bare :seon.typeahead/margin)))
    (is (not (contains? bare :seon.typeahead/draft-preview))
        "blank draft → no preview datom")
    (is (not (contains? bare :seon.typeahead/gen-s)))
    (is (not (contains? bare :seon.typeahead/buffer-preview))
        "no wire buffer picture → no buffer datoms")
    (is (not (contains? bare :seon.typeahead/offers-edn)))
    (is (not (contains? bare :seon.typeahead/holes-edn)))))

(deftest step-projection-buffer-offers-holes
  (let [proj (ta/step-projection
               "call1" 0
               {:transition "expand" :glyph "①" :locked ["(def a 1)"]
                :new_draft ""
                :buffer_text "(def a 1)\n(def b "
                :buffer_spans [{:start 0 :end 9 :status "locked"}
                               {:start 9 :end 17 :status "resolving"}
                               {:start 17 :end 17 :status "frontier"}]
                :offer_status [{:glyph "①" :label "todo/add!" :cal 7.5
                                :raw -0.2 :state "fired"}
                               {:glyph "②" :label "db/query" :cal -1.0
                                :raw -8.0 :state "suppressed"
                                :reason "typed-region"}]
                :expansion {:hole_confidence [{:mean 0.1 :worst 0.42
                                               :accepted true :round 0}
                                              {:mean 1.2 :worst 2.1
                                               :accepted false :round 1}]
                            :probes [{:hole 0 :chosen 8}]
                            :settle_rounds_used 1
                            :settle_round_budget 2}
                :readouts {:auto_offer_margin 6.0 :eos_logprob_tail -3.0}})
        spans  (reader/read-string (:seon.typeahead/buffer-spans proj))
        offers (reader/read-string (:seon.typeahead/offers-edn proj))
        holes  (reader/read-string (:seon.typeahead/holes-edn proj))]
    (is (= "(def a 1)\n(def b " (:seon.typeahead/buffer-preview proj)))
    (is (= [[0 9 :locked] [9 17 :resolving] [17 17 :frontier]] spans)
        "spans → compact clipped tuples, statuses keyworded")
    (is (= 2 (count offers)))
    (is (= :fired (:seon.typeahead/state (first offers))))
    (is (= :typed-region (:seon.typeahead/reason (second offers)))
        "worker-side suppression reason rides")
    (is (= 6.0 (:seon.typeahead/auto-offer-margin proj))
        "the fire threshold projected for the tile's bars")
    (is (= 2 (count holes)))
    (is (true? (:seon.typeahead/accepted (first holes))))
    (is (= 8 (:seon.typeahead/chosen-length (first holes)))
        "CAL-chosen length joined from probes by hole index")
    (is (false? (:seon.typeahead/accepted (second holes))))
    (is (= 1 (:seon.typeahead/rounds-used proj)))
    (is (= 2 (:seon.typeahead/round-budget proj)))))

(deftest with-withheld-offers-appends-loop-suppressions
  (let [proj {:seon.typeahead/call "c" :seon.typeahead/step-idx 0
              :seon.typeahead/at (js/Date.)
              :seon.typeahead/transition :progress
              :seon.typeahead/locked-count 0
              :seon.typeahead/offers-edn
              (pr-str [{:seon.typeahead/glyph "①"
                        :seon.typeahead/state :below-margin}])}
        out  (ta/with-withheld-offers
               proj [{:seon.typeahead/glyph "②"
                      :seon.typeahead/label "db/query"
                      :seon.typeahead/template [["clamp" "(db/query q)"]]}])
        rows (reader/read-string (:seon.typeahead/offers-edn out))]
    (is (= 2 (count rows)))
    (is (= {:seon.typeahead/glyph "②" :seon.typeahead/label "db/query"
            :seon.typeahead/state :suppressed
            :seon.typeahead/reason :failed-before}
           (last rows))
        "withheld offer appended as failed-before suppression")
    (is (= proj (ta/with-withheld-offers proj []))
        "no withheld offers → unchanged")))

;; ============================================================
;; The step loop over the scripted wire.
;; ============================================================

(deftest step-loop-threads-committed-and-assembles-reply
  (async done
    (let [calls (atom [])
          fetch (scripted-fetch
                  calls
                  [(step-response {:transition "progress"
                                   :locked ["(def a 1)"]
                                   :new_draft "(def b"
                                   :forwards 3
                                   :readouts {:eos_logprob_tail -6.5}})
                   (step-response {:transition "done"
                                   :locked ["(def b 2)"]
                                   :new_draft ""
                                   :forwards 2
                                   :readouts {:eos_logprob_tail -2.5}})])]
      (-> (with-conn
            (fn [conn]
              (with-local-worker fetch
                (fn []
                  (-> (db/with-agent a-id
                        (fn [] ((ta/agent-adapter) "the rendered prompt")))
                      (.then
                        (fn [{:keys [text] :as resp}]
                          (is (nil? (:seon.ai/error resp)) "keyless local endpoint works")
                          (is (= "(def a 1)\n\n(def b 2)" text)
                              "reply = the locked forms, LLM-reply-shaped")
                          (is (= :done (get-in resp [:seon.ai/raw
                                                     :seon.ai.typeahead/outcome])))
                          (is (= 2 (count (get-in resp [:seon.ai/raw
                                                        :seon.ai.typeahead/steps]))))
                          ;; wire threading
                          (let [[c1 c2] @calls]
                            (is (= 2 (count @calls)) "one submit per step")
                            (is (str/starts-with? (:url c1) "http://127.0.0.1:17999/run")
                                "full-URL endpoint used AS the worker base")
                            (is (= "step" (get (:payload c1) "mode")))
                            (is (= "" (get (:payload c1) "committed")))
                            (is (= "the rendered prompt" (get (:payload c1) "prompt")))
                            (is (= 8 (get-in (:payload c1) ["policy" "max_rounds"])))
                            (is (= "(def a 1)" (get (:payload c2) "committed"))
                                "step 1's locked form threads into step 2's committed")
                            (is (= "(def b" (get (:payload c2) "draft"))
                                "step 1's new_draft threads into step 2's draft"))
                          ;; per-step datom projections, agent-attributed
                          (let [dbv   @conn
                                steps (->> (db/query
                                             {:seon.db/db dbv
                                              :seon.db/query
                                              '[:find [?e ...]
                                                :in $ ?aid
                                                :where
                                                [?a :seon.agent/id ?aid]
                                                [?e :seon.typeahead/agent ?a]]
                                              :seon.db/args [a-id]})
                                           (map #(db/pull dbv '[*] %))
                                           (sort-by :seon.typeahead/step-idx)
                                           vec)]
                            (is (= 2 (count steps)) "one step row per round")
                            (is (= [:progress :done]
                                   (mapv :seon.typeahead/transition steps)))
                            (is (= [1 1] (mapv :seon.typeahead/locked-count steps)))
                            ;; "the rendered prompt" = 19 chars → 4 tokens
                            (is (= [4 4] (mapv :seon.typeahead/prompt-tokens steps))
                                "render size stamped per row, in TOKENS")
                            ;; the block is EXPLICIT-INSTALL ONLY: the loop
                            ;; must NOT install it (owner constraint — not
                            ;; enabled by default anywhere)
                            (is (empty? (db/query
                                          {:seon.db/db dbv
                                           :seon.db/query
                                           '[:find ?b
                                             :in $ ?aid
                                             :where
                                             [?a :seon.agent/id ?aid]
                                             [?a :seon.agent/ctx ?b]
                                             [?b :seon.agent.ctx/name :typeahead-steps]]
                                           :seon.db/args [a-id]}))
                                "the loop never self-installs :typeahead-steps")))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest step-loop-threads-attempt-signal-to-worker
  (async done
    (let [calls      (atom [])
          controller (js/AbortController.)
          signal     (.-signal controller)
          fetch      (scripted-fetch
                       calls
                       [(step-response {:transition "done"
                                        :locked ["(def a 1)"]
                                        :new_draft ""})])]
      (-> (with-conn
            (fn [_conn]
              (with-local-worker fetch
                (fn []
                  (db/with-agent
                    a-id
                    (fn []
                      ((ta/agent-adapter)
                       {:seon.ai/ctx "the rendered prompt"
                        :seon.ai/abort-signal signal})))))))
          (.then (fn [{:keys [text] :as resp}]
                   (is (nil? (:seon.ai/error resp)))
                   (is (= "(def a 1)" text))
                   (is (= 1 (count @calls)))
                   (is (identical? signal (:signal (first @calls)))
                       "every worker fetch receives the attempt signal")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest step-loop-suppresses-failed-offer
  ;; P6: an offer whose EXPANSION locked nothing is suppressed for the
  ;; rest of the call (the P5 p1 trace: the identical failed auto-offer
  ;; re-fired 4x — the stateless worker cannot remember, the loop must).
  (async done
    (let [calls (atom [])
          fetch (scripted-fetch
                  calls
                  [(step-response {:transition "expand" :glyph "①"
                                   :locked [] :new_draft ""
                                   :events [{:event "expand-failed"
                                             :glyph "①"}]})
                   (step-response {:transition "stuck" :locked []
                                   :new_draft ""})
                   (step-response {:transition "stuck" :locked []
                                   :new_draft ""})])]
      (-> (with-conn
            (fn [_conn]
              ;; one-menu-entry seed: a public program-graph fn + one eval
              ;; calling it → menu/function-offers yields the ① offer
              (-> (db/transact!
                    {:seon.db/tx-data
                     [{:seon.fn/sym      "my.plan/done!"
                       :seon.fn/fn-var?  true
                       :seon.fn/agent-facing? true
                       :seon.fn/arglists "([{:my.plan/keys [id]}])"}
                      {:seon.eval/agent  [:seon.agent/id a-id]
                       :seon.eval/at     (js/Date. 1000)
                       :seon.eval/ok?    true
                       :seon.eval/source "(my.plan/done! {:my.plan/id \"x\"})"}]})
                  (.then
                    (fn [_]
                      (with-local-worker fetch
                        (fn []
                          (db/with-agent a-id
                            (fn [] ((ta/agent-adapter) "the rendered prompt")))))))
                  (.then
                    (fn [resp]
                      (is (= :gave-up (get-in resp [:seon.ai/raw
                                                    :seon.ai.typeahead/outcome]))
                          "failed expand then stuck×2 → gave-up")
                      (let [[c1 c2 c3] @calls]
                        (is (= 3 (count @calls)))
                        (is (= 1 (count (get (:payload c1) "offers")))
                            "step 1 carries the menu offer")
                        (is (= "①" (get-in (:payload c1) ["offers" 0 "glyph"])))
                        (is (nil? (get (:payload c2) "offers"))
                            "the failed offer is suppressed on step 2")
                        (is (nil? (get (:payload c3) "offers"))
                            "…and stays suppressed for the whole call")))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest step-loop-worker-error-is-a-value
  (async done
    (let [calls (atom [])
          fetch (scripted-fetch
                  calls
                  [(step-response {:gen_error "model exploded"})])]
      (-> (with-conn
            (fn [_conn]
              (with-local-worker fetch
                (fn [] ((ta/agent-adapter) "prompt")))))
          (.then
            (fn [{:keys [text] :as resp}]
              (is (= "" text))
              (is (some? (:seon.ai/error resp))
                  "an in-band gen_error fails the call as a VALUE")
              (is (re-find #"model exploded"
                           (get-in resp [:seon.ai/error :seon.ai/msg])))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; (Tile + ai-slot render tests live with the block family:
;; seon.agent.ctx.typeahead-steps-test.)

;; ============================================================
;; W2 — the draft-head prefill affordance + the per-step PLAN PASS
;; (planner-worker-design). my.plan is the test FIXTURE (instance #1);
;; the mechanism under test is registry+graph derived and generic.
;; ============================================================

(def ^:private b-id "typeaheadtestB")   ; the "frontier" author
(def ^:private root-id "planroottestaa")
(def ^:private step-id "planchildtestb")

(def ^:private reconcile-spec
  "[:=> [:cat :my.plan/reconcile-request] :my.plan/reconcile-response]")
(def ^:private document-spec
  "[:=> [:cat :my.plan/tree-request] :my.plan/tree-response]")

(defn- seed-plan!
  "Program-graph rows for the affordance + a two-node open plan: the root
   authored BY agent A, the child step authored BY agent B (foreign)."
  []
  (-> (db/transact!
        {:seon.db/tx-data
         [{:seon.fn/sym "my.plan/reconcile!" :seon.fn/fn-var? true
           :seon.fn/spec reconcile-spec}
          {:seon.fn/sym "my.plan/document" :seon.fn/fn-var? true
           :seon.fn/spec document-spec}
          {:seon.agent/id b-id}]})
      (.then (fn [_]
               (db/with-agent a-id
                 (fn []
                   (db/transact!
                     {:seon.db/tx-data
                      [{:my.plan/id root-id :my.plan/title "root plan"
                        :my.plan/goal "the goal" :my.plan/status :open
                        :my.plan/created-at (js/Date.)
                        :my.plan/agent [:seon.agent/id a-id]}]})))))
      (.then (fn [_]
               (db/with-agent b-id
                 (fn []
                   (db/transact!
                     {:seon.db/tx-data
                      [{:my.plan/id step-id :my.plan/title "frontier step"
                        :my.plan/expect "the outcome holds"
                        :my.plan/status :open
                        :my.plan/created-at (js/Date.)
                        :my.plan/agent [:seon.agent/id a-id]
                        :my.plan/parent [:my.plan/id root-id]}]})))))))

(deftest prefill-affordance-derived-from-registry-and-graph
  ;; ONE computed rule: the registered request schema's entry property
  ;; (:seon.render/prefill-fn) + the program-graph fn whose spec input IS
  ;; that schema. No fn list anywhere.
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-plan!)
                (.then
                  (fn [_]
                    (let [affs (ta/prefill-affordances @conn)
                          aff  (first (filter #(= "my.plan/reconcile!"
                                                  (:seon.ai.typeahead/head %))
                                              affs))]
                      (is (some? aff) "reconcile! affordance derived")
                      (is (= :my.plan/tree (:seon.ai.typeahead/arg-key aff)))
                      (is (= 'my.plan/document
                             (:seon.ai.typeahead/prefill-fn aff)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest document-segments-clamp-ids-and-foreign-entries
  ;; Authority is CLAMPS, not prose: identity attrs clamp everywhere;
  ;; a scalar entry whose current datom ANOTHER agent authored clamps;
  ;; the caller's own entries stay editable prefill text.
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-plan!)
                (.then
                  (fn [_]
                    (db/with-agent a-id
                      (fn []
                        (let [doc  (plan/document {:seon.agent/id a-id})
                              segs (ta/document-segments @conn a-id doc)
                              kind-of (fn [needle]
                                        (some (fn [[k s]]
                                                (when (str/includes? s needle) k))
                                              segs))]
                          (is (vector? segs))
                          (is (= "clamp" (kind-of root-id))
                              "the root id clamps")
                          (is (= "clamp" (kind-of step-id))
                              "the child id clamps")
                          (is (= "prefill" (kind-of "root plan"))
                              "A's own title is editable")
                          (is (= "clamp" (kind-of "frontier step"))
                              "B's (foreign) title clamps")
                          (is (= "clamp" (kind-of "the outcome holds"))
                              "B's (foreign) expect clamps")
                          ;; the segments reassemble to one readable doc
                          (let [text (apply str (map second segs))]
                            (is (str/includes? text ":my.plan/goal"))
                            (is (str/includes? text root-id)))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest step-loop-runs-plan-pass-at-open
  ;; :every-step (the default): call 1 IS the pass — minimal render, the
  ;; head seeded as the draft, the prefilled template on the wire; its
  ;; locked reconcile! form rides the reply FIRST and threads into the
  ;; work step's committed.
  (async done
    (let [edited "(my.plan/reconcile! {:my.plan/tree [{:my.plan/id \"planroottestaa\" :my.plan/title \"root plan EDITED\"}]})"
          calls (atom [])
          fetch (scripted-fetch
                  calls
                  [(step-response {:transition "expand" :arm "prefill-edit"
                                   :prefill_head "my.plan/reconcile!"
                                   :locked [edited] :new_draft ""
                                   :forwards 4 :gen_s 1.2})
                   (step-response {:transition "done"
                                   :locked ["(def a 1)"] :new_draft ""})])]
      (-> (with-conn
            (fn [conn]
              (-> (seed-plan!)
                  (.then
                    (fn [_]
                      (with-local-worker fetch
                        (fn []
                          (db/with-agent a-id
                            (fn [] ((ta/agent-adapter) "the rendered prompt")))))))
                  (.then
                    (fn [{:keys [text] :as resp}]
                      (is (nil? (:seon.ai/error resp)))
                      (is (= (str edited "\n\n(def a 1)") text)
                          "the pass form rides the reply FIRST")
                      (let [[c1 c2] @calls]
                        (is (= 2 (count @calls)) "pass + one work step")
                        (is (str/starts-with? (get (:payload c1) "prompt")
                                              "; PLAN PASS")
                            "the pass render is MINIMAL, not the context")
                        (is (= "(my.plan/reconcile! " (get (:payload c1) "draft"))
                            "the head is seeded as the draft")
                        (let [tmpl (get-in (:payload c1)
                                           ["prefills" "my.plan/reconcile!"])]
                          (is (vector? tmpl) "the prefilled template rides")
                          (is (and (= "clamp" (ffirst tmpl))
                                   (str/starts-with?
                                     (second (first tmpl))
                                     "(my.plan/reconcile! {:my.plan/tree "))
                              "the call opening clamps (coalesced with doc structure)")
                          (is (some (fn [[k s]]
                                      (and (= k "clamp")
                                           (str/includes? s root-id)))
                                    tmpl)
                              "id spans ride as clamp segments"))
                        (is (= "the rendered prompt" (get (:payload c2) "prompt"))
                            "the WORK step uses the real render")
                        (is (= edited (get (:payload c2) "committed"))
                            "the pass form threads into committed")
                        (is (some? (get (:payload c2) "prefills"))
                            "the organic affordance rides every step"))
                      ;; the pass row is a marked step projection
                      (let [dbv @conn
                            rows (->> (db/query
                                        {:seon.db/db dbv
                                         :seon.db/query
                                         '[:find [?e ...]
                                           :where [?e :seon.typeahead/plan-pass? true]]})
                                      (map #(db/pull dbv '[*] %)))]
                        (is (= 1 (count rows)))
                        (let [row (first rows)]
                          (is (= -1 (:seon.typeahead/step-idx row))
                              "the pass precedes round 0")
                          (is (pos? (:seon.typeahead/prefill-tokens row))
                              "prefill size recorded, in tokens")
                          (is (= 1.2 (:seon.typeahead/gen-s row))
                              "pass wall recorded"))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest plan-pass-no-change-drops-the-form
  ;; A no-change pass (model leaves the document) DROPS the form —
  ;; cheaper than a 0/0/0 receipt: zero eval, zero transcript tokens;
  ;; the unchanged :plan block is the confirmation. The pass row still
  ;; records the cost honestly.
  (async done
    (let [calls (atom [])]
      (-> (with-conn
            (fn [conn]
              (-> (seed-plan!)
                  (.then
                    (fn [_]
                      (db/with-agent a-id
                        (fn []
                          ;; reproduce the exact template text, then answer the
                          ;; pass with it (extra whitespace — normalization)
                          (let [doc  (plan/document {:seon.agent/id a-id})
                                segs (ta/document-segments @conn a-id doc)
                                tmpl-text (str "(my.plan/reconcile! {:my.plan/tree "
                                               (apply str (map second segs))
                                               "})")
                                unchanged (str/replace tmpl-text #"\} *\{" "}\n  {")
                                fetch (scripted-fetch
                                        calls
                                        [(step-response
                                           {:transition "expand"
                                            :arm "prefill-edit"
                                            :locked [unchanged] :new_draft ""
                                            :gen_s 0.9})
                                         (step-response
                                           {:transition "done"
                                            :locked ["(def a 1)"] :new_draft ""})])]
                            (with-local-worker fetch
                              (fn []
                                (db/with-agent a-id
                                  (fn [] ((ta/agent-adapter) "the rendered prompt"))))))))))
                  (.then
                    (fn [{:keys [text]}]
                      (is (= "(def a 1)" text)
                          "no-change pass: the form is DROPPED from the reply")
                      (is (= 2 (count @calls)) "the pass still ran")
                      (let [dbv @conn
                            rows (db/query
                                   {:seon.db/db dbv
                                    :seon.db/query
                                    '[:find [?e ...]
                                      :where [?e :seon.typeahead/plan-pass? true]]})]
                        (is (= 1 (count rows))
                            "the no-change pass records its cost")))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest plan-pass-policy-off-and-on-stuck
  ;; The demotion switch (:seon.typeahead/plan-pass): :off never passes
  ;; (but the ORGANIC affordance still rides the wire); :on-stuck passes
  ;; once, at the first observed stuck round.
  (async done
    (let [calls (atom [])
          edited "(my.plan/reconcile! {:my.plan/tree [{:my.plan/id \"planroottestaa\" :my.plan/title \"replanned\"}]})"
          fetch (scripted-fetch
                  calls
                  ;; :off leg — one work step
                  [(step-response {:transition "done"
                                   :locked ["(def a 1)"] :new_draft ""})
                   ;; :on-stuck leg — stuck, then the pass, then done
                   (step-response {:transition "stuck" :locked []
                                   :new_draft ""})
                   (step-response {:transition "expand" :arm "prefill-edit"
                                   :locked [edited] :new_draft ""})
                   (step-response {:transition "done"
                                   :locked ["(def b 2)"] :new_draft ""})])]
      (-> (with-conn
            (fn [_conn]
              (-> (seed-plan!)
                  (.then (fn [_]
                           (db/transact!
                             {:seon.db/tx-data
                              [{:seon.typeahead/id "policy"
                                :seon.typeahead/plan-pass :off}]})))
                  (.then
                    (fn [_]
                      (with-local-worker fetch
                        (fn []
                          (-> (db/with-agent a-id
                                (fn [] ((ta/agent-adapter) "render A")))
                              (.then
                                (fn [{:keys [text]}]
                                  (is (= "(def a 1)" text))
                                  (let [c1 (first @calls)]
                                    (is (= "render A" (get (:payload c1) "prompt"))
                                        ":off → no pass call")
                                    (is (some? (get (:payload c1) "prefills"))
                                        "organic affordance still rides"))
                                  (db/transact!
                                    {:seon.db/tx-data
                                     [{:seon.typeahead/id "policy"
                                       :seon.typeahead/plan-pass :on-stuck}]})))
                              (.then
                                (fn [_]
                                  (db/with-agent a-id
                                    (fn [] ((ta/agent-adapter) "render B")))))
                              (.then
                                (fn [{:keys [text]}]
                                  (is (= (str edited "\n\n(def b 2)") text)
                                      "on-stuck pass form rides the reply")
                                  (let [[_ c2 c3 c4] @calls]
                                    (is (= 4 (count @calls)))
                                    (is (= "render B" (get (:payload c2) "prompt"))
                                        "first :on-stuck call is WORK")
                                    (is (str/starts-with?
                                          (get (:payload c3) "prompt") "; PLAN PASS")
                                        "the pass fires after the stuck round")
                                    (is (= "render B" (get (:payload c4) "prompt"))
                                        "work resumes after the pass"))))))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ============================================================
;; W3 prerequisite — scope-down + skip-with-reason. The pass document
;; scopes to the ▶ active step's subtree + the root layer (titles only
;; for non-active roots); a scoped edit writes back MERGED into the
;; FULL document (reconcile! treats absence as drop, so the narrowed
;; editor view must never reach it directly); a document over budget
;; even scoped records :seon.typeahead/pass-skip — never silent.
;; ============================================================

(def ^:private scope-root-id "planscoperoot1")
(def ^:private scope-step-id "planscopestep1")

(deftest scoped-document-active-subtree-plus-root-titles
  (let [forest [{:my.plan/id scope-root-id :my.plan/title "R1"
                 :my.plan/status :open :my.plan/goal "g"
                 :my.plan/description "R1 DESC"
                 :my.plan/_parent
                 [{:my.plan/id "planscopemidd1" :my.plan/title "M1"
                   :my.plan/status :open :my.plan/description "M DESC"
                   :my.plan/_parent
                   [{:my.plan/id "planscopeactv1" :my.plan/title "A1"
                     :my.plan/status :active :my.plan/expect "x holds"
                     :my.plan/_parent
                     [{:my.plan/id "planscopesubb1" :my.plan/title "S1"
                       :my.plan/status :open}]}]}]}
                {:my.plan/id "planscoperoot2" :my.plan/title "R2"
                 :my.plan/status :open :my.plan/description "R2 DESC"
                 :my.plan/expect "r2 out"}]
        scoped (ta/scoped-document forest)
        [r1 r2] scoped]
    (is (= 2 (count scoped)))
    (is (= "R1" (:my.plan/title r1)))
    (is (nil? (:my.plan/description r1))
        "non-title scalars drop from the root layer")
    (is (= "g" (:my.plan/goal r1))
        "the goal rides (the pass render points at it)")
    (let [[a] (:my.plan/_parent r1)]
      (is (= "planscopeactv1" (:my.plan/id a))
          "the ▶ subtree hangs under its root")
      (is (= "x holds" (:my.plan/expect a)))
      (is (= "S1" (get-in a [:my.plan/_parent 0 :my.plan/title]))
          "the ▶ subtree rides in FULL"))
    (is (= {:my.plan/id "planscoperoot2" :my.plan/title "R2"
            :my.plan/status :open}
           r2)
        "other roots are titles-only")))

(defn- seed-big-plan!
  "Program-graph rows + an over-budget plan: root (A's, `desc-chars`-char
   description) with an ACTIVE child step (A's, `expect` outcome)."
  [desc-chars expect]
  (-> (db/transact!
        {:seon.db/tx-data
         [{:seon.fn/sym "my.plan/reconcile!" :seon.fn/fn-var? true
           :seon.fn/spec reconcile-spec}
          {:seon.fn/sym "my.plan/document" :seon.fn/fn-var? true
           :seon.fn/spec document-spec}]})
      (.then (fn [_]
               (db/with-agent a-id
                 (fn []
                   (db/transact!
                     {:seon.db/tx-data
                      [{:my.plan/id scope-root-id :my.plan/title "big root"
                        :my.plan/goal "the goal"
                        :my.plan/description (apply str (repeat desc-chars "x"))
                        :my.plan/status :open
                        :my.plan/created-at (js/Date.)
                        :my.plan/agent [:seon.agent/id a-id]}
                       {:my.plan/id scope-step-id :my.plan/title "active step"
                        :my.plan/expect expect
                        :my.plan/status :active
                        :my.plan/created-at (js/Date.)
                        :my.plan/agent [:seon.agent/id a-id]
                        :my.plan/parent [:my.plan/id scope-root-id]}]})))))))

(deftest plan-pass-scoped-document-and-merge-back
  ;; Over-budget forest → the pass template is the SCOPED document; the
  ;; locked edit is re-emitted merged into the FULL document; the scoped
  ;; template never rides the organic wire.
  (async done
    (let [calls (atom [])]
      (-> (with-conn
            (fn [_conn]
              (-> (seed-big-plan! 1200 "the outcome holds")
                  (.then
                    (fn [_]
                      (db/with-agent a-id
                        (fn []
                          (let [doc    (plan/document {:seon.agent/id a-id})
                                scoped (ta/scoped-document doc)
                                edited (mapv (fn [r]
                                               (if (= scope-root-id (:my.plan/id r))
                                                 (assoc r :my.plan/title
                                                        "big root SHARPENED")
                                                 r))
                                             scoped)
                                form   (str "(my.plan/reconcile! {:my.plan/tree "
                                            (pr-str edited) "})")
                                fetch  (scripted-fetch
                                         calls
                                         [(step-response
                                            {:transition "expand"
                                             :arm "prefill-edit"
                                             :locked [form] :new_draft ""
                                             :gen_s 0.7})
                                          (step-response
                                            {:transition "done"
                                             :locked ["(def a 1)"]
                                             :new_draft ""})])]
                            (with-local-worker fetch
                              (fn []
                                (db/with-agent a-id
                                  (fn []
                                    ((ta/agent-adapter) "the rendered prompt"))))))))))
                  (.then
                    (fn [{:keys [text] :as resp}]
                      (is (nil? (:seon.ai/error resp)))
                      (let [pass-form (first (str/split text #"\n\n"))
                            parsed    (reader/read-string pass-form)
                            tree      (get (second parsed) :my.plan/tree)
                            root      (first (filter #(= scope-root-id
                                                         (:my.plan/id %))
                                                     tree))]
                        (is (str/includes? pass-form "big root SHARPENED")
                            "the scoped edit survives the merge")
                        (is (= 1200 (count (:my.plan/description root)))
                            "merge-back reinstates the out-of-scope description")
                        (is (= "active step"
                               (get-in root [:my.plan/_parent 0 :my.plan/title]))
                            "the merged form carries the full structure"))
                      (let [[c1 c2] @calls]
                        (is (= 2 (count @calls)) "pass + one work step")
                        (is (str/starts-with? (get (:payload c1) "prompt")
                                              "; PLAN PASS"))
                        (let [tmpl  (get-in (:payload c1)
                                            ["prefills" "my.plan/reconcile!"])
                              ttext (apply str (map second tmpl))]
                          (is (vector? tmpl) "the SCOPED template rides the pass")
                          (is (not (str/includes? ttext "xxxxxxxx"))
                              "the scoped template drops the long description"))
                        (is (nil? (get (:payload c2) "prefills"))
                            "a scoped template never rides the organic wire")))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest plan-pass-doc-over-budget-skips-with-reason
  ;; Even the SCOPED document over budget → no pass call, and the skip
  ;; records a marked step row with the reason (never a silent skip).
  (async done
    (let [calls (atom [])
          fetch (scripted-fetch
                  calls
                  [(step-response {:transition "done"
                                   :locked ["(def a 1)"] :new_draft ""})])]
      (-> (with-conn
            (fn [conn]
              (-> (seed-big-plan! 1200 (apply str (repeat 1200 "y")))
                  (.then
                    (fn [_]
                      (with-local-worker fetch
                        (fn []
                          (db/with-agent a-id
                            (fn []
                              ((ta/agent-adapter) "the rendered prompt")))))))
                  (.then
                    (fn [{:keys [text]}]
                      (is (= "(def a 1)" text))
                      (is (= 1 (count @calls)) "no pass call — skipped")
                      (is (nil? (get (:payload (first @calls)) "prefills"))
                          "no organic prefill for an over-budget scoped doc")
                      (let [dbv  @conn
                            rows (->> (db/query
                                        {:seon.db/db dbv
                                         :seon.db/query
                                         '[:find [?e ...]
                                           :where
                                           [?e :seon.typeahead/pass-skip _]]})
                                      (map #(db/pull dbv '[*] %)))]
                        (is (= 1 (count rows)) "ONE skip row recorded")
                        (let [row (first rows)]
                          (is (true? (:seon.typeahead/plan-pass? row)))
                          (is (re-find #"doc-over-budget \(\d+ tok\)"
                                       (:seon.typeahead/pass-skip row)))
                          (is (= -1 (:seon.typeahead/step-idx row))))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
