package com.mozhi.reader.core.media

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 生图 API 的服务商预设：
 * - OPENAI_IMAGES：OpenAI 兼容 `/images/generations`（gpt-image 官方端点）
 * - OPENAI_CHAT：经 `/chat/completions` 出图的中转（gpt-image 系常见形态）
 * - NOVELAI：NovelAI `/ai/generate-image`（zip 响应）
 */
enum class ImageApiProvider { OPENAI_IMAGES, OPENAI_CHAT, NOVELAI }

fun ImageApiProvider.defaultBaseUrl(): String = when (this) {
    ImageApiProvider.OPENAI_IMAGES, ImageApiProvider.OPENAI_CHAT -> "https://api.openai.com/v1"
    ImageApiProvider.NOVELAI -> "https://image.novelai.net"
}

fun ImageApiProvider.defaultModel(): String = when (this) {
    ImageApiProvider.OPENAI_IMAGES, ImageApiProvider.OPENAI_CHAT -> "gpt-image-1"
    ImageApiProvider.NOVELAI -> "nai-diffusion-4-5-full"
}

fun ImageApiProvider.sizeOptions(): List<String> = when (this) {
    ImageApiProvider.OPENAI_IMAGES, ImageApiProvider.OPENAI_CHAT ->
        listOf("1024x1024", "1536x1024", "1024x1536")
    ImageApiProvider.NOVELAI -> listOf("832x1216", "1216x832", "1024x1024", "1024x1536", "1536x1024")
}

/** NovelAI 采样器选项（UI 下拉）；请求侧空串回落 k_euler_ancestral。 */
val NOVELAI_SAMPLERS = listOf(
    "k_euler_ancestral",
    "k_euler",
    "k_dpmpp_2m",
    // DPM++ 2M SDE：细节与质感通常比 2M 更好，采样偏慢，官方同样支持。
    "k_dpmpp_2m_sde",
    "k_dpmpp_2s_ancestral",
    "k_dpmpp_sde",
    "ddim_v3"
)

/**
 * NovelAI 生图模型（仅列**生成**模型；本应用的独立生图 API 是文生图，
 * 用不到 inpainting / 局部重绘变体）。选择后写入 [ImageApiSettings.model]。
 */
val NOVELAI_MODELS: List<String> = listOf(
    "nai-diffusion-5-full",
    "nai-diffusion-5-curated",
    "nai-diffusion-5-full-medium",
    "nai-diffusion-4-5-full",
    "nai-diffusion-4-5-curated",
    "nai-diffusion-4-full",
    "nai-diffusion-4-curated-preview",
    "nai-diffusion-3",
    "nai-diffusion-furry-3"
)

/** 独立生图 API 配置；填齐后优先于「模型分配」的生图模型。 */
data class ImageApiSettings(
    val provider: ImageApiProvider = ImageApiProvider.OPENAI_IMAGES,
    val baseUrl: String = "",
    val model: String = "",
    /** 形如 1024x1024；NovelAI 可任意填，请求侧会对齐 64 的倍数。 */
    val size: String = "",
    /** 仅 NovelAI 使用；会作为固定 Danbooru tags 前缀拼到每次动态提示词前。 */
    val positivePrompt: String = "",
    /** 仅 NovelAI 使用；空串走内置默认负面词。 */
    val negativePrompt: String = "",
    /** 仅 NovelAI：采样器，空串走默认。 */
    val sampler: String = "",
    /** 仅 NovelAI：采样步数。 */
    val steps: Int = 28,
    /** 仅 NovelAI：提示词相关度（guidance scale）。 */
    val scale: Float = 5f,
    /**
     * 仅 NovelAI：NAI 的 UC 预设档位。0 = Heavy，1 = Light，2 = None，3 = Human Focus。
     * 出 R18 图时 Heavy 会排斥一部分画面元素，可以在这里改。
     */
    val ucPreset: Int = 0,
    /** 仅 NovelAI：是否让官方自动追加质量词。 */
    val qualityToggle: Boolean = true,
    /** 生图专用破限；留空回落内置默认。 */
    val jailbreakPrompt: String = "",
    /** 生图提示词规范（NAI / 通用各一套）；留空回落内置默认。 */
    val imageSpec: String = "",
    /** 出图前的思考清单；留空回落内置默认。 */
    val imageThinking: String = ""
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    val effectiveSize: String
        get() = size.ifBlank { provider.sizeOptions().first() }
}

