/* Leaflet, capas topográficas, rutas, biblioteca GPX y perfil de elevación */
const MapManager = (() => {
  const SAN_LUIS = [-32.95, -65.45];
  let map, startM = null, endM = null, line = null, onStatus = () => {}, currentRoute = null;

  const icon = cls => L.divIcon({ className: '', html: '<div class="ta-marker ' + cls + '"></div>', iconSize: [18, 18], iconAnchor: [9, 9] });
  function defaultLayers() {
    const T = (u, o) => L.tileLayer(u, o);
    return {
      base: {
        'OpenTopoMap': T('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png', { maxZoom: 17, attribution: '© OpenStreetMap, SRTM | OpenTopoMap (CC-BY-SA)', subdomains: 'abc' }),
        'OpenStreetMap': T('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }),
        'ESRI World Imagery': T('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', { maxZoom: 19, attribution: '© Esri' })
      },
      overlays: {
        'Senderos (Waymarked)': T('https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png', { maxZoom: 17, attribution: 'waymarkedtrails.org' }),
        'Ciclovías (Waymarked)': T('https://tile.waymarkedtrails.org/cycling/{z}/{x}/{y}.png', { maxZoom: 17, attribution: 'waymarkedtrails.org' })
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
    let gain = 0, ref = el[0];
    for (const elevation of el) {
      if (elevation - ref >= 3) { gain += elevation - ref; ref = elevation; }
      else if (ref - elevation >= 3) ref = elevation;
    }
    return gain;
  }
  function lengthOf(pts) {
    let km = 0;
    for (let i = 1; i < pts.length; i++) km += hav(pts[i - 1], pts[i]);
    return km;
  }
  function drawProfile(pts, name) {
    const box = document.getElementById('profile'), svg = box.querySelector('svg');
    const valid = pts.filter(p => p[2] != null && isFinite(p[2]));
    const elevations = valid.map(p => p[2]);
    if (valid.length < 2) { box.hidden = true; return; }
    let distance = 0; const xs = [0];
    for (let i = 1; i < valid.length; i++) xs.push(distance += hav(valid[i - 1], valid[i]));
    const min = Math.min(...elevations), max = Math.max(...elevations), span = Math.max(max - min, 1);
    const total = xs[xs.length - 1] || 1;
    const points = valid.map((p, i) => (xs[i] / total * 300) + ',' + (60 - ((p[2] - min) / span) * 52)).join(' ');
    svg.innerHTML = '<path d="M0,60 L' + points + ' L300,60 Z"/>';
    document.getElementById('p-name').textContent = name || 'Perfil de elevación';
    document.getElementById('p-range').textContent = Math.round(min) + ' – ' + Math.round(max) + ' m';
    box.hidden = false;
  }

  function show(pts, color, name, km, gain) {
    if (line) map.removeLayer(line);
    line = L.polyline(pts.map(p => [p[0], p[1]]), { color, weight: 4, opacity: .9 }).addTo(map);
    map.fitBounds(line.getBounds(), { padding: [40, 40] });
    TerraAltis.setMetrics({ km: Number(km) || 0, gain: Number.isFinite(Number(gain)) ? Number(gain) : NaN });
    drawProfile(pts, name);
    document.getElementById('calculate-route').hidden = true;
    document.getElementById('route-actions').hidden = false;
  }

  function drawRoute(pts, km, gain) {
    const arr = typeof pts === 'string' ? JSON.parse(pts) : pts;
    currentRoute = { points: arr.map(p => [Number(p[0]), Number(p[1]), p[2] == null ? null : Number(p[2])]), km: Number(km), gain: Number.isFinite(Number(gain)) ? Number(gain) : null, name: 'Perfil topográfico estimado', source: 'terrain' };
    show(currentRoute.points, getComputedStyle(document.documentElement).getPropertyValue('--cyan').trim() || '#38bdd8', currentRoute.name, currentRoute.km, currentRoute.gain);
    onStatus('Trazado topográfico listo · ' + currentRoute.km.toFixed(1) + ' km · D+ estimado ' + (currentRoute.gain == null ? 'sin datos' : Math.round(currentRoute.gain) + ' m') + ' · no verifica obstáculos', 'ok');
  }

  function loadGpx(xml, name) {
    const doc = new DOMParser().parseFromString(xml, 'text/xml');
    if (doc.querySelector('parsererror')) return onStatus('El archivo GPX no es XML válido', 'busy');
    const nodes = doc.querySelectorAll('trkpt').length ? doc.querySelectorAll('trkpt') : doc.querySelectorAll('rtept');
    const pts = Array.from(nodes).map(n => [+n.getAttribute('lat'), +n.getAttribute('lon'), n.querySelector('ele') ? parseFloat(n.querySelector('ele').textContent) : null]);
    if (pts.length < 2 || pts.some(p => !Number.isFinite(p[0]) || !Number.isFinite(p[1]))) return onStatus('El GPX no contiene una ruta o track válido', 'busy');
    const elevations = pts.map(p => p[2]).filter(v => Number.isFinite(v));
    const gain = elevations.length ? gainOf(elevations) : null;
    currentRoute = { points: pts, km: lengthOf(pts), gain: gain, name: name || 'Ruta importada', source: 'gpx' };
    show(pts, getComputedStyle(document.documentElement).getPropertyValue('--emerald').trim() || '#34a98a', currentRoute.name, currentRoute.km, gain == null ? NaN : gain);
    onStatus('GPX cargado: ' + currentRoute.name + ' · podés guardarlo o exportarlo', 'ok');
  }

  function showSavedRoute(route) {
    if (!route || !Array.isArray(route.points) || route.points.length < 2) return onStatus('La ruta guardada está dañada o incompleta', 'busy');
    currentRoute = {
      id: route.id, name: route.name || 'Ruta guardada', collection: route.collection || 'Mis rutas',
      createdAt: route.createdAt, points: route.points, km: Number(route.km) || lengthOf(route.points),
      gain: route.gain == null ? null : Number(route.gain), source: route.source || 'gpx'
    };
    const color = currentRoute.source === 'terrain'
      ? (getComputedStyle(document.documentElement).getPropertyValue('--cyan').trim() || '#38bdd8')
      : (getComputedStyle(document.documentElement).getPropertyValue('--emerald').trim() || '#34a98a');
    show(currentRoute.points, color, currentRoute.name, currentRoute.km, currentRoute.gain == null ? NaN : currentRoute.gain);
    onStatus('Ruta abierta: ' + currentRoute.name, 'ok');
  }

  function exportRoute(route) {
    if (!route || !Array.isArray(route.points) || route.points.length < 2) return onStatus('No hay una ruta válida para exportar', 'busy');
    const escapeXml = value => String(value || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;');
    const name = route.name || 'TerraAltis';
    const trackPoints = route.points.map(p => {
      const elevation = p[2] == null || !Number.isFinite(Number(p[2])) ? '' : '<ele>' + Number(p[2]) + '</ele>';
      return '<trkpt lat="' + Number(p[0]) + '" lon="' + Number(p[1]) + '">' + elevation + '</trkpt>';
    }).join('');
    const gpx = '<?xml version="1.0" encoding="UTF-8"?>\n' +
      '<gpx version="1.1" creator="TerraAltis" xmlns="http://www.topografix.com/GPX/1/1"><metadata><name>' + escapeXml(name) + '</name></metadata>' +
      '<trk><name>' + escapeXml(name) + '</name><trkseg>' + trackPoints + '</trkseg></trk></gpx>';
    const filename = name.normalize('NFKD').replace(/[^a-zA-Z0-9_-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || 'TerraAltis';
    if (window.Android && typeof Android.exportGpx === 'function') {
      Android.exportGpx(filename + '.gpx', gpx);
      onStatus('Elegí dónde guardar el archivo GPX…', 'busy');
    } else {
      const blob = new Blob([gpx], { type: 'application/gpx+xml;charset=utf-8' });
      const url = URL.createObjectURL(blob), link = document.createElement('a');
      link.href = url; link.download = filename + '.gpx'; link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      onStatus('GPX preparado para descargar', 'ok');
    }
  }

  function reset() {
    [startM, endM, line].forEach(marker => marker && map.removeLayer(marker));
    startM = endM = line = null;
    currentRoute = null;
    document.getElementById('profile').hidden = true;
    document.getElementById('calculate-route').hidden = true;
    document.getElementById('route-actions').hidden = true;
  }
  function calculateRoute() {
    if (!startM || !endM) return;
    const start = startM.getLatLng(), end = endM.getLatLng();
    onStatus('Generando un trazado topográfico nuevo…', 'busy');
    if (window.Android && Android.onRouteEndpoints) Android.onRouteEndpoints(start.lat, start.lng, end.lat, end.lng);
    else onStatus('El cálculo topográfico requiere la aplicación Android', 'busy');
  }
  function onClick(event) {
    if (startM && endM) { reset(); TerraAltis.setMetrics(null); }
    if (!startM) {
      startM = L.marker(event.latlng, { icon: icon('start') }).addTo(map);
      onStatus('Inicio fijado. Toca el punto de destino', 'busy');
    } else {
      endM = L.marker(event.latlng, { icon: icon('end') }).addTo(map);
      document.getElementById('calculate-route').hidden = false;
      onStatus('Inicio y destino fijados. Pulsa Enter o Calcular ruta', 'busy');
    }
  }
  function locate() {
    onStatus('Buscando ubicación GPS…', 'busy');
    map.locate({ setView: true, maxZoom: 15, enableHighAccuracy: true });
  }
  function init(id, statusCb) {
    onStatus = statusCb || onStatus;
    map = L.map(id, { zoomControl: false }).setView(SAN_LUIS, 10);
    const layers = window.TA_LAYERS ? window.TA_LAYERS() : defaultLayers();
    layers.base[Object.keys(layers.base)[0]].addTo(map);
    L.control.layers(layers.base, layers.overlays, { position: 'topright' }).addTo(map);
    L.control.zoom({ position: 'bottomright' }).addTo(map);
    L.control.scale({ imperial: false, position: 'bottomleft' }).addTo(map);
    map.on('click', onClick);
    map.on('locationfound', event => {
      L.circleMarker(event.latlng, { radius: 7, color: '#fff', weight: 2, fillColor: '#38bdd8', fillOpacity: 1 }).addTo(map);
      onStatus('Ubicación encontrada', 'ok');
    });
    map.on('locationerror', () => onStatus('No se pudo obtener la ubicación', 'busy'));
  }
  return {
    init, locate, loadGpx, drawRoute, calculateRoute, showSavedRoute,
    getCurrentRoute: () => currentRoute ? JSON.parse(JSON.stringify(currentRoute)) : null,
    exportCurrentRoute: () => exportRoute(currentRoute),
    exportRoute,
    invalidate: () => map && setTimeout(() => map.invalidateSize(), 50)
  };
})();
