package com.sphinx.voiceagent.data.repository
import android.util.Log
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
    private suspend fun ensureToken() {
        if (SessionManager.accessToken.isBlank()) {
            login()
        }
    }
    suspend fun login() {

        val response =
            api.login(
                LoginRequest(
                    AgentConfig.EMAIL,
                    AgentConfig.PASSWORD
                )
            )
        Log.d("Login","${response.access_token}")
        SessionManager.accessToken =
            response.access_token

        SessionManager.refreshToken =
            response.refresh_token
    }

    suspend fun getAgent():
            AgentDetailResponse {
        ensureToken()
        Log.d("AgentCollection","Get Collection")
        val res = api.getAgent(AgentConfig.AGENT_ID)
        Log.d("AgentCollection agentid","${res.tts_collection_id}")
        return res

    }

    suspend fun getCollections():
            List<CollectionResponse> {
        ensureToken()
        return api.getCollections()
    }

    suspend fun updateVoice(
        collectionId: String
    ) {
        ensureToken()
        api.updateAgent(
            AgentConfig.AGENT_ID,
            UpdateAgentRequest(
                collectionId
            )
        )
    }
}