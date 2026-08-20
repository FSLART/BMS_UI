/** Minimal ring-buffer sparkline. No dependency, no build step. */
export class Sparkline {
  constructor(canvas, series, capacity = 600) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.series = series;                 // [{key, color, label, unit}]
    this.capacity = capacity;
    this.data = series.map(() => []);
    this.w = 0;
    this.h = 0;
    this._resize();
    // Observe the wrapper, not the canvas: the canvas is absolutely positioned
    // and its buffer size must never take part in layout.
    new ResizeObserver(() => this._resize()).observe(canvas.parentElement || canvas);
  }

  _resize() {
    const dpr = window.devicePixelRatio || 1;
    const r = this.canvas.getBoundingClientRect();
    const w = Math.round(r.width);
    const h = Math.round(r.height);
    if (!w || !h || (w === this.w && h === this.h)) return;
    this.w = w;
    this.h = h;
    this.canvas.width = w * dpr;
    this.canvas.height = h * dpr;
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.draw();
  }

  push(values) {
    values.forEach((v, i) => {
      const arr = this.data[i];
      arr.push(v);
      if (arr.length > this.capacity) arr.shift();
    });
    this.draw();
  }

  draw() {
    const { ctx, w, h } = this;
    if (!w || !h) return;
    ctx.clearRect(0, 0, w, h);

    const pad = { t: 8, r: 4, b: 16, l: 4 };
    const plotH = h - pad.t - pad.b;

    ctx.strokeStyle = 'rgba(42,42,45,.8)';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 3; i++) {
      const y = pad.t + (plotH * i) / 3;
      ctx.beginPath();
      ctx.moveTo(pad.l, y + 0.5);
      ctx.lineTo(w - pad.r, y + 0.5);
      ctx.stroke();
    }

    this.series.forEach((s, i) => {
      const arr = this.data[i];
      if (arr.length < 2) return;
      const min = Math.min(...arr);
      const max = Math.max(...arr);
      const span = max - min || 1;
      // Stretch to fill while the buffer is still filling, then scroll.
      const span_n = Math.max(2, Math.min(this.capacity, arr.length));
      const step = (w - pad.l - pad.r) / (span_n - 1);

      ctx.beginPath();
      arr.forEach((v, j) => {
        const x = pad.l + j * step;
        const y = pad.t + plotH - ((v - min) / span) * plotH;
        j ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
      });
      ctx.strokeStyle = s.color;
      ctx.lineWidth = 1.6;
      ctx.stroke();

      ctx.fillStyle = s.color;
      ctx.font = '10px ui-monospace, monospace';
      const last = arr[arr.length - 1];
      ctx.fillText(`${s.label} ${last.toFixed(1)}${s.unit}`, pad.l + 2 + i * 108, h - 4);
    });
  }
}
