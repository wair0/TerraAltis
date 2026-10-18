package com.terraaltis.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

data class RouteResult(val points: JSONArray, val km: Double, val ascent: Double)
data class RouteWaypoint(val lat: Double, val lng: Double)

/** Calcula cada tramo entre waypoints; almacena elevaciones y usa servicios alternativos si hay HTTP 429. */
object RoutingEngine {
    private const val OPEN_TOPO_DATA = "https://api.opentopodata.org/v1/srtm30m"
    private const val OPEN_TOPO_DATA_ASTER = "https://api.opentopodata.org/v1/aster30m"
    private const val OPEN_ELEVATION = "https://api.open-elevation.com/api/v1/lookup"
    private const val MAX_BATCH = 100
    private const val ROWS = 13
    private const val MAX_COLUMNS = 90
    private const val TARGET_METERS = 55.0
    private const val CACHE_NAME = "terrain_elevation_cache_v2"
    private const val CACHE_PREFIX = "e2_"
    private const val CACHE_MAX = 5000
    private const val CACHE_TTL = 30L * 24 * 60 * 60 * 1000
    private const val REQUEST_GAP = 1100L
    private const val ASCENT_COST = 8.0
    private const val DESCENT_COST = 2.0
    private const val SLOPE_COST = 5.0
    @Volatile private var lastRequestAt = 0L

    private data class GridPoint(val lat: Double, val lng: Double, val east: Double, val north: Double)
    private data class BarrierSegment(val ax: Double, val ay: Double, val bx: Double, val by: Double)
    private data class QueueNode(val index: Int, val cost: Double)

    fun bestRouteThroughWaypoints(context: Context, waypoints: List<RouteWaypoint>): RouteResult {
        require(waypoints.size in 2..30) { "La ruta debe tener entre 2 y 30 waypoints" }
        val combined = JSONArray()
        var km = 0.0
        var ascent = 0.0
        for (i in 0 until waypoints.size - 1) {
            val a = waypoints[i]; val b = waypoints[i + 1]
            val part = bestRoute(context, a.lat, a.lng, b.lat, b.lng)
            for (j in 0 until part.points.length()) {
                if (i > 0 && j == 0) continue
                combined.put(part.points.getJSONArray(j))
            }
            km += part.km; ascent += part.ascent
        }
        return RouteResult(combined, km, ascent)
    }

    private fun bestRoute(context: Context, sLat: Double, sLng: Double, eLat: Double, eLng: Double): RouteResult {
        require(listOf(sLat,sLng,eLat,eLng).all { it.isFinite() }) { "Las coordenadas de un waypoint no son válidas" }
        require(sLat in -90.0..90.0 && eLat in -90.0..90.0 && sLng in -180.0..180.0 && eLng in -180.0..180.0) { "Las coordenadas están fuera de rango" }
        require(abs(sLat-eLat)>0.000001 || abs(sLng-eLng)>0.000001) { "Dos waypoints consecutivos deben ser distintos" }
        val meanLat=Math.toRadians((sLat+eLat)/2.0)
        val endEast=(eLng-sLng)*111320.0*cos(meanLat)
        val endNorth=(eLat-sLat)*111320.0
        val direct=hypot(endEast,endNorth)
        require(direct>=10.0) { "Dos waypoints están a menos de 10 metros; separalos un poco" }
        val requiredColumns=ceil(direct/TARGET_METERS).toInt()+1
        require(requiredColumns<=MAX_COLUMNS) {
            "Este tramo supera la distancia que TerraAltis puede analizar con la resolución conservadora actual (~4,8 km por tramo). Añadí uno o más waypoints intermedios; no se generó una ruta de baja resolución."
        }
        val columns=requiredColumns.coerceAtLeast(2)
        val width=(direct*0.25).coerceIn(300.0,2500.0)
        val alongEast=endEast/direct;val alongNorth=endNorth/direct
        val crossEast=-alongNorth;val crossNorth=alongEast
        val points=ArrayList<GridPoint>(columns*ROWS)
        for (column in 0 until columns) {
            val t=column.toDouble()/(columns-1);val centerEast=endEast*t;val centerNorth=endNorth*t
            for (row in 0 until ROWS) {
                val offset=(row-ROWS/2).toDouble()/(ROWS/2).toDouble()*width
                val east=centerEast+crossEast*offset;val north=centerNorth+crossNorth*offset
                val lat=sLat+north/111320.0
                val cosLat=cos(meanLat).let { if(abs(it)<0.01) if(it<0)-0.01 else 0.01 else it }
                val lng=sLng+east/(111320.0*cosLat)
                points.add(GridPoint(lat,lng,east,north))
            }
        }
        val elevations=getElevations(context,points)
        // Verificar cursos de agua oficiales en el corredor; sin esa verificación no se traza.
        val waterBarriers=fetchWaterBarriers(points,sLat,sLng)
        val path=shortestPath(points,elevations,columns,waterBarriers)
        val output=JSONArray();val pathElevations=ArrayList<Double>();var meters=0.0
        for(i in path.indices){
            val p=points[path[i]];val z=elevations[path[i]]
            output.put(JSONArray().put(p.lat).put(p.lng).put(z));pathElevations.add(z)
            if(i>0){val prev=points[path[i-1]];meters+=hypot(p.east-prev.east,p.north-prev.north)}
        }
        return RouteResult(output,meters/1000.0,ascentOf(pathElevations))
    }

