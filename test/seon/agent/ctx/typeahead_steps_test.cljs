(ns seon.agent.ctx.typeahead-steps-test
  "Behavior tests for the `:typeahead-steps` ctx block family
   (`seon.agent.ctx.typeahead-steps` — the typeahead provider's
   observability twin).

   Covers BOTH render slots: the ai slot's PROVIDER GATE (empty unless
   the agent's RESOLVED provider is :typeahead — global config row and
   per-agent `:seon.ai/agent-provider` overlay both ways), its content
   (the result grammar teaching, no glyph re-teaching), and the html
   tile (nil on an empty db, the last call's rows with the call header,
   expand outcome tags, and the token-sized draft preview).

   Fresh `:memory` conn seeded like the pod boots, set! as the root
   db/*conn* so `db/transact!` targets it (lazy-installs the domain
   schema); both slot fns are PURE reads of the passed db value."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent.ctx.typeahead-steps :as ts]
    [seon.ai.typeahead]
    [seon.client :as client]
    [seon.db :as db]))

(def ^:private a-id "tsteststagentA")   ; 14 chars — the :seon.db/id shape

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
   prior root restored after — root set!, not binding (CLJS dynamic bindings
   pop at the first await boundary)."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- ok!
  "Assert a `db/transact!` envelope succeeded (shared seed helper)."
  [{ok? :seon.db/ok? err :seon.db/error}]
  (is (true? ok?) (str "seed transacted — " (pr-str err))))

;; ============================================================
;; :seon.render/ai — the provider gate.
;; ============================================================

(deftest ai-slot-empty-without-typeahead-provider
  (async done
    (-> (with-conn
          (fn [conn]
            (is (= "" (ts/steps-ai {:seon.db/db @conn :seon.agent/id a-id}))
                "no config row → resolved provider :deepseek → empty")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest ai-slot-renders-when-global-provider-is-typeahead
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/transact! {:seon.db/tx-data [{:seon.ai/id "config"
                                                  :seon.ai/provider :typeahead}]})
                (.then
                  (fn [res]
                    (ok! res)
                    (let [text (ts/steps-ai {:seon.db/db @conn
                                             :seon.agent/id a-id})]
                      (is (str/includes? text "⟹")
                          "teaches the live result grammar")
                      (is (str/includes? text ";; =>")
                          "names the banned claim shape explicitly")
                      (is (not (str/includes? text "①"))
                          "glyph teaching stays with the menu headers")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest ai-slot-honors-per-agent-provider-overlay
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/transact! {:seon.db/tx-data
                               [{:seon.ai/id "config"
                                 :seon.ai/provider :deepseek}
                                {:seon.agent/id a-id
                                 :seon.ai/agent-provider :typeahead}]})
                (.then
                  (fn [res]
                    (ok! res)
                    (is (seq (ts/steps-ai {:seon.db/db @conn
                                           :seon.agent/id a-id}))
                        "agent :typeahead over global :deepseek → renders")
                    ;; the reverse: global :typeahead, agent opted OUT
                    (db/transact! {:seon.db/tx-data
                                   [{:seon.ai/id "config"
                                     :seon.ai/provider :typeahead}
                                    {:seon.agent/id a-id
                                     :seon.ai/agent-provider :deepseek}]})))
                (.then
                  (fn [res]
                    (ok! res)
                    (is (= "" (ts/steps-ai {:seon.db/db @conn
                                            :seon.agent/id a-id}))
                        "agent :deepseek over global :typeahead → empty"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; :seon.render/html — the step-trace tile.
;; ============================================================

(defn- seed-steps!
  "Two calls' step rows for the agent — the tile must render only the
   LATEST call (call2: one expand-locked row, one expand-failed row)."
  []
  (db/transact!
    {:seon.db/tx-data
     [{:seon.typeahead/call "call1" :seon.typeahead/step-idx 0
       :seon.typeahead/at (js/Date. 1000) :seon.typeahead/transition :done
       :seon.typeahead/locked-count 1
       :seon.typeahead/agent [:seon.agent/id a-id]}
      {:seon.typeahead/call "call2" :seon.typeahead/step-idx 0
       :seon.typeahead/at (js/Date. 5000) :seon.typeahead/transition :expand
       :seon.typeahead/glyph "①" :seon.typeahead/locked-count 1
       :seon.typeahead/margin 7.5 :seon.typeahead/eos-logprob -6.4
       :seon.typeahead/forwards 4 :seon.typeahead/gen-s 1.7
       :seon.typeahead/entropy-worst 0.42
       :seon.typeahead/prompt-tokens 812
       :seon.typeahead/worker-sha "c88acc1913c4"
       :seon.typeahead/agent [:seon.agent/id a-id]}
      {:seon.typeahead/call "call2" :seon.typeahead/step-idx 1
       :seon.typeahead/at (js/Date. 6000) :seon.typeahead/transition :expand
       :seon.typeahead/glyph "②" :seon.typeahead/locked-count 0
       :seon.typeahead/prompt-tokens 812
       :seon.typeahead/draft-preview "(my.plan/step! {:my.plan/title "
       :seon.typeahead/draft-tokens 9
       :seon.typeahead/agent [:seon.agent/id a-id]}]}))

(deftest tile-nil-when-no-steps
  (async done
    (-> (with-conn
          (fn [conn]
            (is (nil? (ts/steps-tile-html {:seon.db/db @conn
                                           :seon.agent/id a-id}))
                "no step rows → nil (the tile body vanishes)")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(defn- seed-buffer-step!
  "One call3 expand row carrying the FULL buffer/offers/holes picture."
  []
  (db/transact!
    {:seon.db/tx-data
     [{:seon.typeahead/call "call3" :seon.typeahead/step-idx 0
       :seon.typeahead/at (js/Date. 9000)
       :seon.typeahead/transition :expand
       :seon.typeahead/glyph "①" :seon.typeahead/locked-count 1
       :seon.typeahead/eos-logprob -3.1
       :seon.typeahead/gen-s 1.2
       :seon.typeahead/prompt-tokens 640
       :seon.typeahead/worker-sha "af497238f289"
       :seon.typeahead/max-rounds 8
       :seon.typeahead/committed-tokens 12
       :seon.typeahead/auto-offer-margin 6.0
       :seon.typeahead/rounds-used 1
       :seon.typeahead/round-budget 2
       :seon.typeahead/buffer-preview "(todo/add! \"buy milk\")\n(def x 1"
       :seon.typeahead/buffer-spans
       (pr-str [[0 22 :locked] [22 23 :clamped]
                [23 31 :resolving] [31 31 :frontier]])
       :seon.typeahead/offers-edn
       (pr-str [{:seon.typeahead/glyph "①"
                 :seon.typeahead/label "todo/add!"
                 :seon.typeahead/cal 7.5
                 :seon.typeahead/state :fired}
                {:seon.typeahead/glyph "②"
                 :seon.typeahead/label "db/query"
                 :seon.typeahead/state :suppressed
                 :seon.typeahead/reason :failed-before}])
       :seon.typeahead/holes-edn
       (pr-str [{:seon.typeahead/worst 0.42
                 :seon.typeahead/accepted true
                 :seon.typeahead/round 0
                 :seon.typeahead/chosen-length 8}
                {:seon.typeahead/worst 2.1
                 :seon.typeahead/accepted false}])
       :seon.typeahead/agent [:seon.agent/id a-id]}]}))

(deftest tile-buffer-offers-holes-panels
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-buffer-step!)
                (.then
                  (fn [res]
                    (ok! res)
                    (let [tile (ts/steps-tile-html {:seon.db/db @conn
                                                    :seon.agent/id a-id})
                          s    (pr-str tile)]
                      ;; 1. state banner
                      (is (str/includes? s "● expand") "FSM state dot+text")
                      (is (str/includes? s "step 1/8") "step k/N from max-rounds")
                      (is (str/includes? s "rounds 1/2") "expansion round usage")
                      ;; 2. the code-buffer pane + legend
                      (is (str/includes? s "code buffer") "pane header present")
                      (is (str/includes? s "buy milk") "span text painted")
                      (is (str/includes? s "▌") "frontier cursor glyph")
                      (is (str/includes? s "resolving") "legend decodes statuses")
                      (is (str/includes? s "repaired") "legend covers repaired")
                      (is (str/includes? s "committed ~12 tok")
                          "harvest size in TOKENS")
                      ;; 3. offers panel
                      (is (str/includes? s "fire ≥ 6") "threshold in the header")
                      (is (str/includes? s "● fired") "fired offer state")
                      (is (str/includes? s "suppressed (failed-before)")
                          "loop-side suppression reason surfaced")
                      ;; 4. holes panel
                      (is (str/includes? s "expand holes") "holes header")
                      (is (str/includes? s "✓ settled") "accepted hole")
                      (is (str/includes? s "○ unsettled") "unaccepted hole honest")
                      (is (str/includes? s "len 8") "CAL-chosen length")
                      ;; 5. done-ness strip
                      (is (str/includes? s "tok harvested") "harvest totals")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest tile-panels-vanish-without-projection-attrs
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-steps!)
                (.then
                  (fn [res]
                    (ok! res)
                    (let [tile (ts/steps-tile-html {:seon.db/db @conn
                                                    :seon.agent/id a-id})
                          s    (pr-str tile)]
                      (is (vector? tile) "tile still renders")
                      (is (not (str/includes? s "code buffer"))
                          "no buffer rows → no buffer pane")
                      (is (not (str/includes? s "fire ≥"))
                          "no offers rows → no offers panel")
                      (is (not (str/includes? s "expand holes"))
                          "no holes rows → no holes panel")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest tile-renders-latest-call-with-header-and-draft
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-steps!)
                (.then
                  (fn [res]
                    (ok! res)
                    (let [tile (ts/steps-tile-html {:seon.db/db @conn
                                                    :seon.agent/id a-id})
                          s    (pr-str tile)]
                      (is (vector? tile) "step rows → hiccup tile")
                      (is (str/includes? s "2 steps")
                          "latest call only — call1's row not counted")
                      (is (str/includes? s "c88acc19")
                          "worker sha, short form, in the call header")
                      (is (str/includes? s "ctx ~812 tok")
                          "render size in TOKENS in the call header")
                      (is (str/includes? s "→⊢")
                          "fired offer that locked → the locked tag")
                      (is (str/includes? s "✗offer")
                          "fired offer that locked nothing → the failed tag")
                      (is (str/includes? s "1.7s") "wall per step")
                      (is (str/includes? s "draft ~9 tok")
                          "draft preview sized in TOKENS, never chars")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
