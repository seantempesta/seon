(ns seon.repl
  "The ONE generator of REPL bytes: one evaluation, as the session it was.

  An agent writes comments and forms. The REPL answers each form with one
  `#:seon.repl{…}` map the agent is never asked to write (PRD ruling 69,
  docs/prds/context-generation/plan/agent-record-and-repl-response-prd-2026-09-07.md
  §4). The page's AI column, the agent's history unit, and the provider
  prompt are the same pure function over the same evaluation, so their bytes
  are identical by construction rather than by three formatters agreeing.

  The grammar, exactly:

      ; the agent's comment, verbatim, above the prompt
      my.agents.juniper=> (+ 1 1)
      #:seon.repl{:value 2, :result result/e41, :ms 3}

  Comments sit ABOVE the prompt so a prompt line holds exactly one form and
  HTML can label the comment separately. Key order is enforced by walking an
  ordered vector — never by printing a map, whose order is the reader's
  accident. Ruling 45 holds: nothing this emits is comment-shaped except the
  agent's own comment."
  (:require [clojure.edn :as edn]
            [clojure.main :as main]
            [clojure.string :as str]
            [seon.print :as print]
            [seon.render.value :as value]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit])
  (:import [java.io PushbackReader StringReader]))

(schema.edn/load! {})

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
  (or (:seon.eval/value emission) (:seon.repl/value emission)))

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
        by-key {:seon.repl/value (when (and (nil? error) (some? value)) value)
                :seon.repl/error (some-> error pr-str)
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
                :seon.repl/ms (when (int? duration) (str duration))}]
    (into []
          (keep (fn [response-key]
                  (when-some [text (get by-key response-key)]
                    [response-key text])))
          response-order)))

(defn response
  "The one-line `#:seon.repl{…}` answer to one evaluated form.

  Returns nil when the evaluation has settled nothing — a running form has
  not printed a result, and inventing an empty map would say it had."
  {:malli/schema [:=> [:cat :seon.repl/emission] [:maybe :string]]}
  [emission]
  (let [entries (response-entries emission)]
    (when (seq entries)
      (str "#:seon.repl{"
           (str/join ", "
                     (map (fn [[response-key text]]
                            (str ":" (name response-key) " " text))
                          entries))
           "}"))))

(defn text
  "The REPL bytes for ONE evaluation: comment, prompt line, response.

  This is the single generator behind the debug page's AI column, the agent's
  history unit, and the provider prompt. Anything that formats an evaluation
  some other way is by definition a second grammar, and a second grammar is
  what taught agents to echo results instead of writing forms."
  {:malli/schema [:=> [:cat :seon.repl/emission] :string]}
  [{prose :seon.cluster.eval/comment
    source :seon.cluster.eval/source
    prompt-ns :seon.ns/name
    :as emission}]
  (let [answer (response emission)]
    (str (when (seq prose) (str (str/trim-newline prose) "\n"))
         (or prompt-ns 'user) "=> " source
         (when answer (str "\n" answer)))))

;;; ---------------------------------------------------------------------------
;;; The evaluation entity's two projections
;;;
;;; Both are the same bytes by construction: `/html` labels the comment and
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
  (let [unit (if (map? (:seon.render/value unit))
               (:seon.render/value unit)
               unit)]
   (cond-> (select-keys unit [:seon.cluster.eval/id
                             :seon.cluster.eval/source
                             :seon.cluster.eval/comment
                             :seon.cluster.eval/ordinal
                             :seon.eval/value
                             :seon.eval/missing
                             :seon.eval/size
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
    (and (int? (:db/id unit)) (string? (:seon.eval/value unit)))
    (assoc :seon.repl/handle (admit/result-handle (:db/id unit))))))

(defn render-ai
  "`:seon.render/ai` — one evaluation, as the REPL session it was."
  {:malli/schema [:=> [:cat :seon.repl/entity-request] [:maybe :string]]}
  [unit]
  (let [emission (entity-emission unit)]
    (when (seq (:seon.cluster.eval/source emission))
      (text emission))))

(defn render-html
  "`:seon.render/html` — the same evaluation, with the comment labeled.

  The comment is its own element rather than a line of the prompt, which is
  the whole reason it is stored apart from the source it introduces."
  {:malli/schema [:=> [:cat :seon.repl/entity-request] [:maybe :seon.render/hiccup]]}
  [unit]
  (let [emission (entity-emission unit)
        prose (:seon.cluster.eval/comment emission)
        answer (response emission)]
    (when (seq (:seon.cluster.eval/source emission))
      (into [:article {:class "seon-family-entry seon-eval-entry"}]
            (cond-> []
              (seq prose)
              (conj [:p {:class "seon-eval-comment"} prose])
              :always
              (conj [:pre [:code {:class "seon-eval-prompt"}
                           (str (or (:seon.ns/name emission) 'user) "=> "
                                (:seon.cluster.eval/source emission))]])
              answer
              (conj [:pre [:code {:class "seon-eval-response"} answer]]))))))
