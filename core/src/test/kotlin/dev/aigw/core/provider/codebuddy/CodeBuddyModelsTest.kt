package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeBuddyModelsTest {
    @Test
    fun `视觉能力按上游字段标记并遵守禁用开关`() {
        val root = JsonParser.parseString(
            """{"code":0,"data":{"agents":[{"name":"cli","models":["vision","blocked","text"]}],"models":[
              {"id":"vision","name":"Vision（视觉）","maxInputTokens":1000000,"supportsImages":true},
              {"id":"blocked","name":"Blocked","supportsImages":true,"disabledMultimodal":true},
              {"id":"text","name":"Text","supportsImages":false},
              {"id":"hidden","name":"Hidden","supportsImages":true}
            ]}}""",
        ).asJsonObject

        val models = CodeBuddyModels.parse(root)
        assertEquals(listOf("vision", "blocked", "text"), models.map { it.id })
        assertTrue(models[0].supportsVision)
        assertEquals("Vision（视觉）", models[0].displayName)
        assertFalse(models[1].supportsVision)
        assertFalse(models[2].supportsVision)
    }
}
