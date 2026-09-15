(require '[clojure.edn] '[clojure.string] '[my.message] '[seon.db] '[seon.effect]
         '[seon.env] '[seon.error] '[seon.operator] '[seon.repl] '[seon.sci.eval])

; Run with load-file on the default JVM REPL. Reads facts and writes this
; lane's evidence file only; the invalid call cannot send a message.
(let [connection (seon.operator/connection "default")
      database @connection
      before (seon.db/q
              '[:find ?id ?source ?shown
                :where [?e :seon.cluster.eval/id ?id]
                [?e :seon.cluster.eval/source ?source]
                [?e :seon.eval/shown ?shown]
                [(contains? #{"332ad81ea88e"} ?id)]] database)
      docs-before (seon.db/q
                   '[:find ?id ?source ?shown ?at
                     :where [?e :seon.cluster.eval/id ?id]
                     [?e :seon.cluster.eval/source ?source]
                     [?e :seon.eval/shown ?shown]
                     [?e :seon.cluster.eval/at ?at]
                     [(contains? #{"(doc my.message/send)" "(dir my.message)"} ?source)]] database)
      supplied-schemas
      (seon.db/q
       '[:find ?schema-key ?entry-key
         :where [?schema :seon.schema/key ?schema-key]
         [?schema :seon.schema/shape ?shape]
         [?shape :seon.schema.shape/entries ?entry]
         [?entry :seon.schema.shape.entry/optional? false]
         [?entry :seon.schema.map-entry/key-keyword ?entry-key]
         [?entry :seon.schema.shape.entry/schema ?value-shape]
         [?supplier :seon.call-preparation/key ?entry-key]
         [?supplier :seon.call-preparation/schema ?value-schema]
         [?value-schema :seon.schema/shape ?value-shape]] database)
      positional
      (seon.db/q
       '[:find ?sym ?order ?index ?entry-key
         :where [?function :seon.fn/sym ?sym]
         [?function :seon.fn/arities ?arity]
         [?arity :seon.fn.arity/order ?order]
         [?arity :seon.fn.arity/arguments ?argument]
         [?argument :seon.fn.argument/index ?index]
         [?argument :seon.fn.argument/rest? false]
         [?argument :seon.fn.argument/schema ?shape]
         [?supplier :seon.call-preparation/schema ?schema]
         [?supplier :seon.call-preparation/key ?entry-key]
         [?schema :seon.schema/shape ?shape]] database)
      namespaces (seon.db/q '[:find [?name ...] :where [_ :seon.ns/name ?name]] database)
      directories (mapv #(seon.sci.eval/directory-value database % true)
                        (sort (filter #(clojure.string/starts-with? (str %) "my.") namespaces)))
      callers (seon.db/q
               '[:find ?sym ?schema-key
                 :where [?function :seon.fn/sym ?sym]
                 [?function :seon.fn/private? false]
                 [?function :seon.fn/arities ?arity]
                 [?arity :seon.fn.arity/input-refs ?schema]
                 [?schema :seon.schema/key ?schema-key]] database)
      expected (reduce (fn [result [sym schema-key]]
                         (let [entry-keys (map second (filter #(= schema-key (first %)) supplied-schemas))]
                           (if (and (clojure.string/starts-with? sym "my.") (seq entry-keys))
                             (update result (symbol sym) (fnil into #{}) entry-keys) result)))
                       {} callers)
      functions (vec (mapcat :functions directories))
      unmarked (vec (for [[sym entry-keys] expected
                         :let [row (some #(when (= sym (:sym %)) %) functions)]
                         :when (not= entry-keys (set (:supplied row)))]
                     [sym entry-keys (:supplied row)]))
      _ (assert (and (= 1 (count before)) (seq expected) (empty? unmarked)))
      environment (seon.env/environment {:seon.boot/cluster-name "default"
                                         :seon.db/connection connection})
      refusal (binding [seon.effect/*request-context* {:seon.env/environment environment}]
                (try
                  (my.message/send (assoc (second (clojure.edn/read-string (second (first before))))
                                          :seon.db/connection connection :seon.agent/id "juniper"))
                  (catch Exception failure (ex-data failure))))
      documentation (seon.sci.eval/documentation-value database 'my.message/send 'my.message/send)
      result {:probe/basis (seon.db/basis-t database)
              :probe/before before :probe/docs-before docs-before
              :probe/doc-after documentation
              :probe/dir-after (seon.repl/render-directory-ai
                               (seon.sci.eval/directory-value database 'my.message true))
              :probe/refusal-after refusal
              :probe/refusal-shown-after (seon.error/render-ai
                                           (assoc refusal :seon.error/doc documentation))
              :probe/supplied-schemas (vec (sort (filter #(clojure.string/starts-with? (namespace (first %)) "my.") supplied-schemas)))
              :probe/positional (vec (sort (filter #(clojure.string/starts-with? (first %) "my.") positional)))
              :probe/expected-supplied expected
              :probe/audit-unmarked unmarked
              :probe/functions functions}]
  (spit "docs/prds/context-generation/research/supplied-keys-evidence-2026-09-15.edn" (pr-str result))
  (select-keys result [:probe/basis :probe/supplied-schemas :probe/positional]))
