package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.agent.AgentToolExecutor
import com.mozhi.reader.ai.client.*
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.AiProviderType
import com.mozhi.reader.core.retrieval.ReadingScope
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CharacterResearchPipelineTest {
    private val chapters = listOf(
        0 to "林舟在灯塔等候。林舟说：“灯不能灭。”林舟抬头看海。林舟笑道：“好。”阿满在门口喊他。",
        1 to "小满带来一封信。小满道：“给你。”小满说：“走吧。”林舟接过信。"
    )
    private val client = ScriptedClient()
    private val model = ResolvedChatClient(client, ChatOptions.Default,
        AiProviderEntity(id = 1, name = "test", baseUrl = "https://example.test", apiKeyAlias = "alias",
            type = AiProviderType.CHAT, createdAt = 1), "helper")
    private val cache = MemoryCache()
    private val agent = CharacterResearchAgent(
        AgentLoop(
            mockk(), dagger.Lazy { error("unused") }, dagger.Lazy { error("unused") },
            dagger.Lazy { error("unused") }, mockk(), AgentToolExecutor { }
        ),
        KnowledgeJsonRequest(KnowledgeRequestLimiter()), mockk(relaxed = true))

    private fun request() = CharacterResearchRequest(1, "灯塔来信", ReadingScope.WholeBook, false, chapters, { "第${it + 1}章" },
        model, null, "model-key", cache) {}

    private val roster = """{"characters":[{"name":"林舟","role":"protagonist"},{"name":"小满","aliases":["阿满"],"role":"major"}]}"""
    private val lin = """{"name":"林舟","tagline":"守灯人","bio":"守灯塔的人。","relationships":[{"name":"阿满","relation":"邻居","p":1}]}"""
    private val man = """{"name":"小满","tagline":"信使","bio":"送信的人。","relationships":[{"name":"林舟","relation":"收信人","p":1}]}"""

    @Test fun `aliases in relationships are mapped to the roster name so the graph connects`() = runTest {
        client.reply = { system, user -> when { "人物名单" in system -> roster; "目标人物：林舟" in user -> lin; else -> man } }
        val result = agent.run(request()) {}
        val linProfile = result.people.first { it.profile.name == "林舟" }
        assertEquals("小满", linProfile.profile.dossier.relationships.single().target)
        assertEquals(CharacterRole.MAJOR, result.people.first { it.profile.name == "小满" }.entry.role)
        assertTrue(result.failed.isEmpty())
        assertEquals(3, result.usedKeys.size)
    }

    @Test fun `a failed roster falls back to local candidates and profiles still arrive`() = runTest {
        client.reply = { system, user -> when { "人物名单" in system -> "名单有林舟和小满。"; "目标人物：林舟" in user -> lin; else -> man } }
        val result = agent.run(request()) {}
        assertTrue(result.people.any { it.profile.name == "林舟" })
        // 回落的名单不缓存：下次还会再试名单。
        assertEquals(result.people.size, result.usedKeys.size)
    }

    @Test fun `one failing person never blocks the others and is reported`() = runTest {
        client.reply = { system, user -> when { "人物名单" in system -> roster; "目标人物：林舟" in user -> lin; else -> "写不出来" } }
        val progress = mutableListOf<KnowledgeProgress>()
        val result = agent.run(request()) { progress += it }
        assertEquals(listOf("林舟"), result.people.map { it.profile.name })
        assertEquals(listOf("小满"), result.failed)
        assertEquals(listOf("小满"), progress.last().failed)
        assertEquals(2, progress.last().done)
    }

    @Test fun `a second run with unchanged text reuses every cached result`() = runTest {
        client.reply = { system, user -> when { "人物名单" in system -> roster; "目标人物：林舟" in user -> lin; else -> man } }
        agent.run(request()) {}
        val before = client.calls
        val again = agent.run(request()) {}
        assertEquals(before, client.calls)
        assertEquals(setOf("林舟", "小满"), again.people.map { it.profile.name }.toSet())
    }

    @Test fun `everyone failing reports why`() = runTest {
        client.reply = { system, _ -> if ("人物名单" in system) roster else "写不出来" }
        val error = runCatching { agent.run(request()) {} }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("没有整理出人物档案"))
    }

    private class MemoryCache : DossierCache {
        val rows = linkedMapOf<String, String>()
        override suspend fun load(key: String): String? = rows[key]
        override suspend fun store(key: String, identity: String, json: String) { rows[key] = json }
    }

    private class ScriptedClient : ChatApiClient {
        var reply: (String, String) -> String = { _, _ -> "{}" }
        var calls = 0
        override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>, options: ChatOptions): Flow<ChatDelta> = flow {
            calls++
            emit(ChatDelta.Text(reply(messages.first().content, messages.first { it.role == ChatRole.USER }.content)))
        }
        override suspend fun chat(messages: List<ChatMessage>, options: ChatOptions): String = error("unused")
        override suspend fun embed(texts: List<String>): List<FloatArray> = error("unused")
    }
}
