(ns seon.edit.jvm
  "Protected JVM handler for digest-fenced structural source edits."
  (:require [seon.edit :as edit]
            [seon.fs.jvm :as fs.jvm]))

(defn- flat-error
  [kind message data]
  {:seon.error/kind kind
   :seon.error/message message
   :seon.error/data data})

(defn- stale-source
  [request actual-digest]
  (flat-error :my.edit/stale-source
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
    (flat-error :my.edit/not-utf8
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
    (flat-error :my.edit/parse-refused
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
                             :my.edit/replacements])))

(defn- edit
  {:malli/schema
   [:=> [:cat :seon.edit/request :seon.config/effective]
    [:or :my.edit/result :seon.error/value]]}
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
