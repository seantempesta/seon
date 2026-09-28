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
  (:require [clojure.string :as str]))

(def ^:dynamic *world*
  "The world of the evaluation running on this thread: the entrant's world
  value with its database the batch's working value. The fold binds it around
  each evaluation and its print; `seon.turn.batch/with-world` binds it again
  after a thread hop. It is the one source a called function's omitted
  world-member inputs are supplied from."
  nil)

(def world-members
  "Each world member by the schema name a function declares it under: an
  omitted input declared under one of these names is supplied from the
  calling evaluation's world, required or optional. A function whose meaning
  needs the member absent declares another name."
  {:seon.db/database-value :seon.db/db
   :seon.db/connection :seon.db/connection
   :seon.db/writer :seon.db/writer
   :seon.agent/id :seon.agent/id
   :seon.task/id :seon.task/id
   :seon.render/profile :seon.render/profile
   :seon.batch/sink :seon.batch/sink
   :seon.sci.eval/ctx :seon.sci.eval/ctx})

(defn sink?
  "True for a call sink."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason "A predicate answers for any value." :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [x]
  (instance? java.util.concurrent.ConcurrentHashMap x))

(defn sink
  "A new call sink: the per-world count of armed calls, by symbol. Concurrent
  calls from the world's evaluations and the futures they start add to it."
  {:malli/schema [:=> [:cat] :seon.batch/sink]}
  []
  (java.util.concurrent.ConcurrentHashMap.))

(defn calls
  "A sink's counts as a value: symbol -> calls."
  {:malli/schema [:=> [:cat :seon.batch/sink] [:map-of :qualified-symbol nat-int?]]}
  [^java.util.concurrent.ConcurrentHashMap s]
  (into {} (map (fn [[k ^java.util.concurrent.atomic.LongAdder a]] [k (.sum a)])) s))

(defn- ms-since
  {:malli/schema [:=> [:cat :int] :double]}
  [t0]
  (/ (- (System/nanoTime) t0) 1e6))

(defn diagnostic
  "One diagnostic: the failing stage, the form it belongs to, the cause."
  {:malli/schema [:=> [:cat :keyword [:maybe :int] :seon.error/throwable] :seon.batch/diagnostic]}
  [stage ordinal ^Throwable t]
  (cond-> {:seon.error/message (str (name stage) ": " (or (ex-message t) (.getName (class t))))
           :seon.batch/stage stage
           :seon.error/throwable-class (.getName (class t))}
    ordinal (assoc :seon.batch/ordinal ordinal)
    (ex-data t) (assoc :seon.error/data (ex-data t))))

(defn- guard
  "(f batch), or batch with one diagnostic naming `stage` when f throws."
  {:malli/schema [:=> [:cat :seon.batch/batch :keyword [:=> [:cat :seon.batch/batch] :seon.batch/batch]]
                  :seon.batch/batch]}
  [batch stage f]
  (try (f batch)
       (catch Throwable t
         (update batch :seon.batch/diagnostics conj
                 (diagnostic stage (:seon.batch/ordinal batch) t)))))

(defn- clock
  {:malli/schema [:=> [:cat :seon.batch/batch :keyword :int] :seon.batch/batch]}
  [batch k t0]
  (update-in batch [:seon.batch/timings k] (fnil + 0.0) (ms-since t0)))

(defn- last-entry
  {:malli/schema [:=> [:cat :seon.batch/batch [:=> [:cat :seon.batch/entry] :seon.batch/entry]] :seon.batch/batch]}
  [batch f]
  (update batch :seon.batch/entries (fn [es] (conj (pop es) (f (peek es))))))

