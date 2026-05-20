package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalTaskCatalogTest {

    @Test
    fun parsesValidTasks() {
        val raw = """
            {"schema_version":1,"tasks":[
              {"id":"a","prompt":"do a","success_criteria":"a done","setup_hint":"none"},
              {"id":"b","prompt":"do b","success_criteria":"b done","setup_hint":""}
            ]}
        """.trimIndent()
        val tasks = EvalTaskCatalog.parse(raw)
        assertEquals(2, tasks.size)
        assertEquals("a", tasks[0].id)
        assertEquals("do b", tasks[1].prompt)
    }

    @Test
    fun skipsEntriesMissingIdOrPrompt() {
        val raw = """{"tasks":[{"id":"","prompt":"x"},{"id":"ok","prompt":""},{"id":"keep","prompt":"go"}]}"""
        val tasks = EvalTaskCatalog.parse(raw)
        assertEquals(1, tasks.size)
        assertEquals("keep", tasks[0].id)
    }

    @Test
    fun malformedJsonReturnsEmpty() {
        assertTrue(EvalTaskCatalog.parse("not json").isEmpty())
        assertTrue(EvalTaskCatalog.parse("{}").isEmpty())
    }
}
