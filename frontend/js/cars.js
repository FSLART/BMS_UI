/**
 * Car selection — the first screen. The chosen car decides which DBC repo the
 * CAN decoder pulls from, the pack topology, and which GLBs the viewer loads.
 */

export class CarScreen {
  constructor(root, { onSelect }) {
    this.root = root;
    this.onSelect = onSelect;
    this.cars = [];
    this.selected = null;
    this.cards = {};
  }

  async mount() {
    let data;
    try {
      data = await fetch('/api/cars').then((r) => r.json());
    } catch {
      data = { cars: [], selected: null };
    }
    this.cars = data.cars || [];
    this.root.innerHTML = '';
    for (const car of this.cars) this.root.appendChild(this._card(car));
    if (data.selected) this._mark(data.selected);
    return this.cars;
  }

  _card(car) {
    const el = document.createElement('div');
    el.className = `car-card${car.available ? '' : ' unavailable'}`;
    el.dataset.id = car.id;
    if (!car.available) {
      el.setAttribute('aria-disabled', 'true');
      el.title = `${car.name} ainda não está disponível`;
    }
    el.innerHTML = `
      <div class="car-photo">
        <div class="photo-missing">${car.image}</div>
      </div>
      <div class="car-name">${car.name}</div>`;

    // Load the PNG only if it actually resolves, so a missing file leaves the
    // dashed placeholder in place instead of a broken-image icon.
    if (car.image) {
      const img = new Image();
      img.alt = car.name;
      if (car.image_is_silhouette) img.className = 'silhouette';
      img.onload = () => {
        const photo = el.querySelector('.car-photo');
        photo.querySelector('.photo-missing')?.remove();
        photo.appendChild(img);
      };
      img.src = car.image;
    }

    if (car.available) el.addEventListener('click', () => this.select(car.id));
    this.cards[car.id] = el;
    return el;
  }

  _mark(id) {
    this.selected = id;
    for (const [key, el] of Object.entries(this.cards)) el.classList.toggle('selected', key === id);
  }

  async select(id) {
    const car = this.cars.find((c) => c.id === id);
    if (!car || !car.available) return;
    this._mark(id);
    let res;
    try {
      res = await fetch('/api/car', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id }),
      }).then((r) => r.json());
    } catch (e) {
      res = { ok: false, error: String(e) };
    }
    if (!res.ok) {
      this._mark(null);
      return;
    }
    this.onSelect(this.cars.find((c) => c.id === id));
  }
}
