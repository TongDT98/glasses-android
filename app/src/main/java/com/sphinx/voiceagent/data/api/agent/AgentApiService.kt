package com.sphinx.voiceagent.data.api.agent
import com.sphinx.voiceagent.data.model.agent.request.LoginRequest
import com.sphinx.voiceagent.data.model.agent.response.LoginResponse
import com.sphinx.voiceagent.data.model.agent.response.CollectionResponse
import com.sphinx.voiceagent.data.model.agent.request.UpdateAgentRequest
import com.sphinx.voiceagent.data.model.agent.response.AgentDetailResponse
import retrofit2.Response
import retrofit2.http.*
interface AgentApiService {
    @POST("api/auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): LoginResponse

    @GET("api/agents/{agent_id}")
    suspend fun getAgent(
        @Path("agent_id") agentId: String
    ): AgentDetailResponse

    @GET("api/brighto/collections")
    suspend fun getCollections():
            List<CollectionResponse>

    @PATCH("api/agents/{agent_id}")
    suspend fun updateAgent(
        @Path("agent_id") agentId: String,

        @Body request: UpdateAgentRequest
    ): Response<Unit>
}