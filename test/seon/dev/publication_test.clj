(ns seon.dev.publication-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.fresh-operator :as operator]
            [seon.operator.state :as state]
            [seon.dev.clj-kondo :as kondo]
            [seon.test-support :as support]))

(deftest complete-publication-uses-the-advertised-jvm-without-a-census
  (let [observed (atom nil)
        progress (atom "preparation")
        result {:seon.source/branch :current-src :seon.source/commit-id (random-uuid)
                :seon.source/digest (apply str (repeat 64 "a"))}
        ad {:seon.boot/prepl-host "127.0.0.1" :seon.boot/prepl-port 1}]
    (with-redefs-fn
      {#'operator/cluster-truth
       (fn [_ options]
         (reset! observed options)
         [{:seon.fresh-operator/operator-root? true
           :seon.fresh-operator/process-alive? true
           :seon.fresh-operator/transport-advertisement ad}])
       #'kondo/ensure-dependency-cache! (fn [_] {:seon.dev.clj-kondo/status :ready})
       #'state/claim-root-under-lock! (fn [& _])
       #'state/mark-root-created-under-lock! (fn [& _])
       #'operator/prepl-eval!
       (fn [target form _ observe! request]
         (is (= ad target))
         (is (string? form))
         (is (identical? progress (:seon.operator.lock/progress request)))
         (observe! {:tag :out :val "● current-src: {:seon.source/progress :population}\n"})
         [{:tag :ret :val (pr-str result)}])
       #'operator/source-process-value! (fn [& _] (throw (ex-info "Unexpected fresh JVM" {})))}
      #(is (= result (#'operator/init! "tmp/publication-root" [] false
                                     {:seon.operator.lock/progress progress}))))
    (is (= {:seon.fresh-operator/read-offline-roster? false
            :seon.fresh-operator/probe-jvms? false} @observed))
    (is (= :population @progress))))

(deftest subprocess-phases-refresh-the-same-lifecycle-silence-bound
  (let [root (.getCanonicalPath (io/file "tmp/publication-phases" (str (random-uuid))))
        progress (atom "preparation")
        seen (atom [])
        started (System/nanoTime)
        bound 1000]
    (try
      (.mkdirs (io/file root))
      (let [result
            (state/with-lifecycle-lock!
             {:seon.operator.lock/path (str (io/file root "lifecycle.lock"))
              :seon.operator.lock/command "publication phase regression"
              :seon.operator.lock/acquisition-timeout-ms bound
              :seon.operator.lock/hold-timeout-ms bound
              :seon.operator.lock/progress progress}
             #(state/run-process!
               {:seon.operator.subprocess/argv
                ["/bin/bash" "-c" "for phase in analysis rows seal done; do printf '● current-src: {:seon.source/progress :%s}\n' \"$phase\"; sleep 0.4; done"]
                :seon.operator.subprocess/deadline-ms bound
                :seon.operator.subprocess/event-silence-ms bound
                :seon.operator.subprocess/progress progress
                :seon.operator.subprocess/observe-output!
                (fn [line] (swap! seen conj line)
                  (#'operator/publication-output! {:seon.operator.lock/progress progress} line))
                :seon.operator.subprocess/merge-error? true}))]
        (is (= 0 (:seon.operator.subprocess/exit result)))
        (is (= 4 (count @seen)))
        (is (= :done @progress))
        (is (> (quot (- (System/nanoTime) started) 1000000) bound)))
      (finally (support/delete-recursively! root)))))

(deftest ordinary-output-cannot-hide-a-silent-publication
  (let [progress (atom "analysis")
        failure (try
                  (state/run-process!
                   {:seon.operator.subprocess/argv
                    ["/bin/bash" "-c" "while true; do printf 'ordinary output\n'; sleep 0.05; done"]
                    :seon.operator.subprocess/deadline-ms 250
                    :seon.operator.subprocess/event-silence-ms 250
                    :seon.operator.subprocess/progress progress
                    :seon.operator.subprocess/observe-output!
                    #(#'operator/publication-output! {:seon.operator.lock/progress progress} %)
                    :seon.operator.subprocess/merge-error? true})
                  (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
    (is (= :process-exit (:seon.operator.subprocess/phase failure)))
    (is (true? (:seon.operator.subprocess/reaped? failure)))
    (is (= "analysis" @progress))))
