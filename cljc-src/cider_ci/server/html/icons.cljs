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
(defn dismiss [] [FontAwesomeIcon {:icon (.-faTimes solids) :className ""}])
(defn delete [] [FontAwesomeIcon {:icon (.-faTrash solids) :className ""}])
(defn email [] [FontAwesomeIcon {:icon (.-faEnvelope solids) :className ""}])
(defn password [] [FontAwesomeIcon {:icon (.-faKey solids) :className ""}])
(defn project [] [FontAwesomeIcon {:icon (.-faGitAlt brands) :className ""}])
(def projects project)
(defn user [] [FontAwesomeIcon {:icon (.-faUser solids) :className ""}])
(defn users [] [FontAwesomeIcon {:icon (.-faUsers solids) :className ""}])
(defn user-admin [] [FontAwesomeIcon {:icon (.-faUserGear solids) :className ""}])
(defn create [] [FontAwesomeIcon {:icon (.-faCirclePlus solids) :className ""}])
(defn server [] [FontAwesomeIcon {:icon (.-faServer solids) :className ""}])
(defn sign-in [] [FontAwesomeIcon {:icon (.-faRightToBracket solids) :className ""}])
(defn jobs [] [FontAwesomeIcon {:icon (.-faListCheck solids) :className ""}])
(defn config [] [FontAwesomeIcon {:icon (.-faGear solids) :className ""}])
(defn filter-icon [] [FontAwesomeIcon {:icon (.-faFilter solids) :className ""}])
(defn play [] [FontAwesomeIcon {:icon (.-faPlay solids) :className ""}])
(defn stop [] [FontAwesomeIcon {:icon (.-faStop solids) :className ""}])
(defn retry [] [FontAwesomeIcon {:icon (.-faRotateRight solids) :className ""}])
(defn fetch [] [FontAwesomeIcon {:icon (.-faRotate solids) :className ""}])
(defn edit [] [FontAwesomeIcon {:icon (.-faPenToSquare solids) :className ""}])
(defn signed [] [FontAwesomeIcon {:icon (.-faCircleCheck solids) :className ""}])
(defn unsigned [] [FontAwesomeIcon {:icon (.-faCircleXmark solids) :className ""}])
(defn unknown-signature [] [FontAwesomeIcon {:icon (.-faCircleQuestion solids) :className ""}])
(defn file-code [] [FontAwesomeIcon {:icon (.-faFileCode solids) :className ""}])
(defn code-branch [] [FontAwesomeIcon {:icon (.-faCodeBranch solids) :className ""}])
(defn play-circle [] [FontAwesomeIcon {:icon (.-faCirclePlay solids) :className ""}])
(defn clipboard [] [FontAwesomeIcon {:icon (.-faClipboard solids) :className ""}])
(defn spinner [] [:span.fa-4x [FontAwesomeIcon {:icon (.-faSpinner solids) :className "fa-spin"}]])
