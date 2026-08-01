(ns seon.instrument
  "Malli instrumentation, applied explicitly, measured before it was built.

  Every number below was measured on this machine and is reproduced in
  `research/error-handling-grounding-2026-07-27.md` §4. Nothing here is
  a preference dressed as a rule.

  THE SELECTION IS COMPUTED, and the computation is one sentence: every
  loaded public var carrying `:malli/schema`. There is no namespace
  prefix, no allow list, and no exclusion list — a name-based rule is
  the hand list the standing ruling bans. It excludes every hot inner
  function BY CONSTRUCTION, which is the part worth understanding:
  `admit`'s `project`/`project-node`/`project-map`/`take-node!` and
  `eval`'s `arm`/`diagnosis`/`failure-value` are all `defn-` with no
  schema, so \"public + schema\" already means \"a boundary\", and
  instrumentation lands on boundaries and nowhere else without anybody
  maintaining that fact.

  THE COST, measured: **+129 ns** on `(add2 1 2)` against
  `[:=> [:cat :int :int] :int]`, **+175 ns** on one closed four-key map
  argument holding a 32-element vector. Read it as a flat ~130-180 ns
  per instrumented call, dominated by `(vec args)` + `apply` + the
  validator walk — not a multiplier. On a per-turn or per-transaction
  boundary that is free. On a per-node walk it would be fatal, and the
  paragraph above is why it cannot land there.

  WHAT `:report` CANNOT DO, measured, because the docs read otherwise:
  malli's wrapper calls `report` FOR EFFECT and then runs the function
  anyway (`malli/core.cljc:3110-3131`). Probed: the reported call still
  executed and still threw its natural `ClassCastException`. So a
  non-throwing report mode is \"tell me, then let it break\" — never
  graceful degradation, and describing it as such would be a lie a
  reader would plan around.

  THE DIAL, therefore:

  - `:panic` (development) — INSTRUMENT, and the reporter THROWS. A
    contract violation is a bug in our own code and the first call is
    where it is cheapest to find. Fail loud is not fall down: the throw
    halts that CALL, not the tower. And when the caller is a flow proc,
    the throw is a Throwable escaping our code onto `::flow/error`,
    which is exactly the path `seon.error` already owns — so an
    instrumentation violation inside the run loop becomes a durable
    error fact with `:seon.error/kind ::contract-violated`, an
    explanation message, and a `problems` entry, through machinery
    nobody had to add here. That composition is the reason this
    namespace is small.
  - `:record` (production) — INSTRUMENT NOTHING, and remove whatever is
    instrumented. Judgment, flagged for the owner and reversible in one
    line: report mode cannot prevent the bad call (measured above), it
    taxes every public boundary ~150 ns forever, and the natural failure
    that follows a violation still becomes a fault through the wired
    error channel — so nothing is lost silently by staying out of the
    way. The alternative, if the owner wants contract violations named
    in production rather than diagnosed from their consequences, is to
    instrument with a reporter that commits an error fact instead of
    throwing.

  HOT RELOAD STRIPS INSTRUMENTATION SILENTLY, and this is the sharpest
  measured fact here. Re-evaluating a `defn` replaces the var's root,
  `alter-var-root`'s wrapper is gone, the `::original` meta is gone, and
  NOTHING warns: the var looks fine and is unprotected. The schema stays
  registered, so the registry now disagrees with reality. `malli.dev`'s
  watch does not save it either — the watch fires on
  `-register-function-schema!`, and a plain re-eval never touches that
  atom (probed: watch fired on re-collect, not on re-eval).

  THE DISCIPLINE, therefore, is one line: **re-run `apply!` after
  re-evaluating anything.** It is idempotent, it is fast, and it is the
  only reliable trigger. A future edit hook can call it for you; that is
  a note, not a thing this namespace does.

  `malli.dev/start!` is REJECTED as an entry point: it `alter-var-root`s
  `m/-fail!` globally, installs a pretty printer as the reporter, and
  writes clj-kondo config (`malli/dev.clj:13-23, 40-66`). We want a
  violation to become a durable fact, not a coloured box on stderr.

  `remove!` is EMERGENCY RECOVERY, not a dial and never the answer to a
  noisy report. A noisy report means the schema or the caller is wrong;
  the first one found this way was real (`loop.cljc` passing a
  transaction argument map where the contract said vector — see
  `docs/seon/issues/archive/loop-open-transaction-violates-transact-schema.md`)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/instrument.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; What is instrumented, as a question anybody can ask
;;; ---------------------------------------------------------------------------

(defn instrumented
  "The vars carrying an instrumentation wrapper right now.
  Malli stamps the original fn under `::mi/original` when it wraps, so
  this is a fact about the running process rather than a count somebody
  remembered to keep. It is what makes `apply!` idempotence and the
  hot-reload strip both observable instead of assumed."
  {:malli/schema [:=> [:cat] [:set :any]]}
  []
  (into #{}
        (comp (mapcat ns-publics)
              (map val)
              (filter (fn [candidate]
                        (and (bound? candidate)
                             (some-> (deref candidate) meta ::mi/original)))))
        (all-ns)))

;;; ---------------------------------------------------------------------------
;;; The reporter
;;; ---------------------------------------------------------------------------

(defn- violation
  "One malli report as a flat, bounded, agent-readable value.
  `:args` can hold ANYTHING — a live Datahike connection is an ordinary
  argument at these boundaries — so it goes through the one codec, the
  same as every other error payload. With no caps to bound it, the args
  are OMITTED rather than printed: a description that can hang is worse
  than no description."
  ;; malli's report data names the OFFENDING SCHEMA and the OFFENDING
  ;; VALUE separately, and they are different keys per arm
  ;; (`core.cljc:2215,2218`): input is checked against the args vector,
  ;; output against the returned value. Explaining the whole `:=>`
  ;; schema instead — the first thing this reporter did — humanizes to
  ;; the useless "should be a valid function".
  [caps kind data]
  (if (= :malli.core/invalid-arity kind)
    (let [function-symbol (:fn-name data)
          arglists (some-> function-symbol find-var meta :arglists)]
      {:seon.error/kind ::contract-violated
       :seon.error/message
       (str "Wrong number of args (" (:arity data) ") passed to: "
            function-symbol)
       :seon.error/data
       (cond-> {::malli kind
                ::arm :input
                ::arity (:arity data)
                ::fn (str function-symbol)}
         arglists (assoc ::arglists arglists))})
    (let [[offended value] (if (= :malli.core/invalid-output kind)
                             [(:output data) (:value data)]
                             [(:input data) (:args data)])
          schema-form (m/form offended)
          expected (if (and (= :malli.core/invalid-input kind)
                            (= :cat (first schema-form))
                            (= 2 (count schema-form)))
                     (second schema-form)
                     schema-form)]
      (cond-> {:seon.error/kind ::contract-violated
               :seon.error/message
               (str (:fn-name data) " violated its contract ("
                    (name kind) "): "
                    (pr-str (me/humanize (m/explain offended value))))
               :seon.error/data
               (cond-> {::malli kind
                        ::arm (if (= :malli.core/invalid-output kind)
                                :output
                                :input)
                        ::schema (pr-str expected)}
                 (:fn-name data) (assoc ::fn (str (:fn-name data))))}
        caps
        (update :seon.error/data assoc ::args
                (:seon.cluster.eval/result-edn
                 (admit/admit {:seon.sci.admit/value value
                               :seon.sci.admit/interrupt-fn (constantly nil)
                               :seon.sci.admit/caps caps
                               ;; the reporter may not panic on the way to
                               ;; reporting a panic
                               :seon.config/on-core-error :record})))))))

(defn- throwing-report
  "The `:panic` reporter: raise the violation as our own flat error.
  Deliberately NOT `m/-fail!`. The ex-data carries `:seon.error/kind`,
  so when this throw escapes a flow proc the fault path classifies it
  from the cause chain like any other refusal and the durable fact says
  `::contract-violated` rather than naming malli."
  [caps]
  (fn [kind data]
    (let [value (violation caps kind data)]
      (throw (ex-info (:seon.error/message value) value)))))

;;; ---------------------------------------------------------------------------
;;; The one operation
;;; ---------------------------------------------------------------------------

(defn apply!
  "Collect function schemas and instrument per the dial. IDEMPOTENT.
  `(mi/clj-collect! {:ns (all-ns)})` — the FUNCTION, not the macro,
  because the namespace set is a runtime value — then `mi/instrument!`
  with `:scope #{:input :output}`. `:guard` is dropped: no schema in the
  tree declares one and including it costs a validate call per
  invocation for nothing.

  On `:panic` the reporter throws (see the namespace docstring for why,
  and for what happens when the throw escapes a proc). On `:record`
  this instruments nothing and REMOVES any wrapper already installed, so
  moving the dial to production actually takes effect rather than
  leaving yesterday's wrappers in place.

  Returns `{:seon.instrument/registered n :seon.instrument/instrumented m}`
  so \"is instrumentation on right now\" is answerable. In `:panic` a
  count of ZERO is a bug — there are hundreds of schema'd public vars in
  this tree — so it says so on stderr rather than passing quietly.

  Call it again after re-evaluating anything: a re-`defn` silently
  strips the wrapper and no watch fires. Every apply first reloads and
  activates the complete candidate schema population because Malli collection
  compiles against Seon's stable active registry. Merely contributing a new
  named reference leaves an already-active projection unchanged."
  {:malli/schema [:=> [:cat :seon.instrument/request] :seon.instrument/applied]}
  [{mode :seon.config/on-core-error caps :seon.sci.admit/caps}]
  (schema.edn/load! {})
  (let [candidates (schema/snapshot)
        active (some-> (schema/current-projection)
                       :seon.schema.projection/forms)]
    (when-not (= active candidates)
      (schema/activate! candidates)))
  (let [registered (count (mi/clj-collect! {:ns (all-ns)}))]
    (case mode
      :panic
      (do (mi/instrument! {:scope #{:input :output}
                           :report (throwing-report caps)})
          (let [count-now (count (instrumented))]
            (when (zero? count-now)
              (binding [*out* *err*]
                (println "seon.instrument: :panic instrumented ZERO vars —"
                         "that is a bug, not a quiet success")
                (flush)))
            {:seon.instrument/registered registered
             :seon.instrument/instrumented count-now}))

      :record
      (do (mi/unstrument!)
          {:seon.instrument/registered registered
           :seon.instrument/instrumented (count (instrumented))}))))

(defn remove!
  "Strip every instrumentation wrapper. EMERGENCY RECOVERY ONLY.
  Not a dial, and never the answer to a noisy report — a noisy report
  means the schema or the caller is wrong, and both are fixable at the
  source. Returns how many wrappers survive, which is zero unless
  something re-instrumented underneath."
  {:malli/schema [:=> [:cat] :seon.instrument/instrumented]}
  []
  (mi/unstrument!)
  (count (instrumented)))
