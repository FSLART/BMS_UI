import { Sparkline } from './chart.js';
import { Commands } from './commands.js';
import { ConfigPage } from './config.js';
import { GroupedBarChart, ValueTable } from './panels.js';

const $ = (id) => document.getElementById(id);

/** Seconds as h/min/s, dropping the units that are still zero. */
function fmtDuration(s) {
  s = Math.max(0, Math.round(s));
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min ${String(s % 60).padStart(2, '0')} s`;
  return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')} min`;
}

/**
 * Labels for every safety signal the universal model can carry.
 *
 * Which of them a car actually shows comes from `safety.available`, filled by
 * that car's decoder. IMD, BSPD and insulation resistance are in this table
 * because other cars may report them; the TEK-26e's AMS does not read them, so
 * they simply never appear rather than showing a light that means nothing.
 */
const SAFETY_LABELS = {
  ams_ok: 'AMS',
  imd_ok: 'IMD',
  bspd_ok: 'BSPD',
  sdc_closed: 'SDC',
  air_positive: 'AIR+',
  air_negative: 'AIR−',
  precharge_done: 'Pré-carga',
};

/**
 * Cell voltage -> colour. Healthy cells ride a green ramp that brightens with
 * voltage; warn and fault break out into amber and red. The three states match
 * the legend under the grid and the --ok/--warn/--fault tokens in the CSS.
 */
export function cellColor(v, sev, lo = 3.0, hi = 4.15) {
  const light = document.documentElement.dataset.theme === 'light';
  if (sev === 'fault') return light ? '#F3C0BC' : '#8E2020';
  if (sev === 'warn') return light ? '#F6DCA8' : '#8A5310';
  // A rampa vai do aviso de baixa ao de alta, que sao os limites configurados
  // -- estavam aqui em numeros fixos e discordavam do resto da aplicacao.
  const t = Math.max(0, Math.min(1, (v - lo) / ((hi - lo) || 1)));
  // No tema claro a rampa inverte o sentido do brilho: escurece a subir, em vez
  // de clarear. Manter a rampa escura sobre fundo branco dava uma grelha de
  // blocos pesados, e a celula mais carregada -- a que se quer notar -- era a
  // que menos contrastava com o fundo.
  const stops = light ? [
    [0.0, [206, 233, 216]],
    [0.45, [166, 216, 187]],
    [0.75, [110, 189, 148]],
    [1.0, [46, 158, 110]],
  ] : [
    [0.0, [20, 56, 42]],
    [0.45, [24, 84, 60]],
    [0.75, [32, 118, 84]],
    [1.0, [46, 158, 110]],
  ];
  let a = stops[0], b = stops[stops.length - 1];
  for (let i = 0; i < stops.length - 1; i++) {
    if (t >= stops[i][0] && t <= stops[i + 1][0]) { a = stops[i]; b = stops[i + 1]; break; }
  }
  const f = (t - a[0]) / ((b[0] - a[0]) || 1);
  const c = a[1].map((ch, i) => Math.round(ch + (b[1][i] - ch) * f));
  return `rgb(${c[0]},${c[1]},${c[2]})`;
}

export class Dashboard {
  constructor({ viewer, chargeViewer, segmentViewer, onDisconnect }) {
    this.viewer = viewer;
    this.chargeViewer = chargeViewer;
    this.segmentViewer = segmentViewer;
    this.built = false;
    this.selectedSegment = null;
    this.cellEls = [];
    this.segEls = [];
    this.commands = new Commands($('cmd-list'));
    // A pagina de configuracao manda nos limites que a interface desenha.
    this.config = new ConfigPage($('cfg-sections'), {
      onChange: () => this._applyConfig(),
    });
    // Same machinery, filtered: on the charging page only the charging command
    // makes sense, and it is where someone standing at the handcart is looking.
    this.chargeCommands = new Commands($('chg-cmd-list'), { only: ['charging'] });
    this.chart = new Sparkline($('chart'), [
      // Current gets the accent; voltage a cool tone that cannot be mistaken
      // for any of the ok/warn/fault status colours.
      { key: 'current', color: '#FFC300', label: 'I', unit: 'A' },
      { key: 'voltage', color: '#9BB4D9', label: 'V', unit: 'V' },
    ]);
    this._lastChartPush = 0;
    $('btn-disconnect').addEventListener('click', onDisconnect);

    // --- extra pages ------------------------------------------------------
    // Everything is grouped by SEGMENT, the unit the pack is built and
    // serviced in. The AMS slave boundary shows as a dashed line inside each
    // segment rather than as its own grouping.
    this.page = 'overview';
    for (const tab of document.querySelectorAll('#dash-tabs .tab')) {
      tab.addEventListener('click', () => this.showPage(tab.dataset.page));
    }

    this.vChart = new GroupedBarChart($('chart-voltage'), {
      min: 2.5, max: 4.3, warnLow: 2.8, warnHigh: 4.15, unit: 'V', decimals: 3,
    });
    this.tChart = new GroupedBarChart($('chart-temps'), {
      min: 0, max: 70, warnAt: 50, unit: '°C', decimals: 0,
    });


    // Which anchor families are drawn on the segment model. Both on to start:
    // the page is about seeing everything, and hiding is the exception.
    this.segShow = { v: true, t: true };
    for (const btn of document.querySelectorAll('.seg-toggle')) {
      btn.addEventListener('click', () => {
        const kind = btn.dataset.kind;
        this.segShow[kind] = !this.segShow[kind];
        btn.classList.toggle('on', this.segShow[kind]);
        this._applySegToggles();
      });
    }

    this.vTable = new ValueTable($('table-voltage'), {
      cols: 0, rowLabel: (r) => `Segmento ${r + 1}`, extra: ['mín', 'máx', 'Δ'],
    });
    this.tTable = new ValueTable($('table-temps'), {
      cols: 0, rowLabel: (r) => `Segmento ${r + 1}`, extra: ['mín', 'máx', 'Δ'],
    });
  }

