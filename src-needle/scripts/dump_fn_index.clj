;; dump_fn_index.clj — KT2b fn-index dump (read-only).
;;
;; Extracts the agent-facing function surface from a live cluster store:
;; `:seon.fn` program-graph rows (sym, docstring, `:malli/schema` form,
;; arglists) for the `my.*` toolkit + the protected-floor namespaces, plus
;; the FULL `:seon.schema` key->form registry projection (needed to resolve
;; map-in request schemas transitively when translating to needle tool JSON).
;;
;; Run against the acme wire-server REPL (this lane's harness):
;;
;;   bin/acme up            # wire-server 7981 + pod 7980 (pod boot seeds/
;;                          # reconciles the :seon.fn rows from the codebase)
;;   nc -w 60 127.0.0.1 7981 < src-needle/scripts/dump_fn_index.clj
;;
;; Output: src-needle/data/fn-index.json (gitignored; the wire-server's cwd
;; is the repo root). Re-run after any toolkit change — the dump is derived,
;; never patched.

(require '[datahike.api :as d]
         '[cheshire.core :as json])

(let [conn (:conn (deref (deref (var seon.server.wire/state))))
      db   (deref conn)
      ;; The agent-facing surface: the my.* toolkit + the protected floor
      ;; (toolkit.md catalog). *.internal + per-agent home nses excluded.
      target-ns? (fn [ns-str]
                   (or (and (.startsWith ^String ns-str "my.")
                            (not (.endsWith ^String ns-str ".internal"))
                            (not (.startsWith ^String ns-str "my.agent")))
                       (contains? #{"seon.db" "seon.schema"
                                    "seon.agent.message" "seon.agent.lifecycle"
                                    "seon.agent.fs" "seon.agent.search"
                                    "seon.agent.shell" "seon.agent.web"
                                    "seon.agent.schedule" "seon.test.runner"
                                    "seon.embed" "seon.repl.autocomplete"}
                                  ns-str)))
      fns (->> (d/q '[:find [(pull ?e [:seon.fn/sym :seon.fn/doc :seon.fn/spec
                                       :seon.fn/arglists :seon.fn/private?
                                       :seon.fn/fn-var? :seon.fn/schema-error]) ...]
                      :where [?e :seon.fn/sym]]
                    db)
               (filter #(and (:seon.fn/fn-var? %)
                             (not (:seon.fn/private? %))
                             (target-ns? (namespace (symbol (:seon.fn/sym %))))))
               (sort-by :seon.fn/sym)
               vec)
      schemas (->> (d/q '[:find ?k ?src
                          :where [?e :seon.schema/key ?k]
                                 [?e :seon.schema/source ?src]]
                        db)
                   (map (fn [[k src]] [(str k) src]))
                   (into (sorted-map)))]
  (spit "src-needle/data/fn-index.json"
        (json/generate-string
         {:cluster  "acme"
          :basis-t  (:max-tx db)
          :dumped-at (str (java.time.Instant/now))
          :fn-count (count fns)
          :fns      fns
          :schemas  schemas}
         {:pretty true}))
  (println "WROTE src-needle/data/fn-index.json"
           {:fns (count fns) :schemas (count schemas) :basis-t (:max-tx db)}))
