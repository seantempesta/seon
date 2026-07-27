(ns my.blob.core
  "Portable transformations for the content-addressed blob capability."
  (:require [clojure.string :as str]
            [seon.content-hash :as content-hash]))

(defn sha256
  "Return the canonical SHA-256 content identity."
  [content]
  (content-hash/sha-256 content))

(defn valid-hash?
  "True when `hash` is a lowercase SHA-256 hex string."
  [hash]
  (and (string? hash) (some? (re-matches #"[0-9a-f]{64}" hash))))

(defn bad-hash [hash]
  {:my.blob/ok? false :my.blob/hash hash
   :my.blob/error (str "not a sha-256 hex hash: " (pr-str hash)
                       " — use the :my.blob/hash a put!/stat returned")})

(defn not-found [hash]
  {:my.blob/ok? false :my.blob/hash hash
   :my.blob/error (str "no blob stored under " hash
                       " — (my.blob/stat {:my.blob/hash …}) checks the DB projection")})

(defn blob-path-parts
  "Return the archive-relative shard and content-addressed filename."
  [hash]
  [(subs hash 0 2) hash])

(defn select-overlay
  "Return the first successful overlay result in search order."
  [results]
  (first (filter :my.blob/exists? results)))

(defn text-content?
  "True when a bounded prefix has no binary decoding markers."
  [content]
  (let [head (subs content 0 (min (count content) 8192))]
    (not (or (str/includes? head "\u0000")
             (str/includes? head "\uFFFD")))))

(defn page-lines
  "Slice content to a one-based bounded line window."
  [content from-line max-lines]
  (let [lines (str/split content #"\n" -1)
        lines (if (and (seq lines) (= "" (peek lines))) (pop lines) lines)
        total (count lines)
        from (max 1 (or from-line 1))
        start (min (dec from) total)
        end (min total (+ start (max 0 max-lines)))]
    {:my.blob/content (str/join "\n" (subvec lines start end))
     :my.blob/from-line from
     :my.blob/lines-returned (- end start)
     :my.blob/total-lines total}))

(defn concatenate
  "Concatenate ordered blob content without separators."
  [contents]
  (apply str contents))

(defn canonical-retained-hashes
  "Canonicalize hashes acquired from one immutable database value."
  [hashes]
  (->> hashes distinct sort vec))

(defn observe-retained
  "Observe one immutable database value's bounded blob-set identity."
  [{:my.blob/keys [target-branch-head retained-hashes]}]
  (let [hashes (canonical-retained-hashes retained-hashes)
        invalid-hash (first (remove valid-hash? hashes))]
    (if invalid-hash
      {:my.blob/ok? false
       :my.blob/target-branch-head target-branch-head
       :my.blob/error "the retained database contains a malformed :my.blob/hash"}
      {:my.blob/ok? true
       :my.blob/target-branch-head target-branch-head
       :my.blob/reachable-hash-digest (sha256 (pr-str hashes))
       :my.blob/hash-count (count hashes)})))
