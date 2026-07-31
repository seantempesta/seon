(ns seon.render.route
  "The one HTTP route table and its pure reverse-routing function."
  (:require [reitit.core :as reitit]))

(def routes
  "The one route table. Adding a namespace page is adding a line here."
  [["/" {:name ::root
          :get {:handler ::root}}]
   ["/ns/{namespace}" {:name ::namespace
                        :get {:handler ::namespace}}]
   ["/ns/{namespace}/debug" {:name ::namespace-debug
                              :get {:handler ::namespace-debug}}]
   ["/agent/{id}" {:name ::agent
                    :get {:handler ::agent}}]
   ["/agent/{id}/debug" {:name ::agent-debug
                          :get {:handler ::agent-debug}}]
   ["/agent/{id}/message" {:name ::agent-message
                            :post {:middleware [::same-origin]
                                   :handler ::agent-message}}]
   ["/feed/{id}" {:name ::feed
                   :get {:handler ::feed}}]
   ["/data" {:name ::data
              :get {:handler ::data}}]
   ["/css/{*path}" {:name ::css
                     :get {:handler ::css}}]
   ["/js/{*path}" {:name ::js
                    :get {:handler ::js}}]])

(def router
  "The compiled route index used by pure reverse routing.

  Reitit's default path- and name-conflict checks run while this namespace
  loads, before an HTTP service can bind."
  (reitit/router routes))

(defn path
  "Build a path for named `route-name`, path params, and query values."
  {:malli/schema
   [:function
    [:=> [:cat :qualified-keyword] :string]
    [:=> [:cat :qualified-keyword [:map-of :keyword :string]] :string]
    [:=> [:cat :qualified-keyword
          [:map-of :keyword :string]
          [:map-of :keyword :string]]
     :string]]}
  ([route-name]
   (path route-name {} {}))
  ([route-name params]
   (path route-name params {}))
  ([route-name params query]
   (let [match (or (reitit/match-by-name! router route-name params)
                   (throw
                    (ex-info "Unknown route name."
                             {:seon.render.route/name route-name})))]
     (reitit/match->path match query))))
