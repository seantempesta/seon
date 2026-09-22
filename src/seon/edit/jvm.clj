(ns seon.edit.jvm
  "Protected JVM handler for digest-fenced structural source edits."
  (:require [clojure.java.io :as io]
            [seon.edit :as edit]
            [seon.fs.jvm :as fs.jvm]))

(defn- flat-error
  {:malli/schema [:=> [:cat :qualified-keyword :seon.schema/value :string :map]
                  [:or :my.edit/stale-source-error :my.edit/not-utf8-error :my.edit/parse-refused-error]]}
  [marker subject message data]
  {marker subject
   :seon.error/at (java.util.Date.)
   :seon.error/layer :my.edit/edit
   :seon.error/operation 'seon.edit.jvm/flat-error
   :seon.error/message message
   :seon.error/data data})

(defn- stale-source
  {:malli/schema [:=> [:cat :seon.edit/request :my.fs/digest]
                  :my.edit/stale-source-error]}
  [request actual-digest]
  (flat-error :my.edit/stale-source (:my.edit/path request)
              "The source file no longer has the expected digest."
              {:my.edit/path (:my.edit/path request)
               :my.edit/expected-digest (:my.edit/expected-digest request)
               :my.fs/digest actual-digest}))

(defn- edit-error
  {:malli/schema [:=> [:cat :seon.error/base
                       :seon.edit/request [:or :nil :my.fs/digest]]
                  [:or [:and :seon.error/base [:or :my.fs/path-refused-error :my.fs/not-found-error :my.fs/not-directory-error :my.fs/read-limit-error :my.fs/read-failed-error :my.fs/not-regular-file-error :my.fs/changed-during-read-error :my.fs/invalid-utf8-window-error :my.fs/write-limit-error :my.fs/write-failed-error :my.fs/blob-unavailable-error :my.fs/already-exists-error :my.fs/stale-digest-error :my.fs/atomic-write-unsupported-error]] :my.edit/stale-source-error :my.edit/not-utf8-error]]}
  [result request actual-digest]
  (cond
    (:my.fs/stale-digest result) (stale-source request
                                     (get-in result
                                             [:seon.error/data :my.fs/digest]))
    (:my.fs/invalid-utf8-window result)
    (flat-error :my.edit/not-utf8 (:my.edit/path request)
                "Structural source editing requires strict UTF-8."
                {:my.edit/path (:my.edit/path request)
                 :my.fs/digest actual-digest})
    :else result))

(defn- transform
  {:malli/schema [:=> [:cat :string :seon.edit/request [:int {:min 1}]]
                  [:or :seon.edit/candidate :my.edit/parse-refused-error
                   :my.edit/lossless-check-failed-error :my.edit/no-match-error
                   :my.edit/ambiguous-match-error]]}
  [source request context-byte-limit]
  (cond
    (contains? request :my.edit/form)
    (edit/form source request context-byte-limit)

    (contains? request :my.edit/old-string)
    (edit/exact source request context-byte-limit)

    (contains? request :my.edit/old-window)
    (edit/lines source request context-byte-limit)

    :else
    (flat-error :my.edit/parse-byte-count (alength (.getBytes source "UTF-8"))
                "The edit request does not declare one operation shape."
                {})))

(defn- result
  [request before transformed write-result]
  (merge
   {:my.edit/path (:my.edit/path request)
    :my.edit/changed? (:my.fs/changed? write-result)
    :my.edit/before-digest (:my.fs/digest before)
    :my.edit/after-digest (:my.fs/after-digest write-result)
    :my.edit/before-bytes (:my.fs/file-bytes before)
    :my.edit/after-bytes (:my.fs/bytes-written write-result)}
   (select-keys transformed [:my.edit/from-line
                             :my.edit/to-line
                             :my.edit/source-window
                             :my.edit/source-window-complete?
                             :my.edit/replacements])
   ;; WHAT THIS EDIT WROTE, IN THE UNIT THE PROGRAM GRAPH USES. The exact
   ;; changed region is computed to perform the edit and used to be
   ;; dropped here, leaving human line numbers as the only trace; a merge
   ;; then had to diff files to learn what a worker changed. The effect
   ;; writer removes this key before the agent sees the result, exactly as
   ;; it removes `:seon.blob/staged-writes`, and refs the indexed file and
   ;; the declaration whose span contains it.
   {:seon.effect/provenance
    {:seon.effect/file (.getCanonicalPath (io/file (:my.edit/path request)))
     :seon.effect/form-span (:seon.edit/form-span transformed)}}))

