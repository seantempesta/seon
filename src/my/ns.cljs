(ns my.ns
  "What code exists, as data — ask the live program graph, not a file.

   Every namespace and function in this world is indexed as `:seon.ns` /
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
;; a one-line `(defn name "doc line 1" {:malli/schema …} [args] …)` head.
(schema/register! ::ns [:or :symbol :keyword :string])
(schema/register! ::card :string)
(schema/register! ::cards [:vector ::card])
(schema/register! ::count :int)
(schema/register! ::error :string)
(schema/register! ::hint :string)
(schema/register! ::functions-request [:map [::ns ::ns]])
(schema/register!
  ::functions-response
  [:map
   [:seon.result/ok? :seon.result/ok?]
   [::cards {:optional true} ::cards]
   [::count {:optional true} ::count]
   [::error {:optional true} ::error]
   [::hint  {:optional true} ::hint]])

(defn functions
  "List the functions a namespace defines — name, doc, and args.

   Answers \"what can I call in X?\" for ANY indexed namespace (seon.*,
   my.*, your own) straight from the `:seon.fn` rows. Private fns are
   excluded; cards sort by name. SYNC — reads only.

     (my.ns/functions {:my.ns/ns 'my.plan})
     ; ⟹ «map: :seon.result/ok? true, :my.ns/cards [\"(defn done! …)\" …],
     ;    :my.ns/count int»

   An unknown namespace returns an ok?-false envelope whose `::hint`
   carries the query that lists every indexed namespace. To read ONE
   fn's FULL source afterwards, drill:
   (seon.agent.ctx/render-namespace {:seon.ns/name :my.plan
                                     :seon.ns/member \"done!\"})."
  {:malli/schema [:=> [:cat ::functions-request] ::functions-response]}
  [{ns-name ::ns}]
  (let [db     @db/*conn*
        ns-kw  (if (keyword? ns-name) ns-name (keyword (str ns-name)))
        pulled (db/pull db
                        '[:seon.ns/name
                          {:seon.fn/_ns [:seon.fn/sym :seon.fn/arglists
                                         :seon.fn/doc :seon.fn/spec
                                         :seon.fn/private?]}]
                        [:seon.ns/name ns-kw])]
    (if (nil? pulled)
      {:seon.result/ok? false
       ::error (str "namespace " (name ns-kw) " is not indexed — no :seon.ns row.")
       ::hint  (str "(seon.db/query '[:find [?n ...] :where [_ :seon.ns/name ?n]]) "
                    "lists every indexed namespace.")}
      (let [cards (->> (:seon.fn/_ns pulled)
                       (remove :seon.fn/private?)
                       (sort-by :seon.fn/sym)
                       (mapv #(ns-cards/compact-fn-head (name ns-kw) %)))]
        (cond-> {:seon.result/ok? true
                 ::cards cards
                 ::count (count cards)}
          (empty? cards)
          (assoc ::hint (str "indexed, but no public fns — "
                             "(seon.agent.ctx/render-namespace {:seon.ns/name "
                             ns-kw "}) shows the whole namespace.")))))))
