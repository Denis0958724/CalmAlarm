package ua.calmalarm.app

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import org.json.JSONArray
import org.json.JSONObject

/**
 * Межі областей, районів і міст з окремими тривогами (res/raw/ua_geo.json, будує tools/geo/build_geo.py).
 * Координати вже спроєктовані: x = довгота·cos(48,5°), y = -широта, у кроках квантування.
 * Ключі збігаються з ідентифікаторами регіонів в API тривог.
 */
class GeoShape(
    val id: String,
    val oblastId: String,
    val name: String,
    val center: Offset,
    val path: Path,
    val bounds: Rect,
)

class UaGeoData(
    val oblasts: Map<String, GeoShape>,
    val districts: Map<String, GeoShape>,
    val cities: Map<String, GeoShape>,
    val attribution: String,
) {
    val districtsByOblast: Map<String, List<GeoShape>> = districts.values.groupBy { it.oblastId }

    /** Форма для будь-якого регіону з API тривог: область, район або місто. */
    fun shapeFor(regionId: String): GeoShape? = oblasts[regionId] ?: districts[regionId] ?: cities[regionId]
}

object UaGeo {
    @Volatile
    private var data: UaGeoData? = null

    fun get(ctx: Context): UaGeoData? = data ?: synchronized(this) {
        data ?: runCatching { load(ctx) }.getOrNull()?.also { data = it }
    }

    private fun load(ctx: Context): UaGeoData {
        val raw = ctx.resources.openRawResource(R.raw.ua_geo).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val o = JSONObject(raw)
        fun parse(obj: JSONObject?, isOblast: Boolean): Map<String, GeoShape> {
            val out = LinkedHashMap<String, GeoShape>()
            obj ?: return out
            obj.keys().forEach { id ->
                val e = obj.getJSONObject(id)
                val c = e.getJSONArray("c")
                val path = Path()
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                val rings = e.getJSONArray("r")
                for (i in 0 until rings.length()) {
                    val r: JSONArray = rings.getJSONArray(i)
                    var x = 0f
                    var y = 0f
                    var k = 0
                    while (k + 1 < r.length()) {
                        if (k == 0) {
                            x = r.getInt(0).toFloat()
                            y = r.getInt(1).toFloat()
                            path.moveTo(x, y)
                        } else {
                            x += r.getInt(k)
                            y += r.getInt(k + 1)
                            path.lineTo(x, y)
                        }
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                        k += 2
                    }
                    path.close()
                }
                out[id] = GeoShape(
                    id = id,
                    oblastId = if (isOblast) id else e.optString("o"),
                    name = e.optString("n", Regions.byId[id]?.short ?: ""),
                    center = Offset(c.getInt(0).toFloat(), c.getInt(1).toFloat()),
                    path = path,
                    bounds = Rect(minX, minY, maxX, maxY),
                )
            }
            return out
        }
        return UaGeoData(
            oblasts = parse(o.optJSONObject("oblasts"), true),
            districts = parse(o.optJSONObject("districts"), false),
            cities = parse(o.optJSONObject("cities"), false),
            attribution = o.optString("attribution"),
        )
    }
}
