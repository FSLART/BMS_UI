/**
 * BMS configuration page — rendering and validation only.
 *
 * Every value, label, unit, range and note lives in bms-settings.js. Nothing
 * user-facing is written here, so adding a parameter never means touching this
 * file.
 *
 * Kick-off, frontend only: there is no configuration message in the CAN
 * database yet, so nothing is transmitted. What it does do is drive everything
 * the interface decides for itself — cell colours, chart thresholds, warning
 * levels — which used to be hardcoded in three different files.
 */

import { CSS, CurveChart, fitPoints, fitQuality, polyfit } from './curves.js';
import {
  CURVES, LOCKED_MASTER, LOCKED_PRECHARGE, SECTIONS, TEXT, defaultCurvePoints,
} from './bms-settings.js';

const $ = (id) => document.getElementById(id);

export class ConfigPage {
  constructor(root, { onChange } = {}) {
    this.root = root;
    this.onChange = onChange;
    this.values = {};
    this.charts = {};
    this.car = null;
    this.locked = false;
    this.els = {};
  }

  /** Seed from the car profile. Called once per car. */
  setCar(car) {
    if (!car || this.car === car) return;
    this.car = car;
    this.values = {};
    for (const section of SECTIONS) {
      for (const f of section.fields) {
        let v = f.def;
        if (f.from) {
          try { v = f.from(car); } catch { v = f.def; }
        }
        this.values[f.k] = v == null ? (f.def ?? 0) : v;
      }
    }
    // Curve points are not scalars, so they come from their own defaults.
    Object.assign(this.values, defaultCurvePoints(car));
    // Fit settings live alongside the points they describe.
    for (const c of CURVES) {
      if (c.fit) this.values[c.fit.degreeKey] = c.fit.degreeDef;
    }
    this._paintText();
    this._build();
    this._buildCurves();
    this.onChange?.(this.values);
  }

  /**
   * Everything the interface decides for itself, in one place. The dashboard
   * asks for this instead of carrying its own copies of 4.15 and 2.8.
   */
  limits() {
    const v = this.values;
    return {
      v_min: v.v_min, v_max: v.v_max,
      v_warn_low: v.v_warn_low, v_warn_high: v.v_warn_high,
      v_open_wire: v.v_open_wire,
      temp_warn: v.temp_warn, temp_fault: v.temp_fault,
    };
  }

  /**
   * Lock while the pack is energised or moving.
   *
   * HV_ON is the one that matters — contactors closed — but ONMISSION and
   * CHARGING are the states where changing a limit mid-way is worst, so they
   * lock too.
   */
  setLive(state) {
    const sf = state.safety || {};
    const why = sf.precharge_state === LOCKED_PRECHARGE ? TEXT.lockPrecharge
      : LOCKED_MASTER.includes(sf.master_state) ? TEXT.lockMaster(sf.master_state)
      : '';
    const locked = !!why;
    if (locked === this.locked && why === this._why) return;
    this.locked = locked;
    this._why = why;
    this._applyLock();
  }

  /** Fixed page text, straight from TEXT. Written once. */
  _paintText() {
    const intro = $('cfg-intro');
    if (intro && !intro.childElementCount) {
      intro.innerHTML = TEXT.intro.map((p, i) =>
        `<p${i ? ' class="muted"' : ''}>${p}</p>`).join('');
    }
    const lock = $('cfg-lock');
    if (lock) {
      lock.querySelector('[data-role="title"]').textContent = TEXT.lockTitle;
      lock.querySelector('[data-role="hint"]').textContent = TEXT.lockHint;
    }
    const warn = $('cfg-warn');
    if (warn) {
      warn.querySelector('[data-role="title"]').textContent = TEXT.warnTitle;
      warn.querySelector('[data-role="body"]').textContent = TEXT.warnBody;
    }
  }

  _applyLock() {
    const banner = $('cfg-lock');
    if (banner) {
      banner.hidden = !this.locked;
      const text = banner.querySelector('[data-role="why"]');
      if (text) text.textContent = this._why || '';
    }
    for (const el of Object.values(this.els)) el.disabled = this.locked;
    for (const el of document.querySelectorAll('.curve-table input')) {
      el.disabled = this.locked || el.dataset.future === '1';
    }
    this.root.classList.toggle('is-locked', this.locked);
  }

