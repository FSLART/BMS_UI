/**
 * Model preloader.
 *
 * Raw CAD exports run to hundreds of MB, so downloading one the moment a screen
 * needs it reads as a freeze. Fetch them all up front behind a splash with real
 * byte progress, keep them as object URLs, and every later view is instant.
 *
 * Only models for *available* cars are fetched, and a missing GLB is skipped
 * rather than treated as an error -- the app works without any model at all.
 */

/** originalPath -> { objectUrl, bytes } */
export const modelCache = new Map();

const MB = 1048576;

// Which file is downloading is noise to the user; the bar and the MB counter
// already say how far along it is.
const LABEL = 'A carregar…';

export class Preloader {
  constructor(root) {
    this.root = root;
    this.els = {
      bar: root.querySelector('[data-role="bar"]'),
      pct: root.querySelector('[data-role="pct"]'),
      file: root.querySelector('[data-role="file"]'),
      bytes: root.querySelector('[data-role="bytes"]'),
      note: root.querySelector('[data-role="note"]'),
    };
  }

  _paint({ pct, file, loaded, total, note }) {
    if (pct !== undefined) {
      this.els.bar.style.width = `${pct}%`;
      this.els.pct.textContent = `${Math.round(pct)}%`;
    }
    if (file !== undefined) this.els.file.textContent = file;
    if (loaded !== undefined) {
      this.els.bytes.textContent = total
        ? `${(loaded / MB).toFixed(0)} / ${(total / MB).toFixed(0)} MB`
        : '';
    }
    if (note !== undefined) this.els.note.textContent = note;
  }

  async run() {
    this._paint({ pct: 0, file: LABEL, loaded: 0, total: 0, note: '' });

    let cars = [];
    try {
      cars = (await fetch('/api/cars').then((r) => r.json())).cars || [];
    } catch {
      this._paint({ pct: 100, file: '', note: 'Backend indisponível' });
      return;
    }

    // Unique paths, available cars only. EVO/T-28 have no GLBs yet.
    const paths = [...new Set(
      cars.filter((c) => c.available)
          // Every view a car declares. A model that does not exist yet (the
          // handcart one) 404s and is skipped, which is why this can list them
          // all without waiting for the CAD.
          .flatMap((c) => [c.model_closed, c.model_open, c.model_charger, c.model_segment])
          .filter(Boolean),
    )];

    // Size pass first, so the bar tracks real bytes instead of file count.
    const items = [];
    for (const path of paths) {
      try {
        const res = await fetch(path, { method: 'HEAD' });
        if (res.ok) items.push({ path, bytes: Number(res.headers.get('content-length') || 0) });
      } catch {
        /* missing model: nothing to preload, the viewer shows its placeholder */
      }
    }

    const total = items.reduce((a, i) => a + i.bytes, 0);
    if (!items.length || !total) {
      this._paint({ pct: 100, file: '', note: paths.length ? 'Nenhum modelo disponível' : '' });
      return;
    }

    let done = 0;
    for (const item of items) {
      this._paint({ file: LABEL, loaded: done, total });
      try {
        const bytes = await this._fetchWithProgress(item, (loaded) => {
          this._paint({ pct: ((done + loaded) / total) * 100, loaded: done + loaded, total });
        });
        modelCache.set(item.path, { objectUrl: URL.createObjectURL(bytes), bytes: bytes.size });
      } catch (e) {
        console.warn(`[preload] ${item.path} falhou:`, e);
      }
      done += item.bytes;
      this._paint({ pct: (done / total) * 100, loaded: done, total });
    }

    this._paint({ pct: 100, file: 'Pronto', note: '' });
    await new Promise((r) => setTimeout(r, 260));   // let the bar reach the end
  }

  async _fetchWithProgress(item, onProgress) {
    const res = await fetch(item.path);
    if (!res.ok) throw new Error(`${res.status}`);

    // No streaming body (very old engines): fall back to a single blob.
    if (!res.body || !res.body.getReader) return res.blob();

    const reader = res.body.getReader();
    const chunks = [];
    let loaded = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      loaded += value.length;
      onProgress(loaded);
    }
    return new Blob(chunks, { type: 'model/gltf-binary' });
  }
}

/** Object URL for a preloaded model, or the original path if not cached. */
export function resolveModel(path) {
  return modelCache.get(path)?.objectUrl || path;
}
