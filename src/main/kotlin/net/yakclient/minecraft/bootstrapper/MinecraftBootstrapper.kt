package net.yakclient.minecraft.bootstrapper

import com.durganmcbroom.artifact.resolver.simple.maven.HashType
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenArtifactRequest
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenDescriptor
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenRepositorySettings
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import net.yakclient.boot.AppInstance
import net.yakclient.boot.Boot
import net.yakclient.boot.BootApplication
import net.yakclient.boot.maven.MavenDataAccess
import net.yakclient.boot.store.CachingDataStore
import net.yakclient.common.util.make
import net.yakclient.common.util.resolve
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path


private enum class MinecraftRepositoryType(
    val settingsProvider: (String) -> SimpleMavenRepositorySettings,
) {
    DEFAULT({
        SimpleMavenRepositorySettings.default(it, preferredHash = HashType.SHA1)
    }),
    LOCAL({
        SimpleMavenRepositorySettings.local(path = it, preferredHash = HashType.SHA1)
    })
}

public class MinecraftBootstrapper : BootApplication {
    override fun newInstance(args: Array<String>): AppInstance {
        val parser = ArgParser("minecraft-bootstrap", skipExtraArguments = true)

        val version by parser.option(ArgType.String, "minecraft-version").required()
        val gameProviderRepository by parser.option(ArgType.String, "game-provider-repo").required()
        val gameProviderRepositoryType by parser.option(
            ArgType.Choice<MinecraftRepositoryType>(),
            "game-provider-repo-type"
        ).required()
        val gameCachePath by parser.option(ArgType.String, "game-cache-path").required()

        parser.parse(args)

        val handler = MinecraftProviderHandler(
            { request, resource ->
                val descriptor by request::descriptor

                val jarName = "${descriptor.artifact}-${descriptor.version}.jar"
                val jarPath = Path.of(gameCachePath) resolve descriptor.group.replace(
                    '.',
                    File.separatorChar
                ) resolve descriptor.artifact resolve descriptor.version resolve jarName

                if (!Files.exists(jarPath)) {
                    Channels.newChannel(resource.open()).use { cin ->
                        jarPath.make()
                        FileOutputStream(jarPath.toFile()).use { fout ->
                            fout.channel.transferFrom(cin, 0, Long.MAX_VALUE)
                        }
                    }
                }

                jarPath
            },
            CachingDataStore(MavenDataAccess(Path.of(gameCachePath))),
            Boot.maven,
        ) {
            val descriptor =
                SimpleMavenDescriptor("net.yakclient.minecraft", "minecraft-provider-$it", "1.0-SNAPSHOT", null)

            SimpleMavenArtifactRequest(
                descriptor,
                includeScopes = setOf("compile", "runtime", "import"),
            )
        }

        val provider = handler.get(version, gameProviderRepositoryType.settingsProvider(gameProviderRepository))

        return provider.get(version, Path.of(gameCachePath))
    }
}