  _build() {
    this.root.innerHTML = '';
    this.els = {};
    for (const section of SECTIONS) {
      const box = document.createElement('section');
      box.className = `cfg-card${section.future ? ' is-future' : ''}`;
      const head = document.createElement('div');
      head.className = 'panel-head compact';
      head.innerHTML = `<h3>${section.label}</h3>`;
      box.appendChild(head);
      if (section.note) {
        const note = document.createElement('p');
        note.className = 'cfg-note';
        note.textContent = section.note;
        box.appendChild(note);
      }

      const grid = document.createElement('div');
      grid.className = 'cfg-grid';
      for (const f of section.fields) {
        const row = document.createElement('label');
        row.className = 'cfg-field';
        row.innerHTML = `<span class="cfg-k">${f.label}</span>`;
        const wrap = document.createElement('span');
        wrap.className = 'cfg-input';
        const input = document.createElement('input');
        input.type = 'number';
        input.min = f.min; input.max = f.max; input.step = f.step;
        input.value = Number(this.values[f.k] ?? 0).toFixed(f.dec);
        input.disabled = this.locked || !!section.future;
        input.addEventListener('change', () => this._set(f, input));
        wrap.append(input);
        const unit = document.createElement('span');
        unit.className = 'cfg-unit';
        unit.textContent = f.unit;
        wrap.append(unit);
        row.append(wrap);
        if (f.help) {
          const help = document.createElement('span');
          help.className = 'cfg-help';
          help.textContent = f.help;
          row.append(help);
        }
        grid.append(row);
        if (!section.future) this.els[f.k] = input;
      }
      box.append(grid);
      this.root.append(box);
    }
    this._applyLock();
  }

  /**
   * The curve editors.
   *
   * Built once per car, next to the scalar sections. Point tables write into
   * the same `values` object, so a curve and the numbers that bound it stay in
   * one place -- the OCV chart draws the cell limits from the section above it.
   */
  _buildCurves() {
    const host = $('cfg-curves');
    if (!host) return;
    host.innerHTML = '';
    this.charts = {};
    const title = document.createElement('h3');
    title.className = 'cfg-h';
    title.textContent = TEXT.curvesTitle;
    host.append(title);

    for (const c of CURVES) {
      const card = document.createElement('section');
      card.className = `cfg-card curve-card${c.future ? ' is-future' : ''}`;
      card.innerHTML = `<div class="panel-head compact"><h3>${c.label}</h3></div>` +
        (c.note ? `<p class="cfg-note">${c.note}</p>` : '');

      const chartBox = document.createElement('div');
      chartBox.className = 'curve-canvas';
      const canvas = document.createElement('canvas');
      chartBox.append(canvas);
      card.append(chartBox);

      if (c.fit) card.append(this._fitControls(c));
      if (c.editable) card.append(this._curveTable(c));
      host.append(card);
      // After it is in the DOM, so the first measure has a box to read.
      this.charts[c.id] = new CurveChart(canvas, c.axes);
    }
    this._drawCurves();
  }

  /**
   * Grau do polinomio e a qualidade do ajuste.
   *
   * O grau esta a vista porque e a unica escolha que muda o desenho, e nao ha
   * um valor certo: grau a mais passa mais perto das medidas e ondula entre
   * elas, o que numa curva OCV faz a mesma tensao valer dois SOC.
   */
  _fitControls(c) {
    const f = c.fit;
    const wrap = document.createElement('div');
    wrap.className = 'fit-bar';

    const l = document.createElement('label');
    l.className = 'fit-field';
    l.innerHTML = '<span>Grau da equação de ajuste</span>';
    const i = document.createElement('input');
    i.type = 'number';
    i.min = f.degreeMin; i.max = f.degreeMax; i.step = 1;
    i.value = this.values[f.degreeKey];
    i.disabled = this.locked;
    i.addEventListener('change', () => {
      let v = parseInt(i.value, 10);
      if (Number.isNaN(v)) v = this.values[f.degreeKey];
      v = Math.min(f.degreeMax, Math.max(f.degreeMin, v));
      this.values[f.degreeKey] = v;
      i.value = v;
      this._drawCurves();
      this.onChange?.(this.values);
    });
    l.append(i);
    this.els[f.degreeKey] = i;
    wrap.append(l);

    const q = document.createElement('span');
    q.className = 'fit-quality';
    q.dataset.role = `quality-${c.id}`;
    wrap.append(q);
    return wrap;
  }

  /** Ajusta o polinomio as medidas da tabela. */
  _fitRows(c) {
    const f = c.fit;
    const src = (this.values[f.source] || []).map((p) => {
      const k = Object.keys(p);
      return [p[k[0]], p[k[1]]];
    });
    const fit = polyfit(src, this.values[f.degreeKey]);
    return { fit, quality: fit ? fitQuality(src, fit) : null, measured: src };
  }

