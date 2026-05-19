(ns seon.client
  "V0 CLJS pod entry point. Long-running Node process; the V0 client.

   Two responsibilities:

     1. Run the smoke test on boot — proves datahike-cljs is alive.
     2. Boot the V0 agent on demand via `start-agent!`.

   How to run it:

     ;; Terminal 1 — the watcher (compiles + writes nREPL port file)
     clj -M:cljs watch client

     ;; Terminal 2 — the Node host
     node out/client/main.js

     ;; Editor / MCP — connect to nREPL on localhost:7889, then
     ;; pivot into the running CLJS runtime:
     (shadow.cljs.devtools.api/nrepl-select :client)

     ;; To bring up the V0 agent (stub LLM):
     (cljs.core.async/go
       (cljs.core.async/<! (seon.client/start-agent-with-stub!)))

     ;; Then chat with it:
     (seon.agent/chat \"seon\" \"hello\")"
  (:require
    [datahike.api :as d]
    ;; Pull in the agent's required namespaces at compile time so all
    ;; schemas are registered before start-agent! runs.
    [seon.agent :as agent]
    [seon.ai.deepseek :as deepseek]
    [seon.db :as db]
    [seon.eval :as seval]
    ;; Render protocol — A-2. Required here so the build includes it
    ;; AND so `use-compile-state!` is callable from start-agent!.
    [seon.render :as render]
    [seon.render.default]))

;; ---------------------------------------------------------------------------
;; Process-lifetime state. `defonce` so reloads don't reset it.
;; ---------------------------------------------------------------------------

(defonce !state
  (atom {:boot-at      (.toISOString (js/Date.))
         :reload-count 0
         :heartbeat-id nil}))

(defn start-heartbeat!
  "Holds the Node event loop open with a minute-cadence heartbeat. The
   real V0 client will keep the loop alive via pending agent-loop work;
   for now this is the simplest 'process stays open' contract."
  []
  (let [id (js/setInterval
             (fn []
               (js/console.log "[client] heartbeat" (.toISOString (js/Date.))))
             60000)]
    (swap! !state assoc :heartbeat-id id)))

(defn stop-heartbeat! []
  (when-let [id (:heartbeat-id @!state)]
    (js/clearInterval id)
    (swap! !state assoc :heartbeat-id nil)))

(defn ^:dev/before-load before-reload []
  (js/console.log "[client] reloading…")
  (stop-heartbeat!))

(defn ^:dev/after-load after-reload []
  (swap! !state update :reload-count inc)
  (js/console.log
    (str "[client] reload #" (:reload-count @!state)
         " — booted " (:boot-at @!state)))
  (start-heartbeat!))

;; ---------------------------------------------------------------------------
;; datahike-cljs smoke test — proves the substrate works end-to-end.
;;
;; This is the canonical 'is datahike-cljs alive?' check. Useful as:
;;   - boot-time verification (`-main` runs it),
;;   - REPL-callable health probe (`(datahike-smoke-test!)`),
;;   - reference for how to use datahike-cljs from agent code.
;; ---------------------------------------------------------------------------

(def ^:private smoke-schema
  [{:db/ident       :name
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string}
   {:db/ident       :rank
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/long}])

(def ^:private smoke-seed
  [{:name "Alpha"    :rank 1}
   {:name "Seon"     :rank 2}
   {:name "Datahike" :rank 3}])

(def ^:private smoke-expected
  #{["Alpha" 1] ["Seon" 2] ["Datahike" 3]})

(defn ^:async datahike-smoke-test!
  "Create a fresh :memory datahike-cljs DB, transact schema + seed entities,
   query, compare to expected. Returns a Promise resolving to
   {:status :pass :datoms <n> :rows <set>} or
   {:status :fail :got <set> :expected <set>}.

   REPL usage:
     (.then (datahike-smoke-test!) println)"
  []
  (let [cfg {:store              {:backend :memory
                                  :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      false}]
    (await (d/create-database cfg))
    (let [conn (await (d/connect cfg))
          _    (await (d/transact! conn smoke-schema))
          tx   (await (d/transact! conn smoke-seed))
          rows (d/q '[:find ?name ?rank
                      :where
                      [?e :name ?name]
                      [?e :rank ?rank]]
                    @conn)]
      (if (= rows smoke-expected)
        {:status :pass :datoms (count (:tx-data tx)) :rows rows}
        {:status :fail :got rows :expected smoke-expected}))))

(defn ^:async mem-db
  "REPL convenience — open a fresh :memory datahike-cljs DB with optional
   schema. Returns a Promise resolving to a conn atom."
  ([] (mem-db []))
  ([schema]
   (let [cfg {:store              {:backend :memory
                                   :id (random-uuid)}
              :schema-flexibility :write
              :keep-history?      false}]
     (await (d/create-database cfg))
     (let [conn (await (d/connect cfg))]
       (when (seq schema)
         (await (d/transact! conn schema)))
       conn))))

;; ---------------------------------------------------------------------------
;; Agent boot
;;
;; The V0 agent runs against a long-lived :memory datahike conn distinct
;; from the smoke-test's ephemeral conn. start-agent! opens it, bootstraps
;; the datahike schema (idents needed for lookup-refs), binds seon.db/*conn*
;; at the var root, and hands off to seon.agent/boot!.
;;
;; Idempotent: re-calling start-agent! reuses the existing conn (stored in
;; !agent-conn) and overwrites the kick listener. Useful during dev hot-
;; reload where the watcher rebuilds client.cljs.
;; ---------------------------------------------------------------------------

(defonce !agent-conn (atom nil))
(defonce !compile-state (atom nil))

;; Datahike-side schema. Datahike requires every attribute have a
;; declared :db/valueType + :db/cardinality before first use — our
;; seon.schema Malli registry only handles pre-transact validation,
;; not storage shape. Eventually we'd derive this from seon.schema
;; (a generic Malli→datahike bridge), but for V0 we hand-write the
;; small attribute surface here.
(def ^:private agent-bootstrap-schema
  ;; --- Agent ---
  [{:db/ident :seon.agent/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.agent/state
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.agent/turn-count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}

   ;; --- Message ---
   {:db/ident :seon.message/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.message/role
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.message/content
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.message/agent
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.message/at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   ;; --- Eval ---
   {:db/ident :seon.eval/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.eval/agent
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.eval/at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.eval/turn
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.eval/narration
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.eval/source
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.eval/ok?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.eval/result-edn
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.eval/error
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(defn ^:async open-agent-conn! []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? false}]
    (await (d/create-database cfg))
    (let [conn (await (d/connect cfg))]
      (await (d/transact! conn agent-bootstrap-schema))
      conn)))

(defn- stub-llm
  "A fake LLM that demonstrates the REPL-as-harness response shape: a
   `;; narration` line then a real `seon.db/transact!` form, then
   another narration + the state-flip-to-idle form. The agent reads
   its own eval log in subsequent turns and learns by mimicking what
   it sees here. Returns a Promise of {:text \"...\"}."
  [ctx]
  (let [text (str
               ";; stub LLM here — the real one needs DEEPSEEK_API_KEY\n"
               ";; reply to the user\n"
               "(seon.db/transact!\n"
               "  {:seon.db/tx-data\n"
               "   [{:seon.message/id      (seon.agent/new-id!)\n"
               "     :seon.message/role    :assistant\n"
               "     :seon.message/content "
               (pr-str (str "hello from the stub LLM — saw "
                            (count ctx) " chars of ctx"))
               "\n"
               "     :seon.message/agent   [:seon.agent/id (session-id)]\n"
               "     :seon.message/at      (js/Date.)}]})\n\n"
               ";; halt the loop\n"
               "(seon.db/transact!\n"
               "  {:seon.db/tx-data\n"
               "   [{:seon.agent/id     (session-id)\n"
               "     :seon.agent/state  :idle}]})\n")]
    (.then (.resolve js/Promise nil) (fn [_] {:text text}))))

(defn ^:async start-agent!
  "Bring up the V0 agent: open conn, init bootstrap-CLJS, prime the
   agent's home namespace with !session-id / !results / !current-ns
   atoms, then boot the session loop.

     :llm-fn — fn of ctx-string returning a Promise of {:text \"...\"}.
               Optional; defaults to stub-llm for verification without
               an API key. Pass (seon.ai.deepseek/agent-adapter) for
               the real thing.

   Returns a Promise resolving to
     {:seon.agent/id _ :seon.agent/ns _}.
   Subsequent (seon.agent/chat ...) calls drive the loop via the
   kick listener."
  [& [{:keys [llm-fn] :or {llm-fn stub-llm}}]]
  (let [conn          (or @!agent-conn (await (open-agent-conn!)))
        _             (reset! !agent-conn conn)
        ;; Bind the conn as the root *conn* so seon.db calls + the
        ;; kick listener resolve without per-call threading.
        _             (set! db/*conn* conn)
        ;; Bootstrap-CLJS init — defonce-equivalent (idempotent).
        compile-state (or @!compile-state
                          (let [s (await (seval/init-bootstrap!))]
                            (reset! !compile-state s)
                            ;; Wire seon.render's late-bound resolver
                            ;; (A-2). A-4 will start consuming it from
                            ;; the default ctx/view fns; until then it
                            ;; stays nil-safe (every resolve → nil →
                            ;; pretty-print fallback).
                            (render/use-compile-state! !compile-state)
                            s))
        ;; Prime the agent's home namespace with the atoms + accessors.
        ;; agent-ns-sym is the V0 default (`seon.agent.seon`); when
        ;; multi-agent comes, derive per-id via (seon.agent/home-ns id).
        _             (await (seval/setup-agent-ns!
                               compile-state
                               agent/default-ns
                               agent/default-id))
        ;; Boot the turn loop (creates entity + installs kick).
        {:seon.agent/keys [id ns]}
        (await (agent/boot! llm-fn compile-state))]
    (js/console.log "[client] agent started — id" id "ns" (str ns))
    {:seon.agent/id id :seon.agent/ns ns}))

(defn start-agent-with-stub!
  "Bring up the V0 agent with the canned stub LLM. Useful for verifying
   the full loop without a deepseek API key. Returns a channel."
  []
  (start-agent!))

(defn start-agent-with-deepseek!
  "Bring up the V0 agent against the real deepseek API. Requires
   DEEPSEEK_API_KEY in process.env. Returns a channel."
  []
  (start-agent! {:llm-fn (deepseek/agent-adapter)}))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (js/console.log "[client] -main boot at" (:boot-at @!state))
  (-> (datahike-smoke-test!)
      (.then (fn [result]
               (case (:status result)
                 :pass (js/console.log
                         "[client] datahike-cljs smoke test PASS —"
                         (:datoms result) "datoms")
                 :fail (do (js/console.error "[client] datahike-cljs smoke test FAIL")
                           (js/console.error "  got:     " (pr-str (:got result)))
                           (js/console.error "  expected:" (pr-str (:expected result)))))))
      (.catch (fn [err]
                (js/console.error "[client] datahike-cljs smoke test THREW —" err))))
  (js/console.log "[client] connect editor / mcp to nREPL :7889 then")
  (js/console.log "[client]   (shadow.cljs.devtools.api/nrepl-select :client)")
  (start-heartbeat!))
