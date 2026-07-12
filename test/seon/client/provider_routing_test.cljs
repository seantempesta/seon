(ns seon.client.provider-routing-test
  "Per-agent PROVIDER routing (task #88 — config-driven agent-init follow-on).

   The gap this closes: config-init made per-agent model/temperature/
   max-tokens/thinking flow PER CALL (via `seon.ai/effective-config-for` /
   `current`), but the ADAPTER (which provider's HTTP path) was chosen ONCE at
   agent re-arm from the GLOBAL provider — so an agent could NOT use a
   different provider than the cluster default.

   The lower `seon.ai.dispatch/llm-fn` returns a dispatching closure that
   selects the adapter PER CALL via `(seon.ai/provider)`. Inside a turn the
   call runs in `db/with-agent id`, so `(seon.ai/provider)` resolves that
   agent's `:seon.ai/agent-provider` overlay — routing to ITS adapter. A
   no-override / `:inherit` agent resolves the GLOBAL provider (byte-parity).

   These tests MOCK the adapters (with-redefs) — NO live API calls. Each
   mocked adapter returns a fn tagging its provider so the routing target is
   observable. The API-key gates are also mocked true so routing (not a
   missing key → stub) is what's under test.

   Placeholder ids use `:seon.db/id`-shaped strings (14 chars)."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.ai :as ai]
    [seon.ai.anthropic :as anthropic]
    [seon.ai.dispatch :as dispatch]
    [seon.ai.openai-compat :as openai]
    [seon.config :as config]
    [seon.db :as db]))

;; A mocked adapter constructor: returns a fn-of-ctx that resolves to a map
;; tagging which provider handled the call — the observable routing target.
(defn- tagging-adapter
  "A stand-in for a provider's `agent-adapter` — matches its `:function`
   schema arities (0-arity + 1-arity opts) so `with-redefs` over the
   arity-dispatched compiled call site resolves. Returns a fn-of-ctx that
   resolves to a map tagging which provider handled the call."
  [provider-kw]
  (let [make (fn [] (fn [_ctx] (js/Promise.resolve {:text (str provider-kw) ::provider provider-kw})))]
    (fn
      ([] (make))
      ([_opts] (make)))))

(defn- fresh-conn
  "A :memory conn with the ai config attrs + the per-agent override attr +
   the agent-id identity installed (overlay-agent-overrides gates on the attr
   being installed, else datahike-cljs throws querying it)."
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact!
                       conn
                       {:tx-data (into (db/malli->datahike-schema
                                         [::ai/id ::ai/provider ::ai/model
                                          ::ai/agent-provider :seon.agent/id])
                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_] conn))))))))

(deftest per-agent-provider-selects-its-adapter-inside-the-agent-scope
  (async done
    (-> (fresh-conn)
        (.then
          (fn [conn]
            (let [orig db/*conn*]
              (set! db/*conn* conn)
              ;; Global provider = :deepseek (the :openai-compat/deepseek adapter).
              ;; One agent OVERRIDES to :anthropic; one INHERITS (no override).
              (-> (d/transact!
                    conn
                    {:tx-data
                     [{::ai/id "config" ::ai/provider :deepseek}
                      {:seon.agent/id "ovr-2607011800" :seon.ai/agent-provider :anthropic}
                      {:seon.agent/id "inh-2607011800"}]})
                  (.then
                    (fn [_]
                      ;; Mock the adapters + key gates: routing, not key presence.
                      (with-redefs [anthropic/agent-adapter (tagging-adapter :anthropic)
                                    openai/agent-adapter     (tagging-adapter :deepseek)
                                    config/anthropic-api-key (fn [] "test-key")
                                    openai/api-key-configured? (fn [] true)]
                        (let [llm-fn (dispatch/llm-fn)]
                          ;; Provider resolution proof (no scope = global).
                          (is (= :deepseek (ai/provider))
                              "outside an agent scope → the global provider")
                          ;; Inside the OVERRIDE agent's scope, provider resolves :anthropic.
                          (db/with-agent "ovr-2607011800"
                            (fn [] (is (= :anthropic (ai/provider))
                                       "agent's :seon.ai/agent-provider overlay resolves")))
                          ;; The DISPATCHING llm-fn routes PER CALL:
                          (js/Promise.all
                            #js [(db/with-agent "ovr-2607011800" (fn [] (llm-fn "ctx")))
                                 (db/with-agent "inh-2607011800" (fn [] (llm-fn "ctx")))
                                 (llm-fn "ctx")]))))) ; no scope → global
                  (.then
                    (fn [results]
                      (let [[ovr inh none] (array-seq results)]
                        (is (= :anthropic (::provider ovr))
                            "override agent routed to the ANTHROPIC adapter (its own provider)")
                        (is (= :deepseek (::provider inh))
                            "no-override agent routed to the GLOBAL (deepseek) adapter")
                        (is (= :deepseek (::provider none))
                            "outside any agent scope → the GLOBAL adapter"))))
                  (.finally (fn [] (set! db/*conn* orig)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest inherit-provider-uses-the-global-adapter
  ;; :inherit (the default value of ::agent-provider) MUST resolve the global
  ;; provider — byte-parity with the pre-#88 single-adapter behavior.
  (async done
    (-> (fresh-conn)
        (.then
          (fn [conn]
            (let [orig db/*conn*]
              (set! db/*conn* conn)
              (-> (d/transact!
                    conn
                    {:tx-data
                     [{::ai/id "config" ::ai/provider :anthropic}
                      ;; explicit :inherit (not merely absent)
                      {:seon.agent/id "inh-2607011801" :seon.ai/agent-provider :inherit}]})
                  (.then
                    (fn [_]
                      (with-redefs [anthropic/agent-adapter (tagging-adapter :anthropic)
                                    openai/agent-adapter     (tagging-adapter :deepseek)
                                    config/anthropic-api-key (fn [] "test-key")
                                    openai/api-key-configured? (fn [] true)]
                        (db/with-agent "inh-2607011801"
                          (fn []
                            (is (= :anthropic (ai/provider))
                                ":inherit resolves the GLOBAL provider")
                            ((dispatch/llm-fn) "ctx"))))))
                  (.then
                    (fn [r]
                      (is (= :anthropic (::provider r))
                          ":inherit routed to the GLOBAL (anthropic) adapter")))
                  (.finally (fn [] (set! db/*conn* orig)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
