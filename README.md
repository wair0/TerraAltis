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
