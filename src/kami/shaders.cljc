(ns kami.shaders
  "Game shaders as data — the kami.wgsl EDN AST for the lit instanced renderer's fragment lighting.
   The lighting model (hemisphere ambient, Lambert, Blinn-Phong spec, Fresnel rim, PCF shadow,
   ACES filmic tonemap, gamma) is authored as data, so it reads and forks like the scene, and ONE
   source generates the WGSL the web (kami.webgpu) runs — and, in time, the native kami-webgpu-rs
   path (parity by source). The struct/bindings/shadow/vertex preamble stays a template in
   kami.webgpu; this is the fragment a designer actually tunes. `.cljc` → browser + bb/JVM."
  (:require [clojure.walk :as walk]
            [kami.wgsl :as w]))

;; lighting inputs come from the G uniform (g.light_a..d pack the tunables) and the VO varying `i`.
(def lit-fs-body
  [[:let :N [:normalize :i.n]]
   [:let :L [:normalize [:- :g.sun-dir.xyz]]]
   [:let :eye [:vec3 :g.sun-dir.w :g.sun-col.w :g.sky.w]]
   [:let :V [:normalize [:- :eye :i.wpos]]]
   [:let :H [:normalize [:+ :L :V]]]
   [:let :ndl [:max [:dot :N :L] 0.0]]
   [:let :metallic [:clamp :i.mat.x 0.0 1.0]]
   [:let :rough [:clamp :i.mat.y 0.04 1.0]]
   [:let :emissive :i.mat.z]
   [:let :amb [:mix :g.light-a.rgb [:* :g.sky.rgb :g.light-a.w] [:+ [:* :N.y 0.5] 0.5]]]
   [:let :shininess [:mix :g.light-c.x :g.light-c.y [:- 1.0 :rough]]]
   [:let :specStr [:mix :g.light-b.x :g.light-b.y :metallic]]
   [:let :specTint [:mix [:vec3 1.0] :i.col :metallic]]
   [:let :spec [:* [:pow [:max [:dot :N :H] 0.0] :shininess] :specStr]]
   [:let :rim [:* [:pow [:- 1.0 [:max [:dot :N :V] 0.0]] :g.light-b.w] :g.light-b.z]]
   [:let :sh [:shadow :i.wpos :ndl]]
   [:var :c [:+ [:* :i.col [:+ :amb [:* :ndl :g.sun-col.rgb :g.light-c.z [:- 1.0 [:* :metallic :g.light-c.w]] :sh]]]
                [:* :specTint :g.sun-col.rgb :spec :sh]
                [:* :g.sky.rgb :rim]
                [:* :i.col :emissive]]]
   ;; Narkowicz ACES filmic fit: preserves highlight colour and rolls off
   ;; overbright emissive/sun contributions more naturally than Reinhard.
   [:set :c [:clamp
             [:/ [:* :c [:+ [:* 2.51 :c] [:vec3 0.03]]]
              [:+ [:* :c [:+ [:* 2.43 :c] [:vec3 0.59]]] [:vec3 0.14]]]
             [:vec3 0.0] [:vec3 1.0]]]
   [:set :c [:pow :c [:vec3 [:/ 1.0 :g.light-d.x]]]] ;; gamma
   [:return [:vec4 :c 1.0]]])

(defn lit-fs
  "The lit instanced renderer's fragment shader, generated from data."
  []
  (apply w/func :fs {:stage :fragment :params [[:i :VO]] :ret [:loc 0 [:vec4 :f32]]} lit-fs-body))

;; ── the full shader as data: uniforms, shadow-map bindings, PCF shadow fn, varyings, vertex ──────

(def G-fields
  [[:vp :mat4] [:sun-dir :vec4] [:sun-col :vec4] [:sky :vec4] [:light-vp :mat4]
   [:light-a :vec4] [:light-b :vec4] [:light-c :vec4] [:light-d :vec4]])

