package app.quietmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.Position

/** One person on the map. */
data class MapMarker(val id: String, val lat: Double, val lon: Double, val initial: String, val kind: Kind) {
    enum class Kind { ME, ONLINE, OFFLINE }
}

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** Rotation is off, as it was with the Android-only map. */
private val Interactions = MapInteractions(MapInteractions.Standard) {
    camera { rotate { enabled = false } }
}

/**
 * MapLibre with OpenFreeMap tiles, on Android and iOS. The tile server learns roughly which area
 * is on screen, never anyone's exact location; an offline map option is a planned follow-up.
 */
@Composable
fun MapPane(markers: List<MapMarker>, focusKey: Any?, modifier: Modifier = Modifier) {
    val geoJson = remember(markers) { markersToGeoJson(markers) }
    val mapState = rememberMapState(baseStyle = BaseStyle.Uri(STYLE_URL)) {
        val people = rememberGeoJsonSource(GeoJsonData.JsonString(geoJson))
        CircleLayer(
            id = "people-dot",
            source = people,
            radius = const(15.dp),
            color = feature["color"].convertToColor(),
            strokeColor = const(Color.White),
            strokeWidth = const(3.dp),
        )
        SymbolLayer(
            id = "people-initial",
            source = people,
            textField = format(span(feature["initial"].asString())),
            textFont = const(listOf("Noto Sans Bold")),
            textSize = const(13.sp),
            textColor = const(Color.White),
            textAllowOverlap = const(true),
            textIgnorePlacement = const(true),
        )
    }

    // Frame everyone once per circle (and when the first locations arrive).
    var framedFor by remember { mutableStateOf<Any?>(Unit) }
    LaunchedEffect(mapState, focusKey, markers.isEmpty()) {
        if (markers.isEmpty() || framedFor == focusKey) return@LaunchedEffect
        framedFor = focusKey
        if (markers.size == 1) {
            mapState.setCameraPosition(CameraPosition(center = Position(markers[0].lon, markers[0].lat), zoom = 14.0))
        } else {
            val fit = mapState.cameraForCoordinates(
                markers.map { Position(it.lon, it.lat) },
                CameraFit(fitPadding = DpPadding(56.dp, 56.dp, 56.dp, 56.dp)),
            )
            mapState.setCameraPosition(fit.copy(zoom = fit.zoom.coerceAtMost(15.0)))
        }
    }

    MaplibreMap(modifier = modifier, state = mapState, interactions = Interactions)
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
                    put(
                        "color",
                        when (mk.kind) {
                            MapMarker.Kind.ME -> "#1D4ED8"
                            MapMarker.Kind.ONLINE -> "#1F1F1F"
                            MapMarker.Kind.OFFLINE -> "#9E9E9E"
                        },
                    )
                }
            }
        }
    }
}.toString()
