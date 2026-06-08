//package com.sphinx.voiceagent.data.api.agent
//import android.content.Context
//import androidx.datastore.preferences.core.stringPreferencesKey
//import androidx.datastore.preferences.preferencesDataStore
//import androidx.datastore.preferences.core.edit
//import kotlinx.coroutines.flow.*
//class TokenManager(
//    private val context: Context
//) {
//
//    companion object {
//
//        private val Context.dataStore by preferencesDataStore(
//            name = "auth"
//        )
//
//        private val ACCESS_TOKEN =
//            stringPreferencesKey("access_token")
//
//        private val REFRESH_TOKEN =
//            stringPreferencesKey("refresh_token")
//    }
//
//    suspend fun saveToken(
//        accessToken: String,
//        refreshToken: String
//    ) {
//
//        context.dataStore.edit {
//
//            it[ACCESS_TOKEN] = accessToken
//            it[REFRESH_TOKEN] = refreshToken
//        }
//    }
//
//    suspend fun getAccessToken(): String? {
//
//        return context.dataStore.data.first()[ACCESS_TOKEN]
//    }
//
//    suspend fun getRefreshToken(): String? {
//
//        return context.dataStore.data.first()[REFRESH_TOKEN]
//    }
//
//    suspend fun clear() {
//
//        context.dataStore.edit {
//            it.clear()
//        }
//    }
//}