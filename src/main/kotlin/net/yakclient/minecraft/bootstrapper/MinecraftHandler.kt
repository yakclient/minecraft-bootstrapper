package net.yakclient.minecraft.bootstrapper

import com.durganmcbroom.jobs.*
import net.bytebuddy.agent.ByteBuddyAgent
import net.yakclient.archives.ArchiveHandle
import net.yakclient.archives.Archives
import net.yakclient.archives.mixin.MixinInjection
import net.yakclient.archives.transform.AwareClassWriter
import net.yakclient.archives.transform.TransformerConfig
import net.yakclient.common.util.readInputStream
import org.objectweb.asm.ClassReader
import java.lang.instrument.ClassDefinition
import java.nio.file.Path
import net.yakclient.archives.transform.TransformerConfig.Companion.plus

public data class MixinMetadata<T: MixinInjection.InjectionData>(
    val data: T,
    val injection: MixinInjection<T>
)


public class MinecraftHandler<T : MinecraftReference>(
    public val version: String,
    private val cache: Path,
    public val provider: MinecraftProvider<T>,
    private val args: Array<String>,
    private val applyBasicArgs: Boolean
) {
    public lateinit var minecraftReference: T
        private set
    private lateinit var handle: MinecraftHandle
    public val archive: ArchiveHandle by lazy {handle.archive}
    public val archiveDependencies: List<ArchiveHandle> by lazy { handle.libraries }
    public var isLoaded: Boolean = false
        private set
    public var hasStarted: Boolean = false
        private set

    private val instrumentation = ByteBuddyAgent.install()

    private val updatedMixins: MutableSet<String> = HashSet()
    private val mixins: MutableMap<String, MutableList<MixinMetadata<*>>> = HashMap()

    internal suspend fun loadReference() : JobResult<Unit, Throwable> = jobCatching(JobName("Setup minecraft reference")) {
         val r = provider.getReference(version, cache)
             .attempt()

        minecraftReference = r
    }

    public fun loadMinecraft(parent: ClassLoader) {
        check(!isLoaded) { "Minecraft is already loaded" }
        handle = provider.get(minecraftReference, parent)
    }

    public fun startMinecraft() {
        check(!hasStarted) { "Minecraft has already started" }
        hasStarted = true

        val actualArgs =  if (applyBasicArgs) {
            args + arrayOf(
                "--assetsDir",
                minecraftReference.runtimeInfo.assetsPath.toString(),
                "--assetIndex",
                minecraftReference.runtimeInfo.assetsName,
                "--gameDir",
                minecraftReference.runtimeInfo.gameDir.toString(),
                "--version",
                version,
            )
        } else args

        handle.start(actualArgs)
    }

    internal fun shutdownMinecraft() {
        check(hasStarted) {"Minecraft is not running!"}
        handle.shutdown()
        hasStarted = false
    }

    public fun registerMixin(to: String, metadata: MixinMetadata<*>) {
        check(
            minecraftReference.archive.reader.contains(
                to.replace(
                    '.',
                    '/'
                ) + ".class"
            )
        ) { "Class '$to' does not exist." }

        val injects = mixins[to] ?: ArrayList<MixinMetadata<*>>().also { mixins[to] = it }
        injects.add(metadata)

        updatedMixins.add(to)
    }

    public fun writeAll() {
        val mixins: Set<Map.Entry<String, MutableList<MixinMetadata<*>>>> =
            mixins.filter { updatedMixins.contains(it.key) }.entries

        val toWrite = mixins.map { (to, all: MutableList<MixinMetadata<*>>) ->
            all.map {(data, injection) ->
                (injection as MixinInjection<MixinInjection.InjectionData>).apply(data)
            }.reduce { acc: TransformerConfig, t: TransformerConfig.Mutable ->
                acc + t
            } to to
        }

        toWrite.forEach { (config, to) ->
            val entry = minecraftReference
                .archive
                .reader["${to.replace('.', '/')}.class"]
            val bytes = entry
                ?.resource
                ?.open()
                ?.readInputStream()
                ?: throw IllegalArgumentException("Failed to inject into class '$to' because it does not exist!")

            if (!isLoaded) {
                minecraftReference.archive.writer.put(
                    entry.transform(
                        config, minecraftReference.libraries
                    )
                )
            } else {
                instrumentation.redefineClasses(
                    ClassDefinition(
                        handle.archive.classloader.loadClass(to),
                        Archives.resolve(
                            ClassReader(bytes),
                            config,
                            AwareClassWriter(minecraftReference.libraries + minecraftReference.archive, Archives.WRITER_FLAGS)
                        )
                    )
                )
            }
        }
    }
}