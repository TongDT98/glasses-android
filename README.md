HeyCyan Voice Agent
===================

Android project scaffolded from the vendor HeyCyan glasses SDK sample.

What is included:

- Vendor AAR: `app/libs/LIB_GLASSES_SDK-release_3.aar`
- Glasses scan/connect flow from the sample app
- Voice chat screen with configurable WebSocket URL
- PCM microphone frames from `voiceFromGlasses(pcmData)` streamed as binary WebSocket messages
- Agent text/JSON responses appended to the app log
- Agent binary PCM responses played with `AudioTrack`

Default WebSocket endpoint:

```text
ws://10.0.2.2:8080/voice
```

Expected first outbound JSON message:

```json
{
  "type": "session.start",
  "sampleRateHz": 16000,
  "channels": 1,
  "encoding": "pcm_s16le",
  "locale": "vi-VN"
}
```

After that, microphone audio is sent as binary PCM frames. The app also sends:

```json
{ "type": "glasses.voice_status", "status": 1 }
{ "type": "session.stop" }
```

Build:

```powershell
$env:JAVA_HOME='D:\Androidstudio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug
```
