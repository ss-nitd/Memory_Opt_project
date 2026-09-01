package com.example.myapplication.runtime

import android.content.Context
import java.text.Normalizer
import java.util.Locale

internal data class TokenizedText(
    val inputIds: IntArray,
    val attentionMask: IntArray,
    val tokenCount: Int
)

internal class WordPieceTokenizer(
    vocabulary: List<String>,
    private val maxSequenceLength: Int
) {
    private val tokenIds = vocabulary.withIndex().associate { (index, token) -> token to index }
    private val paddingId = requireToken("[PAD]")
    private val unknownId = requireToken("[UNK]")
    private val classificationId = requireToken("[CLS]")
    private val separatorId = requireToken("[SEP]")

    fun encode(text: String): TokenizedText {
        val encoded = ArrayList<Int>(maxSequenceLength)
        encoded += classificationId

        for (token in basicTokens(text)) {
            for (piece in wordPieces(token)) {
                if (encoded.size == maxSequenceLength - 1) break
                encoded += tokenIds[piece] ?: unknownId
            }
            if (encoded.size == maxSequenceLength - 1) break
        }

        encoded += separatorId
        val tokenCount = encoded.size
        val inputIds = IntArray(maxSequenceLength) { paddingId }
        val attentionMask = IntArray(maxSequenceLength)

        encoded.forEachIndexed { index, tokenId ->
            inputIds[index] = tokenId
            attentionMask[index] = 1
        }

        return TokenizedText(inputIds, attentionMask, tokenCount)
    }

    internal fun wordPieces(token: String): List<String> {
        if (token.length > MAX_WORD_CHARACTERS) return listOf("[UNK]")

        val pieces = mutableListOf<String>()
        var start = 0

        while (start < token.length) {
            var end = token.length
            var match: String? = null

            while (start < end) {
                val substring = token.substring(start, end)
                val candidate = if (start == 0) substring else "##$substring"
                if (candidate in tokenIds) {
                    match = candidate
                    break
                }
                end--
            }

            if (match == null) return listOf("[UNK]")
            pieces += match
            start = end
        }

        return pieces
    }

    private fun basicTokens(text: String): List<String> {
        val normalized = Normalizer.normalize(
            text.lowercase(Locale.ROOT),
            Normalizer.Form.NFD
        )
        val separated = StringBuilder(normalized.length)

        normalized.forEach { character ->
            when {
                character.isWhitespace() -> separated.append(' ')
                isAccent(character) || isControl(character) -> Unit
                isPunctuation(character) -> separated.append(' ').append(character).append(' ')
                else -> separated.append(character)
            }
        }

        return separated
            .toString()
            .trim()
            .split(WHITESPACE)
            .filter(String::isNotEmpty)
    }

    private fun requireToken(token: String): Int = requireNotNull(tokenIds[token]) {
        "Vocabulary is missing required token $token"
    }

    private fun isAccent(character: Char): Boolean = when (Character.getType(character)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    private fun isControl(character: Char): Boolean = when (Character.getType(character)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt() -> true
        else -> false
    }

    private fun isPunctuation(character: Char): Boolean = when (Character.getType(character)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt() -> true
        else -> false
    }

    companion object {
        private const val VOCABULARY_ASSET = "minilm_vocab.txt"
        private const val MAX_WORD_CHARACTERS = 100
        private val WHITESPACE = Regex("\\s+")

        fun fromAssets(context: Context, maxSequenceLength: Int): WordPieceTokenizer {
            val vocabulary = context.assets.open(VOCABULARY_ASSET).bufferedReader().use {
                it.readLines()
            }
            return WordPieceTokenizer(vocabulary, maxSequenceLength)
        }
    }
}
