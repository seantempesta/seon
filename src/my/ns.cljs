(ns my.ns
  "What code exists, as data — ask the live program graph, not a file.

   Every namespace and function in this runtime is indexed as `:seon.ns` /
   `:seon.fn` rows (code-as-data), including fns defined this session that
   exist in no source file. [[functions]] turns one namespace's rows into
   the SAME one-line cards the `:namespaces` context section renders
   (`seon.agent.ctx.namespaces/compact-fn-head` — one card mechanism)."
  (:require
    [seon.agent.ctx.namespaces :as ns-cards]
    [seon.db :as db]
    ;; the shared `:seon.result/ok?` discriminator — Core owns it; my.ns
    ;; REFERENCES it (required for load order so its register! runs first).
    [seon.result]
    [seon.schema :as schema]))

;; [[functions]]'s map-in / map-out. `::ns` tolerates the three spellings a
;; caller naturally reaches for ('my.plan, :my.plan, "my.plan"). A card is
;; one inert `fn full.ns/name [args] — "doc line 1" — :malli/schema …` record.
(schema/register! ::ns [:or :symbol :keyword :string])
(schema/register! ::card :string)
(schema/register! ::cards [:vector ::card])
(schema/register! ::count :int)
(schema/register! ::error :string)
(schema/register! ::hint :string)
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

(defn ^{:async true :seon.fn/agent-facing? true} functions
  "List the functions a namespace defines — name, doc, and args.

   Answers \"what can I call in X?\" for ANY indexed namespace (seon.*,
   my.*, your own) from public, real function rows with complete schemas.
   Private, non-function, and incomplete rows remain indexed but are excluded;
   cards sort by name. Database reads use one captured database value when
   supplied, or the current database when `:seon.db/db` is omitted.

     (my.ns/functions {:my.ns/ns 'my.plan})
     ; ⟹ «map: :seon.result/ok? true, :my.ns/cards [\"fn my.plan/done! […]\" …],
     ;    :my.ns/count int»

   An unknown namespace returns an ok?-false envelope whose `::hint`
   carries the query that lists every indexed namespace. To read ONE
   fn's FULL source afterwards, drill:
   (seon.agent.ctx/render-namespace {:seon.ns/name :my.plan
                                     :seon.ns/member \"done!\"})."
  {:malli/schema [:=> [:cat ::functions-request] ::functions-response]}
  [{ns-name ::ns dbv :seon.db/db}]
  (let [ns-kw (if (keyword? ns-name) ns-name (keyword (str ns-name)))
        database (or dbv (await (db/db)))]
    (if (:seon.error/message database)
      {:seon.result/ok? false
       ::error (str "namespace read failed: " (:seon.error/message database))}
      (let [eid (await
                 (db/query
                  {:seon.db/db database
                   :seon.db/query
                   '[:find ?e . :in $ ?n :where [?e :seon.ns/name ?n]]
                   :seon.db/args [ns-kw]}))]
        (cond
          (:seon.error/message eid)
          {:seon.result/ok? false
           ::error (str "namespace query failed: " (:seon.error/message eid))}

          (nil? eid)
          {:seon.result/ok? false
           ::error (str "namespace " (name ns-kw)
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
                              "(seon.agent.ctx/render-namespace "
                              "{:seon.ns/name " ns-kw
                              "}) shows the whole namespace.")))))))))))
