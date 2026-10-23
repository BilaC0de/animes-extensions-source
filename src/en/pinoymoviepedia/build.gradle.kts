plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "PinoyMoviePedia"
    className = "PinoyMoviePedia"
    versionCode = 7
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://pinoymoviepedia.ru"
}

dependencies {
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:doodextractor"))
}
