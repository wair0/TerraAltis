/* Lógica de UI, biblioteca local de rutas y menús contextuales */
const TerraAltis = {
  setStatus(msg, state) {
    document.getElementById('status-text').textContent = msg;
    document.getElementById('status-dot').classList.toggle('ok', state === 'ok');
  },
  setMetrics(m) {
    document.getElementById('m-dist').textContent = m ? m.km.toFixed(1) + ' km' : '—';
    document.getElementById('m-gain').textContent = m && isFinite(m.gain) ? Math.round(m.gain) + ' m' : '—';
  },
  drawRoute(pts, km, gain) { MapManager.drawRoute(pts, km, gain); document.getElementById('calculate-route').disabled = false; },
  routeCalculationFailed(message) { document.getElementById('calculate-route').disabled = false; MapManager.routeCalculationFailed(message); },
  back() {
    const overlay = document.body.classList.contains('overlay');
    if (overlay) document.dispatchEvent(new CustomEvent('ta-close'));
    return overlay;
  }
};

(function () {
  const $ = s => document.querySelector(s), $$ = s => document.querySelectorAll(s);
  const root = document.documentElement, body = document.body;
  const SAVED_KEY = 'ta-saved-routes';
  const store = {
    get: k => { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: (k, v) => { try { localStorage.setItem(k, v); return true; } catch (e) { return false; } }
  };

  function savedRoutes() {
    try {
      const items = JSON.parse(store.get(SAVED_KEY) || '[]');
      return Array.isArray(items) ? items : [];
    } catch (e) { return []; }
  }

  function renderSavedRoutes() {
    const host = $('#saved-list');
    if (!host) return;
    host.replaceChildren();
    const routes = savedRoutes().sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''));
    if (!routes.length) {
      const empty = document.createElement('article');
      empty.className = 'card empty';
      empty.textContent = 'Todavía no guardaste rutas. Calculá una ruta o importá un GPX y pulsá «Guardar ruta».';
      host.appendChild(empty);
      return;
    }
    const groups = new Map();
    routes.forEach(route => {
      const collection = (route.collection || 'Mis rutas').trim() || 'Mis rutas';
      if (!groups.has(collection)) groups.set(collection, []);
      groups.get(collection).push(route);
    });
    groups.forEach((items, collection) => {
      const heading = document.createElement('h3');
      heading.className = 'collection-heading';
      heading.textContent = collection + ' · ' + items.length;
      host.appendChild(heading);
      items.forEach(route => {
        const card = document.createElement('article');
        card.className = 'card saved-route';
        const title = document.createElement('h3');
        title.textContent = route.name || 'Ruta sin nombre';
        const details = document.createElement('p');
        const km = Number.isFinite(Number(route.km)) ? Number(route.km).toFixed(1) + ' km' : 'Distancia desconocida';
        const gain = route.gain == null || !Number.isFinite(Number(route.gain)) ? 'D+ sin datos' : 'D+ ' + Math.round(Number(route.gain)) + ' m';
        details.textContent = km + ' · ' + gain;
        const date = document.createElement('p');
        date.className = 'saved-date';
        date.textContent = route.createdAt ? new Date(route.createdAt).toLocaleString() : 'Fecha desconocida';
        const actions = document.createElement('div');
        actions.className = 'saved-route-actions';
        [['load', 'Abrir'], ['export', 'Exportar GPX'], ['delete', 'Eliminar']].forEach(pair => {
          const button = document.createElement('button');
          button.className = 'small-action' + (pair[0] === 'delete' ? ' danger' : '');
          button.textContent = pair[1];
          button.dataset.routeAction = pair[0];
          button.dataset.routeId = route.id;
          actions.appendChild(button);
        });
        card.append(title, details, date, actions);
        host.appendChild(card);
      });
    });
  }

  function closeAll() {
    body.classList.remove('drawer-open', 'overlay');
    $$('.popover').forEach(p => p.classList.remove('open'));
    $('#drawer').setAttribute('aria-hidden', 'true');
    $('#save-panel').hidden = true;
  }

  function showTab(tab) {
    $$('.view').forEach(v => v.classList.toggle('active', v.dataset.view === tab));
    $$('.nav-item, .drawer-item').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
    closeAll();
    if (tab === 'map') MapManager.invalidate();
    if (tab === 'saved') renderSavedRoutes();
  }

  function togglePop(id) {
    const open = $(id).classList.contains('open');
    closeAll();
    if (!open) { $(id).classList.add('open'); body.classList.add('overlay'); }
  }

  function openSavePanel() {
    const route = MapManager.getCurrentRoute();
    if (!route) return TerraAltis.setStatus('Primero calculá una ruta o importá un GPX', 'busy');
    $('#route-name').value = route.name && route.name !== 'Perfil topográfico estimado' ? route.name : 'Ruta ' + new Date().toLocaleDateString('es-AR');
    $('#route-collection').value = 'Mis rutas';
    $('#save-panel').hidden = false;
    body.classList.add('overlay');
    $('#route-name').focus();
  }

  function saveCurrentRoute() {
    const route = MapManager.getCurrentRoute();
    if (!route || !route.points || route.points.length < 2) {
      closeAll();
      return TerraAltis.setStatus('No hay una ruta válida para guardar', 'busy');
    }
    const name = $('#route-name').value.trim() || 'Ruta sin nombre';
    const collection = $('#route-collection').value.trim() || 'Mis rutas';
    route.id = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);
    route.name = name;
    route.collection = collection;
    route.createdAt = new Date().toISOString();
    const routes = savedRoutes();
    routes.push(route);
    if (!store.set(SAVED_KEY, JSON.stringify(routes))) {
      closeAll();
      return TerraAltis.setStatus('No hay espacio disponible para guardar la ruta en este teléfono', 'busy');
    }
    closeAll();
    renderSavedRoutes();
    TerraAltis.setStatus('Ruta guardada en «' + collection + '»', 'ok');
  }

  root.dataset.theme = store.get('ta-theme') || 'dark';
  document.addEventListener('click', e => {
    const routeButton = e.target.closest('[data-route-action]');
    if (routeButton) {
      const route = savedRoutes().find(item => item.id === routeButton.dataset.routeId);
      if (!route) return;
      if (routeButton.dataset.routeAction === 'load') {
        showTab('map');
        MapManager.showSavedRoute(route);
      } else if (routeButton.dataset.routeAction === 'export') {
        MapManager.exportRoute(route);
      } else if (routeButton.dataset.routeAction === 'delete') {
        const remaining = savedRoutes().filter(item => item.id !== route.id);
        store.set(SAVED_KEY, JSON.stringify(remaining));
        renderSavedRoutes();
        TerraAltis.setStatus('Ruta eliminada de Guardados', 'ok');
      }
      return;
    }

    const el = e.target.closest('[data-action],[data-tab]');
    if (!el) return;
    if (el.dataset.tab) return showTab(el.dataset.tab);
    switch (el.dataset.action) {
      case 'drawer':
        closeAll(); body.classList.add('drawer-open', 'overlay');
        $('#drawer').setAttribute('aria-hidden', 'false');
        break;
      case 'pop-search': togglePop('#pop-search'); break;
      case 'pop-bell': togglePop('#pop-bell'); break;
      case 'close': closeAll(); break;
      case 'locate': MapManager.locate(); break;
      case 'gpx': $('#gpx-file').click(); break;
      case 'calculate': MapManager.calculateRoute(); break;
      case 'undo-point': MapManager.undoWaypoint(); break;
      case 'clear-points': MapManager.newRoute(); break;
      case 'new-route': MapManager.newRoute(); break;
      case 'save-route': openSavePanel(); break;
      case 'confirm-save-route': saveCurrentRoute(); break;
      case 'cancel-save-route': closeAll(); break;
      case 'export-route': MapManager.exportCurrentRoute(); break;
      case 'correct-route': MapManager.toggleCorrection(); break;
      case 'theme': {
        const next = root.dataset.theme === 'dark' ? 'light' : 'dark';
        root.dataset.theme = next; store.set('ta-theme', next);
        break;
      }
    }
  });

  document.addEventListener('ta-close', closeAll);
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') { closeAll(); return; }
    if (e.key !== 'Enter' || e.isComposing) return;
    if (e.target && /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName)) {
      if (e.target.id === 'route-name' || e.target.id === 'route-collection') {
        e.preventDefault(); saveCurrentRoute();
      }
      return;
    }
    MapManager.calculateRoute();
  });
  $('#gpx-file').addEventListener('change', e => {
    const file = e.target.files[0]; if (!file) return;
    const reader = new FileReader();
    reader.onload = () => MapManager.loadGpx(reader.result, file.name);
    reader.readAsText(file);
    e.target.value = '';
  });

  renderSavedRoutes();
  MapManager.init('map', TerraAltis.setStatus);
})();
