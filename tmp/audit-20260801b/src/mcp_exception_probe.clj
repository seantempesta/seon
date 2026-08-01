(ns mcp-exception-probe
  (:require [clojure.edn :as edn]))

(load-file "script/seon/dev/mcp.clj")

(defn run-probe
  "Show which synthetic first-party frames the MCP exception projection retains."
  [& _]
  (let [project (ns-resolve 'seon.dev.mcp 'project-exception-value)
        content-text (ns-resolve 'seon.dev.mcp 'content-text)
        requested-output-tokens
        (ns-resolve 'seon.dev.mcp '*requested-output-tokens*)
        input (pr-str {:cause "boom"
                       :phase :execution
                       :via [{:type 'java.lang.Exception :message "boom"}]
                       :trace [['my.agents.audit$explode 'invoke "audit.clj" 7]
                               ['seon.audit$explode 'invoke "audit.clj" 8]
                               ['user$eval42 'invoke "NO_SOURCE_FILE" 9]]})
        projected (edn/read-string (project input))
        oversized-form (apply str (repeat 20000 "x"))
        encoded
        (with-bindings {requested-output-tokens 128}
          (content-text
           {:seon.dev.mcp/events
            [{:tag :ret :val "ok" :form oversized-form}]}))]
    (prn {:audit/projected-trace (:trace projected)
          :audit/frames-omitted (:seon.dev.mcp/frames-omitted projected)
          :audit/requested-char-estimate (* 4 128)
          :audit/encoded-structured-chars (count encoded)})))

(apply run-probe *command-line-args*)
