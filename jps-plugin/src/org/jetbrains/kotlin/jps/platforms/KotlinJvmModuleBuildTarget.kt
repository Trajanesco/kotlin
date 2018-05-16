/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jps.platforms

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.io.URLUtil
import org.jetbrains.jps.ModuleChunk
import org.jetbrains.jps.incremental.CompileContext
import org.jetbrains.jps.incremental.ModuleBuildTarget
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.module.JpsSdkDependency
import org.jetbrains.kotlin.build.JvmSourceRoot
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.compilerRunner.JpsCompilerEnvironment
import org.jetbrains.kotlin.compilerRunner.JpsKotlinCompilerRunner
import org.jetbrains.kotlin.config.IncrementalCompilation
import org.jetbrains.kotlin.jps.build.FSOperationsHelper
import org.jetbrains.kotlin.jps.build.KotlinBuilder
import org.jetbrains.kotlin.jps.build.KotlinChunkDirtySourceFilesHolder
import org.jetbrains.kotlin.jps.model.k2JvmCompilerArguments
import org.jetbrains.kotlin.jps.model.kotlinCompilerSettings
import org.jetbrains.kotlin.modules.KotlinModuleXmlBuilder
import java.io.File
import java.io.IOException

class KotlinJvmModuleBuildTarget(compileContext: CompileContext, jpsModuleBuildTarget: ModuleBuildTarget) :
    KotlinModuleBuilderTarget(compileContext, jpsModuleBuildTarget) {

    override fun compileModuleChunk(
        allCompiledFiles: MutableSet<File>,
        chunk: ModuleChunk,
        commonArguments: CommonCompilerArguments,
        dirtyFilesHolder: KotlinChunkDirtySourceFilesHolder,
        environment: JpsCompilerEnvironment,
        fsOperations: FSOperationsHelper
    ): Boolean {
        if (chunk.modules.size > 1) {
            environment.messageCollector.report(
                CompilerMessageSeverity.STRONG_WARNING,
                "Circular dependencies are only partially supported. The following modules depend on each other: "
                        + chunk.modules.joinToString(", ") { it.name } + " "
                        + "Kotlin will compile them, but some strange effect may happen"
            )
        }

        val filesSet = dirtyFilesHolder.dirtyFiles
        allCompiledFiles.addAll(filesSet)

        val moduleFile = generateModuleDescription(context, chunk, dirtyFilesHolder)
        if (moduleFile == null) {
            if (KotlinBuilder.LOG.isDebugEnabled) {
                KotlinBuilder.LOG.debug(
                    "Not compiling, because no files affected: " + chunk.targets.joinToString { it.presentableName }
                )
            }

            // No Kotlin sources found
            return false
        }

        val module = chunk.representativeTarget().module

        if (KotlinBuilder.LOG.isDebugEnabled) {
            val totalRemovedFiles = dirtyFilesHolder.removedFilesCount
            KotlinBuilder.LOG.debug("Compiling to JVM ${filesSet.size} files"
                                            + (if (totalRemovedFiles == 0) "" else " ($totalRemovedFiles removed files)")
                                            + " in " + chunk.targets.joinToString { it.presentableName })
        }

        try {
            val compilerRunner = JpsKotlinCompilerRunner()
            compilerRunner.runK2JvmCompiler(
                commonArguments,
                module.k2JvmCompilerArguments,
                module.kotlinCompilerSettings,
                environment,
                moduleFile
            )
        } finally {
            if (System.getProperty("kotlin.jps.delete.module.file.after.build") != "false") {
                moduleFile.delete()
            }
        }

        return true
    }

    fun generateModuleDescription(
        context: CompileContext,
        chunk: ModuleChunk,
        dirtyFilesHolder: KotlinChunkDirtySourceFilesHolder // ignored for non-incremental compilation
    ): File? {
        val builder = KotlinModuleXmlBuilder()

        var noSources = true

        val targets = chunk.targets.mapNotNull { this.context.kotlinBuildTargets[it] as? KotlinJvmModuleBuildTarget }

        val outputDirs = targets.map { it.outputDir }.toSet()

        val logger = context.loggingManager.projectBuilderLogger
        for (target in targets) {
            val outputDir = target.outputDir
            val friendDirs = target.friendOutputDirs

            val jpsModuleTarget = target.jpsModuleBuildTarget
            val moduleSources =
                if (IncrementalCompilation.isEnabled()) {
                    // todo: add all required files with correspond `expected by`/`actual` declarations
                    dirtyFilesHolder.getDirtyFiles(jpsModuleTarget)
                } else target.sourceFiles

            val hasRemovedSources = dirtyFilesHolder.getRemovedFiles(jpsModuleTarget).isNotEmpty()
            if (moduleSources.isNotEmpty() || hasRemovedSources) {
                noSources = false

                if (logger.isEnabled) {
                    logger.logCompiledFiles(moduleSources, KotlinBuilder.KOTLIN_BUILDER_NAME, "Compiling files:")
                }
            }

            val kotlinModuleId = target.targetId
            builder.addModule(
                kotlinModuleId.name,
                outputDir.absolutePath,
                moduleSources,
                target.findSourceRoots(context),
                target.findClassPathRoots(),
                target.findModularJdkRoot(),
                kotlinModuleId.type,
                isTests,
                // this excludes the output directories from the class path, to be removed for true incremental compilation
                outputDirs,
                friendDirs
            )
        }

        if (noSources) return null

        val scriptFile = createTempFileForModuleDesc(chunk)
        FileUtil.writeToFile(scriptFile, builder.asText().toString())
        return scriptFile
    }

    private fun createTempFileForModuleDesc(chunk: ModuleChunk): File {
        val readableSuffix = buildString {
            append(StringUtil.sanitizeJavaIdentifier(chunk.representativeTarget().module.name))
            if (chunk.containsTests()) {
                append("-test")
            }
        }
        val dir = System.getProperty("kotlin.jps.dir.for.module.files")?.let { File(it) }?.takeIf { it.isDirectory }
        return try {
            File.createTempFile("kjps", readableSuffix + ".script.xml", dir)
        } catch (e: IOException) {
            // sometimes files cannot be created, because file name is too long (Windows, Mac OS)
            // see https://bugs.openjdk.java.net/browse/JDK-8148023
            try {
                File.createTempFile("kjps", ".script.xml", dir)
            } catch (e: IOException) {
                val message = buildString {
                    append("Could not create module file when building chunk $chunk")
                    if (dir != null) {
                        append(" in dir $dir")
                    }
                }
                throw RuntimeException(message, e)
            }
        }
    }

    fun findClassPathRoots(): Collection<File> {
        return allDependencies.classes().roots.filter { file ->
            if (!file.exists()) {
                val extension = file.extension

                // Don't filter out files, we want to report warnings about absence through the common place
                if (!(extension == "class" || extension == "jar")) {
                    return@filter false
                }
            }

            true
        }
    }

    fun findModularJdkRoot(): File? {
        // List of paths to JRE modules in the following format:
        // jrt:///Library/Java/JavaVirtualMachines/jdk-9.jdk/Contents/Home!/java.base
        val urls = JpsJavaExtensionService.dependencies(module)
            .satisfying { dependency -> dependency is JpsSdkDependency }
            .classes().urls

        val url = urls.firstOrNull { it.startsWith(StandardFileSystems.JRT_PROTOCOL_PREFIX) } ?: return null

        return File(url.substringAfter(StandardFileSystems.JRT_PROTOCOL_PREFIX).substringBeforeLast(URLUtil.JAR_SEPARATOR))
    }

    private fun findSourceRoots(context: CompileContext): List<JvmSourceRoot> {
        val roots = context.projectDescriptor.buildRootIndex.getTargetRoots(jpsModuleBuildTarget, context)
        val result = ContainerUtil.newArrayList<JvmSourceRoot>()
        for (root in roots) {
            val file = root.rootFile
            val prefix = root.packagePrefix
            if (file.exists()) {
                result.add(JvmSourceRoot(file, if (prefix.isEmpty()) null else prefix))
            }
        }
        return result
    }
}