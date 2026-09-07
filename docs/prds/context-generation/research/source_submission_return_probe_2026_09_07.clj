(let [target (ns-resolve 'seon.instrument 'violation)
      original @target
      observed (promise)]
  (with-redefs-fn
    {target
     (fn [caps kind data]
       (when (and (= :malli.core/invalid-output kind)
                  (= "seon.cluster.agent/submit-source!" (str (:fn-name data))))
         (deliver observed
                  {:seon.dev.submission-probe/kind kind
                   :seon.dev.submission-probe/value (:value data)}))
       (original caps kind data))}
    #(let [client (java.net.http.HttpClient/newHttpClient)
           request (-> (java.net.http.HttpRequest/newBuilder
                        (java.net.URI/create
                         "http://127.0.0.1:7766/feed/juniper?debug=true&viewer=my.agents.juniper&subject=32367"))
                       (.GET)
                       (.build))
           response (.get (.sendAsync client request
                                      (java.net.http.HttpResponse$BodyHandlers/ofInputStream))
                          3 java.util.concurrent.TimeUnit/SECONDS)]
       (try
         (deref observed 10000 :seon.dev.submission-probe/timed-out)
         (finally (.close ^java.io.InputStream (.body response)))))))
