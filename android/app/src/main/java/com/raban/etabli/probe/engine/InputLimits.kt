package com.raban.etabli.probe.engine

import java.io.ByteArrayOutputStream
import java.io.InputStream

fun InputStream.readBounded(limit: Int): ByteArray {
    require(limit > 0)
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit - total + 1))
        if (count == -1) break
        total += count
        require(total <= limit) { "Input exceeds the ${limit / 1024} KiB limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
