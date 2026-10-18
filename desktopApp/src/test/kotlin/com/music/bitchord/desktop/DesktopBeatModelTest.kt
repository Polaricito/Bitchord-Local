package com.music.bitchord.desktop

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.music.bitchord.playback.smart.BeatTracker
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Whether the Beat This! */
class DesktopBeatModelTest {

    @Test
    fun `the beat model loads in this build`() {
        DesktopAnalysisRuntime.ensureStarted()
        if (!DesktopAnalysisRuntime.available) {
            println("analysis library unavailable — skipping")
            return
        }
        val model = File(DesktopAnalysisRuntime.modelPath(BeatTracker.MODEL_ASSET))
        assertTrue(model.isFile && model.length() > 0, "the model was not unpacked")

        val outcome = runCatching {
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            OrtEnvironment.getEnvironment().createSession(model.absolutePath, options).use { session ->
                session.inputNames.first()
            }
        }
        val failure = outcome.exceptionOrNull()
        failure?.let { println("beat model load failed: ${it.message}") }

        // Whether the runtime's own native library will start is a property of the
        // CPU, not of the build: ONNX Runtime 1.21 and up fail to initialise on
        // anything older than AVX512, and the 1.20 that does load there has no
        // kernel for these int8 models. Nothing in this repository changes that, so
        // a machine that cannot run the analyser is reported and skipped rather than
        // failed - the same way a library that is not in the build is handled above.
        // A model missing from the build, by contrast, is a real failure, and is
        // still asserted above.
        //
        // All three shapes are recognised because only the first one carries the
        // cause. The attempt that trips the static initialiser throws
        // ExceptionInInitializerError wrapping the UnsatisfiedLinkError; every
        // attempt after that throws a bare NoClassDefFoundError with no cause at
        // all. Which one comes out depends on whether anything else in the JVM
        // touched OrtSession first, so a test that only handles one of the two
        // passes on a clean run and fails on a re-run - the worst way round.
        val runtimeCannotStart = failure != null && (
            generateSequence(failure) { it.cause }.any { it is UnsatisfiedLinkError } ||
                failure is ExceptionInInitializerError ||
                failure is NoClassDefFoundError
            )
        if (runtimeCannotStart) {
            println("ONNX Runtime will not initialise on this CPU - skipping")
            return
        }

        assertTrue(
            outcome.isSuccess,
            "the beat model would not load, so Automix has no meter: ${failure?.message}",
        )
    }
}
