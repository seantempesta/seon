(let [ts (clojure.edn/read-string (slurp "/private/tmp/claude-501/-Users-sean-src-seon-next/dbf7ed59-fa19-4733-acb2-d111be4c61e9/scratchpad/to-embed.edn"))
      vs (vec (mapcat (fn [b] (let [r (seon.agent/fx! :embed {:texts (vec b)} {})] (or (:vectors (:value r)) (throw (ex-info "embed failed" {:r r}))))) (partition-all 64 ts)))]
  (spit "/private/tmp/claude-501/-Users-sean-src-seon-next/dbf7ed59-fa19-4733-acb2-d111be4c61e9/scratchpad/embedded.edn" (with-out-str (doseq [v vs] (prn (mapv #(/ (Math/round (* 1e5 (double %))) 1e5) v)))))
  (count vs))
