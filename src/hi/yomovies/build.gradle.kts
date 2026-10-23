plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "YoMovies"
    className = "YoMovies"
    versionCode = 36
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:playlistutils"))
}
