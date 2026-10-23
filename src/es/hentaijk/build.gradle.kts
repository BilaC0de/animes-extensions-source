plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hentaijk"
    className = "Hentaijk"
    versionCode = 22
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:okruextractor"))
}
