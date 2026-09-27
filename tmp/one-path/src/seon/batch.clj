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
    :seon.batch/conclude   feedback queried from the validated working value
                           (:seon.eval/feedback, the contract note or warning)
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
                (update :seon.batch/read-state assoc
                        :seon.batch/version (:seon.batch/version r)
                        :seon.batch/repairs (:seon.batch/repairs r)
                        :seon.batch/last-code-end end)
                (clock :read t0))
      (not in-bounds?)
      (-> (update :seon.batch/diagnostics conj
                  {:seon.error/message (str "read: the read stage answered span [" start ", " end
                                            ") outside its text of " (count text) " characters")
                   :seon.batch/stage :read :seon.batch/ordinal (:seon.batch/ordinal batch)})
          (assoc-in [:seon.batch/step :seon.batch/span-invalid?] true)))))

(defn- evaluate-step
  "Evaluate the read form once and publish its outcome at once. Never throws."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.batch/batch]}
  [request batch]
  (let [r (:seon.batch/step batch)
        n (:seon.batch/ordinal batch)
        t0 (System/nanoTime)
        writes (volatile! [])
        returned (volatile! ::none)
        run (fn []
              (vreset! returned ((:seon.batch/evaluate request) request (:seon.batch/form r) (:seon.batch/working batch))))
        [outcome failure]
        (try
          (if (:seon.batch/refused r)
            [{:seon.batch/error (ex-info "Only the REPL writes responses: this map was not evaluated."
                                         {:seon.batch/refused (:seon.batch/refused r)})}]
            [{:seon.batch/value (if-let [observe (:seon.batch/observe request)]
                                  (observe request (:seon.batch/working batch) #(vswap! writes conj %) run)
                                  (run))}])
          (catch Throwable t
            ;; the observer's own failure after the form returned keeps the value
            (if (identical? ::none @returned)
              [{:seon.batch/error t}]
              [{:seon.batch/value @returned} t])))]
    (-> batch
        (update :seon.batch/entries conj
                (assoc outcome :seon.batch/ordinal n
                       :seon.batch/duration-ms (ms-since t0)))
        (cond-> failure (update :seon.batch/diagnostics conj (diagnostic :observe n failure)))
        (update :seon.batch/writes into (map #(assoc % :seon.batch/ordinal n)) @writes)
        (assoc :seon.batch/form-writes @writes)
        (clock :evaluate t0))))

(defn- place-step
  "Where the published entry came from: its source, position and namespaces."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.batch/batch]}
  [request batch]
  (let [r (:seon.batch/step batch)
        text (:seon.batch/text (:seon.batch/version r))
        ns-after ((:seon.batch/ns-name request) request)]
    (last-entry batch
                (fn [e]
                  (cond-> (assoc e :seon.batch/ns-before (:seon.batch/ns-before batch)
                                 :seon.batch/ns-after ns-after)
                    ;; a span the read stage misreported: the form's own print is its source
                    (:seon.batch/span-invalid? r)
                    (assoc :seon.batch/source (binding [*print-length* 100 *print-level* 10]
                                                (pr-str (:seon.batch/form r))))
                    (not (:seon.batch/span-invalid? r))
                    (merge (let [[l c] (line-column text (:seon.batch/start r))]
                             {:seon.batch/source (str/trimr (subs text (:seon.batch/start r) (:seon.batch/end r)))
                              :seon.batch/line l :seon.batch/column c})))))))

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
            (last-entry #(cond-> (merge % (select-keys change [:seon.batch/notes :seon.batch/private
                                                               :seon.batch/uncontracted]))
                           refusal (assoc :seon.batch/facts-error refusal)
                           (not refusal) (assoc :seon.batch/change change)))
            (clock :apply t1))))))

(defn- show-last
  "The last entry's shown text, printed once; a failed print is a diagnostic
  and a minimal text naming the value's class."
  {:malli/schema [:=> [:cat :seon.batch/batch :seon.batch/request] :seon.batch/batch]}
  [batch request]
  (let [t0 (System/nanoTime)
        e (peek (:seon.batch/entries batch))]
    (-> (try (last-entry batch #(assoc % :seon.batch/shown ((:seon.batch/show request) request %)))
             (catch Throwable t
               (-> batch
                   (update :seon.batch/diagnostics conj (diagnostic :show (:seon.batch/ordinal e) t))
                   (last-entry #(assoc % :seon.batch/shown
                                       (str "#<unprintable "
                                            (if (contains? e :seon.batch/value)
                                              (.getName (class (:seon.batch/value e)))
                                              "error")
                                            ">"))))))
        (clock :show t0))))

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
          ;; printed at once, as a REPL prints: a lazy value is realized before
          ;; the next form runs (seon.sci.eval prints inside the arm, 9e61340b2)
          (cond-> (:seon.batch/show request) (show-last request))
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
        basis (:seon.batch/database request)
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
                  ;; feedback is queried from the validated working value, so its shown
                  ;; text can ride the one settlement
                  (cond-> (:seon.batch/conclude request)
                    (guard :conclude #(merge % ((:seon.batch/conclude request) request %))))
                  (cond-> (:seon.batch/settle request)
                    (guard :settle #(let [t1 (System/nanoTime)
                                          commit ((:seon.batch/settle request) request %)]
                                      ;; a settlement that re-derived facts on a moved head
                                      ;; answers the entries with their new facts errors
                                      (clock (cond-> (assoc % :seon.batch/commit (dissoc commit :seon.batch/rebased-entries))
                                               (:seon.batch/rebased-entries commit)
                                               (assoc :seon.batch/entries (:seon.batch/rebased-entries commit)))
                                             :settle t1)))))
        batch (guard batch :fault #(record-faults request %))]
    (assoc batch :seon.batch/total-ms (ms-since t0))))