    private fun shortestPath(points:List<GridPoint>,z:List<Double>,columns:Int,barriers:List<BarrierSegment>):List<Int>{
        // Política conservadora: no relajar el límite si no hay una ruta transitable según el DEM.
        // El modelo de elevación puede omitir paredes y quebradas estrechas; ante dudas se rechaza el trazado.
        val maxGrade=kotlin.math.tan(Math.toRadians(18.0))
        shortestPathWithSlopeLimit(points,z,columns,maxGrade,barriers)?.let{return it}
        error("No se encontró un corredor dentro del límite conservador de pendiente (18°). No se dibujó una ruta potencialmente peligrosa. Probá otros waypoints o verificá el terreno con cartografía de mayor resolución.")
    }

    private fun shortestPathWithSlopeLimit(points:List<GridPoint>,z:List<Double>,columns:Int,maxGrade:Double,barriers:List<BarrierSegment>):List<Int>?{
        val count=points.size;val start=ROWS/2;val end=(columns-1)*ROWS+ROWS/2
        val costs=DoubleArray(count){Double.POSITIVE_INFINITY};val prev=IntArray(count){-1}
        val queue=PriorityQueue<QueueNode>(compareBy{it.cost});costs[start]=0.0;queue.add(QueueNode(start,0.0))
        while(queue.isNotEmpty()){
            val cur=queue.poll();if(cur.cost!=costs[cur.index])continue;if(cur.index==end)break
            val col=cur.index/ROWS;val row=cur.index%ROWS
            for(dc in -1..1)for(dr in -1..1){
                if(dc==0&&dr==0)continue
                val nc=col+dc;val nr=row+dr;if(nc !in 0 until columns||nr !in 0 until ROWS)continue
                val next=nc*ROWS+nr;val a=points[cur.index];val b=points[next]
                val horizontal=hypot(b.east-a.east,b.north-a.north).coerceAtLeast(1.0)
                val delta=z[next]-z[cur.index];val grade=abs(delta)/horizontal
                if(grade>maxGrade)continue
                if(crossesWater(a.east,a.north,b.east,b.north,barriers))continue
                // No permitir atajos diagonales junto a un desnivel que bloquearía el paso ortogonal.
                if(dc!=0&&dr!=0){
                    val sideA=nc*ROWS+row
                    val sideB=col*ROWS+nr
                    if(!edgeWithinSlope(cur.index,sideA,points,z,maxGrade) ||
                       !edgeWithinSlope(cur.index,sideB,points,z,maxGrade) ||
                       !edgeWithinSlope(sideA,next,points,z,maxGrade) ||
                       !edgeWithinSlope(sideB,next,points,z,maxGrade)) continue
                }
                // Penalizar tanto la subida como la bajada y aumentar el coste cerca de pendientes fuertes.
                val slopePenalty=horizontal*SLOPE_COST*grade*grade
                val cost=cur.cost+horizontal+max(delta,0.0)*ASCENT_COST+
                    max(-delta,0.0)*DESCENT_COST+slopePenalty
                if(cost<costs[next]){costs[next]=cost;prev[next]=cur.index;queue.add(QueueNode(next,cost))}
            }
        }
        if(!costs[end].isFinite())return null
        val path=ArrayList<Int>();var cursor=end
        while(cursor!=-1){path.add(cursor);if(cursor==start)break;cursor=prev[cursor]}
        if(path.lastOrNull()!=start)return null
        path.reverse();return path
    }
    /**
     * Consulta cursos perennes e intermitentes del servicio oficial ANIDA/IGN.
     * Si el servicio no responde o los datos están incompletos, se detiene el cálculo.
     */
    private fun fetchWaterBarriers(points:List<GridPoint>,originLat:Double,originLng:Double):List<BarrierSegment>{
        val minLat=(points.minOf{it.lat}-0.0002).coerceAtLeast(-90.0)
        val maxLat=(points.maxOf{it.lat}+0.0002).coerceAtMost(90.0)
        val minLng=(points.minOf{it.lng}-0.0002).coerceAtLeast(-180.0)
        val maxLng=(points.maxOf{it.lng}+0.0002).coerceAtMost(180.0)
        val envelope=JSONObject().put("xmin",minLng).put("ymin",minLat).put("xmax",maxLng).put("ymax",maxLat).put("spatialReference",JSONObject().put("wkid",4326))
        val barriers=ArrayList<BarrierSegment>()
        try{
            for(layer in listOf(0,1)){
                var offset=0
                while(true){
                    val query=buildString{
                        append("where=1%3D1")
                        append("&geometry=").append(URLEncoder.encode(envelope.toString(),"UTF-8"))
                        append("&geometryType=esriGeometryEnvelope&inSR=4326&outSR=4326")
                        append("&spatialRel=esriSpatialRelIntersects&outFields=*&returnGeometry=true&f=geojson")
                        append("&resultOffset=").append(offset).append("&resultRecordCount=1000")
                    }
                    val json=requestJson(URL("https://ide.ign.gob.ar/geoservicios/rest/services/ANIDA/Hidrografia/MapServer/$layer/query?$query"),"GET",null,"Hidrografía oficial IGN")
                    if(json.has("error"))error(json.optJSONObject("error")?.optString("message") ?: "respuesta de error")
                    val features=json.optJSONArray("features")?:error("El IGN no devolvió geometrías GeoJSON")
                    for(i in 0 until features.length()){
                        val geometry=features.optJSONObject(i)?.optJSONObject("geometry")?:continue
                        val coordinates=geometry.optJSONArray("coordinates")?:continue
                        when(geometry.optString("type")){
                            "LineString" -> addGeoJsonLine(coordinates,originLat,originLng,barriers)
                            "MultiLineString" -> for(j in 0 until coordinates.length()) coordinates.optJSONArray(j)?.let{addGeoJsonLine(it,originLat,originLng,barriers)}
                        }
                    }
                    if(features.length()<1000)break
                    offset+=features.length()
                    if(offset>=10000)error("La hidrografía del corredor supera el límite de consulta; añadí waypoints intermedios")
                }
            }
        }catch(e:Exception){
            error("No se pudo verificar la hidrografía oficial del IGN; no se generó una ruta sin esa comprobación. ${e.message ?: "Servicio no disponible"}")
        }
        return barriers
    }