  /**
   * Push the configured limits into everything the interface draws itself.
   *
   * These numbers used to be written three times -- in cellColor, in the two
   * bar charts, and in the car profile -- and drifted apart. Now the config
   * page is the one place, and this is where it lands.
   */
  _applyConfig() {
    const l = this.config.limits();
    if (l.v_min == null) return;
    Object.assign(this.vChart.scale, {
      // Um pouco de folga para lá dos limites, senão uma célula em falta fica
      // encostada ao topo do gráfico e não se vê o quanto passou.
      min: Math.min(l.v_min - 0.3, 2.5),
      max: Math.max(l.v_max + 0.1, 4.3),
      warnLow: l.v_warn_low,
      warnHigh: l.v_warn_high,
    });
    Object.assign(this.tChart.scale, {
      warnAt: l.temp_warn,
      max: Math.max(l.temp_fault + 10, 70),
    });
    if (this.page === 'cells') {
      this.vChart.draw();
      this.tChart.draw();
    }
  }

  /**
   * Repinta o que e desenhado em canvas depois de o tema mudar.
   *
   * Os canvas nao herdam cor: ficariam com a paleta antiga ate ao proximo
   * estado -- ou para sempre, se a ligacao estiver parada ou a pagina aberta
   * for a de configuracao, que nao recebe estado nenhum.
   */
  repaint() {
    for (const c of [this.vChart, this.tChart]) c?.draw();
    this.config?.refresh();
    if (this._last) this.update(this._last);
  }

  showPage(name) {
    this.page = name;
    for (const tab of document.querySelectorAll('#dash-tabs .tab')) {
      tab.classList.toggle('active', tab.dataset.page === name);
    }
    for (const page of document.querySelectorAll('.dash-pages .page')) {
      page.classList.toggle('active', page.id === `page-${name}`);
    }
    // Canvases sized while display:none measure zero, so re-measure on reveal.
    // setTimeout rather than requestAnimationFrame: rAF is tied to the frame
    // loop, which stalls whenever the window is not compositing, and the panels
    // would then stay at the default 300x150 canvas size.
    setTimeout(() => {
      for (const c of [this.vChart, this.tChart]) {
        c._resize();
        c.draw();
      }
      if (name === 'config') this.config.refresh();
    }, 0);
  }

  /**
   * Identity of the rendered skeleton. Total cell count is not enough: 6x24 and
   * 8x18 are both 144 cells but need different grids and segment cards.
   */
  static topology(state) {
    return `${state.car.id}|${state.n_segments}x${state.cells_per_segment}`;
  }

