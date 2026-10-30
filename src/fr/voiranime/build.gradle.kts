plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Voiranime"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "fr"
        baseUrl = "https://voir-anime.to"
    }

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
