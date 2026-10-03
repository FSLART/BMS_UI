import { api, openStateSocket } from './api.js';
import { CarScreen } from './cars.js';
import { LogConsole } from './console.js';
import { ConnectScreen } from './connect.js';
import { Dashboard } from './dashboard.js';
import { Preloader } from './preload.js';
import * as theme from './theme.js';
import { Viewer } from './viewer.js';

const $ = (id) => document.getElementById(id);

// Tema antes de tudo o resto: o splash e a primeira coisa a aparecer, e aplicar
// depois dava um piscar de escuro para claro em cada arranque. Arranca no tema
// do sistema, a nao ser que alguem ja tenha escolhido no botao.
theme.apply(theme.initial(), { announce: false });
theme.mount();

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
  syncViewers();
}

/**
 * So o modelo que esta mesmo no ecra e que desenha.
 *
 * Um <model-viewer> escondido continua a renderizar: `opacity: 0` ou tamanho
 * zero nao lhe dizem nada. Com quatro no documento, tres desenhavam modelos
 * invisiveis a cada frame -- e o acumulador aberto sozinho sao 5,8 milhoes de
 * triangulos.
 */
function syncViewers() {
  connectViewer.setActive(active === 'connect');
  const page = active === 'dash' ? dashboard.page : null;
  dashViewer.setActive(page === 'overview' || page === 'cells' || page === 'tables');
  chargeViewer.setActive(page === 'charge');
  segmentViewer.setActive(page === 'segment');
}

// --- preload every model behind the splash, before anything else is built ---
// Viewers are constructed only afterwards, so their first _build() already
// finds the object URLs in the cache instead of hitting the network.
const preloader = new Preloader($('screen-loading'));
const preloadedCars = await preloader.run();

// --- viewers: closed on the connection screen, open on the dashboard ----
const connectViewer = new Viewer($('viewer-connect'), 'closed');
const dashViewer = new Viewer($('viewer-dash'), 'open');
// Pack on the handcart. Its GLB does not exist yet, so this shows the viewer's
// placeholder until someone exports one -- the charging page works either way.
const chargeViewer = new Viewer($('viewer-charge'), 'charger');
// One generic segment: the six are identical, only the data on the anchors
// changes. So one model and one set of 36 anchors serve all of them.
const segmentViewer = new Viewer($('viewer-segment'), 'segment');
// No auto-rotate: the connection screen is meant to hold the framing set in
// CAMERA.closed, and a slow spin walks away from it after a few seconds.
connectViewer.idle(false);

/**
 * Preparar o 3D enquanto o ecra de carregamento ainda esta a tapar tudo.
 *
 * Descarregar o GLB e menos de metade do trabalho. O caro e o que vem a
 * seguir: analisar o ficheiro, enviar a geometria para o GPU e compilar os
 * shaders. Isso corre na thread principal e nao se parte em bocados.
 *
 * Antes acontecia a primeira vez que cada vista aparecia -- medido em 1,3 s
 * num unico bloqueio ao sair do ecra do carro, com a janela sem responder a
 * cliques. Agora acontece aqui, onde ninguem esta a tentar carregar em nada.
 *
 * So o carro que ja esta disponivel: os outros nao tem GLB nenhum, e escolher
 * um deles nao tem 3D para preparar.
 */
async function warmUpModels(cars) {
  const car = (cars || []).find((c) => c.available);
  if (!car) return;

  const models = {
    closed: car.model_closed, open: car.model_open,
    charger: car.model_charger, segment: car.model_segment,
  };
  const views = {
    closed: car.view_closed, open: car.view_open,
    charger: car.view_charger, segment: car.view_segment,
  };
  // A ordem e a ordem por que aparecem: o fechado e o primeiro que se ve.
  const plano = [
    [connectViewer, 'closed'],
    [dashViewer, 'open'],
    [chargeViewer, 'charger'],
    [segmentViewer, 'segment'],
  ];
  for (const [v] of plano) {
    v.setViews(views);
    v.setHotspots(car.hotspots);
    v.setModels(models);
  }
  for (let i = 0; i < plano.length; i += 1) {
    const [v, which] = plano[i];
    preloader.note('A preparar os modelos 3D…', ((i + 1) / plano.length) * 100);
    await v.warmUp([which]);
  }
  preloader.note('', 100);
}

await warmUpModels(preloadedCars);

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
  chargeViewer,
  segmentViewer,
  onPage: () => syncViewers(),
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
  onSelect: async (car) => {
    currentCar = car;
    // Rebuilds the transport rows: the CAN bus list belongs to this car.
    await connectScreen.setCar(car);
    // Perfil completo, com limites e capacidade -- o CarMeta que vem no estado
    // so traz o que a vista 3D precisa.
    dashboard.config.setCar(car);
    $('connect-car').textContent = car.name;
    $('dash-car').textContent = car.name;
    const models = {
      closed: car.model_closed, open: car.model_open,
      charger: car.model_charger, segment: car.model_segment,
    };
    const views = {
      closed: car.view_closed, open: car.view_open,
      charger: car.view_charger, segment: car.view_segment,
    };
    for (const v of [connectViewer, dashViewer, chargeViewer, segmentViewer]) {
      v.setViews(views);
      v.setHotspots(car.hotspots);
      v.setModels(models);
    }
    connectScreen.reset();
    showScreen('connect');
  },
});