  /** Build the static skeleton once the pack topology is known. */
  _build(state) {
    this.topologySig = Dashboard.topology(state);
    this.selectedSegment = null;
    const segList = $('segment-list');
    segList.innerHTML = '';
    this.segEls = [];
    for (const seg of state.segments) {
      const card = document.createElement('div');
      card.className = 'seg-card';
      card.innerHTML = `
        <div class="seg-head">
          <span class="dot"></span>
          <span class="seg-name">${seg.name}</span>
          <span data-f="v"></span>
        </div>
        <div class="seg-bar"><i data-f="bar"></i></div>
        <div class="readouts">
          <span class="ro" title="Temperatura máxima"><b>T</b><span data-f="tmax"></span></span>
          <span class="ro" title="Paralelo mais baixo"><b>↓</b><span data-f="vmin"></span></span>
          <span class="ro" title="Paralelo mais alto"><b>↑</b><span data-f="vmax"></span></span>
        </div>`;
      card.addEventListener('click', () => {
        // Um clique escolhe o segmento e abre a página dele. Voltar a clicar no
        // mesmo cartão fecha, para não ficar preso numa página que já não
        // interessa.
        const same = this.selectedSegment === seg.id && this.page === 'segment';
        this.selectedSegment = same ? null : seg.id;
        this.segEls.forEach((e, i) =>
          e.card.classList.toggle('sel', state.segments[i].id === this.selectedSegment));
        this.showPage(same ? 'overview' : 'segment');
      });
      segList.appendChild(card);
      this.segEls.push({
        card,
        dot: card.querySelector('.dot'),
        bar: card.querySelector('[data-f="bar"]'),
        v: card.querySelector('[data-f="v"]'),
        tmax: card.querySelector('[data-f="tmax"]'),
        vmin: card.querySelector('[data-f="vmin"]'),
        vmax: card.querySelector('[data-f="vmax"]'),
      });
    }

    const safety = $('safety-grid');
    safety.innerHTML = '';
    this.safetyEls = {};
    // Only what this car reports. An empty list would mean a decoder that says
    // nothing about safety, and an empty panel is the right answer there too.
    for (const key of state.safety.available || []) {
      const label = SAFETY_LABELS[key];
      if (!label) continue;
      const el = document.createElement('div');
      el.className = 'safety-item';
      el.innerHTML = `<span class="dot"></span><span>${label}</span>`;
      safety.appendChild(el);
      this.safetyEls[key] = el.querySelector('.dot');
    }

    const grid = $('cell-grid');
    grid.innerHTML = '';
    this.cellEls = [];
    for (let s = 0; s < state.n_segments; s++) {
      const row = document.createElement('div');
      row.className = 'cell-row';
      const label = document.createElement('span');
      label.className = 'row-label';
      label.textContent = `S${s + 1}`;
      row.appendChild(label);
      for (let c = 0; c < state.cells_per_segment; c++) {
        const cell = document.createElement('div');
        cell.className = 'cell';
        row.appendChild(cell);
        this.cellEls.push(cell);
      }
      grid.appendChild(row);
    }

    // A "cell" in this grid is one series group; parallel cells share a node
    // and the BMS reads them as a single voltage. Say so, so 144 tiles on a
    // 432-cell pack is not read as a bug.
    const topo = $('cells-topo');
    if (topo) {
      const p = state.parallel_strings || 1;
      topo.innerHTML = '';
      for (const t of [state.topology, state.cell_model].filter(Boolean)) {
        const tag = document.createElement('span');
        tag.className = 'tag';
        tag.textContent = t;
        topo.appendChild(tag);
      }
      // The long-form explanation belongs in a tooltip, not on the screen.
      topo.title = `${state.cells.length} paralelos × ${p}p = ${state.cells.length * p} células`;
    }

    this.built = true;
  }

