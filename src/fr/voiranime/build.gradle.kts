plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Voiranime"
    className = "Voiranime"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    deeplink {
        host("voir-anime.to")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:vidmolyextractor"))
    implementation(project(":lib:voeextractor"))
}
