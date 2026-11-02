import io.papermc.paperweight.userdev.PaperweightUserDependenciesExtension

plugins {
    id("buildlogic.adapter-mojmap")
}

/*
 * This adapter is compiled against Folia's development bundle instead of Paper's.
 *
 * Folia's dev bundle (`dev.folia:dev-bundle:<version>`) contains the Folia/Moonrise patched server
 * classes on which this port depends: the regioniser (`ThreadedRegionizer`, `TickRegionScheduler`),
 * `TickThread` (region ownership checks) and the regionised chunk system. Compiling against Paper's
 * bundle would make those symbols unavailable and would let region-unsafe calls compile silently —
 * which is exactly the class of bug this fork exists to eliminate.
 */
val foliaVersion: String = (findProperty("foliaVersion") as String?) ?: "26.1.2.build.8-stable"

dependencies {
    the<PaperweightUserDependenciesExtension>().foliaDevBundle(foliaVersion)
}

logger.lifecycle("[$path] compiling against Folia dev bundle $foliaVersion")
