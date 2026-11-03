package carmem

import java.security.MessageDigest
import kotlin.math.pow
import kotlin.math.sqrt

interface Embedder { val dim: Int; fun embed(text: String): List<Double> }

private val TOKEN = Regex("[a-z0-9]+")
fun tokenize(text: String): List<String> = TOKEN.findAll(text.lowercase()).map { it.value }.toList()

/** Feature-hashed unigrams + bigrams, L2-normalised. Dependency-free. */
class HashingEmbedder(override val dim: Int = 256) : Embedder {
    private fun slot(tok: String): Pair<Int, Double> {
        val h = MessageDigest.getInstance("SHA-256").digest(tok.toByteArray())
        val idx = ((h[0].toInt() and 0xff) or ((h[1].toInt() and 0xff) shl 8) or ((h[2].toInt() and 0xff) shl 16) or ((h[3].toInt() and 0x7f) shl 24)) % dim
        return idx to if (h[4].toInt() and 1 == 1) 1.0 else -1.0
    }
    override fun embed(text: String): List<Double> {
        val vec = DoubleArray(dim)
        val toks = tokenize(text)
        val feats = toks + toks.zipWithNext { a, b -> "${a}_$b" }
        for (t in feats) { val (i, s) = slot(t); vec[i] += s }
        val norm = sqrt(vec.sumOf { it * it }).let { if (it == 0.0) 1.0 else it }
        return vec.map { it / norm }
    }
}

fun cosine(a: List<Double>, b: List<Double>): Double = if (a.isEmpty() || a.size != b.size) 0.0 else a.indices.sumOf { a[it] * b[it] }

fun keywordOverlap(query: String, text: String): Double {
    val q = tokenize(query).toSet(); if (q.isEmpty()) return 0.0
    return q.intersect(tokenize(text).toSet()).size.toDouble() / q.size
}

fun score(query: String, qvec: List<Double>, text: String, tvec: List<Double>?): Double =
    keywordOverlap(query, text) + (tvec?.let { 0.7 * cosine(qvec, it) } ?: 0.0)

fun recencyWeight(ageDays: Double, halfLifeDays: Double): Double =
    if (halfLifeDays <= 0) 1.0 else 0.5 + 0.5 * 0.5.pow(maxOf(ageDays, 0.0) / halfLifeDays)

fun similarity(a: String, b: String, embedder: Embedder): Double {
    val ta = tokenize(a).toSet(); val tb = tokenize(b).toSet()
    val union = ta.union(tb)
    val jacc = if (union.isEmpty()) 0.0 else ta.intersect(tb).size.toDouble() / union.size
    val cos = maxOf(0.0, cosine(embedder.embed(a), embedder.embed(b)))
    return 0.5 * jacc + 0.5 * cos
}
