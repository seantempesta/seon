(ns my.ns
  "Inspect public functions through the live program graph.

   This namespace exposes namespace-scoped discovery over indexed code facts,
   including definitions created during the current session. Results use the
   same compact function-card representation as agent context, keeping source
   parsing and alternate documentation indexes outside this boundary."
  (:require
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.namespaces :as ns-cards]
    [seon.db :as db]
    ;; the shared `:seon.result/ok?` discriminator — Core owns it; my.ns
    ;; REFERENCES it (required for load order so its register! runs first).
    [seon.result]
    [seon.schema :as schema]))

;; [[functions]]'s map-in / map-out. `::ns` is the canonical namespace symbol.
;; A card is
;; one inert `fn full.ns/name [args] — "doc line 1" — :malli/schema …` record.
(schema/register! ::ns :symbol)
(schema/register! ::card :string)
(schema/register! ::cards [:vector ::card])
(schema/register! ::count :int)
(schema/register! ::error :string)
(schema/register! ::hint :string)
(schema/register! ::full? :boolean)
(schema/register! ::functions-request
  [:map
   [::ns ::ns]
   [:seon.db/db {:optional true} :seon.db/db]])
(schema/register!
  ::functions-response
  [:map
   [:seon.result/ok? :seon.result/ok?]
   [::cards {:optional true} ::cards]
   [::count {:optional true} ::count]
   [::error {:optional true} ::error]
   [::hint  {:optional true} ::hint]])
(schema/register! ::selection-request [:map [::ns ::ns]])
(schema/register!
  ::selection-response
  [:map
   [:seon.result/ok? :seon.result/ok?]
   [::ns ::ns]
   [::full? {:optional true} ::full?]
   [::error {:optional true} ::error]
   [::hint {:optional true} ::hint]])

