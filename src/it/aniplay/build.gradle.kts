plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniPlay"
    className = "AniPlay"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("aniplay.co")
        path("/series/..*")
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
