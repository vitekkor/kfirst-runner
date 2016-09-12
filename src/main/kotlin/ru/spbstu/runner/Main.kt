package ru.spbstu.runner

/**
 * Created by akhin on 8/15/16.
 */

import common.TestFailureException
import org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage
import org.junit.platform.engine.support.descriptor.JavaMethodSource
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import ru.spbstu.kotlin.generate.context.Gens
import ru.spbstu.runner.data.TestData
import ru.spbstu.runner.data.TestDatum
import ru.spbstu.runner.util.ConsoleReportListener
import ru.spbstu.runner.util.CustomContextClassLoaderExecutor
import ru.spbstu.runner.util.GoogleApiFacade
import ru.spbstu.runner.util.TestReportListener
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.time.format.DateTimeFormatter
import java.util.*

fun main(args: Array<String>) {
    val classpathRoots = sequenceOf(args[0])
            .flatMap { sequenceOf(URL("$it/classes/"), URL("$it/test-classes/")) }
            .toList()
    val packages = args.drop(1)

    // TODO: Support for nested packages

    val author = File("author.results").readText()
    val owner = File("owner.results").readText()

    println("Classpath roots: ${classpathRoots.joinToString()}")
    println("Test packages: ${packages.joinToString()}")

    CustomContextClassLoaderExecutor(
            URLClassLoader(
                    classpathRoots.toTypedArray(),
                    Thread.currentThread().contextClassLoader
            )
    ).invoke {

        val request = LauncherDiscoveryRequestBuilder
                .request()
                .selectors(
                        packages.map { selectPackage(it) }
                )
                .build()

        val testReport = TestReportListener()
        val consoleReport = ConsoleReportListener()

        val seed = System.getenv("RANDOM_SEED")?.toLong() ?: Gens.random.nextLong()
        Gens.random.setSeed(seed)

        LauncherFactory
                .create()
                .apply {
                    registerTestExecutionListeners(testReport)
                    registerTestExecutionListeners(consoleReport)
                }
                .execute(request)

        System.out.println(testReport.testData)

        val allTests = testReport.testData
                .entries
                .groupBy { e ->
                    e.key.source.map { s ->
                        when (s) {
                            is JavaMethodSource -> {
                                s.javaClass.`package`.name
                            }
                            else -> ""
                        }
                    }.orElse("")
                }

        val tags = listOf(
                "Example",
                "Trivial",
                "Easy",
                "Normal",
                "Hard",
                "Impossible",
                "No tag"
        )

        for (pkg in packages) {
            val pkgTests = allTests[pkg] ?: continue

            val methodTests = pkgTests.groupBy { e ->
                e.key.source.map { s ->
                    when (s) {
                        is JavaMethodSource -> {
                            s.javaMethodName
                        }
                        else -> ""
                    }
                }.orElse("")
            }

            val testData = methodTests.map { e ->
                TestDatum(
                        pkg,
                        e.key,
                        e.value.flatMap { t -> t.key.tags }
                                .map { t -> t.name }
                                .toSet(),
                        e.value.map { t -> t.value }
                                .filter { r ->
                                    !r.throwable.isPresent ||
                                            r.throwable.filter { it !is NotImplementedError }.isPresent
                                }
                )
            }.let { TestData(it) }

            if (testData.all { "Example" in it.tags }) continue

            val data = mutableListOf<Any>()

            data.add(DateTimeFormatter.ISO_INSTANT.format(Date().toInstant()))
            data.add(author)
            data.add(owner)

            for (tag in tags) {
                data.add(testData.tagged(tag).succeeded.size)
            }

            File("$pkg.results").writer().use { writer ->
                writer.appendln("Author: $author")
                writer.appendln()

                writer.appendln("Owner: $owner")
                writer.appendln()

                writer.appendln("Total: ${testData.succeeded.size} / ${testData.size}")
                writer.appendln()

                for (tag in tags) {
                    val tagged = testData.tagged(tag)
                    if (0 != tagged.size) {
                        writer.appendln("$tag: ${tagged.succeeded.size} / ${tagged.size}")
                    }
                }
                writer.appendln()

                if (0 != testData.succeeded.size) {
                    writer.appendln("Succeeded:")
                    testData.succeeded.forEach {
                        writer.appendln("* ${it.tags} ${it.packageName}/${it.methodName}")
                    }
                }
                writer.appendln()

                if (0 != testData.failed.size) {
                    writer.appendln("Failed:")
                    testData.failed.forEach { t ->
                        t.exceptions.forEach { ex ->
                            writer.appendln("* ${t.tags} ${t.packageName}/${t.methodName}")

                            if (ex is TestFailureException) {
                                writer.appendln("    * Expected: ${ex.expectedOutput}")
                                writer.appendln("    * Actual: ${ex.output}")
                                writer.appendln("    * Inputs:")
                                ex.input.forEach {
                                    writer.appendln("        * ${it.key} -> ${it.value}")
                                }
                                writer.appendln("    * Exception: ${ex.inner}")
                            } else {
                                writer.appendln("    * ${ex.javaClass.name} : ${ex.message}")
                            }
                        }
                    }
                }
                writer.appendln()

                writer.appendln("Seed: $seed")
                writer.appendln()

                val invalidTags = testData.data.filter { it.tags.size > 1 }
                if (0 != invalidTags.size) {
                    writer.appendln("Funky tags:")
                    invalidTags.forEach {
                        writer.appendln("* ${it.tags} ${it.packageName}/${it.methodName}")
                    }
                }
            }

            GoogleApiFacade.createSheet(pkg)

            GoogleApiFacade.appendToSheet(pkg, data.map { it.toString() })

        }

    }
}
