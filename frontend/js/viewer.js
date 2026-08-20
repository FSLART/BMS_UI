/**
 * Thin wrapper around <model-viewer>.
 *
 * Two GLBs are stacked and crossfaded (option A: SolidWorks exports the closed
 * assembly and its open-lid configuration as separate files — no animation
 * authoring, no Blender). If a GLB is missing or model-viewer failed to load,
 * a styled placeholder takes over and the rest of the app is unaffected.
 */

import { modelCache, resolveModel } from './preload.js';

// View names are open-ended: a car declares whichever it has. `charger` is the
// pack sitting on the handcart, and exists before its GLB does -- the viewer
// falls back to a placeholder, which is the point.
const DEFAULT_MODELS = {
  closed: '/models/battery_closed.glb',
  open: '/models/battery_open.glb',
  charger: '/models/battery_charger.glb',
  segment: '/models/battery_segment.glb',
};

/**
 * Fallback framing, used only when the car profile does not define a view.
 * The real values live per car in backend/cars.py (`view_closed` / `view_open`)
 * because they depend on that car's geometry, not on the viewer.
 *
 * Empty orbit means "let model-viewer frame the model itself".
 */
const CAMERA_FALLBACK = {
  closed: { orbit: '', target: 'auto auto auto', fov: '', orientation: '' },
  open: { orbit: '', target: 'auto auto auto', fov: '', orientation: '' },
  charger: { orbit: '', target: 'auto auto auto', fov: '', orientation: '' },
  segment: { orbit: '', target: 'auto auto auto', fov: '', orientation: '' },
};

/**
 * CAD exports arrive glossy: SolidWorks writes low roughness and often non-zero
 * metallic, so the case mirrors the whole environment and washes out.
 *
 * `neutral` tone mapping, not `agx`: agx rolls highlights off by desaturating,
 * which drains the colour instead of taming the shine. The shine itself is
 * fixed on the materials (see MATERIAL below), not with exposure.
 */
const RENDER = {
  exposure: '0.72',
  toneMapping: 'neutral',
  shadowIntensity: '0.75',
  shadowSoftness: '1',
};

/** Applied to every material after load. Set `matte: false` to keep CAD looks. */
const MATERIAL = {
  matte: true,
  minRoughness: 0.7,    // floor, never polishes an already-rough material
  maxMetallic: 0.0,     // CAD "metallic" is almost always an appearance artefact
};

/**
 * Colour rules, matched against the material's ORIGINAL base colour.
 *
 * Keyed by colour rather than by material index because indices are export
 * order and shuffle on every re-export, while a SolidWorks appearance keeps its
 * RGB. The same rule then fixes the same part in every GLB: the kevlar cream
 * `243,236,184` is corrected in tek26e_closed and tek26e_open alike.
 *
 * Several of these are literally SolidWorks default appearances - a part that
 * was never assigned a material.
 */
