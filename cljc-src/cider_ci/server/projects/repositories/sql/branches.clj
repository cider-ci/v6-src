; Copyright © 2013 - 2016 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.sql.branches
  (:refer-clojure :exclude [str keyword])
  (:require [cider-ci.utils.core :refer [keyword str]])
  (:require
    [next.jdbc.sql :refer [insert! query]]
    [clojure.java.jdbc :as jdbc]
    [cider-ci.server.db.core :as db]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(defn create! [ds params]
  (insert! ds :branches params db/builder-fn-options-default))

(defn for-repository [ds canonic-id]
  (query ds
         ["SELECT * FROM branches WHERE repository_id = ? " canonic-id]))


