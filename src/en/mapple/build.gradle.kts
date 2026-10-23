plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Mapple"
    className = "Mapple"
    versionCode = 8
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