;; 3×3 PCF percentage-closer shadow lookup (clamps outside the light frustum to lit).
(def shadow-fn-body
  [[:let :lc [:* :g.light-vp [:vec4 :wpos 1.0]]]
   [:let :ndc [:/ :lc.xyz :lc.w]]
   [:let :uv [:vec2 [:+ [:* :ndc.x 0.5] 0.5] [:- 0.5 [:* :ndc.y 0.5]]]]
   [:if [:|| [:< :uv.x 0.0] [:> :uv.x 1.0] [:< :uv.y 0.0] [:> :uv.y 1.0] [:> :ndc.z 1.0]]
    [[:return 1.0]]]
   [:let :bias [:max [:* :g.light-d.y [:- 1.0 :ndl]] :g.light-d.z]]
   [:let :texel :g.light-d.w]
   [:var :lit 0.0]
   [:for [:var :dx [:i -1]] [:<= :dx [:i 1]] [:++ :dx]
    [:for [:var :dy [:i -1]] [:<= :dy [:i 1]] [:++ :dy]
     [:+= :lit [:textureSampleCompareLevel :shadowMap :shadowSamp
                [:+ :uv [:* [:vec2 [:f32 :dx] [:f32 :dy]] :texel]]
                [:- :ndc.z :bias]]]]]
   [:return [:/ :lit 9.0]]])

(def VO-fields
  [[:clip [:vec4 :f32] {:builtin :position}] [:n [:vec3 :f32] {:location 0}]
   [:col [:vec3 :f32] {:location 1}] [:wpos [:vec3 :f32] {:location 2}] [:mat [:vec3 :f32] {:location 3}]])

;; per-instance model matrix (m0..m3) + color + material → clip-space + world varyings.
(def vs-body
  [[:let :model [:mat4 :m0 :m1 :m2 :m3]]
   [:let :world [:* :model [:vec4 :pos 1.0]]]
   [:decl :o :VO]
   [:set :o.clip [:* :g.vp :world]]
   [:set :o.n [:normalize [:. [:* :model [:vec4 :normal 0.0]] :xyz]]]
   [:set :o.col :color.rgb]
   [:set :o.wpos :world.xyz]
   [:set :o.mat :material.xyz]
   [:return :o]])

;; ── the depth-only shadow pass: instances from the sun's POV into the shadow map ──────────────────
;; only needs vp..light_vp (the first 5 G fields), not the lighting tunables.
(def shadow-vs-body
  [[:let :model [:mat4 :m0 :m1 :m2 :m3]]
   [:return [:* :g.light-vp :model [:vec4 :pos 1.0]]]])

(defn shadow-shader
  "The depth-only shadow-map vertex pass, generated from data."
  []
  (w/shader
   (w/struct* :G (vec (take 5 G-fields)))
   (w/binding* {:group 0 :binding 0 :space :uniform} :g :G)
   (apply w/func :vs {:stage :vertex
                      :params [[:pos [:vec3 :f32] {:location 0}] [:normal [:vec3 :f32] {:location 1}]
                               [:m0 [:vec4 :f32] {:location 2}] [:m1 [:vec4 :f32] {:location 3}]
                               [:m2 [:vec4 :f32] {:location 4}] [:m3 [:vec4 :f32] {:location 5}]
                               [:color [:vec4 :f32] {:location 6}] [:material [:vec4 :f32] {:location 7}]]
                      :ret [:builtin :position [:vec4 :f32]]} shadow-vs-body)))

(defn lit-shader
  "The complete lit instanced renderer WGSL — generated entirely from data (struct/bindings/shadow/
   vertex/fragment). One source; the web (kami.webgpu) and, in time, native run the same shader."
  []
  (w/shader
   (w/struct* :G G-fields)
   (w/binding* {:group 0 :binding 0 :space :uniform} :g :G)
   (w/binding* {:group 0 :binding 1} :shadowMap "texture_depth_2d")
   (w/binding* {:group 0 :binding 2} :shadowSamp "sampler_comparison")
   (apply w/func :shadow {:params [[:wpos [:vec3 :f32]] [:ndl :f32]] :ret :f32} shadow-fn-body)
   (w/struct* :VO VO-fields)
   (apply w/func :vs {:stage :vertex
                      :params [[:pos [:vec3 :f32] {:location 0}] [:normal [:vec3 :f32] {:location 1}]
                               [:m0 [:vec4 :f32] {:location 2}] [:m1 [:vec4 :f32] {:location 3}]
                               [:m2 [:vec4 :f32] {:location 4}] [:m3 [:vec4 :f32] {:location 5}]
                               [:color [:vec4 :f32] {:location 6}] [:material [:vec4 :f32] {:location 7}]]
                      :ret :VO} vs-body)
   (lit-fs)))

