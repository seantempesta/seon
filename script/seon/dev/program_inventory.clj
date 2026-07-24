(ns seon.dev.program-inventory
  "Derive build-published function inventories from analyzer data."
  (:require [clojure.java.io :as io]))

(defn- def-meta
  "Return the inventory-relevant metadata for one analyzed definition."
  [definition]
  (merge (:meta definition)
         (select-keys definition [:private :fn-var :file :line :test])))

(defn first-party-roots
  "Return source roots whose analyzed definitions belong to this artifact."
  []
  (let [extra (System/getenv "SEON_EXTRA_SRC")]
    (cond-> [(str (io/file (System/getProperty "user.dir") "src"))]
      (and extra (not= extra "")) (conj extra))))

(defn first-party-file?
  "Return whether an analyzer file belongs to a first-party source root."
  [file]
  (boolean
   (when (and file (string? file))
     (let [roots (first-party-roots)]
       (if (.startsWith ^String file "/")
         (some #(.startsWith ^String file ^String %) roots)
         (when-let [url (io/resource file)]
           (and (= "file" (.getProtocol url))
                (some #(.startsWith (.getPath url) ^String %) roots))))))))

(defn first-party-definition?
  "Return whether an analyzed definition belongs to this artifact."
  [definition]
  (first-party-file? (:file (def-meta definition))))

(defn- analyzed-fn-entries
  [namespaces selected-namespaces]
  (for [namespace-symbol selected-namespaces
        [_ definition] (:defs (get namespaces namespace-symbol))
        :let [metadata (def-meta definition)]
        :when (and (:fn-var metadata) (:line metadata))]
    {:seon.dev.program-inventory/symbol (str (:name definition))
     :seon.dev.program-inventory/private? (true? (:private metadata))
     :seon.dev.program-inventory/first-party?
     (first-party-definition? definition)}))

(defn analyzer-fn-inventory
  "Derive callable symbols from a selected compiler analysis closure."
  [namespaces selected-namespaces]
  (let [entries (analyzed-fn-entries namespaces selected-namespaces)]
    {:seon.dev.program-inventory/public-exports
     (->> entries
          (filter :seon.dev.program-inventory/first-party?)
          (remove :seon.dev.program-inventory/private?)
          (map :seon.dev.program-inventory/symbol)
          distinct
          sort
          vec)
     ;; R39 gives first-party private functions real private corpus rows. Only
     ;; dependency-owned compiled terminals remain artifact-internal.
     :seon.dev.program-inventory/internal-terminals
     (->> entries
          (remove :seon.dev.program-inventory/first-party?)
          (map :seon.dev.program-inventory/symbol)
          distinct
          sort
          vec)
     :seon.dev.program-inventory/first-party-private
     (->> entries
          (filter :seon.dev.program-inventory/first-party?)
          (filter :seon.dev.program-inventory/private?)
          (map :seon.dev.program-inventory/symbol)
          distinct
          sort
          vec)}))
