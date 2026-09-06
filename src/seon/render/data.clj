(ns seon.render.data
  "Bounded entity observations and `get-in` cursors for routed values.

  Entity observations retain actual datoms, references and snapshot identity.
  Nested cursors select the value named by a URL path. Presentation,
  admission, stable node ids, and navigation links
  belong to the one floor in `seon.render.value`; `/data` and per-agent debug
  routes both hand their selected value to that floor.

  Crash walk: pure. A kill loses only a cursor carried by the URL."
  (:require [clojure.edn :as edn]
            [seon.db :as db]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The cursor
;;; ---------------------------------------------------------------------------

(defn parse-cursor
  "Read a cursor from ordinary query parameters. Total.

  `path` is EDN so a key can be a keyword, a string, or an integer index
  without a second encoding to get wrong; anything unreadable is the
  root, because a broken link should show the top of the value rather
  than an error page. A negative or unreadable offset is zero for the
  same reason."
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string]]
                  :seon.render.data/cursor]}
  [path offset]
  {:seon.render.data/path
   (let [parsed (try (edn/read-string (or path "")) (catch Throwable _ nil))]
     (if (vector? parsed) parsed []))
   :seon.render.data/offset
   (max 0 (or (when offset (parse-long offset)) 0))})

(defn at
  "The value `cursor`'s path names, or a refusal naming where it broke.

  A path that leaves the value is a legible refusal rather than nil,
  because nil is also a legitimate value to have navigated to and the
  two must not look the same."
  {:malli/schema [:=> [:cat :any :seon.render.data/cursor]
                  [:or [:map [:seon.render.data/value :any]]
                   :seon.error/value]]}
  [value {:keys [:seon.render.data/path]}]
  (reduce (fn [found step]
            (let [inner (:seon.render.data/value found)
                  missing (Object.)
                  index-step? (and (sequential? inner) (int? step) (< -1 step))
                  indexed (when index-step?
                            (nth inner step missing))]
              (cond
                (and (map? inner) (contains? inner step))
                {:seon.render.data/value (get inner step)}

                (and index-step? (not (identical? missing indexed)))
                {:seon.render.data/value indexed}

                (and (set? inner) (contains? inner step))
                {:seon.render.data/value step}

                :else
                (reduced
                 {:seon.error/kind ::no-such-path
                  :seon.error/message (str "There is nothing at " (pr-str step)
                                           " in this value.")
                  :seon.error/data {:seon.render.data/step (pr-str step)} :seon.render.data/no-such-path true}))))
          {:seon.render.data/value value}
          path))

(defn pull-at
  "Pull one identified entity and return the value at `cursor`.

  A database refusal remains the refusal. A missing path remains the precise
  `at` refusal; nil is returned only when nil is the value at a present path."
  {:malli/schema
   [:=> [:cat :seon.db/pull-selector :seon.db/entity-id
         :seon.render.data/cursor]
    [:or :seon.render/value :seon.error/value]]}
  [selector entity-id cursor]
  (let [pulled (db/pull selector entity-id)]
    (if (:seon.error/kind pulled)
      pulled
      (let [selected (at pulled cursor)]
        (if (:seon.error/kind selected)
          selected
          (:seon.render.data/value selected))))))

(defn- observation-error [kind message]
  {:seon.error/kind kind :seon.error/message message})

(defn- continuation [snapshot eid direction offset cursor]
  (cond-> {::snapshot snapshot ::eid eid ::direction direction
           ::attribute-offset offset}
    cursor (assoc ::index-cursor cursor)))

(defn- matching-continuation? [cursor snapshot eid direction]
  (or (nil? cursor)
      (and (= snapshot (::snapshot cursor))
           (= eid (::eid cursor))
           (= direction (::direction cursor)))))

