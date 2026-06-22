(ns acme.notes
  "Acme NOTES ns — UNSPECCED ON PURPOSE, every fn here. The whole point is
   a downstream ns that owns ZERO specced fns, so the old indexer (which
   derived the downstream ns set from the SPECCED `!extra-core-vars`) gave
   it no `:seon.ns` row at all → it was SILENTLY invisible to agent
   context + retrieval. This is the BUG-C reproduction case: a third
   party's own code that has no `:malli/schema` anywhere still must be
   indexed + shown when SEON_EXTRA_SRC is set.")

(defn slugify
  "Lowercase + dash a label. Unspecced."
  [s]
  (-> s clojure.string/lower-case (clojure.string/replace #"\s+" "-")))

(defn note-line
  "Render a single note line. Unspecced."
  [title body]
  (str "# " title "\n" body))

(defn word-count
  "Count words in a string. Unspecced."
  [s]
  (if (empty? s) 0 (count (clojure.string/split s #"\s+"))))