const COLOR_RULES = [
  // --- estrutura -----------------------------------------------------------
  { rgb: '243,236,184', as: 'kevlar',      color: [0.78, 0.73, 0.55, 1], roughness: 0.72, metallic: 0.0 },
  { rgb: '202,209,238', as: 'aluminio',    color: [0.66, 0.67, 0.69, 1], roughness: 0.38, metallic: 1.0 },
  { rgb: '221,232,255', as: 'aluminio',    color: [0.66, 0.67, 0.69, 1], roughness: 0.38, metallic: 1.0 },
  { rgb: '228,228,228', as: 'aluminio',    color: [0.68, 0.69, 0.71, 1], roughness: 0.40, metallic: 1.0 },
  { rgb: '255,242,232', as: 'inox',        color: [0.74, 0.75, 0.76, 1], roughness: 0.30, metallic: 1.0 },
  { rgb: '255,244,226', as: 'niquel',      color: [0.74, 0.75, 0.76, 1], roughness: 0.30, metallic: 1.0 },
  { rgb: '255,255,255', as: 'PETG branco', color: [0.90, 0.90, 0.89, 1], roughness: 0.40, metallic: 0.0 },

  // --- celulas e terminais -------------------------------------------------
  // 187,197,203 cobre 2402 nos (Fillet/solidBody/Terminal): invólucro niquelado
  { rgb: '187,197,203', as: 'celula',      color: [0.70, 0.72, 0.74, 1], roughness: 0.30, metallic: 1.0 },
  { rgb: '214,119,94',  as: 'cobre',       color: [0.62, 0.36, 0.20, 1], roughness: 0.40, metallic: 1.0 },

  // --- PCBs ----------------------------------------------------------------
  // O verde puro (0,128,0) e default do CAD e berra; FR4 real e escuro e fosco.
  { rgb: '0,128,0',     as: 'PCB',         color: [0.05, 0.20, 0.09, 1], roughness: 0.55, metallic: 0.0 },
  { rgb: '35,120,50',   as: 'PCB',         color: [0.05, 0.20, 0.09, 1], roughness: 0.55, metallic: 0.0 },
  { rgb: '73,169,84',   as: 'PLA verde',   color: [0.16, 0.45, 0.22, 1], roughness: 0.60, metallic: 0.0 },

  // --- contactos dourados --------------------------------------------------
  { rgb: '222,161,44',  as: 'pino dourado', color: [0.75, 0.60, 0.25, 1], roughness: 0.32, metallic: 1.0 },
  { rgb: '165,132,0',   as: 'latao',        color: [0.62, 0.50, 0.18, 1], roughness: 0.35, metallic: 1.0 },
  { rgb: '109,84,5',    as: 'latao escuro', color: [0.42, 0.33, 0.10, 1], roughness: 0.45, metallic: 1.0 },

  // --- metais genericos ----------------------------------------------------
  { rgb: '178,178,178', as: 'metal',       color: [0.62, 0.63, 0.64, 1], roughness: 0.35, metallic: 1.0 },
  { rgb: '192,192,192', as: 'metal',       color: [0.64, 0.65, 0.66, 1], roughness: 0.35, metallic: 1.0 },
  { rgb: '196,196,196', as: 'metal',       color: [0.64, 0.65, 0.66, 1], roughness: 0.35, metallic: 1.0 },
  { rgb: '184,184,184', as: 'metal',       color: [0.62, 0.63, 0.64, 1], roughness: 0.35, metallic: 1.0 },
  { rgb: '191,191,191', as: 'metal',       color: [0.62, 0.63, 0.64, 1], roughness: 0.35, metallic: 1.0 },
  { rgb: '204,204,204', as: 'metal',       color: [0.66, 0.67, 0.68, 1], roughness: 0.35, metallic: 1.0 },
  { rgb: '215,215,215', as: 'metal',       color: [0.68, 0.69, 0.70, 1], roughness: 0.35, metallic: 1.0 },
  { rgb: '163,163,175', as: 'metal',       color: [0.58, 0.59, 0.61, 1], roughness: 0.35, metallic: 1.0 },

  // --- plasticos escuros ---------------------------------------------------
  // Albedo 0.25-0.30 le como cinzento medio sob o IBL; preto tem de ser ~0.05.
  { rgb: '128,128,128', as: 'preto',       color: [0.05, 0.05, 0.05, 1], roughness: 0.80, metallic: 0.0 },
  { rgb: '127,127,127', as: 'preto',       color: [0.06, 0.06, 0.06, 1], roughness: 0.80, metallic: 0.0 },
  { rgb: '64,64,64',    as: 'preto',       color: [0.05, 0.05, 0.05, 1], roughness: 0.85, metallic: 0.0 },
  { rgb: '76,76,76',    as: 'preto',       color: [0.06, 0.06, 0.06, 1], roughness: 0.80, metallic: 0.0 },
  { rgb: '75,75,75',    as: 'preto',       color: [0.06, 0.06, 0.06, 1], roughness: 0.80, metallic: 0.0 },
  { rgb: '73,73,73',    as: 'preto',       color: [0.06, 0.06, 0.06, 1], roughness: 0.80, metallic: 0.0 },
  { rgb: '51,51,51',    as: 'preto',       color: [0.05, 0.05, 0.05, 1], roughness: 0.80, metallic: 0.0 },
  { rgb: '49,49,49',    as: 'preto',       color: [0.05, 0.05, 0.05, 1], roughness: 0.80, metallic: 0.0 },
  { rgb: '63,63,63',    as: 'preto',       color: [0.05, 0.05, 0.05, 1], roughness: 0.80, metallic: 0.0 },

  // --- deixados como estao (cor real, so perdem o brilho de CAD) -----------
  // 255,128,0 e 255,123,0 conectores HV | 255,0,0 e 229,0,0 vermelho
  // 0,0,255 azul | 249,180,44 e 255,207,128 indicador de tensao
];

const RULES_BY_RGB = new Map(COLOR_RULES.map((r) => [r.rgb, r]));