  update(state) {
    if (!state.segments.length) return;
    // Guardado para o repaint do tema, que precisa de redesenhar sem esperar
    // pelo proximo estado.
    this._last = state;
    if (!this.built || this.topologySig !== Dashboard.topology(state)) this._build(state);

    document.getElementById('screen-dash').classList.toggle('stale', state.stale);

    const p = state.pack;
    $('k-voltage').innerHTML = `${p.voltage.toFixed(1)}<small>V</small>`;
    $('k-current').innerHTML = `${p.current.toFixed(1)}<small>A</small>`;
    $('k-power').innerHTML = `${(p.power / 1000).toFixed(1)}<small>kW</small>`;
    $('k-soc').innerHTML = `${p.soc.toFixed(0)}<small>%</small>`;
    $('k-temp').innerHTML = `${p.temp_max.toFixed(1)}<small>°C</small>`;
    $('k-delta').innerHTML = `${(p.cell_v_delta * 1000).toFixed(0)}<small>mV</small>`;
    $('kpi-temp').className = `kpi ${p.temp_max >= 60 ? 'fault' : p.temp_max >= 50 ? 'warn' : ''}`;
    $('kpi-delta').className = `kpi ${p.cell_v_delta >= 0.1 ? 'warn' : ''}`;

    $('dash-link-type').textContent = state.link.type.toUpperCase();
    $('dash-link').textContent = state.link.detail;
    $('dash-rate').textContent = state.stale
      ? 'sem dados'
      : `${state.link.rx_rate.toFixed(0)} Hz`;
    $('dash-dot').className = `dot ${state.stale ? 'fault' : 'ok'}`;

    // Estado das duas máquinas do AMS, com os nomes que a DBC lhes dá. Substitui
    // o bloco de isolamento: a resistência não é medida por este BMS, e o SOH
    // não vem em sinal nenhum -- eram os dois números fixos.
    const st = $('s-state');
    if (st) st.textContent = state.safety.master_state || '—';
    const pc = $('s-precharge');
    if (pc) pc.textContent = state.safety.precharge_state || '—';

    const a = state.ams || {};
    $('s-fan').textContent = a.fan_pwm == null ? '—' : `${a.fan_pwm.toFixed(0)} %`;
    $('s-mcu').textContent = a.mcu_temperature == null ? '—' : `${a.mcu_temperature.toFixed(0)} °C`;
    // Menos slaves que o esperado é uma falha que o decoder já levanta; aqui
    // fica só a contagem, a vermelho para não passar despercebida.
    const expected = state.slave_count || 0;
    $('s-slaves').textContent = a.slaves_detected == null
      ? '—' : `${a.slaves_detected}/${expected}`;
    $('s-slaves').style.color =
      a.slaves_detected != null && expected && a.slaves_detected < expected
        ? 'var(--fault)' : '';

    state.segments.forEach((seg, i) => {
      const e = this.segEls[i];
      if (!e) return;
      e.dot.className = `dot ${seg.status}`;
      e.card.dataset.sev = seg.status;
      // Level track: weakest group in the segment across the usable window,
      // the same 3.0-4.15 V span the cell grid ramps over.
      const level = Math.max(0, Math.min(1, (seg.cell_v_min - 3.0) / (4.15 - 3.0)));
      e.bar.style.width = `${(level * 100).toFixed(1)}%`;
      e.v.textContent = `${seg.voltage.toFixed(1)} V`;
      e.tmax.textContent = `${seg.temp_max.toFixed(1)} °C`;
      e.vmin.textContent = seg.cell_v_min.toFixed(3);
      e.vmax.textContent = seg.cell_v_max.toFixed(3);
    });

    for (const [key, dot] of Object.entries(this.safetyEls)) {
      dot.className = `dot ${state.safety[key] ? 'ok' : 'fault'}`;
    }

    state.cells.forEach((c, i) => {
      const el = this.cellEls[i];
      if (!el) return;
      const lim = this.config.limits();
      el.style.background = cellColor(c.voltage, c.status, lim.v_warn_low, lim.v_warn_high);
      el.dataset.sev = c.status;
      el.classList.toggle('open-wire', !!c.open_wire);
      const dim = this.selectedSegment && c.segment !== this.selectedSegment;
      el.style.opacity = dim ? '0.28' : '1';
      // No per-cell temperature on this bus, and no per-cell balancing either:
      // showing "undefined °C" was worse than showing nothing.
      const where = `Seg ${c.segment} · Slave ${c.slave} · Célula ${c.slave_channel}`;
      el.title = c.open_wire
        ? `${where}\nOpenwire: fio de medição solto (${c.voltage.toFixed(3)} V)`
        : `${where}\n${c.voltage.toFixed(3)} V`;
    });

    // Balancing renders from the shape of the data, not from a setting.
    //
    // Today the AMS publishes one pack-wide state, so every cell carries the
    // same flag and a single badge is the honest way to show it. The firmware
    // is due to send a per-cell bitmask; the day it does, the cells stop
    // agreeing and the marks appear on the ones actually discharging, with no
    // change here.
    const balancing = state.cells.filter((c) => c.balancing).length;
    const perCell = balancing > 0 && balancing < state.cells.length;
    const bal = $('bal-badge');
    if (bal) bal.hidden = !(balancing > 0 && !perCell);
    if (perCell !== this._perCellBalancing) {
      this._perCellBalancing = perCell;
      if (!perCell) this.cellEls.forEach((el) => el && el.classList.remove('balancing'));
    }
    if (perCell) {
      state.cells.forEach((c, i) => {
        const el = this.cellEls[i];
        if (el) el.classList.toggle('balancing', c.balancing);
      });
    }

    const faults = $('fault-list');
    if (!state.faults.length) {
      faults.innerHTML = '<div class="empty-note">Sem falhas registadas.</div>';
    } else if (faults.dataset.n !== String(state.faults.length)) {
      faults.dataset.n = String(state.faults.length);
      faults.innerHTML = state.faults.map((f) => `
        <div class="fault-item" data-sev="${f.severity}">
          <div class="fault-code">${f.code}</div>
          <div class="fault-msg">${f.message}</div>
        </div>`).join('');
    }

    // Chart at 5 Hz — the WS runs at 10 and the canvas does not need it.
    const now = performance.now();
    if (now - this._lastChartPush > 200) {
      this._lastChartPush = now;
      this.chart.push([p.current, p.voltage]);
    }

    this._updateHotspots(state);
    // setCar vem do perfil cru (/api/cars, via main.js): o CarMeta do
    // websocket nao transporta limites nem capacidade.
    this.config.setLive(state);
    this._updateSegmentPage(state);
    this.commands.setCommands(state.car.commands);
    this.commands.setLink(state);
    this.chargeCommands.setCommands(state.car.commands);
    this.chargeCommands.setLink(state);
    this._updateCharger(state);
    this._updatePages(state);
  }

