package com.memora.app.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

private const val KETTLE = "Zenbrew Electric Kettle 1.7L. Warranty period: 2 years from purchase. Purchased at Homeware Plus."

private fun libWithText(db: LocalDb = InMemoryLocalDb()): Pair<LocalLibrary, InMemoryLocalDb> {
    val d = db as InMemoryLocalDb
    val library = newLibrary(d)
    runBlocking { library.index(library.import("card.txt", "text/plain", ByteArrayInputStream(KETTLE.toByteArray()), "test").file.id) }
    return library to d
}

class AiStabilityTest {
    @Test fun failureKindsAreReportedAndNeverEscape() = runBlocking {
        val (_, db) = libWithText()
        for (kind in AiFailure.entries) {
            val engine = FakeEngine(failWith = LocalAiException("boom", kind = kind))
            val a = LocalAnswerGenerator(Bm25Index(db), engine).answer("warranty period kettle")
            assertFalse("a failed model must not be shown as a supported answer", a.sufficient)
            assertTrue(a.text.contains("boom"))
            assertTrue("matching files are still listed", a.sources.isNotEmpty())
            assertTrue(a.sources.none { it.cited })
        }
    }

    @Test fun oneEngineAnswersManyQuestionsWithoutBeingRebuilt() = runBlocking {
        val (_, db) = libWithText()
        val engine = FakeEngine("""{"answer":"Two years [1]","sufficient":true,"citations":[1]}""")
        val gen = LocalAnswerGenerator(Bm25Index(db), engine)
        repeat(3) { assertTrue(gen.answer("warranty period of the kettle").sufficient) }
        assertEquals(3, engine.prompts.size)
    }

    @Test fun noMatchingFilesNeverCallsTheModel() = runBlocking {
        val (_, db) = libWithText()
        val engine = FakeEngine("should not be used")
        val a = LocalAnswerGenerator(Bm25Index(db), engine).answer("xyzzy plugh")
        assertFalse(a.sufficient)
        assertTrue(engine.prompts.isEmpty())
    }

    @Test fun missingModelIsReportedHonestlyWithoutFakingAnAnswer() = runBlocking {
        val (_, db) = libWithText()
        val a = LocalAnswerGenerator(Bm25Index(db), engine = null).answer("warranty period kettle")
        assertFalse(a.sufficient)
        assertEquals(LocalAnswerGenerator.AI_NOT_READY, a.text)
    }

    @Test fun malformedModelOutputIsNotTrustedAsEvidence() = runBlocking {
        val (_, db) = libWithText()
        val a = LocalAnswerGenerator(Bm25Index(db), FakeEngine("{not json")).answer("warranty period kettle")
        assertFalse(a.sufficient)
        assertTrue(a.sources.none { it.cited })
    }

    /**
     * Regression for the crash that closed the app the moment the installed model was used: the release build's R8
     * renamed LiteRT-LM classes that its native code looks up by name (JNI "mid == null" abort). The keep rule must stay.
     */
    @Test fun releaseBuildKeepsTheLiteRtLmClassesItsNativeCodeLooksUp() {
        val rules = listOf("proguard-rules.pro", "app/proguard-rules.pro").map(::File).first { it.isFile }.readText()
        assertTrue(rules.contains("-keep class com.google.ai.edge.litertlm.** { *; }"))
    }

    /** Regression: WorkManager's Room database is built by reflection; without this rule the release app crashed on launch. */
    @Test fun releaseBuildKeepsWorkManagerDatabaseConstructor() {
        val rules = listOf("proguard-rules.pro", "app/proguard-rules.pro").map(::File).first { it.isFile }.readText()
        assertTrue(rules.contains("-keep class androidx.work.impl.WorkDatabase_Impl { *; }"))
    }

    @Test fun failureEnumCoversEveryUiState() {
        assertNotNull(AiFailure.valueOf("NOT_INSTALLED"))
        assertEquals(
            setOf("NOT_INSTALLED", "UNSUPPORTED_FORMAT", "OUT_OF_MEMORY", "INIT_FAILED", "INFERENCE_FAILED"),
            AiFailure.entries.map { it.name }.toSet(),
        )
    }
}
