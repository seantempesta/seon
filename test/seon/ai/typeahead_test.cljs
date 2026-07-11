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
        (swap! calls conj {:url url :payload payload}))
      (js/Promise.resolve (nth responses (swap! n inc))))))

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema + one agent."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact!
                       conn
                       {:tx-data (into (db/malli->datahike-schema
                                         client/agent-bootstrap-attrs)
                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_]
                              (d/transact! conn {:tx-data [{:seon.agent/id a-id}]})))
                     (.then (fn [_] conn))))))))

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
       ";;; └─ end plan ─\n\n"
       ";;; ┌─ recent-verbs ─\n"
       "; ① (db/query [req] …) — Run a Datalog query.\n"
       ";;; └─ end recent-verbs ─\n\n"
       ";;; ┌─ plan-ledger ─\n"
       "; ☐ ① the task\n"
       ";;; └─ end plan-ledger ─\n\n"
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
    (is (str/includes? nr ";;; ┌─ recent-verbs ─")
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
        "the intent-derived plan section dropped whole")
    (is (not (str/includes? nr ";;; ┌─ plan-ledger ─"))
        "the intent-derived plan-ledger section dropped whole")))

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
              ;; calling it → menu/verb-offers yields the ① offer
              (-> (db/transact!
                    {:seon.db/tx-data
                     [{:seon.fn/sym      "my.plan/done!"
                       :seon.fn/fn-var?  true
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
