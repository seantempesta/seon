(ns seon.bootstrap
  "The agent's orientation: `(help)` and its live situation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.ai :as ai]
            [seon.ref :as ref]
            [seon.schema :as schema]
            [seon.turn :as turn]
            [seon.db :as db]
            [seon.config :as config]
            [seon.health :as health]
            [seon.error.refusal :as error]))

(defmacro help
  "Return the calling agent's REPL instructions as a help entity; given a heading anchor, the one section it names."
  ([] (list 'seon.bootstrap/help-value))
  ([anchor] (list 'seon.bootstrap/help-section anchor)))

(defn- sections
  "The `##` sections of help's Markdown `text`: each heading, its anchor, its paragraphs as lines and the references
  its text makes, resolved on `database`."
  {:malli/schema [:=> [:cat :seon.db/database-value :string] :seon.help/groups]}
  [database text]
  (let [lines (vec (str/split-lines text))
        headings (filterv #(= 2 (:seon.ref/level %)) (:seon.ref/headings (ref/document text)))
        ends (conj (mapv :seon.ref/line (rest headings)) (inc (count lines)))]
    (mapv (fn [{:seon.ref/keys [heading anchor line]} end]
            (let [body (str/join "\n" (subvec lines line (dec end)))]
              {:seon.help/title heading
               :seon.help/anchor anchor
               :seon.help/lines (into [] (comp (map str/trim) (remove str/blank?)) (str/split body #"\n\s*\n"))
               :seon.help/references (ref/references database {:seon.ref/text body})}))
          headings ends)))

(defn- groups
  "Help's sections with their resolved references, retained by the digest of `resources/seon/help.md` and the revisions of what the resolution read, so a changed cited function re-resolves them."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.help/groups]}
  [database]
  (let [text (slurp (io/resource "seon/help.md"))]
    (health/derived database [::groups (schema/sha-256 [(.getBytes text "UTF-8")])]
                    #(sections % text))))

(defn help-value
  "The REPL instructions: `resources/seon/help.md`'s orienting guide in titled sections, teaching only installed forms; a step's own guidance is its instructions."
  {:seon/tasks #{'seon.bootstrap/session-end-is-taught-three-ways}
   :malli/schema [:=> [:cat :seon.db/database-value]
                  :seon.help/help]}
  [database]
  ;; The answer depends on this definition's code, so its source is read to
  ;; record its code version in the same dependency evidence as every other read.
  (db/pull database [:seon.fn/source]
           [:seon.fn/sym 'seon.bootstrap/help-value])
  ;; `help` is a macro, so its call names no producer; the value carries
  ;; its own pair, the render law's master override.
  {:seon.render/ai 'seon.bootstrap/render-help-ai
   :seon.render/html 'seon.bootstrap/render-help-html
   :seon.help/groups (groups database)})

(defn help-section
  "The help section whose heading `anchor` names, with its resolved references, or the section error naming the nearest headings."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/anchor]
                  [:or :seon.help/help :seon.ref/section-error]]}
  [database anchor]
  (if-let [group (first (filter #(= anchor (:seon.help/anchor %)) (groups database)))]
    {:seon.render/ai 'seon.bootstrap/render-help-section-ai
     :seon.render/html 'seon.bootstrap/render-help-html
     :seon.help/groups [group]}
    (ref/section (str "resources/seon/help.md#" anchor))))

(defn render-help-ai
  "Show the help groups' titles and lines, as data; their references are in the value."
  {:malli/schema [:=> [:cat :seon.help/help] :seon.help/groups]}
  [instructions]
  (mapv #(select-keys % [:seon.help/title :seon.help/lines]) (:seon.help/groups instructions)))

(defn render-help-section-ai
  "Show the one section `help-section` answers, with its resolved references, as data."
  {:malli/schema [:=> [:cat :seon.help/help] :seon.help/groups]}
  [instructions]
  (:seon.help/groups instructions))

(defn- help-line-html
  "One help line as a list item, each parenthesised form in it as code."
  {:malli/schema [:=> [:cat :string] :seon.render/hiccup]}
  [line]
  (loop [remaining line children [:li]]
    (let [start (.indexOf ^String remaining "(")]
      (if (or (neg? start) (str/includes? remaining "\n"))
        (conj children remaining)
        (let [source (subs remaining start)
              length (try
                       (with-open [reader (clojure.lang.LineNumberingPushbackReader.
                                           (java.io.StringReader. source))]
                         (edn/read {:readers {} :default tagged-literal} reader)
                         (dec (.getColumnNumber reader)))
                       (catch Exception _ 0))]
          (if (pos? length)
            (recur (subs source length)
                   (conj children (subs remaining 0 start)
                         [:code (subs source 0 length)]))
            (conj children remaining)))))))

(defn render-help-html
  "Show the same help groups, each a heading over its list."
  {:malli/schema [:=> [:cat :seon.help/help] :seon.render/hiccup]}
  [instructions]
  (into [:div {:class "seon-help seon-help-instructions"}]
        (mapcat (fn [{:seon.help/keys [title lines]}]
                  [[:h3 title] (into [:ul] (map help-line-html lines))]))
        (:seon.help/groups instructions)))

(defn situation
  "Derive one agent's live opening seeds from current database facts."
  {:malli/schema
   [:=> [:cat :seon.db/db :seon.agent/id]
    [:or :seon.agent/situation :seon.bootstrap/agent-not-found-error :seon.config/error]]}
  [database agent-id]
  (let [agent-entity
        (db/pull database
                 '[:seon.agent/id
                   {:seon.agent/namespace
                    [:db/id :seon.ns/name
                     :seon.ns/requires]}]
                 [:seon.agent/id agent-id])]
    (if-not (:seon.agent/id agent-entity)
      (seon.error.refusal/diagnostic (java.util.Date.) :seon.bootstrap/opening 'seon.bootstrap/situation
       {:seon.error/message "Create the requested agent before deriving its opening."
        :seon.error/offending agent-id
        :seon.error/member :seon.agent/id
        :seon.bootstrap/missing-agent-id agent-id
        :seon.error/expected :seon.agent/entity})
      (let [namespace-entity (:seon.agent/namespace agent-entity)
            run (when-let [id (turn/open-for-agent database [:seon.agent/id agent-id])]
                  (db/pull database
                           '[:seon.turn/id
                             {:seon.turn/trigger [:seon.message/id]}]
                           [:seon.turn/id id]))
            turn-limit
            (or (:seon.config.run/max-episode-runs (ai/agent-overlay database agent-id))
                (config/agent-cluster-dial database agent-id :seon.config.run/max-episode-runs))
            turns-used
            (turn/episode-runs
             database agent-id)
            ;; UNREAD AND THE TURN BOUND ARE ONE DERIVATION, BY `:t`.
            ;; A message is unanswered exactly while its transaction is
            ;; newer than every turn this agent has opened; the bound is
            ;; the turns taken since the latest wake from outside the
            ;; agent. Neither is stored and neither is counted here.
            unread
            (count (turn/unanswered-triggers
                    database agent-id))]
        (if (:seon.config/error-key turn-limit)
          turn-limit
        (cond->
         {:seon.agent/id agent-id
          :seon.agent/namespace-ref
          [:seon.ns/name (:seon.ns/name namespace-entity)]
          :seon.agent/unread-message-count (long unread)
          :seon.turn/turns-remaining
          (long (max 0 (- (or turn-limit 0) turns-used)))
          :seon.agent/protocol-namespaces
          (->> (:seon.ns/requires namespace-entity)
               sort
               vec)}
          run
          (assoc :seon.agent/open-run-ref
                 [:seon.turn/id
                  (:seon.turn/id run)])
          (:seon.turn/trigger run)
          (assoc :seon.turn/trigger
                 [:seon.message/id
                  (get-in run [:seon.turn/trigger
                               :seon.message/id])])))))))

(defmacro dir
  "List the public names in namespace-name through Clojure's REPL macro."
  [namespace-name]
  (list 'clojure.repl/dir namespace-name))

(defmacro doc
  "Print documentation for symbol through Clojure's REPL macro."
  [documented-symbol]
  (list 'clojure.repl/doc documented-symbol))
