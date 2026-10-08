(ns seon.batch
  "The one REPL path: read, evaluate, record, print, as one fold over a batch
  of text whose stages are values.

  `evaluate-batch` owns only the order and the totality. Each entrant supplies
  its stages in the request (`:seon.batch/request`):

    :seon.batch/around     wraps the whole batch (SCI: namespace binding and
                           the schema registration delta)
    :seon.batch/read-next  one read step at the live cursor
    :seon.batch/evaluate   one form, once, against the working database value
    :seon.batch/observe    runs one evaluation under the write observer,
                           handing each ordered write to the batch
    :seon.batch/ns-name    the current namespace
    :seon.batch/derive     one form's observed writes -> its program change
    :seon.batch/facts      a program change -> transaction data on a value
    :seon.batch/apply      a change's transaction data on the working value ->
                           the next working value, validated and gated, or
                           the refusal
    :seon.batch/fold       an applied change folded into the batch's program
                           by identity (what the one transaction proposes)
    :seon.batch/settle     the one recording transaction
    :seon.batch/show       one entry's shown text
    :seon.batch/fault      records one diagnostic as a fault

  Every stage but read, evaluate and ns-name is optional: an absent stage is
  a stage not run. A form's value is published into the batch the moment its
  evaluation returns; every later step for that form, and every step after
  the loop, adds a diagnostic on failure and keeps everything published.

  A form whose facts the apply stage refuses keeps its value; the refusal is
  its `:seon.batch/facts-error`, a logged return value, and its facts are not
  proposed. The one transaction therefore holds only facts already validated
  in order."
  (:require [clojure.string :as str]
            [seon.error.refusal :as refusal])
  (:import (java.util Date)))

(def ^:dynamic *world*
  "The world of the evaluation running on this thread: the entrant's world
  value (its connection and writer; no database value). The fold binds it around
  each evaluation and its print; `seon.turn.batch/with-world` binds it again
  after a thread hop. It is the one source a called function's omitted
  world-member inputs are supplied from."
  nil)

(def world-members
  "Each world member by the schema name a function declares it under: an
  omitted input declared under one of these names is supplied from the
  calling evaluation's world, required or optional. A function whose meaning
  needs the member absent declares another name. The database is no stored
  member: it is the world connection's current value, read at the call
  (`seon.call-preparation/supply`)."
  {:seon.db/database-value :seon.db/db
   :seon.db/connection :seon.db/connection
   :seon.db/writer :seon.db/writer
   :seon.agent/id :seon.agent/id
   :seon.task/id :seon.task/id
   :seon.render/profile :seon.render/profile
   :seon.batch/sink :seon.batch/sink
   :seon.sci.eval/ctx :seon.sci.eval/ctx
   :seon.store/store :seon.store/store
   :seon.agent/routing :seon.agent/routing
   :seon.batch/defined :seon.batch/defined})

(defn environment-members
  "The world members an execution's environment holds: its store and its
  agent routing (what branch operations read, `my.agent/create-branch!`)."
  {:malli/schema [:=> [:cat :map] [:map [:seon.store/store {:optional true} :seon.store/store]
                                   [:seon.agent/routing {:optional true} :seon.agent/routing]]]}
  [environment]
  (select-keys environment [:seon.store/store :seon.agent/routing]))

(defn sink?
  "True for a call sink."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason "A predicate answers for any value." :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [x]
  (instance? java.util.concurrent.ConcurrentHashMap x))

(defn stamped-clock?
  "True for a world's stamp clock."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason "A predicate answers for any value." :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [x]
  (instance? java.util.concurrent.atomic.AtomicLong x))

(defn owned?
  "True for a form's owned-work count."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason "A predicate answers for any value." :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [x]
  (instance? java.util.concurrent.atomic.AtomicLong x))

(defn sink
  "A new call sink: the per-world armed calls and their nanoseconds, by
  executed definition `[sym digest]` (a nil digest is unknown), each a
  `[calls nanos]` counter (`java.util.concurrent.atomic.AtomicLongArray`).
  Concurrent calls from the world's evaluations and the futures they start add
  to it."
  {:malli/schema [:=> [:cat] :seon.batch/sink]}
  []
  (java.util.concurrent.ConcurrentHashMap.))

(defn- counters
  "A sink's counters as a value: `[sym digest]` -> [calls nanos]."
  {:malli/schema [:=> [:cat :seon.batch/sink] [:map-of :seon.batch/executed-key [:tuple nat-int? nat-int?]]]}
  [^java.util.concurrent.ConcurrentHashMap s]
  (into {} (map (fn [[k ^java.util.concurrent.atomic.AtomicLongArray a]] [k [(.get a 0) (.get a 1)]])) s))

(defn executed
  "A sink's counts as a value: `[sym digest]` -> calls. An entered call that
  has not returned is present with 0."
  {:malli/schema [:=> [:cat :seon.batch/sink] [:map-of :seon.batch/executed-key nat-int?]]}
  [s]
  (update-vals (counters s) first))

(def repeated-calls
  "A function called more than this many times in one evaluation is named in
  its call profile: repetition visible when it happens."
  100)

