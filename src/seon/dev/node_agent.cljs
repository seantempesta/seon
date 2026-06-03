(ns seon.dev.node-agent
  "Minimal Node 'agent' process for the multi-runtime MCP-eval go/no-go probe
   (docs/prds/agent-runtime/research/shadow-multi-runtime-mcp-eval-2026-06-03.md).

   Each invocation is ONE Node process that connects to the running shadow
   watcher (via the injected `shadow.cljs.devtools.client.node` websocket
   client) and therefore registers as ONE addressable shadow runtime keyed by
   an integer client-id. Run the `:node-agent` build N times → N distinct
   runtimes under the single `:client`/`:node-agent` worker.

   The PUBLIC handle is the agent-id (a string passed via `--agent-id <id>` on
   argv). client-id is internal shadow plumbing and is NOT stable across a
   crash+restart. So resolution is done by PROBING: the shadow JVM enumerates
   `repl-runtimes`, pins each, and evals `(agent-id)` to learn which runtime is
   which agent. A respawned agent reports the same agent-id under a new
   client-id with no extra bookkeeping — that is the no-restart survival path.

   This ns is intentionally tiny: read id, expose id, print a ready line, idle.
   No DB, no wire-server — that is out of scope for this probe."
  (:require [clojure.string :as str]))

;; The agent-id this process answers to. Set once at boot from argv/env.
(defonce ^:private !agent-id (atom nil))

(defn agent-id
  "Return this process's agent-id string (or nil before boot). This is the
   form the shadow JVM evals into each runtime to resolve agent-id -> runtime."
  []
  @!agent-id)

(defn- parse-agent-id
  "Pull the agent-id from `--agent-id <id>` argv, falling back to the
   SEON_AGENT_ID env var. Returns nil if neither is present."
  [argv]
  (or (let [v (vec argv)]
        (loop [i 0]
          (cond
            (>= i (count v)) nil
            (= "--agent-id" (nth v i)) (get v (inc i))
            :else (recur (inc i)))))
      (some-> (.. js/process -env -SEON_AGENT_ID) not-empty)))

(defn -main [& args]
  ;; shadow's :node-script main receives the post-`node script.js` argv as
  ;; `args`; also fall back to the raw process.argv (slice off node + script).
  (let [from-args (parse-agent-id args)
        from-proc (parse-agent-id (drop 2 (js->clj (.-argv js/process))))
        id (or from-args from-proc)]
    (when (str/blank? id)
      (js/console.error "node-agent: no --agent-id provided (argv or SEON_AGENT_ID)")
      (js/process.exit 2))
    (reset! !agent-id id)
    ;; Distinct, greppable ready line so the probe can confirm 3 live agents.
    (js/console.log (str "node-agent ready: agent-id=" id
                         " pid=" (.-pid js/process)))
    ;; Idle forever so the process stays a live runtime. The interval also
    ;; keeps the Node event loop non-empty (the websocket client alone is
    ;; enough, but this is explicit and survives any client teardown).
    (js/setInterval (fn [] nil) 60000)))
