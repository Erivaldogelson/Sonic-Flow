package com.sonicflow

import android.content.Context
import android.media.audiofx.Equalizer
import androidx.compose.runtime.mutableStateListOf

internal class AndroidEqualizer(context: Context) {
    private val preferences = context.getSharedPreferences("sonicflow", Context.MODE_PRIVATE)
    private val effect: Equalizer?
    val levels = mutableStateListOf<Float>()
    val frequencies = mutableListOf<Int>()
    val minDb: Float
    val maxDb: Float
    val error: String?

    init {
        var created: Equalizer? = null
        var issue: String? = null
        try {
            created = Equalizer(0, 0).apply { enabled = true }
        } catch (e: Exception) {
            issue = e.localizedMessage ?: "Este dispositivo não oferece equalização global de áudio"
        }
        effect = created
        error = issue
        val range = created?.bandLevelRange ?: shortArrayOf(0, 0)
        minDb = range[0] / 100f
        maxDb = range[1] / 100f
        created?.let { eq ->
            repeat(eq.numberOfBands.toInt()) { index ->
                frequencies += eq.getCenterFreq(index.toShort()) / 1000
                val saved = preferences.getFloat("eq_band_$index", 0f).coerceIn(minDb, maxDb)
                levels += saved
                eq.setBandLevel(index.toShort(), (saved * 100).toInt().toShort())
            }
        }
    }

    fun setBand(index: Int, db: Float) {
        val value = db.coerceIn(minDb, maxDb)
        if (index !in levels.indices) return
        levels[index] = value
        effect?.setBandLevel(index.toShort(), (value * 100).toInt().toShort())
        preferences.edit().putFloat("eq_band_$index", value).apply()
    }

    fun applyPreset(name: String) {
        val count = levels.size
        if (count == 0) return
        val curve = when (name) {
            "Neutro" -> List(count) { 0f }
            "Graves" -> List(count) { i -> when { i < 2 -> 5f; i < 4 -> 3f; i < 6 -> 1f; else -> 0f } }
            "Voz" -> List(count) { i -> when (i) { 2 -> 1f; 3, 4 -> 4f; 5 -> 3f; 6 -> 1f; else -> 0f } }
            "Rock" -> List(count) { i -> when { i < 2 -> 3f; i < 4 -> 1f; i in 4..5 -> -1f; i < 8 -> 3f; else -> 2f } }
            "Clássico" -> List(count) { i -> when { i < 2 -> 2f; i < 5 -> 0f; i in 5..6 -> -1f; else -> 2f } }
            "Podcast" -> List(count) { i -> when { i < 2 -> -2f; i < 4 -> 1f; i < 7 -> 4f; else -> 1f } }
            "Agudos" -> List(count) { i -> when { i < 3 -> -1f; i < 5 -> 1f; i < 7 -> 3f; else -> 5f } }
            else -> List(count) { 0f }
        }
        curve.forEachIndexed(::setBand)
    }

    fun close() { try { effect?.release() } catch (_: Exception) { } }
}
