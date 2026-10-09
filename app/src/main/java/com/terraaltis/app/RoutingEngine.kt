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
    private const val ROWS = 11
    private const val MAX_COLUMNS = 40
    private const val TARGET_METERS = 100.0
    private const val CACHE_NAME = "terrain_elevation_cache_v1"
    private const val CACHE_PREFIX = "e_"
    private const val CACHE_MAX = 5000
    private const val CACHE_TTL = 30L * 24 * 60 * 60 * 1000
    private const val REQUEST_GAP = 1100L
    private const val ASCENT_COST = 8.0
    private const val DESCENT_COST = 0.15
    private const val SLOPE_THRESHOLD = 0.08
    private const val SLOPE_COST = 5.0
    @Volatile private var lastRequestAt = 0L

    private data class GridPoint(val lat: Double, val lng: Double, val east: Double, val north: Double)
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
        val columns=(ceil(direct/TARGET_METERS).toInt()+1).coerceIn(2,MAX_COLUMNS)
        val width=(direct*0.30).coerceIn(250.0,2000.0)
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
        val path=shortestPath(points,elevations,columns)
        val output=JSONArray();val pathElevations=ArrayList<Double>();var meters=0.0
        for(i in path.indices){
            val p=points[path[i]];val z=elevations[path[i]]
            output.put(JSONArray().put(p.lat).put(p.lng).put(z));pathElevations.add(z)
            if(i>0){val prev=points[path[i-1]];meters+=hypot(p.east-prev.east,p.north-prev.north)}
        }
        return RouteResult(output,meters/1000.0,ascentOf(pathElevations))
    }

    private fun shortestPath(points:List<GridPoint>,z:List<Double>,columns:Int):List<Int>{
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
                val horizontal=hypot(b.east-a.east,b.north-a.north);val delta=z[next]-z[cur.index]
                val grade=abs(delta)/horizontal.coerceAtLeast(1.0)
                val cost=cur.cost+horizontal+max(delta,0.0)*ASCENT_COST+max(-delta,0.0)*DESCENT_COST+
                    max(grade-SLOPE_THRESHOLD,0.0)*horizontal*SLOPE_COST
                if(cost<costs[next]){costs[next]=cost;prev[next]=cur.index;queue.add(QueueNode(next,cost))}
            }
        }
        if(!costs[end].isFinite())error("No se pudo conectar el inicio con el destino en la cuadrícula")
        val path=ArrayList<Int>();var cursor=end
        while(cursor!=-1){path.add(cursor);if(cursor==start)break;cursor=prev[cursor]}
        if(path.lastOrNull()!=start)error("No se pudo reconstruir el trazado calculado")
        path.reverse();return path
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

    // SRTM tiene resolución cercana a 90 m; redondear a 0,001° permite reutilizar celdas próximas.
    private fun cacheKey(lat:Double,lng:Double)="${(lat*1000).roundToInt()}_${(lng*1000).roundToInt()}"
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
