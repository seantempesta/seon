(defn- derivation-keys
  "Each derivation input's key, stored as `:seon.fn.file/derivation-digest`:
  its bytes, the identity of the code deriving it (`selected-analyzer-digest`),
  clj-kondo's pin and configuration, and the forms of the schema keys its
  derivation reads (`derivation-schema-keys`) with the program row shapes',
  each closed over `:seon.schema.projection/schema-dependencies`. A publication
  re-derives exactly the inputs whose stored key differs.

  # Known issues
  - Cross-file inputs (the symbols a file's calls resolve to, a callee's
    host-bound flag, an invoked attribute's targets, clj-kondo findings over
    another namespace's signatures) are not in the key: the dependent and caller
    re-lint selection (`seon.fn/dependent-paths`) re-derives those files (task
    aca1b25fa16a).
  - The database owner's read path is not in the key: a read-path change that
    alters logical read results is covered by the owner's own tests, not by
    re-derivation (task 50a6a9afcb64).
  - An encoding-only storage change leaves reused entities in their old
    encoding; storage migration owns it (task 52875b443ef0)."
  {:seon/tasks #{"aca1b25fa16a" "50a6a9afcb64" "52875b443ef0"}
   :malli/schema [:=> [:cat :seon.source/analyzer-digest :seon.schema/projection
                       :seon.source/relative-file-digests [:map-of :string [:set :keyword]] [:set :string]]
                  [:map-of :string :seon.source/digest]]}
  [code projection inputs schema-keys paths]
  (let [forms (:seon.schema.projection/forms projection)
        dependencies (:seon.schema.projection/schema-dependencies projection)
        closure (fn [start]
                  (loop [pending (vec start) reached #{}]
                    (if-let [k (peek pending)]
                      (if (reached k)
                        (recur (pop pending) reached)
                        (recur (into (pop pending) (get dependencies k)) (conj reached k)))
                      reached)))
        row-shapes (program/shapes-in projection)
        world (cache/lookup
               ::derivation-world [forms dependencies row-shapes]
               (constantly (* 4096 (count forms)))
               #(hash-map ::key (id/digest 64 [forms dependencies row-shapes])
                          ::shapes (closure (into #{} (filter (partial contains? forms))
                                                (tree-seq coll? seq row-shapes)))))
        kondo [(get (source/loaded-dependencies) "reference-code/clj-kondo")
               (into (sorted-map) (filter #(str/starts-with? (key %) ".clj-kondo/")) inputs)]]
    (into {}
          (map (fn [path]
                 (let [start (get schema-keys path #{})
                       input (get inputs path)]
                   [path
                    (cache/lookup
                     ::file-derivation-key [code kondo input (::key world) start]
                     (constantly (+ 512 (* 64 (count start))))
                     #(file-derivation-key
                       code kondo input
                       (cache/lookup
                        ::derivation-forms [(::key world) start]
                        (fn [held] (* 256 (count held)))
                        (fn []
                          (into (sorted-map)
                                (map (fn [k] [k (id/digest 64 [(get forms k)])]))
                                (into (::shapes world) (closure start)))))))])))
          paths)))
