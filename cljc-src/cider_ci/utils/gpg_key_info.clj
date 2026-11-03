(ns cider-ci.utils.gpg-key-info
  "What a public GPG key (ASCII armored) contains, for the key pages:
   the primary key with its user ids, expiration and revocation, and its
   subkeys."
  (:require
    [cider-ci.utils.git-gpg :as git-gpg])
  (:import
    [org.bouncycastle.openpgp PGPPublicKey]))


(def ^:private algorithms
  ;; org.bouncycastle.bcpg.PublicKeyAlgorithmTags
  {1 "RSA" 2 "RSA (encrypt only)" 3 "RSA (sign only)" 16 "ElGamal" 17 "DSA"
   18 "ECDH" 19 "ECDSA" 20 "ElGamal" 22 "EdDSA" 25 "X25519" 26 "X448"
   27 "Ed25519" 28 "Ed448"})

(defn- instant [^java.util.Date d] (some-> d .toInstant str))

(defn- key-info [^PGPPublicKey k]
  (let [created       (.getCreationTime k)
        valid-seconds (.getValidSeconds k)
        expires       (when (pos? valid-seconds)
                        (java.util.Date. (+ (.getTime created) (* 1000 valid-seconds))))]
    {:fingerprint (git-gpg/hex-fingerprint k)
     :key_id      (format "%016X" (.getKeyID k))
     :algorithm   (get algorithms (.getAlgorithm k) (str "algorithm " (.getAlgorithm k)))
     :bits        (.getBitStrength k)
     :created_at  (instant created)
     :expires_at  (instant expires)
     :expired     (boolean (and expires (.before expires (java.util.Date.))))
     :revoked     (.hasRevocation k)
     :usage       (if (.isEncryptionKey k) "encryption" "signing")}))

(defn details
  "The primary key(s) of the armored key with :user_ids and :subkeys, or
   nil when it cannot be parsed. A key ring normally holds one primary key;
   all of them are returned."
  [ascii-key]
  (try
    (->> (git-gpg/pub-keys ascii-key)
         (partition-by #(.isMasterKey ^PGPPublicKey %))
         (partition-all 2)
         (keep (fn [[[^PGPPublicKey master] subkeys]]
                 (when (.isMasterKey master)
                   (assoc (key-info master)
                          :user_ids (vec (iterator-seq (.getUserIDs master)))
                          :subkeys  (mapv key-info subkeys)))))
         vec
         not-empty)
    (catch Exception _ nil)))
