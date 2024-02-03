package net.yakclient.minecraft.bootstrapper

import com.durganmcbroom.artifact.resolver.ArtifactMetadata
import com.durganmcbroom.artifact.resolver.ArtifactRequest
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenArtifactRequest
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenDescriptor
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenRepositorySettings
import com.durganmcbroom.jobs.*
import net.yakclient.archives.ArchiveHandle
import net.yakclient.archives.ArchiveReference
import net.yakclient.archives.Archives
import net.yakclient.archives.ClassLoaderProvider
import net.yakclient.archives.zip.ZipResolutionResult
import net.yakclient.archives.zip.classLoaderToArchive
import net.yakclient.boot.archive.ArchiveException
import net.yakclient.boot.archive.ArchiveGraph
import net.yakclient.boot.archive.ArchiveResolutionProvider
import net.yakclient.boot.archive.ArchiveTrace
import net.yakclient.boot.loader.ArchiveClassProvider
import net.yakclient.boot.loader.ArchiveSourceProvider
import net.yakclient.boot.loader.DelegatingClassProvider
import net.yakclient.boot.loader.IntegratedLoader
import net.yakclient.boot.maven.MavenDependencyResolver
import net.yakclient.boot.security.PrivilegeAccess
import net.yakclient.boot.security.PrivilegeManager
import net.yakclient.boot.security.SecureSourceDefiner
import java.nio.file.Path
import java.util.*

private const val PROPERTY_FILE_LOCATION = "META-INF/minecraft-provider.properties"

private const val MINECRAFT_PROVIDER_CN = "provider-name"

public class MinecraftHandlerDependencyResolver(
    internal val repository: SimpleMavenRepositorySettings,
//    privilegeManager: PrivilegeManager = PrivilegeManager(null, PrivilegeAccess.allPrivileges()) {},
) : MavenDependencyResolver(
    parentClassLoader = MinecraftBootstrapper::class.java.classLoader,
//    resolutionProvider = object : ArchiveResolutionProvider<ZipResolutionResult> {
//        override suspend fun resolve(
//            resource: Path,
//            classLoader: ClassLoaderProvider<ArchiveReference>,
//            parents: Set<ArchiveHandle>
//        ): JobResult<ZipResolutionResult, ArchiveException> = jobScope {
//            val ref = Archives.Finders.ZIP_FINDER.find(resource)
//            val cl = IntegratedLoader(
//                name = "",
//                DelegatingClassProvider(parents.map(::ArchiveClassProvider)),
//                ArchiveSourceProvider(ref),
//                SecureSourceDefiner(privilegeManager, resource.toUri()),
//                MinecraftBootstrapper::class.java.classLoader
//            )
//
//            val run = runCatching {
//                Archives.resolve(
//                    ref,
//                    cl,
//                    Archives.Resolvers.ZIP_RESOLVER,
//                    (parents + classLoaderToArchive(MinecraftBootstrapper::class.java.classLoader))
//                )
//            }
//
//            if (run.isSuccess) run.getOrNull()!!
//            else fail(
//                ArchiveException.ArchiveLoadFailed(
//                    run.exceptionOrNull()!!.message ?: "Failed to load archive: '$resource'. ", jobElement(ArchiveTrace)
//                )
//            )
//        }
//    },
//privilegeManager = privilegeManager
)

public suspend fun ArchiveGraph.loadProvider(
    descriptor: SimpleMavenDescriptor,
    resolver: MinecraftHandlerDependencyResolver
): JobResult<MinecraftProvider<*>, ArchiveException> = job(JobName("Load minecraft provider: '${descriptor.name}'")) {
//    if (!resolver.isCached(descriptor)) cache(
//        SimpleMavenArtifactRequest(
//            descriptor,
//            includeScopes = setOf("compile", "runtime", "import")
//        ),
//        resolver.repository
//    ).attempt()
//
//    val archive = load(descriptor).attempt().archive ?: throw IllegalArgumentException("Could not cache or get minecraft provider: '${descriptor.name}'")
    cache(
        SimpleMavenArtifactRequest(descriptor, includeScopes = setOf("compile", "runtime", "import")),
        resolver.repository,
        resolver
    ).attempt()
    val archive = get(descriptor, resolver).attempt().archive
        ?: fail(
            ArchiveException.IllegalState(
                "Minecraft provider has no archive! ('${descriptor}')",
                ArchiveTrace(descriptor, null)
            )
        )

//    val archive = get(
//        descriptor,
//        {
//            SimpleMavenArtifactRequest(
//                it,
//                includeScopes = setOf("compile", "runtime", "import")
//            )
//        },
//        resolver
//    ) {
//        resolver.repository
//    }.attempt().archive ?: fail(ArchiveException.IllegalState("Minecraft provider has no archive! ('${descriptor}')"))

    val properties =
        checkNotNull(archive.classloader.getResourceAsStream(PROPERTY_FILE_LOCATION)) { "Failed to find Minecraft Provider properties file in given archive." }.use {
            Properties().apply { load(it) }
        }

    val providerClassName = properties.getProperty(MINECRAFT_PROVIDER_CN)
        ?: throw IllegalStateException("Invalid minecraft-provider app class name.")

    val clazz = archive.classloader.loadClass(providerClassName)

    clazz.getConstructor().newInstance() as? MinecraftProvider<*>
        ?: throw IllegalStateException("Loaded provider class, but type is not a MinecraftProvider!")
}

