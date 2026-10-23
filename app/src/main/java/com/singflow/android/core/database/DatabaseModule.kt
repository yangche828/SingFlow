package com.singflow.android.core.database

import android.content.Context
import androidx.room.Room
import com.singflow.android.core.config.SingBoxConfigPipeline
import com.singflow.android.core.parser.UniversalImportManager
import com.singflow.android.core.subscription.SubscriptionNodeStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 全局 DI 模块：JSON、Room、DataStore、OkHttp 与解析/编译流水线。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** 全局 JSON 实例。 */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** Room 数据库。 */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SingFlowDatabase {
        return Room.databaseBuilder(context, SingFlowDatabase::class.java, SingFlowDatabase.DATABASE_NAME)
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
    }

    /** 节点 DAO。 */
    @Provides
    fun provideNodeDao(database: SingFlowDatabase): NodeDao = database.nodeDao()

    /** 订阅 DAO。 */
    @Provides
    fun provideSubscriptionDao(database: SingFlowDatabase): SubscriptionDao = database.subscriptionDao()

    /** 应用级国家分流规则 DAO。 */
    @Provides
    fun provideAppRouteRuleDao(database: SingFlowDatabase): AppRouteRuleDao = database.appRouteRuleDao()

    /** DataStore 设置管理器。 */
    @Provides
    @Singleton
    fun providePreferencesManager(@ApplicationContext context: Context, json: Json): PreferencesManager {
        return PreferencesManager(context, json)
    }

    /** 节点仓库。 */
    @Provides
    @Singleton
    fun provideNodeRepository(nodeDao: NodeDao, json: Json): NodeRepository {
        return NodeRepository(nodeDao, json)
    }

    /** 订阅节点存储抽象：由 [NodeRepository] 单例直接提供。 */
    @Provides
    @Singleton
    fun provideSubscriptionNodeStore(
        nodeRepository: NodeRepository
    ): com.singflow.android.core.subscription.SubscriptionNodeStore = nodeRepository

    /** 应用级国家分流规则仓库。 */
    @Provides
    @Singleton
    fun provideAppRouteRuleRepository(dao: AppRouteRuleDao): AppRouteRuleRepository {
        return AppRouteRuleRepository(dao)
    }

    /** 全协议导入管理器。 */
    @Provides
    @Singleton
    fun provideUniversalImportManager(json: Json, nodeRepository: NodeRepository): UniversalImportManager {
        return UniversalImportManager(json = json, nodeRepository = nodeRepository)
    }

    /** 配置编译流水线。 */
    @Provides
    @Singleton
    fun provideConfigPipeline(json: Json): SingBoxConfigPipeline {
        return SingBoxConfigPipeline(json)
    }

    /** 订阅管理器。 */
    @Provides
    @Singleton
    fun provideSubscriptionManager(
        subscriptionDao: SubscriptionDao,
        nodeStore: SubscriptionNodeStore,
        importManager: UniversalImportManager,
        okHttpClient: OkHttpClient
    ): com.singflow.android.core.subscription.SubscriptionManager {
        return com.singflow.android.core.subscription.SubscriptionManager(
            dao = subscriptionDao,
            nodeStore = nodeStore,
            importManager = importManager,
            client = okHttpClient
        )
    }

    /** 备份管理器。 */
    @Provides
    @Singleton
    fun provideBackupManager(
        nodeRepository: NodeRepository,
        subscriptionDao: SubscriptionDao,
        appRouteRuleRepository: AppRouteRuleRepository,
        preferencesManager: PreferencesManager,
        json: Json
    ): com.singflow.android.core.backup.BackupManager {
        return com.singflow.android.core.backup.BackupManager(
            nodeRepository = nodeRepository,
            subscriptionDao = subscriptionDao,
            appRuleRepository = appRouteRuleRepository,
            preferencesManager = preferencesManager,
            json = json
        )
    }

    /** WebDAV 客户端。 */
    @Provides
    @Singleton
    fun provideWebDavClient(okHttpClient: OkHttpClient): com.singflow.android.core.backup.WebDavClient {
        return com.singflow.android.core.backup.WebDavClient(okHttpClient)
    }

    /** 共享 OkHttp 客户端（测速 / Clash API）。 */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** 节点延迟测速器。 */
    @Provides
    @Singleton
    fun provideNodeLatencyTester(okHttpClient: OkHttpClient): com.singflow.android.core.engine.NodeLatencyTester {
        return com.singflow.android.core.engine.NodeLatencyTester(okHttpClient)
    }

    /** Clash API 客户端（探活 / Selector 热切换）。 */
    @Provides
    @Singleton
    fun provideClashApiClient(okHttpClient: OkHttpClient): com.singflow.android.core.probe.ClashApiClient {
        return com.singflow.android.core.probe.ClashApiClient(okHttpClient)
    }

    /** 自动切换节点存储适配器。 */
    @Provides
    @Singleton
    fun provideNodeStore(
        repository: NodeRepository
    ): com.singflow.android.core.failover.NodeStore {
        return com.singflow.android.core.failover.RepositoryNodeStore(repository)
    }

    /** 本机底层网络可用性判定。 */
    @Provides
    @Singleton
    fun provideLocalNetworkVerifier(
        @ApplicationContext context: Context
    ): com.singflow.android.core.failover.LocalNetworkVerifier {
        return com.singflow.android.core.failover.AndroidLocalNetworkVerifier(context)
    }
}
