package com.singflow.android.core.database

import android.content.Context
import androidx.room.Room
import com.singflow.android.core.config.SingBoxConfigPipeline
import com.singflow.android.core.parser.UniversalImportManager
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
            .fallbackToDestructiveMigration()
            .build()
    }

    /** 节点 DAO。 */
    @Provides
    fun provideNodeDao(database: SingFlowDatabase): NodeDao = database.nodeDao()

    /** 订阅 DAO。 */
    @Provides
    fun provideSubscriptionDao(database: SingFlowDatabase): SubscriptionDao = database.subscriptionDao()

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
}
