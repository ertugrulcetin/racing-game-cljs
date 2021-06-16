(ns racing-game-cljs.views
  (:require
    ["@react-three/cannon" :refer [Physics usePlane useBox useCylinder useRaycastVehicle]]
    ["@react-three/drei" :refer [useGLTF Sky Environment PerspectiveCamera]]
    ["@react-three/fiber" :refer [Canvas useFrame]]
    ["react" :refer [useRef Suspense]]
    ["three" :as THREE]
    [applied-science.js-interop :as j]
    [clojure.string :as str]
    [goog.object :as ob]
    [racing-game-cljs.events :as events]
    [racing-game-cljs.subs :as subs]
    [re-frame.core :refer [dispatch subscribe]]
    [reagent.core :as r]))

(def canvas (r/adapt-react-class Canvas))
(def sky (r/adapt-react-class Sky))
(def environment (r/adapt-react-class Environment))
(def physics (r/adapt-react-class Physics))
(def suspense (r/adapt-react-class Suspense))
(def perspective-camera (r/adapt-react-class PerspectiveCamera))

(defn- plane [props]
  (usePlane (fn []
              (clj->js (merge props {:type "Static"
                                     :material "ground"}))))
  nil)

(defn- get-wheel-infos [{:keys [radius width height front back]
                         :or {radius 0.7
                              width 1.2
                              height -0.04
                              front 1.3
                              back -1.15}}]
  (let [wheel-info {:radius radius
                    :directionLocal [0 -1 0]
                    :suspensionStiffness 30
                    :suspensionRestLength 0.3
                    :axleLocal [-1 0 0]
                    :chassisConnectionPointLocal [1 0 1]
                    :useCustomSlidingRotationalSpeed true
                    :customSlidingRotationalSpeed -0.1
                    :frictionSlip 1.5}]
    [(assoc wheel-info :isFrontWheel true :chassisConnectionPointLocal [(/ (- width) 2) height front])
     (assoc wheel-info :isFrontWheel true :chassisConnectionPointLocal [(/ width 2) height front])
     (assoc wheel-info :isFrontWheel false :chassisConnectionPointLocal [(/ (- width) 2) height back])
     (assoc wheel-info :isFrontWheel false :chassisConnectionPointLocal [(/ width 2) height back])]))

