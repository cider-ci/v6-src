(ns cider-ci.server.http.core)

(def ANTI_CRSF_TOKEN_COOKIE_NAME "cider-ci-anti-csrf-token")
(def HTTP_UNSAFE_METHODS #{:delete :patch :post :put})
(def HTTP_SAFE_METHODS #{:get :head :options :trace})
