(ns cider-ci.server.state
  (:require
    [reagent.core :as reagent]
    [taoensso.timbre :refer [debug info warn error spy]])
  (:require-macros
    [reagent.ratom :as ratom :refer [reaction]]))


(defonce routing-state* (reagent/atom {}))

(defonce state* (reaction
                  {:routing-state @routing-state*}
                  ))
