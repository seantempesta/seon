(ns my.blob
  "Store and retrieve large content by its SHA-256 address.

   This namespace is the agent-facing disk tier for content too large for
   database datoms. It covers durable publication, chunk assembly, metadata,
   bounded text reads, and full-content access through one writable archive
   with optional ordered read-only bases. Operations use data envelopes and
   leave only compact hashes and projections in the database."
  (:refer-clojure :exclude [get])
  (:require
    ["node:crypto" :as crypto]
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [clojure.string :as str]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.dev.restore.schema]
    [my.blob.schema]
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
(schema/register! ::error   :string)
(schema/register! ::exists? :boolean)
(schema/register! ::retained-hashes [:vector :string])
(schema/register! ::source-storage-view ::storage-view)
(schema/register! ::destination-storage-view ::storage-view)
(schema/register! ::searched-source-paths [:vector :string])
(schema/register! ::destination-path :string)
(schema/register! ::expected-digest ::digest)
(schema/register! ::actual-digest ::digest)
(schema/register!
  ::materialization-operation
  [:enum
   :my.blob.materialization.operation/validate-request
   :my.blob.materialization.operation/derive-retained-set
   :my.blob.materialization.operation/verify-source
   :my.blob.materialization.operation/publish-destination
   :my.blob.materialization.operation/verify-destination])

(schema/register!
  ::materialization-request
  [:map {:closed true}
   [::target-branch-head ::target-branch-head]
   [::retained-hashes ::retained-hashes]
   [::source-storage-view ::source-storage-view]
   [::destination-storage-view ::destination-storage-view]
   [::reachable-hash-digest ::reachable-hash-digest]])

(schema/register!
  ::materialization-failure
  [:map {:closed true}
   [::ok? [:= false]]
   [::target-branch-head ::target-branch-head]
   [::reachable-hash-digest ::reachable-hash-digest]
   [::hash-count ::hash-count]
   [::materialization-operation ::materialization-operation]
   [::hash {:optional true} :string]
   [::searched-source-paths {:optional true} ::searched-source-paths]
   [::destination-path {:optional true} ::destination-path]
   [::expected-digest ::expected-digest]
   [::actual-digest {:optional true} ::actual-digest]
   [::error ::error]])

(schema/register!
  ::materialization-result
  [:or ::materialization-success ::materialization-failure])

(schema/register!
  ::operator-operation
  [:enum
   :my.blob.operator.operation/observe-retained
   :my.blob.operator.operation/materialize-retained])

(schema/register!
  ::operator-observe-request
  [:map {:closed true}
   [::operator-operation
    [:= :my.blob.operator.operation/observe-retained]]
   [::target-branch-head ::target-branch-head]])

(schema/register!
  ::operator-materialize-request
  [:map {:closed true}
   [::operator-operation
    [:= :my.blob.operator.operation/materialize-retained]]
   [:seon.dev.restore/startup-identity
    :seon.dev.restore/startup-identity]
   [::target-branch-head ::target-branch-head]
   [::source-storage-view ::source-storage-view]
   [::destination-storage-view ::destination-storage-view]])

(schema/register!
  ::operator-request
  [:or ::operator-observe-request ::operator-materialize-request])

(schema/register!
  ::retained-observation-request
  [:map {:closed true}
   [::target-branch-head ::target-branch-head]
   [::retained-hashes ::retained-hashes]])

(schema/register!
  ::intent-materialization-request
  [:map {:closed true}
   [:seon.dev.restore/startup-identity
    :seon.dev.restore/startup-identity]
   [::target-branch-head ::target-branch-head]
   [::retained-hashes ::retained-hashes]
   [::source-storage-view ::source-storage-view]
   [::destination-storage-view ::destination-storage-view]])

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
   [::max-lines {:optional true} ::max-lines]
   [::db/db {:optional true} :seon.db/db]])

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
  [:map
   [::hash ::hash]
   [::db/db {:optional true} :seon.db/db]])

(schema/register! ::stat-response
  [:map
   [::ok?     ::ok?]
   [::hash    ::hash]
   [::exists? ::exists?]
   [::tokens  {:optional true} ::tokens]
   [::media   {:optional true} ::media]
   [::at      {:optional true} ::at]
   [::error   {:optional true} ::error]])

;;; LOCATION — one writable archive plus ordered read-only bases. This is
;;; process-local launch data. The atom remains the explicit hermetic-test
;;; seam; the live pod does not mutate it.