  /**
   * Segment page.
   *
   * One generic model serves all six: the segments are physically identical,
   * so the geometry is shared and only the values hung on the anchors change.
   * That means 36 anchors picked once (24 parallels + 12 NTCs) instead
   * of six models with 216 anchors between them.
   */
  _updateSegmentPage(state) {
    const seg = state.segments.find((s) => s.id === this.selectedSegment);
    const tab = $('tab-segment');
    if (tab) {
      tab.hidden = !seg;
      tab.textContent = seg ? seg.name : 'Segmento';
      if (!seg && this.page === 'segment') this.showPage('overview');
    }
    if (!seg || this.page !== 'segment') return;

    const cells = state.cells.filter((c) => c.segment === seg.id);
    const ntcs = state.thermistors.filter((t) => t.segment === seg.id);

    $('seg-title').textContent = seg.name;
    $('seg-v').textContent = seg.voltage.toFixed(2);
    $('seg-sub').textContent =
      `${cells.length} paralelos × ${state.parallel_strings}p · ${ntcs.length} NTC`;

    const volts = cells.filter((c) => c.status !== 'unknown').map((c) => c.voltage);
    const temps = ntcs.map((t) => t.temperature).filter((t) => t != null);
    const v3 = (x) => (x == null ? '—' : `${x.toFixed(3)} V`);
    $('seg-vmin').textContent = volts.length ? v3(Math.min(...volts)) : '—';
    $('seg-vmax').textContent = volts.length ? v3(Math.max(...volts)) : '—';
    $('seg-vdelta').textContent = volts.length
      ? `${((Math.max(...volts) - Math.min(...volts)) * 1000).toFixed(0)} mV` : '—';
    $('seg-tmax').textContent = temps.length ? `${Math.max(...temps).toFixed(1)} °C` : '—';
    $('seg-tavg').textContent = temps.length
      ? `${(temps.reduce((a, b) => a + b, 0) / temps.length).toFixed(1)} °C` : '—';
    $('seg-groups-sub').textContent = `${volts.length}/${cells.length} a reportar`;
    $('seg-ntc-sub').textContent = `${temps.length}/${ntcs.length} a reportar`;

    // The anchors. `index` is the group's position inside the segment, which is
    // exactly what par-N was numbered by.
    for (const c of cells) {
      this.segmentViewer.setHotspotState(`par-${c.index}`, {
        label: c.open_wire ? 'openwire' : `${c.voltage.toFixed(3)} V`,
        sev: c.status === 'unknown' ? 'idle' : c.status,
        hot: c.balancing,
      });
    }
    // NTCs are numbered 1..12 across the segment's two slaves.
    ntcs.forEach((t, i) => {
      this.segmentViewer.setHotspotState(`ntc-${i + 1}`, {
        label: t.temperature == null ? 'sem leitura' : `${t.temperature.toFixed(1)} °C`,
        sev: t.temperature == null ? 'idle' : t.status,
      });
    });

    this._applySegToggles(cells.length, ntcs.length);
    this._paintSegmentTable(state, cells, ntcs);
  }

  /** Hide or show each anchor family on the segment model. */
  _applySegToggles(nGroups = 24, nNtc = 12) {
    if (!this.segmentViewer) return;
    for (let i = 1; i <= nGroups; i++) {
      this.segmentViewer.setHotspotState(`par-${i}`, { off: !this.segShow.v });
    }
    for (let i = 1; i <= nNtc; i++) {
      this.segmentViewer.setHotspotState(`ntc-${i}`, { off: !this.segShow.t });
    }
  }