(defn call-profile
  "One evaluation's call profile from its sink: the armed calls it made, and
  each function it called more than `repeated-calls` times with its calls and
  milliseconds, most calls first."
  {:malli/schema [:=> [:cat :seon.batch/sink] :seon.batch/call-profile]}
  [s]
  (let [by-sym (reduce (fn [m [[sym _] [n t]]] (update m sym (fn [[a b]] [(+ (or a 0) n) (+ (or b 0) t)])))
                       {} (counters s))]
    (cond-> {:seon.batch/call-count (reduce + 0 (map first (vals by-sym)))}
      (some (fn [[n _]] (> n repeated-calls)) (vals by-sym))
      (assoc :seon.batch/repeated
             (vec (sort-by (comp - second)
                           (keep (fn [[sym [n t]]] (when (> n repeated-calls) [sym n (quot t 1000000)])) by-sym)))))))

(defn calls
  "A sink's counts by symbol: symbol -> calls, every executed definition of it
  summed."
  {:malli/schema [:=> [:cat :seon.batch/sink] [:map-of :qualified-symbol nat-int?]]}
  [s]
  (reduce (fn [m [[sym _] n]] (update m sym (fnil + 0) n)) {} (executed s)))

(def ^:private ^java.util.function.Function new-adder
  "A sink's counter for a key it has not seen, the same five slots as
  `seon.profile/new-adder` makes: calls, nanos, self nanos and the two marks
  `seon.profile/transaction-observation` keeps (seon.profile requires this
  namespace, so the constructor is repeated here)."
  (reify java.util.function.Function
    (apply [_ _] (java.util.concurrent.atomic.AtomicLongArray. 5))))

(defn- ms-since
  {:malli/schema [:=> [:cat :int] :double]}
  [t0]
  (/ (- (System/nanoTime) t0) 1e6))

(defn tenths
  "Milliseconds to 0.1, the precision a request's cost shows."
  {:malli/schema [:=> [:cat number?] :double]}
  [ms]
  (/ (Math/round (* 10.0 (double ms))) 10.0))

(defn request-cost
  "One eval request's cost: wall milliseconds since `started-ns` (a
  `System/nanoTime` reading at its entrance), the wall milliseconds of each
  stage it ran and the counts its worlds' sinks hold, when measured."
  {:malli/schema [:=> [:cat :int [:map-of :keyword number?] [:maybe :seon.cost/counts]] :seon.cost/request]}
  [started-ns stages counts]
  (cond-> (merge {:seon.cost/ms (tenths (ms-since started-ns))} counts)
    (seq stages) (assoc :seon.cost/stages (update-vals stages tenths))))

