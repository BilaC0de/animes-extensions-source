plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeGO.studio"
    className = "AnimeGo"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