  /**
   * Two rows: the segment's groups and its NTCs. They are different lengths
   * (24 against 12), so the table is sized to the longer and the short row
   * simply runs out -- padding it with dashes would suggest twelve sensors
   * that do not exist.
   */
  _paintSegmentTable(state, cells, ntcs) {
    const box = $('seg-table');
    if (!box) return;
    if (!this.segTable) {
      this.segTable = new ValueTable(box, {
        cols: 0,
        rowLabel: (r) => (r === 0 ? 'V' : 'NTC'),
        extra: ['mín', 'máx', 'Δ'],
      });
    }
    const width = Math.max(cells.length, ntcs.length);
    this.segTable.cols = width;
    const sub = $('seg-table-sub');
    if (sub) sub.textContent = `${cells.length} paralelos · ${ntcs.length} NTC`;

    const row = (items, fmt) => {
      const vals = items.map((i) => i.value).filter((v) => v != null);
      const lo = vals.length ? Math.min(...vals) : null;
      const hi = vals.length ? Math.max(...vals) : null;
      const body = Array.from({ length: width }, (_, i) => {
        const it = items[i];
        if (!it) return { text: '', cls: '' };
        return { text: it.value == null ? '—' : fmt(it.value),
                 cls: it.status && it.status !== 'ok' ? it.status : '' };
      });
      return [...body,
        { text: lo == null ? '—' : fmt(lo), cls: 'stat-col' },
        { text: hi == null ? '—' : fmt(hi), cls: 'stat-col' },
        { text: lo == null ? '—' : fmt(hi - lo), cls: 'stat-col' }];
    };

    this.segTable.render([
      row(cells.map((c) => ({ value: c.status === 'unknown' ? null : c.voltage,
                              status: c.status })), (v) => v.toFixed(3)),
      row(ntcs.map((t) => ({ value: t.temperature, status: t.status })),
          (v) => v.toFixed(1)),
    ]);
  }

  /**
   * Charging tab.
   *
   * The charger lives on the car's other CAN bus — the one that only exists
   * while the accumulator is on the handcart. On the car bus there is nothing
   * to show, so the tab appears only once a charger has actually been heard.
   */
  _updateCharger(state) {
    const c = state.charger || {};

    // Which bus we landed on, shown next to the link. Nobody chose it, so it
    // is worth saying out loud what the app concluded.
    const tag = $('mode-tag');
    if (tag) {
      const charging = state.mode === 'charger';
      tag.hidden = !charging;
      tag.textContent = state.charging ? 'a carregar' : 'handcart';
    }

    const tab = $('tab-charge');
    if (tab) {
      tab.hidden = !c.present;
      // Leaving a hidden page selected would show an empty dashboard.
      if (!c.present && this.page === 'charge') this.showPage('overview');
    }
    if (!c.present || this.page !== 'charge') return;

    // LEDs do carro de carga: verde ate a alta fechar, vermelho a partir dai.
    // A fonte e o estado de pre-carga do master, o mesmo que bloqueia a pagina
    // de configuracao -- HV_ON quer dizer contactores fechados.
    this.chargeViewer.setLeds((state.safety || {}).precharge_state === 'HV_ON');

    const v = (x, unit, digits = 1) =>
      (typeof x === 'number' ? `${x.toFixed(digits)} ${unit}` : '—');

    $('chg-v').textContent = v(c.output_voltage, 'V');
    $('chg-i').textContent = v(c.output_current, 'A', 2);
    $('chg-t').textContent = c.temperature == null ? '—' : v(c.temperature, '°C');
    $('chg-req-v').textContent = v(c.requested_voltage, 'V');
    $('chg-req-i').textContent = v(c.requested_current, 'A', 2);
    $('chg-pack').textContent = v(state.pack.voltage, 'V');

    $('chg-state').textContent = state.charging ? 'a carregar' : 'ligado, sem corrente';
    // Control = 0 means "charge", 1 means "stop" (confirmed in the firmware).
    $('chg-cmd').textContent = c.enabled ? 'carregar' : 'parar';

    // Session counter. kWh because a full charge of this pack is a few of
    // them, and Wh would run to five digits.
    $('chg-energy').textContent = ((c.energy_wh || 0) / 1000).toFixed(3);
    $('chg-session').textContent = fmtDuration(c.session_s || 0);
    const watts = (c.output_voltage || 0) * (c.output_current || 0);
    $('chg-power').textContent = watts > 1 ? `${(watts / 1000).toFixed(2)} kW` : 'sem corrente';

    // Annotations on the handcart model, same binding style as the pack views.
    if (this.chargeViewer) {
      this.chargeViewer.setHotspotState('chg-precharge', state.safety.precharge_done
        ? { label: 'HV_ON', sev: 'ok' }
        : { label: 'Pré-carga aberta', sev: 'idle' });
      this.chargeViewer.setHotspotState('chg-control', c.enabled
        ? { label: state.charging ? 'A carregar' : 'Carregar pedido', sev: 'ok' }
        : { label: 'Parado', sev: 'idle' });
    }

    const box = $('chg-faults');
    if (box && box.dataset.n !== String((c.faults || []).length)) {
      box.dataset.n = String((c.faults || []).length);
      box.innerHTML = (c.faults || []).length
        ? c.faults.map((f) => `<div class="fault-item" data-sev="fault">
             <div class="fault-msg">${f}</div></div>`).join('')
        : '<div class="empty-note">Sem avisos do carregador.</div>';
    }
  }

