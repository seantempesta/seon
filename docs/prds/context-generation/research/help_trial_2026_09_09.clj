(ns help-trial-2026-09-09
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalog.parser :as datalog]
            [sci.core :as sci]
            [seon.agent :as agent]
            [seon.ai :as ai]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.operator.runtime :as runtime]
            [seon.render :as render]
            [seon.schema :as schema]
            [seon.sci.eval :as sci-eval]
            [seon.turn :as turn]))

; The same fixture owns the scenario in tests and the production JVM.
(load-file "test/seon/context_blocks_fixture.clj")

; Load in default's JVM, then call (help-trial-2026-09-09/run!).
; prepare captures and checks one immutable database value.
; run! makes exactly ONE seon.ai/complete call. A new tuning step needs a new
; evidence path; an existing result file is never overwritten or sent again.
(def questions
  (str "\n\nComprehension trial. Answer questions 1–7 as numbered ;; comments, then write your next reply as thinking comments and Clojure forms.\n"
       "1. What should you write before a form?\n"
       "2. What comes back from each form, and when do you see it?\n"
       "3. How can you reuse a result/e... value?\n"
       "4. What should you do when unsure how to call a function?\n"
       "5. What is your current instruction?\n"
       "6. What ends a turn, what ends a session, and how many turns are left?\n"
       "7. Should you complete a step in the same reply as the form that does its work?\n"
       "8. Write your next reply.\n"))

(defn- checked [value]
  (when (:seon.error/kind value)
    (throw (ex-info (:seon.error/message value) value)))
  value)

(defn- read-forms [text]
  (try
    (binding [*read-eval* false]
      (with-open [reader (java.io.PushbackReader. (java.io.StringReader. text))]
        (loop [forms []]
          (let [form (read {:eof ::eof} reader)]
            (if (= ::eof form) forms (recur (conj forms form)))))))
    (catch Exception failure {:seon.trial/read-error (ex-message failure)})))

(defn- prompt-marker? [text]
  (boolean
   (some (fn [line]
           (when-let [index (str/index-of line "=>")]
             (let [prefix (read-forms (subs line 0 index))]
               (and (vector? prefix) (= 1 (count prefix)) (symbol? (first prefix))))))
         (str/split-lines text))))

