# Evaluación de aplicaciones de referencia para TerraAltis

Fecha: 2026-10-18

## Alcance

Se evalúan funciones que pueden mejorar TerraAltis, no se afirma que los servicios externos compartan APIs públicas adecuadas ni se copian sus mapas, rutas, reseñas o contenido protegido. La integración inicial es mediante enlaces oficiales y flujo GPX cuando el servicio lo permite.

## Comparativa funcional

| Aplicación | Funciones de referencia | Valor para TerraAltis | Decisión |
|---|---|---|---|
| [Wikiloc](https://es.wikiloc.com/outdoor-navigation-app) | Rutas comunitarias, waypoints, fotos y tracks exportables | Comparar rutas conocidas y reutilizar GPX permitido | Enlace en la app; no importar automáticamente el catálogo |
| [AllTrails](https://www.alltrails.com/) | Catálogo de senderos, fotos, reseñas y exportación de rutas según el tipo de contenido/cuenta | Contrastar rutas conocidas y entender la experiencia de descubrimiento | Enlace en la app; no scraping |
| [komoot](https://www.komoot.com/) | Planificación outdoor, perfil de elevación, importación/exportación GPS | Referencia para perfil, pasos de planificación e interoperabilidad GPX | Enlace en la app; sin API/sincronización automática |
| [Mapy.com](https://mapy.com/) | Cartografía y planificación de actividades al aire libre | Referencia de legibilidad cartográfica y planificación | Enlace en la app |
| [OsmAnd](https://osmand.net/) | Mapas offline y funciones topográficas según datos instalados | Importante para funcionamiento sin señal y capas de relieve | Prioridad alta de investigación |
| [Gaia GPS](https://www.gaiagps.com/) | Capas topográficas, mapas de terreno y planificación outdoor | Referencia para combinación de capas y contexto del terreno | Evaluar fuentes, licencia y cobertura |
| [Locus Map](https://www.locusmap.app/) | Gestión GPS, tracks y perfiles de elevación | Referencia para análisis de tracks, D+ y edición | Mejorar validación y métricas del perfil |
| [OruxMaps](https://www.oruxmaps.com/) | Mapas offline y gestión de tracks | Referencia para GPX y mapas locales | Priorizar robustez de GPX; investigar offline |
| [Avenza Maps](https://www.avenzamaps.com/) | Uso de mapas georreferenciados | Puede servir para incorporar cartografía local autorizada | Investigar formatos y licencias antes de implementar |
| [Organic Maps](https://organicmaps.app/) | Mapas offline y navegación sencilla | Referencia para un modo offline ligero | Prioridad alta de investigación |

## Prioridades propuestas

1. **GPX robusto (alta):** probar importación/exportación real con archivos de las plataformas; soportar tracks, rutas, waypoints y elevaciones ausentes; mostrar errores claros.
2. **Offline con licencia (alta):** descargar mapas por zona, administrar almacenamiento y atribución. No reutilizar teselas de terceros sin confirmar las condiciones de uso.
3. **Terreno topográfico (alta):** evaluar curvas de nivel, hillshade y pendiente, separando las capas visuales de las elevaciones usadas por el motor.
4. **Perfil y desnivel (alta):** comparar D+ y D- con tracks de referencia, explicar la incertidumbre y no presentar estimaciones como exactas.
5. **POI y búsqueda (media):** elegir fuentes mantenidas, atribuidas y compatibles con el área de cobertura.
6. **Mapas georreferenciados (media/baja):** estudiar formatos y licencias antes de comprometer una implementación.
7. **Rutas comunitarias (media):** permitir consulta externa y GPX aportado por el usuario; no scraping, copia automática ni asumir que una ruta publicada está actualizada o es segura.

## Límites

- La app abre las páginas oficiales en el navegador del teléfono; no inicia sesión por el usuario ni lee sus bases de datos.
- La disponibilidad de exportación, mapas offline y funciones puede depender del proveedor, región o cuenta.
- Las capas y el catálogo de senderos no son restricciones automáticas del motor de rutas.
- TerraAltis todavía no garantiza transitabilidad ni seguridad; los cauces, barrancos, obstáculos y cambios recientes del terreno pueden estar incompletos.
