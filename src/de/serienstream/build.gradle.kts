plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Serienstream"
    className = "Serienstream"
    versionCode = 36
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
}