    private fun addGeoJsonLine(coords:JSONArray,originLat:Double,originLng:Double,out:MutableList<BarrierSegment>){
        val meanLat=Math.toRadians(originLat)
        val pts=ArrayList<Pair<Double,Double>>()
        for(i in 0 until coords.length()){
            val p=coords.optJSONArray(i)?:continue
            if(p.length()<2)continue
            val lng=p.optDouble(0,Double.NaN);val lat=p.optDouble(1,Double.NaN)
            if(!lat.isFinite()||!lng.isFinite())continue
            pts.add(((lng-originLng)*111320.0*cos(meanLat)) to ((lat-originLat)*111320.0))
        }
        for(i in 1 until pts.size){val a=pts[i-1];val b=pts[i];out.add(BarrierSegment(a.first,a.second,b.first,b.second))}
    }

    private fun crossesWater(ax:Double,ay:Double,bx:Double,by:Double,barriers:List<BarrierSegment>):Boolean{
        val minX=minOf(ax,bx)-12.0;val maxX=maxOf(ax,bx)+12.0
        val minY=minOf(ay,by)-12.0;val maxY=maxOf(ay,by)+12.0
        for(s in barriers){
            if(maxOf(s.ax,s.bx)<minX||minOf(s.ax,s.bx)>maxX||maxOf(s.ay,s.by)<minY||minOf(s.ay,s.by)>maxY)continue
            if(segmentsIntersect(ax,ay,bx,by,s.ax,s.ay,s.bx,s.by))return true
            if(pointSegmentDistance(ax,ay,s.ax,s.ay,s.bx,s.by)<12.0 || pointSegmentDistance(bx,by,s.ax,s.ay,s.bx,s.by)<12.0 || pointSegmentDistance(s.ax,s.ay,ax,ay,bx,by)<12.0 || pointSegmentDistance(s.bx,s.by,ax,ay,bx,by)<12.0)return true
        }
        return false
    }