/**
 * Per-material overrides, by glTF material index.
 * Each entry: { color: [r,g,b,a] 0..1, roughness, metallic } -- all optional.
 *
 * Roughness cannot make a part darker; it only changes how sharp the specular
 * is. A part that reads as the wrong colour got the wrong appearance in
 * SolidWorks, and this is the patch until the CAD is fixed. (243,236,184) and
 * (202,209,238) are literally the SolidWorks default part colours -- every part
 * carrying them was never given an appearance.
 *
 * Indices come from the export; re-run the analysis after re-exporting.
 * tek26e_closed.glb:
 *   0 (202,209,238) 138 parafusos M6       5 (128,128,128) RTS718N32S03 (conector)
 *   1 (243,236,184) caixa Floor/Lid/Walls  6,7 (255,128,0) HVSLS600022B1H6
 *   2 (255,255,255) Cover_Corner           8 (64,64,64)    ventoinhas SanAce
 *   3 (221,232,255) TSAC_Mountings         4 (228,228,228) TSAC_Mountings mirror
 */
const OVERRIDES = {
  tek26e_closed: {
    // parafusos M6: aco zincado, brilhante
    0: { color: [0.72, 0.73, 0.75, 1], roughness: 0.28, metallic: 1.0 },
    // caixa e base (Floor / Lid / Outer_*_Wall): laminado de aramida em epoxi.
    // Bege-creme fosco, com o tom quente do fio -- nem o dourado saturado do
    // fio solto, nem o off-white lavado.
    1: { color: [0.78, 0.73, 0.55, 1], roughness: 0.72, metallic: 0.0 },
    // Cover_Corner: PETG branco, brilho de plastico
    2: { color: [0.90, 0.90, 0.89, 1], roughness: 0.40, metallic: 0.0 },
    // Cover_Tab e TSAC_Mountings: aluminio acetinado
    3: { color: [0.66, 0.67, 0.69, 1], roughness: 0.38, metallic: 1.0 },
    4: { color: [0.66, 0.67, 0.69, 1], roughness: 0.38, metallic: 1.0 },
    // conector RTS718N32S03: preto
    5: { color: [0.05, 0.05, 0.05, 1] },
    // 6, 7 HV connector: laranja e a cor real, deixar
    // ventoinhas SanAce: o (64,64,64) do export tem albedo 0.25 e sob o IBL le
    // como cinzento medio, nao preto. Mesmo preto do conector.
    8: { color: [0.05, 0.05, 0.05, 1], roughness: 0.85 },
  },
};

/**
 * Backstop only, for a GLB so large the tab would die outright. Raw CAD exports
 * in the hundreds of MB are allowed through on purpose -- they load slowly and
 * render heavy, which is a tradeoff to make deliberately, not a failure.
 */
const MAX_MODEL_MB = 1024;

const probed = {};

async function probe(url) {
  // Already in memory from the splash: no need to ask the server again.
  const cached = modelCache.get(url);
  if (cached) return { ok: true, mb: cached.bytes / 1048576, tooBig: false };

  if (url in probed) return probed[url];
  let result = { ok: false, mb: 0, tooBig: false };
  try {
    const res = await fetch(url, { method: 'HEAD' });
    if (res.ok) {
      const mb = Number(res.headers.get('content-length') || 0) / 1048576;
      result = { ok: true, mb, tooBig: mb > MAX_MODEL_MB };
    }
  } catch {
    /* keep the not-found result */
  }
  probed[url] = result;
  return result;
}

function placeholder(which, path, info) {
  const label = { closed: 'bateria fechada', open: 'bateria aberta',
                  charger: 'bateria no carregador',
                  segment: 'segmento' }[which] || which;
  const el = document.createElement('div');
  el.className = 'viewer-placeholder';
  if (info && info.tooBig) {
    el.innerHTML = `
      <div class="ph-box ph-warn">
        <div>
          <div class="ph-title">Modelo demasiado pesado — não carregado</div>
          <div class="ph-path">${path}<br>${info.mb.toFixed(0)} MB · limite ${MAX_MODEL_MB} MB</div>
          <div class="ph-note">Suprime os componentes internos no CAD e baixa a
            tessellation antes de exportar. Ver models/README.md.</div>
        </div>
      </div>`;
  } else {
    el.innerHTML = `
      <div class="ph-box">
        <div>
          <div class="ph-title">Modelo 3D — ${label}</div>
          <div class="ph-path">${path}</div>
        </div>
      </div>`;
  }
  return el;
}

/**
 * Resolve once the model is usable.
 *
 * Deliberately not gated on the `load` event alone: a warm cache can fire it
 * before we subscribe, and when the window is not compositing (background tab,
 * hidden pane) model-viewer's render loop stalls and the event may never be
 * dispatched even though `.loaded` has flipped. Poll as well, and give up after
 * a while rather than leave a blank viewer forever.
 */
