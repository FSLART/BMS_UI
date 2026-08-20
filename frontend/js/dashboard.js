import { Sparkline } from './chart.js';
import { GroupedBarChart, ValueTable } from './panels.js';

const $ = (id) => document.getElementById(id);

const SAFETY_LABELS = [
  ['ams_ok', 'AMS'],
  ['imd_ok', 'IMD'],
  ['bspd_ok', 'BSPD'],
  ['sdc_closed', 'SDC'],
  ['air_positive', 'AIR+'],
  ['air_negative', 'AIR−'],
];

/**
 * Cell voltage -> colour. Healthy cells ride a green ramp that brightens with
 * voltage; warn and fault break out into amber and red. The three states match
 * the legend under the grid and the --ok/--warn/--fault tokens in the CSS.
 */
export function cellColor(v, sev) {
  if (sev === 'fault') return '#8E2020';
  if (sev === 'warn') return '#8A5310';
  const t = Math.max(0, Math.min(1, (v - 3.0) / (4.15 - 3.0)));
  const stops = [
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
  constructor({ viewer, onDisconnect }) {
    this.viewer = viewer;
    this.built = false;
    this.selectedSegment = null;
    this.cellEls = [];
    this.segEls = [];
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


    this.vTable = new ValueTable($('table-voltage'), {
      cols: 0, rowLabel: (r) => `Segmento ${r + 1}`, extra: ['mín', 'máx', 'Δ'],
    });
    this.tTable = new ValueTable($('table-temps'), {
      cols: 0, rowLabel: (r) => `Segmento ${r + 1}`, extra: ['mín', 'máx', 'Δ'],
    });
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
          <span class="ro" title="Grupo mais baixo"><b>↓</b><span data-f="vmin"></span></span>
          <span class="ro" title="Grupo mais alto"><b>↑</b><span data-f="vmax"></span></span>
        </div>`;
      card.addEventListener('click', () => {
        this.selectedSegment = this.selectedSegment === seg.id ? null : seg.id;
        this.segEls.forEach((e, i) =>
          e.card.classList.toggle('sel', state.segments[i].id === this.selectedSegment));
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
    for (const [key, label] of SAFETY_LABELS) {
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
      topo.title = `${state.cells.length} grupos série × ${p}p = ${state.cells.length * p} células`;
    }

    this.built = true;
  }

  update(state) {
    if (!state.segments.length) return;
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

    $('s-iso').textContent = state.safety.insulation_resistance
      ? `${(state.safety.insulation_resistance / 1000).toFixed(0)} kΩ` : '—';
    $('s-soh').textContent = `${p.soh.toFixed(1)} %`;

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
      el.style.background = cellColor(c.voltage, c.status);
      el.dataset.sev = c.status;
      el.classList.toggle('balancing', c.balancing);
      const dim = this.selectedSegment && c.segment !== this.selectedSegment;
      el.style.opacity = dim ? '0.28' : '1';
      el.title = `${c.id}  ${c.voltage.toFixed(3)} V  ${c.temperature?.toFixed(1)} °C`;
    });

    const faults = $('fault-list');
    if (!state.faults.length) {
      faults.innerHTML = '<div class="empty-note">Sem faltas registadas.</div>';
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
    this._updatePages(state);
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
    $('v-sub').textContent = `${state.cells.length} grupos · ${state.topology}`;
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
    $('tbl-v-sub').textContent = `${state.cells_per_segment} grupos por segmento · V`;

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
    v.setHotspotState('fans', { label: 'Ventoinhas', sev: 'idle' });
  }

  _selectSegment(id, state) {
    this.selectedSegment = this.selectedSegment === id ? null : id;
    this.segEls.forEach((e, i) =>
      e.card.classList.toggle('sel', state.segments[i].id === this.selectedSegment));
  }
}
