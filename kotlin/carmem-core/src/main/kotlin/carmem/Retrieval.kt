package carmem

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.pow
import kotlin.math.sqrt

interface Embedder { val dim: Int; fun embed(text: String): DoubleArray }

private val TOKEN = Regex("[a-z0-9]+")
fun tokenize(text: String): List<String> = TOKEN.findAll(text.lowercase()).map { it.value }.toList()

/** Feature-hashed unigrams + bigrams, L2-normalised. Dependency-free. */
class HashingEmbedder(override val dim: Int = 256) : Embedder {
    override fun embed(text: String): DoubleArray {
        val vec = DoubleArray(dim)
        val toks = tokenize(text)
        val md = MessageDigest.getInstance("SHA-256")
        fun add(tok: String) {
            val h = md.digest(tok.toByteArray()) // digest() resets, so one instance serves the whole text
            val idx = ((h[0].toInt() and 0xff) or ((h[1].toInt() and 0xff) shl 8) or ((h[2].toInt() and 0xff) shl 16) or ((h[3].toInt() and 0x7f) shl 24)) % dim
            vec[idx] += if (h[4].toInt() and 1 == 1) 1.0 else -1.0
        }
        for (t in toks) add(t)
        for (i in 0 until toks.size - 1) add("${toks[i]}_${toks[i + 1]}")
        var sq = 0.0
        for (v in vec) sq += v * v
        val norm = sqrt(sq).let { if (it == 0.0) 1.0 else it }
        for (i in vec.indices) vec[i] /= norm
        return vec
    }
}

fun cosine(a: DoubleArray, b: DoubleArray): Double {
    if (a.isEmpty() || a.size != b.size) return 0.0
    var s = 0.0
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/** Vectors are stored as little-endian float64 blobs: exact, and decoded without parsing. */
fun packVector(v: DoubleArray): ByteArray = ByteBuffer.allocate(v.size * 8).order(ByteOrder.LITTLE_ENDIAN).also { it.asDoubleBuffer().put(v) }.array()
fun unpackVector(b: ByteArray): DoubleArray = DoubleArray(b.size / 8).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(it) }

fun keywordOverlap(query: Set<String>, text: String): Double {
    if (query.isEmpty()) return 0.0
    val t = tokenize(text).toSet()
    return query.count { it in t }.toDouble() / query.size
}
fun keywordOverlap(query: String, text: String): Double = keywordOverlap(tokenize(query).toSet(), text)

fun score(query: Set<String>, qvec: DoubleArray, text: String, tvec: DoubleArray?): Double =
    keywordOverlap(query, text) + (tvec?.let { 0.7 * cosine(qvec, it) } ?: 0.0)
fun score(query: String, qvec: DoubleArray, text: String, tvec: DoubleArray?): Double = score(tokenize(query).toSet(), qvec, text, tvec)

fun recencyWeight(ageDays: Double, halfLifeDays: Double): Double =
    if (halfLifeDays <= 0) 1.0 else 0.5 + 0.5 * 0.5.pow(maxOf(ageDays, 0.0) / halfLifeDays)

/** Similarity of a text already tokenised and embedded (`ta`, `va`) to `b`. */
fun similarity(ta: Set<String>, va: DoubleArray, b: String, embedder: Embedder): Double {
    val tb = tokenize(b).toSet()
    val union = ta.union(tb)
    val jacc = if (union.isEmpty()) 0.0 else ta.intersect(tb).size.toDouble() / union.size
    val cos = maxOf(0.0, cosine(va, embedder.embed(b)))
    return 0.5 * jacc + 0.5 * cos
}
fun similarity(a: String, b: String, embedder: Embedder): Double = similarity(tokenize(a).toSet(), embedder.embed(a), b, embedder)
