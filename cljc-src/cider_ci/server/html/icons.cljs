(ns cider-ci.server.html.icons
  (:refer-clojure :exclude [next])
  (:require
    ["@fortawesome/free-brands-svg-icons" :as brands]
    ["@fortawesome/free-regular-svg-icons" :as regulars]
    ["@fortawesome/free-solid-svg-icons" :as solids]
    ["@fortawesome/react-fontawesome" :as fa-react-fontawesome :refer [FontAwesomeIcon]]
    ))


(defn delete [] (FontAwesomeIcon #js{:icon solids/faTimes :className ""}))
(defn email [] (FontAwesomeIcon #js{:icon solids/faEnvelope :className ""}))
(defn password [] (FontAwesomeIcon #js{:icon solids/faKey :className ""}))
(defn project [] (FontAwesomeIcon #js{:icon brands/faGitAlt :className ""}))
(def projects project)
(defn user [] (FontAwesomeIcon #js{:icon solids/faUser :className ""}))
(defn user-admin [] (FontAwesomeIcon #js{:icon solids/faUserGear :className ""}))
(defn create [] (FontAwesomeIcon #js{:icon solids/faCirclePlus :className ""}) )
