(ns seon.phase2.demo
  "Phase 2 demo namespace -- purely a test fixture for the datahike routing work.

   Registers a tiny schema (`::id`, `::name`) and a corresponding entity
   :map used as `:seon.phase2.demo` DB's `namespace-schemas` entry in
   `resources/system.edn`.

   Carries no behavior. Delete once Phase 2 routing is removed or a real
   namespace replaces it."
  (:require [seon.schema :as schema]))

(schema/register! ::id [:uuid {:seon.db/identity true}])
(schema/register! ::name :string)

(def entity-schema
  "Malli :map schema installed on the :seon.phase2.demo datahike DB via
   `:seon.db/flow`'s `:namespace-schemas`."
  [:map
   [::id ::id]
   [::name ::name]])
