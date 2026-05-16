(ns seon.podhost.libdatahike.repl
  "Long-lived CLJS Node runtime for live REPL debugging of the libdatahike-cljs
   stack. Boots, prints a banner, and idles forever via `setInterval` so the
   shadow-cljs dev runtime stays attached. Drive evals into this runtime via
   shadow's nREPL piggieback (`(shadow.cljs.devtools.api/nrepl-select :repl)`)
   from any nREPL client.

   See REPL-WORKFLOW.md for how to invoke it.

   Hot-reload friendly: `dev/after-load` re-runs the banner so you can see
   that the watcher picked up your change."
  (:require [datahike.api :as d]
            [datahike.datom]
            [datahike.index.persistent-set]
            [konserve.node-filestore]
            [konserve.indexeddb]
            [me.tonsky.persistent-sorted-set.btset :as btset]
            [me.tonsky.persistent-sorted-set :as psset]
            [cljs.core.async :as a :refer [<!]])
  (:require-macros [cljs.core.async :refer [go]]))

;; ---------------------------------------------------------------------------
;; CLJS-DATAHIKE FIXES — see REPL-WORKFLOW.md "Diagnosis sidebar" for the
;; full root-cause analysis. Two upstream incompatibilities between datahike
;; 0.7.1624 and persistent-sorted-set 0.3.116 must be patched at runtime
;; before any datahike DB is created:
;;
;; (1) `empty-index` opt-key mismatch: datahike passes
;;       (psset/sorted-set* {:cmp <cmp-fn> ...})
;;     but psset's `btset/from-opts` only reads `:comparator`. Empty-built
;;     indexes therefore default their comparator to `cljs.core/compare`,
;;     which throws on Datom-vs-Datom comparison.
;;
;; (2) `insert` calls `(psset/lookup pset datom prefix-cmp)` expecting the
;;     3rd arg to be a custom comparator (works in CLJ where psset's
;;     `.lookup` Java method has a 3-arg overload), but in CLJS the 3-arg
;;     `psset/lookup` treats the 3rd argument as a `not-found` value, not
;;     a comparator. With `prefix-cmp` (a function) as `not-found`, lookup
;;     returns the function on "not found" — a truthy value — so `insert`
;;     thinks the datom already exists and skips the conj. Result:
;;     subsequent inserts into the same (e,a) get dropped silently.
;;     Most visible for cardinality/many where the second-and-later values
;;     all disappear.
;; ---------------------------------------------------------------------------

