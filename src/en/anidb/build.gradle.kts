plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniDB"
    className = "AniDB"
    versionCode = 8
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    deeplink {
        host("anidb.app")
        path("/anime/.*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
