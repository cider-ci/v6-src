(ns cider-ci.server.html.icons
  (:refer-clojure :exclude [next])
  (:require
   ["@fortawesome/free-brands-svg-icons" :as brands]
   ["@fortawesome/free-regular-svg-icons" :as regulars]
   ["@fortawesome/free-solid-svg-icons" :as solids]
   ["@fortawesome/react-fontawesome" :as fa]
   [reagent.core :as r]))

(def FontAwesomeIcon (r/adapt-react-class (.-FontAwesomeIcon fa)))

(defn commit [] [FontAwesomeIcon {:icon (.-faCodeCommit solids) :className ""}])
(def commits commit)
(defn delete [] [FontAwesomeIcon {:icon (.-faTimes solids) :className ""}])
(defn email [] [FontAwesomeIcon {:icon (.-faEnvelope solids) :className ""}])
(defn password [] [FontAwesomeIcon {:icon (.-faKey solids) :className ""}])
(defn project [] [FontAwesomeIcon {:icon (.-faGitAlt brands) :className ""}])
(def projects project)
(defn user [] [FontAwesomeIcon {:icon (.-faUser solids) :className ""}])
(defn user-admin [] [FontAwesomeIcon {:icon (.-faUserGear solids) :className ""}])
(defn create [] [FontAwesomeIcon {:icon (.-faCirclePlus solids) :className ""}])