  _paintFit(c, { quality }) {
    const q = document.querySelector(`[data-role="quality-${c.id}"]`);
    if (q) {
      if (!quality || !Number.isFinite(quality.rms)) {
        // Nao mostrar "0.0 mV" quando o ajuste falhou: um erro de zero le-se
        // como ajuste perfeito, que e o oposto do que aconteceu.
        q.textContent = 'Não foi possível ajustar com estes pontos.';
        q.className = 'fit-quality bad';
      } else {
        const rms = (quality.rms * 1000).toFixed(1);
        const max = (quality.max * 1000).toFixed(1);
        q.textContent = quality.monotonic
          ? `Erro ${rms} mV rms · ${max} mV máx`
          : `Erro ${rms} mV rms · ${max} mV máx — curva não monótona, a mesma tensão dá dois SOC`;
        q.className = `fit-quality${quality.monotonic ? '' : ' bad'}`;
      }
    }
  }

  /** Editable point table for a curve that IS its points. */
  _curveTable(c) {
    const { key, cols, dec } = c.editable;
    const wrap = document.createElement('div');
    wrap.className = 'curve-table';
    const table = document.createElement('table');
    table.className = 'vtable';
    table.innerHTML = `<thead><tr>${cols.map((h) => `<th>${h}</th>`).join('')}</tr></thead>`;
    const body = document.createElement('tbody');

    const rows = this.values[key] || [];
    const fields = Object.keys(rows[0] || {});
    rows.forEach((row, i) => {
      const tr = document.createElement('tr');
      fields.forEach((f, ci) => {
        const td = document.createElement('td');
        const input = document.createElement('input');
        input.type = 'number';
        input.step = dec[ci] ? Math.pow(10, -dec[ci]) : 1;
        input.value = Number(row[f]).toFixed(dec[ci] ?? 0);
        input.disabled = this.locked || !!c.future;
        if (c.future) input.dataset.future = '1';
        input.addEventListener('change', () => {
          const v = parseFloat(input.value);
          if (!Number.isNaN(v)) this.values[key][i][f] = v;
          input.value = Number(this.values[key][i][f]).toFixed(dec[ci] ?? 0);
          this._drawCurves();
          this.onChange?.(this.values);
        });
        td.append(input);
        tr.append(td);
      });
      body.append(tr);
    });
    table.append(body);
    wrap.append(table);
    return wrap;
  }

  /**
   * Re-measure and redraw. Canvases built while the page is hidden measure
   * zero and stick at the 300x150 default, so the page has to say when it
   * became visible.
   */
  refresh() {
    for (const chart of Object.values(this.charts)) chart._resize();
    this._drawCurves();
  }

  _drawCurves() {
    for (const c of CURVES) {
      const chart = this.charts[c.id];
      if (!chart) continue;
      chart.setMarks(c.marks ? c.marks(this.values) : []);
      chart.setSeries(c.fit ? this._fitSeries(c) : c.series(this.values));
    }
  }

  /**
   * Os pontos escritos na tabela e a curva ajustada a eles.
   *
   * Os pontos ficam sem linha a uni-los de proposito: a linha e o ajuste, e
   * ter as duas faria parecer que sao a mesma coisa. Onde um ponto se afasta
   * da curva, ve-se logo qual.
   */
  _fitSeries(c) {
    const got = this._fitRows(c);
    this._paintFit(c, got);
    const { fit, measured } = got;
    const pontos = { points: measured || [], color: CSS.cool, dots: true, line: false };
    if (!fit) return [pontos];
    return [{ points: fitPoints(fit), color: CSS.accent }, pontos];
  }

  _set(f, input) {
    let v = parseFloat(input.value);
    if (Number.isNaN(v)) v = this.values[f.k];
    // Clamp rather than reject: a value outside the range is a slip, and
    // silently keeping the old one looks like the field is broken.
    v = Math.min(f.max, Math.max(f.min, v));
    this.values[f.k] = v;
    input.value = v.toFixed(f.dec);
    this._validate();
    this._drawCurves();
    this.onChange?.(this.values);
  }

  /**
   * Orderings that must hold. Not a schema check -- these are the pairs where
   * an inversion silently turns a limit into nonsense, like a low warning above
   * the high warning, which would make every cell warn at once.
   */
  _validate() {
    const v = this.values;
    const pairs = [
      ['v_min', 'v_warn_low'], ['v_warn_low', 'v_warn_high'], ['v_warn_high', 'v_max'],
      ['v_open_wire', 'v_min'], ['temp_warn', 'temp_fault'],
      ['bal_v_min', 'bal_v_max'], ['fan_temp_start', 'fan_temp_full'],
      ['fan_pwm_min', 'fan_pwm_max'],
    ];
    const bad = new Set();
    for (const [lo, hi] of pairs) {
      if (v[lo] != null && v[hi] != null && v[lo] > v[hi]) { bad.add(lo); bad.add(hi); }
    }
    for (const [k, el] of Object.entries(this.els)) {
      el.classList.toggle('invalid', bad.has(k));
    }
    const warn = $('cfg-warn');
    if (warn) warn.hidden = bad.size === 0;
  }
}
