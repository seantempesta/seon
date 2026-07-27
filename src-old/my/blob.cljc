(ns my.blob
  "Store and retrieve large content by its SHA-256 address.

   This namespace is the agent-facing disk tier for content too large for
   database datoms. It covers durable publication, chunk assembly, metadata,
   bounded text reads, and full-content access through one writable archive
   with optional ordered read-only bases. Operations use data envelopes and
   leave only compact hashes and projections in the database."
  #?(:clj (:refer-clojure :exclude [get await])
     :cljs (:refer-clojure :exclude [get]))
  (:require
    [my.blob.core :as core]
    [my.blob.schema]
    [seon.db :as db]
    #?(:cljs [seon.dev.restore.schema])
    [seon.schema :as schema]))

#?(:clj (defmacro await [value] value))

(declare put! get concat! text stat)

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
  [:map {:closed true}
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
  [:map {:closed true}
   [::hashes ::hashes]
   [::media  {:optional true} ::media]])

(schema/register! ::get-request
  [:map {:closed true} [::hash ::hash]])

(schema/register! ::get-response
  [:map
   [::ok?     ::ok?]
   [::hash    ::hash]
   [::content {:optional true} ::content]
   [::tokens  {:optional true} ::tokens]
   [::error   {:optional true} ::error]])

(schema/register! ::text-request
  [:map {:closed true}
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
  [:map {:closed true}
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

(def default-max-lines
  "Default [[text]] page size — a blob can be huge and the page renders
   into context, so an unbounded read is never the default."
  100)

(def ^:dynamic *leaf* nil)

(defn bind-leaf
  "Return agent-facing `blob/` functions closed over one platform leaf."
  [platform-leaf]
  (into {}
        (map (fn [v] [(symbol (name (:name (meta v))))
                      (with-meta
                        (fn [& args]
                          (binding [*leaf* platform-leaf] (apply @v args)))
                        (meta v))])
             [#'put! #'get #'concat! #'text #'stat])))

(defn- leaf-fn [key]
  (or (when (contains? *leaf* key) (*leaf* key))
      (fn [& _]
        {:my.blob/ok? false
         :my.blob/error "No blob platform leaf is bound."})))

(defn ^:no-doc configure-storage-view!
  "Replace the process-local blob storage view and return the prior view."
  {:malli/schema [:=> [:cat ::storage-view] ::storage-view]}
  [view]
  ((leaf-fn ::configure-storage-view!) view))

(defn ^:no-doc observe-retained
  "Observe one immutable database value's bounded `blob/` set identity."
  {:malli/schema
   [:=> [:cat ::retained-observation-request]
    ::retained-observation-result]}
  [request]
  (core/observe-retained request))

(defn ^:no-doc materialize-retained!
  "Internal restore boundary for exact retained blob reconstruction.

   The request carries the hashes already acquired from one immutable database
   value. This function performs no database reads or writes."
  {:malli/schema
   [:=> [:cat ::materialization-request] ::materialization-result]}
  [request]
  ((leaf-fn ::materialize-retained!) request))

(defn ^:no-doc materialize-retained-intent!
  "Materialize one validated portable restore identity and retained hash set.

   This is the transport adapter for [[materialize-retained!]], not another
   materializer. It requires the portable startup identity's frozen digest to
   agree before delegating any filesystem effect."
  {:malli/schema
   [:=> [:cat ::intent-materialization-request] ::materialization-result]}
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

(defn text-content?
  "Whether `content` reads as text rather than binary — a COMPUTED sniff.

   NOT a media allowlist: `::media` is an optional hint and any hand-kept
   name set of text-vs-binary types would drift. A binary blob (a PNG,
   audio, an archive) read back as UTF-8 carries NUL (`\\u0000`) and U+FFFD
   replacement chars from the invalid byte sequences; real text carries
   neither. Scans a bounded prefix so a huge blob is never fully walked."
  [content]
  (core/text-content? content))

(defn page-lines
  "Slice `content` to a 1-based line window with honest totals.

   Mirrors seon.agent.fs paging: a trailing newline's empty pseudo-line is
   dropped so `total-lines` matches what an editor shows, and
   `lines-returned` < `max-lines` means you ran off the end."
  [content from-line max-lines]
  (core/page-lines content from-line max-lines))

;;; FUNCTIONS — the JVM leaf owns effects; reads compose as ordinary values.

(declare stat) ; text refuses a binary blob by naming stat's recorded media

(defn ^{:async false
        :seon.capability/effect :idempotent} put!
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

   Returns `{:my.blob/ok? true :my.blob/hash h :my.blob/tokens n}` —
   store the HASH on your own entity (a ref-by-value pointer); never
   re-carry the content in datoms."
  {:malli/schema [:=> [:cat ::put-request] ::put-response]}
  [request]
  ((leaf-fn ::put!) request))

(defn ^{:seon.capability/effect :read} get
  "Fetch a stored text's full content by hash, for use in code.

   Sync, for CODE, never for your reply:
   bind the content and process it with fns; to SHOW a slice, use the
   paged [[text]]. Returns
   `{:my.blob/ok? true :my.blob/hash h :my.blob/content s :my.blob/tokens n}`
   or the not-found/bad-hash error value."
  {:malli/schema [:=> [:cat ::get-request] ::get-response]}
  [request]
  ((leaf-fn ::get) request))

(defn- ^:async concat-with-effects!
  [{::keys [hashes media]} effects]
  (let [reads (mapv (fn [h] (get {::hash h})) hashes)]
    (if-let [bad (first (remove ::ok? reads))]
      (select-keys bad [::ok? ::hash ::error])
      (await
       ((leaf-fn ::put!)
        (cond-> {::content (core/concatenate (mapv ::content reads))}
          media (assoc ::media media)))))))

(defn ^{:async false
        :seon.capability/effect :idempotent} concat!
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
  (concat-with-effects! request *leaf*))

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

(defn ^{:async false :seon.capability/effect :read} text
  "Read a stored blob page by page, as a bounded line window.

   Returns honest totals, never the whole document at once.
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
  (text-with-effects! request *leaf*))

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
                 [(get-else $ ?entity :my.blob/media
                            :my.blob.media/absent) ?media]]
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
              (not= :my.blob.media/absent media) (assoc ::media media))))))))

(defn ^{:async false :seon.capability/effect :read} stat
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
  (stat-with-effects! request *leaf*))
