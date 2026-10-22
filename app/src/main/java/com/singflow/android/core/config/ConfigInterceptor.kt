package com.singflow.android.core.config

/**
 * 配置拦截器扩展点。
 *
 * 每个拦截器只负责 sing-box 配置中的一个段落，新增能力（FakeIP、规则集、实验特性）时
 * 实现本接口并注册到 [SingBoxConfigPipeline.defaultInterceptors] 即可，无需修改既有代码。
 */
interface ConfigInterceptor {
    /** 拦截器名称，便于诊断。 */
    val name: String

    /** 对上下文进行原地修改。 */
    fun intercept(context: SingBoxConfigContext)
}
