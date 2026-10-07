;; Plain-JVM A/B probe: the same forms against the parent and the change, unarmed, platform thread.
(require 'seon.render.value 'seon.repl 'seon.render.hiccup 'clojure.edn)
(let [dir "/private/tmp/claude-501/-Users-sean-src-seon/2f0ff965-686f-4905-bf5c-7fe2ed6b5919/scratchpad/printer-code/"
      text (slurp (str dir "context.txt"))
      composed (clojure.edn/read-string {:default tagged-literal} (slurp (str dir "composed.edn")))
      profile {:seon.render.profile/id :seon.render.profile/agent :seon.render.profile/token-budget 1500
               :seon.render.profile/max-depth 8 :seon.render.profile/max-children 32 :seon.render.profile/composition :multiline}
      help (slurp (str dir "help.txt"))
      big (subs (slurp (str dir "composed.edn")) 0 108891)
      dense (subs (pr-str (vec (range 30000))) 0 108891)
      html-str seon.render.hiccup/->string
      probes {:ctx-ai #(count (seon.render.value/text {:seon.render/value text :seon.render.value/root 'result/x1 :seon.render/profile profile}))
              :ctx-html #(count (html-str (seon.render.value/html {:seon.render/value text :seon.render.value/root [:seon.agent/id "root"] :seon.render/profile profile})))
              :map-html #(count (html-str (seon.render.value/html {:seon.render/value composed :seon.render.value/root [:ctx "root"] :seon.render/profile profile})))
              :help-reply #(count (seon.repl/response {:seon.cluster.eval/source "(help)" :seon.eval/shown help :seon.eval/renderer 'seon.bootstrap/render-help-ai
                                                       :seon.repl/handle 'result/e9a11106a8315 :seon.eval/duration-ms 25 :seon.cluster.eval/call-count 444}))
              :reply-109k #(count (seon.repl/response {:seon.cluster.eval/source "x" :seon.eval/shown big :seon.repl/handle 'result/e1 :seon.eval/duration-ms 3}))
              :reply-109k-dense #(count (seon.repl/response {:seon.cluster.eval/source "x" :seon.eval/shown dense :seon.repl/handle 'result/e1 :seon.eval/duration-ms 3}))}
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      result (promise)
      thread (.unstarted (Thread/ofPlatform)
               (fn []
                 (deliver result
                   (into (sorted-map)
                     (map (fn [[k f]]
                            (dotimes [_ 20] (f))
                            [k (let [samples (mapv (fn [_]
                                                     (let [id (.threadId (Thread/currentThread))
                                                           a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean id)
                                                           t (System/nanoTime) out (f)
                                                           ms (/ (- (System/nanoTime) t) 1e6)]
                                                       [ms (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean id) a) out]))
                                                   (range 10))
                                     ms (sort (map first samples))]
                                 {:median-ms (/ (Math/round (* 100 (nth ms 5))) 100.0)
                                  :kb (quot (second (first samples)) 1024)
                                  :out-chars (nth (first samples) 2)})]))
                     probes))))]
  (.start thread) (.join thread)
  (prn @result))
(shutdown-agents)
(System/exit 0)
