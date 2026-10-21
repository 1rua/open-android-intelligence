package com.openandroidintelligence.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * 数字助理资格声明用的识别服务。
 *
 * Android 判定「谁有资格当默认数字助理」时会解析 `<voice-interaction-service>`：
 * 只要缺 `recognitionService`，`VoiceInteractionServiceInfo` 就直接判为
 * "No recognitionService specified"，整个应用不会出现在「默认数字助理」列表里。
 *
 * 本应用**不做系统级语音识别**：语音输入是在 App 内录音后作为附件发送给 Agent，
 * 不经过 SpeechRecognizer。所以这里不伪造识别结果 —— 系统真的调过来时，
 * 明确返回客户端错误，让调用方知道该能力未接入，而不是假装在听。
 */
class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback?) {
        // 没有进行中的识别：这里无事可做。
    }

    override fun onCancel(listener: Callback?) {
        // 没有进行中的识别：这里无事可做。
    }
}
