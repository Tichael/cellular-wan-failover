package com.example.cellularwanfailover.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Outline icons (24 dp, round strokes) used by the app. Drawn from path data so the app doesn't
 * need the large material-icons-extended library; [androidx.compose.material3.Icon] tints them.
 */
object AppIcons {
    private const val SHIELD = "M12 3l7 3v5c0 4.5-3 8.3-7 10-4-1.7-7-5.5-7-10V6l7-3z"
    private const val BATTERY = "M4 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2zM22 11v2"

    val Shield by lazy { strokeIcon("Shield", SHIELD) }
    val ShieldCheck by lazy { strokeIcon("ShieldCheck", SHIELD, "M9 12l2 2 4-4") }
    val Sliders by lazy {
        strokeIcon(
            "Sliders",
            "M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1",
            "M13 6a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
            "M7 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
            "M15 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"
        )
    }
    val Pulse by lazy { strokeIcon("Pulse", "M3 12h4l3-7 4 14 3-7h4") }
    val Wifi by lazy {
        strokeIcon("Wifi", "M2 9a15 15 0 0 1 20 0", "M5 12.5a10 10 0 0 1 14 0", "M8.5 16a5 5 0 0 1 7 0", "M12 19.5h0.01")
    }
    val Signal by lazy { strokeIcon("Signal", "M5 20v-3M10 20v-7M15 20V9M20 20V4") }
    val Battery by lazy { strokeIcon("Battery", BATTERY) }
    val BatteryCharging by lazy { strokeIcon("BatteryCharging", BATTERY, "M11 9l-2 3h4l-2 3") }
    val Transfer by lazy { strokeIcon("Transfer", "M7 20V4M3 8l4-4 4 4M17 4v16M13 16l4 4 4-4") }
    val Bolt by lazy { strokeIcon("Bolt", "M13 2L4 14h7l-1 8 9-12h-7l1-8z") }
    val Router by lazy {
        strokeIcon(
            "Router",
            "M5 13h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
            "M7 16.5h0.01M11 16.5h0.01M12 13V9",
            "M8.5 6.5a5 5 0 0 1 7 0"
        )
    }
    val Pause by lazy { strokeIcon("Pause", "M10 8v8M14 8v8") }
    val Warning by lazy { strokeIcon("Warning", "M12 3l10 18H2L12 3z", "M12 10v4M12 17.5h0.01") }
    val Check by lazy { strokeIcon("Check", "M5 12l5 5 9-10") }
    val ChevronRight by lazy { strokeIcon("ChevronRight", "M9 6l6 6-6 6") }
    val Trash by lazy { strokeIcon("Trash", "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13") }
    val Copy by lazy {
        strokeIcon("Copy", "M11 9h7a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M5 15V5a1 1 0 0 1 1-1h9")
    }
    val Qr by lazy {
        strokeIcon(
            "Qr",
            "M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
            "M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
            "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z",
            "M14 14h3v3h-3zM21 14v0.01M14 21h0.01M17 21h4v-4"
        )
    }
    val Info by lazy { strokeIcon("Info", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M12 11v5M12 8h0.01") }
    val Stop by lazy { strokeIcon("Stop", "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z") }

    private fun strokeIcon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                )
            }
        }.build()
}
