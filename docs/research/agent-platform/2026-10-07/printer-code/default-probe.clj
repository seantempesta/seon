(let [dir "/private/tmp/claude-501/-Users-sean-src-seon/2f0ff965-686f-4905-bf5c-7fe2ed6b5919/scratchpad/printer-code/"
      conn (seon.cluster.boot/connection "default")
      db (seon.db/db conn)
      text (slurp (str dir "context.txt"))
      composed (clojure.edn/read-string {:default tagged-literal} (slurp (str dir "composed.edn")))
      profile (seon.render/agent-render-profile seon.config/defaults)
      help (seon.bootstrap/render-help-ai (seon.bootstrap/help-value db "printer-code"))
      big (subs (slurp (str dir "composed.edn")) 0 108891)
      html-str (fn [h] (seon.render.hiccup/->string h))
      probes {:ctx-html #(count (html-str (seon.render.value/html {:seon.render/value text :seon.render.value/root [:seon.agent/id "root"] :seon.render/profile profile})))
              :ctx-ai #(count (seon.render.value/text {:seon.render/value text :seon.render.value/root 'result/x1 :seon.render/profile profile}))
              :map-html #(count (html-str (seon.render.value/html {:seon.render/value composed :seon.db/db db :seon.render.value/root [:ctx "root"]})))
              :help-reply #(count (seon.repl/response {:seon.cluster.eval/source "(help)" :seon.eval/shown help :seon.eval/renderer 'seon.bootstrap/render-help-ai
                                                       :seon.repl/handle 'result/e9a11106a8315 :seon.eval/duration-ms 25 :seon.cluster.eval/call-count 444}))
              :reply-109k #(count (seon.repl/response {:seon.cluster.eval/source "x" :seon.eval/shown big :seon.repl/handle 'result/e1 :seon.eval/duration-ms 3}))}
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      sink (some-> (resolve 'seon.batch/*world*) deref :seon.batch/sink)
      calls #(if sink (reduce + 0 (vals (seon.batch/executed sink))) -1)
      result (promise)
      thread (.unstarted (Thread/ofPlatform)
               (bound-fn []
                 (deliver result
                   (try
                     (into (sorted-map)
                       (map (fn [[k f]]
                              (f)
                              [k (mapv (fn [_]
                                         (let [id (.threadId (Thread/currentThread))
                                               c0 (calls)
                                               a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean id)
                                               t (System/nanoTime)
                                               out (f)
                                               ms (/ (- (System/nanoTime) t) 1e6)]
                                           {:ms (/ (Math/round (* ms 100)) 100.0)
                                            :kb (quot (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean id) a) 1024)
                                            :calls (- (calls) c0) :out-chars out}))
                                       (range 3))]))
                       probes)
                     (catch Throwable e {:failed (ex-message e)})))))]
  (.start thread) (.join thread)
  {:basis (seon.db/basis-t db) :text-chars (count text) :help-chars (count help) :samples @result})
