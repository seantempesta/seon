(ns interest
  "The interest-registration mechanism, ADAPTED from the old system.

  NOT PRODUCTION CODE. This is the falsification prototype for plan
  ruling 21's first target (wake-router render-interest delivery). It
  does NOT invent a mechanism: every function below is the old system's
  function, translated from the pod<->writer wire protocol to an
  in-process per-agent renders proc.

  KEPT VERBATIM IN SHAPE (source: src-old/seon/db/writer.clj):
  - `interest-attributes`            <- writer.clj:2774-2780
  - `add-interest` / `remove-interest` (the reverse candidate index
    `{::all #{ref} ::by-attribute {attr #{ref}}}`)
                                     <- writer.clj:2782-2810
  - `candidate-interests`            <- writer.clj:3174-3190
  - `datom-matches-pattern?` (exact E/A/V/added? matching)
                                     <- writer.clj:2984-3002
  - `matching-datoms`                <- writer.clj:3004-3014
  - `merge-read-dependencies`        <- writer.clj:2847-2850
  - dependency plans -> attributes via `d/dependency-plan-attributes`
                                     <- writer.clj:2864-2899 (listen-interest)
  - the read-evidence capture seam   <- src-old/seon/db.cljc:320-348
  - `:all` as the fail-open default  <- src-old/seon/reactive.cljc:141-157
                                        (result-envelope) and
                                        src-old/seon/web/feed.clj:145-153

  DELIBERATELY DROPPED, with reasons:
  - the `[transport-connection request-id owner]` reference triple and
    `current-interest`'s owner check: that identity existed because the
    interest lived across a wire and a reconnect could resurrect a
    stale request id. In-process the reference is
    `[agent-id registration-name]` and the holder is the agent's own
    proc state, which dies with the proc.
  - `::by-scope` (database-name + connection-id + branch): one cluster
    is one store and one branch by the standing law, so there is
    exactly one scope; a second scope key would be dead structure.
  - `reactive.cljc`'s `::pending-db` / `::dirty-at` / `settle-delay` /
    `arm!` scheduler: that is a coalescing queue rebuilt by hand. A
    `(sliding-buffer 1)` in-port plus one proc pass IS that mechanism,
    already owned by flow.
  - the explicit `::patterns` API surface as an agent-facing feature:
    the matcher is kept (it is three lines and it is what makes the
    index exact), but nothing in the render path authors patterns today
    -- plans are the automatic path, exactly as the old system had it."
  (:require [datahike.api :as d]))

;;; ---------------------------------------------------------------------------
;;; Dependency plans -> interest attributes  (writer.clj:2847-2899)
;;; ---------------------------------------------------------------------------