(defn- filesystem-refusal
  "One filesystem refusal as the typed value it already carries.

  `seon.fs.jvm`'s internals refuse by THROWING, and `my.fs`'s own handlers
  convert that back into the flat value an agent reads. This handler calls
  those internals directly (`#'fs.jvm/read-complete`, `#'fs.jvm/write`), so
  it owes the same conversion: without it a `:my.fs/path-refused` naming the
  exact path and a `:my.fs/read-limit` naming its ceiling both reach the
  agent as `:seon.effect/handler-failed`, whose whole evidence is the owner
  symbol. Anything that is NOT a classified refusal is a genuine fault and
  is rethrown to the effect boundary unchanged."
  {:malli/schema [:=> [:cat :seon.error/throwable]
                  [:and :seon.error/base [:or :my.fs/path-refused-error :my.fs/not-found-error :my.fs/not-directory-error :my.fs/read-limit-error :my.fs/read-failed-error :my.fs/not-regular-file-error :my.fs/changed-during-read-error :my.fs/invalid-utf8-window-error :my.fs/write-limit-error :my.fs/write-failed-error :my.fs/blob-unavailable-error :my.fs/already-exists-error :my.fs/stale-digest-error :my.fs/atomic-write-unsupported-error]]]}
  [throwable]
  (let [classified (ex-data throwable)]
    (if (or (:my.fs/path-refused classified) (:my.fs/not-found classified)
            (:my.fs/not-directory classified) (:my.fs/read-limit classified)
            (:my.fs/read-failed classified) (:my.fs/not-regular-file classified)
            (:my.fs/changed-during-read classified) (:my.fs/invalid-utf8-window classified)
            (:my.fs/write-limit classified) (:my.fs/write-failed classified)
            (:my.fs/blob-unavailable classified) (:my.fs/already-exists classified)
            (:my.fs/stale-digest classified) (:my.fs/atomic-write-unsupported classified))
      classified
      (throw throwable))))

(defn- edit*
  {:malli/schema [:=> [:cat :seon.edit/request :seon.config/effective]
                  [:or :my.edit/result [:or [:and :seon.error/base [:or :my.fs/path-refused-error :my.fs/not-found-error :my.fs/not-directory-error :my.fs/read-limit-error :my.fs/read-failed-error :my.fs/not-regular-file-error :my.fs/changed-during-read-error :my.fs/invalid-utf8-window-error :my.fs/write-limit-error :my.fs/write-failed-error :my.fs/blob-unavailable-error :my.fs/already-exists-error :my.fs/stale-digest-error :my.fs/atomic-write-unsupported-error]] :my.edit/stale-source-error :my.edit/not-utf8-error :my.edit/parse-refused-error :my.edit/lossless-check-failed-error :my.edit/no-match-error :my.edit/ambiguous-match-error]]]}
  [request effective]
  (let [path (:my.edit/path request)
        before (#'fs.jvm/read-complete
                {:my.fs/path path :my.fs/encoding :utf-8}
                effective)]
    (if (or (:my.fs/path-refused before) (:my.fs/not-found before)
            (:my.fs/read-limit before) (:my.fs/read-failed before)
            (:my.fs/not-regular-file before) (:my.fs/changed-during-read before)
            (:my.fs/invalid-utf8-window before))
      (edit-error before request (:my.fs/digest before))
      (let [actual-digest (:my.fs/digest before)]
        (if (not= (:my.edit/expected-digest request) actual-digest)
          (stale-source request actual-digest)
          (let [transformed
                (transform (:my.fs/text before) request
                           (:seon.config.fs/max-inline-bytes effective))]
            (if (or (:my.edit/parse-byte-count transformed)
                    (:my.edit/unverified-char-span transformed)
                    (:my.edit/no-match transformed) (:my.edit/ambiguous-match transformed))
              (update transformed :seon.error/data
                      #(assoc (or % {}) :my.fs/digest actual-digest))
              (let [written
                    (#'fs.jvm/write
                     {:my.fs/path path
                      :my.fs/content
                      {:my.fs/text (:seon.edit/source transformed)}
                      :my.fs/precondition
                      {:my.fs/expected-digest actual-digest}}
                     effective)]
                (if (or (:my.fs/path-refused written) (:my.fs/not-found written)
                        (:my.fs/read-limit written) (:my.fs/read-failed written)
                        (:my.fs/not-regular-file written) (:my.fs/changed-during-read written)
                        (:my.fs/write-limit written) (:my.fs/write-failed written)
                        (:my.fs/blob-unavailable written) (:my.fs/already-exists written)
                        (:my.fs/stale-digest written) (:my.fs/atomic-write-unsupported written))
                  (edit-error written request actual-digest)
                  (result request before transformed written))))))))))

(defn- edit
  {:malli/schema
   [:=> [:cat :seon.edit/request :seon.config/effective]
    [:or :my.edit/result :seon.error/value]]}
  [request effective]
  (try
    (edit* request effective)
    (catch clojure.lang.ExceptionInfo failure
      (filesystem-refusal failure))))
