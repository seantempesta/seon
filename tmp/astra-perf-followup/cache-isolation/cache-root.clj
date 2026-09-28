(defn analysis-cache-root
  "Select the resolver cache once from source ownership. Scratch inputs may
  use an explicitly private cache, never the shared cache or its descendants;
  canonical paths include aliases through symlinks. Nil disables caching."
  {:malli/schema [:=> [:cat [:sequential :string] [:maybe :string]] [:maybe :string]]}
  [paths requested]
  (let [directory (or requested cache-directory)]
    (when (or (every? checkout-source? paths)
              (not (under? #{(.getCanonicalPath (io/file config-directory ".cache"))}
                           (.getCanonicalPath (io/file directory)))))
      directory)))
