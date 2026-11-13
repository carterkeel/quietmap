package app.quietmap.ui

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** One person on the map. */
data class MapMarker(val id: String, val lat: Double, val lon: Double, val initial: String, val kind: Kind) {
    enum class Kind { ME, ONLINE, OFFLINE }
}

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val SOURCE = "people"

/**
 * MapLibre with OpenFreeMap tiles. The tile server learns roughly which area is on screen,
 * never anyone's exact location; an offline map option is a planned follow-up.
 */
@Composable
fun MapPane(markers: List<MapMarker>, focusKey: Any?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(Bundle()) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var framedFor by remember { mutableStateOf<Any?>(Unit) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                s.addSource(GeoJsonSource(SOURCE))
                s.addLayer(
                    CircleLayer("people-dot", SOURCE).withProperties(
                        PropertyFactory.circleRadius(15f),
                        PropertyFactory.circleColor(Expression.get("color")),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(3f),
                    ),
                )
                s.addLayer(
                    SymbolLayer("people-initial", SOURCE).withProperties(
                        PropertyFactory.textField(Expression.get("initial")),
                        PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                        PropertyFactory.textSize(13f),
                        PropertyFactory.textColor("#FFFFFF"),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(true),
                    ),
                )
                style = s
            }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(style, markers) {
        val s = style ?: return@LaunchedEffect
        val features = markers.map { mk ->
            Feature.fromGeometry(Point.fromLngLat(mk.lon, mk.lat)).apply {
                addStringProperty("initial", mk.initial)
                addStringProperty(
                    "color",
                    when (mk.kind) {
                        MapMarker.Kind.ME -> "#1D4ED8"
                        MapMarker.Kind.ONLINE -> "#1F1F1F"
                        MapMarker.Kind.OFFLINE -> "#9E9E9E"
                    },
                )
            }
        }
        s.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // Frame everyone once per circle (and when the first locations arrive).
    LaunchedEffect(map, focusKey, markers.isEmpty()) {
        val m = map ?: return@LaunchedEffect
        if (markers.isEmpty() || framedFor == focusKey) return@LaunchedEffect
        framedFor = focusKey
        if (markers.size == 1) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(markers[0].lat, markers[0].lon), 14.0))
        } else {
            val bounds = LatLngBounds.Builder().apply { markers.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 140))
            if (m.cameraPosition.zoom > 15.0) m.moveCamera(CameraUpdateFactory.zoomTo(15.0))
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
