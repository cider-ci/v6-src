; Copyright © 2013 - 2016 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.state.db
  (:require
    [logbug.debug :as debug]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(def db* (atom {:repositories {}
               :users {}}))

(defn watch [k fun]
  (apply add-watch [db* k fun]))

(add-watch db* :debug-watch
           (fn [_ _ before after]
             (debug 'DB-CHANGE {:before (:repositories before) :after (:repositories after)})
             ))

;(fipp.edn/pprint @db*)
;(debug/debug-ns *ns*)
