(ns seon.web-cost-test
  "What an effect's in-flight text costs the web proc per message (task seon.render/one-walk-per-view, rule 10;
  docs/render.md#in-flight-output-streams): flat in how long its stream already is. Run: bin/test seon.web-cost-test"
  (:require [clojure.test :refer [deftest]] [clojure.core.async :as a] [seon.cost :as cost] [seon.web :as w]))

(defn stream-input
  "The proof's input at n: n hundred characters of text (so each size's work is well above a tick quantum), the web proc with one tab of the agent's chat whose page has the call's in-flight element (its
  channel drops what it can't hold, so every measured call offers as the first did), and the next message of the part:
  all its n characters so far. [st m]."
  [n]
  (let [st (#'w/web-proc {:port 0})
        [st _] (#'w/web-proc st :from-render {:op :index :agents {:a1 {:pid 1 :branch :agent-1 :status :busy :key "b1/a1"}}})
        [st _] (#'w/web-proc st :http {:op :open :view [:chat :a1] :ch (a/chan (a/dropping-buffer 1))})
        st (first (#'w/web-proc st :from-render {:op :keyframe :view [:chat :a1]
                                                  :html "<div id=\"app\"><div id=\"b1_2Fa1_2F3\" class=\"seon-stream\" data-stream-in=\"b1/a1/3\"></div></div>"}))
        m {:stream "b1/a1/3/0/message" :in "b1/a1/3" :part "message" :agent "b1/a1"}
        st (#'w/stream-in st (assoc m :text "x"))]
    ;; text as a reply has it: words, a tag and quotes now and then, which the escape copies
    [st (assoc m :text (apply str (take (* 100 n) (cycle "Reading the <task> now, as \"asked\". "))))]))

(deftest a-stream-message-costs-its-text
  ;; owner, 2026-11-08: "the simpler for us option that is always right", the feed compressed: each message draws the
  ;; call's element whole with the text so far, so its work is linear in that text and flat in everything else
  (cost/proof {:of 'seon.web/stream-in :input 'seon.web-cost-test/stream-input
               :n "hundreds of characters of the part so far, one tab watching"
               :work "the part's latest text held; the call's element drawn once (seon.render.ui/stream-container: the text escaped and written into one element), framed as one Datastar event, offered to the one tab; per character: the escape, the write and the frame"
               :growth :linear
               ;; measured 2026-11-08 (one-pass escape): 10,235 / 16,133 / 32,590 / 84,944 ticks and 63 / 105 / 223 / 637 KB at
               ;; 300 / 1,000 / 3,000 / 10,000 characters, 76 calls each (the container's draw); before the escape was one pass, 54
               ;; ticks and 96 B a character
               :per-n {:ticks 800 :bytes 6000}
               :fixed {:ticks 8000 :bytes 45500 :calls 76}
               :sizes [3 10 30 100]}))
