#!/usr/bin/env bb
;; min-extract.bb AGENT_ID OUT_TXT DB_NAME — dump an agent's full transcript
;; from cluster DB_NAME (store data/clusters/DB_NAME/store, blobs
;; data/clusters/DB_NAME/blobs): every turn (prompt blob verbatim, reply blob
;; verbatim, evals with source/result) in chronological order, plus a META
;; line per turn carrying the telemetry attrs this run measures
;; (llm-usage / usage-estimated? / results-stripped / llm-retries).
;; Parameterized descendant of tmp/gram-extract.bb (which hardcoded :gram-d).
(require '[clojure.edn :as edn]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(def agent-id (first *command-line-args*))
(def out-txt  (second *command-line-args*))
(def db-name  (nth *command-line-args* 2))
(def blobs-dir (str "data/clusters/" db-name "/blobs"))

(def expr
  (format
   "(do (seon.server.registry/ensure-db! {:seon.server.registry/db-name :%s :seon.server.registry/path \"data/clusters/%s/store\"})
    (let [conn (:seon.server.registry/conn (seon.server.registry/get-conn {:seon.server.registry/db-name :%s}))
          db (deref conn)
          turns (->> (datahike.api/q '[:find ?t :in $ ?id :where
                                       [?a :seon.agent/id ?id]
                                       [?r :seon.agent.run/agent ?a]
                                       [?t :seon.agent.turn/run ?r]] db %s)
                     (map first) sort)]
      {:turns (vec (for [t turns]
        (datahike.api/pull db '[* {:seon.agent.turn/prompt-blob [:my.blob/hash]}
                                  {:seon.agent.turn/reply-blob [:my.blob/hash]}
                                  {:seon.agent.turn/evals [*]}] t)))}))"
   db-name db-name db-name (pr-str agent-id)))

(def res (sh "bin/seon-server-call" "--timeout" "60" expr))
(when-not (zero? (:exit res))
  (binding [*out* *err*] (println "seon-server-call failed:" (:err res) (:out res)))
  (System/exit 2))

(defn blob-text [hash]
  (when hash
    (let [f (java.io.File. (str blobs-dir "/" (subs hash 0 2) "/" hash))]
      (if (.exists f) (slurp f) (str "<<BLOB MISSING ON DISK: " hash ">>")))))

(def data (edn/read-string {:default (fn [_ v] v)} (:out res)))

(def lines
  (for [[i t] (map-indexed vector (:turns data))]
    (let [ph (get-in t [:seon.agent.turn/prompt-blob :my.blob/hash])
          rh (get-in t [:seon.agent.turn/reply-blob :my.blob/hash])]
      (str "════════ TURN " (inc i) "  eid=" (:db/id t)
           "  id=" (:seon.agent.turn/id t)
           "  status=" (:seon.agent.turn/status t)
           "  rendered-as-of=" (:seon.agent.turn/rendered-as-of t)
           "  at=" (:seon.agent.turn/at t) " ════════\n"
           ;; telemetry line — parsed by usage-summary.py; keep the key=… shape
           "META usage=" (pr-str (:seon.agent.turn/llm-usage t))
           " estimated=" (boolean (:seon.agent.turn/usage-estimated? t))
           " results-stripped=" (or (:seon.agent.turn/results-stripped t) 0)
           " retries=" (or (:seon.agent.turn/llm-retries t) 0) "\n"
           (when (:seon.agent.turn/error t)
             (str "!! turn error: " (:seon.agent.turn/error t) "\n"))
           "──── PROMPT (blob " ph ") ────\n" (blob-text ph)
           "\n──── REPLY (blob " rh ") ────\n" (blob-text rh)
           "\n──── EVALS (" (count (:seon.agent.turn/evals t)) ") ────\n"
           (str/join "\n"
             (for [e (:seon.agent.turn/evals t)]
               (str "  ── eval eid=" (:db/id e) " id=" (:seon.eval/id e)
                    " ok?=" (:seon.eval/ok? e) " at=" (:seon.eval/at e) "\n"
                    "  source:\n" (:seon.eval/source e) "\n"
                    (when (:seon.eval/error e)
                      (str "  error: " (:seon.eval/error e) "\n"))
                    (when (:seon.eval/output e)
                      (str "  output: " (:seon.eval/output e) "\n"))
                    "  result-edn: " (:seon.eval/result-edn e) "\n")))
           "\n"))))

(spit out-txt (str "AGENT " agent-id "  db=" db-name "  (" (count (:turns data)) " turns)\n\n"
                   (str/join "" lines)))
(println "wrote" out-txt "turns=" (count (:turns data)))
