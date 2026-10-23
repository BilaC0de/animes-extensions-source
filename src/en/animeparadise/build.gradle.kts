plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeParadise"
    className = "AnimeParadise"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
