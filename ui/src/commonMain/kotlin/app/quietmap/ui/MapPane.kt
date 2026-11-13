package app.quietmap.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/** One person on the map. [color] is a hex color such as "#1251D3"; offline people are drawn grey. */
data class MapMarker(val id: String, val lat: Double, val lon: Double, val initial: String, val color: String, val kind: Kind) {
    enum class Kind { ME, ONLINE, OFFLINE }
}

/** Ask the map to fly to a point. [seq] makes repeated requests for the same place still move. */
data class MapFocus(val lat: Double, val lon: Double, val seq: Int)

/** City-level zoom used when the map first opens. */
private const val CITY_ZOOM = 12.5

/** Street-level zoom used when centering on someone. */
private const val STREET_ZOOM = 15.5

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** Text the map must show for OpenFreeMap / OpenMapTiles / OpenStreetMap data. */
const val MAP_ATTRIBUTION = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"

/** Rotation is off, as it was with the Android-only map. */
private val Interactions = MapInteractions(MapInteractions.Standard) {
    camera { rotate { enabled = false } }
}

/**
 * MapLibre with OpenFreeMap tiles, on Android and iOS. The tile server learns roughly which area
 * is on screen, never anyone's exact location.
 *
 * - The map stays composed while other screens slide over it, so it keeps its position.
 * - It opens at city level around you, and [focus] glides the camera to a point (yourself for the
 *   recenter button, or a person you tapped).
 * - People close together merge into one bubble with a count, and separate as you zoom in.
 * - [bottomInset] keeps centred points above the people sheet.
 * - The built-in scale bar, compass and logo are off; [MAP_ATTRIBUTION] is shown by the home screen.
 */
@Composable
fun MapPane(markers: List<MapMarker>, focusKey: Any?, focus: MapFocus?, bottomInset: Dp, modifier: Modifier = Modifier) {
    val geoJson = remember(markers) { markersToGeoJson(markers) }
    val mapState = rememberMapState(baseStyle = BaseStyle.Uri(STYLE_URL)) {
        val people = rememberGeoJsonSource(GeoJsonData.JsonString(geoJson)) {
            cluster = true
            clusterRadius = 36
            clusterMaxZoom = 16
        }
        val isCluster = feature.has("point_count")
        // Merged bubble for people standing close together.
        CircleLayer(
            id = "people-cluster",
            source = people,
            filter = isCluster,
            radius = const(18.dp),
            color = const(Palette.Ultramarine),
            strokeColor = const(Color.White),
            strokeWidth = const(3.dp),
        )
        SymbolLayer(
            id = "people-cluster-count",
            source = people,
            filter = isCluster,
            textField = format(span(feature["point_count_abbreviated"].asString())),
            textFont = const(listOf("Noto Sans Bold")),
            textSize = const(13.sp),
            textColor = const(Color.White),
            textAllowOverlap = const(true),
            textIgnorePlacement = const(true),
        )
        // One dot per person.
        CircleLayer(
            id = "people-dot",
            source = people,
            filter = !isCluster,
            radius = const(15.dp),
            color = feature["color"].convertToColor(),
            strokeColor = const(Color.White),
            strokeWidth = const(3.dp),
        )
        SymbolLayer(
            id = "people-initial",
            source = people,
            filter = !isCluster,
            textField = format(span(feature["initial"].asString())),
            textFont = const(listOf("Noto Sans Bold")),
            textSize = const(13.sp),
            textColor = const(Color.White),
            textAllowOverlap = const(true),
            textIgnorePlacement = const(true),
        )
    }

    // Open once per circle at city level around me (or whoever has a location first).
    var framedFor by remember { mutableStateOf<Any?>(Unit) }
    LaunchedEffect(mapState, focusKey, markers.isEmpty()) {
        if (markers.isEmpty() || framedFor == focusKey) return@LaunchedEffect
        framedFor = focusKey
        val center = markers.firstOrNull { it.kind == MapMarker.Kind.ME } ?: markers.first()
        mapState.setCameraPosition(CameraPosition(center = Position(center.lon, center.lat), zoom = CITY_ZOOM))
    }

    // Glide to a requested point.
    LaunchedEffect(mapState, focus) {
        val f = focus ?: return@LaunchedEffect
        mapState.animateCamera(CameraUpdate(center = Position(f.lon, f.lat), zoom = STREET_ZOOM))
    }

    MaplibreMap(
        modifier = modifier,
        state = mapState,
        viewportInsets = PaddingValues(bottom = bottomInset),
        interactions = Interactions,
        overlay = {},
    )
}

private fun markersToGeoJson(markers: List<MapMarker>): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        markers.forEach { mk ->
            addJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "Point")
                    putJsonArray("coordinates") { add(mk.lon); add(mk.lat) }
                }
                putJsonObject("properties") {
                    put("initial", mk.initial)
                    put("color", if (mk.kind == MapMarker.Kind.OFFLINE) "#9E9E9E" else mk.color)
                }
            }
        }
    }
}.toString()
