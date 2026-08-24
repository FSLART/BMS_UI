/**
 * Curve rendering for the configuration page.
 *
 * The curves themselves -- which ones exist, their axes, their labels -- are
 * declared in bms-settings.js. This file only knows how to draw one and how
 * the derived curves are computed.
 *
 * The chart is deliberately small and local rather than reusing panels.js:
 * those draw grouped bars over categories, this draws a continuous function
 * over a numeric domain, and forcing one to be the other helps nobody.
 */

/**
 * Cores lidas do CSS no momento em que se pergunta, nao fixadas na carga.
 *
 * Era um objeto de literais. Com dois temas isso deixa de servir: um canvas
 * pinta pixeis e nao herda nada, portanto se as cores forem lidas uma vez ao
 * carregar o modulo, os graficos ficam escuros dentro de uma pagina clara.
 *
 * O Proxy mantem a escrita `CSS.accent` intacta em todos os sitios que ja a
 * usavam -- so o que ela devolve e que passou a depender do tema.
 */
const TOKENS = {
  ink: ['--text', '#EDEDF0'],
  dim: ['--text-dim', '#C4C4CC'],
  mute: ['--text-mute', '#94949E'],
  grid: ['--c-grid', '#212127'],
  panel: ['--c-panel', '#141417'],
  accent: ['--accent', '#FFC300'],
  ok: ['--ok', '#30D158'],
  warn: ['--warn', '#FFD60A'],
  fault: ['--fault', '#FF453A'],
  cool: ['--c-cool', '#9BB4D9'],
};

export const CSS = new Proxy({}, {
  get(_, k) {
    const t = TOKENS[k];
    if (!t) return undefined;
    const v = getComputedStyle(document.documentElement).getPropertyValue(t[0]).trim();
    return v || t[1];
  },
  has(_, k) { return k in TOKENS; },
  ownKeys() { return Reflect.ownKeys(TOKENS); },
  getOwnPropertyDescriptor() { return { enumerable: true, configurable: true }; },
});