;; ── four-cascade directional shadows -----------------------------------------

(def cascaded-G-fields
  [[:vp :mat4] [:sun-dir :vec4] [:sun-col :vec4] [:sky :vec4]
   [:light-vp0 :mat4] [:light-vp1 :mat4] [:light-vp2 :mat4] [:light-vp3 :mat4]
   [:shadow-splits :vec4]
   [:light-a :vec4] [:light-b :vec4] [:light-c :vec4] [:light-d :vec4]])

(def cascaded-shadow-fn-body
  [[:let :eye [:vec3 :g.sun-dir.w :g.sun-col.w :g.sky.w]]
   [:let :view-distance [:length [:- :eye :wpos]]]
   [:var :layer :i32 [:i 0]]
   [:var :light-vp :mat4 :g.light-vp0]
   [:if [:> :view-distance :g.shadow-splits.x]
    [[:set :layer [:i 1]] [:set :light-vp :g.light-vp1]]]
   [:if [:> :view-distance :g.shadow-splits.y]
    [[:set :layer [:i 2]] [:set :light-vp :g.light-vp2]]]
   [:if [:> :view-distance :g.shadow-splits.z]
    [[:set :layer [:i 3]] [:set :light-vp :g.light-vp3]]]
   [:let :lc [:* :light-vp [:vec4 :wpos 1.0]]]
   [:let :ndc [:/ :lc.xyz :lc.w]]
   [:let :uv [:vec2 [:+ [:* :ndc.x 0.5] 0.5] [:- 0.5 [:* :ndc.y 0.5]]]]
   [:if [:|| [:< :uv.x 0.0] [:> :uv.x 1.0] [:< :uv.y 0.0]
          [:> :uv.y 1.0] [:> :ndc.z 1.0]]
    [[:return 1.0]]]
   [:let :bias [:max [:* :g.light-d.y [:- 1.0 :ndl]] :g.light-d.z]]
   [:let :texel :g.light-d.w]
   [:var :lit 0.0]
   [:for [:var :dx [:i -1]] [:<= :dx [:i 1]] [:++ :dx]
    [:for [:var :dy [:i -1]] [:<= :dy [:i 1]] [:++ :dy]
     [:+= :lit [:textureSampleCompareLevel :shadowMap :shadowSamp
                [:+ :uv [:* [:vec2 [:f32 :dx] [:f32 :dy]] :texel]]
                :layer [:- :ndc.z :bias]]]]]
   [:return [:/ :lit 9.0]]])

(defn cascaded-shadow-shader
  "Depth-only shader for one layer of the shared four-layer shadow texture."
  [cascade-index]
  {:pre [(<= 0 cascade-index 3)]}
  (let [matrix-field (keyword (str "g.light-vp" cascade-index))
        body [[:let :model [:mat4 :m0 :m1 :m2 :m3]]
              [:return [:* matrix-field :model [:vec4 :pos 1.0]]]]]
    (w/shader
     (w/struct* :G cascaded-G-fields)
     (w/binding* {:group 0 :binding 0 :space :uniform} :g :G)
     (apply w/func :vs {:stage :vertex
                        :params [[:pos [:vec3 :f32] {:location 0}]
                                 [:normal [:vec3 :f32] {:location 1}]
                                 [:m0 [:vec4 :f32] {:location 2}]
                                 [:m1 [:vec4 :f32] {:location 3}]
                                 [:m2 [:vec4 :f32] {:location 4}]
                                 [:m3 [:vec4 :f32] {:location 5}]
                                 [:color [:vec4 :f32] {:location 6}]
                                 [:material [:vec4 :f32] {:location 7}]]
                        :ret [:builtin :position [:vec4 :f32]]} body))))

