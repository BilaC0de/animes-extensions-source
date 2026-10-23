plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "FanPelis"
    className = "FanPelis"
    versionCode = 27
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:doodextractor"))
}
