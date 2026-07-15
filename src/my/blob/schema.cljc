(ns my.blob.schema
  "Portable storage-view schemas owned by `my.blob`."
  (:require [seon.schema :as schema]))

(schema/register! :my.blob/directory [:string {:min 1}])
(schema/register! :my.blob/writable-dir :my.blob/directory)
(schema/register! :my.blob/read-only-dirs [:vector :my.blob/directory])
(schema/register!
 :my.blob/storage-view
 [:map {:closed true}
  [:my.blob/writable-dir :my.blob/writable-dir]
  [:my.blob/read-only-dirs :my.blob/read-only-dirs]])
