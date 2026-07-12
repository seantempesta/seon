(ns my.blob
  "Content-addressed blob store — the disk tier for LARGE content.
   The three-tier storage rule: DB datoms = small indexed projections;
   BLOBS = persistent full content; the globalThis stash = volatile live
   values. Datom vs blob is decided by SIZE, never by kind — a benchmark
   run's full output, a scraped document, a big prompt all belong here,
   with only a hash + token estimate left in the DB.

   A blob's name IS its SHA-256 content hash: writes are idempotent,
   identical content dedupes for free, and the hash on a datom is a
   durable pointer that survives restarts. Files live under the cluster
   dir (`<cluster>/blobs/<first-2-of-hash>/<hash>`), beside the store
   they annotate. No GC — content-addressed blobs are append-only.

   Errors are VALUES: every function returns a map with `:my.blob/ok?`;
   a missing or malformed hash is a guiding error map, never a throw.

     (await (my.blob/put! {:my.blob/content big-report
                           :my.blob/media   :markdown}))
     ; returns «map: :my.blob/ok? true, :my.blob/hash \"9f86d0…\", :my.blob/tokens 812»
     (await (my.blob/concat! {:my.blob/hashes [h1 h2 h3]}))
     ; returns «chunked put!s → ONE canonical hash with honest whole-doc totals»
     (my.blob/stat {:my.blob/hash h})   ; DB projection — no disk touched
     (my.blob/text {:my.blob/hash h :my.blob/from-line 41
                    :my.blob/max-lines 40})  ; paged page, honest totals
     (my.blob/get  {:my.blob/hash h})   ; FULL content — bind it in code,
                                        ; never paste it into a reply"
  (:refer-clojure :exclude [get])
  (:require
    ["node:crypto" :as crypto]
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [clojure.string :as str]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.platform :as platform]
    [seon.schema :as schema]))

;;; SCHEMAS — the blob entity is its hash (identity) + a token estimate +
;;; an optional media hint + when it landed. Queries filter and budget on
;;; these projections without ever touching disk.

(schema/register! ::hash   [:string {:seon.db/identity true}]) ; sha-256 hex
(schema/register! ::tokens :int)                               ; tokens/estimate of the content
(schema/register! ::media  :keyword)                           ; hint: :markdown :edn :prompt …
(schema/register! ::at     :inst)                              ; when the blob was recorded

(schema/register! ::content :string)
(schema/register! ::ok?     :boolean)
(schema/register! ::error   :string)
(schema/register! ::exists? :boolean)

;; text paging — 1-based line window + honest totals (the fs precedent).
(schema/register! ::from-line      :int)
(schema/register! ::max-lines      :int)
(schema/register! ::lines-returned :int)
(schema/register! ::total-lines    :int)

(schema/register! ::put-request
  [:map
   [::content ::content]
   [::media   {:optional true} ::media]])

(schema/register! ::put-response
  [:map
   [::ok?    ::ok?]
   [::hash   ::hash]
   [::tokens {:optional true} ::tokens]
   [::error  {:optional true} ::error]])

(schema/register! ::hashes [:vector {:min 1} ::hash])

(schema/register! ::concat-request
  [:map
   [::hashes ::hashes]
   [::media  {:optional true} ::media]])

(schema/register! ::get-request
  [:map [::hash ::hash]])

(schema/register! ::get-response
  [:map
   [::ok?     ::ok?]
   [::hash    ::hash]
   [::content {:optional true} ::content]
   [::tokens  {:optional true} ::tokens]
   [::error   {:optional true} ::error]])

(schema/register! ::text-request
  [:map
   [::hash      ::hash]
   [::from-line {:optional true} ::from-line]
   [::max-lines {:optional true} ::max-lines]])

(schema/register! ::text-response
  [:map
   [::ok?            ::ok?]
   [::hash           ::hash]
   [::content        {:optional true} ::content]
   [::from-line      {:optional true} ::from-line]
   [::lines-returned {:optional true} ::lines-returned]
   [::total-lines    {:optional true} ::total-lines]
   [::tokens         {:optional true} ::tokens]
   [::error          {:optional true} ::error]
   ;; A binary blob refuses as a not-text envelope (naming the recorded media).
   [:seon.error/message {:optional true} :string]
   [:seon.error/data    {:optional true} [:map [::media {:optional true} ::media]]]])

(schema/register! ::stat-request
  [:map [::hash ::hash]])

(schema/register! ::stat-response
  [:map
   [::ok?     ::ok?]
   [::hash    ::hash]
   [::exists? ::exists?]
   [::tokens  {:optional true} ::tokens]
   [::media   {:optional true} ::media]
   [::at      {:optional true} ::at]])

;;; LOCATION — blobs live beside the cluster store they annotate. An atom
;;; so an isolated harness (a hermetic test, bin/acme) can point it at its
;;; own dir; the live pod never changes it.

