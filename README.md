# TerraAltis

App Android híbrida (WebView + Leaflet) para rutas de senderismo con **mínimo desnivel positivo**.

## Plan de desarrollo por fases

### Fase 1 – Esqueleto del proyecto y mapa base
- Estructura Gradle (Kotlin + AppCompat)
- MainActivity con WebView
- Assets: index.html, CSS, Leaflet local, capas OpenTopoMap / OSM / OpenHikingMap
- Modo claro/oscuro
- **Commits:** setup + mapa visible

### Fase 2 – Interacción de mapa y GPS
- Toques: 1º inicio, 2º fin, 3º reinicio
- Marcadores y línea provisional
- Botón centrar en GPS
- Selector de capas
- **Commits:** interacciones + geolocalización

### Fase 3 – Motor de rutas (OpenRouteService)
- WebAppInterface + RoutingEngine
- Alternativas ORS (foot-hiking) → elegir menor ascent
- Dibujo de ruta + métricas (km, D+) + perfil de elevación
- Banner de estado / API key
- **Commits:** routing nativo + UI de ruta

### Fase 4 – GPX e importación
- Selector de archivos GPX
- Parseo de track/ruta, distancia, D+, perfil
- **Commits:** GPX + polish

### Fase 5 – Release y documentación
- APK debug/release
- CREDITS, INSTRUCCIONES
- GitHub Actions para build automático
- **Commits:** APK + CI

## Requisitos
- Android 7.0+ (minSdk 24)
- Clave gratuita de [OpenRouteService](https://openrouteservice.org) en `Config.kt`
- Internet para teselas y rutas

## Compilar
```bash
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/
```

## Uso
Ver `INSTRUCCIONES.txt`.


## Plataformas de rutas y evaluación funcional

La pestaña **Rutas** incluye enlaces externos a [Wikiloc](https://es.wikiloc.com/outdoor-navigation-app), [AllTrails](https://www.alltrails.com/), [komoot](https://www.komoot.com/) y [Mapy.com](https://mapy.com/). Cuando una plataforma permita exportar una ruta como GPX, se puede importar en TerraAltis desde el botón **GPX** del mapa. Las exportaciones y funciones disponibles pueden depender de la cuenta.

La app también muestra una evaluación de funciones de referencia de OsmAnd, Gaia GPS, Locus Map, OruxMaps, Avenza Maps y Organic Maps. La evaluación completa y las prioridades están en [ANALISIS_APPS_REFERENCIA.md](ANALISIS_APPS_REFERENCIA.md).

**Importante:** los enlaces no son una integración de sus APIs ni una sincronización automática. Los tracks comunitarios pueden estar desactualizados y no validan la seguridad de una ruta. TerraAltis no garantiza transitabilidad.


## Exploración de rutas dentro del mapa

El mapa incluye un explorador que consulta senderos cartografiados en OpenStreetMap (Overpass API) y dibuja sus relaciones sobre TerraAltis. También permite importar un GPX descargado de Wikiloc, AllTrails, komoot o Mapy.com sin salir del mapa. La importación usa el selector de archivos del teléfono.

Esto **no** es una integración directa con los catálogos privados ni las APIs comerciales de esos cuatro servicios. La cobertura depende de los datos abiertos disponibles y la conectividad. Las rutas mostradas no están validadas en campo y no garantizan seguridad ni transitabilidad.
