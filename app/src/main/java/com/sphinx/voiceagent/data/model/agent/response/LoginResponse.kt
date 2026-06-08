package com.sphinx.voiceagent.data.model.agent.response

data class LoginResponse(
    val access_token: String,
    val refresh_token: String
)