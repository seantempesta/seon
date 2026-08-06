(ns my.web
  "Fetch web resources and search the configured provider."
  (:require [seon.effect :as effect]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn fetch
  "Fetch one bounded HTTP(S) resource.

  Takes a URL and optional `:get` or `:head` method. Returns status, redirect
  history, bounded body, and extraction data, or a flat web error. Use it when
  you already know the resource URL."
  {:malli/schema
   [:=> [:cat :my.web/fetch-request]
    [:or :my.web/fetch-result :my.web/error]]
   :seon.workload :io
   :seon.effect/capability 'seon.web.jvm/fetch}
  [request]
  (effect/request! #'fetch request))

(defn search
  "Search the configured provider for source rows.

  Takes a query and optional result limit. Returns bounded result rows plus a
  blob digest for the raw response, or a flat web error. Use it to discover
  sources before fetching them."
  {:malli/schema
   [:=> [:cat :my.web/search-request]
    [:or :my.web/search-result :my.web/error]]
   :seon.workload :io
   :seon.effect/capability 'seon.web.jvm/search}
  [request]
  (effect/request! #'search request))
