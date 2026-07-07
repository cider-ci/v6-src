(ns cider-ci.executor.ports
  (:import
    [java.net InetAddress ServerSocket DatagramSocket]))

;; Tracks ports currently held by running trials (globally across all trials).
(defonce ^:private occupied* (atom #{}))

(defn- try-bind [^String inet-address ^long port]
  (try
    (with-open [_ss (ServerSocket. port 1 (InetAddress/getByName inet-address))
                _ds (DatagramSocket. port (InetAddress/getByName inet-address))]
      true)
    (catch Exception _ false)))

(defn- occupy!
  "Finds a free port in [min-port, max-port] for inet-address.
   Throws if no free port is found after 20 attempts."
  [^String inet-address min-port max-port]
  (let [range-size (inc (- max-port min-port))]
    (loop [attempts 0]
      (when (> attempts 20)
        (throw (ex-info "No free port found"
                        {:inet-address inet-address :min min-port :max max-port})))
      (let [port (+ min-port (rand-int range-size))]
        (if (contains? @occupied* port)
          (recur (inc attempts))
          (if (try-bind inet-address port)
            (do (swap! occupied* conj port) port)
            (recur (inc attempts))))))))

(defn reserve!
  "Reserves ports defined in task-spec :ports map.
   Returns a map of upper-cased env-var-name → port-number-string."
  [ports-spec]
  (into {}
        (map (fn [[k v]]
               (let [addr (or (:inet_address v) "localhost")
                     port (occupy! addr (:min v) (:max v))]
                 [(clojure.string/upper-case (name k)) (str port)]))
             ports-spec)))

(defn release!
  "Releases previously reserved ports (pass the map returned by reserve!)."
  [port-env-map]
  (doseq [[_ v] port-env-map]
    (swap! occupied* disj (Long/parseLong v))))
