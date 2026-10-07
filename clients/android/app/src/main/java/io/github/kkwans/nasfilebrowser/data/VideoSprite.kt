package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject

/** Geometry of the server's existing, bounded full-timeline sprite. */
data class VideoSprite(val number: Int, val columns: Int, val width: Int, val height: Int, val intervalSeconds: Double) {
    init {
        require(number in 1..100 && columns in 1..minOf(number, 10))
        require(width in 1..160 && height in 1..90)
        require(intervalSeconds.isFinite() && intervalSeconds > 0)
    }
    val sheetWidth get() = columns * width
    val sheetHeight get() = ((number + columns - 1) / columns) * height
    fun tileAt(positionMs: Long): Pair<Int, Int> {
        val index = (positionMs.coerceAtLeast(0).toDouble() / 1000 / intervalSeconds)
            .coerceAtMost((number - 1).toDouble()).toInt()
        return (index % columns * width) to (index / columns * height)
    }
    companion object {
        fun from(value: JSONObject): VideoSprite = VideoSprite(value.getInt("number"), value.getInt("column"),
            value.getInt("width"), value.getInt("height"), value.getDouble("interval"))
    }
}
