/**
 * Dashboard panels: grouped bar charts, dial gauges and data tables.
 *
 * Canvas for the charts: 144 bars redrawn at 10 Hz is thousands of DOM writes
 * per second as SVG, and none of it needs to be in the document. Tables stay as
 * DOM because they are text you want to select and search.
 */

const FONT = '11px "JetBrains Mono", ui-monospace, monospace';

function css(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}

/**
 * Cores lidas do CSS a cada uso, para os dois temas.
 *
 * Eram literais. Um canvas nao herda cor nenhuma, portanto valores fixos aqui
 * davam graficos escuros dentro de uma pagina clara. O Proxy deixa `COL.fault`
 * escrito como estava em todo o ficheiro.
 */
const TOKENS = {
  ok: ['--ok', '#32D74B'],
  warn: ['--warn', '#FFD60A'],
  fault: ['--fault', '#FF453A'],
  max: ['--fault', '#FF453A'],
  min: ['--c-blue', '#0A84FF'],
  // Roxo e nao vermelho: o vermelho ja e a celula mais alta.
  ow: ['--c-purple', '#BF5AF2'],
  empty: ['--c-empty', 'rgba(120,120,128,0.16)'],
  grid: ['--c-gridline', 'rgba(255,255,255,0.07)'],
  axis: ['--c-axis', 'rgba(255,255,255,0.16)'],
  text: ['--text-mute', '#94949E'],
  textBright: ['--text-dim', '#C4C4CC'],
};

const COL = new Proxy({}, {
  get: (_, k) => (TOKENS[k] ? css(TOKENS[k][0], TOKENS[k][1]) : undefined),
});

/**
 * Bars grouped by SEGMENT -- the unit the pack is actually built and serviced
 * in. A faint divider inside each group marks where one AMS slave hands over
 * to the next, so the wiring is still visible without splitting the layout.
 */
export class GroupedBarChart {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {{min:number,max:number,warnLow?:number,warnHigh?:number,unit:string,decimals:number}} scale
   */
  constructor(canvas, scale) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.scale = scale;
    this.groups = [];        // [{label, values:[{v, status, label}], split}]
    this.hover = null;
    this.w = 0;
    this.h = 0;

