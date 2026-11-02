// js/chat-agent-config.js

// API和用户ID配置
const CHAT_AGENT_CONFIG = {
    API_URL: window.location.protocol + '//' + (window.location.hostname === '81.68.89.43' ? '101.43.106.89' : window.location.hostname) + ':8081/api-ai/chat-stream',
    CHAT_ID: '',
    USER_ID: ''
};

// 全局状态
const ChatAgentState = {
    isLoading: false,
    messages: [] // 只存储已完成的消息对象
};

// 工具函数
const ChatAgentUtils = {
    generateMessageId: () => `msg_${Date.now()}`,
    setLoading(loading) {
        ChatAgentState.isLoading = loading;
        $('#chat-agent-send').prop('disabled', loading);
    }
};