    private fun segmentsIntersect(ax:Double,ay:Double,bx:Double,by:Double,cx:Double,cy:Double,dx:Double,dy:Double):Boolean{
        fun orient(px:Double,py:Double,qx:Double,qy:Double,rx:Double,ry:Double)=(qx-px)*(ry-py)-(qy-py)*(rx-px)
        val o1=orient(ax,ay,bx,by,cx,cy);val o2=orient(ax,ay,bx,by,dx,dy)
        val o3=orient(cx,cy,dx,dy,ax,ay);val o4=orient(cx,cy,dx,dy,bx,by)
        return ((o1<=0&&o2>=0)||(o1>=0&&o2<=0))&&((o3<=0&&o4>=0)||(o3>=0&&o4<=0))
    }

    private fun pointSegmentDistance(px:Double,py:Double,ax:Double,ay:Double,bx:Double,by:Double):Double{
        val dx=bx-ax;val dy=by-ay;val length=dx*dx+dy*dy
        if(length<=1e-9)return hypot(px-ax,py-ay)
        val t=(((px-ax)*dx+(py-ay)*dy)/length).coerceIn(0.0,1.0)
        return hypot(px-(ax+t*dx),py-(ay+t*dy))
    }
    private fun edgeWithinSlope(aIndex:Int,bIndex:Int,points:List<GridPoint>,z:List<Double>,maxGrade:Double):Boolean{
        val a=points[aIndex];val b=points[bIndex]
        val distance=hypot(b.east-a.east,b.north-a.north).coerceAtLeast(1.0)
        return abs(z[bIndex]-z[aIndex])/distance<=maxGrade
    }

    private fun getElevations(context:Context,points:List<GridPoint>):List<Double>{
        val prefs=context.applicationContext.getSharedPreferences(CACHE_NAME,Context.MODE_PRIVATE);val now=System.currentTimeMillis()
        val values=arrayOfNulls<Double>(points.size);val missing=LinkedHashMap<String,MutableList<Int>>()
        points.forEachIndexed{i,p->
            val key=cacheKey(p.lat,p.lng);val parts=prefs.getString(CACHE_PREFIX+key,null)?.split('|')
            val elevation=parts?.getOrNull(0)?.toDoubleOrNull();val timestamp=parts?.getOrNull(1)?.toLongOrNull()
            if(elevation!=null&&timestamp!=null&&now-timestamp<CACHE_TTL)values[i]=elevation
            else missing.getOrPut(key){mutableListOf()}.add(i)
        }
        if(missing.isNotEmpty()){
            val samples=missing.keys.map{key->points[missing.getValue(key).first()]}
            val fetched=fetchElevations(samples);val editor=prefs.edit()
            missing.keys.forEachIndexed{i,key->
                editor.putString(CACHE_PREFIX+key,"${fetched[i]}|$now")
                missing.getValue(key).forEach{values[it]=fetched[i]}
            }
            editor.apply();trimCache(prefs)
        }
        return values.mapIndexed{i,v->v?:error("Falta la elevación del punto ${i+1}")}
    }

    // Clave más fina (~25–30 m) para no reutilizar una misma elevación en un área de ~100 m.
    private fun cacheKey(lat:Double,lng:Double)="${(lat*4000).roundToInt()}_${(lng*4000).roundToInt()}"
    private fun trimCache(prefs:SharedPreferences){
        val keys=prefs.all.keys.filter{it.startsWith(CACHE_PREFIX)};if(keys.size<=CACHE_MAX)return
        val oldest=keys.mapNotNull{k->prefs.getString(k,null)?.substringAfter('|')?.toLongOrNull()?.let{ k to it }}
            .sortedBy{it.second}.take(keys.size-CACHE_MAX)
        val edit=prefs.edit();oldest.forEach{edit.remove(it.first)};edit.apply()
    }