(defn cascaded-lit-shader
  "Lit shader selecting a cascade by camera distance and sampling a depth array."
  []
  (let [fs-body (walk/postwalk-replace {:shadow :cascaded-shadow} lit-fs-body)]
    (w/shader
     (w/struct* :G cascaded-G-fields)
     (w/binding* {:group 0 :binding 0 :space :uniform} :g :G)
     (w/binding* {:group 0 :binding 1} :shadowMap "texture_depth_2d_array")
     (w/binding* {:group 0 :binding 2} :shadowSamp "sampler_comparison")
     (apply w/func :cascaded-shadow
            {:params [[:wpos [:vec3 :f32]] [:ndl :f32]] :ret :f32}
            cascaded-shadow-fn-body)
     (w/struct* :VO VO-fields)
     (apply w/func :vs {:stage :vertex
                        :params [[:pos [:vec3 :f32] {:location 0}]
                                 [:normal [:vec3 :f32] {:location 1}]
                                 [:m0 [:vec4 :f32] {:location 2}]
                                 [:m1 [:vec4 :f32] {:location 3}]
                                 [:m2 [:vec4 :f32] {:location 4}]
                                 [:m3 [:vec4 :f32] {:location 5}]
                                 [:color [:vec4 :f32] {:location 6}]
                                 [:material [:vec4 :f32] {:location 7}]]
                        :ret :VO} vs-body)
     (apply w/func :fs {:stage :fragment :params [[:i :VO]]
                        :ret [:loc 0 [:vec4 :f32]]} fs-body))))

(defn cascaded-hdr-shader
  "Cascaded lit shader returning linear HDR. ACES/gamma are deferred to the
   final composite pass so bloom operates on values above display white."
  []
  (let [linear-body (conj (vec (drop-last 3 lit-fs-body))
                          [:return [:vec4 :c 1.0]])
        fs-body (walk/postwalk-replace {:shadow :cascaded-shadow} linear-body)]
    (w/shader
     (w/struct* :G cascaded-G-fields)
     (w/binding* {:group 0 :binding 0 :space :uniform} :g :G)
     (w/binding* {:group 0 :binding 1} :shadowMap "texture_depth_2d_array")
     (w/binding* {:group 0 :binding 2} :shadowSamp "sampler_comparison")
     (apply w/func :cascaded-shadow
            {:params [[:wpos [:vec3 :f32]] [:ndl :f32]] :ret :f32}
            cascaded-shadow-fn-body)
     (w/struct* :VO VO-fields)
     (apply w/func :vs {:stage :vertex
                        :params [[:pos [:vec3 :f32] {:location 0}]
                                 [:normal [:vec3 :f32] {:location 1}]
                                 [:m0 [:vec4 :f32] {:location 2}]
                                 [:m1 [:vec4 :f32] {:location 3}]
                                 [:m2 [:vec4 :f32] {:location 4}]
                                 [:m3 [:vec4 :f32] {:location 5}]
                                 [:color [:vec4 :f32] {:location 6}]
                                 [:material [:vec4 :f32] {:location 7}]]
                        :ret :VO} vs-body)
     (apply w/func :fs {:stage :fragment :params [[:i :VO]]
                        :ret [:loc 0 [:vec4 :f32]]} fs-body))))

;; ── textured metallic-roughness PBR -----------------------------------------

(def textured-VO-fields
  (conj (mapv (fn [field]
                (if (= :mat (first field))
                  [:mat [:vec4 :f32] {:location 3}]
                  field))
              VO-fields)
        [:uv [:vec2 :f32] {:location 4}]
        [:tangent [:vec4 :f32] {:location 5}]))

(def textured-vs-body
  [[:let :model [:mat4 :m0 :m1 :m2 :m3]]
   [:let :world [:* :model [:vec4 :pos 1.0]]]
   [:decl :o :VO]
   [:set :o.clip [:* :g.vp :world]]
   [:set :o.n [:normalize [:. [:* :model [:vec4 :normal 0.0]] :xyz]]]
   [:set :o.col :color.rgb]
   [:set :o.wpos :world.xyz]
   [:set :o.mat :material]
   [:set :o.uv :uv]
   [:set :o.tangent [:vec4 [:normalize [:. [:* :model [:vec4 :tangent.xyz 0.0]] :xyz]] :tangent.w]]
   [:return :o]])

