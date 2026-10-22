package com.singflow.android.core.parser

import com.singflow.android.core.model.ProxyNode

/**
 * 单协议解析器扩展点。
 *
 * 新增协议时只需实现该接口，并注册到 [UniversalImportManager.defaultParsers]。
 */
interface NodeLinkParser {
    /** 解析器名称，便于日志与错误提示。 */
    val name: String

    /** 该解析器负责的协议头（小写，不含 `://`）。 */
    val schemes: List<String>

    /**
     * 判断是否可以处理该链接。
     */
    fun canParse(link: String): Boolean {
        val normalized = link.trim().lowercase()
        return schemes.any { scheme -> normalized.startsWith("$scheme://") }
    }

    /**
     * 解析单条链接。
     *
     * @return 解析失败返回 null
     */
    fun parse(link: String): ProxyNode?
}