(defn merge-read-dependencies
  "writer.clj:2847-2850, verbatim."
  [left right]
  (if (or (= :all left) (= :all right))
    :all
    (into (or left #{}) right)))

(defn evidence-dependencies
  "writer.clj:2864-2879, minus the branch assertion (one branch here).
  Reduces captured Datahike dependency plans to dependency ATTRIBUTES."
  [read-evidence]
  (if (= :all read-evidence)
    :all
    (reduce
     (fn [dependencies evidence]
       (merge-read-dependencies
        dependencies
        (d/dependency-plan-attributes
         (:datahike.read/dependency-plan evidence)
         (:seon.db/source-argument-position evidence 0))))
     #{}
     read-evidence)))

;;; ---------------------------------------------------------------------------
;;; The read-evidence capture seam  (src-old/seon/db.cljc:320-348)
;;; ---------------------------------------------------------------------------
;;;
;;; The old system captured the plan the READ ITSELF returned; it never
;;; reconstructed (e,a) pairs from returned rows. The fresh tree has no
;;; `seon.db` facade, so this prototype re-creates the seam by binding
;;; the same three read owners. `d/entity` is open-ended access, so it
;;; widens to `:all` -- the old feed's documented coarse case.

(def ^:dynamic *evidence* nil)

(defn record! [entry]
  (when *evidence* (swap! *evidence* conj entry)))

(defn widen! []
  (when *evidence* (reset! *evidence* :all)))

(defn record-plan! [plan]
  (if (= :all plan)
    (widen!)
    (when (and *evidence* (not= :all @*evidence*))
      (swap! *evidence* conj
             {:datahike.read/dependency-plan plan
              :seon.db/source-argument-position 0}))))

(defmacro with-read-evidence
  "Run `body`, returning [value evidence]. The old `db/with-read-evidence`
  (src-old/seon/web/datastar.cljs:395-446 calls it around a render)."
  [& body]
  `(let [collector# (atom [])
         value# (binding [*evidence* collector#]
                  (with-redefs
                    [d/q (let [original# d/q]
                           (fn [& args#]
                             (let [query# (first args#)]
                               (try (record-plan!
                                     (d/query-dependency-plan query#))
                                    (catch Throwable _# (widen!))))
                             (apply original# args#)))
                     d/pull (let [original# d/pull]
                              (fn [db# pattern# eid# & more#]
                                (try (record-plan!
                                      (d/pull-dependency-plan pattern# [eid#]))
                                     (catch Throwable _# (widen!)))
                                (apply original# db# pattern# eid# more#)))
                     d/entity (let [original# d/entity]
                                (fn [& args#]
                                  ;; open-ended attribute access: widen
                                  (widen!)
                                  (apply original# args#)))]
                    ~@body))]
     [value# (let [held# @collector#]
               (if (= :all held#) :all held#))]))

;;; ---------------------------------------------------------------------------
;;; The reverse candidate index  (writer.clj:2782-2810, 3174-3190)
;;; ---------------------------------------------------------------------------

(defn interest-attributes
  "writer.clj:2774-2780, verbatim."
  [interest]
  (if (= :all (::dependencies interest))
    :all
    (or (::dependencies interest)
        (into #{} (map :seon.db/a) (::patterns interest)))))

(defn empty-index [] {::all #{} ::by-attribute {}})

(defn add-interest
  "writer.clj:2782-2795, with the reference simplified (see ns doc)."
  [entry reference interest]
  (let [attributes (interest-attributes interest)]
    (cond-> (update entry ::interest-count (fnil inc 0))
      (= :all attributes)
      (update ::all (fnil conj #{}) reference)

      (set? attributes)
      (update ::by-attribute
              (fn [by-attribute]
                (reduce (fn [index attribute]
                          (update index attribute (fnil conj #{}) reference))
                        (or by-attribute {}) attributes))))))

(defn remove-interest
  "writer.clj:2797-2810, verbatim in shape."
  [entry reference interest]
  (let [attributes (interest-attributes interest)]
    (cond-> (update entry ::interest-count dec)
      (= :all attributes)
      (update ::all disj reference)

      (set? attributes)
      (update ::by-attribute
              (fn [by-attribute]
                (reduce
                 (fn [index attribute]
                   (let [remaining (disj (get index attribute #{}) reference)]
                     (if (seq remaining)
                       (assoc index attribute remaining)
                       (dissoc index attribute))))
                 by-attribute attributes))))))

(defn candidate-interests
  "writer.clj:3174-3190: the union of `::all` and every attribute bucket
  the report's datoms touch. One index lookup per committed datom."
  [entry datoms]
  (into (::all entry)
        (mapcat #(get (::by-attribute entry) (nth % 1) #{}))
        datoms))

;;; ---------------------------------------------------------------------------
;;; Exact E/A/V matching  (writer.clj:2984-3014)
;;; ---------------------------------------------------------------------------
;;;
;;; Translated from the wire's datom MAP (`:seon.db/e` ...) to a raw
;;; Datahike datom, which is what a fresh `listen!` report carries.

(defn datom-matches-pattern?
  "writer.clj:2984-2996, on raw datoms."
  [datom pattern]
  (and (= (nth datom 1) (:seon.db/a pattern))
       (or (not (contains? pattern :seon.db/e))
           (= (nth datom 0) (:seon.db/e pattern)))
       (or (not (contains? pattern :seon.db/v))
           (= (nth datom 2) (:seon.db/v pattern)))
       (or (not (contains? pattern :seon.db/added?))
           (= (nth datom 4) (:seon.db/added? pattern)))))

(defn matching-datoms
  "writer.clj:3004-3014, on raw datoms."
  [interest datoms]
  (cond
    (= :all (::dependencies interest)) datoms
    (set? (::dependencies interest))
    (filterv #(contains? (::dependencies interest) (nth % 1)) datoms)
    :else
    (filterv (fn [datom]
               (some #(datom-matches-pattern? datom %) (::patterns interest)))
             datoms)))

;;; ---------------------------------------------------------------------------
;;; The registry the wake router reads
;;; ---------------------------------------------------------------------------

(defn registry
  "Process-local derived memory: the index plus the interests it points
  at, plus each reference's delivery channel. Losable; rebuilt by one
  render pass per agent after any restart."
  []
  (atom {::index (empty-index) ::interests {} ::channels {}}))

(defn install!
  "Install one agent's render interest. `dependencies` is a set of
  attributes or `:all`. Re-installing the SAME dependency signature is a
  no-op (reactive.cljc:120-128's `evidence-signature` rule)."
  [registry reference dependencies channel]
  (swap!
   registry
   (fn [state]
     (let [interest {::dependencies dependencies}
           held (get-in state [::interests reference])]
       (if (= (::dependencies held) dependencies)
         (assoc-in state [::channels reference] channel)
         (-> state
             (update ::index
                     (fn [index]
                       (cond-> index
                         held (remove-interest reference held)
                         true (add-interest reference interest))))
             (assoc-in [::interests reference] interest)
             (assoc-in [::channels reference] channel)))))))

(defn uninstall! [registry reference]
  (swap! registry
         (fn [state]
           (if-let [held (get-in state [::interests reference])]
             (-> state
                 (update ::index remove-interest reference held)
                 (update ::interests dissoc reference)
                 (update ::channels dissoc reference))
             state))))

(defn interested
  "The references whose interest actually matches this report's datoms.
  Two stages, exactly as the old writer: the reverse index narrows to
  CANDIDATES, then per-interest matching confirms."
  [registry datoms]
  (let [{::keys [index interests]} @registry]
    (into []
          (keep (fn [reference]
                  (let [interest (get interests reference)]
                    (when (and interest
                               (seq (matching-datoms interest datoms)))
                      reference))))
          (candidate-interests index datoms))))
