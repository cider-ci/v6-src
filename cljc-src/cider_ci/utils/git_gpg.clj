; Copyright © 2013 - 2022 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.utils.git-gpg
  (:require
    [clj-pgp.core :as pgp]
    [clj-pgp.signature]
    [clj-pgp.keyring]
    )
  (:import
    [javax.xml.bind DatatypeConverter]
    [org.apache.commons.io IOUtils]
    [org.apache.commons.io.input ReaderInputStream]
    [org.bouncycastle.bcpg ArmoredInputStream]
    [org.bouncycastle.openpgp PGPPublicKeyRingCollection PGPPublicKeyRing PGPPublicKey PGPUtil]
    [org.bouncycastle.openpgp.operator.jcajce JcaKeyFingerprintCalculator]
    ))


(defn read-pubkey [s]
  (PGPPublicKeyRingCollection.
    (PGPUtil/getDecoderStream (IOUtils/toInputStream s  "UTF-8"))
    (JcaKeyFingerprintCalculator.)))

(defn pub-keys [ascii-key]
  (clj-pgp.keyring/list-public-keys (read-pubkey ascii-key)))

(defn strip-whitespaces-linewise [s]
  (->> (clojure.string/split s #"\n")
       (map clojure.string/trim)
       (clojure.string/join "\n")))

(defn extract-ascii-commit-signature [cat-file-commit]
  (when-let [ascii-commit-signature (->> cat-file-commit
                                         (re-find #"(?is)gpgsig(.*END PGP SIGNATURE[^\n]*\n)")
                                         second)]
    (strip-whitespaces-linewise ascii-commit-signature)))

(defn exctract-commit-signature [cat-file-commit]
  "Returns a (non empty) seq of org.bouncycastle.openpgp.PGPSignature or nil."
  (when-let [bare-asccii-signature (extract-ascii-commit-signature cat-file-commit)]
    (->> bare-asccii-signature pgp/decode-signatures seq)))

(defn cat-file-commit-wo-signature [cat-file-commit]
  (clojure.string/replace cat-file-commit
                          #"(?is)gpgsig.*END PGP SIGNATURE[^\n]*\n" ""))


(defn hex-fingerprint [k]
  (DatatypeConverter/printHexBinary
    (.getFingerprint k )))

(defn valid-signature-fingerprint [cat-file-commit ascii-key]
  (when-let [signatures (exctract-commit-signature cat-file-commit)]
    (let [pkeys (pub-keys ascii-key)
          message (cat-file-commit-wo-signature cat-file-commit)]
      (some (fn [pkey]
              (some (fn [signature]
                      (try
                        (and (clj-pgp.signature/verify message signature pkey)
                             (hex-fingerprint pkey))
                        (catch Exception _ nil)))
                    signatures))
            pkeys))))

