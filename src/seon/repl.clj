(ns seon.repl
  "The ONE generator of REPL bytes: one evaluation, as the session it was.

  The prompt precedes everything the agent typed, including its comments.
  Every value, declared AI renderer output included, is the reply map's
  `:value` as the value printer showed it. Historical entries preserve the evaluation's saved shown text.

      my.agents.juniper=> ;; I should check the sum.
      (+ 1 1)
      #:seon.repl{:value 2, :result result/e41, :ms 3}"
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [malli.core :as m]
            [seon.ai :as ai]
            [seon.ai.tokens :as tokens]
            [seon.db :as db]
            [seon.print :as print]
            [seon.render.value :as value]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]))

;;; LOAD-CYCLE BOUNDARIES. `seon.turn` and `seon.cluster.agent` both require
;;; `seon.repl` transitively, so this namespace cannot require them back. One
;;; resolution per var, realized at first use, instead of a
;;; `requiring-resolve` on every call (AGENTS §2.1).
(defonce ^:private turn-episode-runs
  (delay (requiring-resolve 'seon.turn/episode-runs)))

(defonce ^:private config-agent-cluster-dial
  (delay (requiring-resolve 'seon.config/agent-cluster-dial)))
(defonce ^:private cluster-agent-armed
  (delay (requiring-resolve 'seon.cluster.agent/armed)))

(schema.edn/load! {})

(defn render-directory-ai
  "The namespace's public functions as data, one entry each: symbol, arglists
  and the summary sentence (absent when undocumented). Contracts are one
  `(doc sym)` away."
  {:malli/schema [:=> [:cat :seon.repl/directory]
                  [:vector [:map [:sym :qualified-symbol] [:arglists :seon.schema/value] [:summary {:optional true} :string]]]]}
  [directory]
  (mapv (fn [{:keys [sym arglists doc]}]
          (cond-> {:sym sym :arglists arglists} doc (assoc :summary doc)))
        (:functions directory)))

(defn render-directory-html
  "Show complete directory data using the same named columns."
  {:malli/schema [:=> [:cat :seon.repl/directory] :seon.render/hiccup]}
  [directory]
  [:table
   (for [{:keys [sym arglists summary]} (render-directory-ai directory)]
     [:tr [:td [:code (str sym)]] [:td [:code (str arglists)]] [:td summary]])])

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
   (frame database agent-id (ai/agent-overlay database agent-id)))
  ([database agent-id overrides]
   (let [maximum (or (:seon.config.run/max-episode-runs overrides)
                      (@config-agent-cluster-dial database agent-id :seon.config.run/max-episode-runs))]
     (if (:seon.config/error-key maximum)
       (str "turns left: unknown: " (:seon.error/message maximum))
       (str "turns left: " (long (max 0 (- (or maximum 0) (@turn-episode-runs database agent-id))))
            " of " maximum)))))

(defn- read-shown
  "The one complete EDN value `shown` holds, else `shown` itself (prose, or
  text followed by more text)."
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

(defn shown-value
  "Read a complete shown EDN value; preserve terminal prose as its string."
  {:malli/schema [:=> [:cat :string] :seon.schema/value]}
  [shown]
  (read-shown shown))

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
;;; because exactly one of them is a terminal fact of the evaluation. The
;;; composer's own entries come first; the printer's text, spliced as shown
;;; (`spliced`), follows them, so prose a render returned with an open
;;; delimiter or quote cannot swallow a key the composer wrote.
;;; ---------------------------------------------------------------------------

(def ^:private response-order
  [:seon.repl/findings
   :seon.repl/interrupted
   :seon.repl/result
   :seon.repl/out
   :seon.repl/note
   :seon.repl/ns
   :seon.repl/ms
   :seon.repl/alloc-mb
   :seon.repl/errors
   :seon.repl/diagnostics
   :seon.repl/repairs
   :seon.repl/value
   :seon.repl/error])

(def ^:private spliced
  "The entries that are the value printer's shown text, spliced as shown."
  #{:seon.repl/errors :seon.repl/diagnostics :seon.repl/value :seon.repl/error})

(defn- def-note
  "Explain a temporary def from the evaluation's own record: its source is a
  `def` form and its outcome a Var, whose recorded class names the surface
  that evaluated it (`sci.lang.Var`: the agent's SCI context; `clojure.lang.Var`:
  a host Var in `ns-name`, MCP JVM mode). A failed evaluation, an unreadable
  source or any other value earns no note."
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string] [:maybe :symbol]] [:maybe :string]]}
  [source value-class ns-name]
  (when (and (string? source) (#{"sci.lang.Var" "clojure.lang.Var"} value-class))
    (let [form (try (edn/read-string source) (catch Throwable _ nil))]
      (when (and (seq? form) (= 'def (first form)) (symbol? (second form)))
        (if (= "sci.lang.Var" value-class)
          (str (second form)
               " lives only in your SCI context and is lost when the JVM"
               " restarts. Nothing defined with def is persisted, atoms"
               " included; they are for temporary data-modeling experiments."
               " To keep something, write a function, schema, or test, or"
               " transact the data into the database.")
          (str (second form) " is the host Var " (or ns-name 'user) "/" (second form)
               " and lasts until the JVM restarts. Nothing defined with def is"
               " persisted, atoms included. To keep something, write a function,"
               " schema, or test, or transact the data into the database."))))))

(defn value-text
  "Return the saved shown text unchanged."
  {:malli/schema [:=> [:cat :seon.repl/emission] [:maybe :string]]}
  [emission]
  (or (:seon.eval/shown emission) (:seon.repl/value emission)))

(declare findings)

(defonce ^:private render-agent-render-profile
  (delay (requiring-resolve 'seon.render/agent-render-profile)))
(defonce ^:private config-defaults
  (delay (requiring-resolve 'seon.config/defaults)))

(defn- shown-output
  "The evaluation's printed output as the value printer shows it, under the
  agent profile's one budget less the `spent` characters the reply's other
  entries took: a cut carries the form that reads the rest from the live
  output handle (`subs` over `result/o…`)."
  {:malli/schema [:=> [:cat :string [:maybe :string] :int] :string]}
  [output evaluation-id spent]
  (value/text
   {:seon.render/value output
    :seon.render/profile (@render-agent-render-profile @@config-defaults)
    :seon.render.value/spent spent
    ;; an evaluation never recorded has no handle; its cut says so
    :seon.render.value/root (if evaluation-id (admit/output-handle evaluation-id) :seon.repl/out)}))

(declare reply-map)

(defn- entries-in-order
  "The present entries of `by-key`, in the reply's declared order."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword [:maybe :string]]]
                  [:vector [:tuple :qualified-keyword :string]]]}
  [by-key]
  (into []
        (keep (fn [response-key]
                (when-some [text (get by-key response-key)]
                  [response-key text])))
        response-order))

(defn- response-entries
  "The response's present keys, in declared order, each already text.

  ONE MAP (owner 2026-09-27: \"return data, not comments\"). Shown text is
  the value printer's and is spliced as it was shown: the `:value` (a
  declared render's text included), the `:error` when the failure was shown,
  and `:diagnostics`. Only an unshown failure's prose `:seon.cluster.eval/error`
  is quoted, by the printer's `string-literal`. The declared bounds' findings
  are `:findings`; nothing is printed above or around the map."
  {:malli/schema [:=> [:cat :seon.repl/emission] [:vector [:tuple :qualified-keyword :string]]]}
  [{output :seon.cluster.eval/output
    source :seon.cluster.eval/source
    handle :seon.repl/handle
    ending-ns :seon.sci.eval/ending-ns
    prompt-ns :seon.ns/name
    duration :seon.eval/duration-ms
    allocated :seon.eval/allocated-bytes
    interrupted-at :seon.cluster.eval/interrupted-at
    :as emission}]
  (let [value (value-text emission)
        ;; The evaluation's own failure: its shown text IS its flat error.
        ;; Diagnostics are a later stage's, beside a value that stands.
        diagnostics (:seon.cluster.eval/diagnostics emission)
        failed? (and (:seon.cluster.eval/error emission) (not diagnostics))
        found (findings emission)
        by-key {:seon.repl/value (when-not failed? value)
                ;; Where the text came from decides, never parsing: shown
                ;; text is the printer's; the prose error string is quoted.
                :seon.repl/error (when failed?
                                   (or value (print/string-literal (:seon.cluster.eval/error emission))))
                :seon.repl/findings (when (seq found) (value/core-text found))
                :seon.repl/diagnostics diagnostics
                :seon.repl/repairs (some-> (:seon.cluster.eval/delimiter-repairs emission) value/core-text)
                ;; AN EVALUATION BOOT CUT SAYS SO. Without this the response
                ;; for an interrupted evaluation carried `:ms` alone and read
                ;; exactly like one still running — absence of signal read as
                ;; health, in the agent's own history (PRD §4).
                :seon.repl/interrupted
                ;; An instant prints as the printer prints every instant: `#inst` data.
                (when (inst? interrupted-at)
                  (value/core-text interrupted-at))
                ;; A HANDLE IS A FACT, NOT A FLAG. The caller that could bind
                ;; one hands the symbol it bound; ruling 59c's "no handle" is
                ;; simply its absence, so nothing here re-decides what the
                ;; binding already settled.
                :seon.repl/result (some-> handle str)
                ;; ALREADY TEXT: the value renderer bounded it once.
                :seon.repl/errors (:seon.repl/errors emission)
                :seon.repl/note (when-not failed?
                                  (some-> (def-note source (:seon.eval/value-class emission) prompt-ns) print/string-literal))
                :seon.repl/ns (when (and ending-ns (not= ending-ns prompt-ns))
                                (str ending-ns))
                :seon.repl/ms (when (and (int? duration)
                                        (not (:seon.repl/changed-since? emission)))
                               (str duration))
                ;; What the evaluation allocated, as data in MB to 0.1, with
                ;; the measure's scope documented on :seon.eval/allocated-bytes.
                :seon.repl/alloc-mb (when (and (int? allocated) (not (neg? allocated))
                                               (not (:seon.repl/changed-since? emission)))
                                      (value/core-text (/ (Math/round (/ (double allocated) 1e5)) 10.0)))}]
    (entries-in-order
     (cond-> by-key
       ;; ONE BUDGET PER REPLY: the output shows in what the reply's other
       ;; entries left of it (the value held back the output's share).
       (seq output)
       (assoc :seon.repl/out
              (shown-output output (:seon.cluster.eval/id emission)
                            (+ (count ":out ") (count (reply-map (entries-in-order by-key))))))))))

(defn- ends-in-comment?
  "True when `text`'s last line could hold a line comment, so the reply map
  must end that line before the next key; a `;` inside a string on that line
  costs only a newline."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String text]
  (not= -1 (.indexOf text ";" (inc (.lastIndexOf text "\n")))))

(defn- reply-map
  "The entries as one `#:seon.repl{…}` map: entries join with `, ` on one
  line, and a spliced entry whose last line may end in a comment is ended
  before the next key or the closing brace; the composer's own never does."
  {:malli/schema [:=> [:cat [:vector [:tuple :qualified-keyword :string]]] [:maybe :string]]}
  [entries]
  (when (seq entries)
    (let [ended? (fn [[response-key text]] (and (spliced response-key) (ends-in-comment? text)))]
      (str "#:seon.repl{"
           (str/join (map-indexed
                      (fn [index [response-key text]]
                        (str (when (pos? index) (if (ended? (nth entries (dec index))) "\n " ", "))
                             ":" (name response-key) " " text))
                      entries))
           (when (ended? (peek entries)) "\n")
           "}"))))

;;; ---------------------------------------------------------------------------
;;; Misuse findings
;;;
;;; The bounds are one declared Malli map schema, `:seon.config.eval/bounds`,
;;; over the evaluation's measures; a finding is exactly one of `m/explain`'s
;;; errors against it. No measure, schema or symbol is named here: a new rule
;;; is a new entry in the resource, a tighter rule a new bound.
;;; ---------------------------------------------------------------------------

(def ^:private bounds
  "The declared bounds, compiled at the first response after this namespace
  loads (one read of the packaged forms): a bound edited in the schema
  resource is adopted at the next reload of `seon.repl`. Each entry declares
  its finding as data: `:seon.repl/finding` (what it is), `:seon.repl/with`
  (the other recorded measures it reports) and `:seon.repl/fix`."
  (delay
   (let [form (get (schema.edn/packaged-forms) :seon.config.eval/bounds)
         schema (m/schema form)
         declared (into {} (map (fn [[measure properties]]
                                  [measure (select-keys properties [:seon.repl/finding :seon.repl/with
                                                                    :seon.repl/fix])]))
                        (m/children schema))]
     {::explainer (m/explainer schema)
      ::measures (m/explicit-keys schema)
      ::declared declared
      ::named (into #{} (mapcat (comp :seon.repl/with val)) declared)})))

(defn cost-attributes
  "The stored evaluation attributes the declared bounds and their lines read
  (every measure and named value outside `seon.repl`'s own derived ones) and
  the class of its value (which the def note reads). A
  reader that pulls an evaluation for its reply pulls these, so the findings
  see what the receipt recorded; nothing names them by hand."
  {:malli/schema [:=> [:cat] [:vector :qualified-keyword]]}
  []
  (let [{measures ::measures named ::named} @bounds]
    (into [] (comp (remove #(= "seon.repl" (namespace %))) (distinct))
          (concat measures named [:seon.eval/value-class]))))

(defn cost
  "The measures the declared bounds judge for one evaluation: each one its
  emission carries as recorded, and the estimated tokens of its printed
  output (`:seon.repl/out-tokens`). Absent means not measured."
  {:malli/schema [:=> [:cat :seon.repl/emission] :seon.repl/cost]}
  [emission]
  (let [output (:seon.cluster.eval/output emission)]
    (cond-> (select-keys emission (::measures @bounds))
      (seq output) (assoc :seon.repl/out-tokens (tokens/estimate output)))))

(defn findings
  "One map per declared bound the evaluation's cost exceeds: what it is
  (`:seon.repl/finding`), the measure and what was observed, the other
  measures the bound reports, and its fix. Empty within every bound, which is
  the normal reply: nothing is added to it."
  {:malli/schema [:=> [:cat :seon.repl/emission] [:vector :seon.repl/finding-value]]}
  [emission]
  (let [{explainer ::explainer declared ::declared} @bounds
        measured (cost emission)
        values (merge (select-keys emission (::named @bounds)) measured)]
    (into []
          (map (fn [{[measure] :in observed :value}]
                 (let [{kind :seon.repl/finding with :seon.repl/with fix :seon.repl/fix} (get declared measure)]
                   (cond-> (merge {:seon.repl/finding kind measure observed}
                                  (select-keys values with))
                     fix (assoc :seon.repl/fix fix)))))
          (:errors (explainer measured)))))

(defn response
  "The reply: exactly one `#:seon.repl{…}` map of the evaluation's entries,
  its shown text as shown (`response-entries`).

  Returns nil when the evaluation has settled nothing — a running form has
  not printed a result, and inventing an empty map would say it had."
  {:malli/schema [:=> [:cat :seon.repl/emission] [:maybe :string]]}
  [emission]
  (reply-map (response-entries emission)))

(defn- thinking-text
  "The agent's own `;;` prose for this evaluation, or nil.

  Stored separately from the source (`:seon.cluster.eval/comment`), so the
  HTML pair can give it its own block while the AI pair keeps it inside the
  REPL grammar, where it is part of the session the agent reads back."
  [{prose :seon.cluster.eval/comment changed? :seon.repl/changed-since?}]
  (when (and (not changed?) (seq prose))
    (str/trim-newline prose)))

(defn- input-text
  "The prompt line, the agent's comment and its source.

  The two-argument arity omits the comment for a reader that shows it as its
  own block; the AI grammar always includes it."
  ([emission] (input-text emission true))
  ([{source :seon.cluster.eval/source prompt-ns :seon.ns/name
     changed? :seon.repl/changed-since? :as emission}
    comment?]
   (let [prose (thinking-text emission)]
     (str (when changed? ";; changed since your last turn\n")
          (or prompt-ns 'user) "=> "
          (when (and comment? prose) (str prose "\n"))
          source))))

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
           (str "\n;; full value: " (print/form-text (list 'get-in full-handle [])))))))

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

  The one converter from an evaluation entity to its emission, for history,
  the debug session, the ledger and a turn's fresh forms alike. Resolve the
  evaluation's namespace ref against the carried database when the pull did
  not expand its name."
  {:malli/schema [:=> [:cat :seon.repl/entity-request] :seon.repl/emission]}
  [unit]
  (let [database (:seon.db/db unit)
        unit (if (map? (:seon.render/value unit))
               (:seon.render/value unit)
               unit)
        namespace-ref (:seon.cluster.eval/ns unit)
        namespace-name
        (or (when (map? namespace-ref) (:seon.ns/name namespace-ref))
            (when (and database namespace-ref)
              (:seon.ns/name
               (db/pull
                database [:seon.ns/name]
                (if (map? namespace-ref) (:db/id namespace-ref) namespace-ref)))))
        renderer-ref (:seon.eval/renderer-fn unit)
        renderer-symbol
        (or (get-in unit [:seon.eval/renderer-fn :seon.fn/sym])
            (when (and database renderer-ref)
              (:seon.fn/sym
               (db/pull
                database [:seon.fn/sym]
                (if (map? renderer-ref) (:db/id renderer-ref) renderer-ref)))))
        evaluation-id (:seon.cluster.eval/id unit)
        changed? (or (:seon.repl/changed-since? unit)
                     (when (and database evaluation-id)
                       (integer?
                        (db/q
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
                             :seon.cluster.eval/delimiter-repairs
                             :seon.cluster.eval/ordinal
                             :seon.eval/shown
                             :seon.eval/renderer
                             :seon.cluster.eval/error
                             :seon.cluster.eval/interrupted-at
                             :seon.cluster.eval/diagnostics
                             :seon.cluster.eval/output
                             :seon.sci.eval/ending-ns
                             :seon.eval/duration-ms
                             ;; THE STORED KEYS ARE THE ONES THE FORM SET.
                             ;; Reading `:seon.print/options` here left every
                             ;; per-form `set!` of *print-length* / *print-level*
                             ;; out of the response it was recorded for.
                             :seon.eval/print-length
                             :seon.eval/print-level
                             :seon.print/options
                             ;; ALREADY TEXT: the history assembly bounded the
                             ;; branch's new errors once and set them here.
                             :seon.repl/errors])
    ;; The recorded measures the declared bounds read, so this render's
    ;; findings match the MCP reply's and the next context's.
    :always (merge (select-keys unit (cost-attributes)))
    renderer-symbol
    (assoc :seon.eval/renderer (symbol renderer-symbol))
    changed? (assoc :seon.repl/changed-since? true)
    ;; ONE ENTITY PER (run, ordinal), ONE SPELLING. The frozen form family
    ;; is gone; the source, the ordinal and the namespace are this
    ;; evaluation's own attributes, so there is nothing to reconcile here.
    namespace-name
    (assoc :seon.ns/name namespace-name)

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
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (let [emission (entity-emission unit)]
    (when (seq (:seon.cluster.eval/source emission))
      (text emission))))

(defn- live-response
  [unit emission]
  (let [routing (:seon.agent/routing unit)
        agent-id (:seon.agent/id unit)
        ctx (or (get-in (when (and routing agent-id)
                         (@cluster-agent-armed routing agent-id))
                       [:seon.turn.loop/cluster :seon.sci.eval/agent-ctx])
                (:seon.sci.eval/agent-ctx unit))
        objects (some-> (:seon.sci.eval/result-objects ctx) deref)]
    (when-let [entry (find objects (:seon.cluster.eval/id emission))]
      ;; The live value selects its HTML face now; a saved AI renderer (or
      ;; its absence) is a past observation, not this face's policy.
      (value/prepare
       (assoc unit :seon.render/value (val entry)
                   :seon.render.value/root [:seon.cluster.eval/id (:seon.cluster.eval/id emission)])
       :seon.render/html))))

(defn render-html
  "Render the prompt and complete agent input together, then its response."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :seon.render/hiccup]]}
  [unit]
  (let [emission (entity-emission unit)
        answer (response emission)
        live (when answer (live-response unit emission))
        producer (:seon.render.call/selected-producer live)
        content (cond
                  (:seon.render.value/root-description live) [:p (:seon.error/message live)]
                  producer (value/render-html-data live)
                  live [:pre [:code {:class "seon-eval-response"}
                              (response (assoc emission :seon.eval/shown
                                                        (:seon.render.value/text live)))]]
                  (and answer (:seon.eval/renderer emission))
                  (into [:ul {:class "seon-eval-lines"}]
                        (map #(vector :li %) (str/split-lines answer)))
                  answer [:pre [:code {:class "seon-eval-response"} answer]])]
    (when (seq (:seon.cluster.eval/source emission))
      (cond-> (cond-> [:article {:class "seon-family-entry seon-eval-entry"}]
                ;; THE AGENT'S THINKING IS ITS OWN BLOCK on the page: the
                ;; comment is a separately stored fact, so the HTML pair shows
                ;; it as one rather than folding it into the prompt line.
                (thinking-text emission)
                (conj [:div {:class "seon-eval-thinking"}
                       (thinking-text emission)])
                :always
                (conj [:pre [:code {:class "seon-eval-prompt"}
                             (input-text emission false)]]))
        answer (conj [:small {:class "seon-eval-renderer"}
                      (str "AI: " (or (:seon.eval/renderer emission)
                                       'seon.render.value/render-ai)
                           " · HTML: " (or producer (when live 'seon.render.value/render-html)
                                             'seon.repl/render-html)
                           (when-not live " · saved text; live value unavailable"))]
                     content)))))