(defn ^{:async true :seon.fn/agent-facing? true} functions
  "List the functions a namespace defines — name, doc, and args.

   Answers \"what can I call in X?\" for ANY indexed namespace (seon.*,
   my.*, your own) from public, real function rows with complete schemas.
   Private, non-function, and incomplete rows remain indexed but are excluded;
   cards sort by name. Database reads use one captured database value when
   supplied, or the current database when `:seon.db/db` is omitted.

     (my.ns/functions {:my.ns/ns 'my.plan})
     ; returns an ok result with compact cards and their count

   An unknown namespace returns an ok?-false envelope whose `::hint`
   carries the query that lists every indexed namespace. To reveal the
   namespace's complete indexed source in your next context, use:
   (my.ns/full! {:my.ns/ns 'my.plan})
   Compact it again when the source is no longer needed:
   (my.ns/compact! {:my.ns/ns 'my.plan})."
  {:malli/schema [:=> [:cat ::functions-request] ::functions-response]}
  [{ns-name ::ns dbv :seon.db/db}]
  (let [ns-sym ns-name
        database (or dbv (await (db/db)))]
    (if (:seon.error/message database)
      {:seon.result/ok? false
       ::error (str "namespace read failed: " (:seon.error/message database))}
      (let [eid (await
                 (db/query
                  {:seon.db/db database
                   :seon.db/query
                   '[:find ?e . :in $ ?n :where [?e :seon.ns/name ?n]]
                   :seon.db/args [ns-sym]}))]
        (cond
          (:seon.error/message eid)
          {:seon.result/ok? false
           ::error (str "namespace query failed: " (:seon.error/message eid))}

          (nil? eid)
          {:seon.result/ok? false
           ::error (str "namespace " ns-sym
                        " is not indexed — no :seon.ns row.")
           ::hint  (str "(seon.db/query '[:find [?n ...] :where "
                        "[_ :seon.ns/name ?n]]) lists every indexed namespace.")}

          :else
          (let [pulled
                (await
                 (db/pull
                  {:seon.db/db database
                   :seon.db/pull-pattern
                   '[{:seon.fn/_ns [:seon.fn/sym :seon.fn/arglists
                                    :seon.fn/doc :seon.fn/spec
                                    :seon.fn/private?
                                    :seon.fn/fn-var?
                                    :seon.fn/schema-error]}]
                   :seon.db/ref eid}))]
            (if (:seon.error/message pulled)
              {:seon.result/ok? false
               ::error (str "namespace pull failed: "
                            (:seon.error/message pulled))}
              (let [cards (->> (:seon.fn/_ns pulled)
                               (filter ns-cards/callable-fn-row?)
                               (sort-by :seon.fn/sym)
                               (mapv ns-cards/compact-fn-head))]
                (cond-> {:seon.result/ok? true
                         ::cards cards
                         ::count (count cards)}
                  (empty? cards)
                  (assoc ::hint
                         (str "indexed, but no public schema-complete fns — "
                              "(my.ns/full! {:my.ns/ns " (pr-str ns-sym)
                              "}) reveals its complete source in your next "
                              "context.")))))))))))

(defn- selection-error
  [ns-name message]
  {:seon.result/ok? false
   ::ns ns-name
   ::error message})

(defn ^:async ^:private select-source!
  [ns-name full?]
  (let [agent-id (db/current-agent-id)]
    (if-not agent-id
      (selection-error
       ns-name
       "namespace selection requires an agent evaluation context.")
      (let [database (await (db/db))]
        (if (:seon.error/message database)
          (selection-error
           ns-name (str "namespace selection read failed: "
                        (:seon.error/message database)))
          (let [namespace-row
                (await
                 (db/pull
                  {:seon.db/db database
                   :seon.db/pull-pattern [:seon.ns/name :seon.ns/source]
                   :seon.db/ref [:seon.ns/name ns-name]}))]
            (cond
              (:seon.error/message namespace-row)
              (selection-error
               ns-name (str "namespace query failed: "
                            (:seon.error/message namespace-row)))

              (not (string? (:seon.ns/source namespace-row)))
              (assoc
               (selection-error
                ns-name
                (str "namespace " ns-name
                     " has no indexed source to reveal or compact."))
               ::hint
               "Use the namespace catalog in context or my.ns/functions on an indexed namespace.")

              :else
              (let [agent
                    (await
                     (db/pull
                      {:seon.db/db database
                       :seon.db/pull-pattern '[{:seon.agent/ctx [*]}]
                       :seon.db/ref [:seon.agent/id agent-id]}))
                    block
                    (when-not (:seon.error/message agent)
                      (some #(when (= :namespaces
                                      (:seon.agent.ctx/name %))
                               %)
                            (ctx/agent-blocks agent)))]
                (cond
                  (:seon.error/message agent)
                  (selection-error
                   ns-name (str "agent context query failed: "
                                (:seon.error/message agent)))

                  (nil? block)
                  (selection-error
                   ns-name "the agent has no :namespaces context block.")

                  :else
                  (let [current-full (set (::ns-cards/full-source block))
                        current-compact (set (::ns-cards/compact block))
                        selected-full (if full?
                                        (conj current-full ns-name)
                                        (disj current-full ns-name))
                        selected-compact (if full?
                                           (disj current-compact ns-name)
                                           (conj current-compact ns-name))
                        updated (assoc block
                                       ::ns-cards/compact
                                       (vec (sort-by str selected-compact))
                                       ::ns-cards/full-source
                                       (vec (sort-by str selected-full)))
                        installed (await (ctx/install! updated))]
                    (if (:seon.agent.ctx/ok? installed)
                      {:seon.result/ok? true
                       ::ns ns-name
                       ::full? full?}
                      (selection-error
                       ns-name (str "namespace selection failed: "
                                    (:seon.agent.ctx/error installed))))))))))))))

(defn ^{:async true :seon.fn/agent-facing? true} full!
  "Reveal one indexed namespace's complete source in your next context.

   This moves the namespace from the block's compact presence-set to its exact
   full-source presence-set and preserves every other namespace display dial.
   Repeating the same selection is idempotent. An unknown namespace or a stale
   program row without indexed source returns an error value.

     (my.ns/full! {:my.ns/ns 'my.plan})"
  {:malli/schema [:=> [:cat ::selection-request] ::selection-response]}
  [{ns-name ::ns}]
  (await (select-source! ns-name true)))

(defn ^{:async true :seon.fn/agent-facing? true} compact!
  "Return one indexed namespace to its compact card in your next context.

   This moves the namespace from full-source to the existing block's compact
   presence-set, so an unrelated namespace remains visible as an inert public
   schema/function card. The current namespace can still render in full through
   the block's independent current-namespace dial.

     (my.ns/compact! {:my.ns/ns 'my.plan})"
  {:malli/schema [:=> [:cat ::selection-request] ::selection-response]}
  [{ns-name ::ns}]
  (await (select-source! ns-name false)))