(defonce !storage-view
  (atom {::writable-dir
         (str (or (platform/env-val "SEON_CLUSTER_DIR")
                  "data/clusters/default")
              "/blobs")
         ::read-only-dirs []}))

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

(defn- normalize-storage-view
  "Normalize one explicit storage view, or return an errors-as-data envelope."
  [view]
  (if-not (schema/valid-candidate-value? ::storage-view view)
    {::ok? false
     ::error (str "invalid blob storage view — expected "
                  "{:my.blob/writable-dir string "
                  ":my.blob/read-only-dirs [string …]}")}
    (let [writable-dir (.resolve npath (::writable-dir view))
          read-only-dirs (mapv #(.resolve npath %) (::read-only-dirs view))
          all-dirs (into [writable-dir] read-only-dirs)]
      (if (= (count all-dirs) (count (distinct all-dirs)))
        {::ok? true
         ::storage-view {::writable-dir writable-dir
                         ::read-only-dirs read-only-dirs}}
        {::ok? false
         ::error "invalid blob storage view — every directory must be distinct"}))))

(defn- validated-storage-view
  "The normalized ambient storage view, or an errors-as-data envelope."
  []
  (normalize-storage-view @!storage-view))

(defn- blob-path
  "Absolute path for `hash` below one archive directory."
  [dir hash]
  (.resolve npath (.join npath dir (subs hash 0 2) hash)))

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

(defn- inspect-path
  "Read one path through the archive's single SHA-256 verification owner."
  [hash path]
  (try
    (let [content (.readFileSync nfs path "utf8")
          actual (sha256 content)]
      (if (= hash actual)
        {::ok? true
         ::hash hash
         ::path path
         ::actual-digest actual
         ::content content}
        {::ok? false
         ::hash hash
         ::path path
         ::actual-digest actual
         ::error (str "blob integrity failure under " hash
                      " — stored bytes hash to " actual)}))
    (catch :default e
      {::ok? false
       ::hash hash
       ::path path
       ::error (or (some-> e .-message) (str e))})))

(defn- read-path
  "Read and verify one path without exposing internal filesystem evidence."
  [hash path]
  (select-keys (inspect-path hash path) [::ok? ::hash ::content ::error]))

(defn- resolve-blob-evidence
  "Resolve the first existing path and retain the paths searched in order."
  [{::keys [writable-dir read-only-dirs]} hash]
  (reduce
    (fn [{::keys [searched-source-paths]} dir]
      (let [path (blob-path dir hash)
            searched (conj searched-source-paths path)]
        (if (.existsSync nfs path)
          (reduced
            (assoc (inspect-path hash path)
                   ::searched-source-paths searched))
          {::searched-source-paths searched})))
    {::searched-source-paths []}
    (into [writable-dir] read-only-dirs)))

(defn- resolve-blob
  "Read the first existing path in overlay-to-base order."
  [{::keys [writable-dir read-only-dirs]} hash]
  (reduce
    (fn [_ dir]
      (let [path (blob-path dir hash)]
        (when (.existsSync nfs path)
          (reduced (read-path hash path)))))
    nil
    (into [writable-dir] read-only-dirs)))

(def ^:private node-publication-effects
  {::sync-file-descriptor! (fn [fd] (.fsyncSync nfs fd))
   ::atomic-rename! (fn [from to] (.renameSync nfs from to))
   ::transact! db/transact!})

(def ^:private node-database-effects
  {::current-db! db/db
   ::query! db/query})

(defn- sync-directory!
  "Synchronize a directory entry before publication reports success."
  [effects dir]
  (let [fd (.openSync nfs dir "r")]
    (try
      ((::sync-file-descriptor! effects) fd)
      (finally
        (.closeSync nfs fd)))))

(defn- directory-plan
  "The nearest existing ancestor and missing directories in creation order."
  [dir]
  (loop [candidate (.resolve npath dir)
         missing ()]
    (if (.existsSync nfs candidate)
      {::directory-anchor candidate
       ::missing-directories (vec missing)}
      (let [parent (.dirname npath candidate)]
        (when (= candidate parent)
          (throw (js/Error. (str "no existing directory ancestor for " dir))))
        (recur parent (conj missing candidate))))))

(defn- ensure-directory-durable!
  "Create each missing directory and synchronize every parent entry."
  [effects dir]
  (let [{::keys [directory-anchor missing-directories]}
        (directory-plan dir)
        anchor-parent (.dirname npath directory-anchor)]
    ;; If a prior attempt created `directory-anchor` but its parent sync
    ;; failed, presence alone cannot prove that entry durable. Re-sync the
    ;; boundary before extending it or accepting it as the requested path.
    (when-not (= directory-anchor anchor-parent)
      (sync-directory! effects anchor-parent))
    (reduce
      (fn [parent child]
        (try
          (.mkdirSync nfs child)
          (catch :default e
            (when-not (and (= "EEXIST" (.-code e))
                           (.. nfs (statSync child) (isDirectory)))
              (throw e))))
        (sync-directory! effects parent)
        child)
      directory-anchor
      missing-directories)
    (.resolve npath dir)))

(defn- sync-published!
  "Synchronize an existing verified file and its containing directory."
  [effects path]
  (let [fd (.openSync nfs path "r")]
    (try
      ((::sync-file-descriptor! effects) fd)
      (finally
        (.closeSync nfs fd))))
  (sync-directory! effects (.dirname npath path)))

(defn- publish!
  "Publish complete bytes through file sync, rename, and directory sync.

   `replace-existing?` is reserved for restore after an independent source
   verification has proved the replacement bytes. Ordinary puts never replace
   an existing content-addressed pathname."
  [effects writable-dir hash content replace-existing?]
  (let [path (blob-path writable-dir hash)
        shard (.dirname npath path)
        tmp (.join npath shard (str hash "." (.randomUUID crypto) ".new"))]
    (try
      (ensure-directory-durable! effects writable-dir)
      (ensure-directory-durable! effects shard)
      (if (and (.existsSync nfs path) (not replace-existing?))
        (sync-published! effects path)
        (do
          (let [fd (.openSync nfs tmp "wx")]
            (try
              (.writeFileSync nfs fd content "utf8")
              ((::sync-file-descriptor! effects) fd)
              (finally
                (.closeSync nfs fd))))
          ((::atomic-rename! effects) tmp path)
          (sync-directory! effects shard)))
      nil
      (catch :default e
        (or (some-> e .-message) (str e)))
      (finally
        (when (.existsSync nfs tmp)
          (.unlinkSync nfs tmp))))))

(defn- materialization-failure
  [target-branch-head frozen-digest hash-count operation error evidence]
  (merge
    {::ok? false
     ::target-branch-head target-branch-head
     ::reachable-hash-digest frozen-digest
     ::hash-count hash-count
     ::materialization-operation operation
     ::expected-digest (or (::expected-digest evidence) frozen-digest)
     ::error error}
    (select-keys evidence
                 [::hash ::searched-source-paths ::destination-path
                  ::actual-digest])))

(defn- canonical-retained-hashes
  "Canonicalize the blob hashes acquired from one immutable database value."
  [hashes]
  (->> hashes distinct sort vec))

(defn ^:no-doc observe-retained
  "Observe one immutable database value's bounded blob-set identity."
  {:malli/schema
   [:=> [:cat ::retained-observation-request]
    ::retained-observation-result]
   :seon.fn/agent-facing? false}
  [{::keys [target-branch-head retained-hashes]}]
  (try
    (let [hashes (canonical-retained-hashes retained-hashes)
          invalid-hash (first (remove valid-hash? hashes))]
      (if invalid-hash
      {::ok? false
       ::target-branch-head target-branch-head
       ::error "the retained database contains a malformed :my.blob/hash"}
       {::ok? true
        ::target-branch-head target-branch-head
        ::reachable-hash-digest (sha256 (pr-str hashes))
        ::hash-count (count hashes)}))
    (catch :default error
      {::ok? false
       ::target-branch-head target-branch-head
       ::error (or (some-> error .-message) (str error))})))

(defn- materialize-hash!
  [effects source-view destination-dir target-branch-head frozen-digest
   hash-count counts hash]
  (let [destination (blob-path destination-dir hash)
        source (resolve-blob-evidence source-view hash)
        source-paths (::searched-source-paths source)]
    (cond
      (not (contains? source ::ok?))
      (reduced
        (materialization-failure
          target-branch-head frozen-digest hash-count
          :my.blob.materialization.operation/verify-source
          (str "no retained source blob exists under " hash)
          {::hash hash
           ::searched-source-paths source-paths
           ::destination-path destination
           ::expected-digest hash}))

      (false? (::ok? source))
      (reduced
        (materialization-failure
          target-branch-head frozen-digest hash-count
          :my.blob.materialization.operation/verify-source
          (::error source)
          (cond->
            {::hash hash
             ::searched-source-paths source-paths
             ::destination-path destination
             ::expected-digest hash}
            (::actual-digest source)
            (assoc ::actual-digest (::actual-digest source)))))

      :else
      (let [before (when (.existsSync nfs destination)
                     (inspect-path hash destination))
            replace-existing? (and before (false? (::ok? before)))
            publish-error
            ;; A valid pathname can be the complete result of an interrupted
            ;; prior publication whose final directory sync failed. Route it
            ;; through the same publisher so retry re-syncs file + shard.
            (publish! effects destination-dir hash (::content source)
                      replace-existing?)]
        (if publish-error
          (reduced
            (materialization-failure
              target-branch-head frozen-digest hash-count
              :my.blob.materialization.operation/publish-destination
              publish-error
              {::hash hash
               ::searched-source-paths source-paths
               ::destination-path destination
               ::expected-digest hash}))
          (let [final (inspect-path hash destination)]
            (if (false? (::ok? final))
              (reduced
                (materialization-failure
                  target-branch-head frozen-digest hash-count
                  :my.blob.materialization.operation/verify-destination
                  (::error final)
                  (cond->
                    {::hash hash
                     ::searched-source-paths source-paths
                     ::destination-path destination
                     ::expected-digest hash}
                    (::actual-digest final)
                    (assoc ::actual-digest (::actual-digest final)))))
              (cond-> (update counts ::verified-count inc)
                (nil? before) (update ::newly-materialized-count inc)
                replace-existing? (update ::repaired-count inc)))))))))

(defn- materialize-retained-with-effects!
  "Verify and materialize one frozen intent's exact retained blob set."
  [{::keys [target-branch-head retained-hashes source-storage-view
            destination-storage-view reachable-hash-digest]}
   effects]
  (let [source-result (normalize-storage-view source-storage-view)
        destination-result (normalize-storage-view destination-storage-view)
        source-view (::storage-view source-result)
        destination-view (::storage-view destination-result)
        destination-dir (::writable-dir destination-view)]
    (cond
      (false? (::ok? source-result))
      (materialization-failure
        target-branch-head reachable-hash-digest 0
        :my.blob.materialization.operation/validate-request
        (::error source-result)
        {})

      (false? (::ok? destination-result))
      (materialization-failure
        target-branch-head reachable-hash-digest 0
        :my.blob.materialization.operation/validate-request
        (::error destination-result)
        {})

      (not= destination-dir (first (::read-only-dirs source-view)))
      (materialization-failure
        target-branch-head reachable-hash-digest 0
        :my.blob.materialization.operation/validate-request
        "the main writable archive must be the target view's first inherited base"
        {::destination-path destination-dir})

      :else
      (try
        (let [hashes (canonical-retained-hashes retained-hashes)
              hash-count (count hashes)
              invalid-hash (first (remove valid-hash? hashes))
              derived-digest (sha256 (pr-str hashes))]
          (cond
            invalid-hash
          (materialization-failure
            target-branch-head reachable-hash-digest hash-count
            :my.blob.materialization.operation/derive-retained-set
            "the retained database contains a malformed :my.blob/hash"
            {::hash invalid-hash})

            (not= reachable-hash-digest derived-digest)
            (materialization-failure
              target-branch-head reachable-hash-digest hash-count
              :my.blob.materialization.operation/derive-retained-set
              "the retained blob set does not match the frozen intent digest"
              {::actual-digest derived-digest})

            :else
            (let [result
                  (reduce
                    (partial materialize-hash!
                             effects source-view destination-dir
                             target-branch-head reachable-hash-digest
                             hash-count)
                    {::verified-count 0
                     ::newly-materialized-count 0
                     ::repaired-count 0}
                    hashes)]
              (if (false? (::ok? result))
                result
                (merge
                  {::ok? true
                   ::target-branch-head target-branch-head
                   ::reachable-hash-digest derived-digest
                   ::hash-count hash-count}
                  result)))))
        (catch :default e
          (materialization-failure
            target-branch-head reachable-hash-digest 0
            :my.blob.materialization.operation/derive-retained-set
            (or (some-> e .-message) (str e))
            {}))))))

(defn ^:no-doc materialize-retained!
  "Internal restore boundary for exact retained blob reconstruction.

   The request carries the hashes already acquired from one immutable database
   value. This function performs no database reads or writes."
  {:malli/schema
   [:=> [:cat ::materialization-request] ::materialization-result]
   :seon.fn/agent-facing? false}
  [request]
  (materialize-retained-with-effects! request node-publication-effects))

(defn ^:no-doc materialize-retained-intent!
  "Materialize one validated portable restore identity and retained hash set.

   This is the transport adapter for [[materialize-retained!]], not another
   materializer. It requires the portable startup identity's frozen digest to
   agree before delegating any filesystem effect."
  {:malli/schema
   [:=> [:cat ::intent-materialization-request] ::materialization-result]
   :seon.fn/agent-facing? false}
  [{::keys [target-branch-head retained-hashes source-storage-view
            destination-storage-view]
    startup-identity :seon.dev.restore/startup-identity}]
  (let [frozen-digest
        (:seon.dev.restore/reachable-hash-digest startup-identity)]
    (materialize-retained!
      {::target-branch-head target-branch-head
       ::retained-hashes retained-hashes
       ::source-storage-view source-storage-view
       ::destination-storage-view destination-storage-view
       ::reachable-hash-digest frozen-digest})))

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

;;; FUNCTIONS — put! is ^:async (it AWAITS the datom write); reads are sync so
;;; they compose inside let-bindings without an await.

(declare stat) ; text refuses a binary blob by naming stat's recorded media

(defn- ^:async put-with-publication-effects!
  "Publish and project content using one immutable filesystem effect map."
  [{::keys [content media]} effects]
  (let [hash (sha256 content)
        toks (tokens/estimate content)
        {view-ok? ::ok? view ::storage-view view-error ::error}
        (validated-storage-view)]
    (if-not view-ok?
      {::ok? false ::hash hash ::error view-error}
      (let [existing (resolve-blob view hash)
            werr (cond
                   (and existing (false? (::ok? existing))) (::error existing)
                   ;; A failed directory sync happens after the final pathname
                   ;; exists. Retry must synchronize that verified writable
                   ;; file and shard rather than treating presence as durable.
                   (and existing
                        (.existsSync nfs
                                     (blob-path (::writable-dir view) hash)))
                   (publish! effects (::writable-dir view) hash content false)
                   existing nil
                   :else (publish! effects (::writable-dir view) hash content false))]
        (if werr
          {::ok? false ::hash hash ::error werr}
          (let [report
                (await
                 ((::transact! effects)
                  {::db/tx-data [(cond-> {::hash hash
                                          ::tokens toks
                                          ::at (js/Date.)}
                                   media (assoc ::media media))]}))]
            (if-not (:seon.error/message report)
              {::ok? true ::hash hash ::tokens toks}
              {::ok? false
               ::hash hash
               ::error (or (:seon.error/message report)
                           "blob file written but the projection tx was rejected")})))))))

(defn ^{:async true :seon.fn/agent-facing? true} put!
  "Save a long text durably; read it back page by page later.

   Persists `:my.blob/content` content-addressed and records its DB
   projection. The file write is IDEMPOTENT — same content ⇒ same hash
   ⇒ no rewrite, and the datom row UPSERTS on the hash identity (never
   a duplicate).
   Serialize data yourself first (`pr-str` for edn); an optional
   `:my.blob/media` keyword hints what the bytes are (:markdown :edn …).

   One eval form reliably carries only ~2K tokens (~100 lines) of
   literal content — larger pastes truncate mid-form. For big content,
   put! it in chunks, then [[concat!]] the hashes into ONE canonical blob.

   Resolves to `{:my.blob/ok? true :my.blob/hash h :my.blob/tokens n}` —
   store the HASH on your own entity (a ref-by-value pointer); never
   re-carry the content in datoms."
  {:malli/schema [:=> [:cat ::put-request] ::put-response]}
  [request]
  (await (put-with-publication-effects! request node-publication-effects)))

(defn ^:seon.fn/agent-facing? get
  "Fetch a stored text's full content by hash, for use in code.

   Sync, for CODE, never for your reply:
   bind the content and process it with fns; to SHOW a slice, use the
   paged [[text]]. Returns
   `{:my.blob/ok? true :my.blob/hash h :my.blob/content s :my.blob/tokens n}`
   or the not-found/bad-hash error value."
  {:malli/schema [:=> [:cat ::get-request] ::get-response]}
  [{::keys [hash]}]
  (cond
    (not (valid-hash? hash))
    (bad-hash hash)

    :else
    (let [{view-ok? ::ok? view ::storage-view view-error ::error}
          (validated-storage-view)]
      (if-not view-ok?
        {::ok? false ::hash hash ::error view-error}
        (if-let [{read-ok? ::ok? content ::content :as result}
                 (resolve-blob view hash)]
          (if read-ok?
            {::ok? true
             ::hash hash
             ::content content
             ::tokens (tokens/estimate content)}
            result)
          (not-found hash))))))

(defn- ^:async concat-with-effects!
  [{::keys [hashes media]} effects]
  (let [reads (mapv (fn [h] (get {::hash h})) hashes)]
    (if-let [bad (first (remove ::ok? reads))]
      (select-keys bad [::ok? ::hash ::error])
      (await
       (put-with-publication-effects!
        (cond-> {::content (apply str (map ::content reads))}
          media (assoc ::media media))
        effects)))))

(defn ^{:async true :seon.fn/agent-facing? true} concat!
  "Join stored blobs, in order, into ONE new canonical blob.

   Takes `:my.blob/hashes` — existing put! hashes, in order — reads
   them, and stores their concatenation as a NEW content-addressed blob,
   so `:my.blob/tokens` and [[text]]'s `:my.blob/total-lines` are honest
   for the WHOLE document after content had to land as [[put!]] chunks.
   Idempotent like put!: same chunk set ⇒ same hash. A missing or
   malformed hash returns an error value NAMING that hash; nothing is
   written. The source chunks stay stored (append-only, no GC)."
  {:malli/schema [:=> [:cat ::concat-request] ::put-response]}
  [request]
  (await (concat-with-effects! request node-publication-effects)))

(declare stat-with-effects!)

(defn- ^:async text-with-effects!
  [{::keys [hash from-line max-lines] :as request} effects]
  (let [{ok? ::ok? :as env} (get {::hash hash})]
    (cond
      (not ok?)
      (select-keys env [::ok? ::hash ::error])

      (not (text-content? (::content env)))
      (let [media (::media (await (stat-with-effects!
                                   (select-keys request [::hash ::db/db])
                                   effects)))]
        (cond-> {::ok? false
                 ::hash hash
                 :seon.error/message "binary blob — not pageable as text"}
          media (assoc :seon.error/data {::media media})))

      :else
      (merge {::ok? true
              ::hash hash
              ::tokens (::tokens env)}
             (page-lines (::content env) from-line
                         (or max-lines default-max-lines))))))

(defn ^{:async true :seon.fn/agent-facing? true} text
  "Read a stored blob page by page, as a bounded line window.

   Resolves with honest totals, never the whole document at once.
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
  [request]
  (await (text-with-effects! request node-database-effects)))

(defn- ^:async stat-with-effects!
  [{::keys [hash] :as request} effects]
  (let [database (or (::db/db request) (await ((::current-db! effects))))]
    (if (:seon.error/message database)
      {::ok? false ::hash hash ::exists? false
       ::error (:seon.error/message database)}
      (let [projection
            (await
             ((::query! effects)
              {::db/db database
               ::db/query
               '[:find [?tokens ?media ?at]
                 :in $ ?hash
                 :where
                 [?entity :my.blob/hash ?hash]
                 [?entity :my.blob/tokens ?tokens]
                 [?entity :my.blob/at ?at]
                 [(get-else $ ?entity :my.blob/media nil) ?media]]
               ::db/args [hash]}))]
        (cond
          (:seon.error/message projection)
          {::ok? false ::hash hash ::exists? false
           ::error (:seon.error/message projection)}

          (nil? projection)
          {::ok? true ::hash hash ::exists? false}

          :else
          (let [[tokens media at] projection]
            (cond-> {::ok? true
                     ::hash hash
                     ::exists? true
                     ::tokens tokens
                     ::at at}
              media (assoc ::media media))))))))

(defn ^{:async true :seon.fn/agent-facing? true} stat
  "Check whether a blob exists, and its size, without reading it.

   The blob's DB projection — exists?, tokens, media, at; no disk
   touched. `exists?` answers \"is this hash recorded?\" — a missing hash is
   `{:my.blob/ok? true :my.blob/exists? false}`, an answer, not an error.
   Budget on `:my.blob/tokens` BEFORE reading: page a big blob with
   [[text]] instead of pulling it whole. Pass `:seon.db/db` to keep related
   reads on one immutable database value."
  {:malli/schema [:=> [:cat ::stat-request] ::stat-response]}
  [request]
  ;; FIND by attribute presence (never a lookup-ref here: on a store no
  ;; put! has touched yet the attr isn't installed and a lookup-ref throws;
  ;; a query just returns nothing).
  (await (stat-with-effects! request node-database-effects)))
