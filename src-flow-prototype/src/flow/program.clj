(ns flow.program
  "The agent's reply: a message -> ORDERED sources. In the real system this
   comes from a model; here it is a pure function so the demonstrations are
   reproducible.

   `entries` is what the model EMITTED (6). `reply` is what the reader
   REPAIRED it into (7): entry 3 holds two forms and is spliced in place, and
   every source is rewritten by the reader. That is why counting forms by
   re-parsing the reply is wrong, and why the executed step plan is committed
   as data.")

(defn entries
  "Six entries, as emitted. Entry 0 writes; entry 1 READS ITS OWN WRITE."
  [{:keys [agent-id body]}]
  [(format "{:note %s :facts [[:db/add [:agent/id %s] :agent/log \"opened\"]]}"
           (pr-str (str "woke on " body)) (pr-str agent-id))
   (format "{:note (str \"my log lines visible right now: \" (count (db/q '[:find ?l :in $ ?a :where [?e :agent/id ?a] [?e :agent/log ?l]] %s)))}"
           (pr-str agent-id))
   "{:note (str \"sum \" (reduce + (map inc (range 1000))))}"
   ;; ONE entry, TWO forms -- the reader splices it in place
   "{:note \"repaired-a\"} {:note \"repaired-b\"}"
   (format "{:note \"about to hand off\" :facts [[:db/add [:agent/id %s] :agent/log \"handoff\"]]}"
           (pr-str agent-id))
   (if-let [nxt (first (:chain (read-string body)))]
     (format "{:note %s :messages [{:to %s :body %s}]}"
             (pr-str (str "messaging " nxt)) (pr-str nxt)
             (pr-str (pr-str {:chain (vec (rest (:chain (read-string body))))})))
     "{:note \"chain end\"}")])

(defn reply
  "Repair: splice multi-form entries and rewrite each source. 6 entries -> 7 steps."
  [message]
  (into [] (mapcat (fn [e] (map pr-str (read-string (str "[" e "]"))))) (entries message)))