(def textured-pbr-fs-body
  [[:let :useTex [:clamp :i.mat.w 0.0 1.0]]
   [:let :baseN [:normalize :i.n]]
   [:let :T [:normalize [:- :i.tangent.xyz [:* :baseN [:dot :baseN :i.tangent.xyz]]]]]
   [:let :B [:* [:normalize [:cross :baseN :T]] :i.tangent.w]]
   [:let :mapN [:- [:* [:textureSample :normalTex :materialSamp :i.uv] :2.0] [:vec4 1.0]]]
   [:let :mappedN [:normalize [:+ [:* :T :mapN.x] [:* :B :mapN.y] [:* :baseN :mapN.z]]]]
   [:let :N [:normalize [:mix :baseN :mappedN :useTex]]]
   [:let :albedoSample [:textureSample :albedoTex :materialSamp :i.uv]]
   [:let :baseColor [:* :i.col [:mix [:vec3 1.0] :albedoSample.rgb :useTex]]]
   [:let :mr [:textureSample :metallicRoughnessTex :materialSamp :i.uv]]
   [:let :metallic [:clamp [:* :i.mat.x [:mix 1.0 :mr.b :useTex]] 0.0 1.0]]
   [:let :rough [:clamp [:* :i.mat.y [:mix 1.0 :mr.g :useTex]] 0.04 1.0]]
   [:let :emissive :i.mat.z]
   [:let :L [:normalize [:- :g.sun-dir.xyz]]]
   [:let :eye [:vec3 :g.sun-dir.w :g.sun-col.w :g.sky.w]]
   [:let :V [:normalize [:- :eye :i.wpos]]]
   [:let :H [:normalize [:+ :L :V]]]
   [:let :ndl [:max [:dot :N :L] 0.0]]
   [:let :amb [:mix :g.light-a.rgb [:* :g.sky.rgb :g.light-a.w] [:+ [:* :N.y 0.5] 0.5]]]
   [:let :shininess [:mix :g.light-c.x :g.light-c.y [:- 1.0 :rough]]]
   [:let :specStr [:mix :g.light-b.x :g.light-b.y :metallic]]
   [:let :specTint [:mix [:vec3 1.0] :baseColor :metallic]]
   [:let :spec [:* [:pow [:max [:dot :N :H] 0.0] :shininess] :specStr]]
   [:let :rim [:* [:pow [:- 1.0 [:max [:dot :N :V] 0.0]] :g.light-b.w] :g.light-b.z]]
   [:let :sh [:cascaded-shadow :i.wpos :ndl]]
   [:var :c [:+ [:* :baseColor [:+ :amb [:* :ndl :g.sun-col.rgb :g.light-c.z [:- 1.0 [:* :metallic :g.light-c.w]] :sh]]]
                [:* :specTint :g.sun-col.rgb :spec :sh]
                [:* :g.sky.rgb :rim]
                [:* :baseColor :emissive]]]
   [:return [:vec4 :c 1.0]]])

(defn- cascaded-textured-shader* [fs-body]
  (w/shader
   (w/struct* :G cascaded-G-fields)
   (w/binding* {:group 0 :binding 0 :space :uniform} :g :G)
   (w/binding* {:group 0 :binding 1} :shadowMap "texture_depth_2d_array")
   (w/binding* {:group 0 :binding 2} :shadowSamp "sampler_comparison")
   (w/binding* {:group 0 :binding 3} :albedoTex "texture_2d<f32>")
   (w/binding* {:group 0 :binding 4} :normalTex "texture_2d<f32>")
   (w/binding* {:group 0 :binding 5} :metallicRoughnessTex "texture_2d<f32>")
   (w/binding* {:group 0 :binding 6} :materialSamp "sampler")
   (apply w/func :cascaded-shadow
          {:params [[:wpos [:vec3 :f32]] [:ndl :f32]] :ret :f32}
          cascaded-shadow-fn-body)
   (w/struct* :VO textured-VO-fields)
   (apply w/func :vs {:stage :vertex
                      :params [[:pos [:vec3 :f32] {:location 0}]
                               [:normal [:vec3 :f32] {:location 1}]
                               [:m0 [:vec4 :f32] {:location 2}]
                               [:m1 [:vec4 :f32] {:location 3}]
                               [:m2 [:vec4 :f32] {:location 4}]
                               [:m3 [:vec4 :f32] {:location 5}]
                               [:color [:vec4 :f32] {:location 6}]
                               [:material [:vec4 :f32] {:location 7}]
                               [:uv [:vec2 :f32] {:location 8}]
                               [:tangent [:vec4 :f32] {:location 9}]]
                      :ret :VO} textured-vs-body)
   (apply w/func :fs {:stage :fragment :params [[:i :VO]]
                      :ret [:loc 0 [:vec4 :f32]]} fs-body)))

