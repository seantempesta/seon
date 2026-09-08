(ns seon.render.web-feed-test
  "First paint is independent of the cluster render proc."
  (:require [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.render.web-test :as web-test]))

(deftest first-paint-precedes-proc-deltas-and-refusal-precedes-close
  (#'web-test/with-server
   (fn [_ server context]
     (flow/pause (:graph context))
     (let [await-var (ns-resolve 'seon.render.web 'await-feed-package!)
           original @await-var]
       (with-redefs-fn
         {await-var (fn [tap registration-key _backstop-ms]
                      (original tap registration-key 100))}
         (fn []
           (with-open [stream (#'web-test/open-feed server "/feed/root?debug=true")]
             (let [first-frame (#'web-test/read-patches! stream 1)
                   refusal (#'web-test/read-until! stream "seonFeedError")]
               (is (str/includes? first-frame "debug-units")
                   "The paused render proc cannot supply this first paint.")
               (is (str/includes? refusal "event: datastar-patch-signals"))
               (is (str/includes? refusal "seon.await/backstop-fired"))
               (is (str/includes? refusal "seon.config.eval/time-limit-ms"))
               (is (= -1 (.read stream)) "The refusal was written before close.")))))))))