@Singleton
class ImageApiSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val settings: Flow<ImageApiSettings> = dataStore.data.map(::readFrom)

    suspend fun current(): ImageApiSettings = settings.first()

    suspend fun update(transform: (ImageApiSettings) -> ImageApiSettings) {
        dataStore.edit { prefs ->
            val next = transform(readFrom(prefs))
            prefs[KEY_PROVIDER] = next.provider.name
            prefs[KEY_BASE_URL] = next.baseUrl
            prefs[KEY_MODEL] = next.model
            prefs[KEY_SIZE] = next.size
            prefs[KEY_POSITIVE] = next.positivePrompt
            prefs[KEY_NEGATIVE] = next.negativePrompt
            prefs[KEY_SAMPLER] = next.sampler
            prefs[KEY_STEPS] = next.steps
            prefs[KEY_SCALE] = next.scale
            prefs[KEY_UC_PRESET] = next.ucPreset
            prefs[KEY_QUALITY_TOGGLE] = next.qualityToggle
            prefs[KEY_JAILBREAK] = next.jailbreakPrompt
            prefs[KEY_IMAGE_SPEC] = next.imageSpec
            prefs[KEY_IMAGE_THINKING] = next.imageThinking
        }
    }

    private fun readFrom(prefs: Preferences): ImageApiSettings = ImageApiSettings(
        provider = prefs[KEY_PROVIDER]
            ?.let { raw -> ImageApiProvider.entries.firstOrNull { it.name == raw } }
            ?: ImageApiProvider.OPENAI_IMAGES,
        baseUrl = prefs[KEY_BASE_URL].orEmpty(),
        model = prefs[KEY_MODEL].orEmpty(),
        size = prefs[KEY_SIZE].orEmpty(),
        positivePrompt = prefs[KEY_POSITIVE].orEmpty(),
        negativePrompt = prefs[KEY_NEGATIVE].orEmpty(),
        sampler = prefs[KEY_SAMPLER].orEmpty(),
        steps = prefs[KEY_STEPS] ?: 28,
        scale = prefs[KEY_SCALE] ?: 5f,
        ucPreset = prefs[KEY_UC_PRESET] ?: 0,
        qualityToggle = prefs[KEY_QUALITY_TOGGLE] ?: true,
        jailbreakPrompt = prefs[KEY_JAILBREAK].orEmpty(),
        imageSpec = prefs[KEY_IMAGE_SPEC].orEmpty(),
        imageThinking = prefs[KEY_IMAGE_THINKING].orEmpty()
    )

    companion object {
        /** 独立生图 API Key 在 EncryptedSharedPreferences 里的别名。 */
        const val API_KEY_ALIAS = "standalone-image-api"

        private val KEY_PROVIDER = stringPreferencesKey("image_api_provider")
        private val KEY_BASE_URL = stringPreferencesKey("image_api_base_url")
        private val KEY_MODEL = stringPreferencesKey("image_api_model")
        private val KEY_SIZE = stringPreferencesKey("image_api_size")
        private val KEY_POSITIVE = stringPreferencesKey("image_api_positive")
        private val KEY_NEGATIVE = stringPreferencesKey("image_api_negative")
        private val KEY_SAMPLER = stringPreferencesKey("image_api_sampler")
        private val KEY_STEPS = intPreferencesKey("image_api_steps")
        private val KEY_SCALE = floatPreferencesKey("image_api_scale")
        private val KEY_UC_PRESET = intPreferencesKey("image_api_uc_preset")
        private val KEY_QUALITY_TOGGLE = booleanPreferencesKey("image_api_quality_toggle")
        private val KEY_JAILBREAK = stringPreferencesKey("image_api_jailbreak")
        private val KEY_IMAGE_SPEC = stringPreferencesKey("image_api_spec")
        private val KEY_IMAGE_THINKING = stringPreferencesKey("image_api_thinking")
    }
}