(defn- chassis [{:keys [ref camera light] :as props}]
  (let [_ (.preload useGLTF "chassis-draco.glb")
        {:keys [nodes materials]} (j/lookup (useGLTF "chassis-draco.glb"))
        ^:js [_ api] (useBox (fn []
                               (clj->js (merge (select-keys props [:rotation :position :angularVelocity])
                                               {:mass 500
                                                :args [1.7 1 4]
                                                :allowSleep false}))) ref)]
    [:group
     {:ref ref
      :api api
      :dispose nil}
     [perspective-camera
      {:ref camera
       :makeDefault true
       :fov 75
       :rotation [0.5 js/Math.PI 0]
       :position [-100 80 -100]}]
     (when @light [:primitive {:object (.-target @light)}])
     [:group {:position [0 0 0] :scale [0.35 0.35 2.2]}
      [:group {:rotation [(/ (- js/Math.PI) 2) 0 0]}
       [:group {:rotation [(/ js/Math.PI 2) 0 0]}
        [:mesh
         {:castShadow true
          :receiveShadow true
          :geometry (j/get-in nodes [:Mesh_0 :geometry])
          :material (j/assoc! (j/get materials "Material.001")
                              :color #js {:r 0.7454042095350284 :g 0.43415363616478553 :b 0.02955683443236377})}]
        [:mesh {:geometry (j/get-in nodes [:Mesh_1 :geometry]) :material (j/get-in nodes [:Mesh_1 :material])}]
        [:mesh
         {:castShadow true
          :geometry (j/get-in nodes [:Mesh_2 :geometry])
          :material (j/assoc! (j/get materials "Material.003") :color #js {:r 0 :g 0 :b 0})}]
        [:mesh {:geometry (j/get-in nodes [:Mesh_3 :geometry]) :material (j/assoc! (j/get materials "Material.004")
                                                                                   :color #js {:r 1 :g 1 :b 1})}]
        [:mesh {:geometry (j/get-in nodes [:Mesh_4 :geometry]) :material (j/get materials "Material.005")}]
        [:mesh {:geometry (j/get-in nodes [:Mesh_5 :geometry]) :material (j/get materials "Material.006")}]
        [:mesh {:geometry (j/get-in nodes [:Mesh_6 :geometry]) :material (j/get-in nodes [:Mesh_6 :material])}]
        [:mesh {:geometry (j/get-in nodes [:Mesh_7 :geometry]) :material (j/get materials "Material.008")}]]]]]))

(defn- wheel [props]
  (let [wheel* (:ref props)
        _ (.preload useGLTF "wheel-draco.glb")
        {:keys [nodes materials]} (j/lookup (useGLTF "wheel-draco.glb"))
        _ (useCylinder (fn []
                         (clj->js (merge (dissoc props :ref)
                                         {:mass 1
                                          :type "Kinematic"
                                          :material "wheel"
                                          :collisionFilterGroup 0
                                          :args [(:radius props) (:radius props) 0.5 16]}))) wheel*)]
    [:group {:ref wheel* :dispose nil}
     [:group {:scale [(if (:leftSide props) -0.4 0.4) 0.4 0.4] :position [(if (:leftSide props) -0.2 0.2) 0 0]}
      [:mesh {:geometry (j/get-in nodes [:Mesh_14 :geometry]) :material (j/get materials "Material.002")}]
      [:mesh {:geometry (j/get-in nodes [:Mesh_15 :geometry]) :material (j/get materials "Material.009")}]
      [:mesh {:geometry (j/get-in nodes [:Mesh_16 :geometry]) :material (j/get materials "Material.007")}]]]))

(defn- render-loop [{:keys [api chassis* camera force steer max-brake]
                     :or {force 1500
                          steer 0.5
                          max-brake 50}}]
  (let [velocity 0
        {:keys [forward backward left right brake reset]} @(subscribe [::subs/controls])
        engine-value (if (or forward backward)
                       (* force (if (and forward (not backward)) -1 1))
                       0)
        _ (dotimes [_ 2] (j/call api :applyEngineForce engine-value 2))
        steering-value (if (or left right)
                         (* steer (if (and left (not right)) 1 -1))
                         0)]
    (dotimes [s 2] (j/call api :setSteeringValue steering-value s))
    (dotimes [b 4] (j/call api :setBrake (if brake max-brake 0) b))
    (when reset
      (j/call-in chassis* [:current :api :position :set] 0 0.5 0)
      (j/call-in chassis* [:current :api :velocity :set] 0 0 0)
      (j/call-in chassis* [:current :api :angularVelocity :set] 0 0.5 0)
      (j/call-in chassis* [:current :api :rotation :set] 0 (/ js/Math.PI 1.7) 0))
    (j/assoc-in! camera [:current :position :x] (.lerp THREE/MathUtils
                                                       (j/get-in camera [:current :position :x])
                                                       (/ (* (js/Math.sin steering-value) velocity) 2)
                                                       0.025))
    (j/assoc-in! camera [:current :position :z] (.lerp THREE/MathUtils
                                                       (j/get-in camera [:current :position :z])
                                                       (+ (- (js/Math.cos steering-value) (/ velocity 20)) -5.5)
                                                       0.025))
    (j/assoc-in! camera [:current :position :y] (.lerp THREE/MathUtils
                                                       (j/get-in camera [:current :position :y])
                                                       (+ 1.25 (* (/ engine-value 1000) -0.5))
                                                       0.01))
    (j/call-in camera [:current :lookAt] (j/get-in chassis* [:current :position]))))

(defn- vehicle [props]
  (let [chassis* (useRef)
        camera (useRef)
        wheel1 (useRef)
        wheel2 (useRef)
        wheel3 (useRef)
        wheel4 (useRef)
        light (r/atom nil)]
    (fn [{:keys [radius]
          :or {radius 0.7}
          :as props}]
      (let [wheel-infos (get-wheel-infos props)
            ^:js [vehicle api] (useRaycastVehicle #(clj->js {:chassisBody chassis*
                                                             :wheels [wheel1 wheel2 wheel3 wheel4]
                                                             :wheelInfos wheel-infos
                                                             :indexForwardAxis 2
                                                             :indexRightAxis 0
                                                             :indexUpAxis 1}))
            _ (useFrame #(render-loop {:api api
                                       :chassis* chassis*
                                       :camera camera}))]
        [:<>
         [:directionalLight
          {:ref #(reset! light %)
           :position [100 100 50]
           :intensity 1
           :castShadow true
           :shadowBias -0.001
           :shadowMapWidth 2048
           :shadowMapHeight 2048
           :shadowCameraLeft -80
           :shadowCameraRight 80
           :shadowCameraTop 80
           :shadowCameraBottom -80}]
         [:group
          {:ref vehicle
           :position [0 -0.4 0]
           :rotation [0 0 0]}
          [:f> chassis (assoc props :ref chassis* :camera camera :light light)]
          [:f> wheel {:ref wheel1 :radius radius :leftSide true}]
          [:f> wheel {:ref wheel2 :radius radius}]
          [:f> wheel {:ref wheel3 :radius radius :leftSide true}]
          [:f> wheel {:ref wheel4 :radius radius}]]]))))

(defn- track [props]
  (let [birds (r/atom {})]
    (fn [props]
      (let [_ (.preload useGLTF "track-draco.glb")
            {:keys [nodes materials]} (j/lookup (useGLTF "track-draco.glb"))
            config {:receiveShadow true
                    :castShadow true}
            _ (useFrame (fn [_ delta]
                          (j/update-in! (:bird1 @birds) [:rotation :y] + (/ delta 3.5))
                          (j/update-in! (:bird2 @birds) [:rotation :y] + (/ delta 4))
                          (j/update-in! (:bird3 @birds) [:rotation :y] + (/ delta 2.5))
                          (j/update-in! (:bird4 @birds) [:rotation :y] + (/ delta 4.5))))]
        [:group (merge props {:dispose nil})
         (for [palette (sort (keys (js->clj nodes :keywordize-keys true)))
               :when (str/includes? (name palette) "ColorPalette")
               :let [_ (j/assoc-in! nodes [palette :material :roughness] 1)
                     mesh [:mesh
                           (merge config
                                  {:key (name palette)
                                   :geometry (j/get-in nodes [palette :geometry])
                                   :material (j/get-in nodes [palette :material])}
                                  (when (#{:Cube075_ColorPalette_0 :Cube076_ColorPalette_0} palette)
                                    {:position [0.52 0 0.06]})
                                  (when (= :Plane089_ColorPaletteWater_0 palette)
                                    {:material (j/assoc! (j/get materials :ColorPaletteWater) :roughness 1)})
                                  (case palette
                                    :Plane049_ColorPalette_0 {:ref #(swap! birds assoc :bird1 %)}
                                    :Plane050_ColorPalette_0 {:ref #(swap! birds assoc :bird2 %)}
                                    :Plane051_ColorPalette_0 {:ref #(swap! birds assoc :bird3 %)}
                                    :Plane059_ColorPalette_0 {:ref #(swap! birds assoc :bird4 %)}
                                    nil))]]]
           mesh)]))))

(defn- handle-key [e pressed?]
  (condp some [(.-key e)]
    #{"ArrowUp" "w"} (dispatch [::events/set-control :forward pressed?])
    #{"ArrowDown" "s"} (dispatch [::events/set-control :backward pressed?])
    #{"ArrowLeft" "a"} (dispatch [::events/set-control :left pressed?])
    #{"ArrowRight" "d"} (dispatch [::events/set-control :right pressed?])
    #{" "} (dispatch [::events/set-control :brake pressed?])
    #{"r"} (dispatch [::events/set-control :reset pressed?])
    nil))

(defn main-panel []
  (r/create-class
    {:component-did-mount (fn []
                            (js/window.addEventListener "keyup" #(handle-key % false))
                            (js/window.addEventListener "keydown" #(handle-key % true)))
     :reagent-render (fn []
                       [canvas
                        {:dpr [1 1.5]
                         :shadows true
                         :camera {:position [0 5 15] :near 1 :far 200 :fov 50}}
                        [:fog {:attach "fog" :args ["white" 0 350]}]
                        [sky {:sun-position [100 10 100] :scale 1000}]
                        [:ambientLight {:intensity 0.1}]
                        [physics
                         {:broadphase "SAP"
                          :contactEquationRelaxation 4
                          :friction 1e-3
                          :allowSleep true}
                         [:f> plane {:rotation [(/ (- js/Math.PI) 2) 0 0]
                                     :userData {:id "floor"}}]
                         [:f> vehicle {:rotation [0 (/ js/Math.PI 2) 0]
                                       :position [0 2 0]
                                       :angularVelocity [0 0.5 0]
                                       :wheelRadius 0.3}]]
                        [suspense
                         {:fallback nil}
                         [:f> track
                          {:position [80 0 -170]
                           :rotation [0 0 0]
                           :scale 20}]
                         [environment
                          {:preset "night"}]]])}))
