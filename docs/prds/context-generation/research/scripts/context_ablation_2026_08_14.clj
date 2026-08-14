(ns context-ablation-2026-08-14
  "Run one paid context-ablation variant through Seon's provider owner."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.ai :as ai]
            [seon.cluster.reply :as reply]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(def ^:private capture-sha-256
  "8422c3e18e05f59501eebe46f550f7c3f97f91d8a403a55b75df7b817e4b355b")

(def ^:private agent-namespace
  'my.agents.drive-one-agent-attempt-5)

(def ^:private medium-reminder
  (str "\n\nYour reply is read as forms and evaluated in your namespace. "
       "Prose alone runs nothing; include at least one Clojure form."))

(def ^:private primary-target
  {:seon.ai/chars-per-token-prior 3.2
   :seon.ai/prompt-token-budget 32768
   :seon.ai/thinking :disabled
   :seon.ai/api-key-variable "DEEPSEEK_API_KEY"
   :seon.ai/model "deepseek-v4-flash"
   :seon.ai/timeout-ms 180000
   :seon.ai.model/output-token-wire-key "max_tokens"
   :seon.ai/endpoint "https://api.deepseek.com/chat/completions"
   :seon.ai/max-tokens 65536})

(def ^:private entry-prefix
  "my.agents.drive-one-agent-attempt-5=> ")

(defn- entry-position
  [prompt form]
  (or (str/index-of prompt (str entry-prefix form))
      (throw (ex-info "The captured prompt lacks an ablation boundary."
                      {::missing-boundary form}))))

(defn- trim-toolkit
  [prompt]
  (let [web (entry-position prompt "(dir (quote my.web))")
        message (entry-position prompt "(dir (quote my.message))")
        note (entry-position prompt "(dir (quote my.note))")
        plan (entry-position prompt "(dir (quote my.plan))")
        root (entry-position
              prompt
              "(db/pull db (quote [*]) [:seon.cluster.agent/id \"root\"])")
        agent-entry (entry-position
                     prompt
                     "(dir (quote my.agents.drive-one-agent-attempt-5))")
        bootstrap (entry-position prompt "(dir (quote seon.bootstrap))")
        database (entry-position prompt "(dir (quote seon.db))")
        task-run
        (entry-position
         prompt
         (str "(db/pull db (quote [*]) [:seon.cluster.run/id "
              "\"a887d305-c8ae-4b6e-842f-43287f7f7496\"])"))
        original-toolkit-characters (- task-run web)
        kept-toolkit-characters
        (+ (- note message)
           (- root plan)
           (- bootstrap agent-entry)
           (- task-run database))]
    {::prompt
     (str (subs prompt 0 web)
          (subs prompt message note)
          (subs prompt plan root)
          (subs prompt agent-entry bootstrap)
          (subs prompt database task-run)
          (subs prompt task-run))
     ::original-toolkit-characters original-toolkit-characters
     ::kept-toolkit-characters kept-toolkit-characters
     ::kept-toolkit-fraction
     (/ (double kept-toolkit-characters) original-toolkit-characters)}))

(defn- variant
  [variant-name prompt]
  (case variant-name
    ;; The exact /ai capture already carries the whole fenced defn. V1 is an
    ;; identical-byte control, and V3 therefore has the same bytes as V2.
    "V1" {::prompt prompt ::delta :complete-demo-already-present}
    "V2" {::prompt (str prompt medium-reminder)
          ::delta :tail-medium-reminder
          ::appended-text medium-reminder}
    "V3" {::prompt (str prompt medium-reminder)
          ::delta :complete-demo-already-present-plus-tail-reminder
          ::appended-text medium-reminder}
    "V4" (assoc (trim-toolkit prompt) ::delta :toolkit-trimmed)
    (throw (ex-info "Unknown context-ablation variant."
                    {::variant variant-name}))))

(defn- reader-verdict
  [text]
  (let [parsed (reply/sources text agent-namespace)]
    (if (:seon.error/kind parsed)
      {::form-emission? false
       ::reader-error
       (select-keys parsed [:seon.error/kind
                            :seon.error/message
                            :seon.cluster.reply/text])}
      {::form-emission? true
       ::form-count (count parsed)
       ::sources parsed})))

(defn- run-variant
  [variant-name prompt]
  (schema/call-with-forms
   (schema.edn/packaged-forms)
   (fn []
     (let [actual-sha
           (schema/sha-256 [(.getBytes ^String prompt "UTF-8")])]
       (when-not (= capture-sha-256 actual-sha)
         (throw (ex-info "The extracted prompt does not match the capture."
                         {::expected-sha-256 capture-sha-256
                          ::actual-sha-256 actual-sha})))
       (let [{variant-prompt ::prompt :as variant-data}
             (variant variant-name prompt)
             completion
             (ai/complete
              (assoc primary-target
                     :seon.ai/prompt variant-prompt
                     :seon.ai/stream? true
                     :seon.ai/sink (fn [_] nil)))
             text (:seon.ai/text completion)]
         (cond->
          (merge
           {::variant variant-name
            ::capture-sha-256 actual-sha
            ::prompt-characters (count variant-prompt)
            ::prompt-utf8-bytes
            (alength (.getBytes ^String variant-prompt "UTF-8"))
            ::completion
            (select-keys completion
                         [:seon.ai/text
                          :seon.ai/usage
                          :seon.ai/tokens
                          :seon.ai/finish-reason
                          :seon.ai/truncation
                          :seon.ai.model/last-latency-ms
                          :seon.error/kind
                          :seon.error/message
                          :seon.ai/provider-error])}
           (dissoc variant-data ::prompt))
           text (assoc ::reply-reader (reader-verdict text))))))))

(defn -main
  "Run exactly one named paid variant against the captured prompt."
  [& [variant-name prompt-path result-path]]
  (when-not (and variant-name prompt-path)
    (throw
     (ex-info "Usage: SCRIPT V1|V2|V3|V4 PROMPT-PATH [RESULT-PATH]" {})))
  (let [prompt (slurp (io/file prompt-path))
        result (run-variant variant-name prompt)]
    (when result-path
      (let [result-file (io/file result-path)]
        (io/make-parents result-file)
        (spit result-file (str (pr-str result) "\n"))))
    (prn
     (-> result
         (update ::completion dissoc :seon.ai/text)
         (update ::reply-reader dissoc ::sources)))))
