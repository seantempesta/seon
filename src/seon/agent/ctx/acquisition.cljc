(ns seon.agent.ctx.acquisition
  "Execute one portable context-acquisition stage."
  (:require
    [seon.db :as db]))

#?(:clj (defmacro await [value] value))

(defn ^{:async #?(:cljs true :clj false)} execute
  "Execute bounded database members at one pinned database value."
  [request]
  (await (db/execute-many request)))

(defn ^{:async #?(:cljs true :clj false)} call
  "Resolve one portable database acquisition call."
  [result]
  (await result))

(defn ^{:async #?(:cljs true :clj false)} all
  "Resolve every acquisition result while preserving input order."
  [values]
  #?(:clj (vec values)
     :cljs (vec (array-seq (await (js/Promise.all (into-array values)))))))