function waitLoaded(mv, timeoutMs = 45000) {
  return new Promise((resolve) => {
    if (mv.loaded) return resolve(true);
    let done = false;
    const finish = (ok) => {
      if (done) return;
      done = true;
      clearInterval(poll);
      clearTimeout(bail);
      resolve(ok);
    };
    const poll = setInterval(() => { if (mv.loaded) finish(true); }, 100);
    const bail = setTimeout(() => finish(mv.loaded), timeoutMs);
    mv.addEventListener('load', () => finish(true), { once: true });
    mv.addEventListener('error', () => finish(false), { once: true });
  });
}

/**
 * Knock the gloss off every material via the Scene Graph API. Base colour is
 * left alone -- only how the surface reflects changes, so the livery and the
 * part colours survive.
 */
function matteMaterials(mv, src) {
  if (!mv.model) return 0;
  const key = (src.split('/').pop() || '').replace(/\.glb$/i, '');
  const overrides = OVERRIDES[key] || {};
  const stats = { rules: 0, overrides: 0, matte: 0 };

  mv.model.materials.forEach((mat, i) => {
    const pbr = mat.pbrMetallicRoughness;
    if (!pbr) return;
    try {
      // Read the original colour BEFORE touching anything - it is what the
      // rules match on.
      const c = pbr.baseColorFactor;
      const rgb = `${Math.round(c[0] * 255)},${Math.round(c[1] * 255)},${Math.round(c[2] * 255)}`;

      // 1. Global matte pass: anything unmatched at least loses the CAD gloss.
      if (MATERIAL.matte) {
        if (pbr.roughnessFactor < MATERIAL.minRoughness) pbr.setRoughnessFactor(MATERIAL.minRoughness);
        if (pbr.metallicFactor > MATERIAL.maxMetallic) pbr.setMetallicFactor(MATERIAL.maxMetallic);
        stats.matte++;
      }

      // 2. Colour rule, if this appearance is one we recognise.
      const rule = RULES_BY_RGB.get(rgb);
      if (rule) {
        if (rule.color) pbr.setBaseColorFactor(rule.color);
        if (rule.roughness !== undefined) pbr.setRoughnessFactor(rule.roughness);
        if (rule.metallic !== undefined) pbr.setMetallicFactor(rule.metallic);
        stats.rules++;
      }

      // 3. Per-file, per-index override always wins - use for the exceptions
      //    a colour rule cannot express.
      const o = overrides[i];
      if (o) {
        if (o.color) pbr.setBaseColorFactor(o.color);
        if (o.roughness !== undefined) pbr.setRoughnessFactor(o.roughness);
        if (o.metallic !== undefined) pbr.setMetallicFactor(o.metallic);
        stats.overrides++;
      }
    } catch {
      /* a material without a mutable PBR block is not worth failing over */
    }
  });

  console.info(`[viewer] ${key}: ${stats.matte} materiais, ${stats.rules} por regra de cor, `
             + `${stats.overrides} por override`);
  return stats;
}

export class Viewer {
  /**
   * @param {HTMLElement} root  container with class .viewer
   * @param {'closed'|'open'|'charger'} initial
   */
  constructor(root, initial = 'closed') {
    this.root = root;
    this.current = initial;
    this.models = { ...DEFAULT_MODELS };
    this.views = { ...CAMERA_FALLBACK };
    this.mv = {};
    this.placeholder = placeholder(initial, this.models[initial]);
    this.root.appendChild(this.placeholder);
    this.hotspotRenderer = null;
    this._build(initial);
  }

  /**
   * Starting framing per view, straight from the car profile. Anything the
   * profile leaves blank falls through to model-viewer's own framing.
   */
  setViews(views = {}) {
    const pick = (v, fb) => ({
      orbit: v?.orbit || fb.orbit,
      target: v?.target || fb.target,
      fov: v?.fov || fb.fov,
      orientation: v?.orientation || fb.orientation,
    });
    this.views = {};
    for (const key of Object.keys(CAMERA_FALLBACK)) {
      this.views[key] = pick(views[key], CAMERA_FALLBACK[key]);
    }
    // Already-built viewers keep their own camera; re-apply so a car swap does
    // not leave the previous car's framing in place.
    for (const which of Object.keys(this.mv)) this._applyView(which);
  }

