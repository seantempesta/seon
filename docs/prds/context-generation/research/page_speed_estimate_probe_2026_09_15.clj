; Run through MCP JVM mode on default; reads an immutable run-7 commit.
(require 'clojure.edn 'datahike.versioning 'seon.operator 'seon.db
         'seon.ai.tokens 'seon.id)

(let [connection (seon.operator/connection "default")
      commit #uuid "6aa966c2-14ef-5256-b705-84912d5451f3"
      database (datahike.versioning/commit-as-db
                connection commit {:sync? true :secondary-indices? false})]
  (try
    (let [rows (seon.db/q
                '[:find ?id ?at ?prompt ?characters ?usage
                  :in $ ?agent
                  :where [?agent-row :seon.agent/id ?agent]
                         [?turn :seon.turn/agent ?agent-row]
                         [?turn :seon.turn/attempts ?attempt]
                         [?attempt :seon.ai.attempt/id ?id]
                         [?attempt :seon.ai.attempt/at ?at]
                         [?attempt :seon.ai.attempt/usage-edn ?usage]
                         [?capture :seon.context.capture/run ?turn]
                         [?capture :seon.context.capture/prompt ?prompt]
                         [?capture :seon.ai.tokens/characters ?characters]]
                database "juniper")
          observations
          (mapv (fn [[id at prompt characters usage]]
                  (assert (= characters (count prompt)))
                  {:seon.ai.attempt/id id
                   :seon.ai.attempt/at at
                   :seon.ai.tokens/characters characters
                   :seon.ai.usage/prompt-tokens
                   (get (clojure.edn/read-string usage) "prompt_tokens")
                   :seon.probe/prompt-bytes (alength (.getBytes prompt "UTF-8"))
                   :seon.probe/prompt-digest (seon.id/digest 64 [prompt])})
                (sort-by (juxt second first) rows))]
      (assert (= 30 (count observations)))
      {:seon.probe/commit commit
       :seon.probe/basis (seon.db/basis-t database)
       :seon.ai.tokens/observations observations})
    (finally (datahike.versioning/release-materialized-db database))))
