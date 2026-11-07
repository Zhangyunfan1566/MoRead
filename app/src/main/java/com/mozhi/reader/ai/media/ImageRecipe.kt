package com.mozhi.reader.ai.media

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Persisted snapshots contain asset IDs, never credentials, transient URLs or image bytes. */
@Serializable
data class StyleSpec(
    val presetId: String = "watercolor",
    val natural: String = "soft watercolor illustration, muted palette, paper texture, gentle light",
    val tags: String = "watercolor, illustration, muted colors, paper texture, soft lighting",
    val negative: String = "text, watermark, lowres",
    val seed: Long? = null,
    val referenceIds: List<String> = emptyList(),
    val referenceStrength: Float = 0.6f,
    val informationExtracted: Float = 1f
)

@Serializable
data class LookAttribute(val label: String, val value: String, val chapterIndex: Int? = null,
    val start: Int? = null, val end: Int? = null, val quote: String = "")

@Serializable
data class LookSpec(
    val id: String,
    val characterKey: String,
    val name: String,
    val sinceChapter: Int = 0,
    val natural: String = "",
    val tags: String = "",
    val attributes: List<LookAttribute> = emptyList(),
    val referenceIds: List<String> = emptyList(),
    val referenceStrength: Float = 0.6f,
    val fidelity: Float = 1f,
    val source: String = "manual"
)

@Serializable
data class ShotSpec(val cast: List<String> = emptyList(), val action: String = "",
    val setting: String = "", val composition: String = "", val lighting: String = "", val mood: String = "") {
    fun text() = listOf(action, setting, composition, lighting, mood).filter(String::isNotBlank).joinToString(", ")
}

@Serializable
enum class ReferenceKind { CHARACTER, STYLE }

@Serializable
data class ReferenceSpec(val assetId: String, val kind: ReferenceKind, val characterKey: String? = null,
    val strength: Float = 0.6f, val informationExtracted: Float = 1f, val fidelity: Float = 1f)

@Serializable
data class ImageRecipe(
    val version: Int = 1,
    val style: StyleSpec = StyleSpec(),
    val cast: List<LookSpec> = emptyList(),
    val shot: ShotSpec = ShotSpec(),
    val references: List<ReferenceSpec> = emptyList(),
    val seed: Long? = null,
    val size: String? = null,
    val backend: String = "",
    val promptOverride: String = "",
    /**
     * 规划器产出的 **Base 块**（NAI 4/5 与通用后端的全局部分：人数、景别、场景、光线、共享关系）。
     * 非空时优先于「画风 + 镜头」的旧拼接方式。
     */
    val baseTag: String = "",
    /** 规划器产出的**每角色块**，与 [cast] 同序；写进 NAI 的 `char_captions`。 */
    val characterTags: List<String> = emptyList(),
    /** 本画面专用的负面词（规划器只写与正文冲突或本构图易误生成的项；质量词由代码附加）。 */
    val negativeExtra: String = ""
) {
    fun encode(): String = ImageRecipeCodec.json.encodeToString(serializer(), this)
}

object ImageRecipeCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun decode(raw: String): ImageRecipe? = runCatching { json.decodeFromString<ImageRecipe>(raw) }.getOrNull()
}

data class ImageCapabilities(
    val maxReferences: Int = 0,
    val maxCharacterReferences: Int = 0,
    val maxCharacters: Int = Int.MAX_VALUE,
    val perCharacterPrompt: Boolean = false,
    val seed: Boolean = false,
    val vibe: Boolean = false,
    val exclusiveCharacterAndStyle: Boolean = false,
    val characterReferenceCost: Int = 0,
    val tags: Boolean = false,
    val multilingual: Boolean = false
)

enum class ReferenceNotice { TEXT_ONLY, PRIMARY_CHARACTER_ONLY, REFERENCE_LIMIT, VIBE_WITHOUT_CHARACTER, SEED_UNSUPPORTED }
data class ReferencePlan(val references: List<ReferenceSpec>, val notices: List<ReferenceNotice>, val extraCostPerImage: Int)

