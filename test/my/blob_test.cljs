(ns my.blob-test
  "Contract tests for the content-addressed blob store (my.blob):

     1. put → stat → text/get ROUNDTRIP — the file lands under
        <dir>/<first-2>/<hash>, the datom projection carries
        hash/tokens/media/at, and both read functions return the content.
     2. IDEMPOTENCE — double-put of identical content yields one file and
        ONE datom row (identity upsert), same hash both times.
     3. PAGING HONESTY — text windows are 1-based with honest
        total-lines/lines-returned; an unpaged read is capped at
        default-max-lines, never the whole blob.
     4. CONCAT — chunked put!s assemble into ONE canonical blob whose
        hash equals put! of the joined string (content-addressing), with
        honest whole-doc totals, seam-spanning pages, and idempotence.
     5. ERRORS ARE VALUES — a well-formed-but-absent hash and a malformed
        hash both return error envelopes (get/text/concat!, naming the
        offending hash) or exists? false (stat); nothing throws.

   Hermetic: the blob storage view uses a pid-scoped writable dir for the run
   and is restored after — a shared dir would let a concurrent suite skew the
   file-count assertions — and every test gets a fresh
   :memory conn root-set! as db/*conn* (a `binding` would pop at the
   first await — see my.kb-test for the pattern's full rationale)."
  (:require
    ["node:crypto" :as crypto]
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [cljs.test :refer [deftest is async use-fixtures]]
    [clojure.string :as str]
    [datahike.api :as d]
    [my.blob :as blob]
    [seon.ai.tokens :as tokens]
    [seon.client :as client]
    [seon.db :as db]))

;; ---------------------------------------------------------------------------
;; Fixtures — pid-scoped blob dir + fresh :memory conn per test.
;; ---------------------------------------------------------------------------

(def ^:private fixture-dir
  (.resolve npath (str "tmp/blob-test-" (.-pid js/process))))

(def ^:private absent-hash (apply str (repeat 64 "0")))

(defn- storage-view
  [writable-dir & read-only-dirs]
  {:my.blob/writable-dir writable-dir
   :my.blob/read-only-dirs (vec read-only-dirs)})

(defonce ^:private !saved-storage-view (atom nil))

(def ^:private put-with-publication-effects!
  @#'blob/put-with-publication-effects!)

(def ^:private node-publication-effects
  @#'blob/node-publication-effects)

(use-fixtures :once
  {:before (fn []
             (reset! !saved-storage-view @blob/!storage-view)
             (reset! blob/!storage-view (storage-view fixture-dir))
             (.rmSync nfs fixture-dir #js {:recursive true :force true}))
   :after  (fn []
             (reset! blob/!storage-view @!saved-storage-view)
             (.rmSync nfs fixture-dir #js {:recursive true :force true}))})

(defn- fresh-conn
  "Promise of a fresh :memory conn carrying the pod's boot schema —
   :my.blob/* is NOT pre-installed; transact! lazy-installs it on the
   first put! (the same path the live pod takes)."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (db/ensure-provenance! {:seon.db/conn conn})
                     (.then (fn [_]
                              (d/transact!
                                conn
                                {:tx-data (into (db/malli->datahike-schema
                                                  client/agent-bootstrap-attrs)
                                                (db/tx-meta-datahike-schema))})))
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Fresh conn set! as the ROOT db/*conn* for `body` (conn → Promise);
   prior root restored after (root set!, not binding — CLJS dynamic
   bindings don't survive await)."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- pinned
  "Re-pin `conn` as the root db/*conn* before running `f` — a concurrent
   test's fiber may have set! the shared root during the preceding await."
  [conn f]
  (fn [x] (set! db/*conn* conn) (f x)))

(defn- run-test
  [chain done]
  (-> (with-conn (fn [conn] (chain conn)))
      (.then (fn [_] (done)))
      (.catch (fn [e] (is false (str "threw — " e)) (done)))))

(defn- effects-with
  "The production publication effects with explicit test replacements."
  [& replacements]
  (apply assoc node-publication-effects replacements))

(defn- put-with-effects!
  "Publish `content` through explicit immutable filesystem effects."
  [content effects]
  (put-with-publication-effects! {:my.blob/content content} effects))

;; ---------------------------------------------------------------------------
;; 1. put → stat → text/get roundtrip.
;; ---------------------------------------------------------------------------

(def ^:private small-content "# Report\n\nline three\nline four\n")

(deftest put-stat-text-roundtrip
  (async done
    (run-test
      (fn [conn]
        (-> (blob/put! {:my.blob/content small-content
                        :my.blob/media   :markdown})
            (.then (pinned conn
                     (fn [{:my.blob/keys [ok? hash tokens]}]
                       (is (true? ok?) "put! resolves ok")
                       (is (some? (re-matches #"[0-9a-f]{64}" hash))
                           "the hash IS the name — sha-256 hex")
                       (is (= (tokens/estimate small-content) tokens)
                           "tokens come from the ONE estimator")
                       (is (.existsSync nfs (.join npath fixture-dir
                                                   (subs hash 0 2) hash))
                           "file lands at <dir>/<first-2-of-hash>/<hash>")
                       (is (= [hash]
                              (vec (.readdirSync
                                     nfs
                                     (.join npath fixture-dir
                                            (subs hash 0 2)))))
                           "atomic publication leaves no .new file")
                       ;; stat — the datom projection, no disk
                       (let [s (blob/stat {:my.blob/hash hash})]
                         (is (true? (:my.blob/ok? s)))
                         (is (true? (:my.blob/exists? s)))
                         (is (= tokens (:my.blob/tokens s)))
                         (is (= :markdown (:my.blob/media s))
                             "the media hint rides the datom")
                         (is (inst? (:my.blob/at s)) ":my.blob/at stamped"))
                       ;; get — full content, for code
                       (let [g (blob/get {:my.blob/hash hash})]
                         (is (true? (:my.blob/ok? g)))
                         (is (= small-content (:my.blob/content g))
                             "get returns the exact bytes back"))
                       ;; text — the small blob fits one default page
                       (let [t (blob/text {:my.blob/hash hash})]
                         (is (true? (:my.blob/ok? t)))
                         (is (= 4 (:my.blob/total-lines t)))
                         (is (= 4 (:my.blob/lines-returned t)))
                         (is (str/includes? (:my.blob/content t) "line three"))))))))
      done)))

(deftest rename-failure-leaves-no-final-path-and-retry-converges
  (async done
    (run-test
      (fn [conn]
        (let [content "rename failure is retryable"
              hash (-> (.createHash crypto "sha256")
                       (.update content "utf8")
                       (.digest "hex"))
              shard (.join npath fixture-dir (subs hash 0 2))
              path (.join npath shard hash)]
          (-> (put-with-effects!
                content
                (effects-with
                  ::blob/atomic-rename!
                  (fn [& _]
                    (throw (js/Error. "injected rename failure")))))
              (.then (pinned conn
                       (fn [failed]
                         (is (false? (:my.blob/ok? failed)))
                         (is (str/includes? (:my.blob/error failed)
                                            "injected rename failure"))
                         (is (not (.existsSync nfs path))
                             "failure before rename publishes no final path")
                         (is (or (not (.existsSync nfs shard))
                                 (empty? (.readdirSync nfs shard)))
                             "the unique temporary file is reclaimed")
                         (blob/put! {:my.blob/content content}))))
              (.then (pinned conn
                       (fn [retried]
                         (is (true? (:my.blob/ok? retried)))
                         (is (= hash (:my.blob/hash retried)))
                         (is (= content (.readFileSync nfs path "utf8"))
                             "retry publishes complete hash-verifiable bytes")))))))
      done)))

(deftest missing-writable-root-sync-failure-precedes-shard-and-retries
  (async done
    (run-test
      (fn [conn]
        (let [writable-dir (.join npath fixture-dir "new-writable")
              content "first shard needs its parent directory durable"
              hash (-> (.createHash crypto "sha256")
                       (.update content "utf8")
                       (.digest "hex"))
              shard (.join npath writable-dir (subs hash 0 2))
              path (.join npath shard hash)
              !first-syncs (atom 0)
              !retry-syncs (atom 0)]
          (.rmSync nfs writable-dir #js {:recursive true :force true})
          (reset! blob/!storage-view (storage-view writable-dir))
          (-> (put-with-effects!
                content
                (effects-with
                  ::blob/sync-file-descriptor!
                  (fn [fd]
                    (let [call (swap! !first-syncs inc)]
                      (if (= 2 call)
                        (throw (js/Error.
                                 "injected writable-parent sync failure"))
                        (.fsyncSync nfs fd))))))
              (.then (pinned conn
                       (fn [failed]
                         (is (= 2 @!first-syncs)
                             "the new writable root is synced in its parent")
                         (is (false? (:my.blob/ok? failed)))
                         (is (str/includes? (:my.blob/error failed)
                                            "injected writable-parent sync failure"))
                         (is (not (.existsSync nfs path))
                             "parent-sync failure precedes temporary bytes")
                         (is (.existsSync nfs writable-dir)
                             "the complete writable directory may remain")
                         (is (not (.existsSync nfs shard))
                             "the shard is not created past the failed fence")
                         (put-with-effects!
                           content
                           (effects-with
                             ::blob/sync-file-descriptor!
                             (fn [fd]
                               (swap! !retry-syncs inc)
                               (.fsyncSync nfs fd)))))))
              (.then (pinned conn
                       (fn [retried]
                         (is (true? (:my.blob/ok? retried)))
                         (is (= 5 @!retry-syncs)
                             "retry repairs root, creates shard, then publishes")
                         (is (= content (.readFileSync nfs path "utf8"))))))
              (.finally
                (fn []
                  (reset! blob/!storage-view (storage-view fixture-dir)))))))
      done)))

(deftest directory-sync-failure-keeps-complete-bytes-and-retry-resyncs
  (async done
    (run-test
      (fn [conn]
        (let [writable-dir (.join npath fixture-dir "directory-sync-writable")
              content "rename succeeded but directory sync failed"
              hash (-> (.createHash crypto "sha256")
                       (.update content "utf8")
                       (.digest "hex"))
              path (.join npath writable-dir (subs hash 0 2) hash)
              !first-syncs (atom 0)
              !retry-syncs (atom 0)
              !retry-renames (atom 0)]
          (.rmSync nfs writable-dir #js {:recursive true :force true})
          (.mkdirSync nfs writable-dir #js {:recursive true})
          (reset! blob/!storage-view (storage-view writable-dir))
          (-> (put-with-effects!
                content
                (effects-with
                  ::blob/sync-file-descriptor!
                  (fn [fd]
                    (let [call (swap! !first-syncs inc)]
                      (if (= 5 call)
                        (throw (js/Error.
                                 "injected directory sync failure"))
                        (.fsyncSync nfs fd))))))
              (.then (pinned conn
                       (fn [failed]
                         (is (= 5 @!first-syncs)
                             "publication syncs directory chain, file, then shard")
                         (is (false? (:my.blob/ok? failed)))
                         (is (str/includes? (:my.blob/error failed)
                                            "injected directory sync failure"))
                         (is (= content (.readFileSync nfs path "utf8"))
                             "post-rename failure leaves complete final bytes")
                         (is (false? (:my.blob/exists?
                                      (blob/stat {:my.blob/hash hash})))
                             "failed durability does not license the DB projection")
                         (put-with-effects!
                           content
                           (effects-with
                             ::blob/atomic-rename!
                             (fn [from to]
                               (swap! !retry-renames inc)
                               (.renameSync nfs from to))
                             ::blob/sync-file-descriptor!
                             (fn [fd]
                               (swap! !retry-syncs inc)
                               (.fsyncSync nfs fd)))))))
              (.then (pinned conn
                       (fn [retried]
                         (is (true? (:my.blob/ok? retried)))
                         (is (= 4 @!retry-syncs)
                             "retry re-syncs directories, file, and shard")
                         (is (zero? @!retry-renames)
                             "retry never replaces an existing verified blob")
                         (is (true? (:my.blob/exists?
                                     (blob/stat {:my.blob/hash hash})))
                             "projection follows directory-durable retry")
                         (is (= content
                                (:my.blob/content
                                  (blob/get {:my.blob/hash hash})))))))
              (.finally
                (fn []
                  (reset! blob/!storage-view (storage-view fixture-dir)))))))
      done)))

;; ---------------------------------------------------------------------------
;; 2. Storage view — writable overlay + ordered read-only bases.
;; ---------------------------------------------------------------------------

(deftest storage-view-reads-base-without-copying-it
  (async done
    (run-test
      (fn [conn]
        (let [base-dir (.join npath fixture-dir "base")
              overlay-dir (.join npath fixture-dir "overlay")
              content "source-era blob"]
          (reset! blob/!storage-view (storage-view base-dir))
          (-> (blob/put! {:my.blob/content content})
              (.then (pinned conn
                       (fn [{hash :my.blob/hash}]
                         (reset! blob/!storage-view
                                 (storage-view overlay-dir base-dir))
                         (let [read (blob/get {:my.blob/hash hash})]
                           (is (true? (:my.blob/ok? read)))
                           (is (= content (:my.blob/content read))
                               "overlay miss falls through to the source base"))
                         (blob/put! {:my.blob/content content}))))
              (.then (pinned conn
                       (fn [{hash :my.blob/hash ok? :my.blob/ok?}]
                         (is (true? ok?))
                         (is (not (.existsSync
                                    nfs
                                    (.join npath overlay-dir
                                           (subs hash 0 2) hash)))
                             "existing base content dedupes without an overlay copy"))))
              (.finally
                (fn []
                  (reset! blob/!storage-view (storage-view fixture-dir)))))))
      done)))

(deftest corrupt-overlay-does-not-fall-through-to-a-valid-base
  (async done
    (run-test
      (fn [conn]
        (let [base-dir (.join npath fixture-dir "corrupt-base")
              overlay-dir (.join npath fixture-dir "corrupt-overlay")
              content "valid source bytes"]
          (reset! blob/!storage-view (storage-view base-dir))
          (-> (blob/put! {:my.blob/content content})
              (.then (pinned conn
                       (fn [{hash :my.blob/hash}]
                         (let [overlay-path (.join npath overlay-dir
                                                   (subs hash 0 2) hash)]
                           (.mkdirSync nfs (.dirname npath overlay-path)
                                       #js {:recursive true})
                           (.writeFileSync nfs overlay-path "wrong bytes" "utf8")
                           (reset! blob/!storage-view
                                   (storage-view overlay-dir base-dir))
                           (let [read (blob/get {:my.blob/hash hash})]
                             (is (false? (:my.blob/ok? read)))
                             (is (str/includes? (:my.blob/error read)
                                                "integrity failure")
                                 "corruption is loud instead of hidden by fallback"))))))
              (.finally
                (fn []
                  (reset! blob/!storage-view (storage-view fixture-dir)))))))
      done)))

(deftest storage-view-rejects-overlapping-directories
  (async done
    (run-test
      (fn [_conn]
        (reset! blob/!storage-view (storage-view fixture-dir fixture-dir))
        (try
          (let [read (blob/get {:my.blob/hash absent-hash})]
            (is (false? (:my.blob/ok? read)))
            (is (str/includes? (:my.blob/error read) "must be distinct")
                "one directory cannot be both writable and read-only"))
          (finally
            (reset! blob/!storage-view (storage-view fixture-dir))))
        js/undefined)
      done)))

;; ---------------------------------------------------------------------------
;; 3. Idempotent double-put — one file, one datom row.
;; ---------------------------------------------------------------------------

(deftest double-put-is-idempotent
  (async done
    (run-test
      (fn [conn]
        (let [content "same bytes, put twice"
              !first  (atom nil)]
          (-> (blob/put! {:my.blob/content content})
              (.then (pinned conn
                       (fn [{h1 :my.blob/hash ok1 :my.blob/ok?}]
                         (is (true? ok1))
                         (reset! !first h1)
                         (blob/put! {:my.blob/content content}))))
              (.then (pinned conn
                       (fn [{h2 :my.blob/hash ok2 :my.blob/ok?}]
                         (is (true? ok2) "second put is a no-op success")
                         (is (= @!first h2) "identical content ⇒ identical hash")
                         (is (= 1 (count (.readdirSync
                                           nfs
                                           (.join npath fixture-dir (subs h2 0 2)))))
                             "one file in the shard dir — no rewrite, no copy")
                         (is (= 1 (count (db/query
                                           '[:find ?e :where [?e :my.blob/hash _]])))
                             "identity upsert — ONE datom row, never a duplicate")))))))
      done)))

;; ---------------------------------------------------------------------------
;; 4. Paging honesty — window math + the default cap.
;; ---------------------------------------------------------------------------

(deftest text-pages-with-honest-totals
  (async done
    (run-test
      (fn [conn]
        (let [content (str/join "\n" (map #(str "row-" %) (range 1 151)))] ; 150 lines
          (-> (blob/put! {:my.blob/content content})
              (.then (pinned conn
                       (fn [{:my.blob/keys [hash]}]
                         ;; default read is CAPPED — never the whole blob
                         (let [t (blob/text {:my.blob/hash hash})]
                           (is (= 150 (:my.blob/total-lines t)) "totals are the whole blob")
                           (is (= blob/default-max-lines (:my.blob/lines-returned t))
                               "unpaged read caps at default-max-lines")
                           (is (str/starts-with? (:my.blob/content t) "row-1\n"))
                           (is (not (str/includes? (:my.blob/content t) "row-150"))
                               "the tail did NOT come back by default"))
                         ;; explicit window — 1-based, exact
                         (let [t (blob/text {:my.blob/hash hash
                                             :my.blob/from-line 5
                                             :my.blob/max-lines 3})]
                           (is (= "row-5\nrow-6\nrow-7" (:my.blob/content t)))
                           (is (= 5 (:my.blob/from-line t)))
                           (is (= 3 (:my.blob/lines-returned t)))
                           (is (= 150 (:my.blob/total-lines t))))
                         ;; run off the end — lines-returned < max-lines, honestly
                         (let [t (blob/text {:my.blob/hash hash
                                             :my.blob/from-line 149
                                             :my.blob/max-lines 10})]
                           (is (= 2 (:my.blob/lines-returned t))
                               "asked for 10, only 2 remained — the shortfall is visible")
                           (is (= "row-149\nrow-150" (:my.blob/content t))))))))))
      done)))

(deftest text-refuses-a-binary-blob
  (async done
    (run-test
      (fn [conn]
        ;; A PNG signature + IHDR carries NUL bytes and (as UTF-8) replacement
        ;; chars — text/ must refuse it as a not-text envelope naming the
        ;; recorded media, NOT return latin1 mojibake with ok? true.
        (let [png (str "PNG\r\n\n"
                       (js/String.fromCharCode 0 0 0 13) "IHDR"
                       (js/String.fromCharCode 0 0 0 1 0 0 0 1))]
          (-> (blob/put! {:my.blob/content png :my.blob/media :png})
              (.then (pinned conn
                       (fn [{:my.blob/keys [hash]}]
                         (let [t (blob/text {:my.blob/hash hash})]
                           (is (false? (:my.blob/ok? t)) "binary blob refuses")
                           (is (str/includes? (:seon.error/message t)
                                              "binary blob")
                               "honest not-text message")
                           (is (= :png (get-in t [:seon.error/data :my.blob/media]))
                               "error data names the recorded media")
                           (is (nil? (:my.blob/content t))
                               "no mojibake content leaks"))
                         ;; get still reaches the bytes (unchanged)
                         (is (true? (:my.blob/ok? (blob/get {:my.blob/hash hash})))
                             "get/stat still reach a binary blob")))))))
      done)))

;; ---------------------------------------------------------------------------
;; 5. concat! — chunked put!s assemble into ONE canonical blob.
;; ---------------------------------------------------------------------------

(def ^:private chunk-1 "part one line 1\npart one line 2\n")
(def ^:private chunk-2 "part two line 1\npart two line 2\n")
(def ^:private chunk-3 "part three line 1")
(def ^:private joined  (str chunk-1 chunk-2 chunk-3))

(deftest concat-yields-the-canonical-hash-with-honest-totals
  (async done
    (run-test
      (fn [conn]
        (let [!hashes (atom [])
              !concat (atom nil)
              stash!  (fn [next] (fn [{h :my.blob/hash}]
                                   (swap! !hashes conj h) (next)))]
          (-> (blob/put! {:my.blob/content chunk-1})
              (.then (pinned conn (stash! #(blob/put! {:my.blob/content chunk-2}))))
              (.then (pinned conn (stash! #(blob/put! {:my.blob/content chunk-3}))))
              (.then (pinned conn (stash! #(blob/concat! {:my.blob/hashes @!hashes}))))
              (.then (pinned conn
                       (fn [{:my.blob/keys [ok? hash tokens]}]
                         (is (true? ok?) "concat! resolves ok")
                         (reset! !concat hash)
                         (is (not (contains? (set @!hashes) hash))
                             "the whole is a NEW blob, not one of the chunks")
                         (is (= joined (:my.blob/content
                                        (blob/get {:my.blob/hash hash})))
                             "content is the exact in-order concatenation")
                         (is (= (tokens/estimate joined) tokens)
                             "tokens cover the WHOLE, from the one estimator")
                         ;; honest totals + a window spanning a chunk seam
                         (let [t (blob/text {:my.blob/hash hash})]
                           (is (= 5 (:my.blob/total-lines t))
                               "total-lines spans every chunk"))
                         (let [t (blob/text {:my.blob/hash hash
                                             :my.blob/from-line 2
                                             :my.blob/max-lines 2})]
                           (is (= "part one line 2\npart two line 1"
                                  (:my.blob/content t))
                               "a page reads straight across the seam"))
                         ;; content-addressing proof: put! of the joined
                         ;; string IS the same blob — same hash, still one row
                         (blob/put! {:my.blob/content joined}))))
              (.then (pinned conn
                       (fn [{h :my.blob/hash}]
                         (is (= @!concat h)
                             "concat! hash == put! of the joined string")
                         ;; idempotent double-concat — same hash again
                         (blob/concat! {:my.blob/hashes @!hashes}))))
              (.then (pinned conn
                       (fn [{h :my.blob/hash ok? :my.blob/ok?}]
                         (is (true? ok?))
                         (is (= @!concat h) "double-concat is a no-op success")
                         (is (= 1 (count (db/query
                                           '[:find ?e :in $ ?h
                                             :where [?e :my.blob/hash ?h]]
                                           h)))
                             "identity upsert — ONE row for the whole")))))))
      done)))

;; ---------------------------------------------------------------------------
;; 6. Errors are values — absent + malformed hashes.
;; ---------------------------------------------------------------------------

(deftest missing-and-malformed-hashes-are-error-values
  (async done
    (run-test
      (fn [_conn]
        ;; well-formed but nothing stored under it
        (let [g (blob/get {:my.blob/hash absent-hash})]
          (is (false? (:my.blob/ok? g)))
          (is (string? (:my.blob/error g)) "not-found is a guiding value, no throw"))
        (let [t (blob/text {:my.blob/hash absent-hash})]
          (is (false? (:my.blob/ok? t)))
          (is (string? (:my.blob/error t))))
        (is (= {:my.blob/ok? true :my.blob/hash absent-hash :my.blob/exists? false}
               (blob/stat {:my.blob/hash absent-hash}))
            "stat answers the QUESTION — absent is exists? false, not an error")
        ;; malformed — could never be a content address (and never touches disk)
        (let [g (blob/get {:my.blob/hash "../../../etc/passwd"})]
          (is (false? (:my.blob/ok? g)))
          (is (str/includes? (:my.blob/error g) "sha-256")
              "a malformed hash is refused BEFORE any path is built"))
        js/undefined)
      done)))

(deftest concat-of-a-missing-hash-names-that-hash
  (async done
    (run-test
      (fn [conn]
        (-> (blob/put! {:my.blob/content "the one real chunk"})
            (.then (pinned conn
                     (fn [{h :my.blob/hash}]
                       (blob/concat! {:my.blob/hashes [h absent-hash]}))))
            (.then (pinned conn
                     (fn [env]
                       (is (false? (:my.blob/ok? env)))
                       (is (= absent-hash (:my.blob/hash env))
                           "the envelope carries the OFFENDING hash")
                       (is (str/includes? (:my.blob/error env) absent-hash)
                           "the error names WHICH hash is missing")
                       (is (= 1 (count (db/query
                                         '[:find ?e :where [?e :my.blob/hash _]])))
                           "nothing was written — only the seed chunk exists"))))))
      done)))