(defn- line-column
  {:malli/schema [:=> [:cat :string nat-int?] [:tuple pos-int? pos-int?]]}
  [^String text start]
  (let [before (subs text 0 start)
        line (inc (count (filter #(= \newline %) before)))
        col (inc (- start (inc (.lastIndexOf before "\n"))))]
    [line col]))

;;; ---------------------------------------------------------------------------
;;; One form
;;; ---------------------------------------------------------------------------

(defn- read-step
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.batch/batch]}
  [request batch]
  (let [t0 (System/nanoTime)
        r ((:seon.batch/read-next request) request (:seon.batch/read-state batch))
        text (:seon.batch/text (:seon.batch/version r))
        {:seon.batch/keys [start end]} r
        in-bounds? (or (not (contains? r :seon.batch/form))
                       (and (nat-int? start) (nat-int? end) (<= start end (count text))))]
    (cond-> (-> batch
                (assoc :seon.batch/step r)
                (update :seon.batch/read-state #(cond-> (assoc % :seon.batch/version (:seon.batch/version r)
                                                               :seon.batch/repairs (:seon.batch/repairs r))
                                                   end (assoc :seon.batch/last-code-end end)))
                (clock :read t0))
      (not in-bounds?)
      (-> (update :seon.batch/diagnostics conj
                  {:seon.error/message (str "read: the read stage answered span [" start ", " end
                                            ") outside its text of " (count text) " characters")
                   :seon.batch/stage :read :seon.batch/ordinal (:seon.batch/ordinal batch)})
          (assoc-in [:seon.batch/step :seon.batch/span-invalid?] true)))))

(defn- source-of
  "Where the read form came from: its source text and position, known before
  it is evaluated."
  {:malli/schema [:=> [:cat :seon.batch/read-step] :map]}
  [r]
  (let [text (:seon.batch/text (:seon.batch/version r))]
    (if (:seon.batch/span-invalid? r)
      ;; a span the read stage misreported: the form's own print is its source
      {:seon.batch/source (binding [*print-length* 100 *print-level* 10] (pr-str (:seon.batch/form r)))}
      (let [[l c] (line-column text (:seon.batch/start r))]
        {:seon.batch/source (str/trimr (subs text (:seon.batch/start r) (:seon.batch/end r)))
         :seon.batch/line l :seon.batch/column c}))))

(defn- allocated-bytes
  "The calling thread's allocated bytes so far (HotSpot's ThreadMXBean)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (.getCurrentThreadAllocatedBytes
   ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)))

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
  "`entry` with its shown text from the show stage (its text, or its text and
  the printer's own diagnostics); a failed print is the minimal text plus the
  failure."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/entry]
                  [:tuple :seon.batch/entry [:maybe :seon.error/throwable] [:maybe :string]]]}
  [request entry]
  (try (let [p ((:seon.batch/show request) request entry)]
         (if (string? p)
           [(assoc entry :seon.batch/shown p) nil nil]
           [(assoc entry :seon.batch/shown (:seon.batch/shown p)) nil (:seon.batch/print-diagnostics p)]))
       (catch Throwable t [(assoc entry :seon.batch/shown (unprintable entry)) t nil])))

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

