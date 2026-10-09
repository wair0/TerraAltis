/* Leaflet + capas topográficas + rutas/GPX + perfil de elevación */
const MapManager = (() => {
  const SAN_LUIS = [-32.95, -65.45];
  let map, startM = null, endM = null, line = null, onStatus = () => {};

  const icon = cls => L.divIcon({ className: '', html: `<div class="ta-marker ${cls}"></div>`, iconSize: [18, 18], iconAnchor: [9, 9] });

  // Capas que funcionan en WebView (sin openmaps.fr, que bloquea por referer)
  function defaultLayers() {
    const T = (u, o) => L.tileLayer(u, o);
    return {
      base: {
        'OpenTopoMap': T('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png', {
          maxZoom: 17,
          attribution: '© OpenStreetMap, SRTM | OpenTopoMap (CC-BY-SA)',
          subdomains: 'abc'
        }),
        'OpenStreetMap': T('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
          maxZoom: 19,
          attribution: '© OpenStreetMap'
        }),
        'ESRI World Imagery': T('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
          maxZoom: 19,
          attribution: '© Esri'
        })
      },
      overlays: {
        'Senderos (Waymarked)': T('https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png', {
          maxZoom: 17,
          attribution: 'waymarkedtrails.org'
        }),
        'Ciclovías (Waymarked)': T('https://tile.waymarkedtrails.org/cycling/{z}/{x}/{y}.png', {
          maxZoom: 17,
          attribution: 'waymarkedtrails.org'
        })
      }
    };
  }

  const rad = d => d * Math.PI / 180;
  function hav(a, b) {
    const dLat = rad(b[0] - a[0]), dLng = rad(b[1] - a[1]);
    const x = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a[0])) * Math.cos(rad(b[0])) * Math.sin(dLng / 2) ** 2;
    return 12742 * Math.asin(Math.sqrt(x));
  }
  function gainOf(el) {
    let g = 0, ref = el[0];
    for (const e of el) { if (e - ref >= 3) { g += e - ref; ref = e; } else if (ref - e >= 3) ref = e; }
    return g;
  }
  function lengthOf(pts) { let k = 0; for (let i = 1; i < pts.length; i++) k += hav(pts[i - 1], pts[i]); return k; }

  function drawProfile(pts, name) {
    const box = document.getElementById('profile'), svg = box.querySelector('svg');
    const valid = pts.filter(p => p[2] != null && isFinite(p[2]));
    const el = valid.map(p => p[2]);
    if (valid.length < 2) { box.hidden = true; return; }
    let d = 0; const xs = [0]; for (let i = 1; i < valid.length; i++) xs.push(d += hav(valid[i - 1], valid[i]));
    const mn = Math.min(...el), mx = Math.max(...el), span = Math.max(mx - mn, 1);
    const total = xs[xs.length - 1] || 1;
    const P = valid.map((p, i) => `${(xs[i] / total) * 300},${60 - ((p[2] - mn) / span) * 52}`).join(' ');
    svg.innerHTML = `<path d="M0,60 L${P} L300,60 Z"/>`;
    document.getElementById('p-name').textContent = name || 'Perfil de elevación';
    document.getElementById('p-range').textContent = `${Math.round(mn)} – ${Math.round(mx)} m`;
    box.hidden = false;
  }

  function show(pts, color, name, km, gain) {
    if (line) map.removeLayer(line);
    line = L.polyline(pts.map(p => [p[0], p[1]]), { color, weight: 4, opacity: .9 }).addTo(map);
    map.fitBounds(line.getBounds(), { padding: [40, 40] });
    TerraAltis.setMetrics({ km, gain });
    drawProfile(pts, name);
  }

  function drawRoute(pts, km, gain) {
    const arr = typeof pts === 'string' ? JSON.parse(pts) : pts;
    show(arr, getComputedStyle(document.documentElement).getPropertyValue('--cyan').trim() || '#38bdd8', 'Ruta de mínimo desnivel', km, gain);
    onStatus(`Ruta lista · ${km.toFixed(1)} km · D+ ${Math.round(gain)} m`, 'ok');
  }

  function drawTrack(pts, km, gain) {
    if (!map || !pts.length) return;
    if (line) map.removeLayer(line);
    line = L.polyline(pts.map(p => [p[0], p[1]]), {
      color: getComputedStyle(document.documentElement).getPropertyValue('--emerald').trim() || '#34a98a',
      weight: 4, opacity: .95
    }).addTo(map);
    if (pts.length === 1) map.setView([pts[0][0], pts[0][1]], 16);
    TerraAltis.setMetrics({ km, gain });
    drawProfile(pts, 'Desnivel registrado');
  }

  function loadGpx(xml, name) {
    const doc = new DOMParser().parseFromString(xml, 'text/xml');
    const nodes = [...doc.querySelectorAll('trkpt')].length ? doc.querySelectorAll('trkpt') : doc.querySelectorAll('rtept');
    const pts = [...nodes].map(n => [+n.getAttribute('lat'), +n.getAttribute('lon'), parseFloat(n.querySelector('ele')?.textContent)]);
    if (pts.length < 2) return onStatus('El GPX no contiene track ni ruta', 'busy');
    const el = pts.map(p => p[2]).filter(v => isFinite(v));
    show(pts, getComputedStyle(document.documentElement).getPropertyValue('--emerald').trim() || '#34a98a', name, lengthOf(pts), el.length ? gainOf(el) : NaN);
    onStatus(`GPX cargado: ${name}`, 'ok');
  }

  function reset() { [startM, endM, line].forEach(m => m && map.removeLayer(m)); startM = endM = line = null; document.getElementById('profile').hidden = true; }

  function onClick(ev) {
    if (startM && endM) { reset(); TerraAltis.setMetrics(null); }
    if (!startM) { startM = L.marker(ev.latlng, { icon: icon('start') }).addTo(map); onStatus('Inicio fijado. Toca el punto de destino', 'busy'); }
    else {
      endM = L.marker(ev.latlng, { icon: icon('end') }).addTo(map);
      onStatus('Calculando ruta de mínimo desnivel…', 'busy');
      const s = startM.getLatLng(), e = endM.getLatLng();
      if (window.Android && Android.onRouteEndpoints) Android.onRouteEndpoints(s.lat, s.lng, e.lat, e.lng);
      else console.log('Bridge no disponible (navegador):', s, e);
    }
  }

  function locate() {
    onStatus('Buscando ubicación GPS…', 'busy');
    map.locate({ setView: true, maxZoom: 15, enableHighAccuracy: true });
  }

  function init(id, statusCb) {
    onStatus = statusCb || onStatus;
    map = L.map(id, { zoomControl: false }).setView(SAN_LUIS, 10);
    const set = window.TA_LAYERS ? window.TA_LAYERS() : defaultLayers();
    set.base[Object.keys(set.base)[0]].addTo(map);
    L.control.layers(set.base, set.overlays, { position: 'topright' }).addTo(map);
    L.control.zoom({ position: 'bottomright' }).addTo(map);
    L.control.scale({ imperial: false, position: 'bottomleft' }).addTo(map);
    map.on('click', onClick);
    map.on('locationfound', e => { L.circleMarker(e.latlng, { radius: 7, color: '#fff', weight: 2, fillColor: '#38bdd8', fillOpacity: 1 }).addTo(map); onStatus('Ubicación encontrada', 'ok'); });
    map.on('locationerror', () => onStatus('No se pudo obtener la ubicación', 'busy'));
  }
  return { init, locate, loadGpx, drawRoute, drawTrack, invalidate: () => map && setTimeout(() => map.invalidateSize(), 50) };
})();
