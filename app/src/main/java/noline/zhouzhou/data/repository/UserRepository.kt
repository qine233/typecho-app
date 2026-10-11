package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.*
import noline.zhouzhou.data.remote.TypechoApi

class UserRepository(private val api: TypechoApi) {

    suspend fun testConnection(xmlrpcUrl: String): Result<Boolean> {
        return try {
            val ok = api.testConnection(xmlrpcUrl)
            if (ok) Result.success(true)
            else Result.failure(Exception("Connection test failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(xmlrpcUrl: String, username: String, password: String): Result<BlogInfo> {
        return try {
            val blog = api.login(xmlrpcUrl, username, password)
            Result.success(blog)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
