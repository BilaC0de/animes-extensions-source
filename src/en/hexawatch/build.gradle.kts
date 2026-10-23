plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HexaWatch"
    className = "HexaWatch"
    versionCode = 9
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
