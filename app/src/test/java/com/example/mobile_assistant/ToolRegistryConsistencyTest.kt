package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "dead tool" class of bug: every model-visible shared tool must be marked shared
 * (so it is dispatched through SharedToolExecutor and excluded from the post-call screenshot),
 * agent-only tools must NOT be marked shared, and every tool name must be unique and have a
 * canonicalToolName identity mapping.
 */
class ToolRegistryConsistencyTest {

    @Test
    fun everyAgentFunctionToolIsMarkedShared() {
        SharedToolSchemas.agentFunctionTools().forEach { spec ->
            assertTrue(
                "${spec.name} is in agentFunctionTools() but not isSharedTool()",
                SharedToolSchemas.isSharedTool(spec.name)
            )
        }
    }

    @Test
    fun newSharedToolsArePresentAndShared() {
        val expected = listOf(
            SharedToolSchemas.TOOL_LIST_APPS,
            SharedToolSchemas.TOOL_CLIPBOARD_GET,
            SharedToolSchemas.TOOL_CLIPBOARD_SET,
            SharedToolSchemas.TOOL_SEARCH_CONTACTS,
            SharedToolSchemas.TOOL_SET_VOLUME,
            SharedToolSchemas.TOOL_MEDIA_CONTROL,
            SharedToolSchemas.TOOL_TOGGLE_FLASHLIGHT,
            SharedToolSchemas.TOOL_GET_DEVICE_STATUS,
            SharedToolSchemas.TOOL_GET_LOCATION,
            SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME,
            SharedToolSchemas.TOOL_READ_NOTIFICATIONS,
            SharedToolSchemas.TOOL_GET_WEATHER,
            SharedToolSchemas.TOOL_SEND_MESSAGE,
            SharedToolSchemas.TOOL_MEMORY_READ,
            SharedToolSchemas.TOOL_MEMORY_EDIT,
            SharedToolSchemas.TOOL_MEMORY_LIST,
            SharedToolSchemas.TOOL_MEMORY_LINK,
            SharedToolSchemas.TOOL_MEMORY_SAVE_FACT
        )
        val toolNames = SharedToolSchemas.agentFunctionTools().map { it.name }.toSet()
        expected.forEach { name ->
            assertTrue("$name missing from agentFunctionTools()", toolNames.contains(name))
            assertTrue("$name not marked shared", SharedToolSchemas.isSharedTool(name))
        }
    }

    @Test
    fun agentOnlyToolsAreNotMarkedShared() {
        listOf(
            AgentTooling.TOOL_OPEN_RECENTS,
            AgentTooling.TOOL_OPEN_NOTIFICATIONS,
            AgentTooling.TOOL_CLOSE_APP,
            AgentTooling.TOOL_FIND_TEXT,
            AgentTooling.TOOL_COMPOSE_EMAIL,
            AgentTooling.TOOL_CALENDAR_CREATE_EVENT,
            AgentTooling.TOOL_CALENDAR_EDIT_EVENT,
            AgentTooling.TOOL_CALENDAR_DELETE_EVENT
        ).forEach { name ->
            assertFalse("$name must NOT be a shared tool", SharedToolSchemas.isSharedTool(name))
            assertTrue("$name missing from agent tool registry", AgentTooling.toolNames().contains(name))
        }
    }

    @Test
    fun allToolNamesAreUnique() {
        val names = AgentTooling.toolNames()
        assertEquals(
            "Duplicate tool names registered: ${names.groupingBy { it }.eachCount().filter { it.value > 1 }}",
            names.size,
            names.toSet().size
        )
    }

    @Test
    fun canonicalAliasesResolveNewTools() {
        assertEquals(SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME, AgentTooling.canonicalToolName("how_far"))
        assertEquals(SharedToolSchemas.TOOL_READ_NOTIFICATIONS, AgentTooling.canonicalToolName("notifications"))
        assertEquals(SharedToolSchemas.TOOL_GET_LOCATION, AgentTooling.canonicalToolName("where am i"))
        assertEquals(SharedToolSchemas.TOOL_GET_WEATHER, AgentTooling.canonicalToolName("forecast"))
        assertEquals(SharedToolSchemas.TOOL_SEND_MESSAGE, AgentTooling.canonicalToolName("telegram message"))
        assertEquals(SharedToolSchemas.TOOL_MEMORY_READ, AgentTooling.canonicalToolName("read memory"))
        assertEquals(SharedToolSchemas.TOOL_MEMORY_EDIT, AgentTooling.canonicalToolName("write memory"))
        assertEquals(SharedToolSchemas.TOOL_MEMORY_LIST, AgentTooling.canonicalToolName("memory files"))
        assertEquals(SharedToolSchemas.TOOL_MEMORY_LINK, AgentTooling.canonicalToolName("link memory"))
        assertEquals(SharedToolSchemas.TOOL_MEMORY_SAVE_FACT, AgentTooling.canonicalToolName("remember preference"))
        assertEquals(SharedToolSchemas.TOOL_CHECK_EMAILS, AgentTooling.canonicalToolName("search emails"))
        assertEquals(AgentTooling.TOOL_FIND_TEXT, AgentTooling.canonicalToolName("find_text"))
        assertEquals(AgentTooling.TOOL_OPEN_RECENTS, AgentTooling.canonicalToolName("recents"))
        assertEquals(AgentTooling.TOOL_CLOSE_APP, AgentTooling.canonicalToolName("close app"))
        assertEquals(AgentTooling.TOOL_CALENDAR_EDIT_EVENT, AgentTooling.canonicalToolName("edit event"))
        assertEquals(AgentTooling.TOOL_CALENDAR_DELETE_EVENT, AgentTooling.canonicalToolName("delete event"))
    }
}
