package com.sbai.service

import java.io.Reader

/** Bounds decoded text before appending; callers own/close the reader. */
internal fun Reader.readBoundedText(maxChars: Int, checkActive: () -> Unit = {}): String {
    require(maxChars >= 0)
    val result = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        checkActive()
        val count = read(buffer)
        if (count < 0) return result.toString()
        require(count <= maxChars - result.length) { "响应内容超过大小限制" }
        result.append(buffer, 0, count)
    }
}
