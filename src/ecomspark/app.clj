(ns ecomspark.app
  (:require [ring.middleware.resource :as resource-mw]
            [ring.middleware.content-type :as ctype-mw]
            [ring.middleware.session :as session-mw]
            [ring.middleware.params :as params-mw]
            [ring.middleware.json :as json-mw]
            [ring.middleware.reload :as reload-mw]

            [ecomspark.views.pages :as pages]
            [ecomspark.views.cart :as cart]
            [ecomspark.views.product :as product]
            [ecomspark.views.brand :as brand]
            [clojure.string :as str]))


;;; "DB"

(defn get-product [id]
  {:id    id
   :price (+ 10 (mod (hash id) 90))
   :pic   (format "https://picsum.photos/seed/%s/240/300"
            (hash id))})

(defn get-brand [id]
  {:id     id
   :pic    (format "https://picsum.photos/seed/%s/64/64" (hash id))
   :name   (str "Brand " id)
   :rating (float (+ (-> id hash (mod 5))
                     (-> id hash (mod 10) (/ 10))))
   :stars  (mod (hash id) 10000)})

;;; Handlers

(defn cart-add [{:keys [form-params session request-method]}]
  (let [id   (get form-params "id")
        cart (set (:cart session))]

    (cond
      (nil? id)
      {:status 400
       :body   {:error "No id supplied"}}

      (= request-method :post)
      (let [cart (conj cart id)]
        {:status  200
         :session {:cart cart}
         :partial cart/BuyResult
         :page    (fn [_]
                    {:status  303
                     :headers {"Location" "/cart"}})
         :body    {:success true
                   :cart    cart
                   :count   (count cart)}})

      (= request-method :delete)
      (let [cart (disj cart id)]
        {:status  200
         :session {:cart cart}
         :partial cart/RemoveResult
         :page    (fn [_]
                    {:status  303
                     :headers {"Location" "/cart"}})
         :body    {:success true
                   :cart    cart
                   :count   (count cart)}})

      :else
      {:status 405
       :body   "Method not allowed"})))

(defn brand-add [{:keys [form-params session request-method]}]
  (let [id       (get form-params "id")
        brandsub (set (:brandsub session))]

    (cond
      (nil? id)
      {:status 400
       :body   {:error "No id supplied"}}

      (= request-method :post)
      (let [brandsub (conj brandsub id)]
        {:status  200
         :session {:brandsub brandsub}
         :partial brand/SubscribeResult
         :page    (fn [_]
                    {:status  303
                     :headers {"Location" "/brands"}})
         :body    {:success  true
                   :brandsub brandsub
                   :count    (count brandsub)}})

      (= request-method :delete)
      (let [brandsub (disj brandsub id)]
        {:status  200
         :session {:brandsub brandsub}
         :partial brand/UnsubscribeResult
         :page    (fn [_]
                    {:status  303
                     :headers {"Location" "/brands"}})
         :body    {:success  true
                   :brandsub brandsub
                   :count    (count brandsub)}})

      :else
      {:status 405
       :body   "Method not allowed"})))


(defn cart [{:keys [session]}]
  (let [cart (:cart session)]
    {:status  200
     :body    {:count    (count cart)
               :products (mapv get-product cart)}
     :partial cart/HeaderCart
     :page    pages/Cart}))


(defn products [{:keys [query-params]}]
  (let [offset (Integer. (get query-params "offset" 0))]
    {:status  200
     :body    {:offset   (+ 24 offset)
               :products (for [i (range offset (+ 24 offset))]
                           (get-product (str i)))}
     :partial product/ProductList
     :page    pages/Index
     :headers {"Cache-Control" "max-age=3600"
               "Vary"          "Accept"}}))

(defn brands [{:keys [query-params]}]
  (let [offset (Integer. (get query-params "offset" 0))
        n      10]
    {:status  200
     :body    {:offset (+ n offset)
               :brands (for [i (range offset (+ n offset))]
                         (get-brand (str i)))}
     :partial brand/BrandList
     :page    pages/Brands}))


;;; TwinSpark support

(defn render-request [func res]
  (let [html (func (:body res))]
    (if (map? html)
      (merge res html)
      (-> res
          (update :headers assoc "Content-Type" "text/html; charset=UTF-8")
          (assoc :body html)))))


(defn accepts? [req accept]
  (some-> (get-in req [:headers "accept"])
    (str/starts-with? accept)))


(defn twinspark-mw [handler]
  (fn [req]
    (let [res (handler req)]
      (cond
        (and (:partial res)
             (accepts? req "text/html+partial"))
        (render-request (:partial res) res)

        (and (:page res)
             (accepts? req "text/html"))
        (render-request (:page res) res)

        :else
        res))))


(defn methods-mw [handler]
  (fn [{:keys [request-method form-params] :as req}]
    (if (and (= request-method :post)
             (contains? form-params "_method"))
      (let [method (keyword (get form-params "_method"))]
        (handler (assoc req :request-method method)))
      (handler req))))


;;; HTTP handler

(defn h404 [_req]
  {:status  404
   :headers {"Content-Type" "text/plain"}
   :body    "Not Found"})


(defn -app [req]
  ;;; ROUTER
  (let [func (case (:uri req)
               "/"          #'products
               "/brands"    #'brands
               "/cart"      #'cart
               "/cart/add"  #'cart-add
               "/brand/add" #'brand-add
               h404)]
    (func req)))


(def app (-> -app
             (reload-mw/wrap-reload)
             (twinspark-mw)
             (methods-mw)
             (json-mw/wrap-json-response)
             (params-mw/wrap-params)
             (session-mw/wrap-session)
             (resource-mw/wrap-resource "public")
             (ctype-mw/wrap-content-type)))