    new ResizeObserver(() => this._resize()).observe(canvas.parentElement || canvas);
    canvas.addEventListener('mousemove', (e) => this._onMove(e));
    canvas.addEventListener('mouseleave', () => { this.hover = null; this.draw(); });
    this._resize();
  }

  _resize() {
    const dpr = window.devicePixelRatio || 1;
    const r = this.canvas.getBoundingClientRect();
    const w = Math.round(r.width);
    const h = Math.round(r.height);
    if (!w || !h || (w === this.w && h === this.h)) return;
    this.w = w; this.h = h;
    this.canvas.width = w * dpr;
    this.canvas.height = h * dpr;
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.draw();
  }

  setData(groups) {
    this.groups = groups;
    this.draw();
  }

  _geom() {
    const M = { top: 14, right: 52, bottom: 24, left: 46 };
    const cw = Math.max(1, this.w - M.left - M.right);
    const ch = Math.max(1, this.h - M.top - M.bottom);
    const nGroups = this.groups.length || 1;
    const perGroup = this.groups[0]?.values.length || 1;
    const total = nGroups * perGroup;

    const segGap = 18;
    const barGap = 1;
    const gaps = (nGroups - 1) * segGap + nGroups * (perGroup - 1) * barGap;
    const bw = Math.max(1, (cw - gaps) / total);

    const x = (g, i) =>
      M.left + g * segGap + (g * perGroup + i) * (bw + barGap);

    return { M, cw, ch, nGroups, perGroup, bw, x, segGap, barGap };
  }

  draw() {
    const { ctx, w, h } = this;
    if (!w || !h) return;
    ctx.clearRect(0, 0, w, h);
    if (!this.groups.length) return;

    const { M, cw, ch, nGroups, perGroup, bw, x, segGap, barGap } = this._geom();
    const { min, max } = this.scale;
    const span = Math.max(1e-6, max - min);
    const yOf = (v) => M.top + ch * (1 - Math.min(1, Math.max(0, (v - min) / span)));

    // grid + axis labels
    ctx.font = FONT;
    ctx.textBaseline = 'middle';
    ctx.textAlign = 'right';
    const ticks = 5;
    for (let i = 0; i <= ticks; i++) {
      const v = min + (span * i) / ticks;
      const y = Math.round(yOf(v)) + 0.5;
      ctx.strokeStyle = COL.grid;
      ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(M.left, y); ctx.lineTo(M.left + cw, y); ctx.stroke();
      ctx.fillStyle = COL.text;
      ctx.fillText(v.toFixed(this.scale.decimals ?? 1), M.left - 8, y);
    }

    // threshold lines
    for (const [key, colour, label] of [
      ['warnLow', COL.warn, 'UV'],
      ['warnHigh', COL.warn, 'OV'],
      ['warnAt', COL.warn, 'OT'],
    ]) {
      const v = this.scale[key];
      if (v == null || v < min || v > max) continue;
      const y = Math.round(yOf(v)) + 0.5;
      ctx.save();
      ctx.strokeStyle = colour;
      ctx.globalAlpha = 0.5;
      ctx.setLineDash([4, 3]);
      // Stop short of the plot edge: running the dash all the way out leaves it
      // touching both the last bar and the label.
      ctx.beginPath(); ctx.moveTo(M.left, y); ctx.lineTo(M.left + cw - 6, y); ctx.stroke();
      ctx.restore();
      ctx.fillStyle = colour;
      ctx.textAlign = 'left';
      ctx.fillText(label, M.left + cw + 16, y);
      ctx.textAlign = 'right';
    }

    // find global min/max so the extremes can be marked
    let lo = Infinity, hi = -Infinity, loRef = null, hiRef = null;
    this.groups.forEach((g, gi) => g.values.forEach((cell, i) => {
      if (cell.v == null || cell.ow) return;
      if (cell.v < lo) { lo = cell.v; loRef = [gi, i]; }
      if (cell.v > hi) { hi = cell.v; hiRef = [gi, i]; }
    }));

    // bars
    this.groups.forEach((g, gi) => {
      g.values.forEach((cell, i) => {
        const bx = x(gi, i);
        if (cell.v == null) {
          ctx.fillStyle = COL.empty;
          ctx.fillRect(bx, M.top, bw, ch);
          return;
        }
        const y = yOf(cell.v);
        // A descarregar: a coluna por cima da barra fica amarela, como na
        // ferramenta de bancada -- ve-se o padrao do balanceamento de relance.
        if (cell.bal) {
          ctx.save();
          ctx.globalAlpha = 0.85;
          ctx.fillStyle = COL.warn;
          ctx.fillRect(bx, M.top, bw, y - M.top);
          ctx.restore();
        }
        const isMax = hiRef && hiRef[0] === gi && hiRef[1] === i;
        const isMin = loRef && loRef[0] === gi && loRef[1] === i;
        ctx.fillStyle = cell.ow ? COL.ow : isMax ? COL.max : isMin ? COL.min
          : cell.status === 'fault' ? COL.fault
          : cell.status === 'warn' ? COL.warn : COL.ok;
        ctx.fillRect(bx, y, bw, M.top + ch - y);

        if (isMax || isMin) {
          ctx.fillStyle = ctx.fillStyle;
          ctx.textAlign = 'center';
          ctx.fillText(isMax ? '▲' : '▼', bx + bw / 2, y - 7);
          ctx.textAlign = 'right';
        }
      });
    });

    // baseline
    ctx.strokeStyle = COL.axis;
    ctx.beginPath();
    ctx.moveTo(M.left, M.top + ch + 0.5);
    ctx.lineTo(M.left + cw, M.top + ch + 0.5);
    ctx.stroke();

    // segment separators, labels, and the slave hand-over inside each segment
    ctx.textAlign = 'center';
    this.groups.forEach((g, gi) => {
      const x0 = x(gi, 0);
      const x1 = x(gi, perGroup - 1) + bw;

      if (gi > 0) {
        const sx = Math.round(x0 - segGap / 2) + 0.5;
        ctx.strokeStyle = COL.axis;
        ctx.beginPath(); ctx.moveTo(sx, M.top); ctx.lineTo(sx, M.top + ch); ctx.stroke();
      }

      if (g.split && g.split < perGroup) {
        const sx = Math.round(x(gi, g.split) - barGap / 2) + 0.5;
        ctx.save();
        ctx.strokeStyle = COL.axis;
        ctx.globalAlpha = 0.5;
        ctx.setLineDash([3, 4]);
        ctx.beginPath(); ctx.moveTo(sx, M.top); ctx.lineTo(sx, M.top + ch); ctx.stroke();
        ctx.restore();
      }

      ctx.fillStyle = COL.textBright;
      ctx.fillText(g.label, (x0 + x1) / 2, M.top + ch + 14);
    });

    if (this.hover) this._drawTooltip();
  }

  _onMove(ev) {
    if (!this.groups.length) return;
    const r = this.canvas.getBoundingClientRect();
    const mx = ev.clientX - r.left;
    const my = ev.clientY - r.top;
    const { bw, x, perGroup } = this._geom();
    let found = null;
    for (let g = 0; g < this.groups.length && !found; g++) {
      for (let i = 0; i < perGroup; i++) {
        const bx = x(g, i);
        if (mx >= bx && mx <= bx + bw) { found = { g, i, mx, my }; break; }
      }
    }
    this.hover = found;
    this.draw();
  }

  _drawTooltip() {
    const { ctx } = this;
    const cell = this.groups[this.hover.g]?.values[this.hover.i];
    if (!cell) return;

    // Two ways of naming the same bar: how the AMS addresses it, and where it
    // physically sits in the pack. Neither alone is enough to find it.
    const lines = [
      cell.label ?? `${this.groups[this.hover.g].label} · ${this.hover.i + 1}`,
      cell.sub ?? '',
      cell.v == null ? '—' : `${cell.v.toFixed(this.scale.decimals ?? 2)} ${this.scale.unit}`,
      cell.bal ? 'a descarregar (balanceamento)' : '',
      cell.ow ? 'openwire' : '',
    ].filter(Boolean);

    ctx.font = FONT;
    const wBox = Math.max(...lines.map((l) => ctx.measureText(l).width)) + 18;
    const hBox = 12 + lines.length * 14;
    let bx = this.hover.mx + 12;
    let by = this.hover.my - hBox - 6;
    if (bx + wBox > this.w) bx = this.hover.mx - wBox - 12;
    if (by < 0) by = this.hover.my + 12;

    ctx.fillStyle = 'rgba(28,28,32,0.96)';
    ctx.beginPath();
    ctx.roundRect(bx, by, wBox, hBox, 6);
    ctx.fill();
    ctx.textAlign = 'left';
    lines.forEach((line, i) => {
      ctx.fillStyle = i === 2 ? COL.textBright : COL.text;
      ctx.fillText(line, bx + 9, by + 12 + i * 14);
    });
  }
}

