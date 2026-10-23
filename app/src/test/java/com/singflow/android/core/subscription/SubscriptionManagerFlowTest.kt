package com.singflow.android.core.subscription

import com.singflow.android.core.database.SubscriptionDao
import com.singflow.android.core.database.SubscriptionEntity
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.parser.UniversalImportManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 内存版订阅 DAO：仅记录实体，供全流程编排验证使用。 */
private class FakeSubscriptionDao : SubscriptionDao {
    private val store = mutableMapOf<String, SubscriptionEntity>()

    override fun observeAll(): Flow<List<SubscriptionEntity>> = flow { emit(getAll()) }
    override suspend fun getAll(): List<SubscriptionEntity> = store.values.toList()
    override suspend fun getById(id: String): SubscriptionEntity? = store[id]
    override suspend fun upsert(subscription: SubscriptionEntity) {
        store[subscription.id] = subscription
    }

    override suspend fun deleteById(id: String) {
        store.remove(id)
    }
}

/** 内存版节点存储：记录删除/保存调用顺序，供编排断言使用。 */
private class FakeNodeStore : SubscriptionNodeStore {
    val nodes = mutableMapOf<String, ProxyNode>()
    val deletedIds = mutableListOf<String>()
    val operations = mutableListOf<String>()

    override suspend fun getByGroup(groupId: String): List<ProxyNode> =
        nodes.values.filter { it.groupId == groupId }

    override suspend fun deleteAll(ids: List<String>) {
        operations += "delete:${ids.sorted().joinToString(",")}"
        deletedIds += ids
        ids.forEach { nodes.remove(it) }
    }

    override suspend fun saveAllPreservingStats(nodes: List<ProxyNode>): Int {
        operations += "save:${nodes.size}"
        nodes.forEach { this.nodes[it.id] = it }
        return nodes.size
    }

    /** 预置一个已存在的节点（用于差异/删除场景）。 */
    fun seed(node: ProxyNode) {
        nodes[node.id] = node
    }
}

/**
 * 订阅刷新全流程集成测试（JVM）：
 * 真实 HTTP（MockWebServer）+ 真实解析器链 + 内存存储，
 * 覆盖导入保存、失效节点删除、流量元数据写入、失败不动数据与 autoUpdate 过滤。
 */
class SubscriptionManagerFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var dao: FakeSubscriptionDao
    private lateinit var nodeStore: FakeNodeStore
    private lateinit var manager: SubscriptionManager

    /** 本地可解析的 VLESS 分享链接（与 e2e 回环节点同构）。 */
    private val linkA =
        "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:8443?encryption=none&security=none&type=tcp#LoopA"
    private val linkB =
        "vless://22222222-2222-2222-2222-222222222222@5.6.7.8:8443?encryption=none&security=none&type=tcp#LoopB"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dao = FakeSubscriptionDao()
        nodeStore = FakeNodeStore()
        manager = SubscriptionManager(
            dao = dao,
            nodeStore = nodeStore,
            importManager = UniversalImportManager(),
            client = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 加入一个 200 响应（可携带 Subscription-Userinfo 头）。 */
    private fun enqueueSubscribe(body: String, userinfoHeader: String?) {
        val response = MockResponse().setResponseCode(200).setBody(body)
        if (userinfoHeader != null) {
            response.addHeader("Subscription-Userinfo", userinfoHeader)
        }
        server.enqueue(response)
    }

    /** 便捷组合：入队响应并新增订阅（add 内含首次刷新）。 */
    private suspend fun addSubscription(
        body: String,
        userinfoHeader: String? = null,
        name: String = "Local"
    ): SubscriptionRefreshResult {
        enqueueSubscribe(body, userinfoHeader)
        return manager.add(name, server.url("/sub").toString())
    }

    @Test
    fun `新增订阅全流程导入节点并写入流量元数据`() = runTest {
        val result = addSubscription(
            "$linkA\n$linkB",
            "upload=1024; download=2048; total=1048576; expire=1893456000"
        )
        val entity = checkNotNull(result.subscription)
        assertTrue("add/refresh 应成功：${result.message}", result.success)
        assertEquals(2, result.imported)

        assertEquals(2, nodeStore.nodes.size)
        nodeStore.nodes.values.forEach { assertEquals(entity.id, it.groupId) }
        val refreshed = checkNotNull(dao.getById(entity.id))
        assertTrue("lastUpdated 应更新", refreshed.lastUpdated > 0)
        assertEquals(1024L, refreshed.upload)
        assertEquals(2048L, refreshed.download)
        assertEquals(1_048_576L, refreshed.total)
        assertEquals(1_893_456_000L, refreshed.expireAt)
    }

    @Test
    fun `刷新时应删除失效节点并保留存量统计`() = runTest {
        val first = addSubscription("$linkA\n$linkB")
        assertTrue(first.success)
        val entity = checkNotNull(first.subscription)

        val keptExisting = nodeStore.nodes.values.first { it.name == "LoopA" }.copy(id = "kept-existing")
        val staleNode = keptExisting.copy(id = "stale", name = "Stale", server = "9.9.9.9")
        nodeStore.seed(keptExisting)
        nodeStore.seed(staleNode)
        nodeStore.operations.clear()

        enqueueSubscribe("$linkA\n$linkB", null)
        val result = manager.refresh(entity.id)

        assertTrue("刷新应成功：${result.message}", result.success)
        assertEquals(listOf("delete:stale", "save:2"), nodeStore.operations)
        assertTrue("失效节点应被删除", nodeStore.deletedIds.contains("stale"))
        assertTrue("保留节点不应被删", !nodeStore.deletedIds.contains("kept-existing"))
    }

    @Test
    fun `订阅请求失败时不应改动任何数据`() = runTest {
        val first = addSubscription("$linkA")
        assertTrue(first.success)
        val entity = checkNotNull(first.subscription)
        val beforeDao = checkNotNull(dao.getById(entity.id))
        val beforeOps = nodeStore.operations.toList()
        val beforeNodes = nodeStore.nodes.keys.toSet()
        nodeStore.operations.clear()

        server.enqueue(MockResponse().setResponseCode(500))
        val result = manager.refresh(entity.id)

        assertTrue(!result.success)
        assertTrue(result.message.contains("500"))
        assertEquals("失败不应写入节点", beforeNodes, nodeStore.nodes.keys.toSet())
        assertEquals("失败不应产生存储调用", emptyList<String>(), nodeStore.operations)
        assertEquals("失败不应更新 lastUpdated", beforeDao.lastUpdated, dao.getById(entity.id)!!.lastUpdated)
        assertTrue(beforeOps.isNotEmpty())
    }

    @Test
    fun `订阅内容无可识别节点时应失败且不动数据`() = runTest {
        val first = addSubscription("$linkA")
        assertTrue(first.success)
        val entity = checkNotNull(first.subscription)
        nodeStore.operations.clear()

        enqueueSubscribe("not-a-subscription", null)
        val result = manager.refresh(entity.id)

        assertTrue(!result.success)
        assertTrue(result.message.contains("没有可识别的节点"))
        assertEquals("失败不应产生存储调用", emptyList<String>(), nodeStore.operations)
    }

    @Test
    fun `refreshAll 应跳过关闭自动更新的订阅`() = runTest {
        val first = addSubscription("$linkA")
        assertTrue(first.success)
        val auto = checkNotNull(first.subscription)
        dao.upsert(checkNotNull(dao.getById(auto.id)).copy(autoUpdate = true))

        val manual = SubscriptionEntity(
            id = "manual-sub",
            name = "Manual",
            url = server.url("/manual").toString(),
            autoUpdate = false
        )
        dao.upsert(manual)

        enqueueSubscribe("$linkA", null)
        val requestsBefore = server.requestCount
        val results = manager.refreshAll(onlyAutoUpdate = true)

        assertEquals(1, results.size)
        assertEquals(auto.id, results.first().subscription?.id)
        assertEquals("只有 auto 订阅发出了请求", 1, server.requestCount - requestsBefore)
    }

    @Test
    fun `refreshAll 在无符合条件的订阅时不发请求`() = runTest {
        val manual = SubscriptionEntity(
            id = "manual-sub",
            name = "Manual",
            url = server.url("/manual").toString(),
            autoUpdate = false
        )
        dao.upsert(manual)

        val results = manager.refreshAll(onlyAutoUpdate = true)

        assertEquals(0, results.size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `删除订阅应同时清理其分组节点`() = runTest {
        val first = addSubscription("$linkA\n$linkB")
        assertTrue(first.success)
        val entity = checkNotNull(first.subscription)
        assertEquals(2, nodeStore.nodes.size)

        manager.delete(entity.id)

        assertEquals(0, nodeStore.nodes.size)
        assertNull(dao.getById(entity.id))
    }
}
