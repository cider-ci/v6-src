; Copyright © 2013 - 2022 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.utils.daemon
  (:require
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(defmacro defdaemon [daemon-name secs-pause & body]
  (let [stop (gensym "_stop_")
        start-fn (symbol (str "start-" daemon-name))
        stop-fn (symbol (str "stop-" daemon-name))]
    `(do

       (defonce ~stop (atom (fn [])))

       (defn ~stop-fn []
         (@~stop))

       (defn ~start-fn []
         (~stop-fn)
         (let [done# (atom false)
               runner# (future (info "daemon " ~daemon-name " started")
                               (loop []
                                 (when-not @done#
                                   (try
                                     ~@body
                                     (Thread/sleep (long (Math/ceil (* ~secs-pause 1000))))
                                     (catch Throwable _))
                                   (recur))))]
           (reset! ~stop (fn []
                           (reset! done# true)
                           (future-cancel runner#)
                           ;@runner#
                           (info "daemon " ~daemon-name "stopped")))

           (.addShutdownHook (Runtime/getRuntime)
                             (Thread. (fn [] (~stop-fn))))

           )))))

;(macroexpand-1 '(defdeamon "blah" 10 (info "looping ...")))
;(macroexpand-1 '(define "blah"  10 (info "looping ...")))
