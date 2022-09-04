; Copyright © 2013 - 2022 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.fetch-and-update.db-schema
  (:refer-clojure :exclude [str keyword])
  (:require
    [cider-ci.utils.core :refer [keyword str]]
    [schema.core :as schema]
    [tick.core :as tick :refer [now]]
    )
  (:import [java.time Instant]))

(defn default []
  {
   :last_fetched_at nil
   :last_error nil
   :last_error_at nil
   :updated_at (now)
   :pending? true
   :state "initializing"
   })

(def schema
  {:last_fetched_at (schema/maybe Instant)
   :last_error (schema/maybe String)
   :last_error_at (schema/maybe Instant)
   :updated_at Instant
   :state (schema/enum
            "error"
            "fetching"
            "initializing"
            "ok"
            "waiting")
   :pending? Boolean })

(schema/validate schema (default))