  // ── extra pages ─────────────────────────────────────────────────────────
  //
  // Only the visible page is recomputed. Feeding four panels at 10 Hz when
  // three of them are display:none is work nobody sees.
  _updatePages(state) {
    const bySegment = (items) => {
      const out = [];
      for (let seg = 1; seg <= state.n_segments; seg++) out.push([]);
      for (const it of items) {
        const arr = out[it.segment - 1];
        if (arr) arr.push(it);
      }
      return out;
    };

    if (this.page !== 'cells' && this.page !== 'tables') return;

    this.segCells = bySegment(state.cells);
    this.segTemps = bySegment(state.thermistors);

    if (this.page === 'cells') {
      this._paintVoltage(state);
      this._paintTemps(state);
    } else {
      this._paintTables(state);
    }
  }

  _stats(values) {
    let lo = null, hi = null, loAt = '', hiAt = '';
    for (const it of values) {
      const v = it.v;
      if (v == null) continue;
      if (lo == null || v < lo) { lo = v; loAt = it.at; }
      if (hi == null || v > hi) { hi = v; hiAt = it.at; }
    }
    return { lo, hi, loAt, hiAt, delta: lo != null && hi != null ? hi - lo : null };
  }

  _paintVoltage(state) {
    const groups = this.segCells.map((cells, i) => ({
      label: `Segmento ${i + 1}`,
      split: state.cells_per_slave || 0,
      values: cells.map((c) => ({
        v: c.voltage, status: c.status,
        label: `Segmento ${c.segment} · Slave ${c.slave} · Célula ${c.slave_channel}`,
        sub: `Segmento ${c.segment} · Paralelo ${c.index}`,
      })),
    }));
    this.vChart.setData(groups);

    const flat = state.cells.map((c) => ({ v: c.voltage, at: `S${c.segment}·G${c.index}` }));
    const st = this._stats(flat);
    $('v-sub').textContent = `${state.cells.length} paralelos · ${state.topology}`;
    $('v-max').textContent = st.hi != null ? `${st.hi.toFixed(3)} V` : '—';
    $('v-min').textContent = st.lo != null ? `${st.lo.toFixed(3)} V` : '—';
    $('v-delta').textContent = st.delta != null ? `${(st.delta * 1000).toFixed(0)} mV` : '—';
    $('v-max-at').textContent = st.hiAt;
    $('v-min-at').textContent = st.loAt;
  }

  _paintTemps(state) {
    const groups = this.segTemps.map((temps, i) => ({
      label: `Segmento ${i + 1}`,
      split: state.temps_per_slave || 0,
      values: temps.map((t) => ({
        v: t.temperature, status: t.status,
        label: `Segmento ${t.segment} · Slave ${t.slave} · NTC ${t.index}`,
        // NTCs do not sit one per parallel block (12 per segment against 24
        // blocks), so name the position within the segment rather than invent
        // a block number.
        sub: `Segmento ${t.segment} · NTC ${(t.slave - 1) % (state.slave_count / state.n_segments) * state.temps_per_slave + t.index} do segmento`,
      })),
    }));
    this.tChart.setData(groups);

    const flat = state.thermistors.map((t) => ({ v: t.temperature, at: `S${t.slave}·T${t.index}` }));
    const st = this._stats(flat);
    $('t-sub').textContent = `${state.thermistors.length} NTC · ${state.temps_per_slave} por slave`;
    $('t-max').textContent = st.hi != null ? `${st.hi.toFixed(1)} °C` : '—';
    $('t-min').textContent = st.lo != null ? `${st.lo.toFixed(1)} °C` : '—';
    $('t-delta').textContent = st.delta != null ? `${st.delta.toFixed(1)} °C` : '—';
    $('t-max-at').textContent = st.hiAt;
    $('t-min-at').textContent = st.loAt;
  }