(defn arm-measures
  "The allocation and CPU time of the forms' evaluation arms (each arm's
  platform worker and the platform threads that adopted it), summed; a measure
  any form lacks is absent, its reason in `:seon.cost/unmeasured`."
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [evaluations]
  (let [allocated (map #(:seon.eval/allocated-bytes % -1) evaluations)
        cpu (map #(:seon.cost/cpu-ns % -1) evaluations)
        unmeasured (cond-> {}
                     (some neg? allocated) (assoc :seon.cost/alloc-mb :unmeasured-arm)
                     (some neg? cpu) (assoc :seon.cost/cpu-ms :unmeasured-arm))]
    (cond-> {}
      (empty? evaluations) (assoc :seon.cost/unmeasured {:seon.cost/alloc-mb :no-form :seon.cost/cpu-ms :no-form})
      (and (seq evaluations) (not-any? neg? allocated)) (assoc :seon.cost/alloc-mb (/ (Math/round (/ (double (reduce + allocated)) 1e5)) 10.0))
      (and (seq evaluations) (not-any? neg? cpu)) (assoc :seon.cost/cpu-ms (tenths (/ (reduce + cpu) 1e6)))
      (and (seq evaluations) (seq unmeasured)) (assoc :seon.cost/unmeasured unmeasured))))

(defn diagnostic
  "One diagnostic: the failing stage, the form it belongs to, the cause."
  {:malli/schema [:=> [:cat :keyword [:maybe :int] :seon.error/throwable] :seon.batch/diagnostic]}
  [stage ordinal ^Throwable t]
  (cond-> {:seon.error/message (str (name stage) ": " (or (ex-message t) (.getName (class t))))
           :seon.batch/stage stage
           :seon.error/throwable-class (.getName (class t))}
    ordinal (assoc :seon.batch/ordinal ordinal)
    (ex-data t) (assoc :seon.error/data (ex-data t))
    ;; the live failure, kept until the fault stage records it: its frames
    ;; name the producer (seon.error.refusal/producer), never the recorder
    true (assoc :seon.error/throwable t)))

(defn- guard
  "(f batch), or batch with one diagnostic naming `stage` when f throws."
  {:malli/schema [:=> [:cat :seon.batch/folding :keyword [:=> [:cat :seon.batch/folding] :seon.batch/folding]]
                  :seon.batch/folding]}
  [batch stage f]
  (try (f batch)
       (catch Throwable t
         (update batch :seon.batch/diagnostics conj
                 (diagnostic stage (:seon.batch/ordinal batch) t)))))

(defn- clock
  {:malli/schema [:=> [:cat :seon.batch/folding :keyword :int] :seon.batch/folding]}
  [batch k t0]
  (update-in batch [:seon.batch/timings k] (fnil + 0.0) (ms-since t0)))

(defn- with-entry
  "`batch` with `entry` last: it replaces the last entry when their ordinals
  are the same, else it is published after it. The one place an entry is
  validated, once per change, so a step costs its own entry, never the batch's."
  {:malli/schema [:=> [:cat :seon.batch/folding :seon.batch/entry] :seon.batch/folding]}
  [batch entry]
  (update batch :seon.batch/entries
          (fn [es] (if (= (:seon.batch/ordinal entry) (:seon.batch/ordinal (peek es))) (conj (pop es) entry) (conj es entry)))))

(defn- last-entry
  "`batch` with its last entry replaced by `(f entry)`."
  {:malli/schema [:=> [:cat :seon.batch/folding [:=> [:cat :seon.batch/entry] :seon.batch/entry]] :seon.batch/folding]}
  [batch f]
  (with-entry batch (f (peek (:seon.batch/entries batch)))))

(defn- with-writes
  "`batch` with one form's observed writes after its earlier ones, each
  validated here, once."
  {:malli/schema [:=> [:cat :seon.batch/folding [:vector :seon.batch/write]] :seon.batch/folding]}
  [batch writes]
  (update batch :seon.batch/writes into writes))

(defn- line-cursor
  "The line and column of offset `start` in `text`: scanned on from `cursor`
  when it is in the same text at or before `start`, else from the text's
  start, so a batch's positions cost its text once."
  {:malli/schema [:=> [:cat [:maybe :seon.batch/line-cursor] :string nat-int?] :seon.batch/line-cursor]}
  [cursor ^String text start]
  (let [start (long start)
        {p :seon.batch/pos l :seon.batch/line c :seon.batch/column}
        (if (and cursor (identical? text (:seon.batch/text cursor)) (<= (long (:seon.batch/pos cursor)) start))
          cursor
          {:seon.batch/pos 0 :seon.batch/line 1 :seon.batch/column 1})]
    (loop [i (long p) l (long l) c (long c)]
      (if (< i start)
        (if (= \newline (.charAt text i)) (recur (inc i) (inc l) 1) (recur (inc i) l (inc c)))
        {:seon.batch/text text :seon.batch/pos i :seon.batch/line l :seon.batch/column c}))))

;;; ---------------------------------------------------------------------------
;;; One form
;;; ---------------------------------------------------------------------------

(defn- read-step
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/folding] :seon.batch/folding]}
  [request batch]
  (let [t0 (System/nanoTime)
        r ((:seon.batch/read-next request) request (:seon.batch/read-state batch))
        text (:seon.batch/text (:seon.batch/version r))
        {:seon.batch/keys [start end]} r
        in-bounds? (or (not (contains? r :seon.batch/form))
                       (and (nat-int? start) (nat-int? end) (<= start end (count text))))]
    (cond-> (-> batch
                (assoc :seon.batch/step r)
                ;; a read stage over its own sources answers its next state
                (update :seon.batch/read-state #(if-let [next-state (:seon.batch/read-state r)]
                                                  next-state
                                                  (cond-> (assoc % :seon.batch/version (:seon.batch/version r))
                                                    end (assoc :seon.batch/last-code-end end))))
                (clock :read t0))
      (and (nat-int? start) (<= start (count text)))
      (update :seon.batch/line-cursor line-cursor text start)
      (not in-bounds?)
      (-> (update :seon.batch/diagnostics conj
                  {:seon.error/message (str "read: the read stage answered span [" start ", " end
                                            ") outside its text of " (count text) " characters")
                   :seon.batch/stage :read :seon.batch/ordinal (:seon.batch/ordinal batch)})
          (assoc-in [:seon.batch/step :seon.batch/span-invalid?] true)))))

(defn- source-of
  "Where the read form came from: its source text and position, known before
  it is evaluated. `cursor` is the read step's position when it is this
  form's (`read-step`)."
  {:malli/schema [:=> [:cat :seon.batch/read-step [:maybe :seon.batch/line-cursor]] :map]}
  [r cursor]
  (let [text (:seon.batch/text (:seon.batch/version r))
        start (:seon.batch/start r)]
    (if (:seon.batch/span-invalid? r)
      ;; a span the read stage misreported has no source text: the read
      ;; diagnostic names it and the form itself is the step's value
      {}
      (let [source (str/trimr (subs text start (:seon.batch/end r)))
            {l :seon.batch/line c :seon.batch/column}
            (if (and (identical? text (:seon.batch/text cursor)) (= start (:seon.batch/pos cursor)))
              cursor
              (line-cursor nil text start))]
        {:seon.batch/source source :seon.batch/line l :seon.batch/column c}))))

(defn- allocated-bytes
  "The calling thread's allocated bytes so far (HotSpot's ThreadMXBean), nil
  on a virtual thread, for which HotSpot keeps no count
  (`seon.batch/allocation-is-unmeasured-on-a-virtual-thread`)."
  {:malli/schema [:=> [:cat] [:maybe nat-int?]]}
  []
  (let [allocated (.getCurrentThreadAllocatedBytes
                   ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean))]
    (when-not (neg? allocated) allocated)))

