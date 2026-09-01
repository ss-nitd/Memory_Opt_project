package com.example.myapplication.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WordPieceTokenizerTest {
    private val vocabulary = listOf(
        "[PAD]",
        "[UNK]",
        "[CLS]",
        "[SEP]",
        "hello",
        ",",
        "play",
        "##ing"
    )

    @Test
    fun encodeAppliesBasicAndWordPieceTokenization() {
        val encoded = WordPieceTokenizer(vocabulary, maxSequenceLength = 8)
            .encode("Héllo, playing mystery")

        assertArrayEquals(intArrayOf(2, 4, 5, 6, 7, 1, 3, 0), encoded.inputIds)
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 1, 1, 1, 0), encoded.attentionMask)
        assertEquals(7, encoded.tokenCount)
    }

    @Test
    fun encodeTruncatesContentButRetainsSeparator() {
        val encoded = WordPieceTokenizer(vocabulary, maxSequenceLength = 4)
            .encode("hello hello hello hello")

        assertArrayEquals(intArrayOf(2, 4, 4, 3), encoded.inputIds)
        assertEquals(4, encoded.tokenCount)
    }
}
