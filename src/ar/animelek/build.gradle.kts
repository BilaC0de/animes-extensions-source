plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeLek"
    className = "AnimeLek"
    versionCode = 42
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:vidbomextractor"))
}