  _applyView(which) {
    const mv = this.mv[which];
    const cam = this.views[which];
    if (!mv || !cam) return false;
    mv.resetTurntableRotation?.(0);
    // Orientation first: it changes the model's bounds, and "auto" framing and
    // percentage radii are computed from those.
    if (cam.orientation) mv.setAttribute('orientation', cam.orientation);
    else mv.removeAttribute('orientation');
    if (cam.orbit) mv.cameraOrbit = cam.orbit;
    if (cam.target) mv.cameraTarget = cam.target;
    if (cam.fov) mv.fieldOfView = cam.fov;
    // No orbit configured: hand framing back to model-viewer.
    if (!cam.orbit) mv.removeAttribute('camera-orbit');
    if (!cam.fov) mv.removeAttribute('field-of-view');
    return true;
  }

  /** Point the viewer at another car's GLBs, dropping whatever was loaded. */
  setModels(models = {}) {
    const next = {};
    for (const key of Object.keys(DEFAULT_MODELS)) {
      next[key] = models[key] || DEFAULT_MODELS[key];
    }
    if (Object.keys(next).every((k) => next[k] === this.models[k])) return;
    this.models = next;
    Object.values(this.mv).forEach((el) => el.remove());
    this.mv = {};
    this._setPlaceholder(this.current);
    this._build(this.current);
  }

  /** Swap the placeholder, optionally explaining why the GLB was rejected. */
  _setPlaceholder(which, info) {
    const next = placeholder(which, this.models[which], info);
    this.placeholder.replaceWith(next);
    this.placeholder = next;
  }

  async _build(which) {
    if (this.mv[which]) return this.mv[which];
    const src = this.models[which];
    if (!customElements.get('model-viewer')) {
      await customElements.whenDefined('model-viewer').catch(() => {});
    }
    if (!customElements.get('model-viewer')) return null;   // offline: keep placeholder

    const info = await probe(src);
    if (!info.ok || info.tooBig) {
      if (which === this.current) this._setPlaceholder(which, info);
      if (info.tooBig) {
        console.warn(`[viewer] ${src} is ${info.mb.toFixed(0)} MB (limit ${MAX_MODEL_MB} MB) - not loading.`);
      }
      return null;
    }

    const mv = document.createElement('model-viewer');
    // Preloaded object URL when we have one; the served path otherwise. The
    // logical `src` still keys the material overrides and the placeholder text.
    mv.setAttribute('src', resolveModel(src));
    // Default is lazy: the fetch waits for the element to be near the viewport.
    // Here the model IS the screen, and lazily never firing looks like a hang.
    mv.setAttribute('loading', 'eager');
    mv.setAttribute('reveal', 'auto');
    mv.setAttribute('camera-controls', '');
    mv.setAttribute('interaction-prompt', 'none');
    mv.setAttribute('shadow-intensity', RENDER.shadowIntensity);
    mv.setAttribute('shadow-softness', RENDER.shadowSoftness);
    mv.setAttribute('exposure', RENDER.exposure);
    mv.setAttribute('tone-mapping', RENDER.toneMapping);
    mv.setAttribute('environment-image', 'neutral');
    const cam = this.views[which] || CAMERA_FALLBACK[which];
    if (cam.orientation) mv.setAttribute('orientation', cam.orientation);
    if (cam.orbit) mv.setAttribute('camera-orbit', cam.orbit);
    if (cam.target) mv.setAttribute('camera-target', cam.target);
    if (cam.fov) mv.setAttribute('field-of-view', cam.fov);
    // The default minimum orbit stops well short of the model; loosen it so the
    // starting radius is honoured and manual zoom can go closer still.
    mv.setAttribute('min-camera-orbit', 'auto auto 15%');
    mv.setAttribute('min-field-of-view', '18deg');
    mv.setAttribute('max-field-of-view', '48deg');
    mv.setAttribute('touch-action', 'none');
    mv.dataset.which = which;

    this.root.appendChild(mv);
    this.mv[which] = mv;

    const ok = await waitLoaded(mv);

    if (!ok) {
      console.warn(`[viewer] falhou a carregar ${src}`);
      mv.remove();
      delete this.mv[which];
      return null;
    }
    matteMaterials(mv, src);
    if (which === this.current) {
      this._reveal(which);
      this._renderHotspots();
    }
    return mv;
  }