export class CurveChart {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {{xMin,xMax,xStep,xLabel,yMin,yMax,yStep,yLabel,yDec?,xDec?}} axes
   */
  constructor(canvas, axes) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.axes = axes;
    this.series = [];
    this.marks = [];
    new ResizeObserver(() => { this._resize(); this.draw(); })
      .observe(canvas.parentElement || canvas);
    this._resize();
  }

  _resize() {
    const box = this.canvas.parentElement || this.canvas;
    const r = box.getBoundingClientRect();
    if (!r.width) return;
    const dpr = window.devicePixelRatio || 1;
    this.w = r.width;
    this.h = r.height;
    this.canvas.width = Math.round(this.w * dpr);
    this.canvas.height = Math.round(this.h * dpr);
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  /** @param {{points:[number,number][], color:string, dash?:number[], dots?:boolean, label?:string}[]} series */
  setSeries(series) { this.series = series; this.draw(); }

  /** Horizontal or vertical reference lines: limits, thresholds. */
  setMarks(marks) { this.marks = marks; this.draw(); }

  draw() {
    const { ctx, axes: a } = this;
    if (!ctx || !this.w) return;
    const PL = 52, PR = 14, PT = 12, PB = 34;
    const iW = this.w - PL - PR;
    const iH = this.h - PT - PB;
    if (iW <= 0 || iH <= 0) return;

    const toX = (v) => PL + ((v - a.xMin) / (a.xMax - a.xMin)) * iW;
    const toY = (v) => PT + iH - ((v - a.yMin) / (a.yMax - a.yMin)) * iH;

    ctx.clearRect(0, 0, this.w, this.h);
    ctx.fillStyle = CSS.panel;
    ctx.fillRect(PL, PT, iW, iH);

    // Grid
    ctx.strokeStyle = CSS.grid;
    ctx.lineWidth = 1;
    for (let v = a.xMin; v <= a.xMax + 1e-6; v += a.xStep) {
      const x = Math.round(toX(v)) + 0.5;
      ctx.beginPath(); ctx.moveTo(x, PT); ctx.lineTo(x, PT + iH); ctx.stroke();
    }
    for (let v = a.yMin; v <= a.yMax + 1e-6; v += a.yStep) {
      const y = Math.round(toY(v)) + 0.5;
      ctx.beginPath(); ctx.moveTo(PL, y); ctx.lineTo(PL + iW, y); ctx.stroke();
    }

    // Tick labels
    ctx.fillStyle = CSS.mute;
    ctx.font = '10px ui-monospace, monospace';
    ctx.textAlign = 'center';
    for (let v = a.xMin; v <= a.xMax + 1e-6; v += a.xStep) {
      ctx.fillText(v.toFixed(a.xDec ?? 0), toX(v), PT + iH + 15);
    }
    ctx.textAlign = 'right';
    for (let v = a.yMin; v <= a.yMax + 1e-6; v += a.yStep) {
      ctx.fillText(v.toFixed(a.yDec ?? 0), PL - 6, toY(v) + 3.5);
    }

    // Axis captions
    ctx.fillStyle = CSS.dim;
    ctx.font = '11px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(a.xLabel, PL + iW / 2, this.h - 3);
    ctx.save();
    ctx.translate(11, PT + iH / 2);
    ctx.rotate(-Math.PI / 2);
    ctx.fillText(a.yLabel, 0, 0);
    ctx.restore();

    ctx.save();
    ctx.beginPath();
    ctx.rect(PL, PT, iW, iH);
    ctx.clip();

    // Reference lines, under the data
    for (const m of this.marks) {
      ctx.strokeStyle = m.color;
      ctx.lineWidth = 1;
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      if (m.y != null) {
        ctx.moveTo(PL, toY(m.y)); ctx.lineTo(PL + iW, toY(m.y));
      } else {
        ctx.moveTo(toX(m.x), PT); ctx.lineTo(toX(m.x), PT + iH);
      }
      ctx.stroke();
      ctx.setLineDash([]);
      if (m.label) {
        ctx.fillStyle = m.color;
        ctx.font = '10px system-ui, sans-serif';
        ctx.textAlign = m.y != null ? 'left' : 'center';
        if (m.y != null) ctx.fillText(m.label, PL + 5, toY(m.y) - 4);
        else ctx.fillText(m.label, toX(m.x), PT + 11);
      }
    }

    for (const s of this.series) {
      const pts = (s.points || []).filter((p) => p[0] != null && p[1] != null);
      // `line: false` desenha so os marcadores. As medidas em bruto sao pontos
      // soltos, e uni-las com uma linha quebrada faria parecer que a linha
      // quebrada e a curva -- quando a curva e o ajuste, desenhado a parte.
      if (pts.length > 1 && s.line !== false) {
        ctx.strokeStyle = s.color;
        ctx.lineWidth = 1.8;
        ctx.setLineDash(s.dash || []);
        ctx.beginPath();
        pts.forEach(([x, y], i) => {
          const px = toX(x), py = toY(y);
          if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
        });
        ctx.stroke();
        ctx.setLineDash([]);
      }
      if (s.dots) {
        ctx.fillStyle = s.color;
        for (const [x, y] of pts) {
          ctx.beginPath();
          ctx.arc(toX(x), toY(y), 3.2, 0, Math.PI * 2);
          ctx.fill();
        }
      }
    }
    ctx.restore();

    // Legend, only when more than one thing is plotted
    const named = this.series.filter((s) => s.label);
    if (named.length > 1) {
      let x = PL + 8;
      const y = PT + 12;
      ctx.font = '10px system-ui, sans-serif';
      ctx.textAlign = 'left';
      for (const s of named) {
        ctx.strokeStyle = s.color;
        ctx.lineWidth = 2;
        ctx.setLineDash(s.dash || []);
        ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + 16, y); ctx.stroke();
        ctx.setLineDash([]);
        ctx.fillStyle = CSS.dim;
        ctx.fillText(s.label, x + 21, y + 3.5);
        x += 26 + ctx.measureText(s.label).width;
      }
    }
  }
}

// ── Ajuste por mínimos quadrados ───────────────────────────────────────────
//
// Os pontos da tabela sao MEDIDAS. A curva que o BMS usa e a funcao ajustada
// a essas medidas, nao a linha quebrada que as une: a linha quebrada tem
// derivada descontinua em cada ponto, e uma estimativa de SOC feita por
// interpolacao nela salta sempre que a tensao atravessa um no.

/**
 * Ajusta um polinomio de grau `degree` aos pontos, por minimos quadrados.
 *
 * O x e normalizado para [-1, 1] antes de montar a matriz. Sem isso, com
 * SOC ate 100 e grau 6, a matriz normal tem entradas na ordem de 10^12 e
 * perde-se a precisao toda -- os coeficientes saem lixo e a curva afasta-se
 * dos pontos que devia atravessar.
 *
 * @param {[number,number][]} pts
 * @param {number} degree
 * @returns {{coef:number[], x0:number, x1:number, degree:number}|null}
 */
export function polyfit(pts, degree) {
  const clean = (pts || []).filter((p) => Number.isFinite(p[0]) && Number.isFinite(p[1]));
  if (clean.length < 2) return null;
  const xs = clean.map((p) => p[0]);
  const x0 = Math.min(...xs);
  const x1 = Math.max(...xs);
  if (x1 === x0) return null;
  // Um polinomio de grau n precisa de n+1 pontos. Com menos, o sistema fica
  // indeterminado e a solucao nao e unica.
  const n = Math.max(1, Math.min(degree, clean.length - 1));

  const u = clean.map((p) => (2 * (p[0] - x0)) / (x1 - x0) - 1);
  const y = clean.map((p) => p[1]);
  const m = n + 1;

  // Equacoes normais: (A^T A) c = A^T y, com A[i][j] = u_i^j.
  const ata = Array.from({ length: m }, () => new Array(m).fill(0));
  const aty = new Array(m).fill(0);
  for (let i = 0; i < u.length; i += 1) {
    const pow = new Array(2 * m - 1);
    pow[0] = 1;
    for (let k = 1; k < pow.length; k += 1) pow[k] = pow[k - 1] * u[i];
    for (let r = 0; r < m; r += 1) {
      for (let c = 0; c < m; c += 1) ata[r][c] += pow[r + c];
      aty[r] += pow[r] * y[i];
    }
  }

  const coef = solve(ata, aty);
  return coef ? { coef, x0, x1, degree: n } : null;
}