(defn- evaluate-step
  "Evaluate the read form once, and print its value, under one observation
  scope, then publish the outcome. Never throws.

  The observation's writes accumulate in a per-call concurrent queue (writes
  may come from futures the form starts); the scope completes when the
  observer stage returns, after the value was printed, so a definition made
  while realizing a lazy value is observed. A write offered after completion
  is refused to its writer. A write that is not a map is a diagnostic."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.batch/batch]}
  [request batch]
  (let [r (:seon.batch/step batch)
        n (:seon.batch/ordinal batch)
        t0 (System/nanoTime)
        base (merge {:seon.batch/ordinal n} (source-of r))
        ;; the world as this form sees it: its database is the working value
        world (assoc (:seon.batch/world request) :seon.db/db (:seon.batch/working batch))
        request (assoc request :seon.batch/world world)
        none (Object.)
        writes (java.util.concurrent.ConcurrentLinkedQueue.)
        open (java.util.concurrent.atomic.AtomicBoolean. true)
        on-write (fn [w]
                   (when-not (.get open)
                     (throw (ex-info "The evaluation that owned this observation has completed."
                                     {:seon.batch/ordinal n :seon.batch/write w})))
                   (.add writes (if (nil? w) ::nil-write w)))
        returned (volatile! none)
        printed (volatile! nil)
        bytes0 (allocated-bytes)
        measured (volatile! nil)
        measure! (fn [] (vreset! measured {:seon.batch/duration-ms (ms-since t0)
                                           :seon.batch/allocated-bytes (- (allocated-bytes) bytes0)}))
        ;; the entrant's world boundary (custody, read basis, *world*), around
        ;; every evaluation and print on this thread
        within* (fn [w f]
                  (if-let [boundary (:seon.batch/within request)]
                    (boundary w f)
                    (with-bindings (assoc (:seon.batch/scope w {}) #'*world* w) (f))))
        within (fn [f] (within* world f))
        run (fn run
              ([] (run {}))
              ([scope]
               ;; an observer's scope (its bindings) rides the world
               (within* (cond-> world (seq scope) (assoc :seon.batch/scope scope))
               (fn []
                 (let [v ((:seon.batch/evaluate request) request (:seon.batch/form r) (:seon.batch/working batch))]
                   (vreset! returned v)
                   (measure!)
                   (when (:seon.batch/show request)
                     (vreset! printed (print-into request (merge base @measured {:seon.batch/value v}))))
                   v)))))
        refused (:seon.batch/refused r)
        failure (try
                  (cond
                    refused nil
                    (:seon.batch/observe request)
                    ((:seon.batch/observe request) request (:seon.batch/working batch) on-write run)
                    :else
                    (run))
                  nil
                  (catch Throwable t t))
        _ (.set open false)
        _ (when-not @measured (measure!))
        base (merge base @measured)
        value? (not (identical? none @returned))
        entry (cond
                refused (assoc base :seon.batch/error (ex-info "Only the REPL writes responses: this map was not evaluated."
                                                               {:seon.batch/refused refused}))
                (and value? @printed) (first @printed)
                value? (assoc base :seon.batch/value @returned)
                :else (assoc base :seon.batch/error failure))
        [entry print-failure print-notes] (if (or (contains? entry :seon.batch/shown) (not (:seon.batch/show request)))
                                            [entry (second @printed) (nth @printed 2 nil)]
                                            (within #(print-into request entry)))
        ;; published before any observation is normalized: a malformed
        ;; observation is a diagnostic beside the value, never its loss
        published (-> batch
                      (update :seon.batch/entries conj (assoc entry :seon.batch/total-ms (ms-since t0)))
                      (update :seon.batch/diagnostics into
                              (cond-> []
                                (and value? failure) (conj (diagnostic :observe n failure))
                                print-failure (conj (diagnostic :show n print-failure))
                                print-notes (conj {:seon.error/message (str "show: " print-notes) :seon.batch/stage :show
                                                   :seon.batch/ordinal n})))
                      (assoc :seon.batch/form-writes [])
                      (clock :evaluate t0))]
    (guard published :observe
           (fn [b]
             (let [{good true bad false} (group-by write? (vec writes))]
               (-> b
                   (cond-> (seq bad)
                     (update :seon.batch/diagnostics conj
                             {:seon.error/message (str "observe: " (count bad) " observed writes are not writes and were dropped")
                              :seon.batch/stage :observe :seon.batch/ordinal n
                              :seon.error/data {:seon.batch/writes (vec (take 3 (map #(if (= ::nil-write %) nil (pr-str %)) bad)))}}))
                   (update :seon.batch/writes into (map #(assoc % :seon.batch/ordinal n)) good)
                   (assoc :seon.batch/form-writes (vec good))))))))

(defn- place-step
  "The namespace the published entry ran in and ended in."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.batch/batch]}
  [request batch]
  (let [ns-after ((:seon.batch/ns-name request) request)]
    (last-entry batch #(assoc % :seon.batch/ns-before (:seon.batch/ns-before batch)
                              :seon.batch/ns-after ns-after))))

(defn- derive-step
  "The form's program change from its observed writes, validated onto the
  working value. A refusal is the form's facts error; its facts are dropped."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.batch/batch]}
  [request batch]
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
            working (:seon.batch/working batch)
            tx ((:seon.batch/facts request) request working change)
            {after :seon.batch/working refusal :seon.batch/refusal}
            ((:seon.batch/apply request) request working tx change)]
        (-> batch
            (cond-> (not refusal)
              (-> (assoc :seon.batch/working after)
                  (update :seon.batch/program #((:seon.batch/fold request) request % change))))
            (last-entry #(cond-> (merge % (select-keys change [:seon.batch/notes :seon.batch/private]))
                           refusal (assoc :seon.batch/facts-error refusal)
                           (not refusal) (assoc :seon.batch/change change)))
            (clock :apply t1))))))

(defn- form-step
  "Read, evaluate and record one form. Answers the batch with :seon.batch/done
  when reading ended."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.batch/batch]}
  [request batch]
  (let [batch (-> batch
                  (dissoc :seon.batch/step :seon.batch/form-writes)
                  (guard :ns-name #(assoc % :seon.batch/ns-before ((:seon.batch/ns-name request) request)))
                  (guard :read #(read-step request %)))
        r (:seon.batch/step batch)]
    (if (or (nil? r) (:seon.batch/eof r) (contains? r :seon.batch/unread-from))
      (cond-> (assoc batch :seon.batch/done true)
        (contains? r :seon.batch/unread-from)
        (assoc :seon.batch/unread (select-keys r [:seon.batch/unread-from :seon.batch/reason]))
        (nil? r) (assoc :seon.batch/unread {:seon.batch/unread-from (get-in batch [:seon.batch/read-state :seon.batch/version :seon.batch/pos] 0)
                                            :seon.batch/reason :read-stage-failed}))
      (-> (evaluate-step request batch)
          (guard :place #(place-step request %))
          (cond-> (:seon.batch/derive request) (guard :derive #(derive-step request %)))
          (update :seon.batch/ordinal inc)))))

;;; ---------------------------------------------------------------------------
;;; After the loop
;;; ---------------------------------------------------------------------------

(defn- record-faults
  "Each diagnostic recorded through the fault stage. Its own failure is one
  more diagnostic, never recorded again."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.batch/batch]}
  [request batch]
  (if-let [fault (:seon.batch/fault request)]
    (reduce (fn [b d]
              (try (update b :seon.batch/faults (fnil conj []) (fault request d))
                   (catch Throwable t (update b :seon.batch/diagnostics conj (diagnostic :fault nil t)))))
            batch (:seon.batch/diagnostics batch))
    batch))

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
        basis (get-in request [:seon.batch/world :seon.db/db])
        start {:seon.batch/entries [] :seon.batch/writes [] :seon.batch/diagnostics []
               :seon.batch/ordinal 0 :seon.batch/working basis :seon.batch/basis basis
               :seon.batch/program {}
               :seon.batch/derive-state (:seon.batch/derive-state request {})
               :seon.batch/read-state ((:seon.batch/read-state request) (:seon.batch/text request))
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
                  (dissoc :seon.batch/step :seon.batch/form-writes :seon.batch/done)
                  (assoc :seon.batch/text (get-in folded [:seon.batch/read-state :seon.batch/version :seon.batch/text]
                                                  (:seon.batch/text request))
                         :seon.batch/repairs (get-in folded [:seon.batch/read-state :seon.batch/repairs] []))
                  (cond-> (:seon.batch/settle request)
                    (guard :settle #(let [t1 (System/nanoTime)
                                          commit ((:seon.batch/settle request) request %)]
                                      (clock (cond-> (-> %
                                                             ;; the settlement answers the state it settled
                                                             (merge (:seon.batch/settled commit))
                                                             (assoc :seon.batch/commit (dissoc commit :seon.batch/settled)))
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
