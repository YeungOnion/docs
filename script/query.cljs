(ns stats
  "Some docs specific queries"
  (:require [logseq.db.rules :as rules]
            [datascript.core :as d]
            [nbb.core :as nbb]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.string :as string]
            [babashka.cli :as cli]
            [goog.string :as gstring]
            [logseq.graph-parser.property :as gp-property]
            [logseq.bb-tasks.nbb.cached-db :as cached-db]))

(defn- run-data-query [db {:keys [query result-transform]}]
  (let [post-transduce (map first)
        res (d/q (into query [:in '$ '%]) db (vals rules/query-dsl-rules))]
    (cond-> (into [] post-transduce res)
            result-transform
            ((fn [x] (eval (list result-transform x)))))))

(defn- propertify
  [result]
  (map #(-> (:block/properties %)
            (assoc :name (:block/original-name %))
            (dissoc :type))
       result))

 (defn print-table
   [rows & {:keys [fields]}]
   (if fields (pprint/print-table fields rows) (pprint/print-table rows))
   (println "Total:" (count rows)))

(def queries
  {:features
   {:query '[:find (pull ?b [*])
             :where
             (page-property ?b :type "Feature")]
    :columns [:name :platform]
    :result-transform propertify}
   :types
   {:query '[:find (pull ?b [*])
             :where
             (page-property ?b :type "Class")]
    :columns [:name]
    :result-transform propertify}
   :properties
   {:query '[:find ?p
             :where
             [_ :block/properties ?p]]
    :result-transform (fn [res]
                        (->> (map keys res)
                             (apply concat)
                             set
                             ((fn [x]
                                (set/difference
                                 x
                                 (gp-property/hidden-built-in-properties)
                                 (gp-property/editable-built-in-properties))))
                             ;; remove extended proeprties that can't be accessed in scripts
                             (remove #(string/starts-with? (name %) "card-"))
                             sort
                             (map #(hash-map :name %))))}
   :tasks
   {:query '[:find (pull ?b [* {:block/page [:block/original-name]}])
             :where
             (task ?b #{"TODO" "DOING"})
             (page-ref ?b "docs")]
    :result-transform (fn [res]
                        (map #(hash-map :content (:block/content %)
                                        :name (get-in % [:block/page :block/original-name])) res))}})

(defn -main
  [args]
  (let [options (cli/parse-opts args)
        db (cached-db/read-db)
        {:keys [columns] :as query-map}
        (or (get queries (keyword (first args)))
            (throw (ex-info (gstring/format "No query '%s' found" (first args))
                            {:babashka/exit 1})))
        res (run-data-query db query-map)]
    (if (:expand options)
      (pprint/pprint res)
      (if columns
        (print-table res :fields columns)
        (print-table res)))))

(when (= nbb/*file* (:file (meta #'-main)))
  (-main *command-line-args*))
