/* Lógica de UI: tema, secciones, drawer y menús contextuales */
const TerraAltis = {
  setStatus(msg, state) {
    document.getElementById('status-text').textContent = msg;
    document.getElementById('status-dot').classList.toggle('ok', state === 'ok');
  },
  // Kotlin -> JS: TerraAltis.setMetrics({km, gain}) | setMetrics(null)
  setMetrics(m) {
    document.getElementById('m-dist').textContent = m ? `${m.km.toFixed(1)} km` : '—';
    document.getElementById('m-gain').textContent = m && isFinite(m.gain) ? `${Math.round(m.gain)} m` : '—';
  },
  drawRoute(pts, km, gain) { MapManager.drawRoute(pts, km, gain); },
  // Botón atrás de Android: devuelve true si cerró algo
  back() { const o = document.body.classList.contains('overlay'); if (o) document.dispatchEvent(new CustomEvent('ta-close')); return o; }
};

(function () {
  const $ = s => document.querySelector(s), $$ = s => document.querySelectorAll(s);
  const root = document.documentElement, body = document.body;
  const store = { get: k => { try { return localStorage.getItem(k); } catch (e) { return null; } },
                  set: (k, v) => { try { localStorage.setItem(k, v); } catch (e) {} } };

  root.dataset.theme = store.get('ta-theme') || 'dark';

  function closeAll() {
    body.classList.remove('drawer-open', 'overlay');
    $$('.popover').forEach(p => p.classList.remove('open'));
    $('#drawer').setAttribute('aria-hidden', 'true');
  }
  function showTab(tab) {
    $$('.view').forEach(v => v.classList.toggle('active', v.dataset.view === tab));
    $$('.nav-item, .drawer-item').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
    closeAll();
    if (tab === 'map') MapManager.invalidate();   // Leaflet necesita recalcular tamaño
  }
  function togglePop(id) {
    const open = $(id).classList.contains('open');
    closeAll();
    if (!open) { $(id).classList.add('open'); body.classList.add('overlay'); }
  }

  // Delegación de eventos: un único listener para todos los botones
  document.addEventListener('click', e => {
    const el = e.target.closest('[data-action],[data-tab]');
    if (!el) return;
    if (el.dataset.tab) return showTab(el.dataset.tab);
    switch (el.dataset.action) {
      case 'drawer': closeAll(); body.classList.add('drawer-open', 'overlay'); $('#drawer').setAttribute('aria-hidden', 'false'); break;
      case 'pop-search': togglePop('#pop-search'); break;
      case 'pop-bell': togglePop('#pop-bell'); break;
      case 'close': closeAll(); break;
      case 'locate': MapManager.locate(); break;
      case 'gpx': $('#gpx-file').click(); break;
      case 'calculate': MapManager.calculateRoute(); break;
      case 'theme': {
        const next = root.dataset.theme === 'dark' ? 'light' : 'dark';
        root.dataset.theme = next; store.set('ta-theme', next); break;
      }
    }
  });

  document.addEventListener('ta-close', closeAll);
  document.addEventListener('keydown', e => {
    if (e.key !== 'Enter' || e.isComposing) return;
    if (e.target && /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName)) return;
    MapManager.calculateRoute();
  });
  $('#gpx-file').addEventListener('change', e => {
    const f = e.target.files[0]; if (!f) return;
    const r = new FileReader(); r.onload = () => MapManager.loadGpx(r.result, f.name); r.readAsText(f); e.target.value = '';
  });

  MapManager.init('map', TerraAltis.setStatus);
})();
