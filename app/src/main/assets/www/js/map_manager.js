/* Planificador Leaflet con múltiples waypoints */
const MapManager = (() => {
  const SAN_LUIS = [-32.95, -65.45];
  let map, line = null, glowLine = null, previewLine = null, routeLayers = [], onStatus = () => {}, currentRoute = null;
  let waypoints = [], waypointMarkers = [], correctionMode = false, correctionPoints = [], correctionMarkers = [];
  const icon = cls => L.divIcon({ className: '', html: '<div class="ta-marker ' + cls + '"></div>', iconSize: [18,18], iconAnchor: [9,9] });
  function defaultLayers() {
    const T=(u,o)=>L.tileLayer(u,o);
    const ignAttr='© Instituto Geográfico Nacional (IGN), Argentina';
    // El servicio TMS del IGN invierte el eje Y; {-y} es la extensión de Leaflet para TMS.
    const ignTms=(layer)=>T('https://wms.ign.gob.ar/geoserver/gwc/service/tms/1.0.0/'+layer+'@EPSG%3A3857@png/{z}/{x}/{-y}.png',{minZoom:0,maxZoom:18,attribution:ignAttr+' · Argenmap'});
    const openTopo=()=>T('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',{maxZoom:17,attribution:'© OpenStreetMap, SRTM | OpenTopoMap (CC-BY-SA)',subdomains:'abc'});
    const ignTopoOverlay=()=>T('https://wms.ign.gob.ar/geoserver/gwc/service/tms/1.0.0/mapabase_topo@EPSG%3A3857@png/{z}/{x}/{-y}.png',{minZoom:0,maxZoom:19,maxNativeZoom:13,opacity:0.42,attribution:ignAttr+' · Argenmap topográfico'});
    const satellite=()=>T('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',{maxZoom:19,attribution:'© Esri'});
    // Servicios oficiales dinámicos del IGN (ArcGIS REST). Se renderizan como teselas transparentes,
    // sin depender de plugins externos de Leaflet.
    const ignDynamic=(service,layers,label,opacity=0.85)=>{
      const endpoint='https://ide.ign.gob.ar/geoservicios/rest/services/ANIDA/'+service+'/MapServer/export';
      const layer=L.gridLayer({tileSize:256,opacity,attribution:'© IGN Argentina · '+label});
      layer.createTile=function(coords,done){
        const tile=document.createElement('img');tile.alt='';tile.setAttribute('role','presentation');
        const nw=map.unproject(L.point(coords.x*256,coords.y*256),coords.z);
        const se=map.unproject(L.point((coords.x+1)*256,(coords.y+1)*256),coords.z);
        const a=L.CRS.EPSG3857.project(nw),b=L.CRS.EPSG3857.project(se);
        const bbox=[a.x,b.y,b.x,a.y].join(',');
        const url=endpoint+'?bbox='+encodeURIComponent(bbox)+'&bboxSR=3857&imageSR=3857&size=256%2C256&format=png32&transparent=true&f=image&layers='+encodeURIComponent('show:'+layers.join(','));
        tile.onload=()=>done(null,tile);tile.onerror=()=>done(new Error('No se pudo cargar '+label),tile);tile.src=url;return tile;
      };
      return layer;
    };
    return {base:{
      'IGN · Argenmap oficial':ignTms('capabaseargenmap'),
      'IGN · Topográfico':T('https://wms.ign.gob.ar/geoserver/gwc/service/tms/1.0.0/mapabase_topo@EPSG%3A3857@png/{z}/{x}/{-y}.png',{minZoom:0,maxZoom:19,maxNativeZoom:13,attribution:ignAttr+' · Argenmap'}),
      'IGN · Híbrido':ignTms('mapabase_hibrido'),
      'IGN · Oscuro':ignTms('argenmap_oscuro'),
      'IGN · Gris':ignTms('mapabase_gris'),
      'OpenTopoMap':openTopo(),
      'OpenTopoMap + IGN topográfico':L.layerGroup([openTopo(),ignTopoOverlay()]),
      'Satélite + IGN topográfico':L.layerGroup([satellite(),ignTopoOverlay()]),
      'OpenStreetMap':T('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}),
      'ESRI World Imagery':satellite()
    },overlays:{
      'Senderos (Waymarked)':T('https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png',{maxZoom:17,attribution:'waymarkedtrails.org'}),
      'Ciclovías (Waymarked)':T('https://tile.waymarkedtrails.org/cycling/{z}/{x}/{y}.png',{maxZoom:17,attribution:'waymarkedtrails.org'}),
      'IGN · Hidrografía oficial':ignDynamic('Hidrografia',[0,1,2,3],'hidrografía oficial',0.9),
      'IGN · Cerros y relieve':ignDynamic('Relieve',[0],'relieve oficial',0.85),
      'IGN · Geología general':ignDynamic('geol_mayores',[0],'geología general',0.45)
    }};
  }
  const rad=d=>d*Math.PI/180;
  function hav(a,b){const dLat=rad(b[0]-a[0]),dLng=rad(b[1]-a[1]);const x=Math.sin(dLat/2)**2+Math.cos(rad(a[0]))*Math.cos(rad(b[0]))*Math.sin(dLng/2)**2;return 12742*Math.asin(Math.sqrt(x));}
  function gainOf(el){let gain=0,ref=el[0];for(const e of el){if(e-ref>=3){gain+=e-ref;ref=e;}else if(ref-e>=3)ref=e;}return gain;}
  function lengthOf(pts){let km=0;for(let i=1;i<pts.length;i++)km+=hav(pts[i-1],pts[i]);return km;}
  function drawProfile(pts,name){
    const box=document.getElementById('profile'),svg=box.querySelector('svg'),valid=pts.filter(p=>p[2]!=null&&isFinite(p[2])),elev=valid.map(p=>p[2]);
    if(valid.length<2){box.hidden=true;return;}
    let distance=0;const xs=[0];for(let i=1;i<valid.length;i++)xs.push(distance+=hav(valid[i-1],valid[i]));
    const min=Math.min(...elev),max=Math.max(...elev),span=Math.max(max-min,1),total=xs[xs.length-1]||1;
    const poly=valid.map((p,i)=>(xs[i]/total*300)+','+(60-((p[2]-min)/span)*52)).join(' ');
    svg.innerHTML='<path d="M0,60 L'+poly+' L300,60 Z"/>';
    document.getElementById('p-name').textContent=name||'Perfil de elevación';
    document.getElementById('p-range').textContent=Math.round(min)+' – '+Math.round(max)+' m';box.hidden=false;
  }
  function show(pts,color,name,km,gain){
    correctionMode=false;
    const correctionButton=document.querySelector('[data-action="correct-route"]');if(correctionButton)correctionButton.textContent='Corregir camino';
    routeLayers.forEach(layer=>{if(map.hasLayer(layer))map.removeLayer(layer);});routeLayers=[];line=null;glowLine=null;
    if(previewLine){map.removeLayer(previewLine);previewLine=null;}
    const coords=pts.map(p=>[p[0],p[1]]);
    const neon=color==='#34a98a'?'#00ffb3':'#00eaff';
    glowLine=L.polyline(coords,{color:neon,weight:12,opacity:.20,lineCap:'round',lineJoin:'round',className:'route-neon-halo',interactive:false}).addTo(map);routeLayers.push(glowLine);
    const glow=L.polyline(coords,{color:neon,weight:7,opacity:.38,lineCap:'round',lineJoin:'round',className:'route-neon-glow',interactive:false}).addTo(map);routeLayers.push(glow);
    line=L.polyline(coords,{color:color==='#34a98a'?'#45ffd0':'#8df8ff',weight:4,opacity:1,lineCap:'round',lineJoin:'round',className:'route-neon-core'}).addTo(map);routeLayers.push(line);
    glowLine.bringToBack();
    line.bringToFront();
    map.fitBounds(line.getBounds(),{padding:[40,40]});
    TerraAltis.setMetrics({km:Number(km)||0,gain:Number.isFinite(Number(gain))?Number(gain):NaN});drawProfile(pts,name);
    document.getElementById('calculate-route').hidden=true;document.getElementById('calculate-route').disabled=false;
    document.getElementById('waypoint-actions').hidden=true;document.getElementById('route-actions').hidden=false;
  }
  function drawRoute(pts,km,gain){
    const arr=typeof pts==='string'?JSON.parse(pts):pts;
    currentRoute={points:arr.map(p=>[Number(p[0]),Number(p[1]),p[2]==null?null:Number(p[2])]),km:Number(km),gain:Number.isFinite(Number(gain))?Number(gain):null,name:'Perfil topográfico estimado',source:'terrain'};
    show(currentRoute.points,getComputedStyle(document.documentElement).getPropertyValue('--cyan').trim()||'#38bdd8',currentRoute.name,currentRoute.km,currentRoute.gain);
    onStatus('Ruta topográfica estimada · '+currentRoute.km.toFixed(1)+' km · D+ estimado '+(currentRoute.gain==null?'sin datos':Math.round(currentRoute.gain)+' m')+' · se verificaron cauces del IGN; ATENCIÓN: no garantiza seguridad ni detecta todos los precipicios, quebradas u obstáculos. Verificá el terreno antes de salir.','busy');
  }
  function routeCalculationFailed(message){document.getElementById('calculate-route').disabled=false;onStatus(message,'busy');}
  function loadGpx(xml,name){
    const doc=new DOMParser().parseFromString(xml,'text/xml');if(doc.querySelector('parsererror'))return onStatus('El archivo GPX no es XML válido','busy');
    const nodes=doc.querySelectorAll('trkpt').length?doc.querySelectorAll('trkpt'):doc.querySelectorAll('rtept');
    const pts=Array.from(nodes).map(n=>[+n.getAttribute('lat'),+n.getAttribute('lon'),n.querySelector('ele')?parseFloat(n.querySelector('ele').textContent):null]);
    if(pts.length<2||pts.some(p=>!Number.isFinite(p[0])||!Number.isFinite(p[1])))return onStatus('El GPX no contiene una ruta o track válido','busy');
    clearWaypointMarkers();const elevations=pts.map(p=>p[2]).filter(Number.isFinite),gain=elevations.length?gainOf(elevations):null;
    currentRoute={points:pts,km:lengthOf(pts),gain,name:name||'Ruta importada',source:'gpx'};
    show(pts,getComputedStyle(document.documentElement).getPropertyValue('--emerald').trim()||'#34a98a',currentRoute.name,currentRoute.km,gain==null?NaN:gain);
    onStatus('GPX cargado: '+currentRoute.name+' · podés guardarlo o exportarlo','ok');
  }
  function showSavedRoute(route){
    if(!route||!Array.isArray(route.points)||route.points.length<2)return onStatus('La ruta guardada está dañada o incompleta','busy');
    clearWaypointMarkers();currentRoute={id:route.id,name:route.name||'Ruta guardada',collection:route.collection||'Mis rutas',createdAt:route.createdAt,points:route.points,km:Number(route.km)||lengthOf(route.points),gain:route.gain==null?null:Number(route.gain),source:route.source||'gpx'};
    const color=currentRoute.source==='terrain'?(getComputedStyle(document.documentElement).getPropertyValue('--cyan').trim()||'#38bdd8'):(getComputedStyle(document.documentElement).getPropertyValue('--emerald').trim()||'#34a98a');
    show(currentRoute.points,color,currentRoute.name,currentRoute.km,currentRoute.gain==null?NaN:currentRoute.gain);onStatus('Ruta abierta: '+currentRoute.name,'ok');
  }
  function exportRoute(route){
    if(!route||!Array.isArray(route.points)||route.points.length<2)return onStatus('No hay una ruta válida para exportar','busy');
    const esc=v=>String(v||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&apos;');
    const name=route.name||'TerraAltis',track=route.points.map(p=>'<trkpt lat="'+Number(p[0])+'" lon="'+Number(p[1])+'">'+(p[2]==null||!Number.isFinite(Number(p[2]))?'':'<ele>'+Number(p[2])+'</ele>')+'</trkpt>').join('');
    const gpx='<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="TerraAltis" xmlns="http://www.topografix.com/GPX/1/1"><metadata><name>'+esc(name)+'</name></metadata><trk><name>'+esc(name)+'</name><trkseg>'+track+'</trkseg></trk></gpx>';
    const filename=name.normalize('NFKD').replace(/[^a-zA-Z0-9_-]+/g,'-').replace(/^-+|-+$/g,'').slice(0,60)||'TerraAltis';
    if(window.Android&&typeof Android.exportGpx==='function'){Android.exportGpx(filename+'.gpx',gpx);onStatus('Elegí dónde guardar el archivo GPX…','busy');}
    else{const blob=new Blob([gpx],{type:'application/gpx+xml;charset=utf-8'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=filename+'.gpx';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);onStatus('GPX preparado para descargar','ok');}
  }
  function clearWaypointMarkers(){waypointMarkers.forEach(m=>map.removeLayer(m));waypointMarkers=[];waypoints=[];if(previewLine){map.removeLayer(previewLine);previewLine=null;}}
  function clearCorrectionMarkers(){correctionMarkers.forEach(m=>map.removeLayer(m));correctionMarkers=[];correctionPoints=[];correctionMode=false;}
  function updateDraft(){
    waypointMarkers.forEach((m,i)=>m.setIcon(icon(i===0?'start':i===waypointMarkers.length-1?'end':'via')));
    if(previewLine)map.removeLayer(previewLine);
    previewLine=waypoints.length>1?L.polyline(waypoints.map(p=>[p.lat,p.lng]),{color:'#c16be8',weight:3,opacity:.9,dashArray:'7 7'}).addTo(map):null;
    const n=waypoints.length,calc=document.getElementById('calculate-route');calc.hidden=n<2;calc.textContent='Calcular ruta · '+n+' puntos';
    document.getElementById('waypoint-actions').hidden=n<1;document.getElementById('route-actions').hidden=true;
  }
  function reset(){clearCorrectionMarkers();routeLayers.forEach(layer=>{if(map.hasLayer(layer))map.removeLayer(layer);});routeLayers=[];line=null;glowLine=null;if(previewLine){map.removeLayer(previewLine);previewLine=null;}clearWaypointMarkers();currentRoute=null;document.getElementById('profile').hidden=true;document.getElementById('calculate-route').hidden=true;document.getElementById('calculate-route').disabled=false;document.getElementById('waypoint-actions').hidden=true;document.getElementById('route-actions').hidden=true;TerraAltis.setMetrics(null);}
  function addWaypoint(latlng){
    if(waypoints.length&&hav([waypoints[waypoints.length-1].lat,waypoints[waypoints.length-1].lng],[latlng.lat,latlng.lng])<0.01)return onStatus('El nuevo punto está demasiado cerca del anterior','busy');
    waypoints.push({lat:latlng.lat,lng:latlng.lng});waypointMarkers.push(L.marker(latlng,{icon:icon('via')}).addTo(map));updateDraft();
    onStatus(waypoints.length===1?'Inicio fijado. Tocá el mapa para añadir puntos intermedios y el destino':waypoints.length+' puntos fijados. Podés añadir más o calcular la ruta','busy');
  }
  function undoWaypoint(){if(!waypoints.length)return;const m=waypointMarkers.pop();if(m)map.removeLayer(m);waypoints.pop();updateDraft();onStatus(waypoints.length?'Punto eliminado. Hay '+waypoints.length+' punto(s)':'Tocá el mapa para fijar el inicio','busy');}
  function newRoute(){reset();onStatus('Nuevo trazado: tocá el mapa para fijar el inicio','busy');}
  function calculateRoute(){
    if(waypoints.length<2)return onStatus('Añadí al menos dos puntos para calcular la ruta','busy');
    onStatus('Calculando ruta por '+waypoints.length+' waypoints. Puede tardar según el servicio de elevación…','busy');
    document.getElementById('calculate-route').disabled=true;
    if(window.Android&&Android.onRouteWaypoints)Android.onRouteWaypoints(JSON.stringify(waypoints));
    else routeCalculationFailed('El cálculo topográfico requiere la aplicación Android');
  }
  function toggleCorrection(){
    if(!currentRoute||currentRoute.points.length<2)return onStatus('Primero calculá o abrí una ruta para poder corregirla','busy');
    correctionMode=!correctionMode;const button=document.querySelector('[data-action="correct-route"]');
    if(correctionMode){if(button)button.textContent='Finalizar corrección';onStatus('Modo corrección: tocá el mapa donde querés que pase la ruta. Se recalculará pasando por ese punto.','busy');}
    else {if(button)button.textContent='Corregir camino';onStatus('Corrección finalizada. Los puntos agregados se aplicarán al recalcular.','busy');}
  }
  function onClick(e){
    if(correctionMode&&currentRoute){
      const p={lat:e.latlng.lat,lng:e.latlng.lng};const start=currentRoute.points[0],end=currentRoute.points[currentRoute.points.length-1];
      if(hav([start[0],start[1]],[p.lat,p.lng])<0.02||hav([end[0],end[1]],[p.lat,p.lng])<0.02)return onStatus('Elegí un punto separado del inicio y del destino','busy');
      correctionPoints.push(p);correctionMarkers.push(L.marker(e.latlng,{icon:icon('via'),draggable:true}).addTo(map));
      correctionMarkers[correctionMarkers.length-1].on('dragend',ev=>{const pos=ev.target.getLatLng();const idx=correctionMarkers.indexOf(ev.target);if(idx>=0){correctionPoints[idx]={lat:pos.lat,lng:pos.lng};waypoints=[{lat:currentRoute.points[0][0],lng:currentRoute.points[0][1]},...correctionPoints,{lat:currentRoute.points[currentRoute.points.length-1][0],lng:currentRoute.points[currentRoute.points.length-1][1]}];}onStatus('Punto movido. Recalculá la ruta para aplicar el cambio.','busy');});
      waypoints=[{lat:start[0],lng:start[1]},...correctionPoints,{lat:end[0],lng:end[1]}];
      onStatus('Punto de corrección agregado. Recalculando…','busy');calculateRoute();return;
    }
    if(currentRoute)newRoute();addWaypoint(e.latlng);
  }
  function locate(){onStatus('Buscando ubicación GPS…','busy');map.locate({setView:true,maxZoom:15,enableHighAccuracy:true});}
  function init(id,statusCb){
    onStatus=statusCb||onStatus;map=L.map(id,{zoomControl:false}).setView(SAN_LUIS,10);
    const layers=window.TA_LAYERS?window.TA_LAYERS():defaultLayers();layers.base[Object.keys(layers.base)[0]].addTo(map);
    L.control.layers(layers.base,layers.overlays,{position:'topright'}).addTo(map);L.control.zoom({position:'bottomright'}).addTo(map);L.control.scale({imperial:false,position:'bottomleft'}).addTo(map);
    map.on('click',onClick);map.on('locationfound',e=>{L.circleMarker(e.latlng,{radius:7,color:'#fff',weight:2,fillColor:'#38bdd8',fillOpacity:1}).addTo(map);onStatus('Ubicación encontrada','ok');});
    map.on('locationerror',()=>onStatus('No se pudo obtener la ubicación','busy'));
  }
  return {init,locate,loadGpx,drawRoute,routeCalculationFailed,calculateRoute,showSavedRoute,undoWaypoint,newRoute,toggleCorrection,
    getCurrentRoute:()=>currentRoute?JSON.parse(JSON.stringify(currentRoute)):null,exportCurrentRoute:()=>exportRoute(currentRoute),exportRoute,
    invalidate:()=>map&&setTimeout(()=>map.invalidateSize(),50)};
})();
