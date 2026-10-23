plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Cimaleek"
    className = "Cimaleek"
    versionCode = 13
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
