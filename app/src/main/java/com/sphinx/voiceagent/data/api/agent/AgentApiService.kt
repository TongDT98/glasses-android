package com.sphinx.voiceagent.data.api.agent
import com.sphinx.voiceagent.data.model.agent.request.LoginRequest
import com.sphinx.voiceagent.data.model.agent.response.LoginResponse
import com.sphinx.voiceagent.data.model.agent.response.CollectionResponse
import com.sphinx.voiceagent.data.model.agent.request.UpdateAgentRequest
import okhttp3.*
import com.sphinx.voiceagent.data.model.agent.response.AgentDetailResponse

interface AgentApiService {
    @POST("api/auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): LoginResponse

    @GET("api/agents")
    suspend fun getAgent(
        @Query("agent_id")
        agentId: String
    ): AgentDetailResponse

    @GET("api/brighto/collections")
    suspend fun getCollections():
            List<CollectionResponse>

    @PATCH("api/agents")
    suspend fun updateAgent(
        @Query("agent_id")
        agentId: String,

        @Body request: UpdateAgentRequest
    ): Response<Unit>
}