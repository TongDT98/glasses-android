package com.sphinx.voiceagent.data.api.agent
import okhttp3.OkHttpClient
import retrofit2.converter.gson.GsonConverterFactory
import  retrofit2.Retrofit
object AgentnetworkClient {

    private val client =
        OkHttpClient.Builder()
            .addInterceptor { chain ->

                val request =
                    chain.request()
                        .newBuilder()
                        .apply {

                            if (
                                SessionManager.accessToken.isNotBlank()
                            ) {

                                addHeader(
                                    "Authorization",
                                    "Bearer ${SessionManager.accessToken}"
                                )
                            }
                        }
                        .build()

                chain.proceed(request)
            }
            .build()

    val api: AgentApiService =
        Retrofit.Builder()
            .baseUrl(AgentConfig.BASE_URL)
            .client(client)
            .addConverterFactory(
                GsonConverterFactory.create()
            )
            .build()
            .create(AgentApiService::class.java)
}