/** Eliminacao de Gauss com pivotagem parcial. Devolve null se for singular. */
function solve(a, b) {
  const m = b.length;
  const M = a.map((row, i) => [...row, b[i]]);
  for (let col = 0; col < m; col += 1) {
    let piv = col;
    for (let r = col + 1; r < m; r += 1) {
      if (Math.abs(M[r][col]) > Math.abs(M[piv][col])) piv = r;
    }
    if (Math.abs(M[piv][col]) < 1e-12) return null;
    [M[col], M[piv]] = [M[piv], M[col]];
    for (let r = 0; r < m; r += 1) {
      if (r === col) continue;
      const f = M[r][col] / M[col][col];
      for (let c = col; c <= m; c += 1) M[r][c] -= f * M[col][c];
    }
  }
  // Eliminou-se acima e abaixo do pivo, portanto a matriz ficou diagonal:
  // cada incognita e o termo independente a dividir pelo seu proprio pivo.
  const x = M.map((row, i) => row[m] / row[i]);
  return x.every(Number.isFinite) ? x : null;
}

/** Valor da funcao ajustada em x, por Horner sobre o x normalizado. */
export function polyval(fit, x) {
  if (!fit) return null;
  const u = (2 * (x - fit.x0)) / (fit.x1 - fit.x0) - 1;
  let out = 0;
  for (let k = fit.coef.length - 1; k >= 0; k -= 1) out = out * u + fit.coef[k];
  return out;
}

/**
 * Quao bem a funcao descreve as medidas, e se serve para o que vai ser usada.
 *
 * `monotonic` nao e cosmetica: a estimativa de SOC inverte esta curva, e uma
 * funcao que sobe e desce faz a mesma tensao corresponder a dois estados de
 * carga. O grau alto e onde isto acontece -- o polinomio passa mais perto dos
 * pontos e ondula entre eles.
 */
export function fitQuality(pts, fit) {
  if (!fit) return null;
  const clean = (pts || []).filter((p) => Number.isFinite(p[0]) && Number.isFinite(p[1]));
  let sum = 0;
  let max = 0;
  for (const [x, y] of clean) {
    const e = Math.abs(polyval(fit, x) - y);
    sum += e * e;
    if (e > max) max = e;
  }
  const rms = clean.length ? Math.sqrt(sum / clean.length) : 0;

  let rising = 0;
  let falling = 0;
  let prev = polyval(fit, fit.x0);
  const steps = 200;
  for (let i = 1; i <= steps; i += 1) {
    const v = polyval(fit, fit.x0 + ((fit.x1 - fit.x0) * i) / steps);
    if (v > prev) rising += 1; else if (v < prev) falling += 1;
    prev = v;
  }
  return { rms, max, monotonic: rising === 0 || falling === 0 };
}

/** Pontos da funcao ajustada, prontos para desenhar. */
export function fitPoints(fit, steps = 200) {
  if (!fit) return [];
  const out = [];
  for (let i = 0; i <= steps; i += 1) {
    const x = fit.x0 + ((fit.x1 - fit.x0) * i) / steps;
    out.push([x, polyval(fit, x)]);
  }
  return out;
}

// ── Matemática das curvas derivadas ────────────────────────────────────────

export function fmt(v, d) { return v == null ? '—' : Number(v).toFixed(d); }

/** Linear between the two configured points, clamped at both ends. */
export function fanPwm(v, t) {
  const t0 = v.fan_temp_start, t1 = v.fan_temp_full;
  const p0 = v.fan_pwm_min, p1 = v.fan_pwm_max;
  if (t < t0) return 0;
  if (t1 <= t0) return p1;
  if (t >= t1) return p1;
  return p0 + ((t - t0) / (t1 - t0)) * (p1 - p0);
}

export function dclTemp(v, t) {
  const max = v.dcl_max_a, from = v.dcl_derate_temp_hi;
  if (t <= from) return max;
  // 4 % do limite por grau acima do ponto de partida, com um piso de 10 %.
  return Math.max(max * 0.1, max - (t - from) * max * 0.04);
}

export function dclSoc(v, s) {
  const max = v.dcl_max_a, from = v.dcl_derate_soc_lo;
  if (s >= from) return max;
  return Math.max(max * 0.1, max - (from - s) * max * 0.06);
}
