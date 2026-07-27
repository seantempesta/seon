(ns seon.agent.ctx.format
  "Portable, acquisition-free context text formatting."
  (:require [clojure.string :as str]))

(def ^:private leading-marker-re
  #"^[\s;]*(?:(?:=>|⇒)\s*)?")

(defn quote-lines
  "Render body text as reader-valid, byte-stable single-semicolon lines."
  ([text] (quote-lines text {}))
  ([text {:seon.agent.ctx/keys [strip-markers?]}]
   (->> (str/split-lines (or text ""))
        (map (fn [line]
               (let [line (str/trimr line)]
                 (cond
                   (str/blank? line) ";"
                   strip-markers?
                   (str "; " (str/replace line leading-marker-re ""))
                   :else (str "; " line)))))
        (str/join "\n"))))

(defn message-label
  "Resolve a pulled message author ref relative to `own-id`."
  [from own-id]
  (cond
    (:seon.user/id from)             "user"
    (= own-id (:seon.agent/id from)) "assistant"
    (:seon.agent/id from)            (str "agent-" (:seon.agent/id from))
    :else                            "unknown"))
