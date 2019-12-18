package cash.z.ecc.kotlin.mnemonic

import io.github.novacrypto.bip39.MnemonicGenerator
import io.github.novacrypto.bip39.SeedCalculator
import io.github.novacrypto.bip39.Words
import io.github.novacrypto.bip39.wordlists.English
import java.security.SecureRandom
import javax.inject.Inject

// TODO: either find another library that allows for doing this without strings or modify this code
//  to leverage SecureCharBuffer (which doesn't work well with SeedCalculator.calculateSeed,
//  which expects a string so for that reason, we just use Strings here)
class Mnemonics @Inject constructor(): MnemonicProvider {

    override fun nextSeed(): ByteArray {
        return ByteArray(Words.TWENTY_FOUR.byteLength()).apply {
            SecureRandom().nextBytes(this)
        }
    }

    override fun nextMnemonic(): CharArray {
        return nextMnemonic(nextSeed())
    }

    override fun nextMnemonic(seed: ByteArray): CharArray {
        return StringBuilder().let { builder ->
            MnemonicGenerator(English.INSTANCE).createMnemonic(seed) { c ->
                builder.append(c)
            }
            builder.toString().toCharArray()
        }
    }

    override fun nextMnemonicList(): List<CharArray> {
        return nextMnemonicList(nextSeed())
    }

    override fun nextMnemonicList(seed: ByteArray): List<CharArray> {
        return WordListBuilder().let { builder ->
            MnemonicGenerator(English.INSTANCE).createMnemonic(seed) { c ->
                builder.append(c)
            }
            builder.wordList
        }
    }

    override fun toSeed(mnemonic: CharArray): ByteArray {
        // TODO: either find another library that allows for doing this without strings or modify this code to leverage SecureCharBuffer (which doesn't work well with SeedCalculator.calculateSeed, which expects a string so for that reason, we just use Strings here)
        return SeedCalculator().calculateSeed(mnemonic.toString(), "")
    }

    override fun toWordList(mnemonic: CharArray): List<CharArray> {
        val wordList = mutableListOf<CharArray>()
        var cursor = 0
        repeat(mnemonic.size) { i ->
            val isSpace = mnemonic[i] == ' '
            if (isSpace || i == (mnemonic.size - 1)) {
                val wordSize = i - cursor + if (isSpace) 0 else 1
                wordList.add(CharArray(wordSize).apply {
                    repeat(wordSize) {
                        this[it] = mnemonic[cursor + it]
                    }
                })
                cursor = i + 1
            }
        }
        return wordList
    }

    class WordListBuilder {
        val wordList = mutableListOf<CharArray>()
        fun append(c: CharSequence) {
            if (c[0] != English.INSTANCE.space) addWord(c)
        }

        private fun addWord(c: CharSequence) {
            c.length.let { size ->
                val word = CharArray(size)
                repeat(size) {
                    word[it] = c[it]
                }
                wordList.add(word)
            }
        }
    }
}