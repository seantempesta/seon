(ns seon.web-cost-test
  "What an effect's in-flight text costs the web proc per message (task seon.render/one-walk-per-view, rule 10;
  docs/render.md#in-flight-output-streams): flat in how long its stream already is. Run: bin/test seon.web-cost-test"
  (:require [clojure.test :refer [deftest]] [clojure.core.async :as a] [seon.cost :as cost] [seon.web :as w]))

(defn stream-input
  "The proof's input at n: the web proc with one tab of the agent's chat, its stream already n characters long and
  seen by that tab (its channel drops what it can't hold, so every measured call offers as the first did), and the
  next message of 20 characters: [st m]."
  [n]
  (let [st (#'w/web-proc {:port 0})
        [st _] (#'w/web-proc st :from-render {:op :index :agents {:a1 {:pid 1 :branch :agent-1 :status :busy :key "b1/a1"}}})
        [st _] (#'w/web-proc st :http {:op :open :view [:chat :a1] :ch (a/chan (a/dropping-buffer 1))})
        st (first (#'w/web-proc st :from-render {:op :keyframe :view [:chat :a1] :html "<div id=\"app\"></div>"}))
        m {:stream "b1/a1/3/0/message" :in "b1/a1/3" :part "message" :agent "b1/a1"}
        st (#'w/stream-in st (assoc m :offset 0 :text (apply str (repeat n "x"))))]
    [st (assoc m :offset n :text "Reading the task now")]))

(deftest a-stream-message-costs-its-bytes-at-300
  ;; Optimization's minimum (2026-11-08): an offset check and map update, one Datastar append frame O(text), an offer per
  ;; watching tab; nothing that grows with what the stream already sent (an append that resent the text so far would)
  (cost/proof {:of 'seon.web/stream-in :input 'seon.web-cost-test/stream-input
               :n "characters the stream already sent, one tab watching"
               :work "per message: the stream's held offset read and moved, the part's selector made from its id, the text escaped and framed once (one Datastar append), one offer to the one tab and its seen set; none per character already sent"
               :growth :constant
               ;; measured 2026-11-08: 3,390 ticks, 21,328 B and 6 calls at 10, 100, 300 and 1,000 characters already sent
               :fixed {:ticks 3400 :bytes 21500 :calls 6}
               :sizes [10 100 300 1000]}))
