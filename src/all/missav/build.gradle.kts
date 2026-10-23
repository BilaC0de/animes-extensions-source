plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "MissAV"
    className = "MissAV"
    versionCode = 33
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:javcoverfetcher"))
    implementation(project(":lib:m3u8server"))
}