(defn- executable-calls [forms]
  (letfn [(walk [form]
            (cond
              (and (seq? form) (= 'quote (first form))) []
              (seq? form) (concat (when (symbol? (first form)) [form]) (mapcat walk (rest form)))
              (coll? form) (mapcat walk form)
              :else []))]
    (vec (mapcat walk forms))))

(defn- query-shape? [call]
  (try
    (let [argument (second call)
          source (if (map? argument) (:query argument) argument)
          query (when (and (seq? source) (= 'quote (first source))) (second source))]
      (and query (some? (datalog/parse query))))
    (catch Exception _ false)))

(defn score
  "Query numbered comments and parsed forms without evaluating the reply."
  [database ctx text turns-left]
  (let [forms (read-forms text)
        calls (when (vector? forms) (executable-calls forms))
        comments (filter #(str/starts-with? (str/trim %) ";;") (str/split-lines text))
        answer (fn [number]
                 (str/lower-case
                  (str/join " " (filter #(str/starts-with? (str/trim %) (str ";; " number ".")) comments))))
        has? (fn [number term] (str/includes? (answer number) term))
        my-calls (filter #(some-> % first namespace (str/starts-with? "my.")) calls)
        known (set (db/q '[:find [?symbol ...] :where [?f :seon.fn/sym ?symbol]] database))
        unknown (->> calls (map first)
                     (remove #(or (special-symbol? %)
                                  (contains? known (str %))
                                  (some? (sci/resolve ctx %)))))
        shape? (fn [call]
                 (and (<= (count (rest call)) 1)
                      (or (= 1 (count call))
                          (and (map? (second call))
                               (every? qualified-keyword? (keys (second call)))))))
        checks
        [[:thinking (or (has? 1 "thinking") (has? 1 "comment"))]
         [:response (and (has? 2 "next") (has? 2 "value") (has? 2 "error"))]
         [:reuse (and (has? 3 "result/")
                      (some #(has? 3 %) ["get-in" "argument" "symbol" "evaluate"]))]
         [:documentation (and (has? 4 "doc") (or (has? 4 "dir") (has? 4 "ask")))]
         [:instruction (and (has? 5 "order") (some #(has? 5 %) ["query" "read"]))]
         [:session (and (has? 6 "reply") (has? 6 "my.agent/done")
                        (has? 6 (str turns-left)))]
         [:deferred-completion (and (some #(has? 7 %) ["no" "not" "never"])
                                   (some #(has? 7 %) ["result" "next" "seen"]))]
         [:right-function (boolean (some #(or (= 'seon.db/q (first %))
                                              (and (= 'doc (first %)) (= 'seon.db/q (second %)))) calls))]
         [:argument-shapes (and (seq calls) (every? shape? my-calls)
                                (every? query-shape? (filter #(= 'seon.db/q (first %)) calls)))]
         [:no-prompt-marker (not (prompt-marker? text))]
         [:no-premature-complete
          (and (seq calls)
               (not-any? #(or (= 'my.plan/complete! (first %))
                              (and (= 'seon.db/transact! (first %))
                                   (some #{:my.plan.item/completed-at :my.plan.item/completed-tx}
                                         (tree-seq coll? seq (rest %))))) calls))]
         [:syntax (and (vector? forms) (seq forms) (every? seq? forms)
                       (empty? unknown) (not (str/includes? text "```")))]]
        facts (mapv (fn [[check passed]] [check (boolean passed)]) checks)
        failed (db/q '[:find [?check ...] :in [[?check ?passed]] :where [(false? ?passed)]] facts)]
    {:seon.trial/checks (into {} facts)
     :seon.trial/passed (- (count facts) (count failed))
     :seon.trial/total (count facts)
     :seon.trial/failed (vec (sort failed))
     :seon.trial/unknown-functions (vec unknown)
     :seon.trial/forms (if (vector? forms) forms [])
     :seon.trial/reader-valid? (vector? forms)}))

(defn assessment
  "Score a model reply; a provider refusal is unavailable evidence, not a score."
  [database ctx completion turns-left]
  (if (:seon.error/kind completion)
    {:seon.trial/score-status :unavailable
     :seon.trial/score-error (:seon.error/kind completion)}
    {:seon.trial/score-status :measured
     :seon.trial/score (score database ctx (:seon.ai/text completion "") turns-left)}))

(defn preflight
  "Refuse anything except the generated initial opening of the shared fixture.
  Both this check and prompt acquisition consume the caller's immutable db."
  [handle database]
  (let [evaluations (checked (evaluation/of-agent database "juniper"))
        sources (mapv :seon.cluster.eval/source evaluations)
        namespace-name (sci-eval/agent-namespace database "juniper")
        expected (when namespace-name
                   (:seon.turn/forms
                    (checked (#'turn/declared-sources handle database "juniper" namespace-name))))
        expected-sources (mapv :seon.cluster.eval/source
                               (#'turn/system-plan database expected {}))
        live-turns-left (:my.agent/turns-left (checked (agent/settings database "juniper")))
        shown-settings (some-> (some #(when (= ['(seon.agent/effective-settings)]
                                               (read-forms (:seon.cluster.eval/source %))) %)
                                     evaluations)
                               :seon.eval/shown edn/read-string)
        shown-turns-left (some :my.agent/turns-left shown-settings)
        messages (checked
                  (db/q '[:find (pull ?message [:seon.message/id :seon.message/content
                                                {:seon.message/from [:seon.agent/id]}]) :where
                          [?agent :seon.agent/id "juniper"]
                          [?message :seon.message/inbox ?agent]] database))
        orders (checked (db/q '[:find (pull ?order [:example/order :example/customer :example/amount])
                               :where [?order :example/order]] database))]
    (when-not (and (seq expected-sources)
                   (= expected-sources sources)
                   (= 1 (count (set (map #(get-in % [:seon.cluster.eval/run :db/id]) evaluations))))
                   (every? #(= :system (:seon.cluster.eval/author %)) evaluations)
                   (= (count sources) (count (distinct sources)))
                   (zero? (turn/episode-runs database "juniper"))
                   (nat-int? live-turns-left)
                   (= live-turns-left shown-turns-left)
                   (= 1 (count messages))
                   (= @(resolve 'seon.context-blocks-fixture/instruction)
                      (:seon.message/content (ffirst messages)))
                   (= "root" (get-in (ffirst messages) [:seon.message/from :seon.agent/id]))
                   (not-any? :seon.cluster.eval/error evaluations)
                   (= (set @(resolve 'seon.context-blocks-fixture/orders)) (set (map first orders))))
      (throw (ex-info "The selected cluster needs the ruled initial fixture; no model was called."
                      {:seon.trial/sources sources :seon.trial/expected-sources expected-sources
                       :seon.trial/messages messages})))
    {:seon.trial/evaluations evaluations
     :seon.trial/expected-sources expected-sources
     :seon.trial/turns-left live-turns-left}))

(defn prepare
  "Capture the selected cluster's prompt and rank configured models by cost."
  ([] (prepare "default"))
  ([cluster-name]
  (let [handle (:seon.turn.loop/cluster (get @runtime/running-instances cluster-name))]
    (when-not handle (throw (ex-info "The selected cluster is not running" {:seon.cluster/name cluster-name})))
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [database (db/db (:seon.db/connection handle))
             initial (preflight handle database)
             evaluations (:seon.trial/evaluations initial)
             shown-turns-left (:seon.trial/turns-left initial)]
         (let [turn-id (:seon.turn/id
                        (db/pull database [:seon.turn/id]
                                 (get-in (last evaluations) [:seon.cluster.eval/run :db/id])))
               prompt (:seon.cluster.prompt/text
                       (checked (render/acquire-context!
                                 (merge handle {:seon.db/db database :seon.agent/id "juniper"
                                                :seon.turn/id turn-id
                                                :seon.sci.eval/time-limit-ms
                                                (:seon.config.eval/time-limit-ms handle)}))))
               _ (when-not (string? prompt)
                   (throw (ex-info "Prompt acquisition returned no text; no model was called." {})))
               full-prompt (str prompt questions)
               input-tokens (tokens/estimate full-prompt)
               max-output 2048
               dials (checked (config/effective database cluster-name))
               candidates
               (->> (ai/models database)
                    (map #(update % :seon.ai.model/provider (fn [reference] (db/pull database '[*] reference))))
                    (keep (fn [model]
                            (let [input (:seon.ai.model/input-usd-per-mtok model)
                                  output (:seon.ai.model/output-usd-per-mtok model)
                                  variable (get-in model [:seon.ai.model/provider :seon.config.ai/api-key-variable])]
                              (when (and input output variable (seq (ai/credential variable)))
                                (assoc model :seon.trial/estimated-usd
                                       (/ (+ (* input input-tokens) (* output max-output)) 1000000.0))))))
                    (sort-by (juxt :seon.trial/estimated-usd :seon.ai.model/id)) vec)
               model (first candidates)]
           (when-not model (throw (ex-info "No priced configured model has a credential; no model was called." {})))
           (let [target (:seon.ai/primary
                         (checked
                          (ai/targets database
                                      (assoc dials :seon.config.ai/model (:seon.ai.model/id model)
                                             :seon.config.ai/api-key-variable
                                             (get-in model [:seon.ai.model/provider :seon.config.ai/api-key-variable])
                                             :seon.config.ai/thinking :disabled
                                             :seon.config.ai/max-tokens max-output
                                             :seon.config.ai/timeout-ms 120000))))]
             {:seon.cluster/name cluster-name
              :seon.trial/prepared-at (str (java.time.Instant/now))
              :seon.trial/basis-t (db/basis-t database)
              :seon.trial/expected-sources (:seon.trial/expected-sources initial)
              :seon.trial/prompt prompt :seon.trial/questions questions
              :seon.trial/prompt-bytes (alength (.getBytes ^String prompt "UTF-8"))
              :seon.trial/request-bytes (alength (.getBytes ^String full-prompt "UTF-8"))
              :seon.trial/request (assoc target :seon.ai/prompt full-prompt)
              :seon.trial/model model
              :seon.trial/candidates (mapv #(select-keys % [:seon.ai.model/id :seon.trial/estimated-usd]) candidates)
              :seon.trial/turns-left shown-turns-left
              :seon.trial/live-turns-left shown-turns-left
              :seon.trial/help-code-sha256
              (schema/sha-256 [(.getBytes ^String (:seon.fn/source
                                                  (db/pull database [:seon.fn/source]
                                                           [:seon.fn/sym "seon.bootstrap/help-value"])) "UTF-8")])}))))))))

(defn run!
  "Make one call and save its prompt, reply, usage, prices, and score."
  ([] (run! "docs/prds/context-generation/research/help_trial_2026_09_09.edn"))
  ([path] (run! "default" path))
  ([cluster-name path]
   (when (.exists (io/file path))
     (throw (ex-info "A trial result already exists; choose a new path for a new tuning step." {:seon.trial/path path})))
   (let [prepared (prepare cluster-name)
         ; Record the admitted call before sending. Even a transport failure
         ; leaves evidence, and rerunning cannot silently make a second call.
         _ (spit path (pr-str (dissoc prepared :seon.trial/request)))
         handle (:seon.turn.loop/cluster (get @runtime/running-instances cluster-name))
         completion (schema/call-with-projection-state
                     (:seon.sci.eval/projection-state handle)
                     #(ai/complete (:seon.trial/request prepared)))
         ; Persist the first completion before scoring can fail.
         _ (spit path (pr-str (assoc (dissoc prepared :seon.trial/request)
                                     :seon.trial/completion
                                     (dissoc completion :seon.ai.attempt/sent-body))))
         database (db/db (:seon.db/connection handle))
         usage (some-> (:seon.ai/usage completion) ai/normalize-usage)
         prompt-tokens (:seon.ai.usage/prompt-tokens usage)
         output-tokens (:seon.ai.usage/completion-tokens usage)
         cached (get usage :seon.ai.usage/cached-tokens 0)
         model (:seon.trial/model prepared)
         cost (when (and prompt-tokens output-tokens)
                (/ (+ (* (- prompt-tokens cached) (:seon.ai.model/input-usd-per-mtok model))
                      (* cached (get model :seon.ai.model/cached-input-usd-per-mtok
                                     (:seon.ai.model/input-usd-per-mtok model)))
                      (* output-tokens (:seon.ai.model/output-usd-per-mtok model))) 1000000.0))
         result (schema/call-with-projection-state
                 (:seon.sci.eval/projection-state handle)
                 #(merge (dissoc prepared :seon.trial/request)
                         {:seon.trial/completion (dissoc completion :seon.ai.attempt/sent-body)}
                         (assessment database (:seon.sci.eval/ctx handle) completion
                                     (:seon.trial/turns-left prepared))))
         recorded (cond-> result
                    usage (assoc :seon.trial/usage usage)
                    cost (assoc :seon.trial/estimated-actual-usd cost))]
     (spit path (pr-str recorded))
     (select-keys recorded [:seon.trial/score :seon.trial/score-status :seon.trial/score-error
                           :seon.trial/completion :seon.trial/candidates
                           :seon.trial/usage :seon.trial/estimated-actual-usd]))))
