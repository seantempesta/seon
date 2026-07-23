(ns seon.agent.web.host-leaf-test
  "Localized JVM web leaf contract tests."
  (:refer-clojure :exclude [fetch])
  (:require
   [clojure.test :refer [deftest is]]
   [seon.agent.web :as web]
   [seon.agent.web.host :as host]
   [seon.content-hash :as content-hash])
  (:import
   (com.sun.net.httpserver HttpHandler HttpServer)
   (java.net InetSocketAddress)
   (java.nio.charset StandardCharsets)))

(defn- local-server
  []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        body (.getBytes "u8 web leaf\n" StandardCharsets/UTF_8)]
    (.createContext
     server "/probe"
     (reify HttpHandler
       (handle [_ exchange]
         (.add (.getResponseHeaders exchange)
               "Content-Type" "text/plain; charset=utf-8")
         (.sendResponseHeaders exchange 200 (count body))
         (with-open [output (.getResponseBody exchange)]
           (.write output body)))))
    (.start server)
    server))

(def protective-limits
  {:seon.config.web/default-timeout-ms 30000
   :seon.config.web/maximum-response-bytes 2000000
   :seon.config.web/default-preview-tokens 2000
   :seon.config.web/maximum-redirects 5
   :seon.config.web/default-search-results 10
   :seon.config.web/maximum-search-results 20})

(deftest real-local-fetch-preserves-the-public-envelope
  (let [server (local-server)
        url (str "http://127.0.0.1:"
                 (.getPort (.getAddress server)) "/probe")
        leaf
        (host/services
         {::host/enabled? (constantly true)
          ::host/put!
          (fn [{:my.blob/keys [content]}]
            {:my.blob/ok? true
             :my.blob/hash (content-hash/sha-256 content)
             :my.blob/tokens 3})
          ::host/transact! (constantly {:seon.db/ok? true})
          ::host/now #(java.util.Date. 1000)})
        fetch (get ((deref #'web/bind-leaf) leaf) 'fetch)]
    (try
      (let [result
            (fetch {:seon.agent.web/url url
                    :seon.agent.web/timeout-ms 1000
                    :seon.agent.web/max-preview-tokens 100
                    :seon.config/configuration
                    (assoc protective-limits
                           :seon.agent.web/policy :open
                           :seon.agent.web/allowed-domains [])})]
        (is (:seon.agent.web/ok? result))
        (is (= 200 (:seon.agent.web/status result)))
        (is (= "u8 web leaf\n" (:seon.agent.web/preview result)))
        (is (= :text (:seon.agent.web/extractor result)))
        (is (re-matches #"[0-9a-f]{64}"
                        (:seon.agent.web/blob-hash result))))
      (finally
        (.stop server 0)))))

(deftest public-only-policy-refuses-the-same-local-target
  (let [leaf
        (host/services
         {::host/enabled? (constantly true)})
        fetch (get ((deref #'web/bind-leaf) leaf) 'fetch)
        result
        (fetch {:seon.agent.web/url "http://127.0.0.1:1/"
                :seon.config/configuration
                (assoc protective-limits
                       :seon.agent.web/policy :public-only
                       :seon.agent.web/allowed-domains [])})]
    (is (false? (:seon.agent.web/ok? result)))
    (is (re-find #"policy refused" (:seon.error/message result)))))