/** Main character first, then style, then other characters. Text looks are never discarded. */
fun planReferences(capabilities: ImageCapabilities, cast: List<LookSpec>, style: StyleSpec, enabled: Boolean = true): ReferencePlan {
    if (!enabled) return ReferencePlan(emptyList(), emptyList(), 0)
    val people = cast.mapNotNull { look -> look.referenceIds.firstOrNull()?.let {
        ReferenceSpec(it, ReferenceKind.CHARACTER, look.characterKey, look.referenceStrength, fidelity = look.fidelity)
    } }
    val styles = style.referenceIds.take(3).map { ReferenceSpec(it, ReferenceKind.STYLE,
        strength = style.referenceStrength, informationExtracted = style.informationExtracted) }
    val notices = mutableListOf<ReferenceNotice>()
    if (capabilities.maxReferences == 0) {
        if (people.isNotEmpty() || styles.isNotEmpty()) notices += ReferenceNotice.TEXT_ONLY
        return ReferencePlan(emptyList(), notices, 0)
    }
    val selectedPeople = people.take(capabilities.maxCharacterReferences)
    if (people.size > selectedPeople.size) notices += ReferenceNotice.PRIMARY_CHARACTER_ONLY
    val selectedStyles = if (selectedPeople.isNotEmpty() && capabilities.exclusiveCharacterAndStyle) {
        if (styles.isNotEmpty()) notices += ReferenceNotice.VIBE_WITHOUT_CHARACTER
        emptyList()
    } else styles
    val ordered = selectedPeople.take(1) + selectedStyles + selectedPeople.drop(1)
    val selected = ordered.distinctBy { it.assetId to it.kind }.take(capabilities.maxReferences)
    if (selected.size < ordered.size) notices += ReferenceNotice.REFERENCE_LIMIT
    // NovelAI recommends keeping the sum of Vibe strengths <= 1.
    val total = selected.filter { it.kind == ReferenceKind.STYLE }.sumOf { it.strength.toDouble() }.coerceAtLeast(1.0)
    return ReferencePlan(selected.map { if (capabilities.vibe && it.kind == ReferenceKind.STYLE)
        it.copy(strength = (it.strength / total).toFloat()) else it }, notices,
        selected.count { it.kind == ReferenceKind.CHARACTER } * capabilities.characterReferenceCost)
}

fun visibleLook(looks: List<LookSpec>, characterKey: String, chapterIndex: Int): LookSpec? =
    looks.filter { it.characterKey == characterKey && it.sinceChapter <= chapterIndex }
        .maxWithOrNull(compareBy<LookSpec> { it.sinceChapter }.thenBy { it.id })

data class AssembledPrompt(val prompt: String, val base: String, val characterPrompts: List<String>, val negative: String)

object RecipeAssembler {
    fun assemble(recipe: ImageRecipe, capabilities: ImageCapabilities): AssembledPrompt {
        val style = if (capabilities.tags) recipe.style.tags.ifBlank { recipe.style.natural } else recipe.style.natural.ifBlank { recipe.style.tags }
        // 规划器给了角色块就优先用它的（它写的是"外貌照抄库 + 当前服装 + 动作 + 表情 + 视线"），
        // 否则退回外貌库的固定描述。
        val characters = if (recipe.characterTags.size == recipe.cast.size && recipe.characterTags.isNotEmpty()) {
            recipe.characterTags
        } else {
            recipe.cast.map { if (capabilities.tags) it.tags.ifBlank { it.natural } else it.natural }
        }
        // Base 块：规划器产出优先；没有就退回"画风 + 镜头"。
        val base = if (recipe.baseTag.isNotBlank()) {
            listOf(style, recipe.baseTag).filter(String::isNotBlank).joinToString(", ")
        } else {
            listOf(style, recipe.shot.text()).filter(String::isNotBlank).joinToString(", ")
        }
        val natural = buildString {
            append("Style: ").append(style)
            recipe.cast.zip(characters).forEach { (look, description) ->
                append("\nCharacter ").append(look.name).append(": ").append(description)
            }
            append("\nScene (preserve the character descriptions above): ").append(recipe.shot.text())
            if (recipe.references.isNotEmpty()) {
                recipe.references.forEachIndexed { index, ref ->
                    append("\nReference ").append(index + 1).append(": ")
                    append(if (ref.kind == ReferenceKind.STYLE) "visual style only" else
                        "identity and appearance of ${recipe.cast.firstOrNull { it.characterKey == ref.characterKey }?.name.orEmpty()}")
                }
            }
            if (recipe.style.negative.isNotBlank()) append("\nAvoid: ").append(recipe.style.negative)
        }
        val prompt = recipe.promptOverride.ifBlank {
            when {
                !capabilities.tags -> natural
                capabilities.perCharacterPrompt -> base
                else -> (listOf(base) + characters).filter(String::isNotBlank).joinToString(", ")
            }
        }
        val negative = listOf(recipe.style.negative, recipe.negativeExtra)
            .filter(String::isNotBlank).joinToString(", ")
        return AssembledPrompt(prompt, recipe.promptOverride.ifBlank { base }, characters, negative)
    }
}

object ShotParser {
    fun parse(raw: String, allowedKeys: Set<String>): ShotSpec {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val shot = ImageRecipeCodec.json.decodeFromString<ShotSpec>(clean)
        require(shot.text().isNotBlank() && shot.text().length <= 12_000)
        return shot.copy(cast = shot.cast.filter { it in allowedKeys }.distinct())
    }
}

data class StylePreset(val id: String, val natural: String, val tags: String = natural)
val IMAGE_STYLE_PRESETS = listOf(
    StylePreset("watercolor", "soft watercolor illustration, muted palette, paper texture, gentle light"),
    StylePreset("painting", "painterly illustration, rich brush strokes, dramatic light"),
    StylePreset("cel", "cel shading, anime illustration, clean linework, flat colors"),
    StylePreset("ink", "Chinese ink wash painting, expressive ink, generous negative space"),
    StylePreset("pencil", "pencil sketch, graphite on paper, delicate linework, monochrome"),
    StylePreset("storybook", "vintage storybook illustration, warm muted colors, subtle grain")
)
