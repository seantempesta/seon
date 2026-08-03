(ns my.web
  "Bounded web fetch and provider-neutral search requests."
  (:refer-clojure :exclude [fetch])
  (:require [seon.effect :as effect]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn fetch
  "Fetch one bounded HTTP(S) URL through the protected web owner."
  {:malli/schema
   [:=> [:cat :my.web/fetch-request]
    [:or :my.web/fetch-result :my.web/error]]
   :seon.workload :io
   :seon.effect/capability 'seon.web.jvm/fetch}
  [request]
  (effect/request! #'fetch request))

(defn search
  "Search the configured provider and return source rows plus raw evidence."
  {:malli/schema
   [:=> [:cat :my.web/search-request]
    [:or :my.web/search-result :my.web/error]]
   :seon.workload :io
   :seon.effect/capability 'seon.web.jvm/search}
  [request]
  (effect/request! #'search request))
