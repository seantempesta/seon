(ns seon.system
  "Root's system procs as a declaration (resources/seon/system.edn, read with aero), built
  into a flow config with the values root's boot step holds. Owner, 2026-10-20: the
  definition lives in a file and starting is explicit: seon.root/start! builds the graph
  from it at once, seon.root/apply! re-reads it and update!s only what changed. A built
  proc is reused while its declaration is equal, so update! (which keeps procs whose
  :proc, :args and :chan-opts are unchanged, with their queued messages) leaves it running."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [aero.core :as aero] [clojure.walk :as walk]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [seon.fx :as fx]))

(defn read-decl
  "The declaration {:procs :fx :conns}, read with aero from the file at p."
  {:malli/schema [:=> [:cat :string] :seon.system/decl]}
  [p] (aero/read-config p))

(defn- bind
  "Decl with every :bind/<name> keyword replaced by its value in bindings; a name bindings
  lacks is a defect of the declaration."
  {:malli/schema [:=> [:cat :any :map] :any]}
  [decl bindings]
  (walk/postwalk (fn [x] (if (and (keyword? x) (= "bind" (namespace x)))
                           (if (contains? bindings (keyword (name x)))
                             (get bindings (keyword (name x)))
                             (throw (ex-info (str "system.edn names " x ", which root's boot does not bind") {:bind x})))
                           x))
                 decl))

(defn- step-fn
  "The step-fn Var a declaration names (its namespace loaded): the declaration file is the
  declared set of these resolves."
  {:malli/schema [:=> [:cat :qualified-symbol] :any]}
  [sym] (or (requiring-resolve sym) (throw (ex-info (str "system.edn names " sym ", which does not resolve") {:proc sym}))))

(defn- build-one
  "One declared proc entry, built: {pid {:proc :args :chan-opts}}."
  {:malli/schema [:=> [:cat :keyword :map] :map]}
  [pid {:keys [proc args chan-opts]}]
  {pid (cond-> {:proc (flow/process (step-fn proc)) :args (or args {})} chan-opts (assoc :chan-opts chan-opts))})

(defn- build-fx
  "One effect kind's owner and workers (seon.fx/procs), its perform resolved."
  {:malli/schema [:=> [:cat :keyword :map :seon.host/chan] :map]}
  [kind spec back]
  (fx/procs {kind (cond-> (update spec :perform step-fn) (nil? (:rate spec)) (dissoc :rate))} back))

(defn build
  "The flow config of decl under bindings: {:procs :conns :parts}, :parts being each
  declared unit (a proc, or an effect kind) as {unit [bound-decl built-procs]}. A unit
  whose bound declaration equals prev's keeps prev's built procs."
  {:malli/schema [:=> [:cat :seon.system/decl :map [:maybe :map]] :map]}
  [decl bindings prev]
  (let [d (bind decl bindings)
        unit (fn [k bound make] (let [[pd built] (get prev k)] [k [bound (if (= pd bound) built (make))]]))
        parts (into {} (concat (for [[pid pd] (:procs d)] (unit [:proc pid] pd #(build-one pid pd)))
                               (for [[kind spec] (:fx d)] (unit [:fx kind] spec #(build-fx kind spec (:back bindings))))))]
    {:parts parts
     :procs (into {} (map (comp second val)) parts)
     :conns (:conns d)}))

(defn fx-owners
  "The effect owners' pids in a built procs map: what an agent's proc may address."
  {:malli/schema [:=> [:cat :map] [:set :keyword]]}
  [procs] (into #{} (filter #(= "fx" (namespace %))) (keys procs)))
