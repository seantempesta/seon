(ns my.blob-test
  "Contract tests for the content-addressed blob store (my.blob):

     1. put → stat → text/get ROUNDTRIP — the file lands under
        <dir>/<first-2>/<hash>, the datom projection carries
        hash/tokens/media/at, and both read verbs return the content.
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

   Hermetic: blobs go to a pid-scoped tmp dir (my.blob/!dir is re-pointed
   for the run and restored after — a shared dir would let a concurrent
   suite skew the file-count assertions), and every test gets a fresh
   :memory conn root-set! as db/*conn* (a `binding` would pop at the
   first await — see my.kb-test for the pattern's full rationale)."
  (:require
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

(defonce ^:private !saved-dir (atom nil))

(use-fixtures :once
  {:before (fn []
             (reset! !saved-dir @blob/!dir)
             (reset! blob/!dir fixture-dir)
             (.rmSync nfs fixture-dir #js {:recursive true :force true}))
   :after  (fn []
             (reset! blob/!dir @!saved-dir)
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
                 (-> (d/transact! conn {:tx-data (into (db/malli->datahike-schema
                                                         client/agent-bootstrap-attrs)
                                                       (db/tx-meta-datahike-schema))})
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

;; ---------------------------------------------------------------------------
;; 2. Idempotent double-put — one file, one datom row.
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
;; 3. Paging honesty — window math + the default cap.
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

;; ---------------------------------------------------------------------------
;; 4. concat! — chunked put!s assemble into ONE canonical blob.
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
;; 5. Errors are values — absent + malformed hashes.
;; ---------------------------------------------------------------------------

(def ^:private absent-hash (apply str (repeat 64 "0")))

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