    private fun fetchElevations(points:List<GridPoint>):List<Double>{
        val out=ArrayList<Double>(points.size)
        for(batch in points.chunked(MAX_BATCH)){
            var result:List<Double>?=null
            var srtmFailure:Exception?=null
            var asterFailure:Exception?=null
            // OpenTopoData SRTM 30 m es la fuente principal: servicio abierto y sin clave.
            try {
                result = fetchOpenTopoData(batch, OPEN_TOPO_DATA, "OpenTopoData SRTM30m")
            } catch (e:Exception) {
                srtmFailure=e
            }
            // ASTER ofrece una segunda fuente de relieve global si SRTM falla o no cubre una zona.
            if(result==null) {
                try {
                    result = fetchOpenTopoData(batch, OPEN_TOPO_DATA_ASTER, "OpenTopoData ASTER30m")
                } catch (e:Exception) {
                    asterFailure=e
                }
            }
            // Último recurso independiente. No se usa Open-Meteo.
            if(result==null) {
                try {
                    val locations=JSONArray()
                    batch.forEach { locations.put(JSONObject().put("latitude",it.lat).put("longitude",it.lng)) }
                    val json=requestJson(URL(OPEN_ELEVATION),"POST",JSONObject().put("locations",locations).toString(),"Open-Elevation")
                    val arr=json.optJSONArray("results")?:error("Open-Elevation no devolvió resultados")
                    if(arr.length()!=batch.size)error("Open-Elevation devolvió una cantidad inesperada")
                    result=(0 until arr.length()).map { i ->
                        val item=arr.getJSONObject(i)
                        if(item.isNull("elevation")) error("Open-Elevation no tiene elevación para un punto")
                        item.getDouble("elevation")
                    }
                } catch (e:Exception) {
                    error("Fallaron los servicios de elevación. SRTM30m: ${srtmFailure?.message}. ASTER30m: ${asterFailure?.message}. Open-Elevation: ${e.message}")
                }
            }
            out.addAll(result!!)
        }
        return out
    }

    private fun fetchOpenTopoData(batch:List<GridPoint>, endpoint:String, service:String):List<Double> {
        val locations=URLEncoder.encode(batch.joinToString("|") { "${it.lat},${it.lng}" }, "UTF-8")
        val json=requestJson(URL("$endpoint?locations=$locations&interpolation=bilinear"),"GET",null,service)
        val arr=json.optJSONArray("results")?:error("$service no devolvió resultados")
        if(arr.length()!=batch.size)error("$service devolvió una cantidad inesperada")
        return (0 until arr.length()).map { i ->
            val item=arr.getJSONObject(i)
            if(item.isNull("elevation")) error("$service no tiene elevación para un punto")
            item.getDouble("elevation")
        }
    }

    @Synchronized private fun requestJson(url:URL,method:String,body:String?,service:String):JSONObject{
        val elapsed=System.currentTimeMillis()-lastRequestAt
        if(elapsed<REQUEST_GAP)Thread.sleep(REQUEST_GAP-elapsed)
        lastRequestAt=System.currentTimeMillis()
        val conn=url.openConnection() as HttpURLConnection
        try{
            conn.requestMethod=method;conn.connectTimeout=12000;conn.readTimeout=20000
            conn.setRequestProperty("Accept","application/json")
            conn.setRequestProperty("User-Agent","TerraAltis/1.0 (Android terrain route planner)")
            if(body!=null){conn.doOutput=true;conn.setRequestProperty("Content-Type","application/json; charset=utf-8");conn.outputStream.bufferedWriter(Charsets.UTF_8).use{it.write(body)}}
            val code=conn.responseCode;val stream=if(code in 200..299)conn.inputStream else conn.errorStream
            val text=stream?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}.orEmpty()
            if(code !in 200..299)error("$service HTTP $code"+if(text.isNotBlank())": ${text.take(180)}" else "")
            return JSONObject(text)
        }finally{conn.disconnect()}
    }

    private fun ascentOf(z:List<Double>):Double{
        if(z.size<2)return 0.0
        var gain=0.0;var reference=z.first()
        for(e in z.drop(1)){val delta=e-reference;if(delta>=3.0){gain+=delta;reference=e}else if(delta<=-3.0)reference=e}
        return gain
    }
}
