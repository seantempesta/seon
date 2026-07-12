(ns seon.db.process
  "Stable database-process identities used by transaction provenance.

   A process identifies the durable ingress that produced facts. It is not an
   OS process, operation name, role, or authorization principal. Users remain
   existing root, human, and agent entities; transaction metadata relates one
   of those users to exactly one process here."
  (:require
    [seon.schema :as schema]))

(schema/register!
  ::id
  [:and {:seon.db/identity true}
   [:enum ::boot ::config ::repl]])

(schema/register! ::lookup-ref [:tuple [:= ::id] ::id])
(schema/register! ::entity
  [:map {:seon.db/entity true}
   [::id ::id]])
(schema/register! ::entities [:vector ::entity])

(def ids
  "The complete stable database-process identity set."
  [::boot ::config ::repl])

(defn lookup-ref
  "Build the lookup ref for a stable database process."
  {:malli/schema [:=> [:catn [::id ::id]] ::lookup-ref]}
  [id]
  [::id id])

(defn genesis-entities
  "Return the process identity facts installed by database genesis."
  {:malli/schema [:=> [:cat] ::entities]}
  []
  (mapv (fn [id] {::id id}) ids))
