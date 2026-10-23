plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animenosub"
    className = "Animenosub"
    versionCode = 24
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "animestream"
    baseUrl = "https://animenosub.to"
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidmolyextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
}
