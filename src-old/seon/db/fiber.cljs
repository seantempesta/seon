(ns seon.db.fiber
  "Own process-local database context for Bun fibers."
  (:require [seon.db :as-alias db]))

;;; Process-local execution context. Ordinary database descriptors may pin
;;; reads; native Datahike database values never enter these scopes.

(defonce ^:private tx-context
  (let [ctor (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (ctor.)))

(defonce ^:private agent-context
  (let [ctor (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (ctor.)))

(defonce ^:private read-evidence-context
  (let [ctor (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (ctor.)))

(defn current-tx-context
  "The current fiber-local transaction context."
  []
  (some-> tx-context .getStore))

(defn current-agent-id
  "The current fiber-local agent id."
  []
  (some-> agent-context .getStore))

(defn record-read-evidence!
  "Retain one ordinary Datahike read-evidence entry in the current fiber."
  [evidence]
  (when-let [entries (some-> read-evidence-context .getStore)]
    (swap! entries conj evidence))
  nil)

(defn run-with-read-evidence
  "Run `f` in a fresh fiber-local evidence scope and return value + evidence."
  [f]
  (let [entries (atom [])]
    (.run
     read-evidence-context entries
     (fn []
       (-> (js/Promise.resolve nil)
           (.then (fn [_] (f)))
           (.then (fn [value]
                    {::db/value value
                     ::db/read-evidence (vec (distinct @entries))})))))))

(defn run-with-tx-context
  "Run `f` with `context` merged into the current transaction context."
  [context f]
  (.run tx-context (merge (current-tx-context) context) f))

(defn enter-tx-context!
  "Make `context` available to async work created from the current fiber."
  [context]
  (.enterWith tx-context (merge (current-tx-context) context))
  nil)

(defn run-with-agent
  "Run `f` with `agent-id` as the current agent."
  [agent-id f]
  (.run agent-context agent-id f))

(defn run-without-agent
  "Run `f` without an inherited agent id."
  [f]
  (.exit agent-context f))
