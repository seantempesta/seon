(ns my.blob.schema
  "Define portable schemas for `my.blob` storage and restore data.

   This namespace owns the shared shapes exchanged across the blob archive,
   branch-retention, and restore boundaries. It contains schema declarations
   only; storage behavior remains in the runtime namespaces that consume them."
  (:require [seon.db.branch :as branch]
            [seon.schema :as schema]))

(schema/register! :my.blob/directory [:string {:min 1}])
(schema/register! :my.blob/writable-dir :my.blob/directory)
(schema/register! :my.blob/read-only-dirs [:vector :my.blob/directory])
(schema/register!
 :my.blob/storage-view
 [:map {:closed true}
  [:my.blob/writable-dir :my.blob/writable-dir]
  [:my.blob/read-only-dirs :my.blob/read-only-dirs]])

(schema/register! :my.blob/ok? :boolean)
(schema/register! :my.blob/digest [:re "[0-9a-f]{64}"])
(schema/register! :my.blob/target-branch-head ::branch/head)
(schema/register! :my.blob/reachable-hash-digest :my.blob/digest)
(schema/register! :my.blob/hash-count [:int {:min 0}])
(schema/register! :my.blob/error [:string {:min 1 :max 1024}])
(schema/register!
 :my.blob/retained-observation-success
 [:map {:closed true}
  [:my.blob/ok? [:= true]]
  [:my.blob/target-branch-head :my.blob/target-branch-head]
  [:my.blob/reachable-hash-digest :my.blob/reachable-hash-digest]
  [:my.blob/hash-count :my.blob/hash-count]])
(schema/register!
 :my.blob/retained-observation-failure
 [:map {:closed true}
  [:my.blob/ok? [:= false]]
  [:my.blob/target-branch-head :my.blob/target-branch-head]
  [:my.blob/error :my.blob/error]])
(schema/register!
 :my.blob/retained-observation-result
 [:or :my.blob/retained-observation-success
  :my.blob/retained-observation-failure])
(schema/register! :my.blob/verified-count [:int {:min 0}])
(schema/register! :my.blob/newly-materialized-count [:int {:min 0}])
(schema/register! :my.blob/repaired-count [:int {:min 0}])
(schema/register!
 :my.blob/materialization-success
 [:map {:closed true}
  [:my.blob/ok? [:= true]]
  [:my.blob/target-branch-head :my.blob/target-branch-head]
  [:my.blob/reachable-hash-digest :my.blob/reachable-hash-digest]
  [:my.blob/hash-count :my.blob/hash-count]
  [:my.blob/verified-count :my.blob/verified-count]
  [:my.blob/newly-materialized-count :my.blob/newly-materialized-count]
  [:my.blob/repaired-count :my.blob/repaired-count]])