  _paintTables(state) {
    const build = (rows, fmt, cls) => rows.map((items) => {
      const vals = items.map((it) => it.value).filter((v) => v != null);
      const lo = vals.length ? Math.min(...vals) : null;
      const hi = vals.length ? Math.max(...vals) : null;
      return [
        ...items.map((it) => ({ text: it.value == null ? '—' : fmt(it.value), cls: cls(it) })),
        { text: lo == null ? '—' : fmt(lo), cls: 'stat-col' },
        { text: hi == null ? '—' : fmt(hi), cls: 'stat-col' },
        { text: lo == null ? '—' : fmt(hi - lo), cls: 'stat-col' },
      ];
    });

    this.vTable.cols = state.cells_per_segment;
    this.vTable.render(
      build(this.segCells.map((cs) => cs.map((c) => ({ value: c.voltage, status: c.status }))),
        (v) => v.toFixed(3), (it) => (it.status === 'ok' ? '' : it.status)),
    );
    $('tbl-v-sub').textContent = `${state.cells_per_segment} paralelos por segmento · V`;

    this.tTable.cols = (state.temps_per_slave || 0) * (state.slave_count / state.n_segments || 1);
    this.tTable.render(
      build(this.segTemps.map((ts) => ts.map((t) => ({ value: t.temperature, status: t.status }))),
        (v) => v.toFixed(1), (it) => (it.status === 'ok' ? '' : it.status)),
    );
    $('tbl-t-sub').textContent = `${this.tTable.cols} NTC por segmento · °C`;
  }

  /**
   * Drive the markers on the open model. Only things that are *spatial* get a
   * marker: which segment is where, where the hottest one is, and the state of
   * each named part at its real position. Voltages and temperatures per segment
   * stay in the sidebar -- repeating them over the model just hides the model.
   */
  _updateHotspots(state) {
    const v = this.viewer;
    if (!v?.setHotspotState) return;
    const sf = state.safety;

    // hottest segment, so the marker tracks a physical location
    let hottest = null;
    for (const seg of state.segments) {
      if (!hottest || seg.temp_max > hottest.temp_max) hottest = seg;
    }

    for (const seg of state.segments) {
      const isHot = hottest && seg.id === hottest.id;
      v.setHotspotState(`seg-${seg.id}`, {
        label: isHot ? `S${seg.id}  ${seg.temp_max.toFixed(0)}°` : `S${seg.id}`,
        sev: seg.status,
        hot: isHot,
        selected: this.selectedSegment === seg.id,
        onClick: () => this._selectSegment(seg.id, state),
      });
    }

    const flag = (ok) => (ok ? 'ok' : 'fault');
    v.setHotspotState('ams',  { label: sf.ams_ok ? 'AMS' : 'AMS falha', sev: flag(sf.ams_ok) });
    v.setHotspotState('imd',  { label: sf.imd_ok ? 'IMD' : 'IMD falha', sev: flag(sf.imd_ok) });
    v.setHotspotState('fuse', { label: 'Fusível', sev: 'idle' });
    // Each contactor reports separately on CAN (precharge_ctc_air_pos_state,
    // ..._air_min_state, precharge_state), so show them separately.
    v.setHotspotState('air-pos',   { label: sf.air_positive ? 'AIR+ fechado' : 'AIR+ aberto',
                                     sev: sf.air_positive ? 'ok' : 'idle' });
    v.setHotspotState('air-neg',   { label: sf.air_negative ? 'AIR− fechado' : 'AIR− aberto',
                                     sev: sf.air_negative ? 'ok' : 'idle' });
    v.setHotspotState('precharge', { label: sf.precharge_done ? 'Pré-carga ok' : 'Pré-carga',
                                     sev: sf.precharge_done ? 'ok' : 'warn' });
    // The IVT is the current sensor: show what it is measuring.
    v.setHotspotState('ivt', {
      label: `IVT  ${state.pack.current.toFixed(1)} A`,
      sev: state.stale ? 'fault' : 'ok',
    });
    // master_fan_pwm, do mesmo bloco que traz o master_state. Estava a ser
    // descodificado e nunca chegava aqui, e o marcador ficava só com o nome.
    const pwm = state.ams ? state.ams.fan_pwm : null;
    v.setFanSpeed(pwm);
    v.setHotspotState('fans', pwm == null
      ? { label: 'Ventoinhas', sev: 'idle' }
      // Paradas não é falha: abaixo do limite térmico o firmware não as liga.
      : { label: `Ventoinhas  ${pwm.toFixed(0)}%`, sev: pwm > 0 ? 'ok' : 'idle' });
  }

  _selectSegment(id, state) {
    this.selectedSegment = this.selectedSegment === id ? null : id;
    this.segEls.forEach((e, i) =>
      e.card.classList.toggle('sel', state.segments[i].id === this.selectedSegment));
  }
}
