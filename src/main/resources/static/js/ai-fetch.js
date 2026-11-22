/********** ai-fetch：AI 请求统一超时封装 **********/

// 后端 AI 调用上限：文本单次 90 秒、识图单次 125 秒，超时各自动重试一次；
// 前端在「上限 × 2 + 10 秒」处主动中断，避免页面无限等待
const AI_TEXT_TIMEOUT_MS = 190000;
const AI_IMAGE_TIMEOUT_MS = 260000;

const AI_TIMEOUT_MESSAGE = '模型响应超时，请重试';

/** fetch 封装：超时主动中断并抛出 code 为 AI_TIMEOUT 的错误 */
async function fetchAi(url, options, timeoutMs) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs || AI_TEXT_TIMEOUT_MS);
    try {
        return await fetch(url, Object.assign({}, options || {}, { signal: controller.signal }));
    } catch (error) {
        if (error && error.name === 'AbortError') {
            const timeoutError = new Error(AI_TIMEOUT_MESSAGE);
            timeoutError.code = 'AI_TIMEOUT';
            throw timeoutError;
        }
        throw error;
    } finally {
        clearTimeout(timer);
    }
}

/** 请求失败时统一取提示文案：超时用固定文案，其余交给调用方的默认提示 */
function aiFetchErrorText(error, fallback) {
    return (error && error.code === 'AI_TIMEOUT') ? AI_TIMEOUT_MESSAGE : fallback;
}

/** 请求失败时打印日志：超时属于预期分支，不记控制台错误 */
function aiFetchLog(error, label) {
    if (!error || error.code !== 'AI_TIMEOUT') {
        console.error(label || 'AI request error:', error);
    }
}