(defonce ^:private patches-applied?
  (do
    ;; FIX (1) — from-opts honors :cmp as alias for :comparator
    (let [orig btset/from-opts]
      (set! btset/from-opts
            (fn [opts]
              (let [opts' (if (and (:cmp opts) (not (:comparator opts)))
                            (assoc opts :comparator (:cmp opts))
                            opts)]
                (orig opts')))))
    ;; FIX (2) — replace insert to do a comparator-aware existence check via
    ;; conj's idempotency. psset/conj with a 3-arg cmp returns the same set
    ;; if the key already exists per cmp, so we can detect "no change" by
    ;; identity comparison. This costs us the zero-allocation lookup path
    ;; but is correct.
    (let [orig-insert datahike.index.persistent-set/insert]
      (set! datahike.index.persistent-set/insert
            (fn [pset datom index-type]
              (let [quick-cmp (datahike.datom/index-type->cmp-quick index-type)
                    next-pset (me.tonsky.persistent-sorted-set/conj
                               pset datom quick-cmp)]
                next-pset))))
    true))

(defonce !state (atom {:started-at (.getTime (js/Date.))
                       :reloads    0
                       :dbs        {}}))

(defn banner []
  (println (str "[repl] alive @ "
                (.toFixed (/ (.now js/performance) 1000) 1) "s, reloads="
                (:reloads @!state)
                ", indexedDB?="
                (boolean (.-indexedDB js/globalThis))
                ", node=" js/process.version)))

(defn hot-reload-marker
  "Edit this string to verify hot-reload propagation. Each :dev/after-load
   bump in `:reloads` should let a subsequent eval of `(hot-reload-marker)`
   return the new value without restarting the node runtime."
  []
  "v2 — hot-reload confirmed (edit → save → next eval returns the new string)")

(defn ^:dev/after-load on-reload []
  (swap! !state update :reloads inc)
  (println "[repl] :dev/after-load fired")
  (banner))

;; Convenience helpers callable from the REPL session.
(defn mem-db
  "Creates and returns a fresh :memory datahike DB. Returns a connection
   atom in a channel (use `<!`)."
  ([] (mem-db []))
  ([schema]
   (go
     (let [cfg {:store {:backend :memory
                        :id (random-uuid)}
                :schema-flexibility :write
                :keep-history? false}]
       (<! (d/create-database cfg))
       (let [conn (<! (d/connect cfg {:sync? false}))]
         (when (seq schema)
           (<! (d/transact! conn schema)))
         conn)))))

;; ---------------------------------------------------------------------------
;; Bisection helpers for the CLJS-2.5 comparator bug.
;;
;; Call e.g. `(probe :a 2000)` from the REPL; it prints PASS/FAIL + an error
;; string for the given schema variant + entity count.
;; ---------------------------------------------------------------------------

(def schema-variants
  {:a [{:db/ident :note/id :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity :db/valueType :db.type/string}
       {:db/ident :note/path :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity :db/valueType :db.type/string}]
   :b [{:db/ident :note/id :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity :db/valueType :db.type/string}
       {:db/ident :note/path :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity :db/valueType :db.type/string}
       {:db/ident :note/text :db/cardinality :db.cardinality/one
        :db/valueType :db.type/string}]
   :c [{:db/ident :note/id :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity :db/valueType :db.type/string}
       {:db/ident :note/path :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity :db/valueType :db.type/string}
       {:db/ident :note/created-at :db/cardinality :db.cardinality/one
        :db/index true :db/valueType :db.type/long}]
   :d [{:db/ident :note/id :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity :db/valueType :db.type/string}
       {:db/ident :note/path :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity :db/valueType :db.type/string}
       {:db/ident :note/tag :db/cardinality :db.cardinality/many
        :db/index true :db/valueType :db.type/string}]
   :e [{:db/ident :note/id :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity :db/valueType :db.type/string}
       {:db/ident :note/path :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity :db/valueType :db.type/string}
       {:db/ident :note/created-at :db/cardinality :db.cardinality/one
        :db/valueType :db.type/long}]
   :f [{:db/ident :note/id :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity :db/valueType :db.type/string}
       {:db/ident :note/path :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity :db/valueType :db.type/string}
       {:db/ident :note/tag :db/cardinality :db.cardinality/many
        :db/valueType :db.type/string}]})

(defn gen-entities [variant n]
  (case variant
    :a (mapv (fn [i] {:note/id (str "id-" i) :note/path (str "p/" i ".md")}) (range n))
    :b (mapv (fn [i] {:note/id (str "id-" i) :note/path (str "p/" i ".md")
                       :note/text (str "body-" i " text text text")}) (range n))
    :c (mapv (fn [i] {:note/id (str "id-" i) :note/path (str "p/" i ".md")
                       :note/created-at (+ 1700000000000 (* i 1000))}) (range n))
    :d (mapv (fn [i] {:note/id (str "id-" i) :note/path (str "p/" i ".md")
                       :note/tag (vec (distinct ["seon" (str "t" (mod i 7)) (str "u" (mod i 13))]))})
             (range n))
    :e (mapv (fn [i] {:note/id (str "id-" i) :note/path (str "p/" i ".md")
                       :note/created-at (+ 1700000000000 (* i 1000))}) (range n))
    :f (mapv (fn [i] {:note/id (str "id-" i) :note/path (str "p/" i ".md")
                       :note/tag (vec (distinct ["seon" (str "t" (mod i 7))]))})
             (range n))))

(defn probe!
  "Run a single variant/size probe, callback receives a result string.
   Returns the result channel."
  [variant n]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? false}
        schema (get schema-variants variant)
        entities (gen-entities variant n)]
    (go
      (try
        (<! (d/create-database cfg))
        (let [conn (<! (d/connect cfg {:sync? false}))
              _ (<! (d/transact! conn schema))
              r (<! (d/transact! conn entities))]
          (if (instance? js/Error r)
            (let [msg (.-message r)
                  stack (.-stack r)]
              (println (str "[probe " variant " n=" n "] FAIL: " msg))
              (println (str "[probe " variant " n=" n "] stack head: "
                            (->> (clojure.string/split (or stack "") #"\n")
                                 (take 8)
                                 (clojure.string/join "\n  "))))
              {:variant variant :n n :status :fail :msg msg :stack stack})
            (let [tx-count (count (:tx-data r))]
              (println (str "[probe " variant " n=" n "] OK: " tx-count " datoms"))
              {:variant variant :n n :status :ok :tx-count tx-count})))
        (catch :default e
          (println (str "[probe " variant " n=" n "] THREW: " (.-message e)))
          (println (str "[probe " variant " n=" n "] stack head: "
                        (->> (clojure.string/split (or (.-stack e) "") #"\n")
                             (take 8)
                             (clojure.string/join "\n  "))))
          {:variant variant :n n :status :threw :msg (.-message e) :stack (.-stack e)})))))

(defn probe-many-tx!
  "Build the cardinality/many tx data as raw [:db/add eid attr v] tuples
   rather than entity-maps so explode is bypassed. Lets us isolate whether
   the failure is in explode (likely) or insert (would say not).

   variant: :many-vector (entity-map with vector — uses explode)
            :many-raw    (raw [:db/add eid :tag v] tuples — no explode)
            :one         (cardinality/one tag, sanity)"
  [variant n]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? false}
        schema [{:db/ident :note/id :db/cardinality :db.cardinality/one
                 :db/unique :db.unique/identity :db/valueType :db.type/string}
                {:db/ident :note/tag
                 :db/cardinality (if (= :one variant)
                                   :db.cardinality/one
                                   :db.cardinality/many)
                 :db/valueType :db.type/string}]
        entities (case variant
                   :many-vector
                   (mapv (fn [i] {:note/id (str "id-" i)
                                  :note/tag ["seon" (str "t" (mod i 5))]})
                         (range n))
                   :many-raw
                   ;; Use :db/add with -1 / -2 / ... tempids per entity, two tag rows
                   (vec (mapcat (fn [i]
                                  (let [tid (str "e" i)]
                                    [[:db/add tid :note/id (str "id-" i)]
                                     [:db/add tid :note/tag "seon"]
                                     [:db/add tid :note/tag (str "t" (mod i 5))]]))
                                (range n)))
                   :one
                   (mapv (fn [i] {:note/id (str "id-" i)
                                  :note/tag (str "t" (mod i 5))})
                         (range n)))]
    (go
      (try
        (<! (d/create-database cfg))
        (let [conn (<! (d/connect cfg {:sync? false}))
              _ (<! (d/transact! conn schema))
              r (<! (d/transact! conn entities))]
          (if (instance? js/Error r)
            (do (println (str "[probe-many " variant " n=" n "] FAIL: " (.-message r)))
                {:status :fail :msg (.-message r) :stack (.-stack r)})
            (do (println (str "[probe-many " variant " n=" n "] OK: "
                              (count (:tx-data r)) " datoms"))
                {:status :ok :tx-count (count (:tx-data r))})))
        (catch :default e
          (println (str "[probe-many " variant " n=" n "] THREW: " (.-message e)))
          {:status :threw :msg (.-message e)})))))

(defn -main* []
  (banner)
  ;; idle forever — keep the Node runtime alive so shadow-cljs can drive it.
  (js/setInterval (fn [] nil) 60000))

(defn main [& _args]
  (-main*))
