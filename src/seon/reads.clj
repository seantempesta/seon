(ns seon.reads
  "Which readers a change reaches: one index from a read key to the readers that recorded it, matched against moved datoms.
  A key is Datahike's (datahike.reads/range-key); a reader is any value its holder names it by (a component, a result).
  See docs/storage.md#what-a-read-scanned."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.reads :as dr]))

(defn add
  "idx with reader recorded under each of keys. See docs/storage.md#what-a-read-scanned."
  {:malli/schema [:=> [:cat :seon.reads/index :any :seon.reads/keys] :seon.reads/index]}
  [idx reader keys]
  (reduce (fn [m k] (update m k (fnil conj #{}) reader)) idx keys))

(defn drop-reader
  "idx without reader under each of keys (the keys it was added with); a key left with no reader goes. See docs/storage.md#what-a-read-scanned."
  {:malli/schema [:=> [:cat :seon.reads/index :any :seon.reads/keys] :seon.reads/index]}
  [idx reader keys]
  (reduce (fn [m k] (let [rs (disj (get m k #{}) reader)] (if (seq rs) (assoc m k rs) (dissoc m k)))) idx keys))

(defn readers
  "The readers of idx whose keys a datom of datoms touches; a schema datom reaches every reader. See docs/storage.md#what-a-read-scanned."
  {:malli/schema [:=> [:cat :seon.reads/index [:sequential :seon.host/datom]] [:set :any]]}
  [idx datoms]
  (reduce (fn [acc d]
            (let [ks (dr/datom-keys d)]
              (if (some #{:all} ks)
                (reduced (into #{} cat (vals idx)))
                (into acc (mapcat #(get idx %)) ks))))
          #{} datoms))
