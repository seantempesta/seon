(ns ^{:seon.ns/context-relevant? true} my.web
  "Fetch web resources and search the configured provider."
  (:require [seon.effect :as effect]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn fetch
  "Fetch one bounded HTTP or HTTPS resource.

  Supply :my.web/url and optionally :my.web/method (:get or :head).
  Returns :my.web/status, :my.web/final-url, :my.web/redirects and
  :my.web/body, with extraction data when available. In the example,
  example-url is the URL of the resource you want to read.

  Example:
  (my.web/fetch {:my.web/url example-url})"
  {:malli/schema
   [:=> [:cat :my.web/fetch-request]
    [:or :my.web/fetch-result :my.web/error]]
   :seon.workload :io
   :seon.effect/capability 'seon.web.jvm/fetch}
  [request]
  (effect/request! #'fetch request))

(defn search
  "Search the configured provider for source rows.

  Supply :my.web/query and optionally :my.web/max-results. Returns
  :my.web/results and a blob digest for the provider response. The cluster
  config selects the endpoint and credential.

  Example:
  (my.web/search {:my.web/query \"Clojure documentation\" :my.web/max-results 3})"
  {:malli/schema
   [:=> [:cat :my.web/search-request]
    [:or :my.web/search-result :my.web/error]]
   :seon.workload :io
   :seon.effect/capability 'seon.web.jvm/search}
  [request]
  (effect/request! #'search request))