/** Compact dial. One number, its range, and a needle. */
export class Gauge {
  constructor(canvas, { min, max, unit, decimals = 0, label = '', warnAt = null }) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    Object.assign(this, { min, max, unit, decimals, label, warnAt });
    this.value = null;
    new ResizeObserver(() => this._resize()).observe(canvas.parentElement || canvas);
    this._resize();
  }

  _resize() {
    const dpr = window.devicePixelRatio || 1;
    const r = this.canvas.getBoundingClientRect();
    const w = Math.round(r.width), h = Math.round(r.height);
    if (!w || !h || (w === this.w && h === this.h)) return;
    this.w = w; this.h = h;
    this.canvas.width = w * dpr;
    this.canvas.height = h * dpr;
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.draw();
  }

  set(value) { this.value = value; this.draw(); }

  draw() {
    const { ctx, w, h } = this;
    if (!w || !h) return;
    ctx.clearRect(0, 0, w, h);

    const cx = w / 2;
    const cy = h * 0.62;
    const r = Math.min(cx - 8, cy - 8, h * 0.44);
    const START = Math.PI * 0.75;
    const SWEEP = Math.PI * 1.5;
    const track = Math.max(5, r * 0.16);

    ctx.lineCap = 'butt';
    ctx.lineWidth = track;
    ctx.strokeStyle = 'rgba(255,255,255,0.07)';
    ctx.beginPath();
    ctx.arc(cx, cy, r, START, START + SWEEP);
    ctx.stroke();

    const span = Math.max(1e-6, this.max - this.min);
    const t = this.value == null ? null
      : Math.min(1, Math.max(0, (this.value - this.min) / span));

    if (this.warnAt != null) {
      const wt = Math.min(1, Math.max(0, (this.warnAt - this.min) / span));
      ctx.strokeStyle = 'rgba(255,69,58,0.28)';
      ctx.beginPath();
      ctx.arc(cx, cy, r, START + wt * SWEEP, START + SWEEP);
      ctx.stroke();
    }

    const accent = css('--accent', '#FFC300');
    const over = this.warnAt != null && this.value != null && this.value >= this.warnAt;
    const colour = over ? COL.fault : accent;

    if (t != null) {
      ctx.strokeStyle = colour;
      ctx.beginPath();
      ctx.arc(cx, cy, r, START, START + t * SWEEP);
      ctx.stroke();
    }

    ctx.textAlign = 'center';
    ctx.textBaseline = 'alphabetic';
    ctx.fillStyle = t == null ? COL.text : (over ? COL.fault : css('--text', '#F5F5F7'));
    const size = Math.max(15, r * 0.42);
    ctx.font = `600 ${size}px Poppins, system-ui, sans-serif`;
    ctx.fillText(this.value == null ? '—' : this.value.toFixed(this.decimals), cx, cy + size * 0.32);

    ctx.font = FONT;
    ctx.fillStyle = COL.text;
    ctx.fillText(this.unit, cx, cy + size * 0.32 + 15);
    if (this.label) ctx.fillText(this.label, cx, cy - r - 2);
  }
}