(defn- unprintable
  "The minimal shown text of an entry whose print failed; total, nil included."
  {:malli/schema [:=> [:cat :seon.batch/entry] :string]}
  [e]
  (str "#<unprintable "
       (if (contains? e :seon.batch/value)
         (if-some [v (:seon.batch/value e)] (.getName (class v)) "nil")
         "error")
       ">"))

(defn- print-into
  "`entry` with its shown text from the show stage (a render's own failure
  rides its shown map's diagnostics, the agent's); a failed print is the
  minimal text plus the failure."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/entry]
                  [:tuple :seon.batch/entry [:maybe :seon.error/throwable]]]}
  [request entry]
  (try (let [p ((:seon.batch/show request) request entry)]
         (if (string? p)
           [(assoc entry :seon.batch/shown p) nil]
           [(cond-> (assoc entry :seon.batch/shown (:seon.batch/shown p))
              (:seon.batch/shown-map p) (assoc :seon.batch/shown-map (:seon.batch/shown-map p)))
            nil]))
       (catch Throwable t [(assoc entry :seon.batch/shown (unprintable entry)) t])))

(defn- write?
  "Whether an observation is a write the batch records: a map of the declared
  operations with its operation's members (`:seon.batch/write`). Total: an
  observation whose own lookup throws (a sorted map of other keys) is not."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason "An observer stage may hand anything; this predicate decides what the batch records." :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [w]
  (boolean
   (try
     (and (map? w)
          (case (get w :seon.batch/op)
            :bind (and (some? (get w :seon.batch/var)) (keyword? (get w :seon.batch/kind))
                       (let [s (get w :seon.batch/sym ::none)] (or (= ::none s) (qualified-symbol? s))))
            :unmap (qualified-symbol? (get w :seon.batch/sym))
            (:remove-ns :namespace) (symbol? (get w :seon.batch/ns))
            :expansion (and (symbol? (get w :seon.batch/macro)) (vector? (get w :seon.batch/call-span)))
            false))
     (catch Throwable _ false))))

(defn- form-world
  "The world one form runs in: the entrant's world and the form's own
  evidence members, fresh: its call sink
  (`:seon.batch/form-sink`, which the evaluator binds as the world's sink
  around the form's own code only, so the stages' machinery is not the form's
  code evidence), its
  read sink (an atom `seon.turn.world/with-world` binds as the database read
  sink) and its owned-work count (the futures the form starts,
  `seon.sci.kernel/future-call`). Everything the form and the work it starts
  do lands here, whatever form is running when it finishes."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.cluster.eval/ordinal] :seon.batch/world]}
  [request ordinal]
  (assoc (:seon.batch/world request)
         :seon.batch/ordinal ordinal
         :seon.batch/form-sink (sink)
         :seon.batch/reads (atom [])
         :seon.batch/in-flight (java.util.concurrent.atomic.AtomicLong.)))

