(ns cider-ci.utils.cron
  "Cron expressions of `run_when` entries of type `cron` (port of the legacy
   cider-ci.shared.cron on cron-utils).

   Unix 5-field syntax: minute, hour, day of month, month, day of week.
   Expressions are always evaluated in UTC, whatever the server's zone: the
   configurations of madek and leihs document their times as UTC. Only weekly
   or shorter schedules are supported, month and day of month must be `*`
   (legacy restriction; it keeps the 'fired within the last n minutes' test
   cheap and unambiguous)."
  (:require
    [clojure.core.memoize :as memo]
    [clojure.string :as str])
  (:import
    [com.cronutils.descriptor CronDescriptor]
    [com.cronutils.model.definition CronDefinitionBuilder]
    [com.cronutils.model.time ExecutionTime]
    [com.cronutils.parser CronParser]
    [java.time Duration ZonedDateTime ZoneOffset]
    [java.util Locale]))


(def ^:private cron-definition
  (.. (CronDefinitionBuilder/defineCron)
      withMinutes and
      withHours and
      withDayOfMonth supportsHash supportsL supportsW supportsQuestionMark and
      withMonth and
      withDayOfWeek supportsHash supportsL supportsW supportsQuestionMark and
      instance))

(def ^:private parser (CronParser. cron-definition))

(def ^:private descriptor (CronDescriptor/instance Locale/UK))


(defn- invalid [cron-s message & [cause]]
  (ex-info (str "Invalid cron expression '" cron-s "': " message)
           {:status 422 :value cron-s} cause))

(defn- weekly-only! [cron-s]
  (let [[_ _ day-of-month month] (str/split (str/trim cron-s) #"\s+")]
    (when-not (contains? #{"*" "?"} month)
      (throw (invalid cron-s (str "only weekly timers are supported, the month field '"
                                  month "' must be '*'."))))
    (when-not (contains? #{"*" "?"} day-of-month)
      (throw (invalid cron-s (str "only weekly timers are supported, the day of month field '"
                                  day-of-month "' must be '*'."))))))

(defn- parse!-unmemoized [cron-s]
  (when-not (string? cron-s)
    (throw (invalid cron-s "the value must be a string.")))
  (let [cron (try (doto (.parse parser (str/trim cron-s)) .validate)
                  (catch IllegalArgumentException e
                    (throw (invalid cron-s (.getMessage e) e))))]
    (weekly-only! cron-s)
    cron))

(def parse!
  "The parsed and validated expression; throws an ex-info with :status 422
   when it is not a valid weekly cron expression."
  (memo/lru parse!-unmemoized :lru/threshold 1000))

(defn valid?
  "Whether cron-s is a valid weekly cron expression."
  [cron-s]
  (try (parse! cron-s) true
       (catch clojure.lang.ExceptionInfo _ false)))

(def ^:private execution-time
  (memo/lru (fn [cron-s] (ExecutionTime/forCron (parse! cron-s)))
            :lru/threshold 1000))

(defn describe
  "The expression in English, e.g. \"at 04:30\" for \"30 4 * * *\"."
  [cron-s]
  (.describe descriptor (parse! cron-s)))

(defn now-utc ^ZonedDateTime []
  (ZonedDateTime/now ZoneOffset/UTC))

(defn seconds-since-last-execution
  "Seconds from the last time cron-s fired (UTC) before `now` to `now`."
  [cron-s ^ZonedDateTime now]
  (let [now  (.withZoneSameInstant now ZoneOffset/UTC)
        last (.lastExecution (execution-time cron-s) now)]
    (when (.isPresent last)
      (.getSeconds (Duration/between (.get last) now)))))

(defn fire?
  "True when cron-s fired less than `max-minutes` minutes before `now`
   (default: now). Strictly less: a job created at or after the scheduled
   time can then not be older than the window while the window is still
   open, which is what keeps a `rerun` cron from running twice (see
   cider-ci.server.jobs.cron-trigger)."
  ([cron-s max-minutes] (fire? cron-s max-minutes (now-utc)))
  ([cron-s max-minutes now]
   (boolean (some-> (seconds-since-last-execution cron-s now)
                    (< (* 60 max-minutes))))))