(defonce !dir
  (atom (str (or (platform/env-val "SEON_CLUSTER_DIR")
                 "data/clusters/default")
             "/blobs")))

(def default-max-lines
  "Default [[text]] page size — a blob can be huge and the page renders
   into context, so an unbounded read is never the default."
  100)

(defn- sha256
  "SHA-256 hex digest of `s` (utf-8 bytes) — the blob's content address."
  [s]
  (-> (.createHash crypto "sha256")
      (.update s "utf8")
      (.digest "hex")))

(defn- blob-path
  "Absolute file path for `hash`: `<dir>/<first-2-of-hash>/<hash>`."
  [hash]
  (.resolve npath (.join npath @!dir (subs hash 0 2) hash)))

(defn- valid-hash?
  "True iff `hash` is a well-formed sha-256 hex string (64 lowercase hex)."
  [hash]
  (some? (re-matches #"[0-9a-f]{64}" hash)))

(defn- bad-hash
  "Error envelope for a hash that is not sha-256 hex — a value, no throw."
  [hash]
  {::ok?   false
   ::hash  hash
   ::error (str "not a sha-256 hex hash: " (pr-str hash)
                " — use the :my.blob/hash a put!/stat returned")})

(defn- not-found
  "Error envelope for a well-formed hash with no blob on disk."
  [hash]
  {::ok?   false
   ::hash  hash
   ::error (str "no blob stored under " hash
                " — (my.blob/stat {:my.blob/hash …}) checks the DB projection")})

(defn- text-content?
  "Whether `content` reads as text rather than binary — a COMPUTED sniff.

   NOT a media allowlist: `::media` is an optional hint and any hand-kept
   name set of text-vs-binary types would drift. A binary blob (a PNG,
   audio, an archive) read back as UTF-8 carries NUL (`\\u0000`) and U+FFFD
   replacement chars from the invalid byte sequences; real text carries
   neither. Scans a bounded prefix so a huge blob is never fully walked."
  [content]
  (let [head (subs content 0 (min (count content) 8192))]
    (not (or (str/includes? head "\u0000")
             (str/includes? head "\uFFFD")))))

(defn- page-lines
  "Slice `content` to a 1-based line window with honest totals.

   Mirrors seon.agent.fs paging: a trailing newline's empty pseudo-line is
   dropped so `total-lines` matches what an editor shows, and
   `lines-returned` < `max-lines` means you ran off the end."
  [content from-line max-lines]
  (let [lines (str/split content #"\n" -1)
        lines (if (and (seq lines) (= "" (peek lines))) (pop lines) lines)
        total (count lines)
        from  (max 1 (or from-line 1))
        start (min (dec from) total)
        end   (min total (+ start (max 0 max-lines)))]
    {::content        (str/join "\n" (subvec lines start end))
     ::from-line      from
     ::lines-returned (- end start)
     ::total-lines    total}))

;;; VERBS — put! is ^:async (it AWAITS the datom write); reads are sync so
;;; they compose inside let-bindings without an await.

(declare stat) ; text refuses a binary blob by naming stat's recorded media

(defn ^:async put!
  "Persist `:my.blob/content` content-addressed; record its projection.

   The file write is IDEMPOTENT — same content ⇒ same hash ⇒ no rewrite,
   and the datom row UPSERTS on the hash identity (never a duplicate).
   Serialize data yourself first (`pr-str` for edn); an optional
   `:my.blob/media` keyword hints what the bytes are (:markdown :edn …).

   One eval form reliably carries only ~2K tokens (~100 lines) of
   literal content — larger pastes truncate mid-form. For big content,
   put! it in chunks, then [[concat!]] the hashes into ONE canonical blob.

   Resolves to `{:my.blob/ok? true :my.blob/hash h :my.blob/tokens n}` —
   store the HASH on your own entity (a ref-by-value pointer); never
   re-carry the content in datoms."
  {:malli/schema [:=> [:cat ::put-request] ::put-response]}
  [{::keys [content media]}]
  (let [hash  (sha256 content)
        path  (blob-path hash)
        toks  (tokens/estimate content)
        werr  (try
                (when-not (.existsSync nfs path)
                  (.mkdirSync nfs (.dirname npath path) #js {:recursive true})
                  (.writeFileSync nfs path content "utf8"))
                nil
                (catch :default e (or (some-> e .-message) (str e))))]
    (if werr
      {::ok? false ::hash hash ::error werr}
      (let [{ok? ::db/ok? :as env}
            (await (db/transact!
                     {::db/tx-data [(cond-> {::hash   hash
                                             ::tokens toks
                                             ::at     (js/Date.)}
                                      media (assoc ::media media))]}))]
        (if ok?
          {::ok? true ::hash hash ::tokens toks}
          {::ok?   false
           ::hash  hash
           ::error (or (some-> (::db/error env) :seon.error/message)
                       "blob file written but the projection tx was rejected")})))))

(defn get
  "Full blob content by hash (sync) — for CODE, not for your reply.

   Bind the content and process it with fns; to SHOW a slice, use the
   paged [[text]]. Returns
   `{:my.blob/ok? true :my.blob/hash h :my.blob/content s :my.blob/tokens n}`
   or the not-found/bad-hash error value."
  {:malli/schema [:=> [:cat ::get-request] ::get-response]}
  [{::keys [hash]}]
  (cond
    (not (valid-hash? hash))            (bad-hash hash)
    (not (.existsSync nfs (blob-path hash))) (not-found hash)
    :else
    (try
      (let [content (.readFileSync nfs (blob-path hash) "utf8")]
        {::ok?     true
         ::hash    hash
         ::content content
         ::tokens  (tokens/estimate content)})
      (catch :default e
        {::ok? false ::hash hash ::error (or (some-> e .-message) (str e))}))))

(defn ^:async concat!
  "Join stored blobs, in order, into ONE new canonical blob.

   Takes `:my.blob/hashes` — existing put! hashes, in order — reads
   them, and stores their concatenation as a NEW content-addressed blob,
   so `:my.blob/tokens` and [[text]]'s `:my.blob/total-lines` are honest
   for the WHOLE document after content had to land as [[put!]] chunks.
   Idempotent like put!: same chunk set ⇒ same hash. A missing or
   malformed hash returns an error value NAMING that hash; nothing is
   written. The source chunks stay stored (append-only, no GC)."
  {:malli/schema [:=> [:cat ::concat-request] ::put-response]}
  [{::keys [hashes media]}]
  (let [reads (mapv (fn [h] (get {::hash h})) hashes)]
    (if-let [bad (first (remove ::ok? reads))]
      (select-keys bad [::ok? ::hash ::error])
      (await (put! (cond-> {::content (apply str (map ::content reads))}
                     media (assoc ::media media)))))))

(defn text
  "A paged line window of a blob (sync) — honest totals, never the lot.

   Defaults to the FIRST `default-max-lines` lines; pass a 1-based
   `:my.blob/from-line` + `:my.blob/max-lines` to walk the rest. The
   response always carries `:my.blob/total-lines` and the whole blob's
   `:my.blob/tokens`, so a partial page never looks complete —
   `lines-returned` < `max-lines` means you ran off the end.

   A BINARY blob (a PNG, audio, an archive) is not pageable text: `text`
   refuses it with `{:my.blob/ok? false :seon.error/message …}` naming the
   recorded media, rather than returning mojibake — reach its bytes with
   [[get]] instead."
  {:malli/schema [:=> [:cat ::text-request] ::text-response]}
  [{::keys [hash from-line max-lines] :or {max-lines default-max-lines}}]
  (let [{ok? ::ok? :as env} (get {::hash hash})]
    (cond
      (not ok?)
      (select-keys env [::ok? ::hash ::error])

      ;; A binary blob (image/audio/archive) read back as UTF-8 is mojibake,
      ;; never pageable text — refuse with an honest not-text envelope
      ;; (naming the recorded media) instead of returning latin1 garbage
      ;; with ok? true. get/stat still reach it as bytes.
      (not (text-content? (::content env)))
      (let [media (::media (stat {::hash hash}))]
        (cond-> {::ok?               false
                 ::hash              hash
                 :seon.error/message "binary blob — not pageable as text"}
          media (assoc :seon.error/data {::media media})))

      :else
      (merge {::ok?    true
              ::hash   hash
              ::tokens (::tokens env)}
             (page-lines (::content env) from-line max-lines)))))

(defn stat
  "The blob's DB projection — exists?, tokens, media, at; no disk touched.

   `exists?` answers \"is this hash recorded?\" — a missing hash is
   `{:my.blob/ok? true :my.blob/exists? false}`, an answer, not an error.
   Budget on `:my.blob/tokens` BEFORE reading: page a big blob with
   [[text]] instead of pulling it whole."
  {:malli/schema [:=> [:cat ::stat-request] ::stat-response]}
  [{::keys [hash]}]
  ;; FIND by attribute presence (never a lookup-ref here: on a store no
  ;; put! has touched yet the attr isn't installed and a lookup-ref throws;
  ;; a query just returns nothing).
  (if-let [e (some-> (db/query '[:find ?e . :in $ ?h :where [?e ::hash ?h]]
                               hash)
                     db/entity)]
    (cond-> {::ok?     true
             ::hash    hash
             ::exists? true
             ::tokens  (::tokens e)
             ::at      (::at e)}
      (::media e) (assoc ::media (::media e)))
    {::ok? true ::hash hash ::exists? false}))
