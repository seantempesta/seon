(ns seon.repl
  "The ONE generator of REPL bytes: one evaluation, as the session it was.

  The prompt precedes everything the agent typed, including its comments.
  Declared AI renderer output is the response itself; ordinary data uses the
  reply map. Historical entries preserve the evaluation's saved shown text.

      my.agents.juniper=> ;; I should check the sum.
      (+ 1 1)
      #:seon.repl{:value 2, :result result/e41, :ms 3}"
  (:require [clojure.edn :as edn]
            [clojure.main :as main]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [seon.render.value :as value]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]))

(schema.edn/load! {})

(defn render-directory-ai
  "Print directory columns once while retaining every summary and contract."
  {:malli/schema [:=> [:cat :seon.repl/directory] :string]}
  [directory]
  (let [columns (cond-> [:sym :arglists :doc :in :out]
                  (some :supplied (:functions directory)) (conj :supplied))]
    (binding [*print-length* nil *print-level* nil *print-readably* true]
      (pr-str (array-map
               :seon.repl/columns columns
               :seon.repl/rows (mapv #(mapv % columns) (:functions directory))
               :seon.repl/schemas (into (sorted-map) (:schemas directory)))))))

(defn render-directory-html
  "Show complete directory data using the same named columns."
  {:malli/schema [:=> [:cat :seon.repl/directory] :seon.render/hiccup]}
  [directory]
  [:pre (render-directory-ai directory)])

(defn frame
  "Derive the current turn budget outside the saved evaluation history.

  READS THE OVERLAY ONCE (2.1). A caller already holding the agent's
  resolved settings — `seon.cluster.prompt/prompt` derives them once for
  the whole prompt — hands them through the three-argument arity instead
  of this function re-deriving `ai/agent-overlay` a second time; the
  two-argument arity derives it itself for a caller with no settings on
  hand. Either way `seon.turn/max-episode-runs` is never called twice for
  the same number: `episode-runs` alone (no overlay) gives the count that
  turn budget subtracts from the max already read."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/db :seon.agent/id] :string]
    [:=> [:cat :seon.db/db :seon.agent/id :seon.config/agent-overlay] :string]]}
  ([database agent-id]
   (frame database agent-id ((requiring-resolve 'seon.ai/agent-overlay) database agent-id)))
  ([database agent-id overrides]
   (let [maximum (or (:seon.config.run/max-episode-runs overrides)
                      ((requiring-resolve 'seon.db/q)
                       '[:find ?limit . :where
                         [?config :seon.config/cluster _]
                         [?config :seon.config.run/max-episode-runs ?limit]] database))
         remaining (long (max 0 (- (or maximum 0)
                                    ((requiring-resolve 'seon.turn/episode-runs)
                                     database agent-id))))]
     (str "turns left: " remaining " of " maximum))))

(defn shown-value
  "Read a complete shown EDN value; preserve terminal prose as its string."
  {:malli/schema [:=> [:cat :string] :seon.schema/value]}
  [shown]
  (try
    (with-open [reader (java.io.PushbackReader. (java.io.StringReader. shown))]
      (let [end (Object.)
            value (edn/read {:eof end} reader)]
        (if (and (not (identical? end value))
                 (identical? end (edn/read {:eof end} reader)))
          value shown)))
    (catch Exception _ shown)))

(defn source-text
  "Print a generated form with reader quotes and explicit keyword keys."
  {:malli/schema [:=> [:cat :seon.repl/expression] :seon.render/source]}
  [form]
  (binding [*print-namespace-maps* false *print-length* nil *print-level* nil
            *print-readably* true]
    (pprint/write form :stream nil :dispatch pprint/code-dispatch :right-margin 100)))

;;; ---------------------------------------------------------------------------
;;; The response map
;;;
;;; One ordered vector is the whole order authority. A key absent from an
;;; evaluation is absent from its response: the agent never reads a `nil` it
;;; would have to interpret, and `:value` / `:error` are mutually exclusive
;;; because exactly one of them is a terminal fact of the evaluation.
;;; ---------------------------------------------------------------------------

(def ^:private response-order
  [:seon.repl/value
   :seon.repl/error
   :seon.repl/interrupted
   :seon.repl/result
   :seon.repl/out
   :seon.repl/note
   :seon.repl/ns
   :seon.repl/ms])

(defn- def-note
  "Explain a temporary def or a function missing its installation contract
  from the evaluation's source evidence. Unreadable source earns no note."
  [source]
  (when (string? source)
    (let [form (try (edn/read-string source) (catch Throwable _ nil))]
      (when (and (seq? form) (symbol? (second form)))
        (cond
          (= 'def (first form))
          (str (second form)
             " lives only in your SCI context and is lost when the JVM"
             " restarts. Nothing defined with def is persisted, atoms"
             " included; they are for temporary data-modeling experiments."
             " To keep something, write a function, schema, or test, or"
             " transact the data into the database.")
          (and (#{'defn 'defn- 'clojure.core/defn 'clojure.core/defn-}
                (first form))
               (not (:malli/schema (meta (second form))))
               (not (:malli/schema
                     (first (drop-while string? (nnext form))))))
          (str (second form)
               " was not installed: every function needs a :malli/schema"
               " contract to become part of the program."))))))

(defn value-text
  "Return the saved shown text unchanged."
  {:malli/schema [:=> [:cat :seon.repl/emission] [:maybe :string]]}
  [emission]
  (or (:seon.eval/shown emission) (:seon.repl/value emission)))

(defn error-text
  "Clojure's own concise REPL error for one failed evaluation.

  The recorded `ex-triage` data is the authority — it names the throwable's
  class and, when the evaluation had one, its source location. The stored
  message is the fallback when triage is absent or unreadable, and it says
  only what it knows: NO invented `(REPL:1)` location and no empty class
  parens, because a diagnostic that fabricates where a failure happened is
  worse than one that omits it (law 2.4)."
  {:malli/schema [:=> [:cat :seon.repl/emission] [:maybe :string]]}
  [{error :seon.cluster.eval/error triage :seon.cluster.eval/triage-edn}]
  (when (string? error)
    (or (when (string? triage)
          (try
            (let [triaged (edn/read-string triage)]
              (when (map? triaged)
                (-> triaged main/ex-str str/trim-newline)))
            (catch Throwable _ nil)))
        (str "Execution error.\n" (str/trim-newline error)))))

(defn- response-entries
  "The response's present keys, in declared order, each already text."
  [{output :seon.cluster.eval/output
    source :seon.cluster.eval/source
    handle :seon.repl/handle
    ending-ns :seon.sci.eval/ending-ns
    prompt-ns :seon.ns/name
    duration :seon.eval/duration-ms
    interrupted-at :seon.cluster.eval/interrupted-at
    :as emission}]
  (let [value (value-text emission)
        error (error-text emission)
        shown-error (when (and error value
                               (not (:seon.cluster.eval/triage-edn emission)))
                      value)
        by-key {:seon.repl/value (when (and (nil? error) (some? value)) value)
                :seon.repl/error (some-> (or shown-error error) pr-str)
                ;; AN EVALUATION BOOT CUT SAYS SO. Without this the response
                ;; for an interrupted evaluation carried `:ms` alone and read
                ;; exactly like one still running — absence of signal read as
                ;; health, in the agent's own history (PRD §4).
                :seon.repl/interrupted
                ;; AN INSTANT IS AN INSTANT. `(pr-str (.toString …))` made it
                ;; a quoted string where the one print grammar renders every
                ;; other instant as readable `#inst` data.
                (when (inst? interrupted-at)
                  (pr-str interrupted-at))
                ;; A HANDLE IS A FACT, NOT A FLAG. The caller that could bind
                ;; one hands the symbol it bound; ruling 59c's "no handle" is
                ;; simply its absence, so nothing here re-decides what the
                ;; binding already settled.
                :seon.repl/result (some-> handle str)
                :seon.repl/out (when (seq output) (pr-str output))
                :seon.repl/note (some-> (def-note source) pr-str)
                :seon.repl/ns (when (and ending-ns (not= ending-ns prompt-ns))
                                (str ending-ns))
                :seon.repl/ms (when (and (int? duration)
                                        (not (:seon.repl/changed-since? emission)))
                               (str duration))}]
    (into []
          (keep (fn [response-key]
                  (when-some [text (get by-key response-key)]
                    [response-key text])))
          response-order)))

(defn response
  "The saved declared-renderer response, or the reply map for ordinary data.

  Returns nil when the evaluation has settled nothing — a running form has
  not printed a result, and inventing an empty map would say it had."
  {:malli/schema [:=> [:cat :seon.repl/emission] [:maybe :string]]}
  [emission]
  (if (:seon.eval/renderer emission)
    (value-text emission)
    (let [entries (response-entries emission)]
    (when (seq entries)
      (str "#:seon.repl{"
           (str/join ", "
                     (map (fn [[response-key text]]
                            (str ":" (name response-key) " " text))
                          entries))
           "}")))))

(defn- input-text
  [{prose :seon.cluster.eval/comment source :seon.cluster.eval/source
    prompt-ns :seon.ns/name changed? :seon.repl/changed-since?}]
  (str (when changed? ";; changed since your last turn\n")
       (or prompt-ns 'user) "=> "
       (when (and (not changed?) (seq prose)) (str (str/trim-newline prose) "\n"))
       source))

(defn text
  "The REPL bytes for one evaluation: prompt, agent input, response.

  This is the single generator behind the debug page's AI column, the agent's
  history unit, and the provider prompt. Anything that formats an evaluation
  some other way is by definition a second grammar, and a second grammar is
  what taught agents to echo results instead of writing forms."
  {:malli/schema [:=> [:cat :seon.repl/emission] :string]}
  [emission]
  (let [answer (response emission)
        changed (when (and (:seon.repl/changed-since? emission)
                           (:seon.eval/shown emission))
                  (shown-value (:seon.eval/shown emission)))
        full-handle (:seon.repl/handle emission)]
    (str (input-text emission)
         (when answer (str "\n" answer))
         (when (and full-handle (map? changed) (:seon.repl/changes changed))
           (str "\n;; full value: " (pr-str (list 'get-in full-handle [])))))))

(defn- token-boundary? [character]
  (or (Character/isWhitespace ^char character)
      (some #{character} "()[]{}\";,`~@^'")))

(defn syntax-tokens
  "Lossless character lexer for displayed Clojure, including incomplete replies.
  No reader executes here. Every iteration consumes input; unknown runs stay
  plain text and concatenating the token texts restores the original string."
  {:malli/schema [:=> [:cat :string] [:vector [:tuple :string :string]]]}
  [source]
  (let [length (count source)]
    (loop [position 0 tokens []]
      (if (= position length) tokens
        (let [character (.charAt ^String source position)
              end (cond
                    (= character \;)
                    (loop [cursor (inc position)]
                      (if (or (= cursor length) (= \newline (.charAt ^String source cursor)))
                        cursor (recur (inc cursor))))
                    (= character \")
                    (loop [cursor (inc position) escaped? false]
                      (if (= cursor length) cursor
                        (let [current (.charAt ^String source cursor)]
                          (cond
                            escaped? (recur (inc cursor) false)
                            (= current \\) (recur (inc cursor) true)
                            (= current \") (inc cursor)
                            :else (recur (inc cursor) false)))))
                    (token-boundary? character) (inc position)
                    :else
                    (loop [cursor (min length (+ position (if (= character \\) 2 1)))]
                      (if (or (= cursor length) (token-boundary? (.charAt ^String source cursor)))
                        cursor (recur (inc cursor)))))
              token (subs source position end)
              style (cond
                      (= character \;) "comment"
                      (= character \") "string"
                      (= character \:) "keyword"
                      (some #{character} "()[]{}") "delimiter"
                      (or (Character/isDigit ^char character)
                          (and (#{\+ \-} character) (> (count token) 1)
                               (Character/isDigit ^char (.charAt ^String token 1)))) "number"
                      (str/starts-with? token "result/") "result"
                      (Character/isWhitespace ^char character) "space"
                      (Character/isLetter ^char character) "symbol"
                      :else "plain")]
          (recur end (conj tokens [style token])))))))

(defn- syntax-spans [source]
  (map (fn [[style token]] [:span {:class (str "seon-syntax-" style)} token])
       (syntax-tokens source)))

(defn render-emission-ai
  "The exact emission bytes paired with the colourised session rendition."
  {:malli/schema [:=> [:cat :seon.repl/emission] :string]}
  [emission]
  (text emission))

(defn render-emission-html
  "Colourise one emission without changing a single character of `text`.
  The prompt, input and response have separate visual roles. Declared AI
  responses remain prose; ordinary responses use the lossless Clojure lexer."
  {:malli/schema [:=> [:cat :seon.repl/emission] :seon.render/hiccup]}
  [emission]
  (let [emitted (text emission)
        prompt (str (or (:seon.ns/name emission) 'user) "=> ")
        prefix-length (if (:seon.repl/changed-since? emission)
                        (inc (str/index-of emitted "\n")) 0)
        prompt-end (+ prefix-length (count prompt))
        input-end (count (input-text emission))
        answer (response emission)]
    [:code {:class "seon-emission-bytes"}
     (when (pos? prefix-length) (syntax-spans (subs emitted 0 prefix-length)))
     [:span {:class "seon-syntax-prompt"} (subs emitted prefix-length prompt-end)]
     (syntax-spans (subs emitted prompt-end input-end))
     (when answer
       (list "\n"
             [:span {:class (if (:seon.cluster.eval/error emission)
                              "seon-emission-error" "seon-emission-response")}
              (if (:seon.eval/renderer emission)
                (subs emitted (inc input-end))
                (syntax-spans (subs emitted (inc input-end))))]))]))

;;; ---------------------------------------------------------------------------
;;; The evaluation entity's two projections
;;;
;;; Both share the prompt and complete input bytes; `/html` labels input and
;;; the response, and `/ai` is the text the agent reads. Neither invents a
;;; second grammar, because both call `text`/`response`.
;;; ---------------------------------------------------------------------------

(defn entity-emission
  "One pulled evaluation entity as an emission this namespace can render.

  The prompt namespace is the evaluation's own `:seon.cluster.eval/ns` when
  the pull reached its name; an unreached namespace prints as `user`, which
  is what a REPL with no namespace in effect is called."
  {:malli/schema [:=> [:cat :seon.repl/entity-request] :seon.repl/emission]}
  [unit]
  (let [database (:seon.db/db unit)
        unit (if (map? (:seon.render/value unit))
               (:seon.render/value unit)
               unit)
        renderer-ref (:seon.eval/renderer-fn unit)
        renderer-symbol
        (or (get-in unit [:seon.eval/renderer-fn :seon.fn/sym])
            (when (and database renderer-ref)
              (:seon.fn/sym
               ((requiring-resolve 'seon.db/pull)
                database [:seon.fn/sym]
                (if (map? renderer-ref) (:db/id renderer-ref) renderer-ref)))))
        evaluation-id (:seon.cluster.eval/id unit)
        changed? (or (:seon.repl/changed-since? unit)
                     (when (and database evaluation-id)
                       (integer?
                        ((requiring-resolve 'seon.db/q)
                         '[:find ?earlier . :in $ ?evaluation-id :where
                           [?evaluation :seon.cluster.eval/id ?evaluation-id]
                           [?evaluation :seon.cluster.eval/run ?turn]
                           [?turn :seon.turn/id ?id ?t]
                           [?turn :seon.turn/reply-size]
                           (not [?turn :seon.turn/attempts])
                           (not [?turn :seon.turn.work/situation :call])
                           [?turn :seon.turn/agent ?agent]
                           [?earlier :seon.turn/agent ?agent]
                           [?earlier :seon.turn/id ?earlier-id ?before]
                           [(< ?before ?t)]] database evaluation-id))))]
   (cond-> (select-keys unit [:seon.ns/name
                             :seon.cluster.eval/id
                             :seon.cluster.eval/source
                             :seon.cluster.eval/comment
                             :seon.cluster.eval/ordinal
                             :seon.eval/shown
                             :seon.eval/renderer
                             :seon.cluster.eval/error
                             :seon.cluster.eval/interrupted-at
                             :seon.cluster.eval/triage-edn
                             :seon.cluster.eval/output
                             :seon.sci.eval/ending-ns
                             :seon.eval/duration-ms
                             ;; THE STORED KEYS ARE THE ONES THE FORM SET.
                             ;; Reading `:seon.print/options` here left every
                             ;; per-form `set!` of *print-length* / *print-level*
                             ;; out of the response it was recorded for.
                             :seon.print/length
                             :seon.print/level
                             :seon.print/options])
    renderer-symbol
    (assoc :seon.eval/renderer (symbol renderer-symbol))
    changed? (assoc :seon.repl/changed-since? true)
    ;; ONE ENTITY PER (run, ordinal), ONE SPELLING. The frozen form family
    ;; is gone; the source, the ordinal and the namespace are this
    ;; evaluation's own attributes, so there is nothing to reconcile here.
    (get-in unit [:seon.cluster.eval/ns :seon.ns/name])
    (assoc :seon.ns/name (get-in unit [:seon.cluster.eval/ns :seon.ns/name]))

    ;; THE HANDLE COMES FROM THE EVALUATION'S OWN IDENTITY, so two runs of one
    ;; agent never name two values alike. An evaluation with no entity id never
    ;; persisted, and a node that kept only a name never held the value: both
    ;; have no handle, and the response then carries no `:result` key.
    ;; A MISSING VALUE NAMES NOTHING EITHER — it stored no node at all, so
    ;; the same predicate that binds the fork's handles refuses here.
    (and (string? (:seon.cluster.eval/id unit)) (string? (:seon.eval/shown unit)))
    (assoc :seon.repl/handle (admit/result-handle (:seon.cluster.eval/id unit))))))

(defn render-ai
  "`:seon.render/ai` — one evaluation, as the REPL session it was."
  {:malli/schema [:=> [:cat :seon.repl/entity-request] [:maybe :string]]}
  [unit]
  (let [emission (entity-emission unit)]
    (when (seq (:seon.cluster.eval/source emission))
      (text emission))))

(defn- pretty-response
  "Format readable saved data for humans; non-readable shown text stays exact."
  [answer]
  (try
    (binding [*print-length* nil *print-level* nil *print-meta* false]
      (pprint/write (edn/read-string answer) :stream nil :right-margin 100))
    (catch Throwable _ answer)))

(defn- live-response
  [unit emission]
  (let [routing (:seon.agent/routing unit)
        agent-id (:seon.agent/id unit)
        ctx (or (get-in (when (and routing agent-id)
                         ((requiring-resolve 'seon.cluster.agent/armed) routing agent-id))
                       [:seon.turn.loop/cluster :seon.sci.eval/agent-ctx])
                (:seon.sci.eval/agent-ctx unit))
        objects (some-> (:seon.sci.eval/result-objects ctx) deref)]
    (when-let [entry (find objects (:seon.cluster.eval/id emission))]
      (value/prepare
       (cond-> (assoc unit :seon.render/value (val entry)
                           :seon.render.value/root [:seon.cluster.eval/id (:seon.cluster.eval/id emission)])
         (not (:seon.eval/renderer emission))
         (assoc-in [:seon.render.value/options :seon.render.value/structural?] true))
       :seon.render/html))))

(defn render-html
  "Render the prompt and complete agent input together, then its response."
  {:malli/schema [:=> [:cat :seon.repl/entity-request] [:maybe :seon.render/hiccup]]}
  [unit]
  (let [emission (entity-emission unit)
        answer (response emission)
        live (when answer (live-response unit emission))
        producer (:seon.render.call/selected-producer live)
        content (cond
                  (:seon.error/kind live) [:pre (pr-str live)]
                  producer (value/render-html-data live)
                  live [:pre [:code {:class "seon-eval-response"}
                              (pretty-response
                               (response (assoc emission :seon.eval/shown
                                                         (:seon.render.value/text live))))]]
                  (and answer (:seon.eval/renderer emission))
                  (into [:ul {:class "seon-eval-lines"}]
                        (map #(vector :li %) (str/split-lines answer)))
                  answer [:pre [:code {:class "seon-eval-response"}
                                (pretty-response answer)]])]
    (when (seq (:seon.cluster.eval/source emission))
      (cond-> [:article {:class "seon-family-entry seon-eval-entry"}
               [:pre [:code {:class "seon-eval-prompt"} (input-text emission)]]]
        answer (conj [:small {:class "seon-eval-renderer"}
                      (str "AI: " (or (:seon.eval/renderer emission)
                                       'seon.render.value/render-ai)
                           " · HTML: " (or producer (when live 'seon.render.value/render-html)
                                             'seon.repl/render-html)
                           (when-not live " · saved text; live value unavailable"))]
                     content)))))
