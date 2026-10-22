package com.shahaabapps.obscura.ui.setup

import java.security.SecureRandom
import kotlin.math.log2
import kotlin.math.pow

/**
 * A deliberately simple strength estimate. Multi-word passphrases are scored per word
 * (assuming a large dictionary); everything else by length and character variety.
 * The vault requires [Strength.score] >= [MIN_SCORE] before it can be created.
 */
object PassphraseStrength {
    const val MIN_SCORE = 3

    data class Strength(val score: Int, val bits: Int, val label: String, val crackTime: String)

    fun evaluate(passphrase: String): Strength {
        if (passphrase.isEmpty()) return Strength(0, 0, "Start typing", "")
        val words = passphrase.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val bits = if (words.size >= 3) {
            words.size * BITS_PER_WORD + if (passphrase.any { it.isDigit() || !it.isLetterOrDigit() && !it.isWhitespace() }) 4 else 0
        } else {
            (passphrase.length * log2(poolSize(passphrase).toDouble())).toInt()
        }
        val score = when {
            bits < 40 -> 1
            bits < 55 -> 2
            bits < 70 -> 3
            else -> 4
        }
        val label = when (score) {
            1 -> "Too weak"
            2 -> "Weak"
            3 -> "Good"
            else -> "Excellent"
        }
        return Strength(score, bits, label, crackTime(bits))
    }

    /** Rough offline time assuming an attacker manages 1,000 Argon2id guesses per second. */
    private fun crackTime(bits: Int): String {
        val seconds = 2.0.pow(bits - 1) / 1_000
        return when {
            seconds < 3_600 -> "cracked in minutes"
            seconds < 86_400 * 30 -> "cracked in days"
            seconds < 86_400 * 365.0 * 100 -> "cracked in years"
            seconds < 86_400 * 365.0 * 1e6 -> "centuries to crack"
            else -> "longer than the universe"
        }
    }

    private fun poolSize(s: String): Int {
        var pool = 0
        if (s.any { it.isLowerCase() }) pool += 26
        if (s.any { it.isUpperCase() }) pool += 26
        if (s.any { it.isDigit() }) pool += 10
        if (s.any { !it.isLetterOrDigit() }) pool += 33
        return pool.coerceAtLeast(2)
    }

    /** Assumes words drawn from a ~16k-word vocabulary, as people typically pick them. */
    private const val BITS_PER_WORD = 14

    private val random = SecureRandom()

    /** Seven random words from the built-in 256-word list: 56 bits of real entropy. */
    fun generate(): String = List(7) { WORDS[random.nextInt(WORDS.size)] }.joinToString(" ")

    private val WORDS = listOf(
        "acorn", "adobe", "agate", "alder", "amber", "anchor", "angle", "anvil", "apple", "apron", "arbor", "arrow",
        "aspen", "atlas", "attic", "autumn", "badge", "bagel", "bamboo", "banjo", "barley", "basil", "beacon", "beetle",
        "bell", "birch", "bison", "blade", "blaze", "bloom", "bonsai", "boulder", "bramble", "brass", "breeze", "brick",
        "bronze", "brook", "buckle", "cabin", "cactus", "camel", "candle", "canoe", "canyon", "cargo", "carpet", "cedar",
        "cello", "chalk", "cherry", "chess", "cider", "cinder", "citrus", "clover", "cobalt", "cocoa", "comet", "compass",
        "copper", "coral", "cotton", "cove", "crane", "crater", "crystal", "cumin", "cypress", "dahlia", "daisy", "delta",
        "denim", "desert", "dingo", "dolphin", "domino", "dragon", "drift", "dune", "eagle", "easel", "ebony", "echo",
        "ember", "emerald", "engine", "falcon", "fable", "fennel", "fern", "ferry", "fiddle", "fig", "flame", "flint",
        "forest", "fossil", "fountain", "fox", "galaxy", "garnet", "gazelle", "geyser", "ginger", "glacier", "globe", "goblet",
        "granite", "grape", "gravel", "guitar", "harbor", "harp", "hazel", "heron", "hickory", "honey", "horizon", "husky",
        "igloo", "indigo", "iris", "ivory", "jade", "jasmine", "jelly", "jigsaw", "juniper", "kayak", "kelp", "kettle",
        "kiwi", "koala", "lagoon", "lantern", "larch", "lava", "lemon", "lily", "linen", "lizard", "llama", "lotus",
        "lynx", "magnet", "mango", "maple", "marble", "meadow", "melon", "mesa", "meteor", "mint", "mirror", "mocha",
        "monsoon", "mosaic", "moss", "nectar", "nickel", "nimbus", "nutmeg", "oasis", "oat", "ocean", "olive", "onyx",
        "opal", "orbit", "orchid", "otter", "paddle", "panda", "papaya", "parrot", "pebble", "pepper", "pigeon", "pine",
        "piano", "pixel", "plum", "polar", "poppy", "prairie", "prism", "puffin", "quartz", "quill", "quince", "rabbit",
        "radish", "raven", "reef", "ribbon", "ripple", "river", "robin", "rocket", "saffron", "sage", "salmon", "satin",
        "scarab", "sequoia", "shadow", "shell", "sierra", "silver", "sketch", "sloth", "solar", "sparrow", "spice", "spruce",
        "squid", "summit", "sunset", "swan", "tango", "teal", "thistle", "thunder", "tiger", "timber", "topaz", "tulip",
        "tundra", "turtle", "umber", "valley", "vanilla", "velvet", "violet", "walnut", "walrus", "willow", "wombat", "yarrow",
        "yeti", "zebra", "zenith", "zephyr", "almond", "beaver", "biscuit", "bubble", "cashew", "cobra", "dawn", "dew",
        "elm", "finch", "gecko", "hammock",
    )

    internal val wordCount get() = WORDS.size
    internal val distinctWordCount get() = WORDS.toSet().size
}