  /**
   * Show one model, hide the others.
   *
   * Two cases that deserve different timing. Coming up on an empty stage there
   * is nothing to cross with, and the long fade just reads as the app being
   * slow to start -- the model is parsed and on the GPU well before it is
   * visible. Going from one model to the other, the fade is the whole point:
   * it is what stops the lid opening as a hard cut.
   */
  _reveal(which) {
    this.placeholder.classList.add('hidden');
    const first = !Object.values(this.mv).some((el) => el.classList.contains('shown'));
    Object.entries(this.mv).forEach(([k, el]) => {
      el.classList.toggle('quick', first && k === which);
      el.classList.toggle('shown', k === which);
    });
  }

  /** Crossfade to the other model. Falls back to swapping the placeholder text. */
  async show(which) {
    this.current = which;
    const mv = this.mv[which] || (await this._build(which));
    if (!mv) {
      this._setPlaceholder(which, probed[this.models[which]]);
      Object.values(this.mv).forEach((el) => el.classList.remove('shown'));
      return;
    }
    this._reveal(which);
    // Deliberately no auto-rotate: with a "reset view" button the model has to
    // stay where it is put, and a turntable drifting away from the default
    // makes that button look broken.
    this.idle(false);
  }

  /**
   * Back to the framing in CAMERA for the current view.
   *
   * Setting cameraOrbit alone is not enough. Auto-rotate accumulates a separate
   * turntable rotation on top of the orbit, so without resetTurntableRotation()
   * the model snaps back to the spun-to angle the moment the orbit is applied
   * -- which looks exactly like the reset doing nothing.
   */
  resetCamera() {
    return this._applyView(this.current);
  }

  /**
   * Anchor picker: click the model, get the exact hotspot coordinates.
   *
   * model-viewer raycasts the loaded geometry, so the numbers are the same ones
   * a Hotspot entry wants. Beats guessing from node bounds when several parts
   * share one CAD node -- AIR+, AIR- and precharge all live inside
   * Pre_Charge_AIR.step and cannot be told apart by name.
   */
  enablePicker(on, onPick) {
    const mv = this.mv[this.current];
    if (!mv) return false;

    if (this._picker) {
      mv.removeEventListener('click', this._picker);
      this._picker = null;
    }
    mv.style.cursor = on ? 'crosshair' : '';
    if (!on) return true;

    this._picker = (ev) => {
      const hit = mv.positionAndNormalFromPoint(ev.clientX, ev.clientY);
      if (!hit) return onPick?.(null);
      const f = (v) => `${v.x.toFixed(3)}m ${v.y.toFixed(3)}m ${v.z.toFixed(3)}m`;
      onPick?.({
        view: this.current,
        position: f(hit.position),
        normal: f(hit.normal),
      });
    };
    mv.addEventListener('click', this._picker);
    return true;
  }

  /**
   * The current framing, as the `CameraView(...)` line that produces it.
   *
   * Radius comes back in metres and is emitted as metres: a percentage is
   * relative to whatever model-viewer decided the framing distance was, and
   * that shifts when the model or its orientation changes. Metres pin it.
   */
  captureView() {
    const mv = this.mv[this.current];
    if (!mv || !mv.loaded) return null;
    const o = mv.getCameraOrbit();
    const deg = (rad) => `${Math.round((rad * 180) / Math.PI)}deg`;
    const view = this.views[this.current] || {};
    const parts = [
      `orbit="${deg(o.theta)} ${deg(o.phi)} ${o.radius.toFixed(3)}m"`,
      `fov="${mv.getFieldOfView().toFixed(0)}deg"`,
    ];
    // Only carried through when the profile set one; it is not read back from
    // the element, so a hand-edited attribute would not survive a capture.
    if (view.orientation) parts.push(`orientation="${view.orientation}"`);
    return {
      view: this.current,
      snippet: `view_${this.current}=CameraView(${parts.join(', ')}),`,
    };
  }

  idle(on) {
    const mv = this.mv[this.current];
    if (!mv) return;
    on ? mv.setAttribute('auto-rotate', '') : mv.removeAttribute('auto-rotate');
    mv.setAttribute('auto-rotate-delay', '4000');
    mv.setAttribute('rotation-per-second', '8deg');
  }

  /**
   * Anchors declared by the car profile (backend/cars.py -> hotspots), filtered
   * to the view they belong to. Anchors are computed from GLB node bounds, so
   * they follow the geometry rather than being clicked in an editor.
   */
  setHotspots(list) {
    this.hotspots = (list || []).filter((h) => h.view === this.current);
    // Anchors that moved out of the list must not leave a parked label behind.
    for (const [id, l] of this._leaders || []) {
      if (this.hotspots.some((h) => h.id === id && h.standoff)) continue;
      l.el.remove();
      l.line.remove();
      this._leaders.delete(id);
    }
    this._renderHotspots();
  }

