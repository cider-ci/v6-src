(ns cider-ci.utils.identicon
  "GitHub style identicons as SVG: a 5x5 sprite, mirrored left to right, in
   one colour on light grey, derived from an MD5 hash the way identicon.js
   reproduces GitHub's own (GitHub hashes its internal user id, so the
   images only look alike, they never match GitHub's for the same person).

   From the 32 hex chars of the hash: chars 0-4 fill the middle column top
   to bottom, 5-9 the two columns next to it and 10-14 the outer columns,
   an even nibble is foreground, an odd one background; the hue is the
   last 7 chars divided by 0xFFFFFFF, with saturation 0.7 and lightness
   0.5. The hash is usually the MD5 of a lower-cased email address."
  (:require
    [clojure.string :as str])
  (:import
    [java.security MessageDigest]))


(defn md5-hex
  "The MD5 of the string as 32 lower-case hex chars."
  [^String s]
  (let [digest (.digest (MessageDigest/getInstance "MD5") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) digest))))


(def ^:private background "#f0f0f0")

(defn- hsl->rgb
  "h, s, l in [0, 1] to [r g b] in 0-255, as in identicon.js."
  [h s l]
  (let [f (fn [n]
            (let [k (mod (+ n (* h 12)) 12)
                  a (* s (min l (- 1 l)))
                  v (- l (* a (max -1 (min (- k 3) (- 9 k) 1))))]
              (Math/round (* 255 v))))]
    [(f 0) (f 8) (f 4)]))

(defn foreground-colour
  "The identicon colour of the hash as a CSS hex colour."
  [hash]
  (let [hue (/ (double (Long/parseLong (subs hash (- (count hash) 7)) 16))
               (double 0xfffffff))
        [r g b] (hsl->rgb hue 0.7 0.5)]
    (format "#%02x%02x%02x" r g b)))

(defn pattern
  "The 5x5 sprite as 5 rows of 5 booleans (true = foreground)."
  [hash]
  (let [on? (fn [i] (even? (Character/digit ^char (.charAt ^String hash i) 16)))]
    (vec (for [row (range 5)]
           (vec (for [col (range 5)]
                  ;; column 2 from chars 0-4, columns 1 and 3 from 5-9, 0 and 4 from 10-14
                  (on? (+ row (* 5 (- 2 (Math/abs (- col 2))))))))))))

(defn valid-hash? [hash]
  (boolean (and (string? hash) (re-matches #"[0-9a-f]{32}" hash))))

(defn- fmt
  "Locale independent number formatting (a German locale would print 16,8)."
  [x]
  (String/format java.util.Locale/ROOT "%.1f" (to-array [(double x)])))

(defn svg
  "The identicon of the (MD5, lower-case hex) hash as an SVG document that
   scales to any size: 8% margin, cells of the remaining 84%. Anything that
   is not such a hash is hashed first."
  [hash]
  (let [hash   (if (valid-hash? hash) hash (md5-hex (str hash)))
        colour (foreground-colour hash)
        cell   (/ 84.0 5)
        rects  (for [[row cols] (map-indexed vector (pattern hash))
                     [col on?]  (map-indexed vector cols)
                     :when on?]
                 (str "<rect x=\"" (fmt (+ 8 (* col cell))) "\" y=\"" (fmt (+ 8 (* row cell)))
                      "\" width=\"" (fmt cell) "\" height=\"" (fmt cell) "\"/>"))]
    (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\" width=\"100\" height=\"100\">"
         "<rect width=\"100\" height=\"100\" fill=\"" background "\"/>"
         "<g fill=\"" colour "\" shape-rendering=\"crispEdges\">" (str/join rects) "</g>"
         "</svg>")))