(defn- outgoing-page [database snapshot eid limit weight cursor]
  (let [page (db/index-page
              database
              (cond-> {:index :eavt :components [eid] :direction :forward
                       :limit limit :max-result-weight weight}
                cursor (assoc :cursor (::index-cursor cursor))))]
    (if (:seon.error/kind page)
      page
      (cond-> {::datoms (:datahike.index-page/datoms page)
               ::complete? (:datahike.index-page/complete? page)}
        (:datahike.index-page/cursor page)
        (assoc ::continuation
               (continuation snapshot eid :outgoing 0
                             (:datahike.index-page/cursor page)))))))

(defn- incoming-page
  [database snapshot eid attributes limit weight probe-limit cursor]
  (let [start (or (::attribute-offset cursor) 0)
        stop (min (count attributes) (+ start probe-limit))]
    (if (> start (count attributes))
      (observation-error ::invalid-continuation
                         "Incoming continuation exceeds the reference attributes.")
      (reduce
       (fn [result offset]
         (let [remaining (- limit (count (::datoms result)))
               page (db/index-page
                     database
                     (cond-> {:index :avet :components [(nth attributes offset) eid]
                              :direction :forward :limit remaining
                              :max-result-weight weight}
                       (and (= offset start) (::index-cursor cursor))
                       (assoc :cursor (::index-cursor cursor))))]
           (if (:seon.error/kind page)
             (reduced page)
             (let [rows (into (::datoms result) (:datahike.index-page/datoms page))
                   more? (not (:datahike.index-page/complete? page))
                   next-offset (if more? offset (inc offset))
                   complete? (and (not more?) (= next-offset (count attributes)))
                   next-result
                   (cond-> {::datoms rows ::complete? complete?
                            ::ref-attributes-probed (inc (::ref-attributes-probed result))}
                     (not complete?)
                     (assoc ::continuation
                            (continuation snapshot eid :incoming next-offset
                                          (:datahike.index-page/cursor page))))]
               (if (or more? (= limit (count rows)))
                 (reduced next-result)
                 next-result)))))
       (cond-> {::datoms [] ::complete? (= start (count attributes))
                ::ref-attributes-probed 0}
         (< start (count attributes))
         (assoc ::continuation (continuation snapshot eid :incoming start nil)))
       (range start stop)))))

(defn entity-observation
  "Bounded assertions and references for one entity at one database value."
  {:malli/schema [:=> [:catn [::request ::observation-request]]
                  [:or ::observation :seon.error/value]]}
  [{database :seon.db/db ::keys [subject limit max-result-weight
                                max-ref-attributes outgoing-cursor incoming-cursor]}]
  (let [snapshot (db/database-value-identity database)
        found (db/pull database [:db/id] subject)
        eid (:db/id found)]
    (cond
      (:seon.error/kind snapshot) snapshot
      (:seon.error/kind found) found
      (nil? eid) (observation-error ::missing-subject "The selected entity does not exist.")
      (not (and (matching-continuation? outgoing-cursor snapshot eid :outgoing)
                (matching-continuation? incoming-cursor snapshot eid :incoming)))
      (observation-error ::stale-continuation
                         "The continuation belongs to a different database value, entity, or direction.")
      :else
      (let [attributes (into [] (comp (filter (fn [[_ definition]]
                                               (= :db.type/ref (:db/valueType definition))))
                                     (map key))
                             (:schema database))
            attributes (vec (sort attributes))
            outgoing (outgoing-page database snapshot eid limit max-result-weight outgoing-cursor)
            incoming (incoming-page database snapshot eid attributes limit max-result-weight
                                    max-ref-attributes incoming-cursor)]
        {::subject subject ::eid eid ::snapshot snapshot
         ::outgoing outgoing ::incoming incoming
         ::identities (filterv (fn [datom]
                                (= :db.unique/identity
                                   (get-in database [:schema (:a datom) :db/unique])))
                              (::datoms outgoing []))
         ;; A continuation page can be complete for its remaining suffix while
         ;; the observation still omits identity datoms from earlier pages.
         ;; Only an uncontinued, complete outgoing page can certify the set.
         ::identities-complete? (and (nil? outgoing-cursor)
                                     (true? (::complete? outgoing)))
         ::ref-attributes-probed (::ref-attributes-probed incoming 0)}))))
