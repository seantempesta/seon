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
      #:seon.repl{:value 2, :result result/e0, :ms 3}

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
            [seon.schema.edn :as schema.edn])
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
   :seon.repl/result
   :seon.repl/out
   :seon.repl/ns
   :seon.repl/ms])

(defn- read-node
  "Read one stored EDN print node back, or say it is unreadable."
  [serialized]
  (with-open [reader (PushbackReader. (StringReader. serialized))]
    (try
      (let [value (edn/read {:eof ::eof} reader)
            trailing (edn/read {:eof ::eof} reader)]
        (if (and (not= ::eof value) (= ::eof trailing))
          {::node value}
          {::unreadable? true}))
      (catch Throwable _
        {::unreadable? true}))))

(defn value-text
  "Render one stored admitted print node as the text the REPL printed.

  The node is the ONE durable representation of a result: this reads it, it
  never stores it a second time, and a clipped value arrives carrying its own
  elision node, so nothing here needs a `capped?` flag to tell the truth.
  An unreadable node degrades to an honest diagnostic value rather than to
  silence."
  {:malli/schema [:=> [:cat :seon.repl/emission] [:maybe :string]]}
  [{serialized :seon.cluster.eval/result-edn
    node :seon.print/node
    options :seon.print/options}]
  (let [node (if (some? node)
               node
               (when (string? serialized)
                 (let [{::keys [node unreadable?]} (read-node serialized)]
                   (if unreadable?
                     {:seon.cluster.eval/result-edn serialized
                      :seon.render.transcript/unreadable? true}
                     node))))]
    (cond
      (nil? node) nil

      (and (map? node) (= :seon.print/string (:seon.print/face node)))
      (:seon.print/value node)

      (and (map? node) (:seon.print/face node))
      (print/emit-text node (merge (print/default-options) options))

      (string? node) node

      :else (let [rendered (value/render-ai {:seon.render/value node
                                             :seon.render.call/id
                                             [::value node]})]
              (if (string? rendered) rendered (pr-str node))))))

(defn error-text
  "Clojure's own concise REPL error for one failed evaluation.

  The recorded `ex-triage` data is the authority; the stored message is the
  fallback when triage is absent or unreadable, because a refusal that names
  nothing is worse than a plainer one that names the throwable."
  {:malli/schema [:=> [:cat :seon.repl/emission] [:maybe :string]]}
  [{error :seon.cluster.eval/error triage :seon.cluster.eval/triage-edn}]
  (when (string? error)
    (or (when (string? triage)
          (try
            (-> triage edn/read-string main/ex-str str/trim-newline)
            (catch Throwable _ nil)))
        (-> {:clojure.error/phase :execution :clojure.error/cause error}
            main/ex-str
            str/trim-newline))))

(defn- response-entries
  "The response's present keys, in declared order, each already text."
  [{ordinal :seon.cluster.eval/ordinal
    output :seon.cluster.eval/output
    ending-ns :seon.sci.eval/ending-ns
    prompt-ns :seon.ns/name
    duration :seon.eval/duration-ms
    :as emission}]
  (let [value (value-text emission)
        error (error-text emission)
        by-key {:seon.repl/value (when (and (nil? error) (some? value)) value)
                :seon.repl/error (some-> error pr-str)
                ;; A handle names a value a later turn can reach. Ruling 59c:
                ;; a caller that could not bind one says so with an explicit
                ;; false, and the response then simply has no `:result` key.
                :seon.repl/result (when (and (not (false?
                                              (:seon.repl/result-handle?
                                               emission)))
                                             (int? ordinal)
                                             (or (some? value) (some? error)))
                                    (str "result/e" ordinal))
                :seon.repl/out (when (seq output) (pr-str output))
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