(defn cascaded-textured-hdr-shader
  "Linear-HDR PBR shader with sRGB base color, tangent-space normal, and
   glTF metallic-roughness bindings. material.w enables texture sampling per
   instance, so untextured and textured objects share one instanced pipeline."
  []
  (cascaded-textured-shader* textured-pbr-fs-body))

(defn cascaded-textured-lit-shader
  "Display-ready sibling of cascaded-textured-hdr-shader. Applies ACES+gamma
   in-fragment for the adaptive direct-to-swapchain saturation tier."
  []
  (let [display-body
        (into (vec (drop-last textured-pbr-fs-body))
              [[:set :c [:clamp
                         [:/ [:* :c [:+ [:* 2.51 :c] [:vec3 0.03]]]
                          [:+ [:* :c [:+ [:* 2.43 :c] [:vec3 0.59]]] [:vec3 0.14]]]
                         [:vec3 0.0] [:vec3 1.0]]]
               [:set :c [:pow :c [:vec3 [:/ 1.0 :g.light-d.x]]]]
               [:return [:vec4 :c 1.0]]])]
    (cascaded-textured-shader* display-body)))

(def fullscreen-vertex-wgsl
  "struct FullscreenOut { @builtin(position) position: vec4<f32>, @location(0) uv: vec2<f32> };
@vertex fn vs(@builtin(vertex_index) i: u32) -> FullscreenOut {
  var p = array<vec2<f32>, 3>(vec2<f32>(-1.0,-1.0), vec2<f32>(3.0,-1.0), vec2<f32>(-1.0,3.0));
  var out: FullscreenOut;
  out.position = vec4<f32>(p[i], 0.0, 1.0);
  out.uv = p[i] * vec2<f32>(0.5,-0.5) + vec2<f32>(0.5,0.5);
  return out;
}")

(defn bloom-shader
  "Half-resolution bright-pass with a single 3x3 Gaussian filter."
  []
  (w/shader
   fullscreen-vertex-wgsl
   "@group(0) @binding(0) var hdrTex: texture_2d<f32>;
@group(0) @binding(1) var linearSampler: sampler;
@fragment fn fs(in: FullscreenOut) -> @location(0) vec4<f32> {
  let dims = vec2<f32>(textureDimensions(hdrTex));
  let texel = 1.0 / dims;
  var sum = vec3<f32>(0.0);
  for (var y = -1; y <= 1; y++) {
    for (var x = -1; x <= 1; x++) {
      let weight = select(select(1.0, 2.0, x == 0), select(2.0, 4.0, x == 0), y == 0);
      let c = textureSample(hdrTex, linearSampler, in.uv + vec2<f32>(f32(x),f32(y))*texel).rgb;
      let brightness = max(max(c.r,c.g),c.b);
      sum += c * smoothstep(0.8, 1.3, brightness) * weight;
    }
  }
  return vec4<f32>(sum / 16.0, 1.0);
}") )

(defn hdr-composite-shader
  "Combine linear scene+bloom, apply ACES filmic mapping, then output gamma."
  []
  (w/shader
   fullscreen-vertex-wgsl
   "@group(0) @binding(0) var hdrTex: texture_2d<f32>;
@group(0) @binding(1) var bloomTex: texture_2d<f32>;
@group(0) @binding(2) var linearSampler: sampler;
@fragment fn fs(in: FullscreenOut) -> @location(0) vec4<f32> {
  var c = textureSample(hdrTex, linearSampler, in.uv).rgb;
  c += textureSample(bloomTex, linearSampler, in.uv).rgb * 0.12;
  c = clamp((c*(2.51*c+vec3<f32>(0.03))) / (c*(2.43*c+vec3<f32>(0.59))+vec3<f32>(0.14)), vec3<f32>(0.0), vec3<f32>(1.0));
  c = pow(c, vec3<f32>(1.0/2.2));
  return vec4<f32>(c,1.0);
}") )
