(ns seon.podhost.datahike-harness.schema
  "Shared schema + data-generator used by the workloads.

   Note-like entities approximating a personal-vault corpus. Same shape as the
   CLJS-side bench in pod-host/libdatahike-cljs so JVM and CLJS numbers compare
   directly. ~9 datoms per entity on average (5 simple attrs + ~3 tags via
   cardinality/many).")

(def schema
  [{:db/ident       :note/id
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string}
   {:db/ident       :note/path
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string}
   {:db/ident       :note/text
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/string}
   {:db/ident       :note/created-at
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/valueType   :db.type/long}
   {:db/ident       :note/tag
    :db/cardinality :db.cardinality/many
    :db/index       true
    :db/valueType   :db.type/string}])

(def ^:private tag-pool
  ["seon" "seon" "datahike" "wasmer" "edgejs" "clojure" "cljs"
   "research" "spec" "todo" "meeting" "idea" "decision"])

(defn- pick-tags
  "Every note carries 'seon' plus two deterministic seeded tags from the pool."
  [seed]
  (let [b (mod (* seed 13) (count tag-pool))
        c (mod (* seed 31) (count tag-pool))]
    (vec (distinct ["seon" (nth tag-pool b) (nth tag-pool c)]))))

(def ^:private base-ts 1700000000000)

(defn- gen-text [i]
  (str "note-" i " body text. Lorem ipsum sit amet at index " i
       " consectetur adipiscing elit, integer scelerisque."))

(defn gen-note
  "Build a single entity map."
  [i id-vec]
  {:note/id         (nth id-vec i)
   :note/path       (str "vault/notes/note-" i ".md")
   :note/text       (gen-text i)
   :note/created-at (+ base-ts (* i 1000))
   :note/tag        (pick-tags i)})

(defn gen-id-vec
  "Deterministic id-vec — `(gen-id-vec n)` always returns the same n string ids."
  [n]
  (mapv #(str "note-id-" %) (range n)))

(defn gen-batch
  "Generate a flat batch of notes [start, start+n)."
  [start n id-vec]
  (mapv #(gen-note % id-vec) (range start (+ start n))))
