package net.yakclient.minecraft.bootstrapper

import net.yakclient.archive.mapper.ArchiveMapping
import net.yakclient.archives.ArchiveReference
import net.yakclient.archives.ArchiveTree
import java.nio.file.Path

public interface MinecraftReference {
    public val version: String
    public val archive: ArchiveReference
    public val mappings: ArchiveMapping
    public val libraries: List<ArchiveTree>
    public val runtimeInfo: GameRuntimeInfo

    public data class GameRuntimeInfo(
        public val assetsPath: Path,
        public val assetsName: String,
        public val gameDir: Path,
    )
}
