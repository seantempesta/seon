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
            [seon.error :as error]
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
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}] :seon.render.data/cursor] [:or [:map [:seon.render.data/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}]]] :seon.render.data/no-such-path-error]]}
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
                 (assoc
                  (error/diagnostic
                   {:seon.error/at (java.util.Date.)
                    :seon.error/layer :seon.render.data/cursor
                    :seon.error/operation 'seon.render.data/at
                    :seon.error/message "The requested path has no value at this step."
                    :seon.error/diagnostic-layer :seon.render.data/cursor
                    :seon.error/diagnostic-operation 'seon.render.data/at
                    :seon.error/diagnostic-member :seon.render.data/path
                    :seon.error/diagnostic-expected "a present map key, set member, or sequence index"
                    :seon.error/diagnostic-offending value
                    :seon.error/diagnostic-cause :seon.render.data/absent-path
                    :seon.error/diagnostic-evidence
                    {:seon.render.data/step step
                     :seon.render.data/path path
                     :seon.render.data/root-description (if (nil? value) "nil" (.getName (class value)))}})
                  :seon.render.data/path path
                  :seon.render.data/root-description (if (nil? value) "nil" (.getName (class value)))
                  :seon.render.data/requested-path-length (count path)
                  :seon.error/diagnostic-offending value
                  :seon.error/fix "Select a present member from the parent value.")))))
          {:seon.render.data/value value}
          path))

(defn pull-at
  "Pull one identified entity and return the value at `cursor`.

  A database refusal remains the refusal. A missing path remains the precise
  `at` refusal; nil is returned only when nil is the value at a present path."
  {:malli/schema
   [:=> [:cat :seon.db/pull-selector :seon.db/entity-id
         :seon.render.data/cursor]
    [:or :seon.render/value :seon.render.data/no-such-path-error :seon.db/error-result]]}
  [selector entity-id cursor]
  (let [pulled (db/pull selector entity-id)]

    (if (and (:seon.error/at pulled) (:seon.error/layer pulled) (:seon.error/operation pulled)) ;; debt: seon.db/pull declares :seon.error/value (directly or through :seon.db/error-result).
      pulled
      (let [selected (at pulled cursor)]
        (if (:seon.render.data/root-description selected)
          selected
          (:seon.render.data/value selected))))))

(defn- observation-error
  {:malli/schema [:=> [:cat :qualified-symbol :qualified-keyword :string :seon.schema/value]
                  :seon.render.data/observation-error]}
  [operation member message offending]
  (assoc
   (error/diagnostic
    {:seon.error/at (java.util.Date.)
     :seon.error/layer :seon.render.data/observation
     :seon.error/operation operation
     :seon.error/message message
     :seon.error/diagnostic-layer :seon.render.data/observation
     :seon.error/diagnostic-operation operation
     :seon.error/diagnostic-member member
     :seon.error/diagnostic-expected "an existing subject and a continuation from the same snapshot, entity, and direction"
     :seon.error/diagnostic-offending offending
     :seon.error/diagnostic-cause :seon.render.data/observation-unavailable
     :seon.error/diagnostic-evidence {member offending}})
   :seon.render.data/refused-member member
   :seon.error/diagnostic-offending offending
   :seon.error/fix "Acquire the subject again and use the continuation returned by that observation."))

(defn- continuation [snapshot eid direction offset cursor]
  (cond-> {::snapshot snapshot ::eid eid ::direction direction
           ::attribute-offset offset}
    cursor (assoc ::index-cursor cursor)))

(defn- matching-continuation? [cursor snapshot eid direction]
  (or (nil? cursor)
      (and (= snapshot (::snapshot cursor))
           (= eid (::eid cursor))
           (= direction (::direction cursor)))))

(defn- outgoing-page
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.render.data/snapshot :int [:int {:min 1}] [:int {:min 1}] [:maybe :seon.render.data/continuation]] [:or :seon.render.data/page :seon.db/error-result]]}
  [database snapshot eid limit weight cursor]
  (let [page (db/index-page
              database
              (cond-> {:index :eavt :components [eid] :direction :forward
                       :limit limit :max-result-weight weight}
                cursor (assoc :cursor (::index-cursor cursor))))]

    (if (and (:seon.error/at page) (:seon.error/layer page) (:seon.error/operation page)) ;; debt: seon.db/index-page declares :seon.error/value (directly or through :seon.db/error-result).
      page
      (cond-> {::datoms (:datahike.index-page/datoms page)
               ::complete? (:datahike.index-page/complete? page)}
        (:datahike.index-page/cursor page)
        (assoc ::continuation
               (continuation snapshot eid :outgoing 0
                             (:datahike.index-page/cursor page)))))))

(defn- incoming-page

  {:malli/schema [:=> [:cat :seon.db/database-value :seon.render.data/snapshot :int [:vector :qualified-keyword] [:int {:min 1}] [:int {:min 1}] [:int {:min 1}] [:maybe :seon.render.data/continuation]] [:or :seon.render.data/page :seon.render.data/observation-error :seon.db/error-result]]}
  [database snapshot eid attributes limit weight probe-limit cursor]
  (let [start (or (::attribute-offset cursor) 0)
        stop (min (count attributes) (+ start probe-limit))]
    (if (> start (count attributes))
      (observation-error 'seon.render.data/incoming-page ::attribute-offset
                         "Incoming continuation exceeds the reference attributes." cursor)
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

           (if (and (:seon.error/at page) (:seon.error/layer page) (:seon.error/operation page)) ;; debt: seon.db/index-page declares :seon.error/value (directly or through :seon.db/error-result).
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
                  [:or ::observation ::observation-error :seon.db/error-result]]}
  [{database :seon.db/db ::keys [subject limit max-result-weight
                                max-ref-attributes outgoing-cursor incoming-cursor]}]
  (let [snapshot (db/database-value-identity database)
        found (db/pull database [:db/id] subject)
        eid (:db/id found)]
    (cond

      (and (:seon.error/at snapshot) (:seon.error/layer snapshot) (:seon.error/operation snapshot)) snapshot ;; debt: seon.db/database-value-identity declares :seon.error/value (directly or through :seon.db/error-result).

      (and (:seon.error/at found) (:seon.error/layer found) (:seon.error/operation found)) found ;; debt: seon.db/pull declares :seon.error/value (directly or through :seon.db/error-result).
      (nil? eid) (observation-error 'seon.render.data/entity-observation ::subject "The selected entity does not exist." subject)
      (not (and (matching-continuation? outgoing-cursor snapshot eid :outgoing)
                (matching-continuation? incoming-cursor snapshot eid :incoming)))
      (observation-error 'seon.render.data/entity-observation ::continuation
                         "The continuation belongs to a different database value, entity, or direction."
                         {::outgoing-cursor outgoing-cursor ::incoming-cursor incoming-cursor ::snapshot snapshot ::subject subject})
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
