(ns hooks.is-message
  "A `clojure.test/is` message is evaluated on every assertion, passing ones
  included, so a bare printer there prints its value whole on every run: a
  `(pr-str installed)` message holding the schema projection built a 2 GB
  string and killed default (pid 3360, 2026-09-27 21:07). Messages display
  values: the runner shows them through the value printer."
  (:require [clj-kondo.hooks-api :as api]))

(def ^:private printers
  '#{pr-str prn-str print-str println-str with-out-str
     clojure.core/pr-str clojure.core/prn-str clojure.core/print-str
     clojure.core/println-str clojure.core/with-out-str
     pprint clojure.pprint/pprint})

(defn- printer-calls [node]
  (let [head (first (:children node))]
    (concat (when (and (api/list-node? node) head (api/token-node? head)
                       (printers (api/sexpr head)))
              [node])
            (mapcat printer-calls (:children node)))))

(defn is
  "Refuse a bare printer anywhere in an `is` message; analysis is unchanged."
  [{:keys [node]}]
  (when-let [message (nth (:children node) 2 nil)]
    (doseq [call (printer-calls message)]
      (api/reg-finding!
       (assoc (meta call)
              :type :seon/unbounded-assertion-message
              :message "An is message is evaluated on every assertion: an assertion message is prose and never prints a value; the runner shows the failing value through the value printer."))))
  nil)
