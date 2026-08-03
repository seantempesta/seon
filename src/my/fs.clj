(ns my.fs
  "Bounded byte-honest filesystem requests."
  (:refer-clojure :exclude [read])
  (:require [clojure.test.check.generators :as gen]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(defn content?
  "True when content names exactly one byte source."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value)
       (= 1
          (count
           (filter #(contains? value %)
                   [:my.fs/text :my.fs/bytes :seon.blob/digest])))))

(defn write-precondition?
  "True when a write names exactly one current-content fence."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value)
       (= 1
          (count
           (filter #(contains? value %)
                   [:my.fs/expected-absence? :my.fs/expected-digest])))))

(def ^:private digest-generator
  (gen/fmap #(apply str %)
            (gen/vector (gen/elements (seq "0123456789abcdef")) 64)))

(def content-generator
  (gen/one-of
   [(gen/fmap (fn [text] {:my.fs/text text}) gen/string)
    (gen/fmap (fn [octets] {:my.fs/bytes octets})
              (gen/vector (gen/choose 0 255)))
    (gen/fmap (fn [digest] {:seon.blob/digest digest})
              digest-generator)]))

(def write-precondition-generator
  (gen/one-of
   [(gen/return {:my.fs/expected-absence? true})
    (gen/fmap (fn [digest] {:my.fs/expected-digest digest})
              digest-generator)]))

(defonce ^:private _content-predicate
  (schema/register-core-predicate! 'my.fs/content? content?))

(defonce ^:private _write-precondition-predicate
  (schema/register-core-predicate!
   'my.fs/write-precondition? write-precondition?))

;; The predicates must exist before seon.effect loads the complete schema
;; population, whose :my.fs/content declaration resolves these Vars.
(require '[seon.effect :as effect])

(schema.edn/load! {})

(defn read
  "Read one bounded byte window and digest through the filesystem owner."
  {:malli/schema
   [:=> [:cat :my.fs/read-request]
    [:or :my.fs/read-result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.fs.jvm/read}
  [request]
  (effect/request! #'read request))

(defn write
  "Conditionally replace one file through the filesystem owner."
  {:malli/schema
   [:=> [:cat :my.fs/write-request]
    [:or :my.fs/write-result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.fs.jvm/write}
  [request]
  (effect/request! #'write request))

(defn glob
  "Find a bounded set of paths without following symbolic links."
  {:malli/schema
   [:=> [:cat :my.fs/glob-request]
    [:or :my.fs/glob-result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.fs.jvm/glob}
  [request]
  (effect/request! #'glob request))

(defn stat
  "Read no-follow attributes for one filesystem path."
  {:malli/schema
   [:=> [:cat :my.fs/stat-request]
    [:or :my.fs/stat-result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.fs.jvm/stat}
  [request]
  (effect/request! #'stat request))
