;; Seon FORKS CLUSTERS FROM A COMMIT ID, so `:commit-graph? false` is not
;; admissible. Measure the two remaining write-amplification options alone.
(load-file "options-lib.clj")
(run "J-fuse+diffbuf-keep-commit-graph"
     (fn [p] (assoc (base p {}) :fuse-index-roots? true
                    :index-config {:diff-buf-size 256})))
(run "K-J-plus-in-place"
     (fn [p] (assoc (base p {:config {:in-place? true :no-backup? true}})
                    :fuse-index-roots? true :index-config {:diff-buf-size 256})))
(println :done)