// Trocar o tema muda variaveis CSS, e a pagina segue sozinha. Os canvas nao:
// pintam pixeis e ficariam com a paleta anterior ate ao proximo estado.
window.addEventListener('bms:theme', () => dashboard.repaint());

$('btn-reset-view').addEventListener('click', () => dashViewer.resetCamera());
$('btn-reset-segment').addEventListener('click', () => segmentViewer.resetCamera());

$('btn-change-car').addEventListener('click', async () => {
  await api.disconnect();
  connectScreen.reset();
  showScreen('car');
});

// Debug handle: lets the console inspect viewer/link state without a build step.
window.__bms = {
  connectViewer,
  dashViewer,
  chargeViewer,
  segmentViewer,
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
    return {
      closed: read(connectViewer), open: read(dashViewer),
      charger: read(chargeViewer), segment: read(segmentViewer),
    };
  },
};

// --- console --------------------------------------------------------------
const logConsole = new LogConsole();
logConsole.attachButtons();
logConsole.startBadgePolling();

// Anchor picker. Targets whichever model is currently on screen, so the same
// button serves the closed model on the connection screen and the open one on
// the dashboard.
/**
 * Whichever model is on screen right now. On the connection screen that is the
 * closed pack; on the dashboard it depends on which page is open.
 */
function currentViewer() {
  if (active !== 'dash') return connectViewer;
  return { segment: segmentViewer, charge: chargeViewer }[dashboard.page] || dashViewer;
}

// Save the framing you just dragged to. Separate from the anchor picker: that
// one returns a point ON the model, this returns where the camera looks FROM.
logConsole.onCaptureView = async () => {
  const got = currentViewer().captureView();
  if (!got) return logConsole.note('Sem modelo carregado nesta vista.', 'WARNING');
  try { await navigator.clipboard.writeText(got.snippet); } catch { /* clipboard may be blocked */ }
  logConsole.note(`${got.snippet}   [copiado — colar no perfil do carro (backend/cars/)]`);
};

let picking = false;
logConsole.onTogglePicker = async () => {
  picking = !picking;
  const viewer = currentViewer();
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

// Quanto tempo o dashboard aguenta uma ligacao em baixo antes de voltar ao
// ecra de ligacao. O RN4871 do BMS reinicia de 30 em 30 s de proposito; sem
// esta margem cada reinicio atirava o utilizador para fora do dashboard.
const RECONNECT_GRACE_MS = 30000;
// Dentro da margem, quanto tempo os dados continuam com aspeto normal antes de
// ficarem cinzentos. Cobre um reinicio do RN4871 sem o ecra piscar; passado
// isto ja nao e um reinicio, e os numeros deixam de parecer atuais.
const STALE_HOLD_MS = 15000;
let lostAt = null;
// Ultima fotografia com o BMS vivo. Durante a margem e esta que se mostra:
// o estado que chega entretanto e o de uma ligacao nova, ainda vazia.
let lastLive = null;

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
    if (!state.stale || !lastLive) {
      lostAt = null;
      lastLive = state;
      dashboard.update(state);
      return;
    }
    // Vivo mas obsoleto: antes de a ligacao cair, o vigia do backend e o
    // ciclo de leitura alternam `stale` em impulsos de ~100 ms. Conta como
    // falha, com a mesma margem, em vez de piscar o ecra.
  } else {
    wentLive = false;
    if (link.status === 'handshaking') {
      setStatus({ kind: 'busy', text: 'Ligação aberta — à espera de trama válida do BMS…' });
    } else if (link.status === 'connecting') {
      // O detalhe diz o que se esta a abrir -- e, no RN4871 fixo, que se
      // esta a procurar por ele de novo.
      setStatus({ kind: 'busy', text: `${link.detail || `A abrir ${link.type.toUpperCase()}`}…` });
    } else if (link.status === 'error') {
      setStatus({ kind: 'error', text: link.error || 'Falha na ligação' });
    }
  }

  if (active !== 'dash') return;
  // Ligacao em baixo, ou ligada mas sem dados: o dashboard fica com a ultima
  // fotografia boa enquanto o backend volta a ligar. Cinzenta so passado
  // STALE_HOLD_MS; ecra de ligacao so passado RECONNECT_GRACE_MS -- e nunca
  // enquanto a ligacao esta aberta, so a espera de dados.
  lostAt ??= Date.now();
  const away = Date.now() - lostAt;
  const open = link.status === 'live' || link.status === 'handshaking';
  if (link.status === 'disconnected' || (!open && away >= RECONNECT_GRACE_MS)) {
    lostAt = null;
    lastLive = null;
    showScreen('connect');
    connectViewer.show('closed');
  } else {
    dashboard.update({ ...(lastLive || state), link, stale: away >= STALE_HOLD_MS });
  }
});