  /**
   * Update one hotspot. Merges the whole object: destructuring a fixed set of
   * keys here silently dropped `hot` / `selected` / `onClick`.
   *
   * Rendering is deferred so a dashboard frame that touches twelve markers
   * paints once instead of twelve times.
   */
  setHotspotState(id, patch = {}) {
    const state = (this._hsState ||= {});
    state[id] = { ...(state[id] || {}), ...patch };
    if (!this._hsPending) {
      this._hsPending = true;
      queueMicrotask(() => { this._hsPending = false; this._renderHotspots(); });
    }
  }

  _renderHotspots() {
    const mv = this.mv[this.current];
    if (!mv || !this.hotspots) return;
    const state = this._hsState || {};

    for (const h of this.hotspots) {
      const slot = `hotspot-${h.id}`;
      let btn = mv.querySelector(`[slot="${slot}"]`);
      if (!btn) {
        btn = document.createElement('button');
        btn.className = 'hotspot';
        btn.setAttribute('slot', slot);
        btn.setAttribute('data-position', h.position);
        btn.setAttribute('data-normal', h.normal);
        btn.setAttribute('data-visibility-attribute', 'visible');
        btn.innerHTML = '<span class="hotspot-label"></span>';
        mv.appendChild(btn);
      }
      const st = state[h.id] || {};
      // `off` hides a marker without forgetting its value: a segment carries 36
      // of them and showing every one at once buries the model.
      btn.hidden = !!st.off;
      btn.dataset.sev = st.sev || 'idle';
      btn.classList.toggle('is-hot', !!st.hot);
      btn.classList.toggle('is-sel', !!st.selected);
      btn.classList.toggle('is-standoff', !!h.standoff);
      const text = st.label ?? h.label;
      btn.querySelector('.hotspot-label').textContent = text;
      if (h.standoff) this._paintStandoff(h, btn, text);

      // One listener per button, re-pointed rather than re-added, so repeated
      // state updates at 10 Hz do not stack handlers.
      btn._onClick = st.onClick;
      if (!btn._wired) {
        btn._wired = true;
        btn.addEventListener('click', (ev) => { ev.stopPropagation(); btn._onClick?.(); });
      }
      btn.style.cursor = st.onClick ? 'pointer' : 'default';
    }
  }

  /**
   * Label parked in a corner of the stage, joined to its anchor by a leader
   * line drawn in screen space.
   *
   * Not `data-surface`: that binds a hotspot to a triangle on a *skinned* mesh
   * so it tracks an animation, and these GLBs are static — `data-position` is
   * already correct. What the same docs page shows and what actually helps
   * here is the second half: read where the anchor landed on screen each frame
   * and draw the label somewhere else. The connection panel sits over the
   * middle of the closed model, so a label riding beside the RTS connector
   * spends most camera angles underneath it.
   */
  _paintStandoff(h, btn, text) {
    const leaders = (this._leaders ||= new Map());
    let l = leaders.get(h.id);
    if (!l) {
      const svg = this._leaderSvg();
      const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
      line.setAttribute('class', 'hs-line');
      svg.appendChild(line);

      const el = document.createElement('div');
      el.className = 'hs-standoff';
      el.dataset.corner = h.standoff;
      el.innerHTML = '<span class="hotspot-label"></span>';
      this.root.appendChild(el);

      l = { el, line, label: el.firstElementChild, text: null, btn };
      leaders.set(h.id, l);
    }
    l.btn = btn;
    const sev = btn.dataset.sev;
    l.el.dataset.sev = sev;
    l.line.dataset.sev = sev;
    if (l.text !== text) {
      l.text = text;
      l.label.textContent = text;
      // Width changed, so the cached rect the line attaches to is stale.
      this._leaderRects = null;
    }
    this._startLeaders();
  }