/** Table of values, one row per slave. Rebuilt only when the shape changes. */
export class ValueTable {
  constructor(root, { cols, rowLabel, extra = [] }) {
    this.root = root;
    this.cols = cols;
    this.rowLabel = rowLabel;
    this.extra = extra;
    this.sig = '';
  }

  render(rows, colour) {
    const sig = `${rows.length}x${this.cols}`;
    if (sig !== this.sig) {
      this.sig = sig;
      const head = ['', ...Array.from({ length: this.cols }, (_, i) => String(i + 1)), ...this.extra];
      this.root.innerHTML =
        `<table class="vtable"><thead><tr>${head.map((h, i) =>
          `<th${i === 0 ? ' class="rowhead"' : ''}>${h}</th>`).join('')}</tr></thead><tbody>${
          rows.map((_, r) => `<tr><th class="rowhead">${this.rowLabel(r)}</th>${
            Array.from({ length: this.cols + this.extra.length },
              () => '<td></td>').join('')}</tr>`).join('')
        }</tbody></table>`;
      this.cells = [...this.root.querySelectorAll('tbody tr')].map((tr) => [...tr.querySelectorAll('td')]);
    }

    rows.forEach((row, r) => {
      const tds = this.cells[r];
      if (!tds) return;
      row.forEach((cell, c) => {
        const td = tds[c];
        if (!td) return;
        td.textContent = cell.text;
        td.style.background = cell.bg ?? '';
        td.className = cell.cls ?? '';
      });
    });
  }
}