//public class MinecraftProviderHandler<T : ArtifactMetadata.Descriptor, R : ArtifactRequest<T>, S : RepositorySettings>(
//        val path: Path
////        private val archiveWriter: (req: T, resource: SafeResource) -> Path,
////        private val store: DataStore<T, DependencyData<T>>,
////        private val dependencyGraph: DependencyGraph<T, S>,
////        private val requestBuilder: (version: String) -> R,
//) {
//    private val archiveProvider = BasicArchiveResolutionProvider(
//            Archives.Finders.ZIP_FINDER as ArchiveFinder<ArchiveReference>,
//            Archives.Resolvers.ZIP_RESOLVER
//    )
//
//    public fun get(version: String, settings: S): MinecraftProvider<*> {
//        val req: R = requestBuilder(version)
//
//        val data = store[req.descriptor] ?: run {
//            @Suppress("UNCHECKED_CAST")
//            val repositoryFactory =
//                    dependencyGraph.repositoryFactory as RepositoryFactory<S, R, ArtifactStub<R, *>, ArtifactReference<*, ArtifactStub<R, *>>, ArtifactRepository<R, ArtifactStub<R, *>, *>>
//
//            val repository = repositoryFactory.createNew(settings)
//
//            val ref = repository.get(req).fold(
//                    { throw IllegalArgumentException("Failed to load minecraft provider for version: '$version'. Error was: '${(it)}'") },
//                    ::identity
//            )
//
//            ref.children.forEach {
//                for (s in it.candidates) {
//                    val candidateSettings =
//                            (repository.stubResolver.repositoryResolver as RepositoryStubResolver<RepositoryStub, S>).resolve(
//                                    s
//                            ).orNull() ?: continue
//
//                    if (dependencyGraph.cacherOf(candidateSettings).cache(it.request as R).isRight()) break
//                }
//            }
//
//            val jarPath = archiveWriter(
//                    req,
//                    checkNotNull(ref.metadata.resource) { "Archive cannot be null for provider version: '$version'." }.toSafeResource()
//            )
//
//            val value = DependencyData(
//                    req,
//                    jarPath,
//                    @Suppress("UNCHECKED_CAST")
//                    ref.children.map { it.request as T }
//            )
//            store.put(req, value)
//
//            value
//        }
//
//        val children = data.children
//                .map(dependencyGraph::get)
//                .map {
//                    it.orNull()
//                            ?: throw IllegalStateException("Failed to load dependency of minecraft version: '${(it as Either.Left).value}. This means your dependency graph is invalidated, please delete the game cache and restart.")
//                }
//
//        val resource = checkNotNull(data.archive) { "Archive cannot be null for provider version: '$version'." }
//
//        val parents =
//                children.flatMapTo(HashSet()) { it.handleOrChildren() } + classLoaderToArchive(this::class.java.classLoader)
//        val archive = archiveProvider.resolve(
//                resource,
//                {
//                    IntegratedLoader(
//                            sp = ArchiveSourceProvider(it),
//                            parent = this::class.java.classLoader,
//                            cp = DelegatingClassProvider(
//                                    parents.map(::ArchiveClassProvider)
//                            )
//                    )
//                },
//                parents
//        )
//                .fold({ throw it }, ::identity).archive
//
//        val properties =
//                checkNotNull(archive.classloader.getResourceAsStream(PROPERTY_FILE_LOCATION)) { "Failed to find Minecraft Provider properties file in given archive." }.use {
//                    Properties().apply { load(it) }
//                }
//
//        val providerClassName = properties.getProperty(MINECRAFT_PROVIDER_CN)
//                ?: throw IllegalStateException("Invalid minecraft-provider app class name.")
//
//        val clazz = archive.classloader.loadClass(providerClassName)
//
//        return clazz.getConstructor().newInstance() as? MinecraftProvider<*>
//                ?: throw IllegalStateException("Loaded provider class, but type is not a MinecraftProvider!")
//    }
//}

public data class MinecraftProviderRequest(
    override val descriptor: MinecraftProviderDescriptor,
) : ArtifactRequest<MinecraftProviderDescriptor>

public data class MinecraftProviderDescriptor(
    val version: String,
) : ArtifactMetadata.Descriptor {
    override val name: String by ::version
}