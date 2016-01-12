/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.serialization.jvm

import java.util.*

// The maximum possible length of the byte array in the CONSTANT_Utf8_info structure in the bytecode, as per JVMS7 4.4.7
const val MAX_UTF8_INFO_LENGTH = 65535

// 8-to-7-encoded proto messages can never have the zero byte as the first one, as that would mean that the first byte of the actual data
// was either 0 or 1 (0000000x). The first byte of the data is the first byte of the varint which encodes the length of the following proto
// message. The first proto message is the string table and it should contain at least one string, which is impossible to achieve in 1 byte
internal fun isUtf8Encoded(data: Array<String>) =
    data.getOrNull(0)?.getOrNull(0) == 0.toChar()

// Leading bytes are prefixed with 110 in UTF-8
private val LEADING_BYTE_MASK = 0b11000000
// Continuation bytes are prefixed with 10 in UTF-8
private val CONTINUATION_BYTE_MASK = 0b10000000

private val TWO_LOWER_BITS_MASK = 0b00000011
private val SIX_LOWER_BITS_MASK = 0b00111111

fun bytesToStrings(bytes: ByteArray): Array<String> {
    val result = ArrayList<String>(1)
    val buffer = StringBuilder()
    var bytesInBuffer = 0

    buffer.append(0.toChar())
    // Zeros effectively occupy two bytes because each 0x0 is converted to 0x80 0xc0 in Modified UTF-8, see JVMS7 4.4.7
    bytesInBuffer += 2

    for (b in bytes) {
        if (b >= 0) {
            buffer.append(b.toChar())
            bytesInBuffer++
            // Zeros occupy two bytes
            if (b == 0.toByte()) bytesInBuffer++
        }
        else {
            val int = b.toInt() and 0xFF
            val leadingByte = LEADING_BYTE_MASK or (int shr 6)
            val continuationByte = CONTINUATION_BYTE_MASK or (int and SIX_LOWER_BITS_MASK)
            val encodedByte = (leadingByte shl 8) or continuationByte

            buffer.append(encodedByte.toChar())
            bytesInBuffer += 2

            if (bytesInBuffer > MAX_UTF8_INFO_LENGTH) {
                result.add(buffer.substring(0, buffer.length - 1))
                buffer.setLength(0)
                buffer.append(encodedByte.toChar())
                bytesInBuffer = 2
            }
        }

        if (bytesInBuffer == MAX_UTF8_INFO_LENGTH) {
            result.add(buffer.toString())
            buffer.setLength(0)
            bytesInBuffer = 0
        }
    }

    if (!buffer.isEmpty()) {
        result.add(buffer.toString())
    }

    return result.toTypedArray()
}

fun stringsToBytes(strings: Array<String>): ByteArray {
    // Decrement one for the UTF-8 mode marker char
    val resultLength = strings.sumBy { it.length } - 1
    val result = ByteArray(resultLength)

    var end = 0
    for (i in 0..strings.size - 1) {
        val s = strings[i]

        // Skip the mode char
        val start = if (i == 0) 1 else 0

        for (j in start..s.length - 1) {
            val int = s[j].toInt()
            result[end++] = if (int <= 127) {
                int.toByte()
            }
            else {
                val leadingByte = (int and 0xFFFF) shr 8
                val continuationByte = int and 0xFF
                val higherBits = (leadingByte and TWO_LOWER_BITS_MASK) shl 6
                val lowerBits = continuationByte and SIX_LOWER_BITS_MASK
                (higherBits or lowerBits).toByte()
            }
        }
    }

    assert(end == result.size) { "Should have reached the end" }

    return result
}
