import { api, openStateSocket } from './api.js';
import { CarScreen } from './cars.js';
import { LogConsole } from './console.js';
import { ConnectScreen } from './connect.js';
import { Dashboard } from './dashboard.js';
import { Preloader } from './preload.js';
import { Viewer } from './viewer.js';

const $ = (id) => document.getElementById(id);

const screens = {
  loading: $('screen-loading'),
  car: $('screen-car'),
  connect: $('screen-connect'),
  dash: $('screen-dash'),
};
let active = 'loading';

function showScreen(name) {
  if (active === name) return;
  active = name;
  Object.entries(screens).forEach(([k, el]) => el.classList.toggle('active', k === name));
}

// --- preload every model behind the splash, before anything else is built ---
// Viewers are constructed only afterwards, so their first _build() already
// finds the object URLs in the cache instead of hitting the network.
await new Preloader($('screen-loading')).run();

// --- viewers: closed on the connection screen, open on the dashboard ----
const connectViewer = new Viewer($('viewer-connect'), 'closed');
const dashViewer = new Viewer($('viewer-dash'), 'open');
// No auto-rotate: the connection screen is meant to hold the framing set in
// CAMERA.closed, and a slow spin walks away from it after a few seconds.
connectViewer.idle(false);

// --- status line -----------------------------------------------------------
// Link-bound hotspots on the closed model. The connection screen only ever
// shows a BMS that is not live yet, so this reads Offline until it is.
const LINK_HOTSPOT = {
  idle:  { label: 'Offline', sev: 'idle' },
  busy:  { label: 'A ligar…', sev: 'warn' },
  live:  { label: 'Online', sev: 'ok' },
  error: { label: 'Sem resposta', sev: 'fault' },
};

function setStatus({ kind, text }) {
  const line = $('status-line');
  line.className = `status-line${kind === 'error' ? ' err' : ''}`;
  const dotClass = { idle: '', busy: 'warn', live: 'ok', error: 'fault' }[kind] ?? '';
  line.innerHTML = `<span class="dot ${dotClass}"></span><span>${text}</span>`;
  $('conn-dot').className = `dot ${dotClass}`;
  $('conn-pill-text').textContent =
    { idle: 'Desligado', busy: 'A ligar', live: 'BMS ativo', error: 'Erro' }[kind] ?? 'Desligado';

  const hs = LINK_HOTSPOT[kind] || LINK_HOTSPOT.idle;
  connectViewer.setHotspotState('rts', hs);
}

const connectScreen = new ConnectScreen($('transport-list'), { onStatus: setStatus });
const dashboard = new Dashboard({
  viewer: dashViewer,
  onDisconnect: async () => {
    await api.disconnect();
    connectScreen.reset();
    showScreen('connect');
    connectViewer.show('closed');
  },
});

// --- car selection ---------------------------------------------------------
let currentCar = null;

const carScreen = new CarScreen($('car-grid'), {
  onSelect: (car) => {
    currentCar = car;
    $('connect-car').textContent = car.name;
    $('dash-car').textContent = car.name;
    const models = { closed: car.model_closed, open: car.model_open };
    const views = { closed: car.view_closed, open: car.view_open };
    connectViewer.setViews(views);
    dashViewer.setViews(views);
    connectViewer.setHotspots(car.hotspots);
    dashViewer.setHotspots(car.hotspots);
    connectViewer.setModels(models);
    dashViewer.setModels(models);
    connectScreen.reset();
    showScreen('connect');
  },
});

$('btn-reset-view').addEventListener('click', () => dashViewer.resetCamera());

$('btn-change-car').addEventListener('click', async () => {
  await api.disconnect();
  connectScreen.reset();
  showScreen('car');
});

// Debug handle: lets the console inspect viewer/link state without a build step.
window.__bms = {
  connectViewer,
  dashViewer,
  connectScreen,
  dashboard,
  /**
   * Frame a model by dragging, then call __bms.orbit() to read the values back
   * as a camera-orbit string ready to paste into CAMERA in viewer.js.
   */
  orbit() {
    const read = (v) => {
      const mv = v.mv[v.current];
      if (!mv) return null;
      const o = mv.getCameraOrbit();
      const deg = (rad) => `${Math.round((rad * 180) / Math.PI)}deg`;
      // Radius comes back in metres. camera-orbit accepts metres as well as %,
      // so this string can be pasted straight into CAMERA.
      return {
        view: v.current,
        orbit: `${deg(o.theta)} ${deg(o.phi)} ${o.radius.toFixed(2)}m`,
        fov: `${mv.getFieldOfView().toFixed(0)}deg`,
      };
    };
    return { closed: read(connectViewer), open: read(dashViewer) };
  },
};

// --- console --------------------------------------------------------------
const logConsole = new LogConsole();
logConsole.attachButtons();
logConsole.startBadgePolling();

// Anchor picker. Targets whichever model is currently on screen, so the same
// button serves the closed model on the connection screen and the open one on
// the dashboard.
let picking = false;
logConsole.onTogglePicker = async () => {
  picking = !picking;
  const viewer = active === 'dash' ? dashViewer : connectViewer;
  const ok = viewer.enablePicker(picking, async (hit) => {
    if (!hit) return logConsole.note('Clique fora da geometria — sem interseção.', 'WARNING');
    const snippet = `Hotspot(id="", view="${hit.view}", label="",
`
                  + `        position="${hit.position}", normal="${hit.normal}", binds=""),`;
    try { await navigator.clipboard.writeText(snippet); } catch { /* clipboard may be blocked */ }
    logConsole.note(`position="${hit.position}"  normal="${hit.normal}"  [copiado]`);
  });
  logConsole.setPicking(picking && ok);
  logConsole.note(picking && ok
    ? 'Modo âncora ligado — clica numa peça do modelo.'
    : 'Modo âncora desligado.');
};

await carScreen.mount();
await connectScreen.mount();
setStatus({ kind: 'idle', text: 'À espera de seleção' });

// Everything is built and the models are in memory: hand over to the app.
showScreen('car');

// --- state stream ----------------------------------------------------------
let wentLive = false;

openStateSocket((state) => {
  const link = state.link;
  connectScreen.applyLinkState(link);

  if (link.status === 'live') {
    if (!wentLive) {
      wentLive = true;
      setStatus({ kind: 'live', text: `BMS ativo — ${link.detail}` });
      // Beat of confirmation on the connection screen, then crossfade into the
      // open model. Both screens fade simultaneously (see .screen in CSS).
      setTimeout(() => {
        showScreen('dash');
        dashViewer.show('open');
      }, 700);
    }
    dashboard.update(state);
  } else {
    wentLive = false;
    if (link.status === 'handshaking') {
      setStatus({ kind: 'busy', text: 'Ligação aberta — à espera de trama válida do BMS…' });
    } else if (link.status === 'connecting') {
      setStatus({ kind: 'busy', text: `A abrir ${link.type.toUpperCase()}…` });
    } else if (link.status === 'error') {
      setStatus({ kind: 'error', text: link.error || 'Falha na ligação' });
    }
    // Falling out of live drops back to the connection screen, never past the
    // car screen — the car stays chosen.
    if (active === 'dash' && link.status !== 'handshaking') {
      showScreen('connect');
      connectViewer.show('closed');
    }
  }
});
