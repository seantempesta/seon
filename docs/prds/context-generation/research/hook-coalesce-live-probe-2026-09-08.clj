; Run through the selected default JVM after bin/seon start beta.
; Results survive an MCP read timeout under tmp/hook-coalesce-proof/.
(let [instances @@(ns-resolve 'seon.cluster 'running-instances)
      default (get instances "default")
      beta (get instances "beta")
      store (:seon.store/store default)
      beta-head #(seon.cluster.registry/branch-commit-id
                  {:seon.store/store store
                   :seon.store/branch (seon.cluster.registry/cluster-branch "beta")})
      beta-digest #(seon.db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]]
                              @(:seon.boot/cluster-connection beta))
      before-digest (beta-digest)
      before (beta-head)
      url (str (get-in default [:seon.render.web/served :seon.render.web/url]) (seon.render.route/path :seon.render.route/agent-debug {:id "root"}))
      requests (atom [])
      request! (fn [phase]
                 (let [started (System/nanoTime)
                       c (.openConnection (.toURL (java.net.URI. url)))]
                   (.setConnectTimeout c 5000)
                   (.setReadTimeout c 15000)
                   (try
                     (swap! requests conj
                            {:phase phase :status (.getResponseCode c)
                             :ms (/ (- (System/nanoTime) started) 1000000.0)})
                     (catch Throwable e
                       (swap! requests conj {:phase phase :error (ex-message e)}))
                     (finally
                       (.disconnect c)
                       (spit "tmp/hook-coalesce-proof/live-progress.edn"
                             (pr-str {:beta-before before :requests @requests}))))))]
  (assert beta "beta must be running for this proof")
  (request! "before")
  (let [result
          (binding [seon.cluster/*source-progress!*
                    (fn [phase]
                      (when (contains? #{"development schema declarations"
                                         "development loaded definitions"
                                         "development SCI acquisition"
                                         "development JVM instrumentation"} phase)
                        (request! phase)))]
            (try (seon.cluster/refresh-source!
                  "/Users/sean/src/seon" ["src/seon/cluster/source.clj"] "default")
                 (catch Throwable e
                   (spit "tmp/hook-coalesce-proof/adoption-error.edn" (pr-str (ex-data e)))
                   {:error (ex-message e)})))]
    (request! "after")
    (let [report {:result result :requests @requests
     :beta-before before :beta-after (beta-head)
     :beta-program-before before-digest :beta-program-after (beta-digest)
     :same-server (identical? (get-in default [:seon.render.web/served :seon.render.web/server])
                             (get-in @@(ns-resolve 'seon.cluster 'running-instances)
                                     ["default" :seon.render.web/served :seon.render.web/server]))}]
      (spit "tmp/hook-coalesce-proof/live-result.edn" (pr-str report))
      report)))
