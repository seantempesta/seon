(do
  (require 'seon.mcp 'seon.pass-probe 'clojure.data.json 'clojure.string)
  (mapv
   (fn [mode]
     (let [agent (System/getProperty "seon.pass-cost.agent" "pass-cost-probe")
           pass (#'seon.pass-probe/mcp-pass
                 {"code" (clojure.string/join "\n" (repeat 100 "(+ 1 1)"))
                  "agent" agent "namespace" (str "scratch.s0probe-" agent)
                  "cluster" "default" "mode" mode})]
       (pass)
       (with-open [recording (jdk.jfr.Recording.)]
         (-> (.enable recording "jdk.ObjectAllocationSample")
             (.with "throttle" "1000/s") (.withStackTrace))
         (.start recording)
         (dotimes [_ 5] (pass))
       (.stop recording)
       (let [path (java.nio.file.Path/of
                   (str "/tmp/seon-pass-cost-session/pass-cost/" mode
                        (System/getProperty "seon.pass-cost.suffix" "") ".jfr") (make-array String 0))]
         (.dump recording path)
         {:mode mode :bytes (.getSize recording)}))))
   ["sci" "jvm"]))
