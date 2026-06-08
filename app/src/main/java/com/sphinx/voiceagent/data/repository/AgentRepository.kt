package com.sphinx.voiceagent.data.repository
import com.sphinx.voiceagent.data.model.agent.request.LoginRequest
import com.sphinx.voiceagent.data.api.agent.AgentnetworkClient
import com.sphinx.voiceagent.data.api.agent.AgentConfig
import com.sphinx.voiceagent.data.model.agent.response.CollectionResponse
import com.sphinx.voiceagent.data.model.agent.request.UpdateAgentRequest
import com.sphinx.voiceagent.data.api.agent.SessionManager
import com.sphinx.voiceagent.data.model.agent.response.AgentDetailResponse
class AgentRepository {
    private val api =
        AgentnetworkClient.api

    suspend fun login() {

        val response =
            api.login(
                LoginRequest(
                    AgentConfig.EMAIL,
                    AgentConfig.PASSWORD
                )
            )

        SessionManager.accessToken =
            response.access_token

        SessionManager.refreshToken =
            response.refresh_token
    }

    suspend fun getAgent():
            AgentDetailResponse {

        return api.getAgent(
            AgentConfig.AGENT_ID
        )
    }

    suspend fun getCollections():
            List<CollectionResponse> {

        return api.getCollections()
    }

    suspend fun updateVoice(
        collectionId: String
    ) {

        api.updateAgent(
            AgentConfig.AGENT_ID,
            UpdateAgentRequest(
                collectionId
            )
        )
    }
}