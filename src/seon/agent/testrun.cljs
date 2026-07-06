(ns seon.agent.testrun
  "Parse test-runner output into data + persist the latest run as datoms.

   ONE parser, two consumption points: `seon.agent.shell/run` attaches the
   parsed map to a pytest run-response, and (via [[record!]]) the parsed
   result is projected into the graph so the `:test-failures` context
   section can render the CURRENT failing set reactively. Today the one
   recognized framework is pytest; the shape is framework-tagged so a second
   parser is a new `::framework` case here, never a second mechanism.

   Errors-are-values: [[parse]] NEVER throws and NEVER guesses — output it
   doesn't recognize as a test run returns `{::ok? false ::framework
   :unknown …}`, the caller attaches nothing.

   ## Worked example

     (seon.agent.testrun/parse {::stdout \"…FAILED path::test - msg…\"})
     ;; => {::ok? true ::framework :pytest ::passed 2 ::failed 1 ::errors 0
     ;;     ::failures [{::test-name \"test\" ::path \"path\" ::message \"msg\"}]}"
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — every key registered. Leaf attrs double as the persisted
;; failure-entity attrs (transacted as component entities). The response
;; composites reference the `::failure` MAP shape inline; the `::failures`
;; DB attr is the component-ref vector (a keyword is a map KEY in the
;; response, a schema only for the persisted attr — no conflict).
;; ============================================================

(schema/register! ::ok?       :boolean)
(schema/register! ::framework :keyword)                 ; :pytest today; :unknown when unrecognized
(schema/register! ::passed    [:int {:min 0}])
(schema/register! ::failed    [:int {:min 0}])
(schema/register! ::errors    [:int {:min 0}])
(schema/register! ::test-name :string)                  ; "test_foo" / "TestC::test_bar"
(schema/register! ::path      :string)                  ; "tests/test_x.py"
(schema/register! ::line      [:int {:min 0}])          ; optional — absent when unparseable
(schema/register! ::message   :string)                  ; the short-summary failure message
(schema/register! ::agent     :seon.db/ref)             ; SCOPE ref — the agent this run belongs to

(schema/register! ::failure
  [:map
   [::test-name ::test-name]
   [::path      ::path]
   [::line      {:optional true} ::line]
   [::message   ::message]])

;; The PERSISTED cardinality-many component ref (datahike bridge). Referenced
;; only as a DB attr; the response carries `::failures` as a map KEY with an
;; inline `[:vector ::failure]` value, so there is no schema clash.
(schema/register! ::failures [:vector {:seon.db/component true} :seon.db/ref])

;; A recognized run (attached to the shell envelope + persisted). `::failures`
;; here is the inline data vector the agent reads.
(schema/register! ::result
  [:map
   [::ok?       [:= true]]
   [::framework [:= :pytest]]
   [::passed    ::passed]
   [::failed    ::failed]
   [::errors    ::errors]
   [::failures  [:vector ::failure]]])

(schema/register! ::unrecognized
  [:map
   [::ok?       [:= false]]
   [::framework [:= :unknown]]
   [:seon.error/message :string]])

(schema/register! ::parse-request
  [:map
   [::stdout :string]
   [::stderr {:optional true} :string]])

(schema/register! ::parse-response [:or ::result ::unrecognized])

(schema/register! ::record-request
  [:map
   [::result   ::result]
   [::agent-id {:optional true} :string]])

(schema/register! ::record-response
  [:or
   [:map [::ok? [:= true]]]
   [:map [::ok? [:= false]] [:seon.error/message :string]]])

;; ============================================================
;; argv shape — is this invocation a pytest run? Computed prefix match,
;; not a hand-list: `pytest …` OR `python[3…] -m pytest …` (argv[0] alone
;; is "python" in the -m form). A new interpreter spelling widens the
;; basename regex, never a literal set.
;; ============================================================

(defn- basename
  "Last path segment of `cmd` — the argv[0] binary name, abs-path tolerant."
  [cmd]
  (last (str/split cmd #"/")))

(defn pytest-argv?
  "True when argv (`cmd` + `args`) invokes pytest — computed prefix match."
  {:malli/schema [:=> [:catn [::cmd :string] [::args [:vector :string]]] :boolean]}
  [cmd args]
  (let [base (basename cmd)]
    (boolean
      (or (= "pytest" base)
          (and (re-matches #"python[0-9.]*" base)
               (= "-m" (first args))
               (= "pytest" (second args)))))))

;; ============================================================
;; The pytest parser — pure, over stdout+stderr combined.
;; ============================================================

(defn- last-count
  "Last integer preceding a summary word (e.g. `2 passed`) in `text`, or nil.

   pytest emits these counts only in its final summary line, so the LAST
   match is authoritative across `-q`/`-v`/`--tb=short` variants (the
   summary format is stable even when the tracebacks change)."
  [text re]
  (some-> (last (re-seq re text)) second js/parseInt))

(defn- parse-failures
  "The `FAILED`/`ERROR` short-summary lines of `text` as `::failure` maps.

   Filters to targets that look like a test path (`::` or `.py`) so the
   FAILURES/ERRORS section headers and traceback `E …` lines never match.
   `::line` is left absent — the short-summary lines carry no line number."
  [text]
  (->> (str/split-lines text)
       (keep (fn [line]
               (when-let [[_ _kind target msg]
                          (re-matches #"^(FAILED|ERROR)\s+(\S+)(?:\s+-\s+(.*))?$"
                                      (str/trim line))]
                 (when (or (str/includes? target "::")
                           (str/includes? target ".py"))
                   (let [idx  (str/index-of target "::")
                         path (if idx (subs target 0 idx) target)
                         tnm  (if idx (subs target (+ idx 2)) target)]
                     {::test-name tnm
                      ::path      path
                      ::message   (str/trim (or msg ""))})))))
       vec))

(defn parse
  "Parse pytest stdout+stderr into a recognized-run map, or `:unknown`.

   Reads the `short test summary info` `FAILED`/`ERROR` lines for the
   failure set and the final summary line for the passed/failed/errors
   counts; tolerant of `-q`/`-v`/`--tb=short`. Recognizes a run by the
   presence of a summary count, `no tests ran`, `collected N item`, or the
   summary header. Unrecognized output is a value (`::ok? false`), never a
   throw and never a guess."
  {:malli/schema [:=> [:cat ::parse-request] ::parse-response]}
  [{::keys [stdout stderr]}]
  (let [text   (str stdout "\n" stderr)
        passed (last-count text #"(\d+)\s+passed")
        failed (last-count text #"(\d+)\s+failed")
        errors (last-count text #"(\d+)\s+errors?")]
    (if (or passed failed errors
            (re-find #"no tests ran" text)
            (re-find #"short test summary info" text)
            (re-find #"collected \d+ item" text))
      {::ok?       true
       ::framework :pytest
       ::passed    (or passed 0)
       ::failed    (or failed 0)
       ::errors    (or errors 0)
       ::failures  (parse-failures text)}
      {::ok?                false
       ::framework          :unknown
       :seon.error/message  "unrecognized test output format"})))

;; ============================================================
;; Persistence — project the recognized run as datoms (small structured
;; data → the datom tier). Each run is a new entity scoped to its agent;
;; the section reads the LATEST (no upsert, history keeps prior runs).
;; ============================================================

(defn ^:async record!
  "Persist a recognized pytest `::result` scoped to the running agent.

   Transacts one testrun entity (counts + framework) with its failures as
   component entities, scoped via `::agent` to the agent whose universe
   this runs in (`::agent-id`, else the ambient `db/current-agent-id`).
   No agent scope ⇒ nothing is persisted (`::ok? false`); the parse still
   rides the shell envelope. Latest-wins: a later green run supersedes the
   failures in the derived section."
  {:malli/schema [:=> [:cat ::record-request] ::record-response]}
  [{::keys [result agent-id]}]
  (let [aid (or agent-id (db/current-agent-id))]
    (if (nil? aid)
      {::ok? false :seon.error/message "no agent scope — testrun not persisted"}
      (let [{::keys [framework passed failed errors failures]} result
            env (await
                  (db/transact!
                    {:seon.db/tx-data
                     [(cond-> {::framework framework
                               ::passed    passed
                               ::failed    failed
                               ::errors    errors
                               ::agent     [:seon.agent/id aid]}
                        (seq failures)
                        (assoc ::failures
                               (mapv #(select-keys % [::test-name ::path
                                                      ::line ::message])
                                     failures)))]}))]
        (if (:seon.db/ok? env)
          {::ok? true}
          {::ok?               false
           :seon.error/message (get-in env [:seon.db/error :seon.error/message]
                                        "transact failed")})))))