(defn- within
  "Run `f` in `world` on this thread: the entrant's world boundary (custody,
  read basis, `*world*`), or only `*world*`."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/world [:=> [:cat] :any]] :any]}
  [request world f]
  (if-let [boundary (:seon.batch/within request)]
    (boundary world f)
    (with-bindings (assoc (:seon.batch/scope world {}) #'*world* world) (f))))

(defn- outcome?
  "Whether an evaluate stage answered an outcome: exactly one of a value or an
  error, beside the entrant's own keys."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [o]
  (and (map? o) (not= (contains? o :seon.batch/value) (contains? o :seon.batch/error))))

(defn- evaluate-step
  "Evaluate the read form once, and print its value, under one observation
  scope, then publish the outcome. Never throws.

  The evaluate stage answers an outcome (`:seon.batch/value` or
  `:seon.batch/error`, with any entrant keys, which the entry keeps); a throw
  is its error. The observation's writes accumulate in a per-call concurrent
  queue (writes may come from futures the form starts); the scope completes
  when the observer stage returns, after the value was printed, so a
  definition made while realizing a lazy value is observed. A write offered
  after completion is refused to its writer. A write that is not a map is a
  diagnostic. The form's world stays on the batch until its evidence is
  frozen (`freeze-step`)."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/folding] :seon.batch/folding]}
  [request batch]
  (let [r (:seon.batch/step batch)
        n (:seon.batch/ordinal batch)
        t0 (System/nanoTime)
        base (merge {:seon.batch/ordinal n :seon.batch/form (:seon.batch/form r)} (source-of r (:seon.batch/line-cursor batch)))
        world (cond-> (form-world request n)
                (seq (:seon.batch/defined batch)) (assoc :seon.batch/defined (:seon.batch/defined batch)))
        ;; the stages read the batch's working value from their request
        request (assoc request :seon.batch/world world :seon.batch/working (:seon.batch/working batch))
        writes (java.util.concurrent.ConcurrentLinkedQueue.)
        open (java.util.concurrent.atomic.AtomicBoolean. true)
        on-write (fn [w]
                   (when-not (.get open)
                     (throw (ex-info "The evaluation that owned this observation has completed."
                                     {:seon.batch/ordinal n :seon.batch/write w})))
                   (.add writes (if (nil? w) ::nil-write w)))
        returned (volatile! nil)
        printed (volatile! nil)
        bytes0 (allocated-bytes)
        measured (volatile! nil)
        ;; an unmeasured allocation is no key, never 0
        measure! (fn [] (vreset! measured (let [bytes1 (allocated-bytes)]
                                            (cond-> {:seon.batch/duration-ms (ms-since t0)}
                                              (and bytes0 bytes1) (assoc :seon.batch/allocated-bytes (- bytes1 bytes0))))))
        run (fn run
              ([] (run {}))
              ([scope]
               ;; an observer's scope (its bindings) rides the world
               (within request (cond-> world (seq scope) (assoc :seon.batch/scope scope))
                 (fn []
                   (let [o (try ((:seon.batch/evaluate request) request (:seon.batch/form r) (:seon.batch/working batch))
                                (catch Throwable t {:seon.batch/error t}))
                         o (if (outcome? o)
                             o
                             {:seon.batch/error (ex-info "The evaluate stage answered no outcome."
                                                         {:seon.batch/stage :evaluate})})]
                     ;; retained the moment the stage returns: nothing later loses it
                     (vreset! returned o)
                     (measure!)
                     (when (:seon.batch/show request)
                       (vreset! printed (print-into request (merge base @measured o))))
                     o)))))
        read-error (:seon.batch/read-error r)
        failure (try
                  (cond
                    read-error nil
                    (:seon.batch/observe request)
                    ((:seon.batch/observe request) request (:seon.batch/working batch) on-write run)
                    :else
                    (run))
                  nil
                  (catch Throwable t t))
        _ (.set open false)
        _ (when-not @measured (measure!))
        base (merge base @measured)
        value? (some? @returned)
        entry (cond
                read-error (assoc base :seon.batch/error read-error)
                (and value? @printed) (first @printed)
                value? (merge base @returned)
                :else (assoc base :seon.batch/error failure))
        [entry print-failure] (if (or (contains? entry :seon.batch/shown) (not (:seon.batch/show request)))
                                            [entry (second @printed)]
                                            (within request world #(print-into request entry)))
        ;; published before any observation is normalized: a malformed
        ;; observation is a diagnostic beside the value, never its loss
        published (-> batch
                      (with-entry (assoc entry :seon.batch/total-ms (ms-since t0)))
                      (update :seon.batch/diagnostics into
                              (cond-> []
                                (and value? failure) (conj (diagnostic :observe n failure))
                                ;; print notes (a render the agent's value selected
                                ;; failed) already ride the entry's :diagnostics, delivered
                                ;; to the agent; they are not core faults
                                print-failure (conj (diagnostic :show n print-failure))))
                      (assoc :seon.batch/form-writes [] :seon.batch/form-world world)
                      (clock :evaluate t0))]
    (guard published :observe
           (fn [b]
             (let [{good true bad false} (group-by write? (vec writes))]
               (-> b
                   (cond-> (seq bad)
                     (update :seon.batch/diagnostics conj
                             {:seon.error/message (str "observe: " (count bad) " observed writes are not writes and were dropped")
                              :seon.batch/stage :observe :seon.batch/ordinal n
                              :seon.error/offending (mapv #(when-not (= ::nil-write %) %) bad)}))
                   (with-writes (mapv #(assoc % :seon.batch/ordinal n) good))
                   (assoc :seon.batch/form-writes (vec good))))))))

(defn- publish-step
  "The entrant's last word on the published entry, in the form's world:
  `(publish request batch entry)` answers `{:seon.batch/entry e
  :seon.batch/stop? b}`. It may replace the entry (a refused disposition keeps
  the refused value beside its refusal) and ends reading when it answers stop.
  A throw or an answer that is not an entry of the same ordinal is a
  diagnostic; the published entry stands."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/folding] :seon.batch/folding]}
  [request batch]
  (let [entry (peek (:seon.batch/entries batch))
        n (:seon.batch/ordinal entry)
        answer (within request (:seon.batch/form-world batch)
                 #((:seon.batch/publish request) (assoc request :seon.batch/working (:seon.batch/working batch))
                                                 batch entry))
        e (:seon.batch/entry answer)]
    (if (and (map? e) (= n (:seon.batch/ordinal e))
             (not= (contains? e :seon.batch/value) (contains? e :seon.batch/error)))
      (cond-> (last-entry batch (constantly e))
        (:seon.batch/stop? answer) (assoc :seon.batch/stop true))
      (update batch :seon.batch/diagnostics conj
              {:seon.error/message "publish: the stage answered no entry of this form; the published entry stands"
               :seon.batch/stage :publish :seon.batch/ordinal n}))))

(defn- freeze-step
  "The form's evidence, frozen after its own work (evaluation, print,
  publication): its reads, the definitions it executed, and whether work it
  owns is still in flight. In flight is :pending, never complete: the value is
  published and nothing waits for the future. The form's counts join the
  batch world's sink."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/folding] :seon.batch/folding]}
  [request batch]
  (let [{s :seon.batch/form-sink reads :seon.batch/reads ^java.util.concurrent.atomic.AtomicLong owned :seon.batch/in-flight}
        (:seon.batch/form-world batch)
        in-flight (.get owned)
        executed-now (executed s)]
    (when-let [^java.util.concurrent.ConcurrentHashMap batch-sink (:seon.batch/sink (:seon.batch/world request))]
      (doseq [[k ^java.util.concurrent.atomic.AtomicLongArray form-counter] s
              :let [^java.util.concurrent.atomic.AtomicLongArray c (.computeIfAbsent batch-sink k new-adder)]]
        (.addAndGet c 0 (.get form-counter 0))
        (.addAndGet c 1 (.get form-counter 1))
        (when (< 2 (.length form-counter)) (.addAndGet c 2 (.get form-counter 2)))))
    (last-entry batch
                #(cond-> (assoc % :seon.batch/reads @reads :seon.batch/read-sink reads :seon.batch/executed executed-now
                                :seon.batch/call-profile (call-profile s)
                                :seon.batch/evidence (if (pos? in-flight) :pending :complete)
                                :seon.batch/owned owned)
                   (pos? in-flight) (assoc :seon.batch/in-flight in-flight)))))

(defn- place-step
  "The namespace the published entry ran in and ended in."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/folding] :seon.batch/folding]}
  [request batch]
  (let [ns-after ((:seon.batch/ns-name request) request)]
    (last-entry batch #(assoc % :seon.batch/ns-before (:seon.batch/ns-before batch)
                              :seon.batch/ns-after ns-after))))

(defn- defined-by
  "`defined` with the definitions `change` adds, and without those it removes:
  the request's own definitions so far, which a later form's call reads from
  its world (`my.test/check` answers that their tests run after recording)."
  {:malli/schema [:=> [:cat [:maybe :seon.batch/defined] :seon.batch/change] :seon.batch/defined]}
  [defined {entities :seon.batch/entities removals :seon.batch/removals}]
  (as-> (or defined #{}) d
    (into d (keep #(or (:seon.fn/sym %) (:seon.test/sym %))) entities)
    (reduce (fn [d s] (if (qualified-symbol? s)
                        (disj d s)
                        (into #{} (remove #(= (str s) (namespace %))) d)))
            d removals)))

(defn- apply-pending
  "The last derived change, applied: validated onto the working value through
  the apply stage when another form is about to read it (the working value
  then holds it; a refusal is that form's facts error, its facts dropped), or,
  when no form follows, folded as it is: the one settlement validates it at
  the writer, and a refusal there is answered form by form. A change is
  validated here only when a later form can read it."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/folding :boolean] :seon.batch/folding]}
  [request batch validate?]
  (if-let [{n :seon.batch/ordinal tx :seon.batch/tx change :seon.batch/change} (:seon.batch/pending batch)]
    (let [t0 (System/nanoTime)
          working (:seon.batch/working batch)
          {after :seon.batch/working refusal :seon.batch/refusal}
          (if validate? ((:seon.batch/apply request) request working tx change) {:seon.batch/working working})
          ;; the pending change is the last published entry's: derive-step
          ;; leaves it after that entry, and it is applied before another
          mark (fn [b f] (if (= n (:seon.batch/ordinal (peek (:seon.batch/entries b))))
                           (last-entry b f)
                           (throw (ex-info "The pending change is not the last published entry's."
                                           {:seon.batch/ordinal n}))))]
      (-> batch
          (dissoc :seon.batch/pending)
          (cond-> (not refusal)
            (-> (assoc :seon.batch/working after)
                (update :seon.batch/program #((:seon.batch/fold request) request % change))
                (update :seon.batch/defined defined-by change)
                (mark #(assoc % :seon.batch/change change)))
            refusal (mark #(assoc % :seon.batch/facts-error refusal)))
          (clock :apply t0)))
    batch))

(defn- derive-step
  "The form's program change from its observed writes, and its facts on the
  working value, pending until the next form reads them (`apply-pending`)."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/folding] :seon.batch/folding]}
  [request batch]
  (if-let [refused (:seon.batch/facts-refused (peek (:seon.batch/entries batch)))]
    ;; an execution the entrant does not trust (the loaded program changed
    ;; while it ran) keeps its value and proposes no facts
    (last-entry batch #(assoc % :seon.batch/facts-error refused))
  ;; A refusal while deriving the form's change or its facts (a removal
  ;; whose referrers survive) is that FORM's error value, delivered with it;
  ;; it never escapes to the fold's guard as a core fault.
  (try
  (let [t0 (System/nanoTime)
        entry (peek (:seon.batch/entries batch))
        {change :seon.batch/change state :seon.batch/derive-state}
        ((:seon.batch/derive request) request (:seon.batch/derive-state batch)
         (:seon.batch/working batch) entry (:seon.batch/form-writes batch))
        batch (-> batch (assoc :seon.batch/derive-state state) (clock :derive t0))]
    (if-not (some seq (vals (select-keys change [:seon.batch/entities :seon.batch/removals
                                                  :seon.batch/schema-keys :seon.batch/namespaces])))
      (last-entry batch #(merge % (select-keys change [:seon.batch/notes :seon.batch/private])))
      (let [t1 (System/nanoTime)
            tx ((:seon.batch/facts request) request (:seon.batch/working batch) change)]
        (if (:seon.error/at tx)
          ;; the facts stage's refusal (a removal whose referrers survive) is
          ;; this form's error value, delivered with it
          (last-entry (clock batch :facts t1) #(assoc % :seon.batch/facts-error tx))
        (-> batch
            (assoc :seon.batch/pending {:seon.batch/ordinal (:seon.batch/ordinal entry) :seon.batch/tx tx
                                        :seon.batch/change change})
            (last-entry #(merge % (select-keys change [:seon.batch/notes :seon.batch/private])))
            (clock :facts t1))))))
  (catch Exception t
    (let [refusal (ex-data t)]
      (last-entry batch #(assoc % :seon.batch/facts-error
                                (if (and (string? (:seon.error/message refusal)) (:seon.error/at refusal))
                                  refusal
                                  (refusal/diagnostic
                                   (Date.) :seon.batch/derive `derive-step
                                   {:seon.error/message (str "derive: " (or (ex-message t) (.getName (class t))))
                                    :seon.error/throwable t
                                    :seon.batch/ordinal (:seon.batch/ordinal %)})))))))))

(defn- form-step
  "Read, evaluate and record one form. Answers the batch with :seon.batch/done
  when reading ended."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/folding] :seon.batch/folding]}
  [request batch]
  (let [batch (-> batch
                  (dissoc :seon.batch/step :seon.batch/form-writes)
                  (guard :ns-name #(assoc % :seon.batch/ns-before ((:seon.batch/ns-name request) request)))
                  (guard :read #(read-step request %)))
        r (:seon.batch/step batch)
        ends? (or (nil? r) (:seon.batch/eof r))
        ;; the previous form's facts: validated when this form will read them,
        ;; folded for the writer when no form follows
        batch (guard batch :apply #(apply-pending request % (not ends?)))]
    (if ends?
      (cond-> (assoc batch :seon.batch/done true)
        (nil? r) (assoc :seon.batch/unread {:seon.batch/unread-from (get-in batch [:seon.batch/read-state :seon.batch/version :seon.batch/pos] 0)
                                            :seon.batch/reason :read-stage-failed}))
      (let [b (-> (evaluate-step request batch)
                  (guard :place #(place-step request %))
                  (cond-> (:seon.batch/publish request) (guard :publish #(publish-step request %)))
                  (guard :freeze #(freeze-step request %))
                  (cond-> (:seon.batch/derive request) (guard :derive #(derive-step request %)))
                  (dissoc :seon.batch/form-world)
                  (update :seon.batch/ordinal inc))]
        ;; a publication that stops reading ends the batch after this form
        (cond-> b
          (:seon.batch/stop b) (-> (dissoc :seon.batch/stop) (assoc :seon.batch/done true)
                                   (guard :apply #(apply-pending request % false))))))))

;;; ---------------------------------------------------------------------------
;;; After the loop
;;; ---------------------------------------------------------------------------

(defn- compiled-function?
  "True when `producer` is a Var the host JVM compiled, not a definition
  only an agent's SCI world holds."
  {:malli/schema [:=> [:cat :qualified-symbol] :boolean]}
  [producer]
  (some? (some-> (find-ns (symbol (namespace producer)))
                 (.findInternedVar (symbol (name producer))))))

(defn- produced-failures
  "A diagnostic for each form whose failure a compiled definition produced:
  its error value (`:seon.batch/error-value`) names `:seon.error/producer`,
  the blame rule stated where the value was built, and that producer is a
  host function this batch has not redefined by that entry. A failure an
  agent's own form or definition produced returns to the agent and is no
  fault."
  {:malli/schema [:=> [:cat :seon.batch/failure-source] [:vector :seon.batch/diagnostic]]}
  [batch]
  (second
   (reduce
    (fn [[defined diagnostics] {failure :seon.batch/error ordinal :seon.batch/ordinal change :seon.batch/change}]
      (let [defined (into defined (keep :seon.fn/sym) (:seon.batch/entities change))
            error (:seon.batch/error-value (ex-data failure))
            producer (:seon.error/producer error)]
        [defined
         (cond-> diagnostics
           (and (qualified-symbol? producer) (not (defined producer)) (compiled-function? producer))
           (conj (diagnostic :evaluate ordinal
                             (ex-info (str (:seon.error/message error "The evaluation failed.")) error))))]))
    [#{} []] (:seon.batch/entries batch))))

(defn- record-faults
  "Each diagnostic, and each form's failure a definition produced
  ([[produced-failures]]), recorded through the fault stage. Its own failure
  is one more diagnostic, never recorded again."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/folding] :seon.batch/folding]}
  [request batch]
  (if-let [fault (:seon.batch/fault request)]
    (reduce (fn [b d]
              (try (update b :seon.batch/faults (fnil conj []) (fault request d))
                   (catch Throwable t (update b :seon.batch/diagnostics conj (diagnostic :fault nil t)))))
            batch (into (vec (:seon.batch/diagnostics batch)) (produced-failures batch)))
    batch))

(defn- settled-into
  "The published batch with a settlement's answer merged in: the program and
  working value it settled and, per held ordinal, only a facts error (that
  form's change dropped, its value kept). A held entry is never replaced or
  dropped; an update naming an ordinal the batch never published is a
  diagnostic."
  {:malli/schema [:=> [:cat :seon.batch/batch [:maybe :seon.batch/settled]] :seon.batch/batch]}
  [b {entries :seon.batch/entries :as settled}]
  (let [by (into {} (map (juxt :seon.batch/ordinal identity)) entries)
        held (into #{} (map :seon.batch/ordinal) (:seon.batch/entries b))
        stray (vec (remove held (keys by)))]
    (-> b
        (merge (select-keys settled [:seon.batch/program :seon.batch/working]))
        (update :seon.batch/entries
                (partial mapv (fn [e] (if-some [fe (:seon.batch/facts-error (get by (:seon.batch/ordinal e)))]
                                        (-> e (dissoc :seon.batch/change) (assoc :seon.batch/facts-error fe))
                                        e))))
        (cond-> (seq stray)
          (update :seon.batch/diagnostics conj
                  {:seon.error/message (str "settle: the settlement named " (count stray)
                                            " ordinals the batch never published")
                   :seon.batch/stage :settle :seon.error/data {:seon.batch/ordinals stray}})))))

(defn evaluate-batch
  "Read, evaluate and record every form of the request's text, in order.

  Answers the batch: every entry (value or error, shown text), the ordered
  writes, the program changes, the commit, diagnostics and timings.

  Total: a value is kept once published, whatever later stage fails.

  ## Costs

  - One contracted defn: ~40 ms warm; see the one-path spec's timing table
    (default, 2026-09-27, prototype, unarmed)."
  {:malli/schema [:=> [:cat :seon.batch/request] :seon.batch/batch]}
  [request]
  (let [t0 (System/nanoTime)
        basis (:seon.batch/basis request)
        ;; a read state the stage cannot build is a diagnostic: reading then
        ;; ends at once with nothing read
        [read-state failure] (try [((:seon.batch/read-state request) (:seon.batch/text request))]
                                  (catch Throwable t [nil (diagnostic :read nil t)]))
        start {:seon.batch/entries [] :seon.batch/writes [] :seon.batch/diagnostics (if failure [failure] [])
               :seon.batch/ordinal (:seon.batch/first-ordinal request 0) :seon.batch/working basis :seon.batch/basis basis
               :seon.batch/program {}
               :seon.batch/derive-state (:seon.batch/derive-state request {})
               :seon.batch/read-state read-state
               :seon.batch/timings {}}
        folded (let [result (volatile! start)
                     body (fn []
                            (loop [batch start]
                              (vreset! result batch)
                              (let [next-batch (guard batch :fold #(form-step request %))]
                                (if (or (:seon.batch/done next-batch)
                                        ;; a fold step that failed made no progress: end
                                        (= (:seon.batch/ordinal next-batch) (:seon.batch/ordinal batch)))
                                  (do (vreset! result next-batch) next-batch)
                                  (recur next-batch)))))]
                 ;; The around stage (namespace binding, registration delta) can
                 ;; throw after the loop: the last published batch survives it.
                 (try (if-let [around (:seon.batch/around request)] (around request body) (body))
                      (catch Throwable t
                        (update @result :seon.batch/diagnostics conj (diagnostic :around nil t)))))
        batch (-> folded
                  ;; a change still pending when reading ended some other way
                  (guard :apply #(apply-pending request % false))
                  (dissoc :seon.batch/step :seon.batch/form-writes :seon.batch/done)
                  (assoc :seon.batch/text (get-in folded [:seon.batch/read-state :seon.batch/version :seon.batch/text]
                                                  (:seon.batch/text request)))
                  (cond-> (:seon.batch/settle request)
                    (guard :settle #(let [t1 (System/nanoTime)
                                          commit ((:seon.batch/settle request) request %)]
                                      (clock (cond-> (-> %
                                                             ;; the settlement answers the state it settled; it
                                                             ;; never replaces or drops a published entry
                                                             (settled-into (:seon.batch/settled commit))
                                                             (assoc :seon.batch/commit (dissoc commit :seon.batch/settled)))
                                               ;; a measuring failure stays beside the values and reaches the fault stage
                                               (:seon.batch/measurement-error commit)
                                               (update :seon.batch/diagnostics conj
                                                       {:seon.error/message (str "settle: measurements not recorded: "
                                                                                 (:seon.error/message (:seon.batch/measurement-error commit)))
                                                        :seon.batch/stage :settle
                                                        :seon.error/data (:seon.batch/measurement-error commit)})
                                               ;; a refusal or storage failure stays beside the values and
                                               ;; reaches the fault stage
                                               (or (:seon.batch/writer-refusal commit) (:seon.batch/storage-error commit))
                                               (update :seon.batch/diagnostics conj
                                                       (cond-> {:seon.error/message (str "settle: the recording was not committed: "
                                                                                         (or (:seon.error/message (:seon.batch/writer-refusal commit))
                                                                                             (:seon.batch/storage-error commit)))
                                                                :seon.batch/stage :settle}
                                                         (:seon.batch/writer-refusal commit)
                                                         (assoc :seon.error/data (:seon.batch/writer-refusal commit)))))
                                             :settle t1)))))
        batch (guard batch :fault #(record-faults request %))]
    (assoc batch :seon.batch/total-ms (ms-since t0))))