  /** Lazily created overlay the leader lines live in. Sits above the canvas. */
  _leaderSvg() {
    if (this._svg) return this._svg;
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'hs-lines');
    this.root.appendChild(svg);
    this._svg = svg;
    // Both endpoints are measured, and both move when the stage resizes.
    new ResizeObserver(() => { this._leaderRects = null; }).observe(this.root);
    return svg;
  }

  _startLeaders() {
    if (this._leaderRAF) return;
    const step = () => {
      if (!this._leaders?.size) { this._leaderRAF = 0; return; }
      this._leaderRAF = requestAnimationFrame(step);
      this._paintLeaders();
    };
    this._leaderRAF = requestAnimationFrame(step);
  }

  /**
   * One frame of leader lines.
   *
   * Runs on every animation frame because the anchor moves whenever the camera
   * does and model-viewer gives no "camera moved" event worth trusting.
   *
   * The anchor end is read off the marker's own rect, not off queryHotspot's
   * `canvasPosition`: that one is in canvas pixels, which are device pixels
   * scaled by model-viewer's own render scale, so it does not line up with the
   * CSS pixels the SVG is drawn in. model-viewer already places the slotted
   * marker for us, and its rect is in the right units by construction.
   *
   * The label rect is cached instead, and only re-measured on resize or when
   * the text changes — it does not move with the camera.
   */
  _paintLeaders() {
    const mv = this.mv[this.current];
    if (!mv || !this._leaders?.size) return;

    const base = mv.getBoundingClientRect();
    if (!base.width) return;                   // hidden screen: nothing to draw

    if (!this._leaderRects) {
      this._leaderRects = new Map();
      for (const [id, l] of this._leaders) {
        const r = l.el.getBoundingClientRect();
        this._leaderRects.set(id, {
          cx: r.left - base.left + r.width / 2,
          cy: r.top - base.top + r.height / 2,
          hw: r.width / 2,
          hh: r.height / 2,
        });
      }
    }

    for (const [id, l] of this._leaders) {
      const box = this._leaderRects.get(id);
      const a = l.btn?.getBoundingClientRect();
      if (!box || !a || !a.width) { l.line.classList.remove('on'); continue; }

      const x = a.left - base.left + a.width / 2;
      const y = a.top - base.top + a.height / 2;
      const dx = x - box.cx;
      const dy = y - box.cy;
      // Meet the label at its border rather than its centre: scale the ray
      // down to whichever edge it crosses first.
      const t = Math.min(
        dx ? box.hw / Math.abs(dx) : Infinity,
        dy ? box.hh / Math.abs(dy) : Infinity,
      );
      const k = Math.min(t, 1);
      l.line.setAttribute('x1', box.cx + dx * k);
      l.line.setAttribute('y1', box.cy + dy * k);
      l.line.setAttribute('x2', x);
      l.line.setAttribute('y2', y);
      l.line.classList.add('on');
      // `data-visibility-attribute="visible"` makes model-viewer flag the
      // marker when it faces the camera. Anchor on the far side of the case:
      // keep the label, dim the line, so it never claims to point at
      // something the user cannot see.
      const behind = !l.btn.hasAttribute('visible');
      l.line.classList.toggle('behind', behind);
      l.el.classList.toggle('behind', behind);
    }
  }

  /**
   * Sync hotspots with segment data. Anchors come from the model-viewer editor
   * (modelviewer.dev/editor) and live in HOTSPOT_ANCHORS below.
   */
  updateHotspots(segments) {
    const mv = this.mv[this.current];
    if (!mv) return;

    for (const seg of segments) {
      const slot = `hotspot-seg-${seg.id}`;
      const anchor = HOTSPOT_ANCHORS[slot];
      if (!anchor) continue;

      let btn = mv.querySelector(`[slot="${slot}"]`);
      if (!btn) {
        btn = document.createElement('button');
        btn.className = 'hotspot';
        btn.setAttribute('slot', slot);
        btn.setAttribute('data-position', anchor.position);
        btn.setAttribute('data-normal', anchor.normal);
        btn.setAttribute('data-visibility-attribute', 'visible');
        btn.innerHTML = `
          <div class="hotspot-card">
            <div class="hc-title"></div>
            <div class="hc-rows">
              <span class="hc-k">Tensão</span><span class="hc-v" data-f="v"></span>
              <span class="hc-k">T máx</span><span class="hc-v" data-f="t"></span>
            </div>
          </div>`;
        mv.appendChild(btn);
      }

      btn.dataset.sev = seg.status;
      btn.querySelector('.hc-title').textContent = seg.name.toUpperCase();
      btn.querySelector('[data-f="v"]').textContent = `${seg.voltage.toFixed(1)} V`;
      btn.querySelector('[data-f="t"]').textContent = `${seg.temp_max.toFixed(1)} °C`;
    }
  }
}

/**
 * Fill these once the open GLB exists: open modelviewer.dev/editor, drop
 * the file, click each segment, and paste the generated data-position /
 * data-normal here. Keys must match `Segment.hotspot` from the backend.
 */
export const HOTSPOT_ANCHORS = {
  // 'hotspot-seg-1': { position: '0.12m 0.08m 0.31m', normal: '0m 1m 0m' },
};
