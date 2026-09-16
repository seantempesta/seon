(ns seon.edit.jvm
  "Protected JVM handler for digest-fenced structural source edits."
  (:require [clojure.java.io :as io]
            [seon.edit :as edit]
            [seon.fs.jvm :as fs.jvm]))

(defn- flat-error
  [marker subject message data]
  {marker subject
   :seon.error/kind marker
   :seon.error/message message
   :seon.error/data data})

(defn- stale-source
  [request actual-digest]
  (flat-error :my.edit/stale-source (:my.edit/path request)
              "The source file no longer has the expected digest."
              {:my.edit/path (:my.edit/path request)
               :my.edit/expected-digest (:my.edit/expected-digest request)
               :my.fs/digest actual-digest}))

(defn- edit-error
  [result request actual-digest]
  (case (:seon.error/kind result)
    :my.fs/stale-digest (stale-source request
                                     (get-in result
                                             [:seon.error/data :my.fs/digest]))
    :my.fs/invalid-utf8-window
    (flat-error :my.edit/not-utf8 (:my.edit/path request)
                "Structural source editing requires strict UTF-8."
                {:my.edit/path (:my.edit/path request)
                 :my.fs/digest actual-digest})
    result))

(defn- transform
  [source request context-byte-limit]
  (cond
    (contains? request :my.edit/form)
    (edit/form source request context-byte-limit)

    (contains? request :my.edit/old-string)
    (edit/exact source request context-byte-limit)

    (contains? request :my.edit/old-window)
    (edit/lines source request context-byte-limit)

    :else
    (flat-error :my.edit/parse-refused true
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
  [throwable]
  (let [classified (ex-data throwable)]
    (if (and (keyword? (:seon.error/kind classified))
             (string? (:seon.error/message classified)))
      classified
      (throw throwable))))

(defn- edit*
  [request effective]
  (let [path (:my.edit/path request)
        before (#'fs.jvm/read-complete
                {:my.fs/path path :my.fs/encoding :utf-8}
                effective)]
    (if (:seon.error/kind before)
      (edit-error before request (:my.fs/digest before))
      (let [actual-digest (:my.fs/digest before)]
        (if (not= (:my.edit/expected-digest request) actual-digest)
          (stale-source request actual-digest)
          (let [transformed
                (transform (:my.fs/text before) request
                           (:seon.config.fs/max-inline-bytes effective))]
            (if (:seon.error/kind transformed)
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
                (if (:seon.error/kind written)
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
